/*
 * Copyright (C) 2013 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package okhttp3.internal.http;

import java.net.HttpURLConnection;
import java.net.Proxy;
import okhttp3.HttpUrl;
import okhttp3.Request;

public final class RequestLine {
  private RequestLine() {
  }

  /**
   * Returns the request status line, like "GET / HTTP/1.1". This is exposed to the application by
   * {@link HttpURLConnection#getHeaderFields}, so it needs to be set even if the transport is
   * HTTP/2.
   */
  public static String get(Request request, Proxy.Type proxyType) {
    //此处是拼接请求报文起始行
    StringBuilder result = new StringBuilder();
    //POST
    result.append(request.method());
    result.append(' ');
    //默认是NO_PROXY
    if (includeAuthorityInRequestLine(request, proxyType)) {
      result.append(request.url());
    } else {
      //一般是此处，Host+参数的格式
      result.append(requestPath(request.url()));
    }
    //最后拼接协议，注意有空格
    result.append(" HTTP/1.1");
    //总结一下请求报文的起始行格式，比方说请求http://www.hh.com?a=1的一个POST无代理HTTP1.1协议请求
    //此处注意空格，这个是协议的一部分
    //POST www.hh.com?a=1 HTTP1.1
    return result.toString();
  }

  /**
   * Returns true if the request line should contain the full URL with host and port (like "GET
   * http://android.com/foo HTTP/1.1") or only the path (like "GET /foo HTTP/1.1").
   */
  private static boolean includeAuthorityInRequestLine(Request request, Proxy.Type proxyType) {
    return !request.isHttps() && proxyType == Proxy.Type.HTTP;
  }

  /**
   * Returns the path to request, like the '/' in 'GET / HTTP/1.1'. Never empty, even if the request
   * URL is. Includes the query component if it exists.
   */
  public static String requestPath(HttpUrl url) {
    String path = url.encodedPath();
    String query = url.encodedQuery();
    return query != null ? (path + '?' + query) : path;
  }
}
