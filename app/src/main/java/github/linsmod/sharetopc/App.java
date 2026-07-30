package github.linsmod.sharetopc;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;

import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleObserver;
import androidx.lifecycle.OnLifecycleEvent;
import androidx.lifecycle.ProcessLifecycleOwner;

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
        // HB 通道 PAUSE / RESUME 监听 → 委托 TransferManager 优雅暂停/恢复
        heartbeatManager.setMessageListener(new HeartbeatManager.MessageListener() {
            @Override
            public void onPause(String host, int port, String jobId) {
                LogX.d("Hb-PAUSE from PC: jobId=" + jobId);
                transferManager.cancelJob(jobId);
            }
            @Override
            public void onResume(String host, int port, String jobId) {
                LogX.d("Hb-RESUME from PC: jobId=" + jobId);
                transferManager.resumeJob(jobId);
            }
        });
        // 创建前台服务通知渠道
        ShareForegroundService.createNotificationChannel(this);
        // 前后台自动管理持续发现：前台启，后台停
        bindLifecycleDiscovery();
    }

    private final Runnable delayedStopDiscovery = () -> {
        LogX.d("App background → stop NSD discovery (delayed)");
        discoveryManager.stopDiscovery();
    };

    private void bindLifecycleDiscovery() {
        ProcessLifecycleOwner.get().getLifecycle().addObserver(new LifecycleObserver() {
            @OnLifecycleEvent(Lifecycle.Event.ON_START)
            void onForeground() {
                mainHandler.removeCallbacks(delayedStopDiscovery);
                if (!discoveryManager.isDiscovering()) {
                    LogX.d("App foreground → start NSD discovery");
                    discoveryManager.startDiscovery();
                }
            }

            @OnLifecycleEvent(Lifecycle.Event.ON_STOP)
            void onBackground() {
                LogX.d("App background → schedule NSD stop in 10s");
                mainHandler.postDelayed(delayedStopDiscovery, 10_000);
            }
        });
    }
}
