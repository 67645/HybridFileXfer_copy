package top.weixiansen574.hybridfilexfer.core.security;

import java.io.IOException;

/** 远端请求的路径不在允许的根目录内（含 ..、绝对路径越界、符号链接逃逸）。 */
public class PathForbiddenException extends IOException {
    public PathForbiddenException(String message) {
        super(message);
    }
}
