package top.weixiansen574.hybridfilexfer.gui;

import java.io.File;
import java.util.prefs.Preferences;

/** GUI 配置：端口、接收目录、手动指定的 adb 路径。 */
public class GuiConfig {
    private static final Preferences PREFERENCES = Preferences.userRoot().node("top/weixiansen574/hybridfilexfer");

    public static int getPort() {
        return PREFERENCES.getInt("port", 5740);
    }

    public static void setPort(int port) {
        PREFERENCES.putInt("port", port);
    }

    /** 接收目录必填，默认 ${user.home}/HybridFileXfer，首次使用自动创建。 */
    public static String getReceiveDir() {
        String dir = PREFERENCES.get("receive_dir", null);
        if (dir == null || dir.trim().isEmpty()) {
            dir = new File(System.getProperty("user.home"), "HybridFileXfer").getAbsolutePath();
        }
        return dir;
    }

    public static void setReceiveDir(String dir) {
        PREFERENCES.put("receive_dir", dir);
    }

    /** 主题模式：system / light / dark。 */
    public static String getTheme() {
        return PREFERENCES.get("theme", ThemeManager.SYSTEM);
    }

    public static void setTheme(String theme) {
        PREFERENCES.put("theme", theme);
    }

    public static String getAdbPath() {
        return PREFERENCES.get("adb_path", null);
    }

    public static void setAdbPath(String path) {
        if (path == null || path.trim().isEmpty()) {
            PREFERENCES.remove("adb_path");
        } else {
            PREFERENCES.put("adb_path", path);
        }
    }
}
