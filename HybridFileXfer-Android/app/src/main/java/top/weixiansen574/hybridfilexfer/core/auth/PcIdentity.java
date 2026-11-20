package top.weixiansen574.hybridfilexfer.core.auth;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.util.Properties;
import java.util.UUID;

/**
 * 电脑侧持久身份：随机 UUID + 主机名。
 *
 * <p>UUID 只生成一次，保存在用户目录下；手机端按 UUID 记住授权，
 * 主机名仅用于弹窗展示。文件不可写时退回到临时目录，至少保证同一次运行内稳定。</p>
 */
public final class PcIdentity {
    private static final String FILE_NAME = "pc-identity.properties";
    private static volatile PcIdentity instance;

    private final String id;
    private final String name;

    private PcIdentity(String id, String name) {
        this.id = id;
        this.name = name;
    }

    public static PcIdentity get() {
        PcIdentity local = instance;
        if (local == null) {
            synchronized (PcIdentity.class) {
                local = instance;
                if (local == null) {
                    local = load();
                    instance = local;
                }
            }
        }
        return local;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    private static PcIdentity load() {
        String hostName = resolveHostName();
        for (File file : candidateFiles()) {
            Properties properties = read(file);
            String id = properties.getProperty("id");
            if (id != null && !id.trim().isEmpty()) {
                String savedName = properties.getProperty("name");
                return new PcIdentity(id.trim(),
                        savedName == null || savedName.trim().isEmpty() ? hostName : savedName);
            }
            String newId = UUID.randomUUID().toString();
            Properties created = new Properties();
            created.setProperty("id", newId);
            created.setProperty("name", hostName);
            if (write(file, created)) {
                return new PcIdentity(newId, hostName);
            }
        }
        // 所有位置都不可写：使用会话内临时身份，至少不会与其它电脑混淆
        return new PcIdentity(UUID.randomUUID().toString(), hostName);
    }

    private static File[] candidateFiles() {
        String home = System.getProperty("user.home");
        String tmp = System.getProperty("java.io.tmpdir");
        File homeDir = home == null || home.trim().isEmpty()
                ? null : new File(new File(home), ".hybridfilexfer");
        File tmpDir = tmp == null || tmp.trim().isEmpty() ? null : new File(tmp);
        return new File[]{
                homeDir == null ? null : new File(homeDir, FILE_NAME),
                tmpDir == null ? null : new File(tmpDir, "hfx-" + FILE_NAME)
        };
    }

    private static Properties read(File file) {
        Properties properties = new Properties();
        if (file == null || !file.isFile()) {
            return properties;
        }
        try (FileInputStream in = new FileInputStream(file)) {
            properties.load(in);
        } catch (IOException ignored) {
        }
        return properties;
    }

    private static boolean write(File file, Properties properties) {
        if (file == null) {
            return false;
        }
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                return false;
            }
            try (FileOutputStream out = new FileOutputStream(file)) {
                properties.store(out, "HybridFileXfer PC identity");
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static String resolveHostName() {
        try {
            String host = InetAddress.getLocalHost().getHostName();
            if (host != null && !host.trim().isEmpty()) {
                return trimTo(host.trim(), 64);
            }
        } catch (Exception ignored) {
        }
        String user = System.getProperty("user.name");
        if (user != null && !user.trim().isEmpty()) {
            return trimTo(user.trim() + " 的电脑", 64);
        }
        return "未知电脑";
    }

    private static String trimTo(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}
