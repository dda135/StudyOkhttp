/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package okhttp3.internal.http;

import java.io.IOException;
import java.util.List;
import okhttp3.Cookie;
import okhttp3.CookieJar;
import okhttp3.Headers;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.internal.Version;
import okio.GzipSource;
import okio.Okio;

import static okhttp3.internal.Util.hostHeader;

/**
 * Bridges from application code to network code. First it builds a network request from a user
 * request. Then it proceeds to call the network. Finally it builds a user response from the network
 * response.
 */
public final class BridgeInterceptor implements Interceptor {
  private final CookieJar cookieJar;

  public BridgeInterceptor(CookieJar cookieJar) {
    this.cookieJar = cookieJar;
  }

  @Override public Response intercept(Chain chain) throws IOException {
    Request userRequest = chain.request();
    //在构建一个对应Call的时候首先要构建一个request，并且可以设置一些参数
    //通过构造器模式可以完成一些基础请求头等参数的补足
    //url：请求地址
    //method：请求方式（POST/GET）
    //body：请求内容（比方说数据、文件二进制等）
    //tag：当前request请求标记
    //headers：当前request已经配置的头部报文参数
    Request.Builder requestBuilder = userRequest.newBuilder();
    RequestBody body = userRequest.body();
    //如果有正文体，设置一些正文相关的头部报文信息
    if (body != null) {
      //注意此处是覆盖，这就意味着这些信息不需要在设置request的时候手动设置，设置了也可能被覆盖
      MediaType contentType = body.contentType();
      if (contentType != null) {//标记当前内容的媒体类型
        requestBuilder.header("Content-Type", contentType.toString());
      }

      long contentLength = body.contentLength();
      if (contentLength != -1) {
        requestBuilder.header("Content-Length", Long.toString(contentLength));
        requestBuilder.removeHeader("Transfer-Encoding");
      } else {//当无法获取传输内容的大小的时候，标记当前传输内容不定
        requestBuilder.header("Transfer-Encoding", "chunked");
        requestBuilder.removeHeader("Content-Length");
      }
    }
    //如果没有手动设置头部报文中的Host，则会采用设置的url中的域名
    //否则使用你手动设置的值，这个一般可以用于HttpDNS服务
    //在不修改域名的情况下改变Host进行IP直连访问，用于避开一些DNS服务异常的情况
    if (userRequest.header("Host") == null) {
      requestBuilder.header("Host", hostHeader(userRequest.url(), false));
    }
    //这个是用于标志长短连接的，一般close标志短连接，用于请求一些文档之类的，标志TCP可以很快进行4次握手结束连接
    //Keep-Alive表示长连接，在TCP进行完3次握手之后，这个过程中可以传输数据之类的，并不会很快进行4次握手结束连接
    //一个重要的区别就是是否用同一个TCP握手过程
    if (userRequest.header("Connection") == null) {
      //一般APP用的都是长连接哈
      requestBuilder.header("Connection", "Keep-Alive");
    }

    // If we add an "Accept-Encoding: gzip" header field we're responsible for also decompressing
    // the transfer stream.
    // 此处设置可以接受gzip格式的返回数据
    boolean transparentGzip = false;
    if (userRequest.header("Accept-Encoding") == null && userRequest.header("Range") == null) {
      transparentGzip = true;
      requestBuilder.header("Accept-Encoding", "gzip");
    }
    //尝试从存储中获取cookies
    List<Cookie> cookies = cookieJar.loadForRequest(userRequest.url());
    if (!cookies.isEmpty()) {//如果存有cookie，放入请求头部
      requestBuilder.header("Cookie", cookieHeader(cookies));
    }

    if (userRequest.header("User-Agent") == null) {
      requestBuilder.header("User-Agent", Version.userAgent());
    }
    //再次回调RealInterceptorChain的proceed方法
    Response networkResponse = chain.proceed(requestBuilder.build());
    //此处一般来说获取response成功
    //当请求完成之后，要尝试将cookie存入cookieJar当中
    HttpHeaders.receiveHeaders(cookieJar, userRequest.url(), networkResponse.headers());
    //关联当前响应和请求
    Response.Builder responseBuilder = networkResponse.newBuilder()
        .request(userRequest);

    if (transparentGzip
        && "gzip".equalsIgnoreCase(networkResponse.header("Content-Encoding"))
        && HttpHeaders.hasBody(networkResponse)) {//这里是处理Gzip返回格式的数据
      GzipSource responseBody = new GzipSource(networkResponse.body().source());
      Headers strippedHeaders = networkResponse.headers().newBuilder()
          .removeAll("Content-Encoding")
          .removeAll("Content-Length")
          .build();
      responseBuilder.headers(strippedHeaders);
      responseBuilder.body(new RealResponseBody(strippedHeaders, Okio.buffer(responseBody)));
    }

    return responseBuilder.build();
  }

  /** Returns a 'Cookie' HTTP request header with all cookies, like {@code a=b; c=d}.
   * cookie的结构："key1=value1; key2=value2; "
   * */
  private String cookieHeader(List<Cookie> cookies) {
    StringBuilder cookieHeader = new StringBuilder();
    for (int i = 0, size = cookies.size(); i < size; i++) {
      if (i > 0) {
        cookieHeader.append("; ");
      }
      Cookie cookie = cookies.get(i);
      cookieHeader.append(cookie.name()).append('=').append(cookie.value());
    }
    return cookieHeader.toString();
  }
}
