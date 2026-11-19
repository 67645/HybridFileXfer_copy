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
}
