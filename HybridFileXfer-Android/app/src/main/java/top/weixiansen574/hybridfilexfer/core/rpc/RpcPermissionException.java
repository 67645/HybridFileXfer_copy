package top.weixiansen574.hybridfilexfer.core.rpc;

/** 手机端未允许电脑主动访问时抛出，对端收到 ERR_PERMISSION。 */
public class RpcPermissionException extends Exception {
    public RpcPermissionException(String message) {
        super(message);
    }
}
