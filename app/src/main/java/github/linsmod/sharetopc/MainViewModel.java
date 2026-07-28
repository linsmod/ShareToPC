package github.linsmod.sharetopc;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

import github.linsmod.sharetopc.data.SendEntry;
import github.linsmod.sharetopc.data.TransferRecord;
import github.linsmod.sharetopc.discovery.DiscoveredDevice;
import github.linsmod.sharetopc.discovery.NsdDiscoveryManager;
import github.linsmod.sharetopc.transfer.TransferManager;
import github.linsmod.sharetopc.ui.TaskGroup;

public class MainViewModel extends AndroidViewModel {

    private final MutableLiveData<List<DiscoveredDevice>> devices = new MutableLiveData<>();
    private final MutableLiveData<String> connState = new MutableLiveData<>();
    private final MutableLiveData<String> searchHint = new MutableLiveData<>();
    private final MutableLiveData<Boolean> scanning = new MutableLiveData<>(false);
    private final MutableLiveData<List<TransferRecord>> history = new MutableLiveData<>();

    /** 任务列表变更回调（替代 LiveData，避免 lifecycle 延迟丢失初始值）。 */
    public interface TasksChangeListener {
        void onTasksChanged(@NonNull List<TaskGroup> tasks);
    }

    private final List<TasksChangeListener> tasksListeners = new CopyOnWriteArrayList<>();
    private List<TaskGroup> cachedTasks = Collections.emptyList();

    /** 条目进度增量更新回调（替代传输中全量 refreshTasks）。 */
    public interface EntryUpdateListener {
        void onEntryUpdated(@NonNull TransferRecord entry);
    }

    private final List<EntryUpdateListener> entryUpdateListeners = new CopyOnWriteArrayList<>();

    private final App app;

    /** 设备进度更新回调（不用 LiveData，直接通知 UI 刷新）。 */
    public interface DeviceProgressListener {
        void onDeviceProgressChanged(@NonNull String deviceAddress);
    }
    private DeviceProgressListener deviceProgressListener;

    private NsdDiscoveryManager.Listener discoveryListener;
    private final TransferManager.QueueListener queueListener = jobs -> {
        refreshTasks();
        updateDeviceProgressInPlace();
    };
    private final TransferManager.JobProgressListener progressListener = new TransferManager.JobProgressListener() {
        @Override
        public void onProgress(@NonNull TransferManager.SendJob job) {
            updateDeviceProgressInPlace();
            // 仅通知受影响的条目增量更新，避免全量 notifyDataSetChanged 导致 UI 卡顿
            for (SendEntry entry : job.items) {
                for (EntryUpdateListener l : entryUpdateListeners) {
                    l.onEntryUpdated(entry);
                }
            }
        }

        @Override
        public void onJobStarted(@NonNull TransferManager.SendJob job) {
            updateDeviceProgressInPlace();
            refreshTasks();
        }

        @Override
        public void onJobFinished(@NonNull TransferManager.SendJob job) {
            updateDeviceProgressInPlace();
            refreshHistory();
        }
    };

    private final android.os.Handler countdownHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private long scanEndAtMillis = 0L;
    private final Runnable countdownTick = new Runnable() {
        @Override
        public void run() {
            if (!Boolean.TRUE.equals(scanning.getValue())) {
                return;
            }
            updateConnState();
            countdownHandler.postDelayed(this, 1000);
        }
    };

    private void initListeners() {
        discoveryListener = new NsdDiscoveryManager.Listener() {
            @Override
            public void onDevicesChanged(List<DiscoveredDevice> list) {
                devices.setValue(enrichDeviceProgress(list));
                updateConnState();
            }

            @Override
            public void onScanStateChanged(boolean isScanning) {
                scanning.setValue(isScanning);
                countdownHandler.removeCallbacks(countdownTick);
                if (isScanning) {
                    scanEndAtMillis = System.currentTimeMillis()
                            + app.settingsStore.getScanTimeoutSec() * 1000L;
                    countdownHandler.post(countdownTick);
                }
                updateConnState();
            }
        };
    }

    public MainViewModel(@NonNull Application application) {
        super(application);
        app = (App) application;
        initListeners();
        app.discoveryManager.addListener(discoveryListener);
        app.transferManager.addQueueListener(queueListener);
        app.transferManager.addProgressListener(progressListener);
        refreshHistory();
        refreshTasks();
        refreshDevices();
        updateConnState();
    }

    public LiveData<List<DiscoveredDevice>> getDevices() {
        return devices;
    }

    public LiveData<String> getConnState() {
        return connState;
    }

    public LiveData<String> getSearchHint() {
        return searchHint;
    }

    private void updateConnState() {
        List<DiscoveredDevice> list = devices.getValue();
        int count = list != null ? list.size() : 0;
        boolean isScanning = Boolean.TRUE.equals(scanning.getValue());
        String base = count > 0
                ? app.getString(R.string.status_found, count)
                : app.getString(R.string.status_not_found);
        if (isScanning) {
            long remainingSec = Math.max(0, (scanEndAtMillis - System.currentTimeMillis() + 999) / 1000);
            connState.setValue(base + "（" + remainingSec + "s）");
            searchHint.setValue(null);
        } else {
            connState.setValue(base);
            searchHint.setValue(null);
        }
    }

    /** 注册任务变更监听器，注册时立即回调当前数据。 */
    public void addTasksChangeListener(@NonNull TasksChangeListener l) {
        tasksListeners.add(l);
        l.onTasksChanged(cachedTasks);
    }

    /** 取消注册任务变更监听器。 */
    public void removeTasksChangeListener(@NonNull TasksChangeListener l) {
        tasksListeners.remove(l);
    }

    /** 注册条目进度增量更新监听器。 */
    public void addEntryUpdateListener(@NonNull EntryUpdateListener l) {
        entryUpdateListeners.add(l);
    }

    /** 取消注册条目进度增量更新监听器。 */
    public void removeEntryUpdateListener(@NonNull EntryUpdateListener l) {
        entryUpdateListeners.remove(l);
    }

    public LiveData<List<TransferRecord>> getHistory() {
        return history;
    }

    public void setDeviceProgressListener(DeviceProgressListener l) {
        this.deviceProgressListener = l;
    }

    public void startDiscovery() {
        app.discoveryManager.stopDiscovery();
        refreshDevices();
        app.discoveryManager.startDiscovery(app.settingsStore.getScanTimeoutSec() * 1000L);
    }

    // ── 设备进度 ──────────────────────────────────────────

    /** 计算每个设备的当前传输进度，用于设备卡片上显示进度条。 */
    private Map<String, DiscoveredDevice.DeviceProgress> computeDeviceProgress() {
        Map<String, DiscoveredDevice.DeviceProgress> map = new HashMap<>();

        // 1) 当前正在传输的 job
        TransferManager.SendJob cur = app.transferManager.getCurrentJob();
        if (cur != null) {
            DiscoveredDevice.DeviceProgress p = new DiscoveredDevice.DeviceProgress();
            p.jobId = cur.id;
            p.state = cur.isCancelRequested() || cur.isCancelled()
                    ? DiscoveredDevice.DeviceProgress.STATE_PAUSED
                    : DiscoveredDevice.DeviceProgress.STATE_SENDING;
            p.totalEntries = cur.items.size();
            for (SendEntry e : cur.items) {
                if (e.isSuccess()) p.completedEntries++;
            }
            // 进度条只反映当前 entry，不是总体
            SendEntry curEntry = findCurrentEntry(cur.items);
            if (curEntry != null) {
                p.totalBytes = curEntry.totalBytes > 0 ? curEntry.totalBytes : curEntry.size;
                p.sentBytes = curEntry.sentBytes;
                p.currentEntryName = curEntry.name;
            }
            map.put(cur.host + ":" + cur.port, p);
        }

        // 2) 队列中等待的 job（设备当前无活动 job 且有排队任务时显示排队状态）
        for (TransferManager.SendJob job : app.transferManager.getQueueSnapshot()) {
            String key = job.host + ":" + job.port;
            if (!map.containsKey(key)) {
                DiscoveredDevice.DeviceProgress p = new DiscoveredDevice.DeviceProgress();
                p.jobId = job.id;
                p.state = DiscoveredDevice.DeviceProgress.STATE_QUEUED;
                p.totalEntries = job.items.size();
                for (SendEntry e : job.items) {
                    p.totalBytes += e.size;
                }
                map.put(key, p);
            }
        }

        // 3) 从 DB 历史中，为没有活跃任务的设备补上最近一次已完成的 job 进度
        List<TransferRecord> history = app.jobStore.all();
        if (history != null && !history.isEmpty()) {
            // 找到每个设备的最新 jobId
            Map<String, String> deviceLatestJobId = new HashMap<>();
            Map<String, Long> deviceLatestTime = new HashMap<>();
            for (TransferRecord r : history) {
                String deviceKey = r.deviceAddress();
                if (map.containsKey(deviceKey)) continue; // 有活跃任务的不覆盖
                if (r.jobId == null) continue;
                Long prev = deviceLatestTime.get(deviceKey);
                if (prev == null || r.timeMillis > prev) {
                    deviceLatestTime.put(deviceKey, r.timeMillis);
                    deviceLatestJobId.put(deviceKey, r.jobId);
                }
            }
                // 为每个设备构造进度（根据 DB 中 entry 的实际状态还原）
            for (Map.Entry<String, String> e : deviceLatestJobId.entrySet()) {
                String deviceKey = e.getKey();
                String jobId = e.getValue();
                DiscoveredDevice.DeviceProgress p = new DiscoveredDevice.DeviceProgress();
                p.jobId = jobId;
                boolean allSuccess = true;
                boolean hasOutdate = false;
                long maxTime = 0;
                for (TransferRecord r : history) {
                    if (jobId.equals(r.jobId)) {
                        p.totalEntries++;
                        p.totalBytes += r.size;
                        p.sentBytes += r.sentBytes;
                        if (r.idleReason == TransferRecord.IDLE_REASON_SUCCESS) {
                            p.completedEntries++;
                        } else {
                            allSuccess = false;
                            if (r.idleReason == TransferRecord.IDLE_REASON_OUTDATE) {
                                hasOutdate = true;
                            }
                        }
                        if (r.timeMillis > maxTime) maxTime = r.timeMillis;
                    }
                }
                p.state = allSuccess
                        ? DiscoveredDevice.DeviceProgress.STATE_COMPLETED
                        : DiscoveredDevice.DeviceProgress.STATE_PAUSED;
                if (allSuccess) {
                    p.completedAt = maxTime;
                }
                p.expiredAt = (hasOutdate && !allSuccess && p.completedEntries > 0) ? maxTime : 0;
                map.put(deviceKey, p);
            }
        }

        return map;
    }

    /** 从 SendEntry 列表中找出当前正在发送的 entry。 */
    @Nullable
    private SendEntry findCurrentEntry(List<SendEntry> items) {
        if (items == null || items.isEmpty()) return null;
        // 优先找 STATUS_SENDING 状态的
        for (SendEntry e : items) {
            if (e.status == TransferRecord.STATUS_SENDING) return e;
        }
        // 找第一个未完成的
        for (SendEntry e : items) {
            if (!e.isSuccess()) return e;
        }
        return null;
    }

    /** 高德设备列表，关联合并传输进度。 */
    private List<DiscoveredDevice> enrichDeviceProgress(List<DiscoveredDevice> list) {
        Map<String, DiscoveredDevice.DeviceProgress> progressMap = computeDeviceProgress();
        for (DiscoveredDevice d : list) {
            DiscoveredDevice.DeviceProgress p = progressMap.get(d.address());
            d.currentProgress = p != null ? p : new DiscoveredDevice.DeviceProgress();
        }
        return list;
    }

    /** 原地更新当前设备列表的进度（无需重查 DB/网络），通过回调直接通知 UI 刷新。 */
    private void updateDeviceProgressInPlace() {
        List<DiscoveredDevice> list = devices.getValue();
        if (list == null || deviceProgressListener == null) return;
        Map<String, DiscoveredDevice.DeviceProgress> progressMap = computeDeviceProgress();
        for (DiscoveredDevice d : list) {
            DiscoveredDevice.DeviceProgress p = progressMap.get(d.address());
            d.currentProgress = p != null ? p : new DiscoveredDevice.DeviceProgress();
        }
        for (String addr : progressMap.keySet()) {
            deviceProgressListener.onDeviceProgressChanged(addr);
        }
    }

    /** 刷新任务列表（按 jobId 分组，树状展示）。 */
    public void refreshTasks() {
        // 从 DB 取所有历史记录
        List<TransferRecord> all = new ArrayList<>(app.jobStore.all());
        java.util.Set<String> seenIds = new java.util.HashSet<>();
        for (TransferRecord r : all) {
            if (r.id != null) seenIds.add(r.id);
        }
        // 合并队列中未入库的 entry（按 id 去重，DB 已有则跳过）
        List<TransferManager.SendJob> queue = app.transferManager.getQueueSnapshot();
        if (queue != null) {
            for (TransferManager.SendJob job : queue) {
                for (SendEntry entry : job.items) {
                    if (entry.id != null && seenIds.add(entry.id)) {
                        all.add(entry);
                    }
                }
            }
        }

        // 第一步：按 jobId 收集条目，同时记录最大 timeMillis 和设备信息
        Map<String, List<TransferRecord>> entryMap = new LinkedHashMap<>();
        Map<String, Long> maxTimeMap = new HashMap<>();
        Map<String, String> nameMap = new HashMap<>();
        Map<String, String> hostMap = new HashMap<>();
        Map<String, Integer> portMap = new HashMap<>();

        for (TransferRecord r : all) {
            String jid = r.jobId != null ? r.jobId : "__no_job__";
            List<TransferRecord> list = entryMap.get(jid);
            if (list == null) {
                list = new ArrayList<>();
                entryMap.put(jid, list);
                nameMap.put(jid, r.targetName != null ? r.targetName : (r.host != null ? r.host : ""));
                hostMap.put(jid, r.host);
                portMap.put(jid, r.port);
                maxTimeMap.put(jid, r.timeMillis);
            }
            list.add(r);
            if (r.timeMillis > maxTimeMap.get(jid)) {
                maxTimeMap.put(jid, r.timeMillis);
                // 用时间最新的那条记录的设备名
                if (r.targetName != null && !r.targetName.isEmpty()) {
                    nameMap.put(jid, r.targetName);
                }
                if (r.host != null) {
                    hostMap.put(jid, r.host);
                    portMap.put(jid, r.port);
                }
            }
        }

        // 第二步：反转各组内 entry 顺序，使最早提交的排最前（DB 查询是 DESC）
        for (List<TransferRecord> entries : entryMap.values()) {
            java.util.Collections.reverse(entries);
        }

        // 第三步：用收集到的最大 timeMillis 创建 TaskGroup
        List<TaskGroup> result = new ArrayList<>();
        for (Map.Entry<String, List<TransferRecord>> e : entryMap.entrySet()) {
            String jid = e.getKey();
            List<TransferRecord> entries = e.getValue();
            long maxTime = maxTimeMap.get(jid) != null ? maxTimeMap.get(jid) : 0;
            TaskGroup g = new TaskGroup(jid, nameMap.get(jid),
                    hostMap.get(jid), portMap.get(jid) != null ? portMap.get(jid) : 0,
                    maxTime, entries);
            g.recomputeProgress();
            result.add(g);
        }
        cachedTasks = result;
        for (TasksChangeListener l : tasksListeners) {
            l.onTasksChanged(result);
        }
    }

    /** 刷新设备列表（备注名变更后调用）。也用于手动设备保存后刷新。 */
    public void refreshDevices() {
        devices.setValue(enrichDeviceProgress(app.discoveryManager.getDevices()));
    }

    /** 从当前设备列表中移除指定设备（下次扫描可能重新发现）。 */
    public void removeDevice(DiscoveredDevice device) {
        app.discoveryManager.removeDevice(device.host, device.port);
        refreshDevices();
    }

    public void stopDiscovery() {
        app.discoveryManager.stopDiscovery();
    }

    public void refreshHistory() {
        history.setValue(app.jobStore.all());
        refreshTasks();
    }

    public void retry(TransferRecord record) {
        app.transferManager.retry(record);
    }

    /** 查询指定 transferId 是否仍有 PFD 可用（用于 UI 判断是否允许重试/继续）。 */
    public boolean hasPfd(String transferId) {
        return app.transferManager.hasPfd(transferId);
    }

    /** 批量删除历史记录。 */
    public void deleteRecords(java.util.Collection<String> ids) {
        app.jobStore.removeByIds(ids);
        refreshHistory();
    }

    /** 删除整个任务分组：先取消传输 + 清理队列，再删除数据库记录。 */
    public void deleteGroup(TaskGroup group) {
        // 1. 取消正在传输的任务 + 清理等待队列
        app.transferManager.cancelAndRemoveJob(group.jobId);
        // 2. 从数据库删除
        java.util.List<String> ids = new java.util.ArrayList<>();
        for (TransferRecord r : group.entries) {
            if (r.id != null) ids.add(r.id);
        }
        app.jobStore.removeByIds(ids);
        // 3. 刷新 UI
        refreshHistory();
    }

    /** 暂停当前传输：设置 pending action + cancel，等 CANCEL 帧发出后 process() 转为 PAUSED。 */
    public void pauseTransfer(TransferRecord record) {
        TransferManager.SendJob job = app.transferManager.getCurrentJob();
        if (job != null) {
            for (SendEntry entry : job.items) {
                if (entry.id != null && entry.id.equals(record.id) && entry.canRequestPause()) {
                    app.transferManager.requestPause(entry);
                    job.cancel();
                    break;
                }
            }
        }
    }

    /** 暂停分组：对所有 canRequestPause() 的条目设置 pending pause + cancel。 */
    public void pauseGroup(TaskGroup group) {
        TransferManager.SendJob cur = app.transferManager.getCurrentJob();
        boolean cancelled = false;
        if (cur != null && cur.id.equals(group.jobId)) {
            for (SendEntry entry : cur.items) {
                if (entry.canRequestPause()) {
                    app.transferManager.requestPause(entry);
                    cancelled = true;
                }
            }
            if (cancelled) {
                cur.cancel(); // 触发 CANCEL 帧发送
            }
        }
        // 队列中等待的 job：直接标记 cancel，worker 取到时自动跳过
        for (TransferManager.SendJob job : app.transferManager.getQueueSnapshot()) {
            if (job.id.equals(group.jobId)) {
                job.cancel();
            }
        }
    }

    /** 继续/恢复分组中所有可恢复的条目。 */
    public void resumeGroup(TaskGroup group) {
        for (TransferRecord r : group.entries) {
            if (r.status == TransferRecord.STATUS_IDLE
                    && r.idleReason != TransferRecord.IDLE_REASON_SUCCESS
                    && r.pendingAction == 0) {
                retry(r);
            }
        }
        refreshHistory();
    }

    /** 继续/重试指定记录。 */
    public void resumeTransfer(TransferRecord record) {
        retry(record);
    }

    @Override
    protected void onCleared() {
        countdownHandler.removeCallbacks(countdownTick);
        app.discoveryManager.removeListener(discoveryListener);
        app.transferManager.removeQueueListener(queueListener);
        app.transferManager.removeProgressListener(progressListener);
        super.onCleared();
    }
}
