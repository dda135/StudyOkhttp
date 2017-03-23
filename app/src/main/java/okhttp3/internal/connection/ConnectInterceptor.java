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
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.internal.http.HttpCodec;
import okhttp3.internal.http.RealInterceptorChain;

/** Opens a connection to the target server and proceeds to the next interceptor. */
public final class ConnectInterceptor implements Interceptor {
  public final OkHttpClient client;

  public ConnectInterceptor(OkHttpClient client) {
    this.client = client;
  }

  @Override public Response intercept(Chain chain) throws IOException {
    RealInterceptorChain realChain = (RealInterceptorChain) chain;
    Request request = realChain.request();
    //在正常的流程中，此处的StreamAllocation是在RetryAndFollowUpInterceptor中创建
    //每一个Call都有一个url，一个url就会对应一个新的StreamAllocation
    StreamAllocation streamAllocation = realChain.streamAllocation();

    // We need the network to satisfy this request. Possibly for validating a conditional GET.
    //检查当前请求是否GET请求
    boolean doExtensiveHealthChecks = !request.method().equals("GET");
    //创建流和Connection，核心方法
    HttpCodec httpCodec = streamAllocation.newStream(client, doExtensiveHealthChecks);
    //注意在newStream中Connection已经创建，此处只是获取而已
    RealConnection connection = streamAllocation.connection();
    //此处connection创建已经完成
    return realChain.proceed(request, streamAllocation, httpCodec, connection);
  }
}