package top.weixiansen574.hybridfilexfer.core.callback;

import java.util.List;

import top.weixiansen574.hybridfilexfer.core.bean.TrafficInfo;

public interface TransferFileCallback {
    int ERROR_TYPE_EXCEPTION = 1;
    int ERROR_TYPE_INTERRUPT = 2;
    int ERROR_TYPE_READ_ERROR = 3;
    int ERROR_TYPE_WRITE_ERROR = 4;
    int ERROR_CODE_PERMISSION = 1;
    int ERROR_CODE_PATH = 2;
    int ERROR_CODE_NO_SPACE = 3;
    int ERROR_CODE_DISCONNECTED = 4;
    int ERROR_CODE_IO = 5;

    void onFileUploading(String iName, String path, long targetSize, long totalSize);

    void onFileDownloading(String iName, String path, long targetSize, long totalSize);

    void onSpeedInfo(List<TrafficInfo> trafficInfoList);

    void onChannelComplete(String iName, long traffic, long time);

    void onChannelError(String iName, int errorType, String message);//异常信息 or 断开

    void onReadFileError(String message);

    void onWriteFileError(String message);

    void onComplete(boolean isUpload,long traffic, long time);

    //传输通道有其中一个断开时
    void onIncomplete();

    /** 带错误码的传输失败；默认退回 onIncomplete，保持旧实现兼容。 */
    default void onTransferFailed(int code, String message) {
        onIncomplete();
    }

}
