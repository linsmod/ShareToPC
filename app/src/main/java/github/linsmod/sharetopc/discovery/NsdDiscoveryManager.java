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

/** 封装 NsdManager 的服务发现/解析/停止。key = host:port 去重。 */
public class NsdDiscoveryManager {

    public static final String SERVICE_TYPE = "_mytransfer._tcp.";
    /** 默认扫描窗口（兜底值；实际取设置项）。 */
    public static final long DEFAULT_SCAN_TIMEOUT_MS = 30_000;

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
    private final Runnable timeoutStopTask = this::stopDiscovery;

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

    public synchronized List<DiscoveredDevice> getDevices() {
        List<DiscoveredDevice> list = new ArrayList<>(devices.values());
        for (DiscoveredDevice d : list) {
            d.alias = settingsStore.getDeviceAlias(d.host, d.port);
            // 加载最新 jobId（同会话内 TransferManager 写 DB 后这里就能读到）
            if (d.latestJobId == null) {
                d.latestJobId = deviceStore.getLatestJobId(d.host, d.port);
            }
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

    /** 加载已知设备到内存（离线状态），启动时调用。 */
    private synchronized void loadKnownDevices() {
        for (DiscoveredDevice d : deviceStore.loadAll()) {
            if (!devices.containsKey(d.address())) {
                devices.put(d.address(), d);
            }
        }
    }

    public boolean isDiscovering() {
        return discovering;
    }

    public synchronized void startDiscovery() {
        startDiscovery(0);
    }

    /** 开始扫描；timeoutMs > 0 时到时自动停止。 */
    public synchronized void startDiscovery(long timeoutMs) {
        if (discovering) {
            return;
        }
        discovering = true;
        // 扫描前将所有设备标记为离线（内部重置，不通知 UI）
        // 在线/离线由 mDNS 解析 + 心跳管理，扫描开始时的全量重置不应触发 UI 刷新
        // 否则会覆盖心跳 markOnline 的结果，导致绿点不更新
        for (DiscoveredDevice d : devices.values()) {
            d.isOnline = false;
        }
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
            if (timeoutMs > 0) {
                mainHandler.postDelayed(timeoutStopTask, timeoutMs);
            }
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
        mainHandler.removeCallbacks(timeoutStopTask);
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
                        synchronized (NsdDiscoveryManager.this) {
                            // 如果已存在同 key 的离线设备，替换为在线设备（保留 alias）
                            DiscoveredDevice existing = devices.get(device.address());
                            if (existing != null) {
                                existing.isOnline = true;
                            } else {
                                devices.put(device.address(), device);
                            }
                        }
                        deviceStore.saveDevice(device);
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

    /** 外部（心跳）标记设备在线，仅当状态变化时才通知 UI。 */
    public void markOnline(String host, int port) {
        String key = host + ":" + port;
        synchronized (this) {
            DiscoveredDevice device = devices.get(key);
            if (device != null && !device.isOnline) {
                device.isOnline = true;
            } else {
                return; // 未变化，不通知
            }
        }
        notifyChanged();
    }

    /** 外部（心跳）标记设备离线，仅当状态变化时才通知 UI。 */
    public void markOffline(String host, int port) {
        String key = host + ":" + port;
        synchronized (this) {
            DiscoveredDevice device = devices.get(key);
            if (device != null && device.isOnline) {
                device.isOnline = false;
            } else {
                return; // 未变化，不通知
            }
        }
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
