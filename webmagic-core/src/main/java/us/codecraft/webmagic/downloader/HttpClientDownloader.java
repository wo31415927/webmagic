package us.codecraft.webmagic.downloader;

import com.google.common.collect.Maps;

import org.apache.commons.io.IOUtils;
import org.apache.http.HttpResponse;
import org.apache.http.annotation.ThreadSafe;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.util.EntityUtils;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.Map;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import us.codecraft.webmagic.Page;
import us.codecraft.webmagic.Request;
import us.codecraft.webmagic.Response;
import us.codecraft.webmagic.Site;
import us.codecraft.webmagic.Task;
import us.codecraft.webmagic.proxy.Proxy;
import us.codecraft.webmagic.proxy.ProxyProvider;
import us.codecraft.webmagic.selector.PlainText;
import us.codecraft.webmagic.utils.CharsetUtils;
import us.codecraft.webmagic.utils.HttpClientUtils;

/**
 * The http downloader based on HttpClient.
 *
 * @author code4crafter@gmail.com <br>
 * @since 0.1.0
 */
@Slf4j
@ThreadSafe
@Getter
public class HttpClientDownloader extends AbstractDownloader {

  protected static final Map<Request.RequestResourceType, DownloadProcessStrategy> strategyMap =
      Maps.newHashMap();

  static {
    strategyMap.put(Request.RequestResourceType.HTML, new PageDownloadProcessStrategy());
    strategyMap.put(Request.RequestResourceType.IMG, new BinaryDownloadProcessStrategy());
  }

  protected final Map<String, CloseableHttpClient> httpClients =
      new HashMap<String, CloseableHttpClient>();

  protected HttpClientGenerator httpClientGenerator = new HttpClientGenerator();

  protected HttpUriRequestConverter httpUriRequestConverter = new HttpUriRequestConverter();

  protected ProxyProvider proxyProvider;

  protected boolean responseHeader = true;

  public void setHttpUriRequestConverter(HttpUriRequestConverter httpUriRequestConverter) {
    this.httpUriRequestConverter = httpUriRequestConverter;
  }

  public void setProxyProvider(ProxyProvider proxyProvider) {
    this.proxyProvider = proxyProvider;
  }

  public CloseableHttpClient getHttpClient(Site site) {
    if (site == null) {
      return httpClientGenerator.getClient(null);
    }
    String domain = site.getDomain();
    CloseableHttpClient httpClient = httpClients.get(domain);
    if (httpClient == null) {
      synchronized (this) {
        httpClient = httpClients.get(domain);
        if (httpClient == null) {
          httpClient = httpClientGenerator.getClient(site);
          httpClients.put(domain, httpClient);
        }
      }
    }
    return httpClient;
  }

  public HttpResponse http(Request request, Task task) throws IOException {
    if (task == null || task.getSite() == null) {
      throw new NullPointerException("task or site can not be null");
    }
    CloseableHttpClient httpClient = getHttpClient(task.getSite());
    Proxy proxy = proxyProvider != null ? proxyProvider.getProxy(task) : null;
    HttpClientRequestContext requestContext =
        httpUriRequestConverter.convert(request, task.getSite(), proxy);
    try {
      CloseableHttpResponse httpResponse =
          httpClient.execute(requestContext.getHttpUriRequest(), requestContext.getHttpClientContext());
      return httpResponse;
    } finally {
      if (proxyProvider != null && proxy != null) {
        //TODO 确定该函数的具体作用
        proxyProvider.returnProxy(proxy, null, task);
      }
    }
  }

  @Override
  public Response download(Request request, Task task) {
    Response response = new Response(request);
    HttpResponse httpResponse = null;
    try {
      httpResponse = http(request, task);
      response =
          strategyMap
              .get(request.getResourceType())
              .process(request, httpResponse, task, responseHeader);
      onSuccess(request);
      log.info("downloading resurce success {}", request.getUrl());
      return response;
    } catch (IOException e) {
      log.warn("download resource {} error", request.getUrl(), e);
      onError(request);
      //异常时返回null是否兼容
    } finally {
      //直接close即可还是需要使用此方式关闭
      if (httpResponse != null) {
        //ensure the connection is released back to pool
        EntityUtils.consumeQuietly(httpResponse.getEntity());
      }
    }
    return response;
  }

  @Override
  public void setThread(int thread) {
    httpClientGenerator.setPoolSize(thread);
  }

  private interface DownloadProcessStrategy {
    Response process(Request request, HttpResponse httpResponse, Task task, boolean responseHeader);
  }

  protected static class BinaryDownloadProcessStrategy implements DownloadProcessStrategy {
    @Override
    public Response process(
        Request request, HttpResponse httpResponse, Task task, boolean responseHeader) {
      Response response = new Response(request);
      try {
        response.setBytes(IOUtils.toByteArray(httpResponse.getEntity().getContent()));
      } catch (IOException e) {
        log.error("获取HttpResponse二进制数据失败", e);
      }
      return response;
    }
  }

  protected static class PageDownloadProcessStrategy implements DownloadProcessStrategy {
    protected String getHtmlCharset(String contentType, byte[] contentBytes) throws IOException {
      String charset = CharsetUtils.detectCharset(contentType, contentBytes);
      if (charset == null) {
        charset = Charset.defaultCharset().name();
        log.debug(
            "Charset autodetect failed, use {} as charset. Please specify charset in Site.setCharset()",
            Charset.defaultCharset());
      }
      return charset;
    }

    /**
     * 异常情况下返回Page
     *
     * @param request
     * @param httpResponse
     * @param task
     * @param responseHeader
     * @return
     */
    @Override
    public Response process(
        Request request, HttpResponse httpResponse, Task task, boolean responseHeader) {
      Response response = new Response(request);
      Page page = Page.fail();
      page.setUrl(new PlainText(request.getUrl()));
      page.setRequest(request);
      page.setStatusCode(httpResponse.getStatusLine().getStatusCode());
      byte[] bytes = new byte[0];
      String contentType =
          httpResponse.getEntity().getContentType() == null
              ? ""
              : httpResponse.getEntity().getContentType().getValue();
      String charset =
          request.getCharset() != null ? request.getCharset() : task.getSite().getCharset();
      try {
        bytes = IOUtils.toByteArray(httpResponse.getEntity().getContent());
        page.setBytes(bytes);
        if (!request.isBinaryContent()) {
          if (charset == null) {
            charset = getHtmlCharset(contentType, bytes);
          }
          page.setCharset(charset);
          page.setRawText(new String(bytes, charset));
        }
        if (responseHeader) {
          page.setHeaders(HttpClientUtils.convertHeaders(httpResponse.getAllHeaders()));
        }
        page.setDownloadSuccess(true);

        response.setBytes(bytes);
        response.setPage(page);

      } catch (IOException e) {
        log.error("由HttpResponse生成Page失败", e);
        response.setPage(Page.fail());
      }
      return response;
    }
  }
}
