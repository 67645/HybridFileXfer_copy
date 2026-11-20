package top.weixiansen574.hybridfilexfer;

/** 手机端记住的一台电脑。status 为 Config.PC_AUTH_ALLOW / PC_AUTH_DENY。 */
public class PcAuthEntry {
    public final String id;
    public final String name;
    public final int status;
    public final long lastTime;

    public PcAuthEntry(String id, String name, int status, long lastTime) {
        this.id = id;
        this.name = name;
        this.status = status;
        this.lastTime = lastTime;
    }
}
