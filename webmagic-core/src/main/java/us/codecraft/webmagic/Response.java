package us.codecraft.webmagic;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;

/** cctv 2017/8/28 */
@Getter
@Setter
@RequiredArgsConstructor
public class Response {
  protected final Request request;
  protected byte[] bytes;
  protected Page page;

  public Response(Request request, Page page) {
    this.request = request;
    this.page = page;
  }
}
