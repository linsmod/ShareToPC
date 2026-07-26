package github.linsmod.sharetopc.transfer;

/** 传输状态回调（均在主线程触发）。 */
public interface TransferListener {

    /** 队列整体状态变化。 */
    void onStateChanged(TransferManager.State state, String jobName);

    /** 当前文件发送进度。 */
    void onProgress(String itemName, long sentBytes, long totalBytes);

    /** 单个任务完成（成功或失败）。 */
    void onJobFinished(String jobId, boolean success, String errorMsg);

    /** 历史记录有更新。 */
    void onHistoryChanged();
}
