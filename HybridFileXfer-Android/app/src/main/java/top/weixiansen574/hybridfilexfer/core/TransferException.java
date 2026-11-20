package top.weixiansen574.hybridfilexfer.core;

import java.io.IOException;
import java.nio.channels.AsynchronousCloseException;
import java.nio.channels.ClosedChannelException;
import java.util.Locale;

import top.weixiansen574.hybridfilexfer.core.rpc.RpcPermissionException;
import top.weixiansen574.hybridfilexfer.core.security.PathForbiddenException;

/** 带分类的传输失败，便于界面给出“未授权/路径不可写/磁盘满/连接中断”等具体提示。 */
public class TransferException extends IOException {
    public static final int CODE_PERMISSION = 1;
    public static final int CODE_PATH = 2;
    public static final int CODE_NO_SPACE = 3;
    public static final int CODE_DISCONNECTED = 4;
    public static final int CODE_IO = 5;

    public final int code;

    private TransferException(int code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public static TransferException from(Throwable throwable) {
        Throwable cause = throwable;
        if (cause == null) {
            return new TransferException(CODE_IO, "未知错误", null);
        }
        String message = cause.getMessage() == null ? cause.toString() : cause.getMessage();
        int code = CODE_IO;
        for (Throwable current = cause; current != null; current = current.getCause()) {
            if (current instanceof PathForbiddenException) {
                code = CODE_PATH;
                message = current.getMessage();
                break;
            }
            if (current instanceof RpcPermissionException) {
                code = CODE_PERMISSION;
                message = current.getMessage();
                break;
            }
            if (current instanceof ClosedChannelException
                    || current instanceof AsynchronousCloseException) {
                code = CODE_DISCONNECTED;
                break;
            }
            String text = current.getMessage();
            if (text == null) {
                continue;
            }
            String lower = text.toLowerCase(Locale.ROOT);
            if (lower.contains("no space") || lower.contains("enospc")
                    || lower.contains("space left") || text.contains("磁盘空间")
                    || text.contains("空间不足")) {
                code = CODE_NO_SPACE;
                message = text;
                break;
            }
            if (lower.contains("permission denied") || lower.contains("eacces")
                    || lower.contains("access denied") || text.contains("没有权限")) {
                code = CODE_PERMISSION;
                message = text;
                break;
            }
            if (lower.contains("connection reset") || lower.contains("broken pipe")
                    || lower.contains("connection closed") || lower.contains("eof")
                    || text.contains("连接已关闭") || text.contains("连接中断")
                    || lower.contains("timeout") || text.contains("超时")) {
                code = CODE_DISCONNECTED;
                message = text;
                break;
            }
            if (current instanceof IOException) {
                message = text;
            }
        }
        return new TransferException(code, message, cause);
    }
}
