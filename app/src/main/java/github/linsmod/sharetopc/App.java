package github.linsmod.sharetopc;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.tencent.bugly.crashreport.CrashReport;

import github.linsmod.sharetopc.data.DeviceStore;
import github.linsmod.sharetopc.data.JobStore;
import github.linsmod.sharetopc.data.SettingsStore;
import github.linsmod.sharetopc.discovery.HeartbeatManager;
import github.linsmod.sharetopc.discovery.NsdDiscoveryManager;
import github.linsmod.sharetopc.service.ShareForegroundService;
import github.linsmod.sharetopc.transfer.TransferManager;
import github.linsmod.sharetopc.util.LogX;

/** Application：手动 ServiceLocator，持有全局单例。 */
public class App extends Application {

    private static App instance;

    public ExecutorService ioExecutor;
    public Handler mainHandler;
    public SettingsStore settingsStore;
    public DeviceStore deviceStore;
    public JobStore jobStore;
    public NsdDiscoveryManager discoveryManager;
    public HeartbeatManager heartbeatManager;
    public TransferManager transferManager;

    public static App get() {
        return instance;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        LogX.init(this);
        CrashReport.initCrashReport(getApplicationContext(), "c859d2328b", false);
        ioExecutor = Executors.newFixedThreadPool(4);
        mainHandler = new Handler(Looper.getMainLooper());
        settingsStore = new SettingsStore(this);
        settingsStore.getDeviceId(); // 首次启动时生成持久化 deviceId
        CrashReport.setUserId(settingsStore.getDeviceId());
        deviceStore = new DeviceStore(this);
        jobStore = new JobStore(this);
        jobStore.markOutdateOnStartup();
        discoveryManager = new NsdDiscoveryManager(this, deviceStore);
        heartbeatManager = new HeartbeatManager(discoveryManager);
        heartbeatManager.start();
        transferManager = new TransferManager(this, jobStore, deviceStore);
        // 创建前台服务通知渠道
        ShareForegroundService.createNotificationChannel(this);
    }
}
