/*
 * Copyright (C) 2016 Square, Inc.
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

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.HttpRetryException;
import java.net.ProtocolException;
import java.net.Proxy;
import java.net.SocketTimeoutException;
import java.security.cert.CertificateException;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSocketFactory;
import okhttp3.Address;
import okhttp3.CertificatePinner;
import okhttp3.Connection;
import okhttp3.HttpUrl;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.Route;
import okhttp3.internal.connection.RouteException;
import okhttp3.internal.connection.StreamAllocation;
import okhttp3.internal.http2.ConnectionShutdownException;

import static java.net.HttpURLConnection.HTTP_CLIENT_TIMEOUT;
import static java.net.HttpURLConnection.HTTP_MOVED_PERM;
import static java.net.HttpURLConnection.HTTP_MOVED_TEMP;
import static java.net.HttpURLConnection.HTTP_MULT_CHOICE;
import static java.net.HttpURLConnection.HTTP_PROXY_AUTH;
import static java.net.HttpURLConnection.HTTP_SEE_OTHER;
import static java.net.HttpURLConnection.HTTP_UNAUTHORIZED;
import static okhttp3.internal.Util.closeQuietly;
import static okhttp3.internal.http.StatusLine.HTTP_PERM_REDIRECT;
import static okhttp3.internal.http.StatusLine.HTTP_TEMP_REDIRECT;

/**
 * This interceptor recovers from failures and follows redirects as necessary. It may throw an
 * {@link IOException} if the call was canceled.
 */
public final class RetryAndFollowUpInterceptor implements Interceptor {
  /**
   * How many redirects and auth challenges should we attempt? Chrome follows 21 redirects; Firefox,
   * curl, and wget follow 20; Safari follows 16; and HTTP/1.0 recommends 5.
   */
  private static final int MAX_FOLLOW_UPS = 20;

  private final OkHttpClient client;
  private final boolean forWebSocket;
  private StreamAllocation streamAllocation;
  private Object callStackTrace;
  private volatile boolean canceled;

  public RetryAndFollowUpInterceptor(OkHttpClient client, boolean forWebSocket) {
    this.client = client;
    this.forWebSocket = forWebSocket;
  }

  /**
   * Immediately closes the socket connection if it's currently held. Use this to interrupt an
   * in-flight request from any thread. It's the caller's responsibility to close the request body
   * and response body streams; otherwise resources may be leaked.
   * 注意到这个可以立刻关闭当前的连接流。
   *
   * <p>This method is safe to be called concurrently, but provides limited guarantees. If a
   * transport layer connection has been established (such as a HTTP/2 stream) that is terminated.
   * Otherwise if a socket connection is being established, that is terminated.
   */
  public void cancel() {
    canceled = true;
    StreamAllocation streamAllocation = this.streamAllocation;
    if (streamAllocation != null) streamAllocation.cancel();
  }

  public boolean isCanceled() {
    return canceled;
  }

  public void setCallStackTrace(Object callStackTrace) {
    this.callStackTrace = callStackTrace;
  }

  public StreamAllocation streamAllocation() {
    return streamAllocation;
  }

  @Override public Response intercept(Chain chain) throws IOException {
    Request request = chain.request();
    //流的分配者，这里重点是通过当前请求连接构建Address和RouteSelector
    streamAllocation = new StreamAllocation(
        client.connectionPool(), createAddress(request.url()), callStackTrace);

    int followUpCount = 0;//重定向的次数，最大20
    Response priorResponse = null;//用于记录重定向之前上一次的响应成果，这个响应是会清空响应体的
    while (true) {//用于重连或者重定向
      if (canceled) {//每一次请求之前先判断当前Call(Request)是否被要求取消
        streamAllocation.release();//清理连接池的资源，并且关闭Socket
        throw new IOException("Canceled");//直接在此处抛出IOException即可，会在AsyncCall中回调onFailure
      }

      Response response = null;
      //用于标记每一次请求是否应该释放套接字连接
      boolean releaseConnection = true;
      try {
        //进行请求
        response = ((RealInterceptorChain) chain).proceed(request, streamAllocation, null, null);
        //正常来说此时服务端已经返回结果，单次请求完成
        releaseConnection = false;
      } catch (RouteException e) {
        // The attempt to connect via a route failed. The request will not have been sent.
        // 尝试连接到指定的节点时候失败
        // 1.在通过host去dns查找ip地址的时候出现异常
        // 2.进行socket连接过程中出现异常，实际上socket在连接的时候会尝试一直进行连接
        // 除非retryOnConnectionFailure要求不能重连，或者socket连接过程中出现不可重新连接相关的异常的时候会抛出
        // 后续会进行异常回调
        if (!recover(e.getLastConnectException(), false, request)) {
          throw e.getLastConnectException();
        }
        releaseConnection = false;
        continue;
      } catch (IOException e) {
        // An attempt to communicate with a server failed. The request may have been sent.
        // 这个异常可能更多的出现在网络传输数据的时候
        boolean requestSendStarted = !(e instanceof ConnectionShutdownException);
        if (!recover(e, requestSendStarted, request)) throw e;
        releaseConnection = false;
        continue;
      } finally {
        // We're throwing an unchecked exception. Release any resources.
        if (releaseConnection) {// 当前连接中出现异常，并且不可尝试重新连接
          streamAllocation.streamFailed(null);//从复用池中移除当前连接，并且关闭当前套接字连接
          streamAllocation.release();//标记当前流分配者后续不再可用
        }
      }

      // Attach the prior response if it exists. Such responses never have a body.
      // 在重试之前记录上一次请求的结果
      if (priorResponse != null) {
        //这里记录了上一次请求返回的响应，不过上一次请求的正文体在这里被置空
        //因为之前的响应的不应该被关心的，应该关心的是当前响应的正文体
        response = response.newBuilder()
            .priorResponse(priorResponse.newBuilder()
                    .body(null)
                    .build())
            .build();
      }
      //判断是否存在代理、重定向和一些异常情况，可能需要尝试发出新的请求
      //这里可能要进行新的请求的构建
      Request followUp = followUpRequest(response);

      if (followUp == null) {//不需要重定向
        if (!forWebSocket) {//App连接非WebSocket
          //标记当前流分配者后续不再可用，释放当前RealConnection所关联的流分配者
          //通过连接池的空闲时间来判断当前连接是否回收，如果回收则会关闭socket
          //否则会待在复用池中，等待复用或者在指定的时间后被清理
          streamAllocation.release();
        }
        return response;//返回成功的响应结果
      }
      //如果到这里，说明要重定向
      //先关闭之前服务端响应的输入流
      closeQuietly(response.body());

      if (++followUpCount > MAX_FOLLOW_UPS) {//最大重定向次数20
        streamAllocation.release();//这里只是单纯的标记流分配者，连接本身会根据连接池状态觉得是否回收，同理socket也根据情况关闭
        throw new ProtocolException("Too many follow-up requests: " + followUpCount);
      }

      if (followUp.body() instanceof UnrepeatableRequestBody) {
        streamAllocation.release();//这里只是单纯的标记流分配者，连接本身会根据连接池状态觉得是否回收，同理socket也根据情况关闭
        throw new HttpRetryException("Cannot retry streamed HTTP body", response.code());
      }
      //判断当前是否可以重用连接，主要是Route的问题，包括ip地址等数据
      //如果不能则需要通过新的请求地址来新建连接
      if (!sameConnection(response, followUp.url())) {
        streamAllocation.release();//这里只是单纯的标记流分配者，连接本身会根据连接池状态觉得是否回收，同理socket也根据情况关闭
        //通过新的Address构建新的流分配者
        streamAllocation = new StreamAllocation(
            client.connectionPool(), createAddress(followUp.url()), callStackTrace);
      } else if (streamAllocation.codec() != null) {
        throw new IllegalStateException("Closing the body of " + response
            + " didn't close its backing stream. Bad interceptor?");
      }
      //准备进行下一次重定向的请求
      request = followUp;
      priorResponse = response;
    }
  }

  /**
   * 在发起请求之前，通过当前链接和OkHttpClient中的一些配置
   * 初始化当前请求的目标对象
   * @param url 当前请求地址
   * @return 封装后的请求目标对象
   */
  private Address createAddress(HttpUrl url) {
    SSLSocketFactory sslSocketFactory = null;
    HostnameVerifier hostnameVerifier = null;
    CertificatePinner certificatePinner = null;
    if (url.isHttps()) {//当前请求为Https请求
      //初始化Https握手校验的工厂
      sslSocketFactory = client.sslSocketFactory();
      //用于请求主机域名校验
      hostnameVerifier = client.hostnameVerifier();
      //用于标记一些域名可信任的证书
      certificatePinner = client.certificatePinner();
    }
    //主要包含了请求域名、端口、自定义的DNS、套接字工厂、SSL套接字工厂、域名校验
    //Https证书锁定、代理认证、代理、支持协议、Https加密支持套件工厂、代理选择器
    return new Address(url.host(), url.port(), client.dns(), client.socketFactory(),
        sslSocketFactory, hostnameVerifier, certificatePinner, client.proxyAuthenticator(),
        client.proxy(), client.protocols(), client.connectionSpecs(), client.proxySelector());
  }

  /**
   * Report and attempt to recover from a failure to communicate with a server. Returns true if
   * {@code e} is recoverable, or false if the failure is permanent. Requests with a body can only
   * be recovered if the body is buffered or if the failure occurred before the request has been
   * sent.
   * 重连基本条件
   */
  private boolean recover(IOException e, boolean requestSendStarted, Request userRequest) {
    streamAllocation.streamFailed(e);//这里会关闭之前的socket

    // 应用层是否允许在连接失败之后重新尝试连接
    if (!client.retryOnConnectionFailure()) return false;

    // We can't send the request body again.
    // 这个是Http2协议中的情况，这里先不考虑
    if (requestSendStarted && userRequest.body() instanceof UnrepeatableRequestBody) return false;

    // This exception is fatal.
    // 检查当前异常类型，因为有的异常是无法再次进行重试连接的
    if (!isRecoverable(e, requestSendStarted)) return false;

    // 当前没有连接节点可以去尝试
    // 一般来说就是当前节点
    if (!streamAllocation.hasMoreRoutes()) return false;

    // For failure recovery, use the same route selector with a new connection.
    return true;
  }

  private boolean isRecoverable(IOException e, boolean requestSendStarted) {
    // If there was a protocol problem, don't recover.
    // 协议异常，比方说协议规定的报文格式不符之类的情况
    // 总之就是一些不满足当前协议的条件
    if (e instanceof ProtocolException) {
      return false;
    }

    // If there was an interruption don't recover, but if there was a timeout connecting to a route
    // we should try the next route (if there is one).
    // 这个一般是Okio的异常，会在流操作超时之后抛出
    // SocketTimeoutException一般可以认为是socket连接超时或者读写超时
    // 这种时候可以尝试重连
    if (e instanceof InterruptedIOException) {
      return e instanceof SocketTimeoutException && !requestSendStarted;
    }

    // Look for known client-side or negotiation errors that are unlikely to be fixed by trying
    // again with a different route.
    // 当前是Https握手失败异常
    if (e instanceof SSLHandshakeException) {
      // 如果是证书异常，这样没有必要重试，因为重试了也会失败
      // If the problem was a CertificateException from the X509TrustManager,
      // do not retry.
      if (e.getCause() instanceof CertificateException) {
        return false;
      }
    }
    if (e instanceof SSLPeerUnverifiedException) {
      // e.g. a certificate pinning error.
      return false;
    }

    // An example of one we might want to retry with a different route is a problem connecting to a
    // proxy and would manifest as a standard IOException. Unless it is one we know we should not
    // retry, we return true and try a new route.
    return true;
  }

  /**
   * Figures out the HTTP request to make in response to receiving {@code userResponse}. This will
   * either add authentication headers, follow redirects or handle a client request timeout. If a
   * follow-up is either unnecessary or not applicable, this returns null.
   * 尝试通过响应来构建新的请求来获取真实的响应。这个包括了添加认证头部、重定向或者处理请求超时。
   * 如果不需要再次请求，这会直接返回null
   */
  private Request followUpRequest(Response userResponse) throws IOException {
    if (userResponse == null) throw new IllegalStateException();
    Connection connection = streamAllocation.connection();
    //Route其实就是访问路径，记录了当前连接使用的代理、主机名、端口等信息
    Route route = connection != null
        ? connection.route()
        : null;
    int responseCode = userResponse.code();

    final String method = userResponse.request().method();
    switch (responseCode) {//当前服务端响应码
      case HTTP_PROXY_AUTH://407需要代理授权，这一块可以去看看Authenticator里面的注释和用法，比较详细
        Proxy selectedProxy = route != null
            ? route.proxy()
            : client.proxy();
        if (selectedProxy.type() != Proxy.Type.HTTP) {
          throw new ProtocolException("Received HTTP_PROXY_AUTH (407) code while not using proxy");
        }
        //这里大概注意一下，这个代理认证是在client单例中设置的。
        return client.proxyAuthenticator().authenticate(route, userResponse);

      case HTTP_UNAUTHORIZED://401表示未认证，需要认证，类似407
        //在client中有代理认证和普通认证，注意一下需要分别设置就可以了。
        return client.authenticator().authenticate(route, userResponse);

      case HTTP_PERM_REDIRECT:
      case HTTP_TEMP_REDIRECT:
        // "If the 307 or 308 status code is received in response to a request other than GET
        // or HEAD, the user agent MUST NOT automatically redirect the request"
        //这里的具体细节可以自行查看HTTP状态码
        //大概说一下，307/308作为301/302的另一种形式，主要是用于区分GET/POST请求
        //其中307和308只对应于GET和HEAD请求
        if (!method.equals("GET") && !method.equals("HEAD")) {
          return null;
        }
        // fall-through
      case HTTP_MULT_CHOICE:
      case HTTP_MOVED_PERM:
      case HTTP_MOVED_TEMP:
      case HTTP_SEE_OTHER:
        // 300-303,307,308都是重定向
        // Does the client allow redirects?
        // client中可以设置是否允许重定向
        if (!client.followRedirects()) return null;
        //重定向之后的新的地址会在头部报文中的Location字段中返回
        String location = userResponse.header("Location");
        if (location == null) return null;
        //生成重定向的新的URL
        HttpUrl url = userResponse.request().url().resolve(location);

        // Don't follow redirects to unsupported protocols.
        if (url == null) return null;

        // If configured, don't follow redirects between SSL and non-SSL.
        //判断当前scheme是否一致，如果一开始Http然后重定向Https是不被允许的
        boolean sameScheme = url.scheme().equals(userResponse.request().url().scheme());
        //注意到要求client中followSslRedirects为true才可以进行重定向
        //我的理解是一开始有设置是否允许HTTP重定向，从源头解决了HTTP到HTTP的情况
        //此处主要是针对HTTPS到HTTPS的重定向，如果不接受SSL握手的重定向，则此处不应该继续
        if (!sameScheme && !client.followSslRedirects()) return null;

        // Most redirects don't include a request body.
        // 大多数的重定向请求不应该有请求正文体
        Request.Builder requestBuilder = userResponse.request().newBuilder();
        //method为当前请求的method
        //稍微总结一下
        //POST,PUT,PATCH,REPORT,OPTIONS,DELETE,(PROPFIND,MKCOL,LOCK,PROPPATCH(WebDAV类型，app不用管))
        //因为GET请求的时候正文体是没有内容的，所以不需要处理
        if (HttpMethod.permitsRequestBody(method)) {
          //PROPFIND(WebDAV)可以认为false
          final boolean maintainBody = HttpMethod.redirectsWithBody(method);
          //此处不是PROPFIND
          if (HttpMethod.redirectsToGet(method)) {
            //将新的请求定义为GET请求，一般就是浏览器重定向跳转
            requestBuilder.method("GET", null);
          } else {
            RequestBody requestBody = maintainBody ? userResponse.request().body() : null;
            requestBuilder.method(method, requestBody);
          }
          if (!maintainBody) {//POST是肯定进入这里的，清理了请求报文头部中的一些和正文体相关的内容，传输编码、正文长度和正文类型
            requestBuilder.removeHeader("Transfer-Encoding");
            requestBuilder.removeHeader("Content-Length");
            requestBuilder.removeHeader("Content-Type");
          }
        }

        // When redirecting across hosts, drop all authentication headers. This
        // is potentially annoying to the application layer since they have no
        // way to retain them.
        // 检查scheme、host和post，如果有出入则不应该将之前设置的认证信息带过去
        // 如果新的地址需要认证，其实可以返回401，然后再次尝试认证，并且带header中的Authorization即可
        if (!sameConnection(userResponse, url)) {
          requestBuilder.removeHeader("Authorization");
        }

        return requestBuilder.url(url).build();

      case HTTP_CLIENT_TIMEOUT://实际上开发APP关心这个就好了，服务端等待连接时间过长或者说当前服务器过忙也有可能
        // 408's are rare in practice, but some servers like HAProxy use this response code. The
        // spec says that we may repeat the request without modifications. Modern browsers also
        // repeat the request (even non-idempotent ones.)
        if (userResponse.request().body() instanceof UnrepeatableRequestBody) {
          return null;
        }
        //尝试重连就是咯
        return userResponse.request();

      default:
        return null;
    }
  }

  /**
   * Returns true if an HTTP request for {@code followUp} can reuse the connection used by this
   * engine.
   */
  private boolean sameConnection(Response response, HttpUrl followUp) {
    HttpUrl url = response.request().url();
    return url.host().equals(followUp.host())
        && url.port() == followUp.port()
        && url.scheme().equals(followUp.scheme());
  }
}
