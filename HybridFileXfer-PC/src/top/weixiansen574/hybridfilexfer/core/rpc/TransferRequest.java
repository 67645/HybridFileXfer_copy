package top.weixiansen574.hybridfilexfer.core.rpc;

import java.util.List;

import top.weixiansen574.hybridfilexfer.core.bean.Directory;
import top.weixiansen574.hybridfilexfer.core.bean.RemoteFile;

/**
 * 301 帧化的传输请求载荷，字段含义与 300 的 REQUEST_SEND 保持一致：
 * 请求方待传的文件列表、请求方的接收目录（对端视角的目标目录）、被请求方的源目录。
 */
public class TransferRequest {
    public final List<RemoteFile> files;
    /** 请求方的本地目录，也就是数据最终落到请求方的哪个目录。 */
    public final Directory localDir;
    /** 被请求方的源目录，用于把文件绝对路径映射成相对路径。 */
    public final Directory remoteDir;

    public TransferRequest(List<RemoteFile> files, Directory localDir, Directory remoteDir) {
        this.files = files;
        this.localDir = localDir;
        this.remoteDir = remoteDir;
    }
}
