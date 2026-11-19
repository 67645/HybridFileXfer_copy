package top.weixiansen574.hybridfilexfer.core.rpc;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import top.weixiansen574.hybridfilexfer.core.bean.RemoteFile;

/**
 * 301 帧的载荷编解码。目录条目格式与 300 协议保持一致：
 * UTF name、UTF path、long lastModified、long size、boolean isDirectory。
 */
public final class RpcCodec {
    private RpcCodec() {
    }

    public static byte[] encodePath(String path) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bos);
        out.writeUTF(path);
        out.flush();
        return bos.toByteArray();
    }

    public static String decodePath(byte[] payload) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload));
        return in.readUTF();
    }

    public static byte[] encodeMkdir(String parent, String child) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bos);
        out.writeUTF(parent);
        out.writeUTF(child);
        out.flush();
        return bos.toByteArray();
    }

    public static String[] decodeMkdir(byte[] payload) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload));
        return new String[]{in.readUTF(), in.readUTF()};
    }

    public static byte[] encodeBool(boolean value) {
        return new byte[]{value ? (byte) 1 : (byte) 0};
    }

    public static boolean decodeBool(byte[] body) {
        return body != null && body.length > 0 && body[0] != 0;
    }

    public static byte[] encodeMessage(String message) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bos);
        out.writeUTF(message == null ? "" : message);
        out.flush();
        return bos.toByteArray();
    }

    public static String decodeMessage(byte[] body) {
        if (body == null || body.length == 0) {
            return "";
        }
        try {
            return new DataInputStream(new ByteArrayInputStream(body)).readUTF();
        } catch (IOException e) {
            return "";
        }
    }

    public static byte[] encodeFileList(List<RemoteFile> files) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bos);
        if (files == null) {
            out.writeInt(-1);
        } else {
            out.writeInt(files.size());
            for (RemoteFile file : files) {
                out.writeUTF(file.getName());
                out.writeUTF(file.getPath());
                out.writeLong(file.lastModified());
                out.writeLong(file.getSize());
                out.writeBoolean(file.isDirectory());
            }
        }
        out.flush();
        return bos.toByteArray();
    }

    public static List<RemoteFile> decodeFileList(byte[] body) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(body));
        int count = in.readInt();
        if (count < 0) {
            return null;
        }
        List<RemoteFile> files = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String name = in.readUTF();
            String path = in.readUTF();
            long lastModified = in.readLong();
            long size = in.readLong();
            boolean directory = in.readBoolean();
            files.add(new RemoteFile(name, path, lastModified, size, directory));
        }
        return files;
    }
}
