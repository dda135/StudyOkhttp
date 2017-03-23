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
package okhttp3.internal.connection;

import java.io.IOException;
import java.lang.ref.Reference;
import java.net.ConnectException;
import java.net.ProtocolException;
import java.net.Proxy;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownServiceException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import okhttp3.Address;
import okhttp3.CertificatePinner;
import okhttp3.Connection;
import okhttp3.ConnectionPool;
import okhttp3.ConnectionSpec;
import okhttp3.Handshake;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.Route;
import okhttp3.internal.Internal;
import okhttp3.internal.Util;
import okhttp3.internal.Version;
import okhttp3.internal.http.HttpCodec;
import okhttp3.internal.http.HttpHeaders;
import okhttp3.internal.http1.Http1Codec;
import okhttp3.internal.http2.ErrorCode;
import okhttp3.internal.http2.Http2Codec;
import okhttp3.internal.http2.Http2Connection;
import okhttp3.internal.http2.Http2Stream;
import okhttp3.internal.platform.Platform;
import okhttp3.internal.tls.OkHostnameVerifier;
import okhttp3.internal.ws.RealWebSocket;
import okio.BufferedSink;
import okio.BufferedSource;
import okio.Okio;
import okio.Source;

import static java.net.HttpURLConnection.HTTP_OK;
import static java.net.HttpURLConnection.HTTP_PROXY_AUTH;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static okhttp3.internal.Util.closeQuietly;

public final class RealConnection extends Http2Connection.Listener implements Connection {
  private final ConnectionPool connectionPool;
  private final Route route;

  // The fields below are initialized by connect() and never reassigned.

  /** The low-level TCP socket. */
  private Socket rawSocket;

  /**
   * The application layer socket. Either an {@link SSLSocket} layered over {@link #rawSocket}, or
   * {@link #rawSocket} itself if this connection does not use SSL.
   */
  private Socket socket;
  private Handshake handshake;
  private Protocol protocol;
  private Http2Connection http2Connection;
  private BufferedSource source;
  private BufferedSink sink;

  // The fields below track connection state and are guarded by connectionPool.

  /** If true, no new streams can be created on this connection. Once true this is always true.
   * true的话要注意将不会在当前连接中创建新的流
   * */
  public boolean noNewStreams;
  //标记当前连接的完成次数，StramFinished之类的时候有叠加
  public int successCount;

  /**
   * The maximum number of concurrent streams that can be carried by this connection. If {@code
   * allocations.size() < allocationLimit} then new streams can be created on this connection.
   */
  public int allocationLimit = 1;

  /** Current streams carried by this connection.
   * 当前连接所关联的流分配者
   * */
  public final List<Reference<StreamAllocation>> allocations = new ArrayList<>();

  /** Nanotime timestamp when {@code allocations.size()} reached zero. */
  public long idleAtNanos = Long.MAX_VALUE;

  public RealConnection(ConnectionPool connectionPool, Route route) {
    this.connectionPool = connectionPool;
    this.route = route;
  }

  public static RealConnection testConnection(
      ConnectionPool connectionPool, Route route, Socket socket, long idleAtNanos) {
    RealConnection result = new RealConnection(connectionPool, route);
    result.socket = socket;
    result.idleAtNanos = idleAtNanos;
    return result;
  }

  public void connect(
      int connectTimeout, int readTimeout, int writeTimeout, boolean connectionRetryEnabled) {
    if (protocol != null) throw new IllegalStateException("already connected");

    RouteException routeException = null;
    List<ConnectionSpec> connectionSpecs = route.address().connectionSpecs();
    ConnectionSpecSelector connectionSpecSelector = new ConnectionSpecSelector(connectionSpecs);

    if (route.address().sslSocketFactory() == null) {
      if (!connectionSpecs.contains(ConnectionSpec.CLEARTEXT)) {
        throw new RouteException(new UnknownServiceException(
            "CLEARTEXT communication not enabled for client"));
      }
      String host = route.address().url().host();
      if (!Platform.get().isCleartextTrafficPermitted(host)) {
        throw new RouteException(new UnknownServiceException(
            "CLEARTEXT communication to " + host + " not permitted by network security policy"));
      }
    }

    while (true) {//这里毫无疑问是一个阻塞性操作
      try {
        if (route.requiresTunnel()) {
          connectTunnel(connectTimeout, readTimeout, writeTimeout);
        } else {//一般来说都是NO_PROXY模式，则不会使用隧道，这里只是进行Scoket的连接
          connectSocket(connectTimeout, readTimeout);
          //至此TCP3次握手结束，并且初始化了双向流通道
        }
        //这里面主要是进行SSL握手，并且如果当前请求协议为Http2.0，则需要创建Http2Connection
        establishProtocol(connectionSpecSelector);
        break;
      } catch (IOException e) {
        closeQuietly(socket);
        closeQuietly(rawSocket);
        socket = null;
        rawSocket = null;
        source = null;
        sink = null;
        handshake = null;
        protocol = null;
        http2Connection = null;

        if (routeException == null) {
          routeException = new RouteException(e);
        } else {
          routeException.addConnectException(e);
        }
        //如果在OkHttpClient中设置不允许重连，那么在一次连接失败之后就会结束
        if (!connectionRetryEnabled || !connectionSpecSelector.connectionFailed(e)) {
          throw routeException;
        }
      }
    }
    //如果创建的连接为HTTP2.0，不同于HTTP1相关的协议
    //HTTP2.0允许一个连接当中有多个流
    //Connection中默认最多StramAllocation为1
    //在Http2下可以为无限大
    if (http2Connection != null) {
      synchronized (connectionPool) {
        allocationLimit = http2Connection.maxConcurrentStreams();
      }
    }
  }

  /**
   * Does all the work to build an HTTPS connection over a proxy tunnel. The catch here is that a
   * proxy server can issue an auth challenge and then close the connection.
   */
  private void connectTunnel(int connectTimeout, int readTimeout, int writeTimeout)
      throws IOException {
    Request tunnelRequest = createTunnelRequest();
    HttpUrl url = tunnelRequest.url();
    int attemptedConnections = 0;
    int maxAttempts = 21;
    while (true) {
      if (++attemptedConnections > maxAttempts) {
        throw new ProtocolException("Too many tunnel connections attempted: " + maxAttempts);
      }

      connectSocket(connectTimeout, readTimeout);
      tunnelRequest = createTunnel(readTimeout, writeTimeout, tunnelRequest, url);

      if (tunnelRequest == null) break; // Tunnel successfully created.

      // The proxy decided to close the connection after an auth challenge. We need to create a new
      // connection, but this time with the auth credentials.
      closeQuietly(rawSocket);
      rawSocket = null;
      sink = null;
      source = null;
    }
  }

  /** Does all the work necessary to build a full HTTP or HTTPS connection on a raw socket. */
  private void connectSocket(int connectTimeout, int readTimeout) throws IOException {
    //这里一般说都是NO_PROXY
    Proxy proxy = route.proxy();
    Address address = route.address();
    //NO_PROXY模式为DIRECT，则需要通过Client中的SocketFactory来创建Socket
    //如果没有在Client中手动设置，默认使用DefaultSocketFactory
    //其实就是new socket()而已
    rawSocket = proxy.type() == Proxy.Type.DIRECT || proxy.type() == Proxy.Type.HTTP
        ? address.socketFactory().createSocket()
        : new Socket(proxy);
    //设置Socket中InputStream的读取最长时间，简单理解就是Server的响应时间
    rawSocket.setSoTimeout(readTimeout);
    try {
      //这里本质上就是socket.connect
      //内部实际上进行的就是传统的TCP3次握手，连接时长也就是握手成功最大时长
      //这里成功之后套接字连接就完成，接下来可以进行流的操作
      Platform.get().connectSocket(rawSocket, route.socketAddress(), connectTimeout);
    } catch (ConnectException e) {
      ConnectException ce = new ConnectException("Failed to connect to " + route.socketAddress());
      ce.initCause(e);
      throw ce;
    }
    //在TCP握手结束之后，连接已经建立，之后就是建立双向的流通道
    //这里通过Okio对于socket方来说建立输入和输出流
    //输入流用于接收服务端的数据，输出流用于向服务端写入数据
    source = Okio.buffer(Okio.source(rawSocket));
    sink = Okio.buffer(Okio.sink(rawSocket));
  }

  private void establishProtocol(ConnectionSpecSelector connectionSpecSelector) throws IOException {
    //如果不是HTTPS协议，不需要进行TLS握手过程
    if (route.address().sslSocketFactory() == null) {
      protocol = Protocol.HTTP_1_1;
      socket = rawSocket;
      return;
    }
    //如果为HTTPS，进行SSL握手
    connectTls(connectionSpecSelector);

    if (protocol == Protocol.HTTP_2) {
      socket.setSoTimeout(0); // HTTP/2 connection timeouts are set per-stream.
      http2Connection = new Http2Connection.Builder(true)
          .socket(socket, route.address().url().host(), source, sink)
          .listener(this)
          .build();
      http2Connection.start();
    }
  }

  private void connectTls(ConnectionSpecSelector connectionSpecSelector) throws IOException {
    Address address = route.address();
    SSLSocketFactory sslSocketFactory = address.sslSocketFactory();
    boolean success = false;
    SSLSocket sslSocket = null;
    try {
      // Create the wrapper over the connected socket.
      // 注意此时的Socket已经完成握手过程，这里通过SSLSocketFactory来构建对应的SSLSocket
      // 如果没有手动设置，默认可以看OkHttpClient中的systemDefaultSslSocketFactory
      sslSocket = (SSLSocket) sslSocketFactory.createSocket(
          rawSocket, address.url().host(), address.url().port(), true /* autoClose */);

      // Configure the socket's ciphers, TLS versions, and extensions.
      // ConnectionSpec的默认值可以看OkHttpClient中DEFAULT_CONNECTION_SPECS
      // 此处主要是查找满足SSLSocket的TLS版本和Cipher，并且关联SSLSocket
      ConnectionSpec connectionSpec = connectionSpecSelector.configureSecureSocket(sslSocket);
      // 此处必然为true
      if (connectionSpec.supportsTlsExtensions()) {
        //注意这里的实现类是AndroidPlatform
        //这里面主要是通过反射，然后用SSLSocket调用一些方法，来实现一些设置
        //比方Session、Host和ALPN
        Platform.get().configureTlsExtensions(
            sslSocket, address.url().host(), address.protocols());
      }

      // Force handshake. This can throw!
      //开始TLS握手流程
      sslSocket.startHandshake();
      //注意getSession会阻塞直到握手完成，此时获取握手的信息
      //这个Session包括服务端返回的TLS版本、Cipher组件、证书等信息
      Handshake unverifiedHandshake = Handshake.get(sslSocket.getSession());

      // Verify that the socket's certificates are acceptable for the target host.
      // SSL握手完成之后，要尝试验证主机名，如果严重失败会抛出异常，则会一路回调到onFailure中
      if (!address.hostnameVerifier().verify(address.url().host(), sslSocket.getSession())) {
        X509Certificate cert = (X509Certificate) unverifiedHandshake.peerCertificates().get(0);
        throw new SSLPeerUnverifiedException("Hostname " + address.url().host() + " not verified:"
            + "\n    certificate: " + CertificatePinner.pin(cert)
            + "\n    DN: " + cert.getSubjectDN().getName()
            + "\n    subjectAltNames: " + OkHostnameVerifier.allSubjectAltNames(cert));
      }

      // Check that the certificate pinner is satisfied by the certificates presented.
      // CertificatePining证书锁定，用于校验证书的有效性，避免一些中间人攻击
      // 在完成主机名验证之后，此处进行的是证书认证
      // 具体可以看CertificatePinner的注释中的demo
      address.certificatePinner().check(address.url().host(),
          unverifiedHandshake.peerCertificates());

      // Success! Save the handshake and the ALPN protocol.
      // 尝试通过反射getAlpnSelectedProtocol获取ALPN协议并保存
      String maybeProtocol = connectionSpec.supportsTlsExtensions()
          ? Platform.get().getSelectedProtocol(sslSocket)
          : null;
      socket = sslSocket;
      source = Okio.buffer(Okio.source(socket));
      sink = Okio.buffer(Okio.sink(socket));
      handshake = unverifiedHandshake;
      protocol = maybeProtocol != null
          ? Protocol.get(maybeProtocol)
          : Protocol.HTTP_1_1;
      success = true;
    } catch (AssertionError e) {
      if (Util.isAndroidGetsocknameError(e)) throw new IOException(e);
      throw e;
    } finally {
      if (sslSocket != null) {
        Platform.get().afterHandshake(sslSocket);
      }
      if (!success) {
        closeQuietly(sslSocket);
      }
    }
  }

  /**
   * To make an HTTPS connection over an HTTP proxy, send an unencrypted CONNECT request to create
   * the proxy connection. This may need to be retried if the proxy requires authorization.
   */
  private Request createTunnel(int readTimeout, int writeTimeout, Request tunnelRequest,
      HttpUrl url) throws IOException {
    // Make an SSL Tunnel on the first message pair of each SSL + proxy connection.
    String requestLine = "CONNECT " + Util.hostHeader(url, true) + " HTTP/1.1";
    while (true) {
      Http1Codec tunnelConnection = new Http1Codec(null, null, source, sink);
      source.timeout().timeout(readTimeout, MILLISECONDS);
      sink.timeout().timeout(writeTimeout, MILLISECONDS);
      tunnelConnection.writeRequest(tunnelRequest.headers(), requestLine);
      tunnelConnection.finishRequest();
      Response response = tunnelConnection.readResponseHeaders(false)
          .request(tunnelRequest)
          .build();
      // The response body from a CONNECT should be empty, but if it is not then we should consume
      // it before proceeding.
      long contentLength = HttpHeaders.contentLength(response);
      if (contentLength == -1L) {
        contentLength = 0L;
      }
      Source body = tunnelConnection.newFixedLengthSource(contentLength);
      Util.skipAll(body, Integer.MAX_VALUE, TimeUnit.MILLISECONDS);
      body.close();

      switch (response.code()) {
        case HTTP_OK:
          // Assume the server won't send a TLS ServerHello until we send a TLS ClientHello. If
          // that happens, then we will have buffered bytes that are needed by the SSLSocket!
          // This check is imperfect: it doesn't tell us whether a handshake will succeed, just
          // that it will almost certainly fail because the proxy has sent unexpected data.
          if (!source.buffer().exhausted() || !sink.buffer().exhausted()) {
            throw new IOException("TLS tunnel buffered too many bytes!");
          }
          return null;

        case HTTP_PROXY_AUTH:
          tunnelRequest = route.address().proxyAuthenticator().authenticate(route, response);
          if (tunnelRequest == null) throw new IOException("Failed to authenticate with proxy");

          if ("close".equalsIgnoreCase(response.header("Connection"))) {
            return tunnelRequest;
          }
          break;

        default:
          throw new IOException(
              "Unexpected response code for CONNECT: " + response.code());
      }
    }
  }

  /**
   * Returns a request that creates a TLS tunnel via an HTTP proxy. Everything in the tunnel request
   * is sent unencrypted to the proxy server, so tunnels include only the minimum set of headers.
   * This avoids sending potentially sensitive data like HTTP cookies to the proxy unencrypted.
   */
  private Request createTunnelRequest() {
    return new Request.Builder()
        .url(route.address().url())
        .header("Host", Util.hostHeader(route.address().url(), true))
        .header("Proxy-Connection", "Keep-Alive") // For HTTP/1.0 proxies like Squid.
        .header("User-Agent", Version.userAgent())
        .build();
  }

  /**
   * Returns true if this connection can carry a stream allocation to {@code address}. If non-null
   * {@code route} is the resolved route for a connection.
   */
  public boolean isEligible(Address address, Route route) {
    // If this connection is not accepting new streams, we're done.
    // 首先确认连接中是否可以容纳更多的流（或者就不允许新建流）
    // 这里稍微注意一下，在HTTP2以前，一个Connection只能被一个StreamAllocation持有
    // HTTP2的话可以做到多个StreamAllocation
    if (allocations.size() >= allocationLimit || noNewStreams) return false;

    // If the non-host fields of the address don't overlap, we're done.
    // 检查一些参数是否一致，具体看Address中equalsNonHost
    if (!Internal.instance.equalsNonHost(this.route.address(), address)) return false;

    // If the host exactly matches, we're done: this connection can carry the address.
    //如果请求host一致，则认为当前连接可以携带此流，对于绝大部分APP来说都会满足这个条件
    if (address.url().host().equals(this.route().address().url().host())) {
      return true; // This connection is a perfect match.
    }

    // At this point we don't have a hostname match. But we still be able to carry the request if
    // our connection coalescing requirements are met. See also:
    // https://hpbn.co/optimizing-application-delivery/#eliminate-domain-sharding
    // https://daniel.haxx.se/blog/2016/08/18/http2-connection-coalescing/

    // 1. This connection must be HTTP/2.
    //在host无法匹配的情况下，http2.0下有可能可以重用连接
    if (http2Connection == null) return false;

    // 2. The routes must share an IP address. This requires us to have a DNS address for both
    // hosts, which only happens after route planning. We can't coalesce connections that use a
    // proxy, since proxies don't tell us the origin server's IP address.
    //在这种情况下必须要保证IP地址是一致的，这个要求不能够使用代理，因为这样我们无法正确的获得服务端的IP地址
    //但是通过传统的DNS模式是可以的
    if (route == null) return false;
    //一般来说都是NO_PROXY模式
    if (route.proxy().type() != Proxy.Type.DIRECT) return false;
    if (this.route.proxy().type() != Proxy.Type.DIRECT) return false;
    //在获取route的时候已经通过InetAddress去访问DNS获取到对应的IP地址，这里可以认为是在对比IP地址是否一致
    if (!this.route.socketAddress().equals(route.socketAddress())) return false;

    // 3. This connection's server certificate's must cover the new host.
    // 不同的Host情况下，比方说www.ba.com和www.ca.com，其实它们有着同样的IP地址
    // 在Http2.0的情况下，可能可以使用同一个连接
    // 但是因为有握手过程，所以要求主机名认证必须同时通过这两个Host，所以不能采用默认的认证
    // 默认只是校验主机名的合法性而已
    if (route.address().hostnameVerifier() != OkHostnameVerifier.INSTANCE) return false;
    // 在端口一致的前提下
    // 要求之前必须有一个Host成功完成握手过程，然后再次验签通过
    if (!supportsUrl(address.url())) return false;

    // 4. Certificate pinning must match the host.
    try {
      //验证证书的合理性，这个要在OkHttpClient中配置证书对应的“身份证”
      address.certificatePinner().check(address.url().host(), handshake().peerCertificates());
    } catch (SSLPeerUnverifiedException e) {
      return false;
    }

    return true; // The caller's address can be carried by this connection.
  }

  public boolean supportsUrl(HttpUrl url) {
    if (url.port() != route.address().url().port()) {
      return false; // Port mismatch.
    }

    if (!url.host().equals(route.address().url().host())) {
      // We have a host mismatch. But if the certificate matches, we're still good.
      return handshake != null && OkHostnameVerifier.INSTANCE.verify(
          url.host(), (X509Certificate) handshake.peerCertificates().get(0));
    }

    return true; // Success. The URL is supported.
  }

  public HttpCodec newCodec(
      OkHttpClient client, StreamAllocation streamAllocation) throws SocketException {
    //注意一个小区别，HTTP2在一次TCP连接中可能有多个流操作，对应的读取/写入超时时间应该从开始写入头部报文开始
    //此时一个流操作正式开始
    //对于HTTP1.1来说，一次TCP连接中虽然也可以有多次请求，但是流操作是在同一个流中进行的
    //那么完全可以在初始化的时候设置读取/写入超时时间
    if (http2Connection != null) {//HTTP2.0
      return new Http2Codec(client, streamAllocation, http2Connection);
    } else {
      socket.setSoTimeout(client.readTimeoutMillis());
      source.timeout().timeout(client.readTimeoutMillis(), MILLISECONDS);
      sink.timeout().timeout(client.writeTimeoutMillis(), MILLISECONDS);
      return new Http1Codec(client, streamAllocation, source, sink);
    }
  }

  public RealWebSocket.Streams newWebSocketStreams(final StreamAllocation streamAllocation) {
    return new RealWebSocket.Streams(true, source, sink) {
      @Override public void close() throws IOException {
        streamAllocation.streamFinished(true, streamAllocation.codec());
      }
    };
  }

  @Override public Route route() {
    return route;
  }

  public void cancel() {
    // Close the raw socket so we don't end up doing synchronous I/O.
    closeQuietly(rawSocket);
  }

  @Override public Socket socket() {
    return socket;
  }

  /** Returns true if this connection is ready to host new streams. */
  public boolean isHealthy(boolean doExtensiveChecks) {
    //检查socket连接和流是否已经关闭
    if (socket.isClosed() || socket.isInputShutdown() || socket.isOutputShutdown()) {
      return false;
    }
    //如果是Http2.0，看一下Connection是否关闭就好
    if (http2Connection != null) {
      return !http2Connection.isShutdown();
    }
    //当这里都不是Http2.0
    if (doExtensiveChecks) {//POST这里为true
      try {
        //具体检查方式可以看注释
        //大概就是改变socket的读取时间为最小
        //然后尝试去
        int readTimeout = socket.getSoTimeout();
        try {
          socket.setSoTimeout(1);
          if (source.exhausted()) {
            return false; // Stream is exhausted; socket is closed.
          }
          return true;
        } finally {
          socket.setSoTimeout(readTimeout);
        }
      } catch (SocketTimeoutException ignored) {
        // Read timed out; socket is good.
      } catch (IOException e) {
        return false; // Couldn't read; socket is closed.
      }
    }

    return true;
  }

  /** Refuse incoming streams. */
  @Override public void onStream(Http2Stream stream) throws IOException {
    stream.close(ErrorCode.REFUSED_STREAM);
  }

  /** When settings are received, adjust the allocation limit. */
  @Override public void onSettings(Http2Connection connection) {
    synchronized (connectionPool) {
      allocationLimit = connection.maxConcurrentStreams();
    }
  }

  @Override public Handshake handshake() {
    return handshake;
  }

  /**
   * Returns true if this is an HTTP/2 connection. Such connections can be used in multiple HTTP
   * requests simultaneously.
   */
  public boolean isMultiplexed() {
    return http2Connection != null;
  }

  @Override public Protocol protocol() {
    return protocol;
  }

  @Override public String toString() {
    return "Connection{"
        + route.address().url().host() + ":" + route.address().url().port()
        + ", proxy="
        + route.proxy()
        + " hostAddress="
        + route.socketAddress()
        + " cipherSuite="
        + (handshake != null ? handshake.cipherSuite() : "none")
        + " protocol="
        + protocol
        + '}';
  }
}
