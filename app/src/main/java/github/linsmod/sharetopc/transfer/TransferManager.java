package github.linsmod.sharetopc.transfer;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;

import github.linsmod.sharetopc.App;
import github.linsmod.sharetopc.data.DeviceStore;
import github.linsmod.sharetopc.data.JobStore;
import github.linsmod.sharetopc.data.SendEntry;
import github.linsmod.sharetopc.discovery.DiscoveredDevice;
import github.linsmod.sharetopc.util.LogX;

/**
 * 发送队列 + 状态机。单线程串行消费，避免并发抢连接。
 * 连接失败自动重试 1 次（间隔 2s）。
 */
public class TransferManager {
    public static class SendJob implements TransferProtocol.Cancellable {
        public final String id;
        public final List<SendEntry> items;
        public final String host;
        public final int port;
        public final String deviceId;
        public Long createMillis;
        public Long updateMillis;
        private boolean isCancelRequested;
        private boolean isCancelled;
        @Nullable
        public SendEntry sendingItem;
        @Nullable
        public DiscoveredDevice device;
        public SendJob(String id,String deviceId, List<SendEntry> items, String host, int port) {
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
        public void onCancelled() {
            this.isCancelled= true;
        }

        @Override
        public void cancel() {
            this.isCancelRequested = true;
        }

        public boolean isCancelled(){
            return this.isCancelled;
        }

        public String deviceAddress() {
            return this.host+":"+this.port;
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
        public int countIdle(int reason){
            int n = 0;
            for (var item :
                    items) {
                if (item.status==SendEntry.STATUS_IDLE) {
                    if( item.idleReason==reason){
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
            return false;
        }
    }

    private static final int MAX_ATTEMPTS = 2; // 首次 + 重试 1 次
    private static final long RETRY_DELAY_MS = 2000;
    private static final long PROGRESS_THROTTLE_MS = 500; // 进度刷新最小间隔

    private final BlockingQueue<SendJob> queue = new LinkedBlockingQueue<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final JobStore jobStore;
    private final DeviceStore deviceStore;
    private final Context appContext;

    /** 队列变更监听器：入队/出队/取消时回调，供 UI 响应式更新。 */
    public interface QueueListener {
        void onQueueChanged(@NonNull List<SendJob> queue);
    }

    /** 传输进度监听器：当前 job 的某个 entry 进度更新时回调。 */
    public interface JobProgressListener {
        /** @param job 当前正在传输的 job（含更新后的 entry 进度） */
        void onProgress(@NonNull SendJob job);
        /** 某个 job 开始处理 */
        void onJobStarted(@NonNull SendJob job);
        /** 某个 job 处理完毕 */
        void onJobFinished(@NonNull SendJob job);
    }

    private final List<QueueListener> queueListeners = new CopyOnWriteArrayList<>();
    private final List<JobProgressListener> progressListeners = new CopyOnWriteArrayList<>();

    /** 注册队列变更监听器。 */
    public void addQueueListener(@NonNull QueueListener listener) {
        queueListeners.add(listener);
    }

    /** 取消注册队列变更监听器。 */
    public void removeQueueListener(@NonNull QueueListener listener) {
        queueListeners.remove(listener);
    }

    /** 注册进度监听器。 */
    public void addProgressListener(@NonNull JobProgressListener listener) {
        progressListeners.add(listener);
    }

    /** 取消注册进度监听器。 */
    public void removeProgressListener(@NonNull JobProgressListener listener) {
        progressListeners.remove(listener);
    }

    /** 获取当前队列快照（线程安全）。 */
    @NonNull
    public List<SendJob> getQueueSnapshot() {
        return new ArrayList<>(queue);
    }

    /** PFD 注册表：transferId → FileChannel，供重试/继续时复用内核文件句柄。 */
    private final Map<String, PfdHandle> pfdHandles = new ConcurrentHashMap<>();

    private volatile SendJob currentJob;
    private volatile Map<String,SendJob> recentJobs = new HashMap<>();
    private volatile long lastProgressNotifyMs;

    public TransferManager(Context context, JobStore jobStore, DeviceStore deviceStore) {
        this.appContext = context.getApplicationContext();
        this.jobStore = jobStore;
        this.deviceStore = deviceStore;
        worker.execute(this::consumeLoop);
    }

    /** 当前正在处理的任务，无任务时返回 null。 */
    @Nullable
    public SendJob getCurrentJob() {
        return currentJob;
    }

    /** 取消当前正在执行的任务并从等待队列中移除同 jobId 的任务（用于删除分组）。 */
    public void cancelJob(String jobId) {
        SendJob cur = currentJob;
        if (cur != null && cur.id.equals(jobId)) {
            cur.cancel();
            for (SendEntry entry : cur.items) {
                if (entry.canRequestPause()) {
                    requestPause(entry);
                }
            }
            if(queue.remove(cur))
                recentJobs.put(cur.id,cur);
        }
    }

    /** 拍快照并通过监听器回调到主线程（入队/出队/取消后调用）。 */
    private void emitQueueChanged() {
        List<SendJob> snapshot = new ArrayList<>(queue);
        mainHandler.post(() -> {
            for (QueueListener listener : queueListeners) {
                listener.onQueueChanged(snapshot);
            }
        });
    }

    /**
     * 请求暂停一条记录。设置 PENDING_ACTION_END_PAUSE 中间状态，
     * 等待 TransferProtocol 实际发送 CANCEL 帧后在 process() 中转为 IDLE_REASON_PAUSED。
     */
    public void requestPause(SendEntry record) {
        if (record instanceof SendEntry) {
            SendEntry sendEntry = (SendEntry) record;
            if (!sendEntry.canRequestPause()) return;
            sendEntry.pendingAction = SendEntry.PENDING_ACTION_END_PAUSE;
            App.get().jobStore.updateStatus(sendEntry.id, sendEntry.status, sendEntry.idleReason, sendEntry.pendingAction);
        }
    }

    public void enqueue(SendJob job) {
        queue.offer(job);
        emitQueueChanged();
        LogX.d("enqueue job: " + job.displayName() + " -> " + job.host + ":" + job.port);
    }

    public boolean resumeJob(String jobId){
        SendJob job = recentJobs.get(jobId);
        if(job!=null){
            // reset states
            boolean canResumeJob = false;
            for (var item :
                    job.items) {
                if(item.canResume()){
                    canResumeJob = true;
                    // reset paused item state
                    if(item.pendingAction==SendEntry.PENDING_ACTION_END_RESUME){
                        item.status = SendEntry.STATUS_SENDING;
                        item.pendingAction = SendEntry.PENDING_ACTION_NONE;
                        item.updateMillis = System.currentTimeMillis();
                        job.updateMillis = System.currentTimeMillis();
                        App.get().jobStore.updateStatus(item.id,item.status,item.idleReason, item.pendingAction);
                    }
                }
            }
            if(canResumeJob){
                // reset job state and enqueue
                job.isCancelRequested = false;
                queue.offer(job);
                emitQueueChanged();
                LogX.d("resume job: " + job.displayName() + " -> " + job.deviceAddress() );
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
                        + (job != null ? job.displayName() : "null"), t);
            }
        }
    }
    private void process(SendJob job) {
        currentJob = job;
        deviceStore.setLatestJobId(job.host, job.port, job.id);
        mainHandler.post(() -> {
            for (JobProgressListener l : progressListeners) l.onJobStarted(job);
        });

        TransferClient client = new TransferClient(job.host,job.port);
        try {
            for (SendEntry item : job.items) {
                if (this.currentJob.isCancelRequested()) {
                    break;
                }
                if (!item.canResume()) {
                    continue;
                }
                // 走到哪标记到哪
                item.status = SendEntry.STATUS_SENDING;
                item.updateMillis = System.currentTimeMillis();
                job.updateMillis = System.currentTimeMillis();
                jobStore.updateStatus(item.id, item.status, item.idleReason, item.pendingAction);
                jobStore.updateProgress(item.id, 0, item.size);
                lastProgressNotifyMs = 0;

                // 复用连接，断开时自动重连
                for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
                    if (this.currentJob.isCancelRequested()) {
                        break;
                    }
                    try {
                        if (this.currentJob.isCancelRequested()) {
                            break;
                        }
                        job.sendingItem =item;
                        sendItem(client, job, item);
                        item.status = SendEntry.STATUS_IDLE;
                        item.idleReason = SendEntry.IDLE_REASON_SUCCESS;
                        break;
                    } catch (Exception e) {
                        // 连接断开，关闭以便下次重连
                        if (client != null) {
                            client.close();
                        }
                        if (this.currentJob.isCancelRequested()) {
                            break;
                        }
                        String errMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                        LogX.e("transfer attempt " + attempt + " for " + item.name + " failed: " + errMsg, e);
                        if (attempt >= MAX_ATTEMPTS) {
                            item.status = SendEntry.STATUS_IDLE;
                            if (item.idleReason != SendEntry.IDLE_REASON_IO_ERROR) {
                                item.idleReason = SendEntry.IDLE_REASON_NET_ERROR;
                            }
                            if (item.errorMsg == null) {
                                item.errorMsg = errMsg;
                            }
                            job.cancel(); // cancel to skip reset of items
                        }
                        if (attempt < MAX_ATTEMPTS) {
                            try {
                                Thread.sleep(RETRY_DELAY_MS);
                            } catch (InterruptedException ie) {
                                Thread.currentThread().interrupt();
                                break;
                            }
                        }
                    }
                }
                updateJob(job, item);
                if (item.isSuccess()) {
                    releasePfd(item.id);
                }
            }
        } catch (Throwable t) {
            LogX.e("unexpected error in transfer for job: " + job.displayName(), t);
        } finally {
            if (client != null) {
                client.close();
            }
            // 处理取消后的 pending action 状态转换
            for (SendEntry item : job.items) {
                if (item.pendingAction == SendEntry.PENDING_ACTION_END_PAUSE) {
                    // CANCEL 帧已发送，转为暂停状态
                    item.status = SendEntry.STATUS_IDLE;
                    item.idleReason = SendEntry.IDLE_REASON_PAUSED;
                    item.pendingAction = SendEntry.PENDING_ACTION_NONE;
                    updateJob(job, item);
                } else if (item.pendingAction == SendEntry.PENDING_ACTION_END_RESUME
                        && job.isCancelRequested()) {
                    // 恢复请求中途被取消，回退到暂停状态
                    item.status = SendEntry.STATUS_IDLE;
                    item.idleReason = SendEntry.IDLE_REASON_PAUSED;
                    item.pendingAction = SendEntry.PENDING_ACTION_NONE;
                    updateJob(job, item);
                }
            }
            SendJob finished = currentJob;
            recentJobs.put(currentJob.id,currentJob);
            currentJob.sendingItem = null;
            currentJob = null; // 及时清空，让 computeDeviceProgress 走 DB 逻辑标记 STATE_COMPLETED
            mainHandler.post(() -> {
                for (JobProgressListener l : progressListeners) l.onJobFinished(finished);
            });
        }
    }

    private void sendItem(TransferClient client, SendJob job, SendEntry item) throws Exception {
        client.connect();
        if (item.isText()) {
            client.sendText(item.text, job.id);
        } else {
            // 优先通过 PFD→FileChannel 读文件（内核文件句柄，skip/seek 可靠，支持断点续传）
            if (item.id != null) {
                PfdHandle handle = pfdHandles.get(item.id);
                if (handle != null) {
                    try {
                        InputStream in = handle.newInputStream();
                    client.sendFileFromStream(job,item, in,
                            (sent, total) -> {
                                item.sentBytes = sent;
                                item.totalBytes = total;
                                onProgress(item);
                            });
                    return;
                } catch (Exception e) {
                    LogX.e("PFD send failed for " + item.name + ", fallback to URI", e);
                }
            }
        }
        // 次选：从 content:// URI 直接读流发送
        if (item.contentUri != null) {
            Uri uri = Uri.parse(item.contentUri);
            try (InputStream in = appContext.getContentResolver().openInputStream(uri)) {
                if (in == null) {
                    item.idleReason = SendEntry.IDLE_REASON_IO_ERROR;
                    item.errorMsg = "Pfd丢失，无法读取：" + item.name;
                    throw new Exception(item.errorMsg);
                }
                client.sendFileFromStream(job,item, in,
                        (sent, total) -> {
                            item.sentBytes = sent;
                            item.totalBytes = total;
                            onProgress(item);
                        });
                return;
            }
        }
        // 历史兼容：从本地 inbox 文件发送
        if (item.localPath != null) {
            File file = new File(item.localPath);
            if (file.exists()) {
                client.sendFile(job,item, file,
                        (sent, total) -> {
                            item.sentBytes = sent;
                            item.totalBytes = total;
                            onProgress(item);
                        });
                    return;
                }
            }
        // 所有源都不可用 → IO 错误
        item.idleReason = SendEntry.IDLE_REASON_IO_ERROR;
        item.errorMsg = "无可读取的源：" + item.name;
        throw new Exception(item.errorMsg);
    }
    }

    /** 更新历史记录状态（发送成功后 / 取消 / 失败）。targetName 不覆盖，提交时已正确设置。 */
    private void updateJob(SendJob job, SendEntry item) {
        long now = System.currentTimeMillis();
        item.updateMillis = now;
        job.updateMillis = now;
        jobStore.updateJobEntry(item.id, job.host, job.port,
                null, item.status, item.idleReason, item.errorMsg, item.pendingAction, now);
        jobStore.upsertJob(job);
    }

    private void onProgress(SendEntry entry) {
        SendEntry old = jobStore.getSendEntry(entry.id);
        jobStore.updateProgress(entry.id, entry.sentBytes, entry.totalBytes);
        long now = System.currentTimeMillis();
        var progressed = old.percent() != entry.percent();
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

    /** 持有 PFD 及其 FileChannel，每次发送前重置位置。 */
    private static class PfdHandle {
        final ParcelFileDescriptor pfd;
        final FileChannel channel;
        /** 保持引用防止 GC finalizer 意外关闭 FileDescriptor。 */
        @SuppressWarnings("unused")
        private final FileInputStream fisGuard;

        PfdHandle(ParcelFileDescriptor pfd) throws IOException {
            this.pfd = pfd;
            this.fisGuard = new FileInputStream(pfd.getFileDescriptor());
            this.channel = fisGuard.getChannel();
        }

        /** 重置文件位置到 0 并返回新的 InputStream（不关闭底层 channel）。 */
        InputStream newInputStream() throws IOException {
            channel.position(0);
            InputStream raw = Channels.newInputStream(channel);
            return new FilterInputStream(raw) {
                @Override public void close() throws IOException {
                    // 不关闭底层 channel/PFD
                }
            };
        }

        void close() {
            try { channel.close(); } catch (Exception ignored) {}
            try { fisGuard.close(); } catch (Exception ignored) {}
            try { pfd.close(); } catch (Exception ignored) {}
        }
    }

    /** 为 SendEntry 注册 PFD（如果尚未注册）。 */
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

    /** 释放指定 transferId 的 PFD。 */
    private void releasePfd(String transferId) {
        PfdHandle handle = pfdHandles.remove(transferId);
        if (handle != null) {
            handle.close();
        }
    }

    /** 查询指定 transferId 是否仍有 PFD 可用（用于 UI 判断是否允许重试/继续）。 */
    public boolean hasPfd(String transferId) {
        return transferId != null && pfdHandles.containsKey(transferId);
    }
}
