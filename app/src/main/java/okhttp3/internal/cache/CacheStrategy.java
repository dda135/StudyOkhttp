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
package okhttp3.internal.cache;

import java.util.Date;
import okhttp3.CacheControl;
import okhttp3.Headers;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.internal.Internal;
import okhttp3.internal.http.HttpDate;
import okhttp3.internal.http.HttpHeaders;
import okhttp3.internal.http.StatusLine;

import static java.net.HttpURLConnection.HTTP_BAD_METHOD;
import static java.net.HttpURLConnection.HTTP_GONE;
import static java.net.HttpURLConnection.HTTP_MOVED_PERM;
import static java.net.HttpURLConnection.HTTP_MOVED_TEMP;
import static java.net.HttpURLConnection.HTTP_MULT_CHOICE;
import static java.net.HttpURLConnection.HTTP_NOT_AUTHORITATIVE;
import static java.net.HttpURLConnection.HTTP_NOT_FOUND;
import static java.net.HttpURLConnection.HTTP_NOT_IMPLEMENTED;
import static java.net.HttpURLConnection.HTTP_NO_CONTENT;
import static java.net.HttpURLConnection.HTTP_OK;
import static java.net.HttpURLConnection.HTTP_REQ_TOO_LONG;
import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * Given a request and cached response, this figures out whether to use the network, the cache, or
 * both.
 *
 * <p>Selecting a cache strategy may add conditions to the request (like the "If-Modified-Since"
 * header for conditional GETs) or warnings to the cached response (if the cached data is
 * potentially stale).
 */
public final class CacheStrategy {
  /** The request to send on the network, or null if this call doesn't use the network. */
  public final Request networkRequest;

  /** The cached response to return or validate; or null if this call doesn't use a cache. */
  public final Response cacheResponse;

  CacheStrategy(Request networkRequest, Response cacheResponse) {
    this.networkRequest = networkRequest;
    this.cacheResponse = cacheResponse;
  }

  /**
   * 工厂模式，用于生产缓存策略
   */
  public static class Factory {

    /** Returns true if {@code response} can be stored to later serve another request. */
    public static boolean isCacheable(Response response, Request request) {
      // Always go to network for uncacheable response codes (RFC 7231 section 6.1),
      // This implementation doesn't support caching partial content.
      switch (response.code()) {//这里是一些允许缓存的返回状态码
        case HTTP_OK:
        case HTTP_NOT_AUTHORITATIVE:
        case HTTP_NO_CONTENT:
        case HTTP_MULT_CHOICE:
        case HTTP_MOVED_PERM:
        case HTTP_NOT_FOUND:
        case HTTP_BAD_METHOD:
        case HTTP_GONE:
        case HTTP_REQ_TOO_LONG:
        case HTTP_NOT_IMPLEMENTED:
        case StatusLine.HTTP_PERM_REDIRECT:
          // These codes can be cached unless headers forbid it.
          break;

        case HTTP_MOVED_TEMP:
        case StatusLine.HTTP_TEMP_REDIRECT:
          // These codes can only be cached with the right response headers.
          // http://tools.ietf.org/html/rfc7234#section-3
          // s-maxage is not checked because OkHttp is a private cache that should ignore s-maxage.
          if (response.header("Expires") != null
                  || response.cacheControl().maxAgeSeconds() != -1
                  || response.cacheControl().isPublic()
                  || response.cacheControl().isPrivate()) {
            break;
          }
          // Fall-through.

        default:
          // All other codes cannot be cached.
          return false;
      }

      // A 'no-store' directive on request or response prevents the response from being cached.
      // no-store标志的意义是在接收到服务端的response的情况下也不进行存储
      return !response.cacheControl().noStore() && !request.cacheControl().noStore();
    }
    //生产缓存策略的时间ms
    final long nowMillis;
    //当前要请求的request
    final Request request;
    //当前请求的request在自定义cache中是否有对应的response的缓存
    final Response cacheResponse;

    /** The server's time when the cached response was served, if known. */
    private Date servedDate;
    private String servedDateString;

    /** The last modified date of the cached response, if known. */
    private Date lastModified;
    private String lastModifiedString;

    /**
     * The expiration date of the cached response, if known. If both this field and the max age are
     * set, the max age is preferred.
     */
    private Date expires;

    /**
     * Extension header set by OkHttp specifying the timestamp when the cached HTTP request was
     * first initiated.
     */
    private long sentRequestMillis;

    /**
     * Extension header set by OkHttp specifying the timestamp when the cached HTTP response was
     * first received.
     */
    private long receivedResponseMillis;

    /** Etag of the cached response. */
    private String etag;

    /** Age of the cached response. */
    private int ageSeconds = -1;

    public Factory(long nowMillis, Request request, Response cacheResponse) {
      //当前创建策略的时间戳
      this.nowMillis = nowMillis;
      //当前请求策略的请求
      this.request = request;
      //当前尝试通过request获取的response
      this.cacheResponse = cacheResponse;
      //如果从缓存中获取成功
      if (cacheResponse != null) {
        //该响应为本地当中的缓存
        //注意这个时间其实是在CallServerInterceptor中调用intercept时候初始化的时间，简称发送request的时间
        this.sentRequestMillis = cacheResponse.sentRequestAtMillis();
        //客户端接收到缓存的时间戳
        this.receivedResponseMillis = cacheResponse.receivedResponseAtMillis();
        Headers headers = cacheResponse.headers();
        for (int i = 0, size = headers.size(); i < size; i++) {//遍历response的头部报文,获取与缓存相关的字段
          String fieldName = headers.name(i);
          String value = headers.value(i);
          if ("Date".equalsIgnoreCase(fieldName)) {//消息发送的时间（创建报文的时间）
            servedDate = HttpDate.parse(value);
            servedDateString = value;
          } else if ("Expires".equalsIgnoreCase(fieldName)) {//消息应该过期的时间
            expires = HttpDate.parse(value);
          } else if ("Last-Modified".equalsIgnoreCase(fieldName)) {//客户端请求的资源在服务端的最后一次修改时间
            lastModified = HttpDate.parse(value);
            lastModifiedString = value;
          } else if ("ETag".equalsIgnoreCase(fieldName)) {//和Last-Modified之类的作用一致，用于判断服务端缓存是否过期
            etag = value;//资源的匹配信息，类似于身份证
          } else if ("Age".equalsIgnoreCase(fieldName)) {//从原始服务器到代理缓存形成的估算时间
            ageSeconds = HttpHeaders.parseSeconds(value, -1);
          }
        }
      }
    }

    /**
     * Returns a strategy to satisfy {@code request} using the a cached response {@code response}.
     */
    public CacheStrategy get() {
      //通过header参数来判断当前缓存是否过期，并且设置对应的request和response
      //如果缓存没有过期，则request:null,response:cacheResponse
      CacheStrategy candidate = getCandidate();
      //当前没有获得的缓存，request中设置only-if-cached
      if (candidate.networkRequest != null && request.cacheControl().onlyIfCached()) {
        // We're forbidden from using the network and the cache is insufficient.
        // 因为只接收缓存里的内容，所以获取缓存失败之后应该取消掉这次请求
        return new CacheStrategy(null, null);
      }

      return candidate;
    }

    /** Returns a strategy to use assuming the request can use the network.
     *
     * */
    private CacheStrategy getCandidate() {
      // No cached response.
      //没有从自定义cache中通过request获得了对应的缓存response
      if (cacheResponse == null) {
        //对于没有自定义缓存来说，这里就简单返回null的response
        return new CacheStrategy(request, null);
      }

      // Drop the cached response if it's missing a required handshake.
      //从自定义cache中获取到response
      //该请求为https，但是TLS握手失败，可以直接认为该response是无效的
      if (request.isHttps() && cacheResponse.handshake() == null) {
        return new CacheStrategy(request, null);
      }

      // If this response shouldn't have been stored, it should never be used
      // as a response source. This check should be redundant as long as the
      // persistence store is well-behaved and the rules are constant.
      //检查当前request和response是否允许缓存，如果不允许缓存，则需要废除response
      //一般来说就是检查request和response的no-store标志
      if (!isCacheable(cacheResponse, request)) {
        return new CacheStrategy(request, null);
      }
      //下面通过获取request中的cache-control来实现最终的缓存策略
      //获取请求报头中cache-control对象
      CacheControl requestCaching = request.cacheControl();
      //在request中的cache-control指定不使用缓存no-cache或者在头部报文中指定了If-Modified-Since/If-None-Match的时候
      //认为缓存无效
      if (requestCaching.noCache() || hasConditions(request)) {
        return new CacheStrategy(request, null);
      }
      //获取缓存到当前时间总共经过的时间戳
      long ageMillis = cacheResponseAge();
      //获取response可以存活的最长时间戳
      long freshMillis = computeFreshnessLifetime();
      //当前可以存活时间戳应该为request和response中设置的max-age的最小值
      //一般可以通过这种形式直接让缓存过期
      if (requestCaching.maxAgeSeconds() != -1) {
        freshMillis = Math.min(freshMillis, SECONDS.toMillis(requestCaching.maxAgeSeconds()));
      }

      long minFreshMillis = 0;
      //最少应该剩余的有效时间，如果剩余时间小于该时间，认为缓存过期，比方说原本10s后过期，如果这个设置3s，则实际上7s后就被认为过期了，也就是说至少要保留3秒的有效期
      if (requestCaching.minFreshSeconds() != -1) {
        minFreshMillis = SECONDS.toMillis(requestCaching.minFreshSeconds());
      }

      long maxStaleMillis = 0;
      CacheControl responseCaching = cacheResponse.cacheControl();
      //在最长过期时间的基础上可以额外接收的时间，比方说原本10s后过期，如果这个设置3s，则实际上13s后才会过期
      //注意request中的max-stale只有在response中没有返回must-revalidate的前提下有效
      if (!responseCaching.mustRevalidate() && requestCaching.maxStaleSeconds() != -1) {
        maxStaleMillis = SECONDS.toMillis(requestCaching.maxStaleSeconds());
      }
      //如果允许使用缓存，并且当前缓存时间小于最长存活时间，认为该缓存依然有效
      if (!responseCaching.noCache() && ageMillis + minFreshMillis < freshMillis + maxStaleMillis) {
        Response.Builder builder = cacheResponse.newBuilder();
        if (ageMillis + minFreshMillis >= freshMillis) {
          //当前缓存是在request的max-stale前提下有效，否则已然过期
          builder.addHeader("Warning", "110 HttpURLConnection \"Response is stale\"");
        }
        long oneDayMillis = 24 * 60 * 60 * 1000L;
        if (ageMillis > oneDayMillis && isFreshnessLifetimeHeuristic()) {
          builder.addHeader("Warning", "113 HttpURLConnection \"Heuristic expiration\"");
        }
        //当前缓存有效，返回当前缓存
        return new CacheStrategy(null, builder.build());
      }
      // 缓存已经过期
      // Find a condition to add to the request. If the condition is satisfied, the response body
      // will not be transmitted.
      String conditionName;
      String conditionValue;
      if (etag != null) {
        //如果当前设置了ETAG缓存标示，通过If-None-Match可以让服务端进行校验
        conditionName = "If-None-Match";
        conditionValue = etag;
      } else if (lastModified != null) {//在没有使用ETAG标志的前提下，通过发送上一次修改或者发送时间给服务端，让服务端判断当前数据是否已经过期
        conditionName = "If-Modified-Since";
        conditionValue = lastModifiedString;
      } else if (servedDate != null) {
        conditionName = "If-Modified-Since";
        conditionValue = servedDateString;
      } else {//没有必要让服务端校验response的过期情况
        return new CacheStrategy(request, null); // No condition! Make a regular request.
      }
      //将缓存过期需要设置的信息放入request的头部报文之中
      //主要就是客户端缓存的response的时间，用于服务端判断是否过期来使用
      Headers.Builder conditionalRequestHeaders = request.headers().newBuilder();
      Internal.instance.addLenient(conditionalRequestHeaders, conditionName, conditionValue);

      Request conditionalRequest = request.newBuilder()
          .headers(conditionalRequestHeaders.build())
          .build();
      return new CacheStrategy(conditionalRequest, cacheResponse);
    }

    /**
     * Returns the number of milliseconds that the response was fresh for, starting from the served
     * date.
     * 从服务端发出response开始计算的response存活的有效时间
     */
    private long computeFreshnessLifetime() {
      CacheControl responseCaching = cacheResponse.cacheControl();
      if (responseCaching.maxAgeSeconds() != -1) {
        //如果response头部中有设定最长存活时间max-age，优先使用
        return SECONDS.toMillis(responseCaching.maxAgeSeconds());
      } else if (expires != null) {
        //如果没有设置max-age，但是有Expires字段，expires是过期时间
        long servedMillis = servedDate != null
            ? servedDate.getTime()
            : receivedResponseMillis;
        //通过过期时间减去response的发出时间，可以获取最长存活时间
        long delta = expires.getTime() - servedMillis;
        return delta > 0 ? delta : 0;
      } else if (lastModified != null
          && cacheResponse.request().url().query() == null) {
        // 在没有设置max-age和expires字段的情况下
        // As recommended by the HTTP RFC and implemented in Firefox, the
        // max age of a document should be defaulted to 10% of the
        // document's age at the time it was served. Default expiration
        // dates aren't used for URIs containing a query.
        long servedMillis = servedDate != null
            ? servedDate.getTime()
            : sentRequestMillis;
        long delta = servedMillis - lastModified.getTime();
        return delta > 0 ? (delta / 10) : 0;
      }
      return 0;
    }

    /**
     * Returns the current age of the response, in milliseconds. The calculation is specified by RFC
     * 2616, 13.2.3 Age Calculations.
     */
    private long cacheResponseAge() {
      long apparentReceivedAge = servedDate != null
          ? Math.max(0, receivedResponseMillis - servedDate.getTime())
          : 0;
      //一般来说直接以Age为准就可以了
      long receivedAge = ageSeconds != -1
          ? Math.max(apparentReceivedAge, SECONDS.toMillis(ageSeconds))
          : apparentReceivedAge;
      //计算整个请求从发出到响应的时间
      long responseDuration = receivedResponseMillis - sentRequestMillis;
      //接收到response之后到当前所经过的时间
      long residentDuration = nowMillis - receivedResponseMillis;
      //这里一般来说理解成该条response从当前到它在服务端生成所经过的时间即可
      //同样max-age的基准也要同Age，不然就有点坑了
      return receivedAge + responseDuration + residentDuration;
    }

    /**
     * Returns true if computeFreshnessLifetime used a heuristic. If we used a heuristic to serve a
     * cached response older than 24 hours, we are required to attach a warning.
     */
    private boolean isFreshnessLifetimeHeuristic() {
      return cacheResponse.cacheControl().maxAgeSeconds() == -1 && expires == null;
    }

  }
  /**
   * Returns true if the request contains conditions that save the server from sending a response
   * that the client has locally. When a request is enqueued with its own conditions, the built-in
   * response cache won't be used.
   */
  private static boolean hasConditions(Request request) {
    return request.header("If-Modified-Since") != null || request.header("If-None-Match") != null;
  }
}
