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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import github.linsmod.sharetopc.data.DeviceStore;
import github.linsmod.sharetopc.data.SettingsStore;
import github.linsmod.sharetopc.util.LogX;

/**
 * 封装 NsdManager 的服务发现/解析/停止。
 * 客户端唯一身份 = deviceId（同一 id 换 IP 视为同一台，地址被更新）；deviceId 为空视为未确认身份。
 * <p>
 * 并发模型：所有缓存变更均投递到单线程事件队列（FIFO）串行执行，
 * 每个事件原子地完成「mergeIncoming 合并 → 落库 → notifyChanged」，
 * 因此任何线程（mDNS 回调 / 心跳线程 / UI）发起的变更都只入队，不抢占锁、不阻塞传输。
 * 缓存读取通过 cacheLock 取快照，DB 写入不占用锁。
 */
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
    /** 保护 devices 映射的锁：合并/读取时短暂持有，落库在锁外进行。 */
    private final Object cacheLock = new Object();
    private final Map<String, DiscoveredDevice> devices = new LinkedHashMap<>();
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final SettingsStore settingsStore;
    private final DeviceStore deviceStore;

    /** 单线程事件队列：所有缓存变更在此线程串行处理。 */
    private final ExecutorService eventExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "nsd-events");
        t.setDaemon(true);
        return t;
    });

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

    /**
     * 从缓存中移除设备（下次 mDNS 扫描/发现时仍会重新出现）。
     * 投递到事件队列异步执行。
     */
    public void removeDevice(String host, int port) {
        eventExecutor.execute(() -> {
            synchronized (cacheLock) {
                devices.remove(host + ":" + port);
            }
            notifyChanged();
        });
    }

    /**
     * 手动添加设备到缓存并持久化。投递到事件队列异步执行。
     */
    public void addDevice(DiscoveredDevice device) {
        eventExecutor.execute(() -> {
            DiscoveredDevice merged;
            synchronized (cacheLock) {
                merged = mergeIncoming(device);
            }
            deviceStore.saveDevice(merged);
            notifyChanged();
        });
    }

    /**
     * 将传入设备合并入内存缓存。必须持有 cacheLock 调用（仅事件队列线程内）。
     * 客户端唯一身份 = deviceId：
     * - 传入 deviceId 非空：严格按 deviceId 合并，同一 id 地址变化视为同一台（IP 变化即更新）。
     * - 传入 deviceId 为空：视为「未确认身份的候选」，仅按地址暂存供心跳连接获取真实身份，
     *   不作为正式客户端展示/持久化。
     */
    private DiscoveredDevice mergeIncoming(DiscoveredDevice incoming) {
        if (incoming.deviceId == null || incoming.deviceId.isEmpty()) {
            // 未确认身份的候选客户端：按地址暂存，等待心跳 HELLO 补齐 deviceId
            DiscoveredDevice pending = devices.get(incoming.address());
            if (pending == null) {
                devices.put(incoming.address(), incoming);
                return incoming;
            }
            // 覆盖/刷新暂存条目，保留其传输状态（name 为 final，新建对象替换）
            DiscoveredDevice refreshed = new DiscoveredDevice(
                    incoming.name != null ? incoming.name : pending.name,
                    pending.host, pending.port,
                    pending.isOnline || incoming.isOnline
            );
            refreshed.version = incoming.version != null ? incoming.version : pending.version;
            refreshed.protoVersion = incoming.protoVersion != null ? incoming.protoVersion : pending.protoVersion;
            refreshed.deviceId = pending.deviceId;
            refreshed.latestJobId = pending.latestJobId;
            refreshed.latestJob = pending.latestJob;
            refreshed.addedTimeMillis = pending.addedTimeMillis;
            devices.put(refreshed.address(), refreshed);
            return refreshed;
        }

        DiscoveredDevice existing = findByDeviceId(incoming.deviceId);
        if (existing == null) {
            // 同地址已有未确认身份的暂存条目 → 直接补全身份（视为同一台）
            DiscoveredDevice slot = devices.get(incoming.address());
            if (slot != null && (slot.deviceId == null || slot.deviceId.isEmpty())) {
                DiscoveredDevice confirmed = new DiscoveredDevice(
                        slot.name != null ? slot.name : incoming.name,
                        incoming.host, incoming.port,
                        incoming.isOnline || slot.isOnline
                );
                confirmed.version = incoming.version != null ? incoming.version : slot.version;
                confirmed.protoVersion = incoming.protoVersion != null ? incoming.protoVersion : slot.protoVersion;
                confirmed.deviceId = incoming.deviceId;
                confirmed.latestJobId = slot.latestJobId;
                confirmed.latestJob = slot.latestJob;
                confirmed.addedTimeMillis = slot.addedTimeMillis;
                devices.put(confirmed.address(), confirmed);
                return confirmed;
            }
            devices.put(incoming.address(), incoming);
            return incoming;
        }

        // 同 deviceId 已存在 → 合并，地址随新位置更新（IP 变化视为同一台）
        boolean mergedOnline = existing.isOnline || incoming.isOnline;
        DiscoveredDevice merged = new DiscoveredDevice(
                incoming.name != null ? incoming.name : existing.name,
                incoming.host,
                incoming.port,
                mergedOnline
        );
        merged.version = incoming.version != null ? incoming.version : existing.version;
        merged.protoVersion = incoming.protoVersion != null ? incoming.protoVersion : existing.protoVersion;
        merged.deviceId = existing.deviceId;
        merged.latestJobId = existing.latestJobId;
        merged.latestJob = existing.latestJob;
        merged.addedTimeMillis = existing.addedTimeMillis;

        // 地址变化（同 deviceId 但 host/port 不同）时，移除旧 key 重新放入
        if (!existing.address().equals(merged.address())) {
            devices.remove(existing.address());
        }
        devices.put(merged.address(), merged);
        return merged;
    }

    /** 按 deviceId 在内存缓存中查找已存在设备（唯一身份标识）。必须持有 cacheLock 调用。 */
    private DiscoveredDevice findByDeviceId(String deviceId) {
        if (deviceId == null || deviceId.isEmpty()) return null;
        for (DiscoveredDevice d : devices.values()) {
            if (deviceId.equals(d.deviceId)) {
                return d;
            }
        }
        return null;
    }

    /** 展示给 UI 的客户端列表：仅包含已确认身份的（deviceId 非空）客户端，按 deviceId 唯一去重。 */
    public List<DiscoveredDevice> getDevices() {
        List<DiscoveredDevice> all;
        synchronized (cacheLock) {
            all = new ArrayList<>(devices.values());
        }
        Map<String, DiscoveredDevice> byDeviceId = new LinkedHashMap<>(); // 保留插入顺序
        for (DiscoveredDevice d : all) {
            if (d.deviceId == null || d.deviceId.isEmpty()) continue; // 未确认身份不作为客户端展示
            DiscoveredDevice prev = byDeviceId.get(d.deviceId);
            if (prev == null || (d.isOnline && !prev.isOnline)) {
                byDeviceId.put(d.deviceId, d);
            }
        }
        List<DiscoveredDevice> deduped = new ArrayList<>(byDeviceId.values());
        for (DiscoveredDevice d : deduped) {
            d.alias = settingsStore.getDeviceAlias(d.host, d.port);
        }
        // 在线设备在前，同组内按名称排序
        Collections.sort(deduped, (a, b) -> {
            if (a.isOnline != b.isOnline) {
                return a.isOnline ? -1 : 1;
            }
            return a.displayName().compareToIgnoreCase(b.displayName());
        });
        return deduped;
    }

    /** 全部缓存条目（含未确认身份的暂存条目），供心跳建立连接获取真实身份。 */
    public List<DiscoveredDevice> getAllCached() {
        synchronized (cacheLock) {
            return new ArrayList<>(devices.values());
        }
    }

    /** 加载已知设备到内存（离线状态），启动时调用。仅加载已确认身份的（deviceId 非空）客户端。 */
    private void loadKnownDevices() {
        synchronized (cacheLock) {
            for (DiscoveredDevice d : deviceStore.loadAll()) {
                if (d.deviceId == null || d.deviceId.isEmpty()) continue; // 历史脏数据/未确认身份不加载
                if (findByDeviceId(d.deviceId) != null) continue; // 同 deviceId 已存在则跳过
                devices.put(d.address(), d);
            }
        }
    }

    public synchronized boolean isDiscovering() {
        return discovering;
    }

    /**
     * 强制重启 NSD 扫描。删除设备后，Android 不会对仍在广播的同一服务再次回调
     * onServiceFound，必须重启扫描才能重新发现在线设备。
     */
    public void forceRescan() {
        mainHandler.post(() -> {
            if (discovering) {
                stopDiscovery();
                // 等 NSD 停止处理完成再启动，避免状态冲突
                mainHandler.postDelayed(this::startDiscovery, 200);
            } else {
                startDiscovery();
            }
        });
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
                markOfflineByName(serviceInfo.getServiceName());
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
                        final DiscoveredDevice resolved = device;
                        // 合并动作投递到事件队列，与其它变更串行处理
                        eventExecutor.execute(() -> {
                            DiscoveredDevice toSave;
                            synchronized (cacheLock) {
                                // mDNS 解析出的 resolved.isOnline=true，故合并后设备一定在线
                                toSave = mergeIncoming(resolved);
                            }
                            deviceStore.saveDevice(toSave);
                            notifyChanged();
                        });
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
     * 投递到事件队列，由 mergeIncoming 按 deviceId 合并（host:port 变化也视为同一台）。
     */
    public void updateDeviceInfo(String host, int port, String version, int protoVersion, String deviceId) {
        eventExecutor.execute(() -> {
            DiscoveredDevice current;
            synchronized (cacheLock) {
                current = devices.get(host + ":" + port);
            }
            String name = current != null ? current.name : "PC";
            boolean online = current != null && current.isOnline;
            DiscoveredDevice incoming = new DiscoveredDevice(name, host, port, online);
            incoming.version = version;
            incoming.protoVersion = String.valueOf(protoVersion);
            incoming.deviceId = deviceId;

            DiscoveredDevice merged;
            synchronized (cacheLock) {
                merged = mergeIncoming(incoming);
            }
            deviceStore.saveDevice(merged);
            notifyChanged();
        });
    }

    /** 外部（心跳）标记设备在线。投递到事件队列异步执行。 */
    public void markOnline(String host, int port) {
        eventExecutor.execute(() -> {
            DiscoveredDevice device;
            synchronized (cacheLock) {
                device = devices.get(host + ":" + port);
                if (device != null && !device.isOnline) {
                    device.isOnline = true;
                } else {
                    return; // 未变化，不通知
                }
            }
            deviceStore.saveDevice(device);
            notifyChanged();
        });
    }

    /** 外部（心跳）标记设备离线。投递到事件队列异步执行。 */
    public void markOffline(String host, int port) {
        eventExecutor.execute(() -> {
            DiscoveredDevice device;
            synchronized (cacheLock) {
                device = devices.get(host + ":" + port);
                if (device != null && device.isOnline) {
                    device.isOnline = false;
                } else {
                    return; // 未变化，不通知
                }
            }
            deviceStore.saveDevice(device);
            notifyChanged();
        });
    }

    /** mDNS 服务丢失：按服务名标记离线。投递到事件队列异步执行。 */
    private void markOfflineByName(String serviceName) {
        eventExecutor.execute(() -> {
            boolean changed = false;
            synchronized (cacheLock) {
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
        });
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