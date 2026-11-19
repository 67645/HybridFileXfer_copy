package top.weixiansen574.hybridfilexfer.droidcore.callback;

import top.weixiansen574.async.BackstageTask;

public interface StartServerCallback extends BackstageTask.BaseEventHandler {
    void onBindFailed(int port);
    void onStatedServer();
    void onAccepted(String name);
    void onAcceptFailed(String name);
    void onPcOOM();
    void onMeOOM(int created, int localBufferCount);
    void onConnectSuccess();
    /** 会话结束（对端断开或握手失败），服务端已回到等待新连接的状态。 */
    void onDisconnected(String reason);
}
