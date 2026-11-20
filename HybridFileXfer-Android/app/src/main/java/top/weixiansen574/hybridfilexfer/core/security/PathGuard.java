package top.weixiansen574.hybridfilexfer.core.security;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 远端可控路径的根目录约束。
 *
 * <p>使用 canonical path 判断是否位于允许的根目录内，因此会同时解析 {@code ..}
 * 与符号链接/目录联接。目标不存在时，先规范化其最近的存在父目录再拼接文件名，
 * 防止通过“尚未创建的符号链接父目录”逃逸。</p>
 */
public final class PathGuard {
    private final List<String> roots;
    private final boolean allowAll;

    public PathGuard(List<File> rootFiles, boolean allowAll) {
        this.allowAll = allowAll;
        List<String> canonicalRoots = new ArrayList<>();
        if (rootFiles != null) {
            for (File root : rootFiles) {
                if (root == null) {
                    continue;
                }
                try {
                    canonicalRoots.add(normalize(realPath(root)));
                } catch (IOException ignored) {
                }
            }
        }
        this.roots = Collections.unmodifiableList(canonicalRoots);
    }

    public static PathGuard allowAll() {
        return new PathGuard(Collections.emptyList(), true);
    }

    public boolean isAllowAll() {
        return allowAll;
    }

    public List<String> getRoots() {
        return roots;
    }

    public void checkReadable(String path) throws PathForbiddenException {
        check(path, false);
    }

    public void checkWritable(String path) throws PathForbiddenException {
        check(path, false);
    }

    public void checkDelete(String path) throws PathForbiddenException {
        check(path, true);
    }

    private void check(String path, boolean rejectRoot) throws PathForbiddenException {
        if (allowAll) {
            return;
        }
        if (path == null || path.trim().isEmpty()) {
            throw new PathForbiddenException("路径为空");
        }
        String canonical = canonicalTarget(path);
        for (String root : roots) {
            if (canonical.equals(root)) {
                if (rejectRoot) {
                    throw new PathForbiddenException("不能删除允许范围的根目录：" + path);
                }
                return;
            }
            if (canonical.startsWith(root + File.separator)) {
                return;
            }
        }
        throw new PathForbiddenException("路径不在允许范围内：" + path);
    }

    private static String canonicalTarget(String path) throws PathForbiddenException {
        File file = new File(path);
        rejectParentTraversal(file);
        try {
            File absolute = file.getAbsoluteFile();
            File existing = absolute;
            List<String> missing = new ArrayList<>();
            while (existing != null && !existing.exists()) {
                missing.add(0, existing.getName());
                existing = existing.getParentFile();
            }
            String base;
            if (existing == null) {
                base = absolute.getCanonicalPath();
            } else {
                // 父目录可能是指向根目录外的符号链接/目录联接，必须先解析真实路径
                base = realPath(existing);
                for (String name : missing) {
                    base = new File(base, name).getPath();
                }
            }
            return normalize(new File(base).getCanonicalPath());
        } catch (IOException e) {
            throw new PathForbiddenException("路径无效：" + path);
        }
    }

    private static void rejectParentTraversal(File file) throws PathForbiddenException {
        String normalized = file.getPath().replace('\\', '/');
        for (String part : normalized.split("/")) {
            if ("..".equals(part)) {
                throw new PathForbiddenException("路径不允许包含 ..：" + file.getPath());
            }
        }
    }

    /**
     * 解析真实路径。
     *
     * <p>Linux/Android 的 {@link File#getCanonicalPath()} 会解析符号链接；
     * Windows 的 canonical path 不解析目录联接，因此这里在 Windows 上优先使用
     * NIO 的 toRealPath（仅 PC 端运行，Android 走不到这个分支）。</p>
     */
    private static String realPath(File file) throws IOException {
        if (File.separatorChar == '\\') {
            String resolved = realPathNio(file);
            if (resolved != null) {
                return resolved;
            }
        }
        return file.getCanonicalPath();
    }

    private static String realPathNio(File file) {
        try {
            Object path = File.class.getMethod("toPath").invoke(file);
            Class<?> pathClass = Class.forName("java.nio.file.Path");
            Class<?> linkOptionArray = Class.forName("[Ljava.nio.file.LinkOption;");
            Object emptyOptions = Array.newInstance(Class.forName("java.nio.file.LinkOption"), 0);
            // 必须通过公共接口 Path 调用；WindowsPath 是非 public 实现类，直接 invoke 会 IllegalAccess
            Object real = pathClass.getMethod("toRealPath", linkOptionArray)
                    .invoke(path, emptyOptions);
            return real.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private static String normalize(String path) {
        if (File.separatorChar == '\\') {
            return path.toLowerCase(Locale.ROOT);
        }
        return path;
    }
}
