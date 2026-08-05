package github.linsmod.sharetopc.discovery;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import github.linsmod.sharetopc.data.DeviceStore;
import github.linsmod.sharetopc.data.SettingsStore;
import github.linsmod.sharetopc.util.LogX;

/** 封装 NsdManager 的服务发现/解析/停止。按 deviceId 去重（host:port 变化视为同一台），deviceId 缺失时退化为 host:port。 */
public class NsdDiscoveryManager {

    public static final String SERVICE_TYPE = "_mytransfer._tcp.";

    public interface Listener {
        void onDevicesChanged(List<DiscoveredDevice> devices);

        /** 扫描状态变化（开始/结束）。默认空实现，便于 lambda 使用。 */
        default void onScanStateChanged(boolean scanning) {
        }
    }

    private final NsdManager nsdManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Map<String, DiscoveredDevice> devices = new LinkedHashMap<>();
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final SettingsStore settingsStore;
    private final DeviceStore deviceStore;

    /** 解析串行化：NsdManager 同时只能处理一个 resolveService。 */
    private final List<NsdServiceInfo> resolveQueue = new ArrayList<>();
    private boolean resolving = false;

    private NsdManager.DiscoveryListener discoveryListener;
    private boolean discovering = false;

    public NsdDiscoveryManager(Context context, DeviceStore deviceStore) {
        nsdManager = (NsdManager) context.getSystemService(Context.NSD_SERVICE);
        settingsStore = new SettingsStore(context);
        this.deviceStore = deviceStore;
        loadKnownDevices();
    }

    public void addListener(Listener listener) {
        listeners.add(listener);
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    /** 从缓存中移除设备（下次 mDNS 扫描/发现时仍会重新出现）。 */
    public synchronized void removeDevice(String host, int port) {
        devices.remove(host + ":" + port);
    }

    /** 手动添加设备到缓存并持久化到数据库。按 deviceId 去重，host:port 为兜底。 */
    public synchronized void addDevice(DiscoveredDevice device) {
        DiscoveredDevice merged = mergeIncoming(device);
        deviceStore.saveDevice(merged);
        notifyChanged();
    }

    /**
     * 将传入设备合并入内存缓存。
     * 优先按 deviceId 查找已存在设备（host:port 变化也视为同一台），其次按 host:port 兜底。
     * 若已存在：保留旧设备的传输状态（latestJobId/latestJob），合并身份信息，
     * 若地址变化则换 key 重新放入。返回合并后的对象。
     */
    private synchronized DiscoveredDevice mergeIncoming(DiscoveredDevice incoming) {
        DiscoveredDevice existing = findByDeviceId(incoming.deviceId);
        if (existing == null) {
            existing = devices.get(incoming.address());
        }

        if (existing == null) {
            devices.put(incoming.address(), incoming);
            return incoming;
        }

        boolean mergedOnline = existing.isOnline || incoming.isOnline;
        DiscoveredDevice merged = new DiscoveredDevice(
                incoming.name != null ? incoming.name : existing.name,
                incoming.host,
                incoming.port,
                mergedOnline
        );
        merged.version = incoming.version != null ? incoming.version : existing.version;
        merged.protoVersion = incoming.protoVersion != null ? incoming.protoVersion : existing.protoVersion;
        merged.deviceId = incoming.deviceId != null ? incoming.deviceId : existing.deviceId;
        merged.latestJobId = existing.latestJobId;
        merged.latestJob = existing.latestJob;

        // 地址变化（同 deviceId 但 host/port 不同）时，移除旧 key 重新放入
        if (!existing.address().equals(merged.address())) {
            devices.remove(existing.address());
        }
        devices.put(merged.address(), merged);
        return merged;
    }

    /** 按 deviceId 在内存缓存中查找已存在设备。 */
    private synchronized DiscoveredDevice findByDeviceId(String deviceId) {
        if (deviceId == null) return null;
        for (DiscoveredDevice d : devices.values()) {
            if (deviceId.equals(d.deviceId)) {
                return d;
            }
        }
        return null;
    }

    public synchronized List<DiscoveredDevice> getDevices() {
        List<DiscoveredDevice> list = new ArrayList<>(devices.values());
        for (DiscoveredDevice d : list) {
            d.alias = settingsStore.getDeviceAlias(d.host, d.port);
        }
        // 在线设备在前，同组内按名称排序
        Collections.sort(list, (a, b) -> {
            if (a.isOnline != b.isOnline) {
                return a.isOnline ? -1 : 1;
            }
            return a.displayName().compareToIgnoreCase(b.displayName());
        });
        return list;
    }

    /** 加载已知设备到内存（离线状态），启动时调用。按 deviceId 去重，host:port 兜底。 */
    private synchronized void loadKnownDevices() {
        for (DiscoveredDevice d : deviceStore.loadAll()) {
            if (d.deviceId != null) {
                // 同 deviceId 已存在则跳过（DB v2 升级已清理，但防御性检查）
                if (findByDeviceId(d.deviceId) != null) continue;
            } else if (devices.containsKey(d.address())) {
                continue;
            }
            devices.put(d.address(), d);
        }
    }

    public boolean isDiscovering() {
        return discovering;
    }

    /** 开始持续扫描（前台长连接，由 App 层生命周期管理启停）。 */
    public synchronized void startDiscovery() {
        if (discovering) {
            return;
        }
        discovering = true;
        // 扫描开始时不重置设备在线状态。
        // 已连接的设备由心跳保持在线，扫描不应破坏其状态；
        // 离线判定交由心跳超时 markOffline() 和 mDNS onServiceLost() 处理。
        discoveryListener = new NsdManager.DiscoveryListener() {
            @Override
            public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                LogX.e("NSD start failed: " + errorCode);
                discovering = false;
                notifyScanState(false);
            }

            @Override
            public void onStopDiscoveryFailed(String serviceType, int errorCode) {
                LogX.e("NSD stop failed: " + errorCode);
            }

            @Override
            public void onDiscoveryStarted(String serviceType) {
                LogX.d("NSD discovery started");
            }

            @Override
            public void onDiscoveryStopped(String serviceType) {
                LogX.d("NSD discovery stopped");
            }

            @Override
            public void onServiceFound(NsdServiceInfo serviceInfo) {
                LogX.d("NSD found: " + serviceInfo.getServiceName());
                enqueueResolve(serviceInfo);
            }

            @Override
            public void onServiceLost(NsdServiceInfo serviceInfo) {
                removeByName(serviceInfo.getServiceName());
            }
        };
        try {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener);
            notifyScanState(true);
        } catch (Exception e) {
            discovering = false;
            LogX.e("discoverServices error", e);
            notifyScanState(false);
        }
    }

    public synchronized void stopDiscovery() {
        if (!discovering) {
            return;
        }
        discovering = false;
        try {
            nsdManager.stopServiceDiscovery(discoveryListener);
        } catch (Exception e) {
            LogX.e("stopServiceDiscovery error", e);
        }
        notifyScanState(false);
    }

    private void notifyScanState(boolean scanning) {
        mainHandler.post(() -> {
            for (Listener l : listeners) {
                l.onScanStateChanged(scanning);
            }
        });
    }

    private synchronized void enqueueResolve(NsdServiceInfo info) {
        resolveQueue.add(info);
        if (!resolving) {
            resolveNext();
        }
    }

    private synchronized void resolveNext() {
        if (resolveQueue.isEmpty()) {
            resolving = false;
            return;
        }
        resolving = true;
        NsdServiceInfo info = resolveQueue.remove(0);
        try {
            nsdManager.resolveService(info, new NsdManager.ResolveListener() {
                @Override
                public void onResolveFailed(NsdServiceInfo serviceInfo, int errorCode) {
                    LogX.e("resolve failed: " + errorCode);
                    resolveNext();
                }

                @Override
                public void onServiceResolved(NsdServiceInfo serviceInfo) {
                    String host = serviceInfo.getHost() != null
                            ? serviceInfo.getHost().getHostAddress() : null;
                    if (host != null) {
                        DiscoveredDevice device = new DiscoveredDevice(
                                serviceInfo.getServiceName(), host, serviceInfo.getPort(), true);
                        // 从 mDNS TXT 记录中解析 version、protoVersion、deviceId
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                            Map<String, byte[]> attrs = serviceInfo.getAttributes();
                            if (attrs != null) {
                                byte[] verBytes = attrs.get("version");
                                if (verBytes != null) {
                                    device.version = new String(verBytes, java.nio.charset.StandardCharsets.UTF_8);
                                }
                                byte[] protoBytes = attrs.get("protoVersion");
                                if (protoBytes != null) {
                                    device.protoVersion = new String(protoBytes, java.nio.charset.StandardCharsets.UTF_8);
                                }
                                byte[] didBytes = attrs.get("device_id");
                                if (didBytes != null) {
                                    device.deviceId = new String(didBytes, java.nio.charset.StandardCharsets.UTF_8);
                                }
                            }
                        }
                        DiscoveredDevice toSave;
                        synchronized (NsdDiscoveryManager.this) {
                            // mergeIncoming 会按 deviceId 合并：地址变化时换 key 重新放入，
                            // 保留 existing 的传输状态（latestJobId/latestJob），isOnline 取 OR。
                            // mDNS 解析出的 device.isOnline=true，故合并后设备一定在线。
                            toSave = mergeIncoming(device);
                        }
                        deviceStore.saveDevice(toSave);
                        notifyChanged();
                    }
                    resolveNext();
                }
            });
        } catch (Exception e) {
            LogX.e("resolveService error", e);
            resolveNext();
        }
    }

    /**
     * HELLO 握手完成时更新 PC 端身份信息（版本、协议版本、设备 ID）。
     * 走 mergeIncoming 路径，确保 deviceId 与已有设备冲突时自动合并（host:port 变化也视为同一台）。
     */
    public void updateDeviceInfo(String host, int port, String version, int protoVersion, String deviceId) {
        DiscoveredDevice current;
        synchronized (this) {
            current = devices.get(host + ":" + port);
        }
        String name = current != null ? current.name : "PC";
        boolean online = current != null && current.isOnline;
        DiscoveredDevice incoming = new DiscoveredDevice(name, host, port, online);
        incoming.version = version;
        incoming.protoVersion = String.valueOf(protoVersion);
        incoming.deviceId = deviceId;

        DiscoveredDevice merged;
        synchronized (this) {
            merged = mergeIncoming(incoming);
        }
        deviceStore.saveDevice(merged);
        notifyChanged();
    }

    /** 外部（心跳）标记设备在线，持久化到数据库并通知 UI。 */
    public void markOnline(String host, int port) {
        DiscoveredDevice device;
        String key = host + ":" + port;
        synchronized (this) {
            device = devices.get(key);
            if (device != null && !device.isOnline) {
                device.isOnline = true;
            } else {
                return; // 未变化，不通知
            }
        }
        deviceStore.saveDevice(device);
        notifyChanged();
    }

    /** 外部（心跳）标记设备离线，持久化到数据库并通知 UI。 */
    public void markOffline(String host, int port) {
        DiscoveredDevice device;
        String key = host + ":" + port;
        synchronized (this) {
            device = devices.get(key);
            if (device != null && device.isOnline) {
                device.isOnline = false;
            } else {
                return; // 未变化，不通知
            }
        }
        deviceStore.saveDevice(device);
        notifyChanged();
    }

    private void removeByName(String serviceName) {
        boolean changed = false;
        synchronized (this) {
            for (DiscoveredDevice d : devices.values()) {
                if (d.name.equals(serviceName) && d.isOnline) {
                    d.isOnline = false;
                    changed = true;
                    break;
                }
            }
        }
        if (changed) {
            notifyChanged();
        }
    }

    private void notifyChanged() {
        List<DiscoveredDevice> snapshot = getDevices();
        mainHandler.post(() -> {
            for (Listener l : listeners) {
                l.onDevicesChanged(snapshot);
            }
        });
    }
}
