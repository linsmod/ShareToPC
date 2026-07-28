package github.linsmod.sharetopc.discovery;

import androidx.annotation.NonNull;

/** 发现的设备（电脑端）。 */
public class DiscoveredDevice {

    /** 当前传输进度（ViewModel 填充，不做持久化）。 */
    public static class DeviceProgress {
        public static final int STATE_IDLE = 0;
        public static final int STATE_SENDING = 1;
        public static final int STATE_QUEUED = 2;
        public static final int STATE_PAUSED = 3;
        public static final int STATE_COMPLETED = 4;

        public int state = STATE_IDLE;
        /** 当前 jobId。 */
        public String jobId;
        /** job 总 entry 数。 */
        public int totalEntries;
        /** 已完成 entry 数。 */
        public int completedEntries;
        /** job 总字节数。 */
        public long totalBytes;
        /** job 已发送字节数。 */
        public long sentBytes;
        /** 当前正在传输的 entry 名称（文件名或文本预览）。 */
        public String currentEntryName;

        public boolean isActive() {
            return state == STATE_SENDING || state == STATE_QUEUED
                    || state == STATE_PAUSED || state == STATE_COMPLETED;
        }

        public int percent() {
            return totalBytes > 0 ? (int) (sentBytes * 100 / totalBytes) : 0;
        }

        public String summary() {
            if (state == STATE_SENDING) {
                return "传输中 " + completedEntries + "/" + totalEntries + " · " + percent() + "%";
            } else if (state == STATE_QUEUED) {
                return "排队中 " + totalEntries + " 项";
            } else if (state == STATE_PAUSED) {
                return "已暂停 " + completedEntries + "/" + totalEntries + " · " + percent() + "%";
            } else if (state == STATE_COMPLETED) {
                return "传输完成 " + completedEntries + "/" + totalEntries + " 项";
            }
            return "";
        }
    }

    public final String name;
    public final String host;
    public final int port;
    /** 用户备注名（可选），由 SettingsStore 填充。 */
    public String alias;
    /** 是否在线（当前扫描中发现）。 */
    public boolean isOnline;
    /** 该设备最近一次发送的 jobId。 */
    public String latestJobId;
    /** 当前传输进度，由 ViewModel 在刷新设备列表时填充。 */
    @NonNull
    public DeviceProgress currentProgress = new DeviceProgress();

    public DiscoveredDevice(String name, String host, int port) {
        this(name, host, port, true);
    }

    public DiscoveredDevice(String name, String host, int port, boolean isOnline) {
        this.name = name;
        this.host = host;
        this.port = port;
        this.isOnline = isOnline;
    }

    public String address() {
        return host + ":" + port;
    }

    /** 展示名：优先用户备注，其次 mDNS 原始名。 */
    public String displayName() {
        return alias != null && !alias.isEmpty() ? alias : name;
    }

    @NonNull
    @Override
    public String toString() {
        return displayName() + " (" + host + ":" + port + ")";
    }
}
