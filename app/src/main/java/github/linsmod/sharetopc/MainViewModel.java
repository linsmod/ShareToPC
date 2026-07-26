package github.linsmod.sharetopc;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import java.util.ArrayList;
import java.util.List;

import github.linsmod.sharetopc.data.TransferRecord;
import github.linsmod.sharetopc.discovery.DiscoveredDevice;
import github.linsmod.sharetopc.discovery.NsdDiscoveryManager;
import github.linsmod.sharetopc.transfer.TransferListener;
import github.linsmod.sharetopc.transfer.TransferManager;
import github.linsmod.sharetopc.ui.DeviceAdapter;

/** 主界面状态：设备/连接状态/传输进度/历史。 */
public class MainViewModel extends AndroidViewModel {

    /** 当前传输进度展示。 */
    public static class TransferProgress {
        public final String name;
        public final int percent;
        public final String statusText;

        public TransferProgress(String name, int percent, String statusText) {
            this.name = name;
            this.percent = percent;
            this.statusText = statusText;
        }
    }

    private final MutableLiveData<List<DiscoveredDevice>> devices = new MutableLiveData<>();
    private final MutableLiveData<String> connState = new MutableLiveData<>();
    private final MutableLiveData<String> searchHint = new MutableLiveData<>();
    private final MutableLiveData<DeviceAdapter.DeviceTransferInfo> deviceTransfer = new MutableLiveData<>();
    private final MutableLiveData<Boolean> scanning = new MutableLiveData<>(false);
    private final MutableLiveData<TransferProgress> progress = new MutableLiveData<>();
    private final MutableLiveData<List<TransferRecord>> history = new MutableLiveData<>();

    private final App app;

    private NsdDiscoveryManager.Listener discoveryListener;
    private TransferListener transferListener;

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
                devices.setValue(mergeManualDevice(list));
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

        transferListener = new TransferListener() {
        @Override
        public void onStateChanged(TransferManager.State state, String jobName, String deviceKey) {
            String text;
            switch (state) {
                case CONNECTING:
                    text = app.getString(R.string.state_connecting);
                    break;
                case SENDING:
                    text = app.getString(R.string.state_sending);
                    break;
                case DONE:
                    text = app.getString(R.string.state_done);
                    break;
                case FAILED:
                    text = app.getString(R.string.state_failed);
                    break;
                default:
                    text = null;
                    break;
            }
            if (state == TransferManager.State.IDLE) {
                progress.setValue(null);
                deviceTransfer.setValue(new DeviceAdapter.DeviceTransferInfo(
                        null, null, 0, false));
            } else {
                String name = jobName != null ? jobName : "";
                progress.setValue(new TransferProgress(name, -1, text));
                deviceTransfer.setValue(new DeviceAdapter.DeviceTransferInfo(
                        deviceKey, name, -1, true));
            }
        }

        @Override
        public void onProgress(String itemName, long sentBytes, long totalBytes, String deviceKey) {
            int percent = totalBytes > 0 ? (int) (sentBytes * 100 / totalBytes) : 0;
            progress.setValue(new TransferProgress(itemName, percent,
                    app.getString(R.string.state_sending)));
            deviceTransfer.setValue(new DeviceAdapter.DeviceTransferInfo(
                    deviceKey, itemName, percent, true));
        }

        @Override
        public void onJobFinished(String jobId, boolean success, String errorMsg) {
            // 状态由后续 IDLE 覆盖
        }

        @Override
        public void onJobCancelled(String jobId, String jobName) {
            progress.setValue(new TransferProgress(jobName != null ? jobName : "", -1,
                    app.getString(R.string.state_cancelled)));
        }

            @Override
            public void onHistoryChanged() {
                refreshHistory();
            }
        };
    }

    public MainViewModel(@NonNull Application application) {
        super(application);
        app = (App) application;
        initListeners();
        app.discoveryManager.addListener(discoveryListener);
        app.transferManager.addListener(transferListener);
        app.historyStore.fixStaleSendingRecords();
        refreshHistory();
        // 立即显示已知设备（离线状态），包含手动设备
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

    public LiveData<DeviceAdapter.DeviceTransferInfo> getDeviceTransfer() {
        return deviceTransfer;
    }

    public LiveData<Boolean> getScanning() {
        return scanning;
    }

    /** 根据扫描状态与已发现数量生成状态文案（扫描中在已发现x台设备后追加秒数）。 */
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

    public LiveData<TransferProgress> getProgress() {
        return progress;
    }

    public LiveData<List<TransferRecord>> getHistory() {
        return history;
    }

    public void startDiscovery() {
        app.discoveryManager.stopDiscovery();
        refreshDevices();
        app.discoveryManager.startDiscovery(app.settingsStore.getScanTimeoutSec() * 1000L);
    }

    /** 刷新设备列表（备注名变更后调用）。也用于手动设备保存后刷新。 */
    public void refreshDevices() {
        devices.setValue(mergeManualDevice(app.discoveryManager.getDevices()));
    }

    /** 将手动添加的设备合并到列表中（去重）。 */
    private List<DiscoveredDevice> mergeManualDevice(List<DiscoveredDevice> list) {
        DiscoveredDevice manual = app.settingsStore.getManualDevice();
        if (manual == null) return list;
        for (DiscoveredDevice d : list) {
            if (d.key().equals(manual.key())) return list;
        }
        List<DiscoveredDevice> merged = new ArrayList<>(list);
        merged.add(manual);
        return merged;
    }

    /** 从当前设备列表中移除指定设备（下次扫描可能重新发现）。 */
    public void removeDevice(DiscoveredDevice device) {
        app.discoveryManager.removeDevice(device.host, device.port);
        refreshDevices();
    }

    public void stopDiscovery() {
        app.discoveryManager.stopDiscovery();
    }

    public void setPermissionDeniedState() {
        connState.setValue(app.getString(R.string.status_no_permission));
    }

    public void refreshHistory() {
        history.setValue(app.historyStore.all());
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
        app.historyStore.removeByIds(ids);
        refreshHistory();
    }

    /** 暂存记录补发：入列发送并关联原记录，完成后原地更新为最终状态（保持单个条目）。 */
    public void sendPending(TransferRecord record, DiscoveredDevice device) {
        List<github.linsmod.sharetopc.data.PendingContent> items = new java.util.ArrayList<>();
        items.add(record.toPendingContent());
        app.transferManager.enqueue(items, device.host, device.port, device.displayName(), record.id);
    }

    /** 取消当前正在进行的传输。 */
    public void cancelCurrentTransfer() {
        app.transferManager.cancelCurrentJob();
    }

    /** 暂停当前传输（= cancel + 标记 paused，PFD 不释放，支持继续）。 */
    public void pauseCurrentTransfer() {
        app.transferManager.cancelCurrentJob();
    }

    /** 继续/重试指定记录。 */
    public void resumeTransfer(String recordId) {
        if (recordId == null) return;
        java.util.List<TransferRecord> records = app.historyStore.all();
        for (TransferRecord r : records) {
            if (recordId.equals(r.id)) {
                retry(r);
                return;
            }
        }
    }

    /** 取消队列中指定 ID 的任务。 */
    public boolean cancelQueuedJob(String jobId) {
        boolean result = app.transferManager.cancelQueuedJob(jobId);
        if (result) {
            refreshHistory();
        }
        return result;
    }

    @Override
    protected void onCleared() {
        countdownHandler.removeCallbacks(countdownTick);
        app.discoveryManager.removeListener(discoveryListener);
        app.transferManager.removeListener(transferListener);
        super.onCleared();
    }
}
