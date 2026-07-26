package github.linsmod.sharetopc;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import github.linsmod.sharetopc.cleanup.CleanupScheduler;
import github.linsmod.sharetopc.data.DeviceStore;
import github.linsmod.sharetopc.data.HistoryStore;
import github.linsmod.sharetopc.data.SettingsStore;
import github.linsmod.sharetopc.discovery.HeartbeatManager;
import github.linsmod.sharetopc.discovery.NsdDiscoveryManager;
import github.linsmod.sharetopc.transfer.TransferManager;

/** Application：手动 ServiceLocator，持有全局单例。 */
public class App extends Application {

    private static App instance;

    public ExecutorService ioExecutor;
    public Handler mainHandler;
    public SettingsStore settingsStore;
    public DeviceStore deviceStore;
    public HistoryStore historyStore;
    public NsdDiscoveryManager discoveryManager;
    public HeartbeatManager heartbeatManager;
    public TransferManager transferManager;
    public CleanupScheduler cleanupScheduler;

    public static App get() {
        return instance;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        ioExecutor = Executors.newFixedThreadPool(4);
        mainHandler = new Handler(Looper.getMainLooper());
        settingsStore = new SettingsStore(this);
        deviceStore = new DeviceStore(this);
        historyStore = new HistoryStore(this);
        discoveryManager = new NsdDiscoveryManager(this, deviceStore);
        heartbeatManager = new HeartbeatManager(discoveryManager);
        heartbeatManager.start();
        cleanupScheduler = new CleanupScheduler(this);
        transferManager = new TransferManager(this, historyStore, cleanupScheduler);
        cleanupScheduler.ensurePeriodicScan();
    }
}
