/*
 * Copyright (C) 2012 The Android Open Source Project
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
package okhttp3.internal.http1;

import java.io.EOFException;
import java.io.IOException;
import java.net.ProtocolException;
import okhttp3.Headers;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okhttp3.internal.Internal;
import okhttp3.internal.Util;
import okhttp3.internal.connection.RealConnection;
import okhttp3.internal.connection.StreamAllocation;
import okhttp3.internal.http.HttpCodec;
import okhttp3.internal.http.HttpHeaders;
import okhttp3.internal.http.RealResponseBody;
import okhttp3.internal.http.RequestLine;
import okhttp3.internal.http.StatusLine;
import okio.Buffer;
import okio.BufferedSink;
import okio.BufferedSource;
import okio.ForwardingTimeout;
import okio.Okio;
import okio.Sink;
import okio.Source;
import okio.Timeout;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static okhttp3.internal.Util.checkOffsetAndCount;
import static okhttp3.internal.http.StatusLine.HTTP_CONTINUE;

/**
 * A socket connection that can be used to send HTTP/1.1 messages. This class strictly enforces the
 * following lifecycle:
 *
 * <ol>
 *     <li>{@linkplain #writeRequest Send request headers}.
 *     <li>Open a sink to write the request body. Either {@linkplain #newFixedLengthSink
 *         fixed-length} or {@link #newChunkedSink chunked}.
 *     <li>Write to and then close that sink.
 *     <li>{@linkplain #readResponseHeaders Read response headers}.
 *     <li>Open a source to read the response body. Either {@linkplain #newFixedLengthSource
 *         fixed-length}, {@linkplain #newChunkedSource chunked} or {@linkplain
 *         #newUnknownLengthSource unknown length}.
 *     <li>Read from and close that source.
 * </ol>
 *
 * <p>Exchanges that do not have a request body may skip creating and closing the request body.
 * Exchanges that do not have a response body can call {@link #newFixedLengthSource(long)
 * newFixedLengthSource(0)} and may skip reading and closing that source.
 */
public final class Http1Codec implements HttpCodec {
  private static final int STATE_IDLE = 0; // Idle connections are ready to write request headers.
  private static final int STATE_OPEN_REQUEST_BODY = 1;
  private static final int STATE_WRITING_REQUEST_BODY = 2;
  private static final int STATE_READ_RESPONSE_HEADERS = 3;
  private static final int STATE_OPEN_RESPONSE_BODY = 4;
  private static final int STATE_READING_RESPONSE_BODY = 5;
  private static final int STATE_CLOSED = 6;

  /** The client that configures this stream. May be null for HTTPS proxy tunnels. */
  final OkHttpClient client;
  /** The stream allocation that owns this stream. May be null for HTTPS proxy tunnels. */
  final StreamAllocation streamAllocation;

  final BufferedSource source;
  final BufferedSink sink;
  int state = STATE_IDLE;

  public Http1Codec(OkHttpClient client, StreamAllocation streamAllocation, BufferedSource source,
      BufferedSink sink) {
    this.client = client;
    this.streamAllocation = streamAllocation;
    this.source = source;
    this.sink = sink;
  }

  @Override public Sink createRequestBody(Request request, long contentLength) {
    if ("chunked".equalsIgnoreCase(request.header("Transfer-Encoding"))) {
      // 此处可以回想一下BridgeInterceptor中的情况，Transfer-Encoding: chunked
      // 相当于在RequestBody中的Content-Length为-1，简单理解就是无法预测长度
      // Stream a request body of unknown length.
      // 会根据需要一直读
      return newChunkedSink();
    }

    if (contentLength != -1) {
      // 只能读取指定的字节数
      // Stream a request body of a known length.
      return newFixedLengthSink(contentLength);
    }

    throw new IllegalStateException(
        "Cannot stream a request body without chunked encoding or a known content length!");
  }

  @Override public void cancel() {
    RealConnection connection = streamAllocation.connection();
    if (connection != null) connection.cancel();
  }

  /**
   * Prepares the HTTP headers and sends them to the server.
   *
   * <p>For streaming requests with a body, headers must be prepared <strong>before</strong> the
   * output stream has been written to. Otherwise the body would need to be buffered!
   *
   * <p>For non-streaming requests with a body, headers must be prepared <strong>after</strong> the
   * output stream has been written to and closed. This ensures that the {@code Content-Length}
   * header field receives the proper value.
   */
  @Override public void writeRequestHeaders(Request request) throws IOException {
    //拼接请求报文中起始行数据
    String requestLine = RequestLine.get(
        request, streamAllocation.connection().route().proxy().type());
    //写入起始行和头部报文数据
    writeRequest(request.headers(), requestLine);
  }

  @Override public ResponseBody openResponseBody(Response response) throws IOException {
    Source source = getTransferStream(response);
    return new RealResponseBody(response.headers(), Okio.buffer(source));
  }

  private Source getTransferStream(Response response) throws IOException {
    if (!HttpHeaders.hasBody(response)) {
      return newFixedLengthSource(0);
    }
    //分块传输模式，一般就是不确定当前数据大小的时候使用
    if ("chunked".equalsIgnoreCase(response.header("Transfer-Encoding"))) {
      return newChunkedSource(response.request().url());
    }
    //固定大小，会指定在头部报文的Content-Length中
    long contentLength = HttpHeaders.contentLength(response);
    if (contentLength != -1) {
      return newFixedLengthSource(contentLength);
    }

    // Wrap the input stream from the connection (rather than just returning
    // "socketIn" directly here), so that we can control its use after the
    // reference escapes.
    return newUnknownLengthSource();
  }

  /** Returns true if this connection is closed. */
  public boolean isClosed() {
    return state == STATE_CLOSED;
  }

  @Override public void flushRequest() throws IOException {
    sink.flush();
  }

  @Override public void finishRequest() throws IOException {
    sink.flush();
  }

  /** Returns bytes of a request header for sending on an HTTP transport. */
  public void writeRequest(Headers headers, String requestLine) throws IOException {
    //稍微注意一下这个状态标记是用于处理当前报文的发送流程的，在HTTP1.1下请求报文要满足先协议报文后正文
    if (state != STATE_IDLE) throw new IllegalStateException("state: " + state);
    //起始行结束需要换行操作
    sink.writeUtf8(requestLine).writeUtf8("\r\n");
    //换行之后要开始写入头部报文数据
    for (int i = 0, size = headers.size(); i < size; i++) {
      //基本格式：
      //name: value
      //name1: value1
      //...
      sink.writeUtf8(headers.name(i))
          .writeUtf8(": ")
          .writeUtf8(headers.value(i))
          .writeUtf8("\r\n");
    }
    //最后与正文体之间的识别符是一个空行
    sink.writeUtf8("\r\n");
    //标记状态允许写入请求体
    state = STATE_OPEN_REQUEST_BODY;
  }

  @Override public Response.Builder readResponseHeaders(boolean expectContinue) throws IOException {
    //正常来说此时的状态都是STATE_OPEN_REQUEST_BODY
    if (state != STATE_OPEN_REQUEST_BODY && state != STATE_READ_RESPONSE_HEADERS) {
      throw new IllegalStateException("state: " + state);
    }
    //毫无疑问，此处是等待阻塞性操作
    try {
      //读取响应的起始行
      //parse内部是解析协议、状态码等参数，然后设置到StatusLine中，具体解析可以看parse函数
      StatusLine statusLine = StatusLine.parse(source.readUtf8LineStrict());

      Response.Builder responseBuilder = new Response.Builder()
          .protocol(statusLine.protocol)
          .code(statusLine.code)
          .message(statusLine.message)
          .headers(readHeaders());//此处是读取并解析响应的头部报文

      if (expectContinue && statusLine.code == HTTP_CONTINUE) {
        return null;
      }

      state = STATE_OPEN_RESPONSE_BODY;
      return responseBuilder;
    } catch (EOFException e) {
      // Provide more context if the server ends the stream before sending a response.
      IOException exception = new IOException("unexpected end of stream on " + streamAllocation);
      exception.initCause(e);
      throw exception;
    }
  }

  /** Reads headers or trailers. */
  public Headers readHeaders() throws IOException {
    Headers.Builder headers = new Headers.Builder();
    // parse the result headers until the first blank line
    for (String line; (line = source.readUtf8LineStrict()).length() != 0; ) {
      Internal.instance.addLenient(headers, line);
    }
    return headers.build();
  }

  public Sink newChunkedSink() {
    if (state != STATE_OPEN_REQUEST_BODY) throw new IllegalStateException("state: " + state);
    state = STATE_WRITING_REQUEST_BODY;
    return new ChunkedSink();
  }

  public Sink newFixedLengthSink(long contentLength) {
    if (state != STATE_OPEN_REQUEST_BODY) throw new IllegalStateException("state: " + state);
    state = STATE_WRITING_REQUEST_BODY;
    return new FixedLengthSink(contentLength);
  }

  public Source newFixedLengthSource(long length) throws IOException {
    if (state != STATE_OPEN_RESPONSE_BODY) throw new IllegalStateException("state: " + state);
    state = STATE_READING_RESPONSE_BODY;
    return new FixedLengthSource(length);
  }

  public Source newChunkedSource(HttpUrl url) throws IOException {
    if (state != STATE_OPEN_RESPONSE_BODY) throw new IllegalStateException("state: " + state);
    state = STATE_READING_RESPONSE_BODY;
    return new ChunkedSource(url);
  }

  public Source newUnknownLengthSource() throws IOException {
    if (state != STATE_OPEN_RESPONSE_BODY) throw new IllegalStateException("state: " + state);
    if (streamAllocation == null) throw new IllegalStateException("streamAllocation == null");
    state = STATE_READING_RESPONSE_BODY;
    streamAllocation.noNewStreams();
    return new UnknownLengthSource();
  }

  /**
   * Sets the delegate of {@code timeout} to {@link Timeout#NONE} and resets its underlying timeout
   * to the default configuration. Use this to avoid unexpected sharing of timeouts between pooled
   * connections.
   */
  void detachTimeout(ForwardingTimeout timeout) {
    Timeout oldDelegate = timeout.delegate();
    timeout.setDelegate(Timeout.NONE);
    oldDelegate.clearDeadline();
    oldDelegate.clearTimeout();
  }

  /** An HTTP body with a fixed length known in advance. */
  private final class FixedLengthSink implements Sink {
    private final ForwardingTimeout timeout = new ForwardingTimeout(sink.timeout());
    private boolean closed;
    private long bytesRemaining;

    FixedLengthSink(long bytesRemaining) {
      this.bytesRemaining = bytesRemaining;
    }

    @Override public Timeout timeout() {
      return timeout;
    }

    @Override public void write(Buffer source, long byteCount) throws IOException {
      if (closed) throw new IllegalStateException("closed");
      checkOffsetAndCount(source.size(), 0, byteCount);
      if (byteCount > bytesRemaining) {
        throw new ProtocolException("expected " + bytesRemaining
            + " bytes but received " + byteCount);
      }
      sink.write(source, byteCount);
      bytesRemaining -= byteCount;
    }

    @Override public void flush() throws IOException {
      if (closed) return; // Don't throw; this stream might have been closed on the caller's behalf.
      sink.flush();
    }

    @Override public void close() throws IOException {
      if (closed) return;
      closed = true;
      if (bytesRemaining > 0) throw new ProtocolException("unexpected end of stream");
      detachTimeout(timeout);
      state = STATE_READ_RESPONSE_HEADERS;
    }
  }

  /**
   * An HTTP body with alternating chunk sizes and chunk bodies. It is the caller's responsibility
   * to buffer chunks; typically by using a buffered sink with this sink.
   */
  private final class ChunkedSink implements Sink {
    private final ForwardingTimeout timeout = new ForwardingTimeout(sink.timeout());
    private boolean closed;

    ChunkedSink() {
    }

    @Override public Timeout timeout() {
      return timeout;
    }

    @Override public void write(Buffer source, long byteCount) throws IOException {
      if (closed) throw new IllegalStateException("closed");
      if (byteCount == 0) return;

      sink.writeHexadecimalUnsignedLong(byteCount);
      sink.writeUtf8("\r\n");
      sink.write(source, byteCount);
      sink.writeUtf8("\r\n");
    }

    @Override public synchronized void flush() throws IOException {
      if (closed) return; // Don't throw; this stream might have been closed on the caller's behalf.
      sink.flush();
    }

    @Override public synchronized void close() throws IOException {
      if (closed) return;
      closed = true;
      sink.writeUtf8("0\r\n\r\n");
      detachTimeout(timeout);
      state = STATE_READ_RESPONSE_HEADERS;
    }
  }

  private abstract class AbstractSource implements Source {
    protected final ForwardingTimeout timeout = new ForwardingTimeout(source.timeout());
    protected boolean closed;

    @Override public Timeout timeout() {
      return timeout;
    }

    /**
     * Closes the cache entry and makes the socket available for reuse. This should be invoked when
     * the end of the body has been reached.
     */
    protected final void endOfInput(boolean reuseConnection) throws IOException {
      if (state == STATE_CLOSED) return;
      if (state != STATE_READING_RESPONSE_BODY) throw new IllegalStateException("state: " + state);

      detachTimeout(timeout);

      state = STATE_CLOSED;
      if (streamAllocation != null) {
        streamAllocation.streamFinished(!reuseConnection, Http1Codec.this);
      }
    }
  }

  /** An HTTP body with a fixed length specified in advance. */
  private class FixedLengthSource extends AbstractSource {
    private long bytesRemaining;

    public FixedLengthSource(long length) throws IOException {
      bytesRemaining = length;
      if (bytesRemaining == 0) {
        endOfInput(true);
      }
    }

    @Override public long read(Buffer sink, long byteCount) throws IOException {
      if (byteCount < 0) throw new IllegalArgumentException("byteCount < 0: " + byteCount);
      if (closed) throw new IllegalStateException("closed");
      if (bytesRemaining == 0) return -1;

      long read = source.read(sink, Math.min(bytesRemaining, byteCount));
      if (read == -1) {
        endOfInput(false); // The server didn't supply the promised content length.
        throw new ProtocolException("unexpected end of stream");
      }

      bytesRemaining -= read;
      if (bytesRemaining == 0) {//当前数据读完了
        //实际上关注这里就好了，一旦数据读取完成，这里就会去处理连接完成
        //true的话表示连接可以复用
        //会释放连接的引用，标记连接池中当前连接可以复用了
        endOfInput(true);
      }
      return read;
    }

    @Override public void close() throws IOException {
      if (closed) return;

      if (bytesRemaining != 0 && !Util.discard(this, DISCARD_STREAM_TIMEOUT_MILLIS, MILLISECONDS)) {
        endOfInput(false);
      }

      closed = true;
    }
  }

  /** An HTTP body with alternating chunk sizes and chunk bodies. */
  private class ChunkedSource extends AbstractSource {
    private static final long NO_CHUNK_YET = -1L;
    private final HttpUrl url;
    private long bytesRemainingInChunk = NO_CHUNK_YET;
    private boolean hasMoreChunks = true;

    ChunkedSource(HttpUrl url) {
      this.url = url;
    }

    @Override public long read(Buffer sink, long byteCount) throws IOException {
      if (byteCount < 0) throw new IllegalArgumentException("byteCount < 0: " + byteCount);
      if (closed) throw new IllegalStateException("closed");
      if (!hasMoreChunks) return -1;

      if (bytesRemainingInChunk == 0 || bytesRemainingInChunk == NO_CHUNK_YET) {
        readChunkSize();
        if (!hasMoreChunks) return -1;
      }

      long read = source.read(sink, Math.min(byteCount, bytesRemainingInChunk));
      if (read == -1) {
        endOfInput(false); // The server didn't supply the promised chunk length.
        throw new ProtocolException("unexpected end of stream");
      }
      bytesRemainingInChunk -= read;
      return read;
    }

    private void readChunkSize() throws IOException {
      // Read the suffix of the previous chunk.
      if (bytesRemainingInChunk != NO_CHUNK_YET) {
        source.readUtf8LineStrict();
      }
      try {
        bytesRemainingInChunk = source.readHexadecimalUnsignedLong();
        String extensions = source.readUtf8LineStrict().trim();
        if (bytesRemainingInChunk < 0 || (!extensions.isEmpty() && !extensions.startsWith(";"))) {
          throw new ProtocolException("expected chunk size and optional extensions but was \""
              + bytesRemainingInChunk + extensions + "\"");
        }
      } catch (NumberFormatException e) {
        throw new ProtocolException(e.getMessage());
      }
      if (bytesRemainingInChunk == 0L) {
        hasMoreChunks = false;
        HttpHeaders.receiveHeaders(client.cookieJar(), url, readHeaders());
        endOfInput(true);
      }
    }

    @Override public void close() throws IOException {
      if (closed) return;
      if (hasMoreChunks && !Util.discard(this, DISCARD_STREAM_TIMEOUT_MILLIS, MILLISECONDS)) {
        endOfInput(false);
      }
      closed = true;
    }
  }

  /** An HTTP message body terminated by the end of the underlying stream. */
  private class UnknownLengthSource extends AbstractSource {
    private boolean inputExhausted;

    UnknownLengthSource() {
    }

    @Override public long read(Buffer sink, long byteCount)
        throws IOException {
      if (byteCount < 0) throw new IllegalArgumentException("byteCount < 0: " + byteCount);
      if (closed) throw new IllegalStateException("closed");
      if (inputExhausted) return -1;

      long read = source.read(sink, byteCount);
      if (read == -1) {
        inputExhausted = true;
        endOfInput(true);
        return -1;
      }
      return read;
    }

    @Override public void close() throws IOException {
      if (closed) return;
      if (!inputExhausted) {
        endOfInput(false);
      }
      closed = true;
    }
  }
}
