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
package okhttp3;

import java.lang.ref.Reference;
import java.net.Socket;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import okhttp3.internal.Util;
import okhttp3.internal.connection.RealConnection;
import okhttp3.internal.connection.RouteDatabase;
import okhttp3.internal.connection.StreamAllocation;
import okhttp3.internal.platform.Platform;

import static okhttp3.internal.Util.closeQuietly;

/**
 * Manages reuse of HTTP and HTTP/2 connections for reduced network latency. HTTP requests that
 * share the same {@link Address} may share a {@link Connection}. This class implements the policy
 * of which connections to keep open for future use.
 * 管理复用HTTP和HTTP2.0的连接从而减少网络潜在的损耗。
 * HTTP请求同一个地址也许会共享一个连接。
 * 可以通过实现这个类来定义连接的保持和使用。
 */
public final class ConnectionPool {
  /**
   * Background threads are used to cleanup expired connections. There will be at most a single
   * thread running per connection pool. The thread pool executor permits the pool itself to be
   * garbage collected.
   * 缓存线程池
   */
  private static final Executor executor = new ThreadPoolExecutor(0 /* corePoolSize */,
      Integer.MAX_VALUE /* maximumPoolSize */, 60L /* keepAliveTime */, TimeUnit.SECONDS,
      new SynchronousQueue<Runnable>(), Util.threadFactory("OkHttp ConnectionPool", true));

  /** The maximum number of idle connections for each address.
   * 最大空闲连接
   * */
  private final int maxIdleConnections;
  private final long keepAliveDurationNs;
  private final Runnable cleanupRunnable = new Runnable() {
    @Override public void run() {
      while (true) {
        long waitNanos = cleanup(System.nanoTime());
        if (waitNanos == -1) return;
        if (waitNanos > 0) {//计算下一次唤醒进行清理任务的间隔
          long waitMillis = waitNanos / 1000000L;//毫秒
          waitNanos -= (waitMillis * 1000000L);//纳秒
          synchronized (ConnectionPool.this) {
            try {//线程挂起指定时间
              ConnectionPool.this.wait(waitMillis, (int) waitNanos);
            } catch (InterruptedException ignored) {
            }
          }
        }
      }
    }
  };
  //当前连接池所持有的连接队列，注意RealConnection只是一个Connection的管理类
  private final Deque<RealConnection> connections = new ArrayDeque<>();
  final RouteDatabase routeDatabase = new RouteDatabase();
  boolean cleanupRunning;

  /**
   * Create a new connection pool with tuning parameters appropriate for a single-user application.
   * The tuning parameters in this pool are subject to change in future OkHttp releases. Currently
   * this pool holds up to 5 idle connections which will be evicted after 5 minutes of inactivity.
   */
  public ConnectionPool() {
    this(5, 5, TimeUnit.MINUTES);
  }

  public ConnectionPool(int maxIdleConnections, long keepAliveDuration, TimeUnit timeUnit) {
    this.maxIdleConnections = maxIdleConnections;
    this.keepAliveDurationNs = timeUnit.toNanos(keepAliveDuration);

    // Put a floor on the keep alive duration, otherwise cleanup will spin loop.
    if (keepAliveDuration <= 0) {
      throw new IllegalArgumentException("keepAliveDuration <= 0: " + keepAliveDuration);
    }
  }

  /** Returns the number of idle connections in the pool.
   * 返回连接池当前闲置的连接总数
   * */
  public synchronized int idleConnectionCount() {
    int total = 0;
    //遍历当前连接池中的连接管理类
    for (RealConnection connection : connections) {
      //
      if (connection.allocations.isEmpty()) total++;
    }
    return total;
  }

  /**
   * Returns total number of connections in the pool. Note that prior to OkHttp 2.7 this included
   * only idle connections and HTTP/2 connections. Since OkHttp 2.7 this includes all connections,
   * both active and inactive. Use {@link #idleConnectionCount()} to count connections not currently
   * in use.
   */
  public synchronized int connectionCount() {
    return connections.size();
  }

  /**
   * Returns a recycled connection to {@code address}, or null if no such connection exists. The
   * route is null if the address has not yet been routed.
   */
  RealConnection get(Address address, StreamAllocation streamAllocation, Route route) {
    assert (Thread.holdsLock(this));
    for (RealConnection connection : connections) {
      //检测连接是否可以复用
      if (connection.isEligible(address, route)) {
        streamAllocation.acquire(connection);//关联流和连接
        return connection;
      }
    }
    return null;
  }

  /**
   * Replaces the connection held by {@code streamAllocation} with a shared connection if possible.
   * This recovers when multiple multiplexed connections are created concurrently.
   */
  Socket deduplicate(Address address, StreamAllocation streamAllocation) {
    assert (Thread.holdsLock(this));
    for (RealConnection connection : connections) {
      if (connection.isEligible(address, null)
          && connection.isMultiplexed()
          && connection != streamAllocation.connection()) {
        return streamAllocation.releaseAndAcquire(connection);
      }
    }
    return null;
  }

  /**
   * 将当前连接放入连接池中
   * @param connection
     */
  void put(RealConnection connection) {
    assert (Thread.holdsLock(this));
    //往连接池中添加连接
    if (!cleanupRunning) {//当前清理线程没有运行
      cleanupRunning = true;//执行清理线程
      executor.execute(cleanupRunnable);
    }
    connections.add(connection);
  }

  /**
   * Notify this pool that {@code connection} has become idle. Returns true if the connection has
   * been removed from the pool and should be closed.
   */
  boolean connectionBecameIdle(RealConnection connection) {
    assert (Thread.holdsLock(this));
    //noNewStreams实际上是标记当前流不可复用
    if (connection.noNewStreams || maxIdleConnections == 0) {
      connections.remove(connection);//直接从连接池中移除
      return true;
    } else {
      //这里是唤醒cleaup线程
      notifyAll(); // Awake the cleanup thread: we may have exceeded the idle connection limit.
      return false;
    }
  }

  /** Close and remove all idle connections in the pool. */
  public void evictAll() {
    List<RealConnection> evictedConnections = new ArrayList<>();
    synchronized (this) {
      for (Iterator<RealConnection> i = connections.iterator(); i.hasNext(); ) {
        RealConnection connection = i.next();
        if (connection.allocations.isEmpty()) {
          connection.noNewStreams = true;
          evictedConnections.add(connection);
          i.remove();
        }
      }
    }

    for (RealConnection connection : evictedConnections) {
      closeQuietly(connection.socket());
    }
  }

  /**
   * Performs maintenance on this pool, evicting the connection that has been idle the longest if
   * either it has exceeded the keep alive limit or the idle connections limit.
   *
   * <p>Returns the duration in nanos to sleep until the next scheduled call to this method. Returns
   * -1 if no further cleanups are required.
   */
  long cleanup(long now) {
    int inUseConnectionCount = 0;
    int idleConnectionCount = 0;
    RealConnection longestIdleConnection = null;
    long longestIdleDurationNs = Long.MIN_VALUE;

    // Find either a connection to evict, or the time that the next eviction is due.
    synchronized (this) {
      //遍历当前连接池中的所有连接
      for (Iterator<RealConnection> i = connections.iterator(); i.hasNext(); ) {
        RealConnection connection = i.next();

        // If the connection is in use, keep searching.
        // 如果当前连接正在使用中，不回收，继续查找下一个
        if (pruneAndGetAllocationCount(connection, now) > 0) {
          inUseConnectionCount++;
          continue;
        }

        idleConnectionCount++;//空闲连接数+1

        // If the connection is ready to be evicted, we're done.
        // 当前连接空闲时间 = 当前时间 - 当前连接被释放允许参与复用的时间
        long idleDurationNs = now - connection.idleAtNanos;
        // 这里是记录当前连接池中所有空闲连接中空闲的最久的连接
        if (idleDurationNs > longestIdleDurationNs) {
          longestIdleDurationNs = idleDurationNs;//当前最大的空闲的连接到现在所经过的纳秒数
          longestIdleConnection = connection;//当前空闲最久的可复用的连接
        }
      }

      if (longestIdleDurationNs >= this.keepAliveDurationNs
          || idleConnectionCount > this.maxIdleConnections) {//当前连接超过最大的空闲时间或者数量大于最大的空闲连接数量
        // We've found a connection to evict. Remove it from the list, then close it below (outside
        // of the synchronized block).
        // 从连接池中移除，不再复用
        connections.remove(longestIdleConnection);
      } else if (idleConnectionCount > 0) {//当前存在空闲连接，并且空闲连接没有超时
        // A connection will be ready to evict soon.
        // 当前清理线程挂起最大的空闲时间，下一次唤醒可能会清理当前最久的空闲连接
        return keepAliveDurationNs - longestIdleDurationNs;
      } else if (inUseConnectionCount > 0) {
        // All connections are in use. It'll be at least the keep alive duration 'til we run again.
        return keepAliveDurationNs;
      } else {
        // No connections, idle or in use.
        cleanupRunning = false;
        return -1;
      }
    }
    //每一次清理的对象为当前空闲连接列表中最久的空闲连接
    closeQuietly(longestIdleConnection.socket());

    // Cleanup again immediately.
    return 0;
  }

  /**
   * Prunes any leaked allocations and then returns the number of remaining live allocations on
   * {@code connection}. Allocations are leaked if the connection is tracking them but the
   * application code has abandoned them. Leak detection is imprecise and relies on garbage
   * collection.
   */
  private int pruneAndGetAllocationCount(RealConnection connection, long now) {
    List<Reference<StreamAllocation>> references = connection.allocations;
    for (int i = 0; i < references.size(); ) {
      Reference<StreamAllocation> reference = references.get(i);

      if (reference.get() != null) {
        i++;
        continue;
      }

      // We've discovered a leaked allocation. This is an application bug.
      StreamAllocation.StreamAllocationReference streamAllocRef =
          (StreamAllocation.StreamAllocationReference) reference;
      String message = "A connection to " + connection.route().address().url()
          + " was leaked. Did you forget to close a response body?";
      Platform.get().logCloseableLeak(message, streamAllocRef.callStackTrace);

      references.remove(i);
      connection.noNewStreams = true;

      // If this was the last allocation, the connection is eligible for immediate eviction.
      if (references.isEmpty()) {
        connection.idleAtNanos = now - keepAliveDurationNs;
        return 0;
      }
    }

    return references.size();
  }
}
