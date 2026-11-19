package top.weixiansen574.hybridfilexfer.core;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingDeque;

import top.weixiansen574.hybridfilexfer.core.bean.Directory;
import top.weixiansen574.hybridfilexfer.core.bean.RemoteFile;
import top.weixiansen574.hybridfilexfer.core.bean.TrafficInfo;
import top.weixiansen574.hybridfilexfer.core.callback.TransferFileCallback;
import top.weixiansen574.hybridfilexfer.core.rpc.RpcCodec;
import top.weixiansen574.hybridfilexfer.core.rpc.RpcException;
import top.weixiansen574.hybridfilexfer.core.rpc.RpcPeer;
import top.weixiansen574.hybridfilexfer.core.rpc.RpcProtocol;
import top.weixiansen574.hybridfilexfer.core.rpc.RpcResult;
import top.weixiansen574.hybridfilexfer.core.rpc.TransferRequest;
import top.weixiansen574.nio.DataByteChannel;

public abstract class HFXService {
    public static final String CLIENT_HEADER = "HFXC";

    /** 对端主动发起传输时的静默回调，本端没有对应界面展示进度。 */
    protected static final TransferFileCallback SILENT_CALLBACK = new TransferFileCallback() {
        @Override
        public void onFileUploading(String iName, String path, long targetSize, long totalSize) {
        }

        @Override
        public void onFileDownloading(String iName, String path, long targetSize, long totalSize) {
        }

        @Override
        public void onSpeedInfo(List<TrafficInfo> trafficInfoList) {
        }

        @Override
        public void onChannelComplete(String iName, long traffic, long time) {
        }

        @Override
        public void onChannelError(String iName, int errorType, String message) {
        }

        @Override
        public void onReadFileError(String message) {
        }

        @Override
        public void onWriteFileError(String message) {
        }

        @Override
        public void onComplete(boolean isUpload, long traffic, long time) {
        }

        @Override
        public void onIncomplete() {
        }
    };
    /** 旧版单向协议版本，保持与既有手机端一致。 */
    public static final int VERSION_CODE = RpcProtocol.VERSION_LEGACY;
    protected final LinkedBlockingDeque<ByteBuffer> buffers = new LinkedBlockingDeque<>();
    protected DataByteChannel ctChannel;
    protected List<TransferConnection> connections;
    /** 当前会话协商出的协议版本：300 或 301。 */
    protected int protocolVersion = RpcProtocol.VERSION_LEGACY;
    /** 301 模式下的双向帧通道；300 模式为 null。 */
    protected RpcPeer rpc;
    /** 传输任务进行中：对端新的控制请求一律返回 ERR_BUSY（SHUTDOWN、QUERY_INFO 除外）。 */
    protected volatile boolean transferBusy;

    public int getProtocolVersion() {
        return protocolVersion;
    }

    public boolean isTransferBusy() {
        return transferBusy;
    }

    protected boolean sendFiles(List<RemoteFile> fileList,Directory localDir, Directory remoteDir, TransferFileCallback callback) throws IOException {
        ReadFileCall readFileCall = createReadFileCall(buffers, fileList, localDir, remoteDir, connections.size());
        FutureTask<Void> readFileTask = new FutureTask<>(readFileCall);
        Thread readThread = new Thread(readFileTask);
        readThread.setName("FileRead");
        readThread.start();
        //另开一个线程读取传输流量信息，1秒一次
        SpeedMonitorThread speedMonitorThread = new SpeedMonitorThread(connections, callback);
        speedMonitorThread.setName("SpeedMonitor");
        speedMonitorThread.start();
        long startTime = System.currentTimeMillis();
        List<FutureTask<Void>> transferTasks = new ArrayList<>(connections.size());
        for (TransferConnection connection : connections) {
            FutureTask<Void> task = new FutureTask<>(new SendFileCall(readFileCall, connection, callback));
            transferTasks.add(task);
            Thread thread = new Thread(task);
            thread.setName("UL_" + connection.iName);
            thread.start();
        }

        //其中一条通道断掉，可能控制器通道也一起跟着断了
        boolean complete;
        try {
            //等待客户端接收成功或者写入到硬盘时发生IO错误
            complete = ctChannel.readBoolean();
        } catch (IOException e) {
            speedMonitorThread.cancel();
            callback.onIncomplete();
            return false;
        }
        speedMonitorThread.cancel();

        if (!complete) {
            String errMsg = ctChannel.readUTF();
            callback.onWriteFileError(errMsg);
            readFileCall.shutdownByWriteError();
            return true;
        }

        for (FutureTask<Void> transferTask : transferTasks) {
            try {
                transferTask.get();
            } catch (ExecutionException | InterruptedException e) {
                callback.onIncomplete();
                return false;
            }
        }

        long totalUploadTraffic = 0;
        for (TransferConnection connection : connections) {
            totalUploadTraffic += connection.resetTotalTrafficInfo().uploadTraffic;
        }

        try {
            readFileTask.get();
            ctChannel.writeBoolean(true);
        } catch (ExecutionException | InterruptedException e) {
            Throwable cause = e.getCause();
            String ex = cause != null ? cause.toString() : e.toString();
            ctChannel.writeBoolean(false);
            ctChannel.writeUTF(ex);
            callback.onReadFileError(ex);
            return true;
        }

        callback.onComplete(true,totalUploadTraffic, System.currentTimeMillis() - startTime);
        return true;
    }

    protected boolean receiveFiles(TransferFileCallback callback) throws IOException {
        WriteFileCall writeFileCall = createWriteFileCall(buffers, connections.size());
        long startTime = System.currentTimeMillis();

        SpeedMonitorThread speedMonitorThread = new SpeedMonitorThread(connections, callback);
        speedMonitorThread.setName("SpeedMonitor");
        speedMonitorThread.start();

        List<FutureTask<Void>> transferTasks = new ArrayList<>(connections.size());
        for (int i = 0; i < connections.size(); i++) {
            TransferConnection connection = connections.get(i);
            FutureTask<Void> task = new FutureTask<>(new ReceiveFileCall(i, connection, writeFileCall, callback));
            transferTasks.add(task);
            Thread thread = new Thread(task);
            thread.setName("DL_" + connection.iName);
            thread.start();
        }
        FutureTask<Void> writeFileTask = new FutureTask<>(writeFileCall);
        Thread thread = new Thread(writeFileTask);
        thread.setName("FileWrite");
        thread.start();
        try {
            writeFileTask.get();
        } catch (InterruptedException | ExecutionException e) {
            speedMonitorThread.cancel();
            Throwable cause = e.getCause();
            ctChannel.writeBoolean(false);
            String ex = cause != null ? cause.toString() : e.toString();
            ctChannel.writeUTF(ex);
            callback.onWriteFileError(ex);
            return true;
        }

        for (FutureTask<Void> task : transferTasks) {
            try {
                task.get();
            } catch (InterruptedException | ExecutionException e) {
                speedMonitorThread.cancel();
                //此时没有连同控制器通道一起断掉，要通知对方，写线程没问题（对方的传输线程通道已出问题）
                ctChannel.writeBoolean(true);
                callback.onIncomplete();
                return false;
            }
        }
        speedMonitorThread.cancel();
        ctChannel.writeBoolean(true);
        if (ctChannel.readBoolean()) {
            long totalDownloadTraffic = 0;
            for (TransferConnection connection : connections) {
                totalDownloadTraffic += connection.resetTotalTrafficInfo().downloadTraffic;
            }
            callback.onComplete(false,totalDownloadTraffic, System.currentTimeMillis() - startTime);
        } else {
            callback.onReadFileError(ctChannel.readUTF());
        }
        return true;
    }

    // ---------------- 301 帧化传输 ----------------

    /** 301：本端把文件推给对端（本端读文件并通过传输通道发送）。 */
    protected void pushFilesFramed(RpcPeer rpc, List<RemoteFile> fileList, Directory localDir,
                                   Directory remoteDir, TransferFileCallback callback) throws IOException {
        transferBusy = true;
        try {
            final IOException[] sendError = new IOException[1];
            Thread sender = new Thread(() -> {
                try {
                    sendFilesFramed(fileList, localDir, remoteDir, callback);
                } catch (IOException e) {
                    sendError[0] = e;
                }
            }, "FramedPush");
            sender.setDaemon(true);
            sender.start();
            RpcResult response = callTransfer(rpc, RpcProtocol.CMD_RECEIVE_FILES, null);
            if (response.status != RpcProtocol.OK) {
                closeTransferConnections();
                joinQuietly(sender);
                throw new RpcException(response.status, RpcCodec.decodeMessage(response.body));
            }
            joinQuietly(sender);
            if (sendError[0] != null) {
                throw sendError[0];
            }
        } finally {
            transferBusy = false;
        }
    }

    /** 301：请求对端把文件发过来（本端接收并写盘）。 */
    protected void pullFilesFramed(RpcPeer rpc, List<RemoteFile> files, Directory localDir,
                                   Directory remoteDir, TransferFileCallback callback) throws IOException {
        transferBusy = true;
        try {
            byte[] payload = RpcCodec.encodeTransferRequest(new TransferRequest(files, localDir, remoteDir));
            final IOException[] receiveError = new IOException[1];
            Thread receiver = new Thread(() -> {
                try {
                    receiveFilesFramed(callback);
                } catch (IOException e) {
                    receiveError[0] = e;
                }
            }, "FramedPull");
            receiver.setDaemon(true);
            receiver.start();
            RpcResult response = callTransfer(rpc, RpcProtocol.CMD_SEND_FILES, payload);
            if (response.status != RpcProtocol.OK) {
                closeTransferConnections();
                joinQuietly(receiver);
                throw new RpcException(response.status, RpcCodec.decodeMessage(response.body));
            }
            joinQuietly(receiver);
            if (receiveError[0] != null) {
                throw receiveError[0];
            }
        } finally {
            transferBusy = false;
        }
    }

    /** 301：作为被请求方接收对端推送的文件。返回 null 表示成功。 */
    protected String receiveFilesFromPeer(TransferFileCallback callback) throws IOException {
        transferBusy = true;
        try {
            return receiveFilesFramed(callback) ? null : "接收文件失败";
        } finally {
            transferBusy = false;
        }
    }

    /** 301：作为被请求方把文件发送给对端。返回 null 表示成功。 */
    protected String sendFilesToPeer(List<RemoteFile> files, Directory localDir, Directory remoteDir,
                                     TransferFileCallback callback) throws IOException {
        transferBusy = true;
        try {
            return sendFilesFramed(files, localDir, remoteDir, callback) ? null : "发送文件失败";
        } finally {
            transferBusy = false;
        }
    }

    private RpcResult callTransfer(RpcPeer rpc, short cmd, byte[] payload) throws IOException {
        try {
            return rpc.call(cmd, payload, RpcProtocol.TRANSFER_TIMEOUT_MS);
        } catch (IOException e) {
            closeTransferConnections();
            throw e;
        }
    }

    private void joinQuietly(Thread thread) {
        try {
            thread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void closeTransferConnections() {
        if (connections != null) {
            for (TransferConnection connection : connections) {
                try {
                    connection.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    /**
     * 仅通过传输通道发送文件，不读写控制通道；301 模式下由帧承载请求与结果。
     * @return 本端读文件与发送线程是否全部正常结束
     */
    protected boolean sendFilesFramed(List<RemoteFile> fileList, Directory localDir, Directory remoteDir,
                                      TransferFileCallback callback) throws IOException {
        ReadFileCall readFileCall = createReadFileCall(buffers, fileList, localDir, remoteDir, connections.size());
        FutureTask<Void> readFileTask = new FutureTask<>(readFileCall);
        Thread readThread = new Thread(readFileTask, "FileRead");
        readThread.start();
        SpeedMonitorThread speedMonitorThread = new SpeedMonitorThread(connections, callback);
        speedMonitorThread.setName("SpeedMonitor");
        speedMonitorThread.start();
        long startTime = System.currentTimeMillis();
        List<FutureTask<Void>> transferTasks = new ArrayList<>(connections.size());
        for (TransferConnection connection : connections) {
            FutureTask<Void> task = new FutureTask<>(new SendFileCall(readFileCall, connection, callback));
            transferTasks.add(task);
            Thread thread = new Thread(task);
            thread.setName("UL_" + connection.iName);
            thread.start();
        }
        boolean complete = true;
        try {
            for (FutureTask<Void> transferTask : transferTasks) {
                transferTask.get();
            }
            readFileTask.get();
        } catch (ExecutionException e) {
            complete = false;
            Throwable cause = e.getCause();
            callback.onReadFileError(cause != null ? cause.toString() : e.toString());
        } catch (InterruptedException e) {
            complete = false;
            Thread.currentThread().interrupt();
        } finally {
            speedMonitorThread.cancel();
        }
        if (complete) {
            long totalUploadTraffic = 0;
            for (TransferConnection connection : connections) {
                totalUploadTraffic += connection.resetTotalTrafficInfo().uploadTraffic;
            }
            callback.onComplete(true, totalUploadTraffic, System.currentTimeMillis() - startTime);
        }
        return complete;
    }

    /**
     * 仅通过传输通道接收文件，不读写控制通道；301 模式下由帧承载请求与结果。
     * @return 写盘与接收线程是否全部正常结束
     */
    protected boolean receiveFilesFramed(TransferFileCallback callback) throws IOException {
        WriteFileCall writeFileCall = createWriteFileCall(buffers, connections.size());
        long startTime = System.currentTimeMillis();
        SpeedMonitorThread speedMonitorThread = new SpeedMonitorThread(connections, callback);
        speedMonitorThread.setName("SpeedMonitor");
        speedMonitorThread.start();
        List<FutureTask<Void>> transferTasks = new ArrayList<>(connections.size());
        for (int i = 0; i < connections.size(); i++) {
            TransferConnection connection = connections.get(i);
            FutureTask<Void> task = new FutureTask<>(new ReceiveFileCall(i, connection, writeFileCall, callback));
            transferTasks.add(task);
            Thread thread = new Thread(task);
            thread.setName("DL_" + connection.iName);
            thread.start();
        }
        FutureTask<Void> writeFileTask = new FutureTask<>(writeFileCall);
        Thread writeThread = new Thread(writeFileTask, "FileWrite");
        writeThread.start();
        boolean complete = true;
        try {
            writeFileTask.get();
            for (FutureTask<Void> task : transferTasks) {
                task.get();
            }
        } catch (ExecutionException e) {
            complete = false;
            Throwable cause = e.getCause();
            callback.onWriteFileError(cause != null ? cause.toString() : e.toString());
        } catch (InterruptedException e) {
            complete = false;
            Thread.currentThread().interrupt();
        } finally {
            speedMonitorThread.cancel();
        }
        if (complete) {
            long totalDownloadTraffic = 0;
            for (TransferConnection connection : connections) {
                totalDownloadTraffic += connection.resetTotalTrafficInfo().downloadTraffic;
            }
            callback.onComplete(false, totalDownloadTraffic, System.currentTimeMillis() - startTime);
        }
        return complete;
    }

    protected abstract WriteFileCall createWriteFileCall(LinkedBlockingDeque<ByteBuffer> buffers, int dequeCount);

    protected abstract ReadFileCall createReadFileCall(LinkedBlockingDeque<ByteBuffer> buffers, List<RemoteFile> files, Directory localDir, Directory remoteDir, int operateThreadCount);

}