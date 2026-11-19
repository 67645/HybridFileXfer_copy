package top.weixiansen574.hybridfilexfer.gui;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** adb 定位、设备列举与端口转发。 */
public class AdbUtil {

    public static class AdbDevice {
        public final String serial;
        public final String state;
        public final String model;

        public AdbDevice(String serial, String state, String model) {
            this.serial = serial;
            this.state = state;
            this.model = model;
        }

        public boolean isReady() {
            return "device".equals(state);
        }

        public String stateText() {
            switch (state) {
                case "device":
                    return "已授权";
                case "unauthorized":
                    return "未授权（请在手机上允许 USB 调试）";
                case "offline":
                    return "离线";
                default:
                    return state;
            }
        }

        @Override
        public String toString() {
            return serial + "  " + stateText() + (model == null || model.isEmpty() ? "" : "  " + model);
        }
    }

    /**
     * 三级探测：PATH、程序所在目录、常见安装位置；都失败时返回 null。
     */
    public static String findAdb() {
        String manual = GuiConfig.getAdbPath();
        if (manual != null && new File(manual).isFile()) {
            return manual;
        }
        // 1. PATH
        if (canRun("adb")) {
            return "adb";
        }
        // 2. 程序所在目录
        File programDir = getProgramDirectory();
        String[] names = isWindows() ? new String[]{"adb.exe", "adb"} : new String[]{"adb", "adb.exe"};
        if (programDir != null) {
            for (String name : names) {
                File file = new File(programDir, name);
                if (file.isFile()) {
                    return file.getAbsolutePath();
                }
            }
        }
        // 3. 常见安装位置
        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData != null) {
            File file = new File(localAppData, "Android/Sdk/platform-tools/adb.exe");
            if (file.isFile()) {
                return file.getAbsolutePath();
            }
        }
        return null;
    }

    /** 列出设备；unauthorized/offline 不做过滤，交给界面提示。 */
    public static List<AdbDevice> listDevices(String adbPath) throws Exception {
        List<AdbDevice> devices = new ArrayList<>();
        Process process = new ProcessBuilder(adbPath, "devices", "-l").redirectErrorStream(true).start();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            boolean first = true;
            while ((line = reader.readLine()) != null) {
                if (first) {
                    first = false;
                    continue;
                }
                line = line.trim();
                if (line.isEmpty() || line.startsWith("*")) {
                    continue;
                }
                String[] parts = line.split("\\s+");
                if (parts.length < 2) {
                    continue;
                }
                String model = "";
                for (int i = 2; i < parts.length; i++) {
                    if (parts[i].startsWith("model:")) {
                        model = parts[i].substring("model:".length()).replace('_', ' ');
                    }
                }
                devices.add(new AdbDevice(parts[0], parts[1], model));
            }
        }
        process.waitFor();
        return devices;
    }

    /** 执行端口转发，成功返回 null，失败返回 adb 的原始错误输出。 */
    public static String forward(String adbPath, String serial, int port) {
        try {
            List<String> command = new ArrayList<>();
            command.add(adbPath);
            if (serial != null && !serial.isEmpty()) {
                command.add("-s");
                command.add(serial);
            }
            command.add("forward");
            command.add("tcp:" + port);
            command.add("tcp:" + port);
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append('\n');
                }
            }
            int exit = process.waitFor();
            return exit == 0 ? null : output.toString().trim();
        } catch (Exception e) {
            return e.toString();
        }
    }

    public static File getProgramDirectory() {
        try {
            File location = new File(AdbUtil.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            if (location.isFile()) {
                return location.getParentFile();
            }
            return location;
        } catch (URISyntaxException | RuntimeException e) {
            return null;
        }
    }

    private static boolean canRun(String adb) {
        try {
            Process process = new ProcessBuilder(adb, "version").redirectErrorStream(true).start();
            process.waitFor();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
