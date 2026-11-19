package top.weixiansen574.hybridfilexfer.core.rpc;

import java.util.List;

import top.weixiansen574.hybridfilexfer.core.bean.RemoteFile;

/**
 * 301 模式下对端请求在本端执行的文件操作。
 * 由 HFXClient（PC/Android 客户端）与 HFXServer（Android 服务端）分别实现。
 */
public interface RpcFileOps {
    List<RemoteFile> rpcListFiles(String path) throws Exception;

    boolean rpcDeleteFile(String path) throws Exception;

    boolean rpcMkdir(String parent, String child) throws Exception;

    /** 对端关闭或协议错误时的回调，实现方负责结束本端会话。 */
    void rpcOnPeerClosed(String reason);

    /** 本端是否正在执行传输任务，用于对并发控制请求返回 ERR_BUSY。 */
    default boolean rpcIsBusy() {
        return false;
    }

    /**
     * 对端把文件推给本端。返回 null 表示成功，否则返回错误信息。
     */
    default String rpcReceiveFiles() throws Exception {
        throw new UnsupportedOperationException("本端不支持接收文件");
    }

    /**
     * 对端要求本端把文件发过去。返回 null 表示成功，否则返回错误信息。
     */
    default String rpcSendFiles(TransferRequest request) throws Exception {
        throw new UnsupportedOperationException("本端不支持发送文件");
    }

    /** 返回编码后的 QUERY_INFO 应答体。 */
    default byte[] rpcQueryInfo() throws Exception {
        throw new UnsupportedOperationException("本端不支持查询信息");
    }
}
