package github.linsmod.sharetopc.transfer;

/** 传输状态回调（均在主线程触发）。 */
public interface TransferListener {

    /** 队列整体状态变化。deviceKey = host:port，IDLE 时为 null。 */
    void onStateChanged(TransferManager.State state, String jobName, String deviceKey);

    /** 当前文件发送进度。deviceKey = host:port，无活动任务时为 null。 */
    void onProgress(String itemName, long sentBytes, long totalBytes, String deviceKey);

    /** 单个任务完成（成功或失败）。 */
    void onJobFinished(String jobId, boolean success, String errorMsg);

    /** 任务被用户取消。 */
    void onJobCancelled(String jobId, String jobName);

    /** 历史记录有更新。 */
    void onHistoryChanged();
}
