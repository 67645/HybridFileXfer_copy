package top.weixiansen574.hybridfilexfer.droidcore;

import android.os.ParcelFileDescriptor;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.concurrent.LinkedBlockingDeque;

import top.weixiansen574.hybridfilexfer.aidl.IIOService;
import top.weixiansen574.hybridfilexfer.core.WriteFileCall;
import top.weixiansen574.hybridfilexfer.core.security.PathGuard;

public class DroidWriteFileCall extends WriteFileCall {
    private final IIOService ioService;
    private final PathGuard guard;
    private ParcelFileDescriptor pfd;
    private FileOutputStream fileOutputStream;
    private FileChannel channel;
    public DroidWriteFileCall(LinkedBlockingDeque<ByteBuffer> buffers, int dequeCount, IIOService ioService,
                              PathGuard guard) {
        super(buffers, dequeCount);
        this.ioService = ioService;
        this.guard = guard;
    }

    private void check(String path) throws Exception {
        if (guard != null) {
            guard.checkWritable(path);
        }
    }

    @Override
    protected void createParentDirIfNotExists(String path) throws Exception {
        check(path);
        String exception = ioService.createParentDirIfNotExists(path);
        if (exception != null){
            throw new IOException(exception);
        }
    }

    @Override
    protected void tryMkdirs(String path) throws Exception {
        check(path);
        String exception = ioService.tryMkdirs(path);
        if (exception != null){
            throw new IOException(exception);
        }
    }

    @Override
    protected FileChannel createAndOpenFile(String path, long length) throws Exception {
        check(path);
        pfd = ioService.createAndOpenWriteableFile(path, length);
        fileOutputStream = new FileOutputStream(pfd.getFileDescriptor());
        channel = fileOutputStream.getChannel();
        return channel;
    }

    @Override
    protected void closeFile() throws Exception {
        channel.close();
        fileOutputStream.close();
        pfd.close();
    }

    @Override
    protected boolean setFileLastModified(String path, long time) throws Exception {
        check(path);
        return ioService.setFileLastModified(path,time);
    }
}
