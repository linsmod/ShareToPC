package github.linsmod.sharetopc.ui;

import androidx.annotation.NonNull;

import java.util.List;

import github.linsmod.sharetopc.data.SendEntry;

/**
 * 传输任务分组（按 jobId 聚合），用于任务页树状展示。
 */
public class TaskGroup {
    public final String jobId;
    public final String deviceName;
    public final String host;
    public final int port;
    public final long timeMillis;
    @NonNull
    public final List<SendEntry> entries;
    public boolean expanded = true;

    /** 预计算的聚合进度。 */
    public long totalBytes;
    public long sentBytes;
    public int totalEntries;
    public int completedEntries;

    /** 分组标题栏是否显示设备信息（从设备跳转时不需要，因为弹窗标题已有）。 */
    public boolean showDeviceInfo = true;

    public TaskGroup(String jobId, String deviceName, String host, int port, long timeMillis,
                     @NonNull List<SendEntry> entries) {
        this.jobId = jobId;
        this.deviceName = deviceName;
        this.host = host;
        this.port = port;
        this.timeMillis = timeMillis;
        this.entries = entries;
        recomputeProgress();
    }

    /** 根据子项状态重新计算聚合进度。 */
    public void recomputeProgress() {
        totalBytes = 0;
        sentBytes = 0;
        totalEntries = entries.size();
        completedEntries = 0;
        for (SendEntry r : entries) {
            totalBytes += r.totalBytes > 0 ? r.totalBytes : r.size;
            sentBytes += r.sentBytes;
            if (r.status == SendEntry.STATUS_IDLE
                    && r.idleReason == SendEntry.IDLE_REASON_SUCCESS) {
                completedEntries++;
            }
        }
    }

    /** 是否有正在传输的子项。 */
    public boolean hasActive() {
        for (SendEntry r : entries) {
            if (r.status == SendEntry.STATUS_SENDING) return true;
        }
        return false;
    }

    /** 是否全部完成。 */
    public boolean isAllDone() {
        return completedEntries >= totalEntries && totalEntries > 0;
    }

    public int percent() {
        return totalBytes > 0 ? (int) (sentBytes * 100 / totalBytes) : 0;
    }

    public String deviceAddress() {
        if (host == null || host.isEmpty()) return "";
        return host + ":" + port;
    }

    /** 该分组是否为"从 PC 下载"任务（发送任务返回 false）。 */
    public boolean isPull() {
        for (SendEntry r : entries) {
            if (r.isPull()) return true;
        }
        return false;
    }
}
