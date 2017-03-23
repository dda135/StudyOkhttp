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
package okhttp3.internal.cache;

import java.io.IOException;
import okhttp3.Headers;
import okhttp3.Interceptor;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.internal.Internal;
import okhttp3.internal.Util;
import okhttp3.internal.http.HttpCodec;
import okhttp3.internal.http.HttpHeaders;
import okhttp3.internal.http.HttpMethod;
import okhttp3.internal.http.RealResponseBody;
import okio.Buffer;
import okio.BufferedSink;
import okio.BufferedSource;
import okio.Okio;
import okio.Sink;
import okio.Source;
import okio.Timeout;

import static java.net.HttpURLConnection.HTTP_NOT_MODIFIED;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static okhttp3.internal.Util.closeQuietly;
import static okhttp3.internal.Util.discard;

/** Serves requests from the cache and writes responses to the cache. */
public final class CacheInterceptor implements Interceptor {
  final InternalCache cache;

  public CacheInterceptor(InternalCache cache) {
    this.cache = cache;
  }

  @Override public Response intercept(Chain chain) throws IOException {
    //尝试先从cache中通过对应的request获取response
    //注意这个cache是在Client中自定义的缓存，默认为空
    Response cacheCandidate = cache != null
        ? cache.get(chain.request())
        : null;
    //获取当前时间戳
    long now = System.currentTimeMillis();
    //创建一个默认的缓存策略
    CacheStrategy strategy = new CacheStrategy.Factory(now, chain.request(), cacheCandidate).get();
    //在不需要发出请求的时候，request为null，比方说成功获取缓存或者request中设置了only-if-cached
    //在没有自定义缓存的情况下，cacheResponse为null，当然此时request可能null
    //注意此处如果cacheResponse不为空，它至少也是cacheCandidate的一个深拷贝
    Request networkRequest = strategy.networkRequest;
    Response cacheResponse = strategy.cacheResponse;

    if (cache != null) {
      //此处统计用的，可以记录请求次数和击中缓存次数和网络请求次数
      cache.trackResponse(strategy);
    }
    //获取缓存失败或者缓存已经过期，可以释放掉旧的缓存的资源
    if (cacheCandidate != null && cacheResponse == null) {
      closeQuietly(cacheCandidate.body()); // The cache candidate wasn't applicable. Close it.
    }

    // If we're forbidden from using the network and the cache is insufficient, fail.
    // 此处可以认为在only-if-cached的情况下获取缓存失败
    if (networkRequest == null && cacheResponse == null) {
      //这里返回504请求超时，说明服务端没有在合适时间内收到客户端的请求
      return new Response.Builder()
          .request(chain.request())
          .protocol(Protocol.HTTP_1_1)
          .code(504)
          .message("Unsatisfiable Request (only-if-cached)")
          .body(Util.EMPTY_RESPONSE)
          .sentRequestAtMillis(-1L)
          .receivedResponseAtMillis(System.currentTimeMillis())
          .build();
    }
    // only-if-cached处理完成
    // If we don't need the network, we're done.
    // 如果此时不需要发出网络请求，比方说击中缓存
    if (networkRequest == null) {
      //返回缓存即可
      return cacheResponse.newBuilder()
          .cacheResponse(stripBody(cacheResponse))
          .build();
    }
    //来到这里说明没有击中缓存，并且要继续请求的发起
    Response networkResponse = null;
    try {
      //回调下一个拦截器
      networkResponse = chain.proceed(networkRequest);
      //此处返回的是服务端的响应
    } finally {
      // If we're crashing on I/O or otherwise, don't leak the cache body.
      if (networkResponse == null && cacheCandidate != null) {
        closeQuietly(cacheCandidate.body());
      }
    }

    // If we have a cache response too, then we're doing a conditional get.
    //如果当前有本地缓存
    if (cacheResponse != null) {
      //注意此时的逻辑，完整来说
      //首先客户端通过request和缓存response中header的标志认为缓存对于客户端来说已经过期了
      //然后又没有设置only-if-cached，则尝试发出了请求，这个请求中会附带缓存response的last-modified之类的信息
      //服务端会通过这个信息来判断当前服务端的数据是不是最新的，如果不是则返回304
      if (networkResponse.code() == HTTP_NOT_MODIFIED) {
        //这时候说明客户端的缓存其实还是服务端最新的数据，那么重新组装一下header等信息即可
        Response response = cacheResponse.newBuilder()
            .headers(combine(cacheResponse.headers(), networkResponse.headers()))
            .sentRequestAtMillis(networkResponse.sentRequestAtMillis())
            .receivedResponseAtMillis(networkResponse.receivedResponseAtMillis())
            .cacheResponse(stripBody(cacheResponse))
            .networkResponse(stripBody(networkResponse))
            .build();
        //注意此时又拷贝了一份，那么网络请求回来的response就可以关闭了
        networkResponse.body().close();

        // Update the cache after combining headers but before stripping the
        // Content-Encoding header (as performed by initContentStream()).
        //击中缓存次数+1，虽然还是发出了请求
        cache.trackConditionalCacheHit();
        //更新缓存，主要是刷新头部之类的信息，因为至少可以延迟当前response的有效期
        cache.update(cacheResponse, response);
        return response;
      } else {
        //说明服务端返回的response数据是最新的，那么缓存数据已经无效，可以释放资源
        closeQuietly(cacheResponse.body());
      }
    }
    //当前接受从服务端返回的response
    Response response = networkResponse.newBuilder()
        .cacheResponse(stripBody(cacheResponse))
        .networkResponse(stripBody(networkResponse))
        .build();

    if (HttpHeaders.hasBody(response)) {//这里是判断报文是否有内容体
      //判断当前网络响应是否可以缓存，注意一下这里如果可以缓存的话会先将header之类的写入cacheRequest当中
      CacheRequest cacheRequest = maybeCache(response, networkResponse.request(), cache);
      //这里是将response的body写入cacheRequest的body当中，至此整个
      response = cacheWritingResponse(cacheRequest, response);
    }

    return response;
  }

  private static Response stripBody(Response response) {
    return response != null && response.body() != null
        ? response.newBuilder().body(null).build()
        : response;
  }

  private CacheRequest maybeCache(Response userResponse, Request networkRequest,
      InternalCache responseCache) throws IOException {
    if (responseCache == null) return null;

    // Should we cache this response for this request?
    if (!CacheStrategy.isCacheable(userResponse, networkRequest)) {
      //这里说一种情况，在no-store的情况下，对于POST请求需要清理之前的缓存，当然也许之前并没有缓存
      if (HttpMethod.invalidatesCache(networkRequest.method())) {
        try {
          responseCache.remove(networkRequest);
        } catch (IOException ignored) {
          // The cache cannot be written.
        }
      }
      return null;
    }

    // Offer this request to the cache.
    // 当前允许进行缓存，则缓存之
    return responseCache.put(userResponse);
  }

  /**
   * Returns a new source that writes bytes to {@code cacheRequest} as they are read by the source
   * consumer. This is careful to discard bytes left over when the stream is closed; otherwise we
   * may never exhaust the source stream and therefore not complete the cached response.
   */
  private Response cacheWritingResponse(final CacheRequest cacheRequest, Response response)
      throws IOException {
    // Some apps return a null body; for compatibility we treat that like a null cache request.
    if (cacheRequest == null) return response;
    //获得之前put进cache的输出流，可以用于写入
    Sink cacheBodyUnbuffered = cacheRequest.body();
    if (cacheBodyUnbuffered == null) return response;
    //当前应该缓存的response的输入流
    final BufferedSource source = response.body().source();
    //cacheBody可以理解为cacheRequest中body的缓冲输出流，用于写入
    final BufferedSink cacheBody = Okio.buffer(cacheBodyUnbuffered);
    //新建一个输入流，主要用于写入操作
    //实际上就是从response的body中读取数据，然后写入到cacheRequest的body当中
    Source cacheWritingSource = new Source() {
      boolean cacheRequestClosed;

      @Override public long read(Buffer sink, long byteCount) throws IOException {
        long bytesRead;
        try {
          bytesRead = source.read(sink, byteCount);
        } catch (IOException e) {
          if (!cacheRequestClosed) {
            cacheRequestClosed = true;
            cacheRequest.abort(); // Failed to write a complete cache response.
          }
          throw e;
        }

        if (bytesRead == -1) {
          if (!cacheRequestClosed) {
            cacheRequestClosed = true;
            cacheBody.close(); // The cache response is complete!
          }
          return -1;
        }

        sink.copyTo(cacheBody.buffer(), sink.size() - bytesRead, bytesRead);
        cacheBody.emitCompleteSegments();
        return bytesRead;
      }

      @Override public Timeout timeout() {
        return source.timeout();
      }

      @Override public void close() throws IOException {
        if (!cacheRequestClosed
            && !discard(this, HttpCodec.DISCARD_STREAM_TIMEOUT_MILLIS, MILLISECONDS)) {
          cacheRequestClosed = true;
          cacheRequest.abort();
        }
        source.close();
      }
    };

    return response.newBuilder()
        .body(new RealResponseBody(response.headers(), Okio.buffer(cacheWritingSource)))
        .build();
  }

  /** Combines cached headers with a network headers as defined by RFC 2616, 13.5.3. */
  private static Headers combine(Headers cachedHeaders, Headers networkHeaders) {
    Headers.Builder result = new Headers.Builder();

    for (int i = 0, size = cachedHeaders.size(); i < size; i++) {
      String fieldName = cachedHeaders.name(i);
      String value = cachedHeaders.value(i);
      if ("Warning".equalsIgnoreCase(fieldName) && value.startsWith("1")) {
        continue; // Drop 100-level freshness warnings.
      }
      if (!isEndToEnd(fieldName) || networkHeaders.get(fieldName) == null) {
        Internal.instance.addLenient(result, fieldName, value);
      }
    }

    for (int i = 0, size = networkHeaders.size(); i < size; i++) {
      String fieldName = networkHeaders.name(i);
      if ("Content-Length".equalsIgnoreCase(fieldName)) {
        continue; // Ignore content-length headers of validating responses.
      }
      if (isEndToEnd(fieldName)) {
        Internal.instance.addLenient(result, fieldName, networkHeaders.value(i));
      }
    }

    return result.build();
  }

  /**
   * Returns true if {@code fieldName} is an end-to-end HTTP header, as defined by RFC 2616,
   * 13.5.1.
   */
  static boolean isEndToEnd(String fieldName) {
    return !"Connection".equalsIgnoreCase(fieldName)
        && !"Keep-Alive".equalsIgnoreCase(fieldName)
        && !"Proxy-Authenticate".equalsIgnoreCase(fieldName)
        && !"Proxy-Authorization".equalsIgnoreCase(fieldName)
        && !"TE".equalsIgnoreCase(fieldName)
        && !"Trailers".equalsIgnoreCase(fieldName)
        && !"Transfer-Encoding".equalsIgnoreCase(fieldName)
        && !"Upgrade".equalsIgnoreCase(fieldName);
  }
}
