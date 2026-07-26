package github.linsmod.sharetopc.discovery;

import androidx.annotation.NonNull;

/** 发现的设备（电脑端）。 */
public class DiscoveredDevice {

    public final String name;
    public final String host;
    public final int port;
    /** 用户备注名（可选），由 SettingsStore 填充。 */
    public String alias;
    /** 是否在线（当前扫描中发现）。 */
    public boolean isOnline;
    /** 最近一次传输记录 ID，用于启动时恢复 TransferItemView。 */
    public String lastTransferId;

    public DiscoveredDevice(String name, String host, int port) {
        this(name, host, port, true);
    }

    public DiscoveredDevice(String name, String host, int port, boolean isOnline) {
        this.name = name;
        this.host = host;
        this.port = port;
        this.isOnline = isOnline;
    }

    public String key() {
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
