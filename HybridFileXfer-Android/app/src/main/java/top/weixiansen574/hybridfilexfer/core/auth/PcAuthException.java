package top.weixiansen574.hybridfilexfer.core.auth;

import java.io.IOException;

/** 手机端拒绝或未能确认电脑授权。message 是可直接展示给用户的原因。 */
public class PcAuthException extends IOException {
    public PcAuthException(String message) {
        super(message);
    }
}
