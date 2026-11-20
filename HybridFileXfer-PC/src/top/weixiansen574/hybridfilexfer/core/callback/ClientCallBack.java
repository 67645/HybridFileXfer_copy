package top.weixiansen574.hybridfilexfer.core.callback;

public interface ClientCallBack extends TransferFileCallback {
    void onReceiving();
    void onSending();
    void onExit();

    /** 对端关闭或心跳超时；默认退回 onExit。 */
    default void onPeerClosed(String reason) {
        onExit();
    }
}
