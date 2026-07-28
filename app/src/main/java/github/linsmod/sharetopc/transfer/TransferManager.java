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
import github.linsmod.sharetopc.data.TransferRecord;
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
        public final String deviceName;
        private boolean isCancelRequested;
        private boolean isCancelled;

        public SendJob(String id, List<SendEntry> items, String host, int port,
                       String deviceName) {
            this.id = id;
            this.items = items;
            this.host = host;
            this.port = port;
            this.deviceName = deviceName;
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
            items.forEach(TransferRecord::cancel);
        }

        public boolean isCancelled(){
            return this.isCancelled;
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

    /** 从等待队列中移除指定 jobId 的任务（不影响当前正在执行的任务）。 */
    public void removeFromQueue(String jobId) {
        queue.removeIf(job -> job.id.equals(jobId));
        emitQueueChanged();
    }

    /** 取消当前正在执行的任务并从等待队列中移除同 jobId 的任务（用于删除分组）。 */
    public void cancelAndRemoveJob(String jobId) {
        SendJob cur = currentJob;
        if (cur != null && cur.id.equals(jobId)) {
            cur.cancel();
        }
        removeFromQueue(jobId);
    }

    /** 暂停当前或队列中指定 jobId 的任务（走 cancel 请求协议，会发包通知 PC 端）。 */
    public void cancelJob(String jobId) {
        SendJob cur = currentJob;
        if (cur != null && cur.id.equals(jobId)) {
            cur.cancel(); // 触发 TransferProtocol.writeContentLoop 发送 CANCEL 帧
        }
        for (SendJob job : queue) {
            if (job.id.equals(jobId)) {
                job.cancel(); // 标记取消，worker 取到时自动跳过
            }
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
    public void requestPause(TransferRecord record) {
        if (record instanceof SendEntry) {
            SendEntry sendEntry = (SendEntry) record;
            if (!sendEntry.canRequestPause()) return;
            sendEntry.pendingAction = TransferRecord.PENDING_ACTION_END_PAUSE;
            App.get().jobStore.updateStatus(sendEntry.id, sendEntry.status, sendEntry.idleReason);
        }
    }

    /**
     * 重发一条历史记录（完成后原地更新该记录状态）。
     */
    public void retry(TransferRecord record) {
        if(record instanceof SendEntry){
            SendEntry sendEntry = (SendEntry) record;
            if(!sendEntry.canResume()){
                return;
            }
            sendEntry.status = TransferRecord.STATUS_IDLE;
            sendEntry.pendingAction = TransferRecord.PENDING_ACTION_END_RESUME;
            App.get().jobStore.updateStatus(sendEntry.id,sendEntry.status,sendEntry.idleReason);
        }
    }

    public void enqueue(String id, List<SendEntry> items, String host, int port,
                        String deviceName) {
        SendJob job = new SendJob(id,items, host, port, deviceName);
        queue.offer(job);
        emitQueueChanged();
        LogX.d("enqueue job: " + job.displayName() + " -> " + host + ":" + port);
    }

    private void consumeLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            SendJob job;
            try {
                job = queue.take();
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
        String error = null;
        deviceStore.setLatestJobId(job.host, job.port, job.id);
        mainHandler.post(() -> {
            for (JobProgressListener l : progressListeners) l.onJobStarted(job);
        });

        try {
            for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
                if (this.currentJob.isCancelRequested()) {
                    break;
                }
                var client = new TransferClient();
                try {
                    client.connect(job.host, job.port);
                    if (this.currentJob.isCancelRequested()) {
                        break;
                    }
                    client.hello();
                    for (SendEntry item : job.items) {
                        if (this.currentJob.isCancelRequested()||item.isCancelRequested()) {
                            break;
                        }
                        if (item.isSuccess()) {
                            continue;
                        }
                        item.status = TransferRecord.STATUS_SENDING;
                        jobStore.updateStatus(item.id, item.status, item.idleReason);
                        jobStore.updateProgress(item.id, 0, item.size);
                        // 每个 item 重置节流计时器，确保首个进度回调不被上一个文件抑制
                        lastProgressNotifyMs = 0;
                        try {
                            sendItem(client, job, item);
                            item.status = TransferRecord.STATUS_IDLE;
                            item.idleReason = TransferRecord.IDLE_REASON_SUCCESS;
                        } catch (Exception e) {
                            item.status = TransferRecord.STATUS_IDLE;
                            // sendItem 已区分 IO 错误（PFD 丢失/流不可读），不覆写为 NET_ERROR
                            if (item.idleReason != TransferRecord.IDLE_REASON_IO_ERROR) {
                                item.idleReason = TransferRecord.IDLE_REASON_NET_ERROR;
                            }
                            if (item.errorMsg == null) {
                                item.errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                            }
                            throw e;
                        } finally {
                            updateJob(job, item);
                        }
                        if (item.isSuccess()) {
                            releasePfd(item.id);
                        }
                    }
                    if (this.currentJob.isCancelRequested()) {
                        break;
                    }
                    error = null;
                    break;
                } catch (Exception e) {
                    if (this.currentJob.isCancelRequested()) {
                        break;
                    }
                    error = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                    LogX.e("transfer attempt " + attempt + " failed: " + error, e);
                    if (attempt < MAX_ATTEMPTS) {
                        try {
                            Thread.sleep(RETRY_DELAY_MS);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                } finally {
                    client.close();
                }
            }
        } catch (Throwable t) {
            // 防御：connect/send 阶段出现未预期异常也不会崩溃
            LogX.e("unexpected error in transfer for job: " + job.displayName(), t);
            error = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
        } finally {
            // 处理取消后的 pending action 状态转换
            for (SendEntry item : job.items) {
                if (item.pendingAction == TransferRecord.PENDING_ACTION_END_PAUSE) {
                    // CANCEL 帧已发送，转为暂停状态
                    item.status = TransferRecord.STATUS_IDLE;
                    item.idleReason = TransferRecord.IDLE_REASON_PAUSED;
                    item.pendingAction = TransferRecord.PENDING_ACTION_NONE;
                    updateJob(job, item);
                } else if (item.pendingAction == TransferRecord.PENDING_ACTION_END_RESUME
                        && job.isCancelRequested()) {
                    // 恢复请求中途被取消，回退到暂停状态
                    item.status = TransferRecord.STATUS_IDLE;
                    item.idleReason = TransferRecord.IDLE_REASON_PAUSED;
                    item.pendingAction = TransferRecord.PENDING_ACTION_NONE;
                    updateJob(job, item);
                }
            }
            SendJob finished = currentJob;
            currentJob = null; // 及时清空，让 computeDeviceProgress 走 DB 逻辑标记 STATE_COMPLETED
            mainHandler.post(() -> {
                for (JobProgressListener l : progressListeners) l.onJobFinished(finished);
            });
        }
    }

    private void sendItem(TransferClient client, SendJob job, SendEntry item) throws Exception {
        if (item.isText()) {
            client.sendText(item.text);
        } else {
            // 优先通过 PFD→FileChannel 读文件（内核文件句柄，skip/seek 可靠，支持断点续传）
            if (item.id != null) {
                PfdHandle handle = pfdHandles.get(item.id);
                if (handle != null) {
                    try {
                        InputStream in = handle.newInputStream();
                    client.sendFileFromStream(item, in,
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
                    item.idleReason = TransferRecord.IDLE_REASON_IO_ERROR;
                    item.errorMsg = "Pfd丢失，无法读取：" + item.name;
                    throw new Exception(item.errorMsg);
                }
                client.sendFileFromStream(item, in,
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
                client.sendFile(item, file,
                        (sent, total) -> {
                            item.sentBytes = sent;
                            item.totalBytes = total;
                            onProgress(item);
                        });
                    return;
                }
            }
        // 所有源都不可用 → IO 错误
        item.idleReason = TransferRecord.IDLE_REASON_IO_ERROR;
        item.errorMsg = "无可读取的源：" + item.name;
        throw new Exception(item.errorMsg);
    }
    }

    /** 更新历史记录状态（发送成功后 / 取消 / 失败）。 */
    private void updateJob(SendJob job, SendEntry item) {
        jobStore.updateJobEntry(item.id, job.host, job.port,
                job.deviceName, item.status, item.idleReason, item.errorMsg);
    }

    private void onProgress(SendEntry entry) {
        TransferRecord old = jobStore.getSendEntry(entry.id);
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
