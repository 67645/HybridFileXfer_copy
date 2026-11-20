package top.weixiansen574.hybridfilexfer.core;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import top.weixiansen574.hybridfilexfer.core.auth.PcAuthException;
import top.weixiansen574.hybridfilexfer.core.auth.PcIdentity;
import top.weixiansen574.hybridfilexfer.core.security.PathGuard;
import top.weixiansen574.hybridfilexfer.core.bean.Directory;
import top.weixiansen574.hybridfilexfer.core.bean.RemoteFile;
import top.weixiansen574.hybridfilexfer.core.callback.ClientCallBack;
import top.weixiansen574.hybridfilexfer.core.callback.ConnectServerCallback;
import top.weixiansen574.hybridfilexfer.core.callback.TransferFileCallback;
import top.weixiansen574.hybridfilexfer.core.rpc.RpcCodec;
import top.weixiansen574.hybridfilexfer.core.rpc.RpcFileOps;
import top.weixiansen574.hybridfilexfer.core.rpc.RpcPeer;
import top.weixiansen574.hybridfilexfer.core.rpc.RpcProtocol;
import top.weixiansen574.hybridfilexfer.core.rpc.RpcResult;
import top.weixiansen574.hybridfilexfer.core.rpc.TransferRequest;
import top.weixiansen574.nio.DataByteChannel;

public abstract class HFXClient extends HFXService implements RpcFileOps {

    /** 单条通道的 TCP 连接超时，避免某张不可达网卡让整个握手一直卡住。 */
    protected static final int CONNECT_TIMEOUT_MS = 5000;

    protected final String serverControllerAddress;
    protected final int serverPort;
    protected final String homeDir;
    protected boolean isRun = true;
    protected ClientCallBack callBack;
    private int preferredVersion = RpcProtocol.VERSION_LEGACY;
    private int remoteVersion = -1;
    private final PcIdentity identity = PcIdentity.get();
    private String lastConnectError;
    /** 本端允许远端读写的根目录；默认就是接收目录/主目录。 */
    protected final PathGuard pathGuard;

    public HFXClient(String serverControllerAddress, int serverPort,String homeDir) {
        this.serverControllerAddress = serverControllerAddress;
        this.serverPort = serverPort;
        this.homeDir = homeDir;
        String root = homeDir == null || homeDir.trim().isEmpty() ? "/" : homeDir;
        this.pathGuard = new PathGuard(Collections.singletonList(new File(root)), false);
    }

    public PathGuard getPathGuard() {
        return pathGuard;
    }

    /** 指定首选协议版本（300 或 301）。 */
    public void setPreferredProtocolVersion(int version) {
        if (version != RpcProtocol.VERSION_LEGACY && version != RpcProtocol.VERSION_RPC) {
            throw new IllegalArgumentException("不支持的协议版本：" + version);
        }
        this.preferredVersion = version;
    }

    public int getRemoteProtocolVersion() {
        return remoteVersion;
    }

    /** 最近一次连接失败的可读原因（授权拒绝、协议不匹配等），未失败时为 null。 */
    public String getLastConnectError() {
        return lastConnectError;
    }

    /**
     * 先尝试 301；若对端只支持 300，则重新建连并以 300 完成握手。
     */
    public boolean connectAuto(ConnectServerCallback callback) throws IOException {
        if (connectWithVersion(RpcProtocol.VERSION_RPC, callback)) {
            return true;
        }
        if (remoteVersion == RpcProtocol.VERSION_LEGACY) {
            resetConnectionState();
            return connectWithVersion(RpcProtocol.VERSION_LEGACY, callback);
        }
        return false;
    }

    public boolean connect(ConnectServerCallback callback) throws IOException {
        return connectWithVersion(preferredVersion, callback);
    }

    private void resetConnectionState() {
        ctChannel = null;
        connections = null;
        rpc = null;
        buffers.clear();
        isRun = true;
    }

    private boolean connectWithVersion(int version, ConnectServerCallback callback) throws IOException {
        lastConnectError = null;
        try {
            //System.out.println("正在连接控制通道：" + serverControllerAddress);
            callback.onConnectingControlChannel(serverControllerAddress, serverPort);
            ctChannel = new DataByteChannel(connectChannel(
                    new InetSocketAddress(serverControllerAddress, serverPort), null,
                    version == RpcProtocol.VERSION_RPC));

            ctChannel.write(CLIENT_HEADER.getBytes(StandardCharsets.UTF_8));
            ctChannel.writeInt(version);
            if (!ctChannel.readBoolean()) {
                remoteVersion = ctChannel.readInt();
                callback.onVersionMismatch(version, remoteVersion);
                ctChannel.close();
                return false;
            }
            remoteVersion = version;
            if (version == RpcProtocol.VERSION_RPC) {
                // 301：在网卡与缓冲协商之前完成电脑身份交换与授权
                ctChannel.writeInt(RpcProtocol.AUTH_MAGIC);
                ctChannel.writeUTF(identity.getId());
                ctChannel.writeUTF(identity.getName());
                int magic = ctChannel.readInt();
                if (magic != RpcProtocol.AUTH_RESP_MAGIC) {
                    throw new IOException("授权握手失败：对端不是兼容的 301 服务端，请同时升级手机端");
                }
                byte authStatus = ctChannel.readByte();
                String reason = ctChannel.readUTF();
                if (authStatus != RpcProtocol.AUTH_OK) {
                    throw new PcAuthException(reason == null || reason.isEmpty()
                            ? "手机端拒绝了这台电脑的连接" : reason);
                }
            }
        } catch (PcAuthException e) {
            lastConnectError = e.getMessage();
            try {
                ctChannel.close();
            } catch (IOException ignored) {
            }
            return false;
        } catch (IOException e) {
            //System.out.println("控制通道连接到手机失败，请检查手机的服务端是否启动？");
            callback.onConnectControlFailed();
            return false;
        }
        int ipCount = ctChannel.readInt();
        String[] names = new String[ipCount];
        InetAddress[] addresses = new InetAddress[ipCount];
        InetAddress[] bindAddresses = new InetAddress[ipCount];

        for (int i = 0; i < ipCount; i++) {
            String name = ctChannel.readUTF();
            byte[] address = new byte[ctChannel.readByte()];
            ctChannel.readFully(address);
            InetAddress inetAddress = InetAddress.getByAddress(address);
            byte l46 = ctChannel.readByte();
            InetAddress bindAddress = null;
            if (l46 != 0) {
                byte[] bAddress = new byte[l46];
                ctChannel.readFully(bAddress);
                bindAddress = InetAddress.getByAddress(bAddress);
            }
            names[i] = name;
            addresses[i] = inetAddress;
            bindAddresses[i] = bindAddress;
        }
        connections = new ArrayList<>(ipCount);
        try {
            for (int i = 0; i < ipCount; i++) {
                String name = names[i];
                InetAddress inetAddress = addresses[i];
                InetAddress bindAddress = bindAddresses[i];
                /*System.out.printf("正在连接 网卡名：%s 远程地址：%s 绑定地址：%s\n", name, inetAddress.getHostAddress(), bindAddress == null ?
                        "null" : bindAddress.getHostAddress());*/
                callback.onConnectingTransferChannel(name, inetAddress, bindAddress);
                /*if (name.equals("USB_ADB") && !serverControllerAddress.equals("127.0.0.1")) {
                    System.err.println("错误：你在手机上选用了USB_ADB网卡，但没有使用ADB进行连接");
                    ctChannel.writeBoolean(false);
                    socket.close();
                    return false;
                }*/
                try {
                    SocketChannel socketChannel = connectChannel(
                            new InetSocketAddress(inetAddress, serverPort), bindAddress,
                            version == RpcProtocol.VERSION_RPC);
                    connections.add(new TransferConnection(name, new DataByteChannel(socketChannel)));
                } catch (IOException e) {
                    //该网卡不可达：只跳过这张网卡，其余可达网卡继续协商，避免整个会话卡死在等待连接
                    callback.onConnectTransferChannelFailed(name, inetAddress, e);
                    ctChannel.writeBoolean(false);
                    ctChannel.writeUTF(name);
                    continue;
                }
                ctChannel.writeBoolean(true);
                ctChannel.writeUTF(name);
                ctChannel.readBoolean();
            }
            //初始化缓冲区块
            int bufferCount = ctChannel.readInt();
            for (int i = 0; i < bufferCount; i++) {
                ByteBuffer buffer = createBuffer(FileBlock.BLOCK_SIZE);
                if (buffer != null) {
                    buffers.add(buffer);
                } else {
                    String arch = System.getProperty("os.arch");
                    long availableMemoryMB = getAvailableMemoryMB();
                    freeBuffers();
                    callback.onOOM(i, bufferCount, availableMemoryMB, arch);
                    ctChannel.writeBoolean(false);
                    ctChannel.close();
                    return false;
                }
            }
            ctChannel.writeBoolean(true);
            if (!ctChannel.readBoolean()) {
                callback.onRemoteOOM();
                ctChannel.close();
                return false;
            }
            //返回文件系统信息给对方
            ctChannel.writeInt(Directory.getCurrentFileSystem());
            //返回主路径信息给对方
            ctChannel.writeUTF(homeDir);
        } catch (IOException e) {
            closeConnections();
            ctChannel.close();
            return false;
        }
        //System.out.println("传输通道已全部连接完成");
        List<String> channelNames = new ArrayList<>(connections.size());
        for (TransferConnection connection : connections) {
            channelNames.add(connection.iName);
        }
        callback.onConnectSuccess(channelNames);
        protocolVersion = version;
        if (version == RpcProtocol.VERSION_RPC) {
            rpc = new RpcPeer(ctChannel, this, "Client");
        }
        return true;
    }

    /**
     * 带超时地建立一条 TCP 通道，超时或失败时抛出 IOException。
     *
     * @param target      目标地址
     * @param bindAddress 本端需要绑定的网卡地址，可为 null
     * @param noDelay     是否关闭 Nagle（301 启用，300 保持原行为）
     */
    private static SocketChannel connectChannel(InetSocketAddress target, InetAddress bindAddress,
                                                boolean noDelay) throws IOException {
        SocketChannel channel = SocketChannel.open();
        try {
            if (bindAddress != null) {
                channel.bind(new InetSocketAddress(bindAddress, 0));
            }
            channel.configureBlocking(false);
            if (!channel.connect(target)) {
                Selector selector = Selector.open();
                try {
                    channel.register(selector, SelectionKey.OP_CONNECT);
                    long deadline = System.currentTimeMillis() + CONNECT_TIMEOUT_MS;
                    while (!channel.finishConnect()) {
                        long remain = deadline - System.currentTimeMillis();
                        if (remain <= 0) {
                            throw new SocketTimeoutException("连接超时：" + target);
                        }
                        if (selector.select(remain) == 0) {
                            throw new SocketTimeoutException("连接超时：" + target);
                        }
                        selector.selectedKeys().clear();
                    }
                } finally {
                    selector.close();
                }
            }
            channel.configureBlocking(true);
            if (noDelay) {
                // 301 的每文件请求/应答都是小包，Nagle 与延迟确认叠加会带来约 40ms 固定延迟
                channel.socket().setTcpNoDelay(true);
            }
            return channel;
        } catch (IOException e) {
            try {
                channel.close();
            } catch (IOException ignored) {
            }
            throw e;
        }
    }

    private void closeConnections() {
        if (connections != null) {
            for (TransferConnection connection : connections) {
                try {
                    connection.close();
                } catch (IOException ignored) {
                }
            }
            connections.clear();
        }
    }

    public abstract ByteBuffer createBuffer(int size);

    public abstract long getAvailableMemoryMB();

    public void start(ClientCallBack transferFileCallback) throws Exception {
        this.callBack = transferFileCallback;
        if (rpc != null) {
            // 301 模式：命令与应答全部由 RpcPeer 的读线程处理
            while (isRun) {
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            return;
        }
        //LOOP
        while (isRun) {
            short id = ctChannel.readShort();
            switch (id) {
                case ControllerIdentifiers.LIST_FILES:
                    handleListFiles();
                    break;
                case ControllerIdentifiers.DELETE_FILE:
                    handleDeleteFile();
                    break;
                case ControllerIdentifiers.MKDIR:
                    handleMkdir();
                    break;
                case ControllerIdentifiers.REQUEST_RECEIVE:
                    handleReceiveFiles();
                    break;
                case ControllerIdentifiers.REQUEST_SEND:
                    handleSendFiles();
                    break;
                case ControllerIdentifiers.SHUTDOWN:
                    handleShutdown();
                    break;
            }
        }
    }


    private void handleDeleteFile() throws Exception {
        ctChannel.writeBoolean(deleteLocalFile(ctChannel.readUTF()));
    }

    protected abstract boolean deleteLocalFile(String path) throws Exception;
    /*public boolean deleteLocalFile(String path) {
        File file = new File(path);
        if (!file.exists()) {
            System.out.println("文件或目录不存在: " + path);
            return false;
        }

        // 如果是目录，递归删除
        if (file.isDirectory()) {
            File[] files = file.listFiles();
            if (files != null) { // 检查是否为空
                for (File subFile : files) {
                    deleteLocalFile(subFile.getAbsolutePath());
                }
            }
        }

        // 删除文件或空目录
        return file.delete();
    }*/

    private void handleMkdir() throws Exception {
        String parent = ctChannel.readUTF();
        String child = ctChannel.readUTF();
        ctChannel.writeBoolean(mkdir(parent, child));
    }

    protected abstract boolean mkdir(String parent, String child) throws Exception;

    private void handleShutdown() {
        isRun = false;
        try {
            ctChannel.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
        if (connections != null) {
            for (TransferConnection transferConnection : connections) {
                try {
                    transferConnection.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
        callBack.onExit();
        //System.out.println("收到停止指令，客户端已正常关闭！");
    }

    private void handleListFiles() throws Exception {
        String path = ctChannel.readUTF();
        //System.out.println(path);
        if (!path.equals("/")) {
            List<RemoteFile> files = listFiles(path);
            if (files != null) {
                ctChannel.writeInt(files.size());
                for (RemoteFile file : files) {
                    writeFile(file);
                }
            } else {
                ctChannel.writeInt(-1);
            }
        } else {
            File[] roots = File.listRoots();
            //判断是否是Linux的目录结构，Windows的根目录是C:\\，而不是所有盘符，Linux的根目录是“/”没有盘符概念
            if (roots.length == 1 && roots[0].getAbsolutePath().equals("/")) {
                List<RemoteFile> files = listFiles(roots[0].getPath());
                if (files == null) {
                    ctChannel.writeInt(-1);
                    return;
                }
                ctChannel.writeInt(files.size());
                for (RemoteFile file : files) {
                    writeFile(file);
                }
            } else {//Windows的
                ctChannel.writeInt(roots.length);
                for (File file : roots) {
                    ctChannel.writeUTF(file.getPath());//不要getName，否则空白
                    ctChannel.writeUTF(file.getPath());
                    ctChannel.writeLong(file.lastModified());
                    ctChannel.writeLong(file.length());
                    ctChannel.writeBoolean(file.isDirectory());
                }
            }
        }
        //已弃用ObjectOutputStream
        //| name       | path       | lastModified | size    | isDirectory |
        //| ---------- | ---------- | ------------ | ------- | ----------- |
        //| String:UTF | String:UTF | long:8b      | long:8b | boolean     |
    }

    protected abstract List<RemoteFile> listFiles(String path) throws Exception;

    private void writeFile(RemoteFile file) throws IOException {
        ctChannel.writeUTF(file.getName());
        ctChannel.writeUTF(file.getPath());
        ctChannel.writeLong(file.lastModified());
        ctChannel.writeLong(file.getSize());
        ctChannel.writeBoolean(file.isDirectory());
    }

    private void handleReceiveFiles() throws IOException {
        //System.out.println("准备接收");
        callBack.onReceiving();
        isRun = receiveFiles(callBack);
    }

    private void handleSendFiles() throws IOException {
        int listSize = ctChannel.readInt();
        List<RemoteFile> fileList = new ArrayList<>(listSize);
        for (int i = 0; i < listSize; i++) {
            fileList.add(new RemoteFile(new File(ctChannel.readUTF())));
        }
        Directory remoteDir = new Directory(ctChannel.readUTF(), ctChannel.readInt());//对方的localDir
        Directory localDir = new Directory(ctChannel.readUTF(), Directory.getCurrentFileSystem());//对方为remoteDir
        callBack.onSending();
        isRun = sendFiles(fileList,localDir,remoteDir,callBack);
    }

    protected void freeBuffers(){
        buffers.clear();
    }

    /** 主动断开当前连接（供 GUI 使用），关闭控制通道与全部传输通道。 */
    public void disconnect() {
        isRun = false;
        if (rpc != null) {
            try {
                rpc.call(RpcProtocol.CMD_SHUTDOWN, null, 2000L);
            } catch (IOException ignored) {
            }
            rpc.close();
            rpc = null;
        }
        if (connections != null) {
            for (TransferConnection connection : connections) {
                try {
                    connection.close();
                } catch (IOException ignored) {
                }
            }
            connections.clear();
        }
        if (ctChannel != null) {
            try {
                ctChannel.close();
            } catch (IOException ignored) {
            }
            ctChannel = null;
        }
        freeBuffers();
    }

    // ---------------- 301 双向帧：客户端主动请求 ----------------

    private void requireRpc() throws IOException {
        if (rpc == null) {
            throw new IOException("当前为 300 兼容模式，对端不支持客户端主动请求");
        }
    }

    /** 主动列出对端目录；301 模式专用。 */
    public List<RemoteFile> requestListFiles(String path) throws IOException {
        requireRpc();
        RpcResult result = rpc.call(RpcProtocol.CMD_LIST_FILES, RpcCodec.encodePath(path));
        return RpcCodec.decodeFileList(result.body);
    }

    public boolean requestDeleteFile(String path) throws IOException {
        requireRpc();
        RpcResult result = rpc.call(RpcProtocol.CMD_DELETE_FILE, RpcCodec.encodePath(path));
        return RpcCodec.decodeBool(result.body);
    }

    public boolean requestMkdir(String parent, String child) throws IOException {
        requireRpc();
        RpcResult result = rpc.call(RpcProtocol.CMD_MKDIR, RpcCodec.encodeMkdir(parent, child));
        return RpcCodec.decodeBool(result.body);
    }

    /** 301：把本地文件推给对端。 */
    public void pushFiles(List<RemoteFile> files, Directory localDir, Directory remoteDir,
                          TransferFileCallback callback) throws IOException {
        requireRpc();
        pushFilesFramed(rpc, files, localDir, remoteDir, callback);
    }

    /** 301：请求对端把文件发过来。 */
    public void pullFiles(List<RemoteFile> files, Directory localDir, Directory remoteDir,
                          TransferFileCallback callback) throws IOException {
        requireRpc();
        pullFilesFramed(rpc, files, localDir, remoteDir, callback);
    }

    /** 301：查询对端文件系统与主目录，返回 {文件系统类型, 主目录}。 */
    public String[] queryInfo() throws IOException {
        requireRpc();
        RpcResult result = rpc.call(RpcProtocol.CMD_QUERY_INFO, null);
        return RpcCodec.decodeQueryInfo(result.body);
    }

    public RpcPeer getRpc() {
        return rpc;
    }

    // ---------------- RpcFileOps：响应对端请求 ----------------

    @Override
    public final List<RemoteFile> rpcListFiles(String path) throws Exception {
        pathGuard.checkReadable(path);
        return listFiles(path);
    }

    @Override
    public final boolean rpcDeleteFile(String path) throws Exception {
        pathGuard.checkDelete(path);
        return deleteLocalFile(path);
    }

    @Override
    public final boolean rpcMkdir(String parent, String child) throws Exception {
        pathGuard.checkWritable(new File(parent, child).getPath());
        return mkdir(parent, child);
    }

    @Override
    public boolean rpcIsBusy() {
        return transferBusy;
    }

    @Override
    public String rpcReceiveFiles() throws Exception {
        return receiveFilesFromPeer(callBack != null ? callBack : SILENT_CALLBACK);
    }

    @Override
    public String rpcSendFiles(TransferRequest request) throws Exception {
        for (RemoteFile file : request.files) {
            pathGuard.checkReadable(file.getPath());
        }
        return sendFilesToPeer(request.files, request.localDir, request.remoteDir,
                callBack != null ? callBack : SILENT_CALLBACK);
    }

    @Override
    public byte[] rpcQueryInfo() throws Exception {
        return RpcCodec.encodeQueryInfo(Directory.getCurrentFileSystem(), homeDir);
    }

    @Override
    public void rpcOnPeerClosed(String reason) {
        isRun = false;
        if (callBack != null) {
            callBack.onPeerClosed(reason);
        }
    }

}
