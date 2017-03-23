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
import java.net.ProtocolException;
import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.internal.Util;
import okhttp3.internal.connection.StreamAllocation;
import okio.BufferedSink;
import okio.Okio;
import okio.Sink;

/** This is the last interceptor in the chain. It makes a network call to the server. */
public final class CallServerInterceptor implements Interceptor {
  private final boolean forWebSocket;

  public CallServerInterceptor(boolean forWebSocket) {
    this.forWebSocket = forWebSocket;
  }

  @Override public Response intercept(Chain chain) throws IOException {
    //获得在RealInterceptorChain中的基本参数
    HttpCodec httpCodec = ((RealInterceptorChain) chain).httpStream();
    StreamAllocation streamAllocation = ((RealInterceptorChain) chain).streamAllocation();
    Request request = chain.request();

    long sentRequestMillis = System.currentTimeMillis();
    //到这里之前socket连接已经建立，流通道也已经准备完成
    //将头部报文写入输出流中
    httpCodec.writeRequestHeaders(request);

    Response.Builder responseBuilder = null;
    //验证头部请求method合法且具有请求体内容
    if (HttpMethod.permitsRequestBody(request.method()) && request.body() != null) {
      // If there's a "Expect: 100-continue" header on the request, wait for a "HTTP/1.1 100
      // Continue" response before transmitting the request body. If we don't get that, return what
      // we did get (such as a 4xx response) without ever transmitting the request body.
      if ("100-continue".equalsIgnoreCase(request.header("Expect"))) {
        httpCodec.flushRequest();
        responseBuilder = httpCodec.readResponseHeaders(true);
      }

      // Write the request body, unless an "Expect: 100-continue" expectation failed.
      if (responseBuilder == null) {//正常情况下，将requestBody写进输出流，这里一般就是往服务端传POST的参数
        //构建一个可以用于承载一定数量字节的流
        Sink requestBodyOut = httpCodec.createRequestBody(request, request.body().contentLength());
        //使用缓冲流，简单理解就是类似于BufferedStream
        BufferedSink bufferedRequestBody = Okio.buffer(requestBodyOut);
        //将request中的body写入sink中
        request.body().writeTo(bufferedRequestBody);
        //关闭流
        bufferedRequestBody.close();
      }
    }
    //关闭输出流，这里可以认为一次请求的发送已经完成，剩下的就是等待服务端的返回响应
    httpCodec.finishRequest();

    if (responseBuilder == null) {
      //此处是获得响应报文的起始行，method code reason
      //然后再读取头部报文
      responseBuilder = httpCodec.readResponseHeaders(false);
    }
    //到此处如果responseBuilder不为空，说明已经正常接收到起始行和头部报文
    //可以设置握手结果、发送时间和接收到响应的时间
    Response response = responseBuilder
        .request(request)
        .handshake(streamAllocation.connection().handshake())
        .sentRequestAtMillis(sentRequestMillis)
        .receivedResponseAtMillis(System.currentTimeMillis())
        .build();

    int code = response.code();
    if (forWebSocket && code == 101) {
      // Connection is upgrading, but we need to ensure interceptors see a non-null response body.
      response = response.newBuilder()
          .body(Util.EMPTY_RESPONSE)
          .build();
    } else {
      //设置响应中的正文体，注意此处只是关联了source供应流而已
      //大致也可以理解OkHttp的回调默认是在子线程中，从该线程中获取实际的body
      //比方说String类型是要经过IO操作的，所以说回调是合理的
      response = response.newBuilder()
          .body(httpCodec.openResponseBody(response))
          .build();
      //至此整个网络请求流程已经完成
    }

    if ("close".equalsIgnoreCase(response.request().header("Connection"))
        || "close".equalsIgnoreCase(response.header("Connection"))) {
      streamAllocation.noNewStreams();
    }

    if ((code == 204 || code == 205) && response.body().contentLength() > 0) {
      throw new ProtocolException(
          "HTTP " + code + " had non-zero Content-Length: " + response.body().contentLength());
    }

    return response;
    //到这里一次请求以及响应的数据设置就全部完成，剩下需要关注整个回调的流程
  }
}
