package top.weixiansen574.hybridfilexfer.core.rpc;

/**
 * 协议 301（双向帧）的常量定义。
 * 300 为旧版单向协议，301 为双向 RPC 协议。
 */
public final class RpcProtocol {
    public static final int VERSION_LEGACY = 300;
    public static final int VERSION_RPC = 301;

    public static final short CMD_SHUTDOWN = 0;
    public static final short CMD_LIST_FILES = 1;
    public static final short CMD_DELETE_FILE = 2;
    public static final short CMD_MKDIR = 3;
    public static final short CMD_REQUEST_RECEIVE = 10;
    public static final short CMD_REQUEST_SEND = 11;
    /** 301：请求方把本地文件推给对端，对端负责接收。 */
    public static final short CMD_RECEIVE_FILES = CMD_REQUEST_RECEIVE;
    /** 301：请求方要求对端把文件发过来，请求方负责接收。 */
    public static final short CMD_SEND_FILES = CMD_REQUEST_SEND;
    public static final short CMD_QUERY_INFO = 100;
    public static final short CMD_CANCEL_JOB = 120;
    /** 301 应用层心跳，收到后直接回 OK，不进入串行命令队列。 */
    public static final short CMD_PING = 121;

    /** 帧类型：应答（seq 与被应答的请求一致）。 */
    public static final short CMD_RESPONSE = -2;

    /** 301 握手授权：客户端在版本匹配后、网卡协商前发送。 */
    public static final int AUTH_MAGIC = 0x48465841; // "HFXA"
    public static final int AUTH_RESP_MAGIC = 0x48465852; // "HFXR"
    public static final byte AUTH_OK = 0;
    public static final byte AUTH_DENIED = 1;
    /** 服务端等待旧客户端授权帧的时间；超时按旧 301 客户端处理，跳过授权。 */
    public static final int AUTH_LEGACY_GRACE_MS = 2000;

    public static final byte OK = 0;
    public static final byte ERR_UNKNOWN_COMMAND = 1;
    public static final byte ERR_UNSUPPORTED = 2;
    public static final byte ERR_IO = 3;
    public static final byte ERR_BUSY = 4;
    public static final byte ERR_PERMISSION = 5;

    public static final int MAX_FRAME = 64 * 1024 * 1024;
    public static final long DEFAULT_TIMEOUT_MS = 30000L;
    /** 传输任务的应答等待时间，0 表示不超时。 */
    public static final long TRANSFER_TIMEOUT_MS = 0L;
    /** 301 心跳间隔与无入站数据的超时阈值。 */
    public static final long HEARTBEAT_INTERVAL_MS = 5000L;
    public static final long HEARTBEAT_TIMEOUT_MS = 20000L;

    private RpcProtocol() {
    }
}
