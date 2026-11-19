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
    public static final short CMD_QUERY_INFO = 100;
    public static final short CMD_CANCEL_JOB = 120;

    /** 帧类型：应答（seq 与被应答的请求一致）。 */
    public static final short CMD_RESPONSE = -2;

    public static final byte OK = 0;
    public static final byte ERR_UNKNOWN_COMMAND = 1;
    public static final byte ERR_UNSUPPORTED = 2;
    public static final byte ERR_IO = 3;
    public static final byte ERR_BUSY = 4;
    public static final byte ERR_PERMISSION = 5;

    public static final int MAX_FRAME = 64 * 1024 * 1024;
    public static final long DEFAULT_TIMEOUT_MS = 30000L;

    private RpcProtocol() {
    }
}
