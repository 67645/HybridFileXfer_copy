package top.weixiansen574.hybridfilexfer.core.rpc;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import top.weixiansen574.nio.DataByteChannel;

/**
 * 301 模式下的双向帧通道。
 *
 * <p>帧格式：请求 [int seq][short cmd][int len][payload]；
 * 应答 [int seq][short -2][int len][byte status][body]。</p>
 *
 * <p>线程模型：一个读线程负责分派，一个单线程执行器顺序执行对端请求，
 * 所有写出经过同一把锁（DataByteChannel 非线程安全）。</p>
 */
public class RpcPeer {
    private static final byte[] EMPTY = new byte[0];

    private final DataByteChannel channel;
    private final RpcFileOps ops;
    private final Object writeLock = new Object();
    private final AtomicInteger seqGenerator = new AtomicInteger(1);
    private final ConcurrentHashMap<Integer, CompletableFuture<RpcResult>> pending = new ConcurrentHashMap<>();
    private final ExecutorService handlerExecutor;
    private final Thread readerThread;
    private volatile boolean running = true;

    public RpcPeer(DataByteChannel channel, RpcFileOps ops, String name) {
        this.channel = channel;
        this.ops = ops;
        ThreadFactory handlerFactory = runnable -> {
            Thread thread = new Thread(runnable, name + "-RpcHandler");
            thread.setDaemon(true);
            return thread;
        };
        this.handlerExecutor = Executors.newSingleThreadExecutor(handlerFactory);
        this.readerThread = new Thread(this::readLoop, name + "-RpcReader");
        this.readerThread.setDaemon(true);
        this.readerThread.start();
    }

    public boolean isRunning() {
        return running;
    }

    public RpcResult call(short cmd, byte[] payload) throws IOException {
        return call(cmd, payload, RpcProtocol.DEFAULT_TIMEOUT_MS);
    }

    public RpcResult call(short cmd, byte[] payload, long timeoutMs) throws IOException {
        if (!running) {
            throw new IOException("RPC 通道已关闭");
        }
        int seq = seqGenerator.getAndIncrement();
        CompletableFuture<RpcResult> future = new CompletableFuture<>();
        pending.put(seq, future);
        try {
            writeFrame(seq, cmd, payload);
        } catch (IOException e) {
            pending.remove(seq);
            throw e;
        }
        RpcResult result;
        try {
            result = future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            pending.remove(seq);
            throw new IOException("RPC 超时：cmd=" + cmd, e);
        } catch (InterruptedException e) {
            pending.remove(seq);
            Thread.currentThread().interrupt();
            throw new IOException("RPC 被中断：cmd=" + cmd, e);
        } catch (ExecutionException e) {
            pending.remove(seq);
            throw new IOException("RPC 连接已关闭：cmd=" + cmd, e.getCause());
        }
        if (result.status != RpcProtocol.OK) {
            throw new RpcException(result.status, RpcCodec.decodeMessage(result.body));
        }
        return result;
    }

    private void writeFrame(int seq, short cmd, byte[] payload) throws IOException {
        byte[] data = payload == null ? EMPTY : payload;
        ByteBuffer header = ByteBuffer.allocate(10);
        header.putInt(seq);
        header.putShort(cmd);
        header.putInt(data.length);
        header.flip();
        synchronized (writeLock) {
            writeFully(header);
            if (data.length > 0) {
                writeFully(ByteBuffer.wrap(data));
            }
        }
    }

    /**
     * DataByteChannel 的写方法不保证一次写完，且其内部缓冲为非线程安全，
     * 这里使用帧内局部缓冲并循环写出。
     */
    private void writeFully(ByteBuffer buffer) throws IOException {
        while (buffer.hasRemaining()) {
            if (channel.write(buffer) < 0) {
                throw new IOException("写入失败");
            }
        }
    }

    private void readLoop() {
        String reason = "对端关闭";
        try {
            while (running) {
                ByteBuffer header = ByteBuffer.allocate(10);
                channel.readFully(header);
                header.flip();
                int seq = header.getInt();
                short cmd = header.getShort();
                int length = header.getInt();
                if (length < 0 || length > RpcProtocol.MAX_FRAME) {
                    throw new IOException("非法帧长度：" + length);
                }
                byte[] payload = new byte[length];
                if (length > 0) {
                    channel.readFully(ByteBuffer.wrap(payload));
                }
                if (cmd == RpcProtocol.CMD_RESPONSE) {
                    dispatchResponse(seq, payload);
                } else {
                    final int requestSeq = seq;
                    final short requestCmd = cmd;
                    final byte[] requestPayload = payload;
                    try {
                        handlerExecutor.execute(() -> dispatchRequest(requestSeq, requestCmd, requestPayload));
                    } catch (RejectedExecutionException ignored) {
                        // 已关闭，忽略
                    }
                }
            }
        } catch (Exception e) {
            reason = e.toString();
        }
        running = false;
        failAllPending(reason);
        handlerExecutor.shutdownNow();
        try {
            ops.rpcOnPeerClosed(reason);
        } catch (Exception ignored) {
        }
    }

    private void dispatchResponse(int seq, byte[] payload) {
        CompletableFuture<RpcResult> future = pending.remove(seq);
        if (future == null) {
            return;
        }
        byte status = payload.length > 0 ? payload[0] : RpcProtocol.ERR_IO;
        byte[] body = new byte[Math.max(0, payload.length - 1)];
        if (payload.length > 1) {
            System.arraycopy(payload, 1, body, 0, body.length);
        }
        future.complete(new RpcResult(status, body));
    }

    private void dispatchRequest(int seq, short cmd, byte[] payload) {
        byte status;
        byte[] body;
        try {
            switch (cmd) {
                case RpcProtocol.CMD_LIST_FILES:
                    body = RpcCodec.encodeFileList(ops.rpcListFiles(RpcCodec.decodePath(payload)));
                    status = RpcProtocol.OK;
                    break;
                case RpcProtocol.CMD_DELETE_FILE:
                    body = RpcCodec.encodeBool(ops.rpcDeleteFile(RpcCodec.decodePath(payload)));
                    status = RpcProtocol.OK;
                    break;
                case RpcProtocol.CMD_MKDIR: {
                    String[] paths = RpcCodec.decodeMkdir(payload);
                    body = RpcCodec.encodeBool(ops.rpcMkdir(paths[0], paths[1]));
                    status = RpcProtocol.OK;
                    break;
                }
                case RpcProtocol.CMD_SHUTDOWN:
                    body = EMPTY;
                    status = RpcProtocol.OK;
                    running = false;
                    break;
                default:
                    body = RpcCodec.encodeMessage("暂不支持的命令：" + cmd);
                    status = RpcProtocol.ERR_UNSUPPORTED;
                    break;
            }
        } catch (Exception e) {
            try {
                body = RpcCodec.encodeMessage(String.valueOf(e));
            } catch (IOException io) {
                body = EMPTY;
            }
            status = RpcProtocol.ERR_IO;
        }
        byte[] response = new byte[body.length + 1];
        response[0] = status;
        System.arraycopy(body, 0, response, 1, body.length);
        try {
            writeFrame(seq, RpcProtocol.CMD_RESPONSE, response);
        } catch (IOException ignored) {
        }
    }

    private void failAllPending(String reason) {
        for (Map.Entry<Integer, CompletableFuture<RpcResult>> entry : pending.entrySet()) {
            entry.getValue().completeExceptionally(new IOException("RPC 连接已关闭：" + reason));
        }
        pending.clear();
    }

    public void close() {
        running = false;
        failAllPending("主动关闭");
        handlerExecutor.shutdownNow();
    }
}
