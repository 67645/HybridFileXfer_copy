package top.weixiansen574.hybridfilexfer.droidcore;

import android.os.Environment;
import android.os.RemoteException;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.locks.ReentrantLock;

import top.weixiansen574.async.BackstageTask;
import top.weixiansen574.hybridfilexfer.NativeMemory;
import top.weixiansen574.hybridfilexfer.aidl.IIOService;
import top.weixiansen574.hybridfilexfer.core.ControllerIdentifiers;
import top.weixiansen574.hybridfilexfer.core.FileBlock;
import top.weixiansen574.hybridfilexfer.core.HFXService;
import top.weixiansen574.hybridfilexfer.core.ReadFileCall;
import top.weixiansen574.hybridfilexfer.core.ReceiveFileCall;
import top.weixiansen574.hybridfilexfer.core.SendFileCall;
import top.weixiansen574.hybridfilexfer.core.SpeedMonitorThread;
import top.weixiansen574.hybridfilexfer.core.TransferConnection;
import top.weixiansen574.hybridfilexfer.core.WriteFileCall;
import top.weixiansen574.hybridfilexfer.core.bean.Directory;
import top.weixiansen574.hybridfilexfer.core.bean.RemoteFile;
import top.weixiansen574.hybridfilexfer.core.bean.ServerNetInterface;
import top.weixiansen574.hybridfilexfer.core.callback.TransferFileCallback;
import top.weixiansen574.hybridfilexfer.core.rpc.RpcCodec;
import top.weixiansen574.hybridfilexfer.core.rpc.RpcFileOps;
import top.weixiansen574.hybridfilexfer.core.rpc.RpcPeer;
import top.weixiansen574.hybridfilexfer.core.rpc.RpcProtocol;
import top.weixiansen574.hybridfilexfer.core.rpc.RpcResult;
import top.weixiansen574.hybridfilexfer.core.rpc.TransferRequest;
import top.weixiansen574.hybridfilexfer.droidcore.callback.StartServerCallback;
import top.weixiansen574.nio.DataByteChannel;

public class HFXServer extends HFXService implements RpcFileOps {
    /** 300 兼容模式下探测对端是否已断开的间隔。 */
    private static final long LEGACY_PROBE_INTERVAL_MS = 1000L;

    public static HFXServer instance;
    protected final IIOService ioService;
    protected ServerSocketChannel serverSocketChannel;
    protected int remoteFileSystem;
    protected String remoteHomeDir;

    /** 控制通道对应的底层通道，300 模式下用于探测对端是否已断开。 */
    private SocketChannel controlSocketChannel;
    private Thread legacyWatchdog;
    /** 保护 300 模式下控制通道的所有读写，避免探测线程与业务读写互相打断。 */
    private final ReentrantLock ioLock = new ReentrantLock();
    private final Object sessionLock = new Object();
    private volatile boolean stopping;
    private volatile boolean sessionActive;
    private volatile String sessionEndReason;

    public HFXServer(IIOService ioService) {
        this.ioService = ioService;
    }

    public void startServer(int port, List<ServerNetInterface> interfaceList, int localBufferCount, int remoteBufferCount, StartServerCallback callback) throws IOException {
        ServerSocketChannel serverSocketChannel;
        try {
            serverSocketChannel = ServerSocketChannel.open().bind(new InetSocketAddress(port));
        } catch (IOException e) {
            callback.onBindFailed(port);
            return;
        }
        this.serverSocketChannel = serverSocketChannel;
        callback.onStatedServer();
        try {
            //一个服务进程内持续监听：任意一次会话结束后都回到等待新连接的状态
            while (!stopping && serverSocketChannel.isOpen()) {
                if (!acceptSession(interfaceList, localBufferCount, remoteBufferCount, callback)) {
                    continue;
                }
                callback.onConnectSuccess();
                awaitSessionEnd();
                String reason = sessionEndReason;
                cleanupSession();
                if (!stopping) {
                    callback.onDisconnected(reason);
                }
            }
        } catch (ClosedChannelException e) {
            //AsynchronousCloseException 是其子类，一并在此处理
            //本端主动停止监听
        } finally {
            cleanupSession();
        }
    }

    /**
     * 执行一次完整握手。成功时把会话状态写入实例字段并返回 true；
     * 失败时清理本次握手产生的资源并返回 false，由外层继续等待下一个客户端。
     */
    private boolean acceptSession(List<ServerNetInterface> interfaceList, int localBufferCount,
                                  int remoteBufferCount, StartServerCallback callback) throws IOException {
        while (!stopping && serverSocketChannel.isOpen()) {
            SocketChannel controlSocket;
            try {
                controlSocket = serverSocketChannel.accept();
            } catch (ClosedChannelException e) {
                return false;
            }
            if (controlSocket == null) {
                continue;
            }
            DataByteChannel ctChannel = new DataByteChannel(controlSocket);
            List<TransferConnection> accepted = new ArrayList<>(interfaceList.size());
            boolean anyAccepted = false;
            try {
                //协议判断
                byte[] headerBytes = CLIENT_HEADER.getBytes(StandardCharsets.UTF_8);
                byte[] header = new byte[headerBytes.length];
                ctChannel.readFully(header);
                if (!Arrays.equals(headerBytes, header)) {
                    ctChannel.writeBytes("protocol error\n");
                    ctChannel.close();
                    continue;
                }
                //版本判断：同时接受旧版 300 与双向帧 301
                int versionCode = ctChannel.readInt();
                if (versionCode != RpcProtocol.VERSION_LEGACY && versionCode != RpcProtocol.VERSION_RPC) {
                    //返回当前服务端版本信息
                    ctChannel.writeBoolean(false);//版本未正确匹配
                    ctChannel.writeInt(RpcProtocol.VERSION_LEGACY);
                    ctChannel.close();
                    continue;
                }
                ctChannel.writeBoolean(true);//版本正确匹配
                ctChannel.writeInt(interfaceList.size());//网卡IP数量
                for (ServerNetInterface netInterface : interfaceList) {
                    byte[] address = netInterface.address.getAddress();
                    ctChannel.writeUTF(netInterface.name);//网卡名称
                    ctChannel.writeByte(address.length);//地址长度（IPv4：4与IPv6：16）
                    ctChannel.write(address);//地址
                    if (netInterface.clientBindAddress == null) {
                        ctChannel.writeByte(0);//地址长度（null:0）
                    } else {
                        byte[] bAddress = netInterface.clientBindAddress.getAddress();
                        ctChannel.writeByte(bAddress.length);//地址长度（IPv4：4与IPv6：16）
                        ctChannel.write(bAddress);//地址
                    }
                }
                //连接传输通道
                for (int i = 0; i < interfaceList.size(); i++) {
                    //对方连接通道是否成功
                    boolean succeed = ctChannel.readBoolean();
                    //对方所连接通道的名称（由控制器通道发送名称）
                    String name = ctChannel.readUTF();
                    if (succeed) {
                        //accept通道
                        accepted.add(new TransferConnection(name, new DataByteChannel(serverSocketChannel.accept())));
                        ctChannel.writeBoolean(true);
                        anyAccepted = true;
                        callback.onAccepted(name);
                    } else {
                        //该网卡在对方不可达：只跳过这一张，其余通道继续协商
                        callback.onAcceptFailed(name);
                    }
                }
                if (accepted.isEmpty()) {
                    ctChannel.close();
                    if (anyAccepted) {
                        callback.onDisconnected("没有可用的传输通道");
                    }
                    continue;
                }
                //告知对方创建的缓冲区块数量
                ctChannel.writeInt(remoteBufferCount);
                if (!ctChannel.readBoolean()) {
                    callback.onPcOOM();
                    stopListening();
                    closeConnections(accepted);
                    ctChannel.close();
                    return false;
                }
                //创建缓冲区块
                for (int i = 0; i < localBufferCount; i++) {
                    ByteBuffer buffer = NativeMemory.allocateLargeBuffer(FileBlock.BLOCK_SIZE);
                    if (buffer != null) {
                        buffers.add(buffer);
                    } else {
                        //释放缓冲区块内存
                        releaseBuffers();
                        ctChannel.writeBoolean(false);
                        callback.onMeOOM(i, localBufferCount);
                        stopListening();
                        closeConnections(accepted);
                        ctChannel.close();
                        return false;
                    }
                }
                ctChannel.writeBoolean(true);
                //读取对方文件系统信息
                int remoteFileSystem = ctChannel.readInt();
                //读取对方设定的主目录
                String remoteHomeDir = ctChannel.readUTF();
                //会话建立
                this.ctChannel = ctChannel;
                this.controlSocketChannel = controlSocket;
                this.connections = accepted;
                this.protocolVersion = versionCode;
                this.remoteFileSystem = remoteFileSystem;
                this.remoteHomeDir = remoteHomeDir;
                //在启动读线程之前标记会话处于活跃状态，避免“连上即断开”时丢失结束信号
                synchronized (sessionLock) {
                    sessionActive = true;
                    sessionEndReason = null;
                }
                if (versionCode == RpcProtocol.VERSION_RPC) {
                    this.rpc = new RpcPeer(ctChannel, this, "Server");
                } else {
                    startLegacyWatchdog();
                }
                return true;
            } catch (IOException e) {
                closeQuietly(ctChannel);
                closeConnections(accepted);
                if (anyAccepted) {
                    callback.onDisconnected("握手失败：" + e.getMessage());
                }
                if (stopping || !serverSocketChannel.isOpen()) {
                    return false;
                }
                //继续等待下一个客户端，无需用户手动重启服务
            }
        }
        return false;
    }

    private void awaitSessionEnd() {
        synchronized (sessionLock) {
            while (sessionActive && !stopping) {
                try {
                    sessionLock.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /** 301 模式由 RpcPeer 读到 EOF 触发；300 模式由探测线程或 IO 异常触发。 */
    private void endSession(String reason) {
        synchronized (sessionLock) {
            if (!sessionActive) {
                return;
            }
            sessionEndReason = reason;
            sessionActive = false;
            sessionLock.notifyAll();
        }
    }

    /** 本端停止服务时唤醒会话等待循环。 */
    private void wakeSession() {
        synchronized (sessionLock) {
            sessionActive = false;
            sessionLock.notifyAll();
        }
    }

    /**
     * 300 兼容模式下，控制通道上没有读线程，客户端断开只能靠主动探测发现。
     * 仅在业务读写空闲时以非阻塞方式读一个字节：-1 表示对端关闭。
     */
    private void startLegacyWatchdog() {
        Thread thread = new Thread(() -> {
            while (sessionActive && !stopping) {
                try {
                    Thread.sleep(LEGACY_PROBE_INTERVAL_MS);
                } catch (InterruptedException e) {
                    return;
                }
                if (!sessionActive || stopping) {
                    return;
                }
                if (!ioLock.tryLock()) {
                    //有业务读写正在进行，跳过本轮
                    continue;
                }
                try {
                    SocketChannel channel = controlSocketChannel;
                    if (channel == null || !channel.isOpen()) {
                        endSession("控制通道已关闭");
                        return;
                    }
                    channel.configureBlocking(false);
                    try {
                        int read = channel.read(ByteBuffer.allocate(1));
                        if (read < 0) {
                            endSession("对端已断开连接");
                            return;
                        }
                        if (read > 0) {
                            endSession("控制通道出现非预期数据");
                            return;
                        }
                    } finally {
                        channel.configureBlocking(true);
                    }
                } catch (IOException e) {
                    endSession("控制通道异常：" + e.getMessage());
                    return;
                } finally {
                    ioLock.unlock();
                }
            }
        }, "HFX-LegacyWatchdog");
        thread.setDaemon(true);
        legacyWatchdog = thread;
        thread.start();
    }

    private void stopLegacyWatchdog() {
        Thread thread = legacyWatchdog;
        legacyWatchdog = null;
        if (thread != null) {
            thread.interrupt();
        }
    }

    /** 释放当前会话占用的资源，可重复调用。 */
    private void cleanupSession() {
        stopLegacyWatchdog();
        releaseBuffers();
        RpcPeer oldRpc = this.rpc;
        this.rpc = null;
        if (oldRpc != null) {
            oldRpc.close();
        }
        List<TransferConnection> oldConnections = this.connections;
        this.connections = null;
        closeConnections(oldConnections);
        DataByteChannel oldChannel = this.ctChannel;
        this.ctChannel = null;
        this.controlSocketChannel = null;
        closeQuietly(oldChannel);
        this.remoteFileSystem = 0;
        this.remoteHomeDir = null;
        this.protocolVersion = RpcProtocol.VERSION_LEGACY;
    }

    /**
     * 释放原生缓冲区块。停止服务时会话线程与断开线程可能同时进入，
     * 这里必须串行化，否则同一块内存会被重复 free 导致进程崩溃。
     */
    private synchronized void releaseBuffers() {
        for (ByteBuffer buffer : buffers) {
            NativeMemory.freeBuffer(buffer);
        }
        buffers.clear();
    }

    private void closeConnections(List<TransferConnection> connectionList) {
        if (connectionList != null) {
            for (TransferConnection connection : connectionList) {
                try {
                    connection.close();
                } catch (IOException ignored) {
                }
            }
            connectionList.clear();
        }
    }

    private void closeQuietly(DataByteChannel channel) {
        if (channel != null) {
            try {
                channel.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void stopListening() {
        stopping = true;
        wakeSession();
        if (serverSocketChannel != null) {
            try {
                serverSocketChannel.close();
            } catch (IOException ignored) {
            }
        }
    }

    public void closeServerSocket() {
        stopListening();
    }

    public void disconnect(BackstageTask.BaseEventHandler callback) {
        new DisconnectTask(callback, this).execute();
    }

    public int getRemoteFileSystem() {
        return remoteFileSystem;
    }

    public String getRemoteHomeDir() {
        return remoteHomeDir;
    }

    public void sendFilesToRemote(List<RemoteFile> files, Directory localDir, Directory remoteDir, TransferFileCallback callback) throws IOException {
        if (rpc != null) {
            //301：推送迁移为帧请求
            pushFilesFramed(rpc, files, localDir, remoteDir, callback);
            return;
        }
        ioLock.lock();
        try {
            ctChannel.writeShort(ControllerIdentifiers.REQUEST_RECEIVE);//请求对方接收
            sendFiles(files, localDir, remoteDir, callback);
        } finally {
            ioLock.unlock();
        }
    }

    public void sendFilesToShelf(List<RemoteFile> files, Directory localDir, Directory remoteDir, TransferFileCallback callback) throws IOException {
        if (rpc != null) {
            //301：拉取迁移为帧请求
            pullFilesFramed(rpc, files, localDir, remoteDir, callback);
            return;
        }
        ioLock.lock();
        try {
            ctChannel.writeShort(ControllerIdentifiers.REQUEST_SEND);
            ctChannel.writeInt(files.size());
            for (RemoteFile file : files) {
                ctChannel.writeUTF(file.getPath());
            }
            ctChannel.writeUTF(localDir.path);
            ctChannel.writeInt(localDir.fileSystem);
            ctChannel.writeUTF(remoteDir.path);
            receiveFiles(callback);
        } finally {
            ioLock.unlock();
        }
    }

    public List<RemoteFile> listLocalFiles(String path) throws RemoteException {
        return listLocalFiles(ioService, path);
    }

    public boolean deleteLocalFile(String file) throws RemoteException {
        return ioService.deleteFile(file);
    }

    public boolean createLocalDir(String parent, String child) throws RemoteException {
        return ioService.appendAndMkdirs(parent, child);
    }

    public List<RemoteFile> listClientFiles(String path) throws IOException {
        if (rpc != null) {
            RpcResult result = rpc.call(RpcProtocol.CMD_LIST_FILES, RpcCodec.encodePath(path));
            return RpcCodec.decodeFileList(result.body);
        }
        ioLock.lock();
        try {
            ctChannel.writeShort(ControllerIdentifiers.LIST_FILES);
            ctChannel.writeUTF(path);
            int listSize = ctChannel.readInt();
            if (listSize == -1) {
                return null;
            }
            ArrayList<RemoteFile> remoteFiles = new ArrayList<>(listSize);
            //| name       | path       | lastModified | size    | isDirectory |
            //| ---------- | ---------- | ------------ | ------- | ----------- |
            //| String:UTF | String:UTF | long:8b      | long:8b | boolean     |
            for (int i = 0; i < listSize; i++) {
                RemoteFile remoteFile = new RemoteFile(
                        ctChannel.readUTF(),//name
                        ctChannel.readUTF(),//path
                        ctChannel.readLong(),//lastModified
                        ctChannel.readLong(),//size
                        ctChannel.readBoolean()//isDirectory
                );
                remoteFiles.add(remoteFile);
            }
            return remoteFiles;
        } finally {
            ioLock.unlock();
        }
    }

    public boolean deleteRemoteFile(String file) throws IOException {
        if (rpc != null) {
            RpcResult result = rpc.call(RpcProtocol.CMD_DELETE_FILE, RpcCodec.encodePath(file));
            return RpcCodec.decodeBool(result.body);
        }
        ioLock.lock();
        try {
            ctChannel.writeShort(ControllerIdentifiers.DELETE_FILE);
            ctChannel.writeUTF(file);
            return ctChannel.readBoolean();
        } finally {
            ioLock.unlock();
        }
    }

    public boolean createRemoteDir(String parent, String child) throws IOException {
        if (rpc != null) {
            RpcResult result = rpc.call(RpcProtocol.CMD_MKDIR, RpcCodec.encodeMkdir(parent, child));
            return RpcCodec.decodeBool(result.body);
        }
        ioLock.lock();
        try {
            ctChannel.writeShort(ControllerIdentifiers.MKDIR);
            ctChannel.writeUTF(parent);
            ctChannel.writeUTF(child);
            return ctChannel.readBoolean();
        } finally {
            ioLock.unlock();
        }
    }

    public static List<RemoteFile> listLocalFiles(IIOService ioService, String path) throws RemoteException {
        int[] chunkIds = ioService.listFiles(path);
        if (chunkIds == null) {
            return null;
        }
        ArrayList<RemoteFile> remoteFiles = new ArrayList<>();
        for (int chunkId : chunkIds) {
            remoteFiles.addAll(ioService.getAndRemoveFileListSlice(chunkId));
        }
        return remoteFiles;
    }

    public List<String> getConnectionListINames() {
        List<String> names = new ArrayList<>(connections.size());
        for (TransferConnection connection : connections) {
            names.add(connection.iName);
        }
        return names;
    }

    public void disconnect() {
        stopListening();
        stopLegacyWatchdog();
        ioLock.lock();
        try {
            releaseBuffers();
            if (ctChannel != null) {
                try {
                    if (rpc != null) {
                        rpc.call(RpcProtocol.CMD_SHUTDOWN, null, 2000L);
                    } else {
                        //通知断开连接
                        ctChannel.writeShort(ControllerIdentifiers.SHUTDOWN);
                    }
                } catch (IOException ignored) {
                }
            }
        } finally {
            ioLock.unlock();
        }
        //关闭serverSocket，释放端口绑定
        if (serverSocketChannel != null) {
            try {
                serverSocketChannel.close();
            } catch (IOException ignored) {
            }
        }
        //关闭所有用于传输的连接
        List<TransferConnection> oldConnections = this.connections;
        this.connections = null;
        closeConnections(oldConnections);
    }

    public void close() {
        stopListening();
        stopLegacyWatchdog();
        //释放并清理缓冲区块
        releaseBuffers();
        //关闭serverSocket，释放端口绑定，不用管其他accept的Socket
        if (serverSocketChannel != null) {
            try {
                serverSocketChannel.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    protected WriteFileCall createWriteFileCall(LinkedBlockingDeque<ByteBuffer> buffers, int dequeCount) {
        return new DroidWriteFileCall(buffers, dequeCount, ioService);
    }

    @Override
    protected ReadFileCall createReadFileCall(LinkedBlockingDeque<ByteBuffer> buffers, List<RemoteFile> files, Directory localDir, Directory remoteDir, int operateThreadCount) {
        return new DroidReadFileCall(ioService, buffers, files, localDir, remoteDir, operateThreadCount);
    }

    // ---------------- RpcFileOps：响应电脑端主动请求 ----------------

    @Override
    public List<RemoteFile> rpcListFiles(String path) throws Exception {
        return listLocalFiles(path);
    }

    @Override
    public boolean rpcDeleteFile(String path) throws Exception {
        return ioService.deleteFile(path);
    }

    @Override
    public boolean rpcMkdir(String parent, String child) throws Exception {
        return ioService.appendAndMkdirs(parent, child);
    }

    @Override
    public boolean rpcIsBusy() {
        return transferBusy;
    }

    @Override
    public String rpcReceiveFiles() throws Exception {
        return receiveFilesFromPeer(SILENT_CALLBACK);
    }

    @Override
    public String rpcSendFiles(TransferRequest request) throws Exception {
        return sendFilesToPeer(request.files, request.localDir, request.remoteDir, SILENT_CALLBACK);
    }

    @Override
    public byte[] rpcQueryInfo() throws Exception {
        return RpcCodec.encodeQueryInfo(Directory.getCurrentFileSystem(),
                Environment.getExternalStorageDirectory().getAbsolutePath());
    }

    @Override
    public void rpcOnPeerClosed(String reason) {
        //301 模式的读线程发现对端关闭：结束当前会话，回到等待新连接的状态
        endSession(reason);
    }
}
