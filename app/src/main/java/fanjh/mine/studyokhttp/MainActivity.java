package fanjh.mine.studyokhttp;

import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;

import java.io.IOException;
import java.lang.ref.Reference;
import java.net.Socket;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.internal.Internal;
import okhttp3.internal.connection.RealConnection;
import okhttp3.internal.connection.StreamAllocation;
import okhttp3.internal.http.RealInterceptorChain;
import okhttp3.internal.http2.ConnectionShutdownException;
import okhttp3.internal.http2.ErrorCode;
import okhttp3.internal.http2.StreamResetException;

import static okhttp3.internal.Util.closeQuietly;

public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        OkHttpClient okHttpClient = new OkHttpClient();
        okHttpClient.newCall(new Request.Builder().url("https://www.baidu.com/").build()).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {}
            @Override
            public void onResponse(Call call, Response response) throws IOException {}
        });
    }

    public void streamFailed(IOException e) {
        Socket socket;
        boolean noNewStreams = false;

        synchronized (connectionPool) {
            if (e instanceof StreamResetException) {//这个是Http2协议的，先忽略
                //...
            } else if (connection != null
                    && (!connection.isMultiplexed() || e instanceof ConnectionShutdownException)) {//当前连接不为空，且不是http2协议
                noNewStreams = true;//标记之后不会新建连接
                // 当前连接没有成功，标记当前连接节点失败
                // 当前连接被标记成功的条件是要求完成一次请求，并且从服务端成功读取响应体中的正文部分
                if (connection.successCount == 0) {
                    if (route != null && e != null) {
                        routeSelector.connectFailed(route, e);
                    }
                    route = null;//会导致当前节点失效，那么如果要重新连接，要求必须有下一个备用的节点
                }
            }
            //不新建流、当前流完成、不释放流分配者
            //当前socket连接会被关闭
            socket = deallocate(noNewStreams, false, true);
        }

        closeQuietly(socket);
    }

    private Socket deallocate(boolean noNewStreams, boolean released, boolean streamFinished) {
        assert (Thread.holdsLock(connectionPool));
        //连接完成之后，无论成功失败，都清理当前codec
        if (streamFinished) {
            this.codec = null;
        }
        //当前流分配者是否继续可用
        if (released) {
            this.released = true;
        }
        Socket socket = null;
        if (connection != null) {
            if (noNewStreams) {//当前连接是否可以重用
                //一旦被标记，会导致连接池移除当前连接
                //后续会关闭socket连接
                connection.noNewStreams = true;
            }
            //在一次正常的请求完成之后，要进行连接的释放
            //包括socket的关闭
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

    private void release(RealConnection connection) {
        //这里实际上就是将连接和流分配者的关联解除
        //如果允许连接复用，那么不应该关闭socket连接，会在一定时间内等待复用
        //否则后面应该主动关闭socket连接
        for (int i = 0, size = connection.allocations.size(); i < size; i++) {
            Reference<StreamAllocation> reference = connection.allocations.get(i);
            if (reference.get() == this) {
                connection.allocations.remove(i);
                return;
            }
        }
        throw new IllegalStateException();
    }

}
