package top.weixiansen574.hybridfilexfer.core.rpc;

import java.io.IOException;

public class RpcException extends IOException {
    public final byte status;

    public RpcException(byte status, String message) {
        super(message);
        this.status = status;
    }
}
