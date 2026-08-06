package github.linsmod.sharetopc.transfer;

import android.content.ActivityNotFoundException;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.net.http.NetworkException;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.FileProvider;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.SocketException;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Consumer;

import github.linsmod.sharetopc.App;
import github.linsmod.sharetopc.data.DeviceStore;
import github.linsmod.sharetopc.data.JobStore;
import github.linsmod.sharetopc.data.PairStore;
import github.linsmod.sharetopc.data.SendEntry;
import github.linsmod.sharetopc.discovery.DiscoveredDevice;
import github.linsmod.sharetopc.discovery.PairSession;
import github.linsmod.sharetopc.util.FileUtils;
import github.linsmod.sharetopc.util.LogX;

/**
 * 发送队列 + 状态机。单线程串行消费，避免并发抢连接。
 * 连接失败自动重试 1 次（间隔 2s）。
 */
public class TransferManager implements TransferProtocol.Progress {
    public static class SendJob implements TransferProtocol.Cancellable {
        public final String id;
        public final List<SendEntry> items;
        public final String host;
        public final int port;
        public final String deviceId;
        /** true = 从 PC 下载文件到手机（复用发送队列/状态机/任务记录）。 */
        public boolean pull;
        public Long createMillis;
        public Long updateMillis;
        private boolean isCancelRequested;
        /**
         * 本连接是否已发送过 JOB_INFO
         */
        public TransferClient.JobInfoResult authResult;
        @Nullable
        public SendEntry sendingItem;
        @Nullable
        public DiscoveredDevice device;

        public String shortId() {
            return this.id.length() > 8 ? id.substring(0, 8) : id;
        }

        public SendJob(String id, String deviceId, List<SendEntry> items, String host, int port) {
            this.id = id;
            this.deviceId = deviceId;
            this.items = items;
            this.host = host;
            this.port = port;
            this.createMillis = System.currentTimeMillis();
            this.updateMillis = System.currentTimeMillis();
        }

        public String displayName() {
            if (!items.isEmpty()) {
                return items.get(0).name;
            }
            return "";
        }

        @Override
        public boolean isCancelRequested() {
            return this.isCancelRequested;
        }

        @Override
        public void cancel() {
            this.isCancelRequested = true;
        }

        public String deviceAddress() {
            return this.host + ":" + this.port;
        }

        public boolean isAllSuccess() {
            for (var item :
                    items) {
                if (!item.isSuccess()) {
                    return false;
                }
            }
            return true;
        }

        public int countIdle(int reason) {
            int n = 0;
            for (var item :
                    items) {
                if (item.status == SendEntry.STATUS_IDLE) {
                    if (item.idleReason == reason) {
                        n++;
                    }
                }
            }
            return n;
        }

        public String summary() {
            return countIdle(SendEntry.IDLE_REASON_SUCCESS) + "/" + items.size();
        }

        public boolean isSending() {
            return items.stream().anyMatch(x -> x.status == SendEntry.STATUS_SENDING);
        }

        public boolean isAuthSuccess() {
            return this.authResult != null && this.authResult.accepted();
        }
    }

    private static final int MAX_ATTEMPTS = 6;          // 首次 + 5次重试
    private static final long BASE_DELAY_MS = 2000;     // 基础间隔 2s
    private static final long MAX_DELAY_MS = 60000;     // 最大上限 60s（防止无限膨胀）
    private static final Random RANDOM = new Random();  // 用于抖动
    private static final long PROGRESS_THROTTLE_MS = 500; // 进度刷新最小间隔

    private final BlockingQueue<SendJob> queue = new LinkedBlockingQueue<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final JobStore jobStore;
    private final DeviceStore deviceStore;
    private final PairStore pairStore;
    private final Context appContext;

    /**
     * 队列变更监听器：入队/出队/取消时回调，供 UI 响应式更新。
     */
    public interface QueueListener {
        void onQueueChanged(@NonNull List<SendJob> queue);
    }

    /**
     * 传输进度监听器：当前 job 的某个 entry 进度更新时回调。
     */
    public interface JobProgressListener {
        /**
         * @param job 当前正在传输的 job（含更新后的 entry 进度）
         */
        void onProgress(@NonNull SendJob job);

        /**
         * 某个 job 开始处理
         */
        void onJobStarted(@NonNull SendJob job);

        /**
         * 某个 job 处理完毕
         */
        void onJobFinished(@NonNull SendJob job);
    }

    private final List<QueueListener> queueListeners = new CopyOnWriteArrayList<>();
    private final List<JobProgressListener> progressListeners = new CopyOnWriteArrayList<>();

    /**
     * 注册队列变更监听器。
     */
    public void addQueueListener(@NonNull QueueListener listener) {
        queueListeners.add(listener);
    }

    /**
     * 取消注册队列变更监听器。
     */
    public void removeQueueListener(@NonNull QueueListener listener) {
        queueListeners.remove(listener);
    }

    /**
     * 注册进度监听器。
     */
    public void addProgressListener(@NonNull JobProgressListener listener) {
        progressListeners.add(listener);
    }

    /**
     * 取消注册进度监听器。
     */
    public void removeProgressListener(@NonNull JobProgressListener listener) {
        progressListeners.remove(listener);
    }

    /**
     * 获取当前队列快照（线程安全）。
     */
    @NonNull
    public List<SendJob> getQueueSnapshot() {
        return new ArrayList<>(queue);
    }

    /**
     * PFD 注册表：transferId → FileChannel，供重试/继续时复用内核文件句柄。
     */
    private final Map<String, PfdHandle> pfdHandles = new ConcurrentHashMap<>();

    private volatile SendJob currentJob;
    private final Map<String, SendJob> recentJobs = new HashMap<>();
    private volatile long lastProgressNotifyMs;

    public TransferManager(Context context, JobStore jobStore, DeviceStore deviceStore) {
        this.appContext = context.getApplicationContext();
        this.jobStore = jobStore;
        this.deviceStore = deviceStore;
        this.pairStore = new PairStore(context);
        worker.execute(this::consumeLoop);
    }

    /**
     * 当前正在处理的任务，无任务时返回 null。
     */
    @Nullable
    public SendJob getCurrentJob() {
        return currentJob;
    }

    /**
     * 取消当前正在执行的任务并从等待队列中移除同 jobId 的任务（用于删除分组）。
     */
    public void cancelJob(String jobId) {
        SendJob cur = currentJob;
        if (cur != null && cur.id.equals(jobId)) {
            cur.cancel();
            for (SendEntry entry : cur.items) {
                if (entry.canRequestPause()) {
                    // Mark pendingAction only. it is a flying action.
                    entry.pendingAction = SendEntry.PENDING_ACTION_END_PAUSE;
                    updateJob(cur, entry);
                }
            }
            if (queue.remove(cur))
                recentJobs.put(cur.id, cur);
        }
    }

    /**
     * 拍快照并通过监听器回调到主线程（入队/出队/取消后调用）。
     */
    private void emitQueueChanged() {
        List<SendJob> snapshot = new ArrayList<>(queue);
        mainHandler.post(() -> {
            for (QueueListener listener : queueListeners) {
                listener.onQueueChanged(snapshot);
            }
        });
    }

    public void enqueue(SendJob job) {
        if(queue.offer(job)){
            emitQueueChanged();
            LogX.d("enqueue job: " + job.displayName() + " -> " + job.host + ":" + job.port);
        }
    }

    public boolean resumeJob(String jobId) {
        SendJob job = recentJobs.get(jobId);
        if (job != null) {
            boolean canResumeJob = false;
            for (SendEntry item : job.items) {
                // 飞行中的 PAUSE → RESUME 直接覆盖，取消暂停意向
                if (item.pendingAction == SendEntry.PENDING_ACTION_END_PAUSE) {
                    item.pendingAction = SendEntry.PENDING_ACTION_NONE;
                    canResumeJob = true;
                    updateJob(job, item);
                    continue;
                }
                if (item.canResume()) {
                    canResumeJob = true;
                    // reset paused item state
                    if (item.pendingAction == SendEntry.PENDING_ACTION_END_RESUME) {
                        item.status = SendEntry.STATUS_SENDING;
                        item.pendingAction = SendEntry.PENDING_ACTION_NONE;
                        updateJob(job, item);
                    }
                }
            }
            if (canResumeJob) {
                // reset job state and enqueue（去重：已在队列中不再重复入队）
                job.isCancelRequested = false;
                if (!queue.contains(job)) {
                    queue.offer(job);
                }
                emitQueueChanged();
                LogX.d("resume job: " + job.displayName() + " -> " + job.deviceAddress());
                return true;
            }
        }
        return false;
    }

    private void consumeLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            SendJob job;
            try {
                job = queue.take();
                job.device = deviceStore.getById(job.deviceId);
                emitQueueChanged();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            try {
                process(job);
            } catch (Throwable t) {
                LogX.e("unhandled exception in consumeLoop, job="
                        + job.displayName(), t);
            }
        }
    }

    private void process(SendJob job) {
        currentJob = job;
        deviceStore.setLatestJobId(job.host, job.port, job.id);
        mainHandler.post(() -> {
            for (JobProgressListener l : progressListeners) l.onJobStarted(job);
        });
        try (TransferClient client = new TransferClient(job.deviceId, job.host, job.port)) {
            for (SendEntry item : job.items) {
                if (this.currentJob.isCancelRequested()) {
                    break;
                }
                if (!item.canResume()) {
                    continue;
                }

                // 复用连接，断开时自动重连
                for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
                    if (this.currentJob.isCancelRequested()) {
                        break;
                    }
                    try {
                        if (this.currentJob.isCancelRequested()) {
                            break;
                        }
                        job.sendingItem = item;
                        if (job.pull) {
                            pullItem(client, job, item);
                        } else {
                            sendItem(client, job, item);
                        }
                        if (item.isSuccess()) {
                            releasePfd(item.id);
                        }
                        else{
                            job.cancel(); // skip rest of all
                        }
                        break;
                    }
                    catch (Exception e) {
                        // 连接断开，关闭以便下次重连
                        client.close();
                        job.authResult = null;
                        if (this.currentJob.isCancelRequested()) {
                            break;
                        }
                        String errMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                        LogX.e("transfer attempt " + attempt + "/ " + MAX_ATTEMPTS + " for " + item.name + " failed: " + errMsg, e);
                        if (attempt >= MAX_ATTEMPTS) {
                            item.status = SendEntry.STATUS_IDLE;
                            item.idleReason = SendEntry.IDLE_REASON_NET_ERROR;
                            if (item.errorMsg == null) {
                                item.errorMsg = errMsg;
                            }
                            job.cancel(); // cancel to skip reset of items
                        }
                        if (attempt < MAX_ATTEMPTS) {
                            long exponentialDelay = BASE_DELAY_MS * (1L << (attempt - 1)); // 2^(n-1)
                            int capped = (int)Math.min(MAX_DELAY_MS, exponentialDelay);

                            // 生成 [0, capped] 之间的随机毫秒数（全抖动）
                            long delay = (capped == 0) ? 0 : RANDOM.nextInt(capped + 1);

                            // 如果想保底至少 1s（避免重试太频繁），可以再加一层：
                            // delay = Math.max(1000, delay);
                            LogX.e("Sleep "+delay+"ms before next attempt");
                            try {
                                Thread.sleep(delay);
                            } catch (InterruptedException ie) {
                                Thread.currentThread().interrupt();
                                break;
                            }
                        }
                    }
                }
            }
        } catch (Throwable t) {
            LogX.e("unexpected error in transfer for job: " + job.displayName(), t);
        } finally {
            // 处理取消后的 pending action 状态转换
            for (SendEntry item : job.items) {
                clearFlayingState(job, item);
            }
            SendJob finished = currentJob;
            recentJobs.put(currentJob.id, currentJob);
            currentJob.isCancelRequested = false;
            currentJob.sendingItem = null;
            currentJob.authResult = null;
            currentJob = null; // 及时清空，让 computeDeviceProgress 走 DB 逻辑标记 STATE_COMPLETED
            // 下载任务全部成功后自动打开已拉到本机的文件
            if (finished.pull && finished.isAllSuccess() && !finished.items.isEmpty()) {
                openPulledFiles(finished);
            }
            mainHandler.post(() -> {
                for (JobProgressListener l : progressListeners) l.onJobFinished(finished);
            });
        }
    }

    /** 打开已下载成功的文件：按条目的 postPullAction 决定打开/转发/仅下载。 */
    private void openPulledFiles(SendJob job) {
        for (SendEntry item : job.items) {
            if (!item.isSuccess() || item.localPath == null) continue;
            File f = new File(item.localPath);
            if (!f.exists()) continue;
            if (item.postPullAction == SendEntry.POST_PULL_SHARE) {
                sharePulledFile(f, item);
            } else if (item.postPullAction == SendEntry.POST_PULL_NONE) {
                // 仅下载到缓存，不自动处理
            } else {
                openPulledFile(f, item);
            }
        }
    }

    /** 供任务列表/外部调用：以"打开方式"选择器打开已下载的文件。 */
    public void openPulledFile(SendEntry item) {
        if (item == null || item.localPath == null) return;
        File f = new File(item.localPath);
        if (f.exists()) openPulledFile(f, item);
    }

    /** 供任务列表/外部调用：转发已下载的文件。 */
    public void sharePulledFile(SendEntry item) {
        if (item == null || item.localPath == null) return;
        File f = new File(item.localPath);
        if (f.exists()) sharePulledFile(f, item);
    }

    /** 解析条目 MIME：优先用 PC 上报的，缺失/兜底再按扩展名猜测。 */
    private String resolveMime(SendEntry item) {
        String mime = item.mime;
        if (mime == null || mime.isEmpty() || "application/octet-stream".equals(mime)) {
            return FileUtils.guessMimeType(item.name);
        }
        return mime;
    }

    /** 打开文件：优先"打开方式"选择器；无应用可打开时回退为分享，避免静默无响应。 */
    private void openPulledFile(File f, SendEntry item) {
        try {
            Uri uri = FileProvider.getUriForFile(appContext,
                    appContext.getPackageName() + ".fileprovider", f);
            Intent view = new Intent(Intent.ACTION_VIEW);
            view.setDataAndType(uri, resolveMime(item));
            view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            if (hasHandler(view)) {
                Intent chooser = Intent.createChooser(view, "打开方式");
                chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                appContext.startActivity(chooser);
            } else {
                // 无应用可打开 → 分享（转发/存到其他应用），并提示
                sharePulledFile(f, item, "没有可打开的应用，请选择其他方式");
            }
            LogX.d("Opened pulled file chooser: " + f.getAbsolutePath());
        } catch (ActivityNotFoundException e) {
            LogX.e("No app to open pulled file: " + f.getAbsolutePath(), e);
            mainHandler.post(() -> Toast.makeText(appContext,
                    "没有应用能打开 " + item.name, Toast.LENGTH_SHORT).show());
        } catch (Exception e) {
            LogX.e("Failed to open pulled file: " + f.getAbsolutePath(), e);
        }
    }

    /** 转发文件（分享选择器）。 */
    private void sharePulledFile(File f, SendEntry item) {
        sharePulledFile(f, item, "转发文件");
    }

    private void sharePulledFile(File f, SendEntry item, String title) {
        try {
            Uri uri = FileProvider.getUriForFile(appContext,
                    appContext.getPackageName() + ".fileprovider", f);
            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType(resolveMime(item));
            share.putExtra(Intent.EXTRA_STREAM, uri);
            share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            Intent chooser = Intent.createChooser(share, title);
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            appContext.startActivity(chooser);
            LogX.d("Shared pulled file: " + f.getAbsolutePath());
        } catch (ActivityNotFoundException e) {
            LogX.e("No app to share pulled file: " + f.getAbsolutePath(), e);
            mainHandler.post(() -> Toast.makeText(appContext,
                    "没有应用能转发 " + item.name, Toast.LENGTH_SHORT).show());
        } catch (Exception e) {
            LogX.e("Failed to share pulled file: " + f.getAbsolutePath(), e);
        }
    }

    /** 是否存在能处理该 intent 的应用。 */
    private boolean hasHandler(Intent intent) {
        return !appContext.getPackageManager()
                .queryIntentActivities(intent, 0).isEmpty();
    }

    /**
     * 把已下载的文件保存到系统"下载/ShareToPC"目录（MediaStore，无需存储权限）。
     * @param onSaved 成功回调（主线程），参数为展示用路径
     * @param onError 失败回调（主线程）
     */
    public void savePulledFile(SendEntry item, Consumer<String> onSaved, Consumer<String> onError) {
        if (item == null || item.localPath == null) {
            onError.accept("文件不存在");
            return;
        }
        File f = new File(item.localPath);
        if (!f.exists()) {
            onError.accept("文件不存在");
            return;
        }
        App.get().ioExecutor.execute(() -> {
            try {
                String savedPath = copyToDownloads(f, item);
                mainHandler.post(() -> onSaved.accept(savedPath));
            } catch (Exception e) {
                LogX.e("save pulled file failed", e);
                mainHandler.post(() -> onError.accept(e.getMessage()));
            }
        });
    }

    /** 复制到 下载/ShareToPC，返回展示路径。 */
    private String copyToDownloads(File src, SendEntry item) throws IOException {
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, src.getName());
        values.put(MediaStore.MediaColumns.MIME_TYPE, resolveMime(item));
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/ShareToPC");
        Uri uri = appContext.getContentResolver().insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (uri == null) {
            throw new IOException("无法在下载目录创建文件");
        }
        try (OutputStream os = appContext.getContentResolver().openOutputStream(uri);
             InputStream in = new FileInputStream(src)) {
            if (os == null) {
                throw new IOException("无法写入下载目录");
            }
            FileUtils.copy(in, os);
        }
        return "下载/ShareToPC/" + src.getName();
    }

    /** 删除已下载文件的本地缓存，并删除对应任务记录。 */
    public void deletePulledFile(SendEntry item) {
        if (item == null) return;
        if (item.localPath != null) {
            FileUtils.deleteQuietly(new File(item.localPath));
        }
        jobStore.deleteEntry(item.id);
    }

    private void clearFlayingState(SendJob job, SendEntry item) {
        if (item.pendingAction == SendEntry.PENDING_ACTION_END_PAUSE) {
            item.status = SendEntry.STATUS_IDLE;
            item.idleReason = SendEntry.IDLE_REASON_PAUSED;
            item.pendingAction = SendEntry.PENDING_ACTION_NONE;
        } else if (item.pendingAction == SendEntry.PENDING_ACTION_END_RESUME
                && job.isCancelRequested()) {
            // 恢复请求中途被取消，回退到暂停状态
            item.status = SendEntry.STATUS_IDLE;
            item.idleReason = SendEntry.IDLE_REASON_PAUSED;
            item.pendingAction = SendEntry.PENDING_ACTION_NONE;
        }
    }

    private void sendItem(TransferClient client, SendJob job, SendEntry item) throws Exception {
        client.connect();
        client.hello(job, true);
        // 本连接首次：发送 JOB_INFO 获取 PC 授权
        if (!job.isAuthSuccess()) {
            // 计算 job 级别汇总（含当前已在 SENDING 的 item）
            int itemCount = 0;
            long totalBytes = 0;
            for (SendEntry e : job.items) {
                if (e.status == SendEntry.STATUS_SENDING || e.canResume()) {
                    itemCount++;
                    totalBytes += e.size;
                }
            }

            // 查本地已有的 pairKey
            String existingKey = "";
            PairSession session = pairStore.getByDeviceId(client.pcDeviceId);
            if (session != null && session.key != null) {
                existingKey = session.key;
            }

            TransferClient.JobInfoResult result = client.sendJobInfoAndWaitRemoteApproval(job,
                    App.get().settingsStore.getDeviceId(),
                    existingKey,
                    job.id,
                    itemCount,
                    totalBytes,this);
            job.authResult = result;
            if (result != null && result.accepted()) {
                // 存下 PC 返回的 pairKey（可能新的或已有的）
                if (result.pairKey != null && !result.pairKey.isEmpty()) {
                    PairSession ps = session != null ? session : new PairSession();
                    ps.deviceId = client.pcDeviceId;
                    ps.key = result.pairKey;
                    ps.credits = result.credits;
                    ps.maxItemsPerJob = result.maxItemsPerJob;
                    ps.maxBytesPerJob = result.maxBytesPerJob;
                    ps.issuedAt = System.currentTimeMillis();
                    pairStore.save(ps);
                }
                LogX.d("JOB_INFO accepted by " + client.pcDeviceId
                        + " credits=" + result.credits
                        + " maxItems=" + result.maxItemsPerJob
                        + " maxBytes=" + result.maxBytesPerJob);

                // reset to sending from waiting approval
                item.status = SendEntry.STATUS_SENDING;
                item.idleReason = SendEntry.IDLE_REASON_CREATED;
                updateJob(job, item);
            } else if (result != null && result.canceled()) {
                // 对端用户取消传输
                LogX.d("JOB_INFO canceled by remote device: " + client.pcDeviceId);
                item.status = SendEntry.STATUS_IDLE;
                item.idleReason = SendEntry.IDLE_REASON_PAUSED;
                updateJob(job, item);
                job.cancel(); // 取消后续，不影响用户重试/继续
                return;
            }
            else if(result!=null){
                // 对端拒绝
                LogX.d("JOB_INFO rejected by " + client.pcDeviceId);
                item.status = SendEntry.STATUS_IDLE;
                item.idleReason = SendEntry.IDLE_REASON_REMOTE_REJECTED;
                updateJob(job, item);
                job.cancel();
                return;
            }
            else if (job.isCancelRequested()) {
                LogX.d("JOB_INFO is canceled from local. jobId=" + job.shortId());
                item.status = SendEntry.STATUS_IDLE;
                item.idleReason = SendEntry.IDLE_REASON_PAUSED;
                updateJob(job, item);
                return;
            }
        }

        item.status = SendEntry.STATUS_SENDING;
        job.updateMillis = System.currentTimeMillis();
        updateJob(job, item);

        lastProgressNotifyMs = 0;

        if (item.isText()) {
            try (var in = new ByteArrayInputStream(item.text.getBytes(StandardCharsets.UTF_8))) {
                client.sendFileFromStream(job, item, in, job.authResult.pairKey, this);
            }
        } else {
            // 优先通过 PFD→FileChannel 读文件（内核文件句柄，skip/seek 可靠，支持断点续传）
            if (item.id != null) {
                PfdHandle handle = pfdHandles.get(item.id);
                if (handle != null) {
                    try {
                        InputStream in = handle.newInputStream();
                        client.sendFileFromStream(job, item, in, job.authResult.pairKey, this);
                        // No need update state here, cancelled/progress/finish are updated in progress listener
                        return;
                    }
                    catch (SocketException ex) {
                        item.status = SendEntry.STATUS_IDLE;
                        item.idleReason = SendEntry.IDLE_REASON_PAUSED; // pause when server restart or net error
                        updateJob(job, item);
                        throw new TransferClient.NetworkError("PFD send failed for " + item.name, ex);
                    } catch (IOException e) {
                        item.status = SendEntry.STATUS_IDLE;
                        item.idleReason = SendEntry.IDLE_REASON_IO_ERROR;
                        updateJob(job, item);
                        throw new IOException("PFD send failed for " + item.name, e);
                    }
                }
            }
            item.status = SendEntry.STATUS_IDLE;
            item.idleReason = SendEntry.IDLE_REASON_IO_ERROR;
            updateJob(job, item);
            throw new IOException("Pfd Not available: " + item.name);
        }
    }

    /** 下载单条：连接 → HELLO → PULL_REQUEST → 写缓存并上报进度（无 JOB_INFO 授权，由 PC 安全规则把关）。 */
    private void pullItem(TransferClient client, SendJob job, SendEntry item) throws Exception {
        client.connect();
        client.hello(job, true);
        item.status = SendEntry.STATUS_SENDING;
        item.idleReason = SendEntry.IDLE_REASON_CREATED;
        job.updateMillis = System.currentTimeMillis();
        updateJob(job, item);
        lastProgressNotifyMs = 0;
        TransferClient.PullAck ack = client.pullFile(job, item, this);
        // PC 显式拒绝（PULL_ACK status=REJECTED）：标记"远程拒绝"，不重试
        if (ack != null && ack.rejected()) {
            item.status = SendEntry.STATUS_IDLE;
            item.idleReason = SendEntry.IDLE_REASON_REMOTE_REJECTED;
            if (item.errorMsg == null) {
                item.errorMsg = ack.msg;
            }
            updateJob(job, item);
            job.cancel();
        }
    }

    /**
     * 提交一个"从 PC 下载文件"的任务（复用发送队列/状态机/任务记录，direction=下载）。
     * @param postPullAction 完成后动作：POST_PULL_OPEN / POST_PULL_SHARE / POST_PULL_NONE。
     * @return 新建的任务；enqueue 失败时返回 null。
     */
    @Nullable
    public SendJob enqueuePull(String deviceId, String host, int port,
                               String name, long size, String mime, String remotePath,
                               int postPullAction) {
        if (deviceId == null || host == null || remotePath == null) return null;
        SendEntry e = new SendEntry();
        e.id = UUID.randomUUID().toString();
        e.objectType = SendEntry.TYPE_FILE;
        e.direction = SendEntry.DIRECTION_PULL;
        e.postPullAction = postPullAction;
        e.name = name;
        e.size = Math.max(0, size);
        e.mime = mime != null ? mime : FileUtils.guessMimeType(name);
        e.remotePath = remotePath;
        e.timeMillis = System.currentTimeMillis();
        e.status = SendEntry.STATUS_IDLE;
        e.idleReason = SendEntry.IDLE_REASON_CREATED;
        e.host = host;
        e.port = port;
        List<SendEntry> items = new ArrayList<>();
        items.add(e);

        SendJob job = new SendJob(UUID.randomUUID().toString(), deviceId, items, host, port);
        job.pull = true;
        jobStore.addJobEntry(e);
        jobStore.upsertJob(job);
        deviceStore.setLatestJobId(host, port, job.id);
        enqueue(job);
        return job;
    }

    /**
     * 更新历史记录状态（发送成功后 / 取消 / 失败）。targetName 不覆盖，提交时已正确设置。
     */
    private void updateJob(SendJob job, SendEntry item) {
        long now = System.currentTimeMillis();
        item.updateMillis = now;
        job.updateMillis = now;
        jobStore.updateJobEntry(item.id, job.host, job.port,
                null, item.status, item.sentBytes, item.totalBytes, item.idleReason, item.errorMsg, now);
        jobStore.upsertJob(job);
    }

    public void onCanceled(SendEntry entry, long sent, long total) {
        entry.sentBytes = sent;
        entry.totalBytes = total;
        entry.status = SendEntry.STATUS_IDLE;
        entry.idleReason = SendEntry.IDLE_REASON_PAUSED;
        updateJob(currentJob, entry);
        SendJob job = currentJob;
        if (job != null) {
            mainHandler.post(() -> {
                for (JobProgressListener l : progressListeners) l.onProgress(job);
            });
        }
    }

    @Override
    public void onFinish(SendEntry entry, long sentBytes, long totalBytes) {
        entry.sentBytes = sentBytes;
        entry.totalBytes = totalBytes;
        entry.status = SendEntry.STATUS_IDLE;
        entry.idleReason = SendEntry.IDLE_REASON_SUCCESS;
        updateJob(currentJob, entry);
        if (entry.isPull() && entry.localPath != null) {
            jobStore.updateLocalPath(entry.id, entry.localPath);
        }
        SendJob job = currentJob;
        if (job != null) {
            mainHandler.post(() -> {
                for (JobProgressListener l : progressListeners) l.onProgress(job);
            });
        }
    }

    @Override
    public void onWaitingApproval(SendEntry entry) {
        entry.status = SendEntry.STATUS_IDLE;
        entry.idleReason = SendEntry.IDLE_REASON_WAITING_REMOTE_APPROVAL;
        updateJob(currentJob, entry);
        SendJob job = currentJob;
        if (job != null) {
            mainHandler.post(() -> {
                for (JobProgressListener l : progressListeners) l.onProgress(job);
            });
        }
    }

    public void onProgress(SendEntry entry, long sent, long total) {
        SendEntry old = jobStore.getSendEntry(entry.id);

        entry.sentBytes = sent;
        entry.totalBytes = total;
        entry.status = SendEntry.STATUS_SENDING;
        entry.idleReason = SendEntry.IDLE_REASON_CREATED;
        updateJob(currentJob, entry);

        long now = System.currentTimeMillis();
        var progressed = old.sentBytes != entry.sentBytes || old.status!=entry.status|| old.idleReason!=entry.idleReason;
        if (!progressed || now - lastProgressNotifyMs < PROGRESS_THROTTLE_MS) {
            return;
        }
        lastProgressNotifyMs = now;

        // 通知进度监听器（设备卡片实时更新进度条）
        SendJob job = currentJob;
        if (job != null) {
            mainHandler.post(() -> {
                for (JobProgressListener l : progressListeners) l.onProgress(job);
            });
        }
    }
    // ---- PFD 管理：内核文件句柄，支持可靠的 skip/seek 断点续传 ----

    /**
     * 持有 PFD 及其 FileChannel，每次发送前重置位置。
     */
    private static class PfdHandle {
        final ParcelFileDescriptor pfd;
        final FileChannel channel;
        /**
         * 保持引用防止 GC finalizer 意外关闭 FileDescriptor。
         */
        @SuppressWarnings("unused")
        private final FileInputStream fisGuard;

        PfdHandle(ParcelFileDescriptor pfd) throws IOException {
            this.pfd = pfd;
            this.fisGuard = new FileInputStream(pfd.getFileDescriptor());
            this.channel = fisGuard.getChannel();
        }

        /**
         * 重置文件位置到 0 并返回新的 InputStream（不关闭底层 channel）。
         * 管道（流式 content，不可 seek）跳过 seek：新管道数据天然从 0 开始。
         */
        InputStream newInputStream() throws IOException {
            try {
                channel.position(0);
            } catch (IOException e) {
                // 非可 seek 管道（如流式分享 content）：不 seek，直接从当前位置读
                LogX.d("Pfd not seekable, reading stream as-is");
            }
            InputStream raw = Channels.newInputStream(channel);
            return new FilterInputStream(raw) {
                @Override
                public void close() throws IOException {
                    // 不关闭底层 channel/PFD
                }
            };
        }

        void close() {
            try {
                channel.close();
            } catch (Exception ignored) {
            }
            try {
                fisGuard.close();
            } catch (Exception ignored) {
            }
            try {
                pfd.close();
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * 为 SendEntry 注册 PFD（如果尚未注册）。
     */
    public void registerPfdIfNeeded(String name, String transferId, ParcelFileDescriptor pfd) {
        pfdHandles.computeIfAbsent(transferId, id -> {
            try {
                return new PfdHandle(pfd);
            } catch (Exception e) {
                LogX.e("Failed to create PfdHandle for " + name, e);
                return null;
            }
        });
    }

    /**
     * 释放指定 transferId 的 PFD。
     */
    private void releasePfd(String transferId) {
        PfdHandle handle = pfdHandles.remove(transferId);
        if (handle != null) {
            handle.close();
        }
    }

    /**
     * 查询指定 transferId 是否仍有 PFD 可用（用于 UI 判断是否允许重试/继续）。
     */
    public boolean hasPfd(String transferId) {
        return transferId != null && pfdHandles.containsKey(transferId);
    }
}
