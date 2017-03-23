/*
 * Copyright (C) 2015 Square, Inc.
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
package okhttp3.internal.connection;

import java.io.IOException;
import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.net.Socket;
import okhttp3.Address;
import okhttp3.ConnectionPool;
import okhttp3.OkHttpClient;
import okhttp3.Route;
import okhttp3.internal.Internal;
import okhttp3.internal.Util;
import okhttp3.internal.http.HttpCodec;
import okhttp3.internal.http2.ConnectionShutdownException;
import okhttp3.internal.http2.ErrorCode;
import okhttp3.internal.http2.StreamResetException;

import static okhttp3.internal.Util.closeQuietly;

/**
 * This class coordinates the relationship between three entities:
 *
 * <ul>
 *     <li><strong>Connections:</strong> physical socket connections to remote servers. These are
 *         potentially slow to establish so it is necessary to be able to cancel a connection
 *         currently being connected.
 *     <li><strong>Streams:</strong> logical HTTP request/response pairs that are layered on
 *         connections. Each connection has its own allocation limit, which defines how many
 *         concurrent streams that connection can carry. HTTP/1.x connections can carry 1 stream
 *         at a time, HTTP/2 typically carry multiple.
 *     <li><strong>Calls:</strong> a logical sequence of streams, typically an initial request and
 *         its follow up requests. We prefer to keep all streams of a single call on the same
 *         connection for better behavior and locality.
 * </ul>
 *
 * <p>Instances of this class act on behalf of the call, using one or more streams over one or more
 * connections. This class has APIs to release each of the above resources:
 *
 * <ul>
 *     <li>{@link #noNewStreams()} prevents the connection from being used for new streams in the
 *         future. Use this after a {@code Connection: close} header, or when the connection may be
 *         inconsistent.
 *     <li>{@link #streamFinished streamFinished()} releases the active stream from this allocation.
 *         Note that only one stream may be active at a given time, so it is necessary to call
 *         {@link #streamFinished streamFinished()} before creating a subsequent stream with {@link
 *         #newStream newStream()}.
 *     <li>{@link #release()} removes the call's hold on the connection. Note that this won't
 *         immediately free the connection if there is a stream still lingering. That happens when a
 *         call is complete but its response body has yet to be fully consumed.
 * </ul>
 *
 * <p>This class supports {@linkplain #cancel asynchronous canceling}. This is intended to have the
 * smallest blast radius possible. If an HTTP/2 stream is active, canceling will cancel that stream
 * but not the other streams sharing its connection. But if the TLS handshake is still in progress
 * then canceling may break the entire connection.
 */
public final class StreamAllocation {
  public final Address address;
  private Route route;
  private final ConnectionPool connectionPool;
  private final Object callStackTrace;

  // State guarded by connectionPool.
  private final RouteSelector routeSelector;
  private int refusedStreamCount;
  private RealConnection connection;
  private boolean released;
  private boolean canceled;
  private HttpCodec codec;

  public StreamAllocation(ConnectionPool connectionPool, Address address, Object callStackTrace) {
    this.connectionPool = connectionPool;
    this.address = address;
    this.routeSelector = new RouteSelector(address, routeDatabase());
    this.callStackTrace = callStackTrace;
  }

  /**
   * 正常流程中，会在ConnectInterceptor中调用，创建一个新的连接
   * @param doExtensiveHealthChecks true说明不是GET请求
     */
  public HttpCodec newStream(OkHttpClient client, boolean doExtensiveHealthChecks) {
    //对于一般APP来说都是POST操作，则doExtensiveHealthChecks为true
    //获取之前在client中设定的连接、读取和输出超时时长
    int connectTimeout = client.connectTimeoutMillis();
    int readTimeout = client.readTimeoutMillis();
    int writeTimeout = client.writeTimeoutMillis();
    //获取之前在client中设置的是否允许重新连接
    boolean connectionRetryEnabled = client.retryOnConnectionFailure();

    try {
      //尝试从连接池中获取可以复用的连接，相对来说Http2.0在这方面支持力度更大
      //如果获取到可以复用的连接，但是要检查当前连接对应的socket的可用性
      RealConnection resultConnection = findHealthyConnection(connectTimeout, readTimeout,
          writeTimeout, connectionRetryEnabled, doExtensiveHealthChecks);
      //根据HTTP2/HTTP1获取对应的请求和响应处理类（因为协议的不同，所以有所区别）
      HttpCodec resultCodec = resultConnection.newCodec(client, this);

      synchronized (connectionPool) {//标记当前流的请求和响应实现类
        codec = resultCodec;
        return resultCodec;
      }
    } catch (IOException e) {
      throw new RouteException(e);
    }
  }

  /**
   * Finds a connection and returns it if it is healthy. If it is unhealthy the process is repeated
   * until a healthy connection is found.
   * 会尝试寻找一个健康可用的连接（内部会一直循环直到找到一个健康的连接）。
   */
  private RealConnection findHealthyConnection(int connectTimeout, int readTimeout,
      int writeTimeout, boolean connectionRetryEnabled, boolean doExtensiveHealthChecks)
      throws IOException {
    //此处通过循环直到成功获取连接为止
    while (true) {
      RealConnection candidate = findConnection(connectTimeout, readTimeout, writeTimeout,
          connectionRetryEnabled);

      // If this is a brand new connection, we can skip the extensive health checks.
      synchronized (connectionPool) {
        //如果当前连接是新建的，可以直接省略健康的检查
        if (candidate.successCount == 0) {
          return candidate;
        }
      }

      // Do a (potentially slow) check to confirm that the pooled connection is still good. If it
      // isn't, take it out of the pool and start again.
      // 因为当前Connection之前已经完成过流的传输，这里要尝试检查连接是否还可用
      if (!candidate.isHealthy(doExtensiveHealthChecks)) {
        //当前Connection已经不再健康，关闭Socket和释放连接资源
        noNewStreams();
        //继续查找其它可用的连接
        continue;
      }

      return candidate;
    }
  }

  /**
   * Returns a connection to host a new stream. This prefers the existing connection if it exists,
   * then the pool, finally building a new connection.
   * 返回一个连接对应的新流。
   * 会尝试从连接池中获取已经存在的连接，实在没有才会重新建立一个连接
   */
  private RealConnection findConnection(int connectTimeout, int readTimeout, int writeTimeout,
      boolean connectionRetryEnabled) throws IOException {
    Route selectedRoute;
    synchronized (connectionPool) {//此处获取了连接池的锁，因为尝试从连接池中获取可以复用的连接，应该可以理解为消费者模型
      if (released) throw new IllegalStateException("released");
      if (canceled) throw new IOException("Canceled");
      if (codec != null) throw new IllegalStateException("codec != null");

      // Attempt to use an already-allocated connection.
      //首先注意这里connection初始一般为空
      //这里主要就是分配连接的策略
      RealConnection allocatedConnection = this.connection;
      if (allocatedConnection != null && !allocatedConnection.noNewStreams) {
        return allocatedConnection;
      }

      // Attempt to get a connection from the pool.
      //尝试从连接池中获取一个连接
      //这里其实就是调用connectionPoll.get方法
      Internal.instance.get(connectionPool, address, this, null);
      if (connection != null) {//从连接池中复用成功
        return connection;
      }
      //这里注意一下route一开始都是空
      selectedRoute = route;
    }

    // If we need a route, make one. This is a blocking operation.
    // 内部实际上有通过InetAddress通过Host来查找IP，这是为了Socket连接提供IP和端口
    if (selectedRoute == null) {
      selectedRoute = routeSelector.next();
    }

    RealConnection result;
    synchronized (connectionPool) {
      if (canceled) throw new IOException("Canceled");

      // Now that we have an IP address, make another attempt at getting a connection from the pool.
      // This could match due to connection coalescing.
      // 在Host不匹配的情况下，可以再次通过IP地址来进行查找可复用的连接
      Internal.instance.get(connectionPool, address, this, selectedRoute);
      if (connection != null) return connection;

      // Create a connection and assign it to this allocation immediately. This makes it possible
      // for an asynchronous cancel() to interrupt the handshake we're about to do.
      route = selectedRoute;
      refusedStreamCount = 0;
      //当前连接池中没有可重用的流，于是只能新建流
      result = new RealConnection(connectionPool, selectedRoute);
      //关联当前分配者分配的Connection
      acquire(result);
    }
    // 因为没有可以复用的连接，这里进行TCP和TLS的握手
    // Do TCP + TLS handshakes. This is a blocking operation.
    result.connect(connectTimeout, readTimeout, writeTimeout, connectionRetryEnabled);
    routeDatabase().connected(result.route());

    Socket socket = null;
    synchronized (connectionPool) {
      // Pool the connection.
      // 将当前新建的连接放入连接池中
      Internal.instance.put(connectionPool, result);

      // If another multiplexed connection to the same address was created concurrently, then
      // release this connection and acquire that one.
      // 如果当前新建的连接协议为http2.0，因为http2.0一个TCP连接中可以接受多个流同时进行
      // 所以可以清理掉连接池中一些多余的连接，这里只清理一个
      // 并且将之前用的socket和流都转到当前新建的Connection中
      if (result.isMultiplexed()) {
        socket = Internal.instance.deduplicate(connectionPool, address, this);
        result = connection;
      }
    }
    //如果上面有被转移的流，可以关闭旧的socket连接
    closeQuietly(socket);

    return result;
  }

  public void streamFinished(boolean noNewStreams, HttpCodec codec) {
    Socket socket;
    synchronized (connectionPool) {
      if (codec == null || codec != this.codec) {
        throw new IllegalStateException("expected " + this.codec + " but was " + codec);
      }
      if (!noNewStreams) {
        connection.successCount++;
      }
      socket = deallocate(noNewStreams, false, true);
    }
    closeQuietly(socket);
  }

  public HttpCodec codec() {
    synchronized (connectionPool) {
      return codec;
    }
  }

  private RouteDatabase routeDatabase() {
    return Internal.instance.routeDatabase(connectionPool);
  }

  public synchronized RealConnection connection() {
    return connection;
  }

  public void release() {
    Socket socket;
    synchronized (connectionPool) {
      socket = deallocate(false, true, false);
    }
    closeQuietly(socket);
  }

  /** Forbid new streams from being created on the connection that hosts this allocation. */
  public void noNewStreams() {
    Socket socket;
    synchronized (connectionPool) {
      socket = deallocate(true, false, false);
    }
    closeQuietly(socket);
  }

  /**
   * Releases resources held by this allocation. If sufficient resources are allocated, the
   * connection will be detached or closed. Callers must be synchronized on the connection pool.
   *
   * <p>Returns a closeable that the caller should pass to {@link Util#closeQuietly} upon completion
   * of the synchronized block. (We don't do I/O while synchronized on the connection pool.)
   */
  private Socket deallocate(boolean noNewStreams, boolean released, boolean streamFinished) {
    assert (Thread.holdsLock(connectionPool));

    if (streamFinished) {
      this.codec = null;
    }
    if (released) {
      this.released = true;
    }
    Socket socket = null;
    if (connection != null) {
      if (noNewStreams) {
        connection.noNewStreams = true;
      }
      if (this.codec == null && (this.released || connection.noNewStreams)) {
        release(connection);
        if (connection.allocations.isEmpty()) {
          connection.idleAtNanos = System.nanoTime();
          if (Internal.instance.connectionBecameIdle(connectionPool, connection)) {
            socket = connection.socket();
          }
        }
        connection = null;
      }
    }
    return socket;
  }

  public void cancel() {
    HttpCodec codecToCancel;
    RealConnection connectionToCancel;
    synchronized (connectionPool) {
      canceled = true;
      codecToCancel = codec;
      connectionToCancel = connection;
    }
    if (codecToCancel != null) {
      codecToCancel.cancel();
    } else if (connectionToCancel != null) {
      connectionToCancel.cancel();
    }
  }

  public void streamFailed(IOException e) {
    Socket socket;
    boolean noNewStreams = false;

    synchronized (connectionPool) {
      if (e instanceof StreamResetException) {
        StreamResetException streamResetException = (StreamResetException) e;
        if (streamResetException.errorCode == ErrorCode.REFUSED_STREAM) {
          refusedStreamCount++;
        }
        // On HTTP/2 stream errors, retry REFUSED_STREAM errors once on the same connection. All
        // other errors must be retried on a new connection.
        if (streamResetException.errorCode != ErrorCode.REFUSED_STREAM || refusedStreamCount > 1) {
          noNewStreams = true;
          route = null;
        }
      } else if (connection != null
          && (!connection.isMultiplexed() || e instanceof ConnectionShutdownException)) {
        noNewStreams = true;

        // If this route hasn't completed a call, avoid it for new connections.
        if (connection.successCount == 0) {
          if (route != null && e != null) {
            routeSelector.connectFailed(route, e);
          }
          route = null;
        }
      }
      socket = deallocate(noNewStreams, false, true);
    }

    closeQuietly(socket);
  }

  /**
   * Use this allocation to hold {@code connection}. Each call to this must be paired with a call to
   * {@link #release} on the same connection.
   */
  public void acquire(RealConnection connection) {
    assert (Thread.holdsLock(connectionPool));
    if (this.connection != null) throw new IllegalStateException();

    this.connection = connection;
    connection.allocations.add(new StreamAllocationReference(this, callStackTrace));
  }

  /** Remove this allocation from the connection's list of allocations. */
  private void release(RealConnection connection) {
    for (int i = 0, size = connection.allocations.size(); i < size; i++) {
      Reference<StreamAllocation> reference = connection.allocations.get(i);
      if (reference.get() == this) {
        connection.allocations.remove(i);
        return;
      }
    }
    throw new IllegalStateException();
  }

  /**
   * Release the connection held by this connection and acquire {@code newConnection} instead. It is
   * only safe to call this if the held connection is newly connected but duplicated by {@code
   * newConnection}. Typically this occurs when concurrently connecting to an HTTP/2 webserver.
   *
   * <p>Returns a closeable that the caller should pass to {@link Util#closeQuietly} upon completion
   * of the synchronized block. (We don't do I/O while synchronized on the connection pool.)
   */
  public Socket releaseAndAcquire(RealConnection newConnection) {
    assert (Thread.holdsLock(connectionPool));
    if (codec != null || connection.allocations.size() != 1) throw new IllegalStateException();

    // Release the old connection.
    Reference<StreamAllocation> onlyAllocation = connection.allocations.get(0);
    Socket socket = deallocate(true, false, false);

    // Acquire the new connection.
    this.connection = newConnection;
    newConnection.allocations.add(onlyAllocation);

    return socket;
  }

  public boolean hasMoreRoutes() {
    return route != null || routeSelector.hasNext();
  }

  @Override public String toString() {
    RealConnection connection = connection();
    return connection != null ? connection.toString() : address.toString();
  }

  public static final class StreamAllocationReference extends WeakReference<StreamAllocation> {
    /**
     * Captures the stack trace at the time the Call is executed or enqueued. This is helpful for
     * identifying the origin of connection leaks.
     */
    public final Object callStackTrace;

    StreamAllocationReference(StreamAllocation referent, Object callStackTrace) {
      super(referent);
      this.callStackTrace = callStackTrace;
    }
  }
}
