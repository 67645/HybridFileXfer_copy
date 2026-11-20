package top.weixiansen574.hybridfilexfer.droidcore;

/**
 * 手机端首次遇到某台电脑时的授权确认入口。
 *
 * <p>由界面层实现。服务端线程调用 {@link #requestPcAuth} 后会阻塞等待用户选择，
 * 因此实现方必须把弹窗投递到主线程。</p>
 */
public interface PcAuthPrompter {
    Decision requestPcAuth(String pcId, String pcName);

    final class Decision {
        public final boolean allow;
        public final boolean remember;

        public Decision(boolean allow, boolean remember) {
            this.allow = allow;
            this.remember = remember;
        }

        public static Decision deny() {
            return new Decision(false, false);
        }
    }
}
