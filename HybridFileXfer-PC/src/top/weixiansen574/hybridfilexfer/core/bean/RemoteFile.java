package top.weixiansen574.hybridfilexfer.core.bean;

import org.jetbrains.annotations.NotNull;

import java.io.File;

public class RemoteFile {
    protected final String name;
    protected final String path;
    protected final long lastModified;
    protected final long size;
    protected final boolean isDirectory;
    /** 覆盖确认里用户指定的新名称；null 表示沿用原名。 */
    protected final String transferName;
    //public final String name;
    //public final String path;
    //public final long lastModified;
    //public final long size;
    //public final boolean isDirectory;

    public RemoteFile(File file) {
        this(file, null);
    }

    public RemoteFile(File file, String transferName) {
        this.path = file.getPath();
        this.isDirectory = file.isDirectory();
        this.name = file.getName();
        this.lastModified = file.lastModified();
        this.size = file.length();
        this.transferName = transferName;
    }

    public RemoteFile(String name, String path, long lastModified, long size, boolean isDirectory){
        this(name, path, lastModified, size, isDirectory, null);
    }

    public RemoteFile(String name, String path, long lastModified, long size, boolean isDirectory,
                      String transferName){
        this.name = name;
        this.path = path;
        this.lastModified = lastModified;
        this.size = size;
        this.isDirectory = isDirectory;
        this.transferName = transferName;
    }

    public RemoteFile(RemoteFile file){
        this(file, file.transferName);
    }

    public RemoteFile(RemoteFile file, String transferName) {
        this.name = file.name;
        this.path = file.path;
        this.lastModified = file.lastModified;
        this.size = file.size;
        this.isDirectory = file.isDirectory;
        this.transferName = transferName;
    }


    @NotNull
    @Override
    public String toString() {
        return "RemoteFile{" +
                "path='" + path + '\'' +
                ", isDirectory=" + isDirectory +
                '}';
    }

    public String getPath() {
        return path;
    }

    public boolean isDirectory() {
        return isDirectory;
    }

    public String getName() {
        return name;
    }

    public long lastModified() {
        return lastModified;
    }

    public long getSize() {
        return size;
    }

    public String getTransferName() {
        return transferName;
    }

}
