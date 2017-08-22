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
import java.util.List;
import okhttp3.Connection;
import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.internal.connection.RealConnection;
import okhttp3.internal.connection.StreamAllocation;

/**
 * A concrete interceptor chain that carries the entire interceptor chain: all application
 * interceptors, the OkHttp core, all network interceptors, and finally the network caller.
 * 一个和拦截器关联的Chain，内部封装了对应的request
 */
public final class RealInterceptorChain implements Interceptor.Chain {
  private final List<Interceptor> interceptors;
  private final StreamAllocation streamAllocation;
  private final HttpCodec httpCodec;
  private final RealConnection connection;
  private final int index;
  private final Request request;
  private int calls;
  //在RealCall内部默认只有拦截器和request，其余均为空
  public RealInterceptorChain(List<Interceptor> interceptors, StreamAllocation streamAllocation,
      HttpCodec httpCodec, RealConnection connection, int index, Request request) {
    this.interceptors = interceptors;
    this.connection = connection;
    this.streamAllocation = streamAllocation;
    this.httpCodec = httpCodec;
    this.index = index;
    this.request = request;
  }

  @Override public Connection connection() {
    return connection;
  }

  public StreamAllocation streamAllocation() {
    return streamAllocation;
  }

  public HttpCodec httpStream() {
    return httpCodec;
  }

  @Override public Request request() {
    return request;
  }

  @Override public Response proceed(Request request) throws IOException {
    return proceed(request, streamAllocation, httpCodec, connection);
  }

  /**
   * AsyncCall实际执行的方法
   */
  public Response proceed(Request request, StreamAllocation streamAllocation, HttpCodec httpCodec,
      RealConnection connection) throws IOException {
    if (index >= interceptors.size()) throw new AssertionError();

    calls++;

    // If we already have a stream, confirm that the incoming request will use it.
    if (this.httpCodec != null && !this.connection.supportsUrl(request.url())) {
      throw new IllegalStateException("network interceptor " + interceptors.get(index - 1)
          + " must retain the same host and port");
    }

    // If we already have a stream, confirm that this is the only call to chain.proceed().
    if (this.httpCodec != null && calls > 1) {
      throw new IllegalStateException("network interceptor " + interceptors.get(index - 1)
          + " must call proceed() exactly once");
    }

    // Call the next interceptor in the chain.
    RealInterceptorChain next = new RealInterceptorChain(
        interceptors, streamAllocation, httpCodec, connection, index + 1, request);
    //index默认为0
    Interceptor interceptor = interceptors.get(index);
    //也就是说拦截器的执行顺序满足添加顺序，然后内部会进行循环
    Response response = interceptor.intercept(next);
    //在没有设置自定义拦截器的基础上
    //首先进入到RetryAndFollowUpInterceptor，构造streamAllocation，
    //通过call回调proceed方法，然后进入到BridgeInterceptor
    //通过request设置url、method等一系列报文头，并且根据requestBody中的媒体类型和字节长度
    //设置报头的Content-Type和Content-Length等属性，具体还是看BridgeInterceptor的内部实现
    //接着通过call回调，进入CacheInterceptor，首先尝试从自定义缓存（OkHttpClient中的cache）中通过request获取response，
    //一般在没有设置的情况下，直接会通过call回调，进入ConnectInterceptor，
    //然后尝试通过连接池获取可复用的连接，如果没有则新建连接
    //再进行TCP和TLS的握手行为，然后将该连接放入连接池中
    //最后生成对应的HttpCodec和RealConnection，假设当前不是websocket（一般app请求都不是websocket类型）
    //执行在OkHttpClient中设置的自定义network拦截器（一般都没有）
    //最后一步，进入CallServerInterceptor，首先将通过和服务器建立的流向内输入参数
    //然后关闭流，相当于完成一次完整的请求流程。
    //然后开始等待服务端返回报文的头部，这个过程中当服务端没有响应的情况下会处于阻塞状态，当服务端响应后，
    //构造新的响应实体，开始拼装头部和报文回复体，之后开始沿之前递归的路径反向回调。
    //其中ConnectInterceptor并没有多余操作，然后回到CacheInterceptor，后续就是判断服务端返回的缓存是否变化标记和
    //是否有本地缓存来决定是否更新和采用缓存，之后回到BridgeInterceptor，首先是处理cookies的存储，然后判断一下返回
    //类型是否采用Gzip压缩进行相应的处理，最后回到RetryAndFollowUpInterceptor，里面本身是一个while循环，当一次response回到之后
    //要判断是否有重定向或者超时的情况出现，这种时候会设置新的request继续尝试连接，否则返回，至此一个完整流程结束。


    // Confirm that the next interceptor made its required call to chain.proceed().
    if (httpCodec != null && index + 1 < interceptors.size() && next.calls != 1) {
      throw new IllegalStateException("network interceptor " + interceptor
          + " must call proceed() exactly once");
    }

    // Confirm that the intercepted response isn't null.
    if (response == null) {
      throw new NullPointerException("interceptor " + interceptor + " returned null");
    }

    return response;
  }
}

