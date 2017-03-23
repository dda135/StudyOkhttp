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
package okhttp3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import okhttp3.RealCall.AsyncCall;
import okhttp3.internal.Util;

/**
 * Policy on when async requests are executed.
 * 异步请求执行的策略
 *
 * <p>Each dispatcher uses an {@link ExecutorService} to run calls internally. If you supply your
 * own executor, it should be able to run {@linkplain #getMaxRequests the configured maximum} number
 * of calls concurrently.
 * 每一个分发者都使用ExecutorService来执行calls。
 * 如果你使用自己定义的线程池，你也应该通过getMaxRequests的方式来限制最大的calls并发量。
 */
public final class Dispatcher {
  private int maxRequests = 64;//calls的最大并发量
  private int maxRequestsPerHost = 5;//同一个请求host的最大允许并发请求
  //这两个参数共同决定了一个call是否可以进入执行状态

  //空闲状态回调，在每一个RealCall执行完成之后都会进行check
  private Runnable idleCallback;

  /** Executes calls. Created lazily.
   * 懒创建线程池，没有使用到具体分发者的使用没有必要创建
   * */
  private ExecutorService executorService;

  /** Ready async calls in the order they'll be run.
   * 双向队列，用于放置那些准备开始异步请求的calls
   * */
  private final Deque<AsyncCall> readyAsyncCalls = new ArrayDeque<>();

  /** Running asynchronous calls. Includes canceled calls that haven't finished yet.
   * 双向队列，用于放置那些在运行中的异步calls，也包括那些已经执行然后被要求取消的calls（因为它们还没有完成）
   * */
  private final Deque<AsyncCall> runningAsyncCalls = new ArrayDeque<>();

  /** Running synchronous calls. Includes canceled calls that haven't finished yet.
   * 双向队列，用于放置那些运行中的同步请求，也包括那些已经执行然后被要求取消的calls（因为它们还没有完成）
   * */
  private final Deque<RealCall> runningSyncCalls = new ArrayDeque<>();

  public Dispatcher(ExecutorService executorService) {
    this.executorService = executorService;
  }

  public Dispatcher() {
  }

  /**
   * 获取线程池
   */
  public synchronized ExecutorService executorService() {
    if (executorService == null) {
      //基本可以认为是缓存线程池，消费性队列（同一时刻只有一个任务等待，只有取出后才可放置新的，不然阻塞）
      executorService = new ThreadPoolExecutor(0, Integer.MAX_VALUE, 60, TimeUnit.SECONDS,
          new SynchronousQueue<Runnable>(), Util.threadFactory("OkHttp Dispatcher", false));
    }
    return executorService;
  }

  /**
   * Set the maximum number of requests to execute concurrently. Above this requests queue in
   * memory, waiting for the running calls to complete.
   *
   * 设置最大的同步执行数量。等待的请求会在内存中的队列存在，等着有执行的calls结束
   * <p>If more than {@code maxRequests} requests are in flight when this is invoked, those requests
   * will remain in flight.
   * 如果当前执行中的异步请求多余给定的最大请求数，那些已经执行中的请求还会继续执行
   * 如果小于最大请求数，会从等待队列中获取满足最大host的请求，并且开始执行，直到满足最大请求数
   */
  public synchronized void setMaxRequests(int maxRequests) {
    if (maxRequests < 1) {
      throw new IllegalArgumentException("max < 1: " + maxRequests);
    }
    this.maxRequests = maxRequests;
    promoteCalls();
  }

  public synchronized int getMaxRequests() {
    return maxRequests;
  }

  /**
   * Set the maximum number of requests for each host to execute concurrently. This limits requests
   * by the URL's host name. Note that concurrent requests to a single IP address may still exceed
   * this limit: multiple hostnames may share an IP address or be routed through the same HTTP
   * proxy.
   * 设置每一个host最多可以并发执行的数量。
   * 这个限制对于ip来说可能是没有太大意义的，因为很有可能超过这个限制
   * 1.同一个IP可以对应多个主机名
   * 2.中途可能通过http代理来转换
   *
   * <p>If more than {@code maxRequestsPerHost} requests are in flight when this is invoked, those
   * requests will remain in flight.
   * 如果当前执行中的异步请求多余给定的最大请求数，那些已经执行中的请求还会继续执行
   * 如果小于最大请求数，会从等待队列中获取满足最大host的请求，并且开始执行，直到满足最大请求数
   */
  public synchronized void setMaxRequestsPerHost(int maxRequestsPerHost) {
    if (maxRequestsPerHost < 1) {
      throw new IllegalArgumentException("max < 1: " + maxRequestsPerHost);
    }
    this.maxRequestsPerHost = maxRequestsPerHost;
    promoteCalls();
  }

  public synchronized int getMaxRequestsPerHost() {
    return maxRequestsPerHost;
  }

  /**
   * 设置一个当分发者空闲（执行中calls的数量变成0）的时候进行的回调
   * Set a callback to be invoked each time the dispatcher becomes idle (when the number of running
   * calls returns to zero).
   *
   * <p>Note: The time at which a {@linkplain Call call} is considered idle is different depending
   * on whether it was run {@linkplain Call#enqueue(Callback) asynchronously} or
   * {@linkplain Call#execute() synchronously}. Asynchronous calls become idle after the
   * {@link Callback#onResponse onResponse} or {@link Callback#onFailure onFailure} callback has
   * returned. Synchronous calls become idle once {@link Call#execute() execute()} returns. This
   * means that if you are doing synchronous calls the network layer will not truly be idle until
   * every returned {@link Response} has been closed.
   *
   * 判断空闲的状态需要区分：
   * 异步：在onResponse或者onFailure之后将可能会进行回调。
   * 同步：在execute之后可能进行回调
   */
  public synchronized void setIdleCallback(Runnable idleCallback) {
    this.idleCallback = idleCallback;
  }

  /**
   * 根据条件决定是否开始立刻执行和添加到指定的队列当中
   * @param call 当前需要操作的call请求
     */
  synchronized void enqueue(AsyncCall call) {
    if (runningAsyncCalls.size() < maxRequests && runningCallsForHost(call) < maxRequestsPerHost) {
      //如果当前执行中的异步请求数目和已经执行的同一host数目没有超过最大值
      //添加到执行中队列并开始异步执行请求
      runningAsyncCalls.add(call);
      executorService().execute(call);
    } else {
      //否则进入准备执行队列当中
      readyAsyncCalls.add(call);
    }
  }

  /**
   * Cancel all calls currently enqueued or executing. Includes calls executed both {@linkplain
   * Call#execute() synchronously} and {@linkplain Call#enqueue asynchronously}.
   * 取消所有的请求
   * 内部主要采用关闭流的方式，在流传输完成之前都是有效的。
   */
  public synchronized void cancelAll() {
    for (AsyncCall call : readyAsyncCalls) {
      call.get().cancel();
    }

    for (AsyncCall call : runningAsyncCalls) {
      call.get().cancel();
    }

    for (RealCall call : runningSyncCalls) {
      call.cancel();
    }
  }

  /**
   * 开启call请求，直到ready队列为空或者到达最大异步请求数量
   */
  private void promoteCalls() {
    if (runningAsyncCalls.size() >= maxRequests) return; // Already running max capacity.
    if (readyAsyncCalls.isEmpty()) return; // No ready calls to promote.

    for (Iterator<AsyncCall> i = readyAsyncCalls.iterator(); i.hasNext(); ) {
      //遍历准备执行的calls
      AsyncCall call = i.next();
      //首先要判断当前call请求的host是否超过设定的最大值
      //如果超过最大值则不应该开启当前call
      if (runningCallsForHost(call) < maxRequestsPerHost) {
        //没有超过最大值，开始执行当前call

        //将当前call从准备状态中移除，执行当前call，并添加到异步执行队列中
        i.remove();
        runningAsyncCalls.add(call);
        executorService().execute(call);
      }
      //直到当前执行的call的数量到达设定的最大值
      if (runningAsyncCalls.size() >= maxRequests) return; // Reached max capacity.
    }
  }

  /** Returns the number of running calls that share a host with {@code call}.
   * 获取当前异步执行中的请求的host与设置的host一致的数目
   * */
  private int runningCallsForHost(AsyncCall call) {
    int result = 0;
    for (AsyncCall c : runningAsyncCalls) {//获取当前异步执行中的请求的host与设置的host一致的数目
      if (c.host().equals(call.host())) result++;
    }
    return result;
  }

  /** Used by {@code Call#execute} to signal it is in-flight. */
  synchronized void executed(RealCall call) {
    runningSyncCalls.add(call);
  }

  /** Used by {@code AsyncCall#run} to signal completion. */
  void finished(AsyncCall call) {
    finished(runningAsyncCalls, call, true);
  }

  /** Used by {@code Call#execute} to signal completion. */
  void finished(RealCall call) {
    finished(runningSyncCalls, call, false);
  }

  /**
   * 完成某一个Call
   * @param calls 当前call对应的运行中双向队列
   * @param call 当前完成的Call
   * @param promoteCalls 是否开启请求，如果异步请求结束的时候会继续开启，同步则不会
     */
  private <T> void finished(Deque<T> calls, T call, boolean promoteCalls) {
    int runningCallsCount;
    Runnable idleCallback;
    synchronized (this) {
      if (!calls.remove(call)) throw new AssertionError("Call wasn't in-flight!");
      //异步请求的call结束之后会尝试开启其他等待中的异步请求
      if (promoteCalls) promoteCalls();
      //获取当前同/异步执行的总数量
      runningCallsCount = runningCallsCount();
      idleCallback = this.idleCallback;
    }
    //空闲回调，注意这里只有当同/异步执行中队列为空的时候才会回调
    if (runningCallsCount == 0 && idleCallback != null) {
      idleCallback.run();
    }
  }

  /** Returns a snapshot of the calls currently awaiting execution.
   * 获取当前等待运行的请求的快照
   * */
  public synchronized List<Call> queuedCalls() {
    List<Call> result = new ArrayList<>();
    for (AsyncCall asyncCall : readyAsyncCalls) {
      result.add(asyncCall.get());
    }
    return Collections.unmodifiableList(result);
  }

  /** Returns a snapshot of the calls currently being executed.
   * 获取当前同/异步运行的请求的快照
   * */
  public synchronized List<Call> runningCalls() {
    List<Call> result = new ArrayList<>();
    result.addAll(runningSyncCalls);
    for (AsyncCall asyncCall : runningAsyncCalls) {
      result.add(asyncCall.get());
    }
    //不允许添加/删除等操作，允许查找和获取等不改变列表结构的操作
    return Collections.unmodifiableList(result);
  }
  //获取当前等待执行的请求数量
  public synchronized int queuedCallsCount() {
    return readyAsyncCalls.size();
  }
  //获取当前执行中的所有同步和异步的请求数量
  public synchronized int runningCallsCount() {
    return runningAsyncCalls.size() + runningSyncCalls.size();
  }
}
