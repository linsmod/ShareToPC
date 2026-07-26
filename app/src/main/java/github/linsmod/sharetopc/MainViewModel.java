package github.linsmod.sharetopc;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import java.util.List;

import github.linsmod.sharetopc.data.TransferRecord;
import github.linsmod.sharetopc.discovery.DiscoveredDevice;
import github.linsmod.sharetopc.discovery.NsdDiscoveryManager;
import github.linsmod.sharetopc.transfer.TransferListener;
import github.linsmod.sharetopc.transfer.TransferManager;

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
                devices.setValue(list);
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
        public void onStateChanged(TransferManager.State state, String jobName) {
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
            } else {
                String name = jobName != null ? jobName : "";
                progress.setValue(new TransferProgress(name, -1, text));
            }
        }

        @Override
        public void onProgress(String itemName, long sentBytes, long totalBytes) {
            int percent = totalBytes > 0 ? (int) (sentBytes * 100 / totalBytes) : 0;
            progress.setValue(new TransferProgress(itemName, percent,
                    app.getString(R.string.state_sending)));
        }

        @Override
        public void onJobFinished(String jobId, boolean success, String errorMsg) {
            // 状态由后续 IDLE 覆盖
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
        refreshHistory();
    }

    public LiveData<List<DiscoveredDevice>> getDevices() {
        return devices;
    }

    public LiveData<String> getConnState() {
        return connState;
    }

    public LiveData<Boolean> getScanning() {
        return scanning;
    }

    /** 根据扫描状态与已发现数量生成状态文案（扫描中带倒计时）。 */
    private void updateConnState() {
        List<DiscoveredDevice> list = devices.getValue();
        int count = list != null ? list.size() : 0;
        boolean isScanning = Boolean.TRUE.equals(scanning.getValue());
        if (isScanning) {
            long remainingSec = Math.max(0, (scanEndAtMillis - System.currentTimeMillis() + 999) / 1000);
            connState.setValue(count > 0
                    ? app.getString(R.string.status_searching_found, remainingSec, count)
                    : app.getString(R.string.status_searching, remainingSec));
        } else {
            connState.setValue(count > 0
                    ? app.getString(R.string.status_found, count)
                    : app.getString(R.string.status_not_found));
        }
    }

    public LiveData<TransferProgress> getProgress() {
        return progress;
    }

    public LiveData<List<TransferRecord>> getHistory() {
        return history;
    }

    public void startDiscovery() {
        devices.setValue(app.discoveryManager.getDevices());
        app.discoveryManager.startDiscovery(app.settingsStore.getScanTimeoutSec() * 1000L);
    }

    /** 刷新设备列表（备注名变更后调用）。 */
    public void refreshDevices() {
        devices.setValue(app.discoveryManager.getDevices());
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

    /** 批量删除历史记录。 */
    public void deleteRecords(java.util.Collection<String> ids) {
        app.historyStore.removeByIds(ids);
        refreshHistory();
    }

    /** 暂存记录补发：入列发送并关联原记录，完成后原地更新为最终状态（保持单个条目）。 */
    public void sendPending(TransferRecord record, DiscoveredDevice device) {
        github.linsmod.sharetopc.cleanup.CleanupPolicy policy =
                github.linsmod.sharetopc.cleanup.CleanupPolicy.fromName(
                        record.policy, github.linsmod.sharetopc.cleanup.CleanupPolicy.AFTER_SEND);
        List<github.linsmod.sharetopc.data.PendingContent> items = new java.util.ArrayList<>();
        items.add(record.toPendingContent());
        app.transferManager.enqueue(items, device.host, device.port, device.displayName(), policy, record.id);
    }

    @Override
    protected void onCleared() {
        countdownHandler.removeCallbacks(countdownTick);
        app.discoveryManager.removeListener(discoveryListener);
        app.transferManager.removeListener(transferListener);
        super.onCleared();
    }
}
