package github.linsmod.sharetopc.discovery;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import github.linsmod.sharetopc.transfer.TransferManager;

/** 发现的设备（电脑端）。 */
public class DiscoveredDevice {
    public final String name;
    public final String host;
    public final int port;
    /** 用户备注名（可选），由 SettingsStore 填充。 */
    public String alias;
    /** 设备程序版本（由 mDNS TXT 或 HELLO 握手获取）。 */
    public String version;
    /** 设备协议版本号（由 mDNS TXT 或 HELLO 握手获取）。 */
    public String protoVersion;
    /** 设备持久化唯一 ID（由 mDNS TXT 或 HELLO ACK 获取）。 */
    public String deviceId;
    /** 是否在线（当前扫描中发现）。 */
    public boolean isOnline;
    /** 该设备最近一次发送的 jobId。 */
    public String latestJobId;
    /** 当前传输进度，由 ViewModel 在刷新设备列表时填充。 */
    @Nullable
    public TransferManager.SendJob latestJob = null;

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
