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
import java.util.concurrent.CopyOnWriteArrayList;

import github.linsmod.sharetopc.data.SendEntry;
import github.linsmod.sharetopc.discovery.DiscoveredDevice;
import github.linsmod.sharetopc.discovery.NsdDiscoveryManager;
import github.linsmod.sharetopc.transfer.TransferManager;
import github.linsmod.sharetopc.ui.TaskGroup;

public class MainViewModel extends AndroidViewModel {

    private final MutableLiveData<List<DiscoveredDevice>> devices = new MutableLiveData<>();
    private final MutableLiveData<String> connState = new MutableLiveData<>();
    private boolean scanning = false;
    private List<SendEntry> history = null;
    private Map<String, TransferManager.SendJob> deviceJobs;

    /** 任务列表变更回调（替代 LiveData，避免 lifecycle 延迟丢失初始值）。 */
    public interface TasksChangeListener {
        void onTasksChanged(@NonNull List<TaskGroup> tasks);
    }

    private final List<TasksChangeListener> tasksListeners = new CopyOnWriteArrayList<>();
    private List<TaskGroup> cachedTasks = Collections.emptyList();

    /** 条目进度增量更新回调（替代传输中全量 refreshTasks）。 */
    public interface EntryUpdateListener {
        void onEntryUpdated(@NonNull SendEntry entry);
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
            deviceJobs = null; // 使 DB 缓存失效，下次 computeDeviceLatestJob 重新读取最新状态
            updateDeviceProgressInPlace();
            refreshHistory();
            // 通知条目级监听器，确保弹窗中的列表即时更新（如暂停后状态变更）
            for (SendEntry entry : job.items) {
                for (EntryUpdateListener l : entryUpdateListeners) {
                    l.onEntryUpdated(entry);
                }
            }
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
                scanning = isScanning;
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
        updateConnState();
    }

    public LiveData<List<DiscoveredDevice>> getDevices() {
        return devices;
    }

    public LiveData<String> getConnState() {
        return connState;
    }

    private void updateConnState() {
        List<DiscoveredDevice> list = devices.getValue();
        int count = list != null ? list.size() : 0;
        String base = count > 0
                ? app.getString(R.string.status_found, count)
                : app.getString(R.string.status_not_found);
        connState.setValue(scanning ? base + "（扫描中）" : base);
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

    @Nullable
    public List<SendEntry> getHistory() {
        return history;
    }

    /** 从当前队列中按 jobId 查找对应的 SendJob。 */
    @Nullable
    public TransferManager.SendJob findJobById(@Nullable String jobId) {
        if (jobId == null) return null;
        List<TransferManager.SendJob> queue = app.transferManager.getQueueSnapshot();
        if (queue != null) {
            for (TransferManager.SendJob j : queue) {
                if (jobId.equals(j.id)) return j;
            }
        }
        return null;
    }

    public void setDeviceProgressListener(DeviceProgressListener l) {
        this.deviceProgressListener = l;
    }

    public void startDiscovery() {
        refreshDevices();
    }

    // ── 设备进度 ──────────────────────────────────────────

    /** 计算每个设备的当前传输进度，用于设备卡片上显示进度条。 */
    private Map<String, TransferManager.SendJob> computeDeviceLatestJob() {
        Map<String, TransferManager.SendJob> map = new HashMap<>();

        // 1) 当前正在传输的 job
        TransferManager.SendJob cur = app.transferManager.getCurrentJob();
        if (cur != null) {
            map.put(cur.deviceId, cur);
        }

        // 2) 队列中等待的 job（设备当前无活动 job 且有排队任务时显示排队状态）
        for (TransferManager.SendJob job : app.transferManager.getQueueSnapshot()) {
            String key = job.host + ":" + job.port;
            if (!map.containsKey(key)) {
                map.put(key, job);
            }
        }

        // 3) 从 DB 历史中恢复最近24小时的设备Job记录
        // cache避免IO频繁
        if(this.deviceJobs == null){
            this.deviceJobs = app.jobStore.allDeviceLatestJobs(24);
        }
        this.deviceJobs.keySet().forEach(deviceId->{
            if(!map.containsKey(deviceId)){
                map.put(deviceId, deviceJobs.get(deviceId));
            }
        });
        return map;
    }

    /** 高德设备列表，关联合并传输进度。 */
    private List<DiscoveredDevice> enrichDeviceProgress(List<DiscoveredDevice> list) {
        Map<String, TransferManager.SendJob> progressMap = computeDeviceLatestJob();
        for (DiscoveredDevice d : list) {
            d.latestJob = progressMap.get(d.deviceId);
            if (d.latestJob != null) d.latestJobId = d.latestJob.id;
        }
        return list;
    }

    /** 原地更新当前设备列表的进度（无需重查 DB/网络），通过回调直接通知 UI 刷新。 */
    private void updateDeviceProgressInPlace() {
        List<DiscoveredDevice> list = devices.getValue();
        if (list == null || deviceProgressListener == null) return;
        Map<String, TransferManager.SendJob> progressMap = computeDeviceLatestJob();
        for (DiscoveredDevice d : list) {
            // 优先用 deviceId 匹配（与 enrichDeviceProgress 保持一致），fallback 到 address()
            TransferManager.SendJob p = progressMap.get(d.deviceId);
            if (p == null) p = progressMap.get(d.address());
            d.latestJob = p;
            if (p != null) d.latestJobId = p.id;
        }
        // 直接遍历设备列表通知，确保 key 与 DeviceAdapter 中 address() 匹配
        for (DiscoveredDevice d : list) {
            deviceProgressListener.onDeviceProgressChanged(d.address());
        }
    }

    /** 刷新任务列表（按 jobId 分组，树状展示）。 */
    public void refreshTasks() {
        // 从 DB 取所有历史记录
        List<SendEntry> all = new ArrayList<>(app.jobStore.all());
        java.util.Set<String> seenIds = new java.util.HashSet<>();
        for (SendEntry r : all) {
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
        Map<String, List<SendEntry>> entryMap = new LinkedHashMap<>();
        Map<String, Long> maxTimeMap = new HashMap<>();
        Map<String, String> nameMap = new HashMap<>();
        Map<String, String> hostMap = new HashMap<>();
        Map<String, Integer> portMap = new HashMap<>();

        for (SendEntry r : all) {
            String jid = r.jobId != null ? r.jobId : "__no_job__";
            List<SendEntry> list = entryMap.get(jid);
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
        for (List<SendEntry> entries : entryMap.values()) {
            java.util.Collections.reverse(entries);
        }

        // 构建 jobId -> SendJob 映射，用于取 job.updateMillis 作为分组时间
        Map<String, TransferManager.SendJob> jobMap = new HashMap<>();
        if (queue != null) {
            for (TransferManager.SendJob j : queue) {
                if (j.id != null) jobMap.put(j.id, j);
            }
        }

        // 第三步：创建 TaskGroup，优先用 job.updateMillis，回退到 entry max time
        List<TaskGroup> result = new ArrayList<>();
        for (Map.Entry<String, List<SendEntry>> e : entryMap.entrySet()) {
            String jid = e.getKey();
            List<SendEntry> entries = e.getValue();
            TransferManager.SendJob job = jobMap.get(jid);
            long groupTime = (job != null && job.updateMillis != null && job.updateMillis > 0)
                    ? job.updateMillis
                    : (maxTimeMap.get(jid) != null ? maxTimeMap.get(jid) : 0);
            TaskGroup g = new TaskGroup(jid, nameMap.get(jid),
                    hostMap.get(jid), portMap.get(jid) != null ? portMap.get(jid) : 0,
                    groupTime, entries);
            // 继承旧的展开/折叠状态，避免刷新时状态丢失
            for (TaskGroup old : cachedTasks) {
                if (jid.equals(old.jobId)) {
                    g.expanded = old.expanded;
                    break;
                }
            }
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
        deviceJobs = null; // 使缓存失效，下次 computeDeviceLatestJob 重新从 DB 读取
        history = app.jobStore.all();
        refreshTasks();
    }

    /** 查询指定 transferId 是否仍有 PFD 可用（用于 UI 判断是否允许重试/继续）。 */
    public boolean hasPfd(String transferId) {
        return app.transferManager.hasPfd(transferId);
    }

    /** 删除整个任务分组：先取消传输 + 清理队列，再删除数据库记录。 */
    public void deleteGroup(TaskGroup group) {
        // 1. 取消正在传输的任务 + 清理等待队列
        app.transferManager.cancelJob(group.jobId);
        app.jobStore.deleteEntriesByJobId(group.jobId);
        // 3. 刷新 UI
        refreshHistory();
    }

    /** 暂停分组：对所有 canRequestPause() 的条目设置 pending pause + cancel。 */
    public void pauseGroup(TaskGroup group) {
        app.transferManager.cancelJob(group.jobId);
    }

    /** 继续/恢复分组中所有可恢复的条目。 */
    public void resumeGroup(TaskGroup group) {
        boolean resumed = false;
        for (SendEntry r : group.entries) {
            if(r.canResume()){
                resumed = app.transferManager.resumeJob(group.jobId);
                break;
            }
        }
        if(resumed)
            refreshHistory();
    }

    @Override
    protected void onCleared() {
        app.discoveryManager.removeListener(discoveryListener);
        app.transferManager.removeQueueListener(queueListener);
        app.transferManager.removeProgressListener(progressListener);
        super.onCleared();
    }
}
