package top.weixiansen574.hybridfilexfer.core.rpc;

public final class RpcResult {
    public final byte status;
    public final byte[] body;

    public RpcResult(byte status, byte[] body) {
        this.status = status;
        this.body = body == null ? new byte[0] : body;
    }
}
