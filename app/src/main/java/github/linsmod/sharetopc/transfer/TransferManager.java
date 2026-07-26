package github.linsmod.sharetopc.transfer;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;

import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

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
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;

import github.linsmod.sharetopc.data.HistoryStore;
import github.linsmod.sharetopc.data.PendingContent;
import github.linsmod.sharetopc.data.TransferRecord;
import github.linsmod.sharetopc.util.LogX;

/**
 * 发送队列 + 状态机。单线程串行消费，避免并发抢连接。
 * 连接失败自动重试 1 次（间隔 2s）。
 */
public class TransferManager {

    public enum State {
        IDLE, CONNECTING, SENDING, DONE, FAILED, CANCELLED
    }

    public static class SendJob {
        public final String id = UUID.randomUUID().toString();
        public final List<PendingContent> items;
        public final String host;
        public final int port;
        public final String deviceName;
        /** 关联的历史记录 id（重试/补发）：完成时原地更新该记录，不再新增条目。 */
        public final String recordId;

        public SendJob(List<PendingContent> items, String host, int port,
                       String deviceName) {
            this(items, host, port, deviceName, null);
        }

        public SendJob(List<PendingContent> items, String host, int port,
                       String deviceName, String recordId) {
            this.items = items;
            this.host = host;
            this.port = port;
            this.deviceName = deviceName;
            this.recordId = recordId;
            // 为每个 item 分配唯一 transferId，支持同名文件多次传输
            for (PendingContent item : items) {
                if (item.transferId == null) {
                    item.transferId = UUID.randomUUID().toString();
                }
            }
        }

        public String displayName() {
            if (!items.isEmpty()) {
                return items.get(0).name;
            }
            return "";
        }
    }

    private static final int MAX_ATTEMPTS = 2; // 首次 + 重试 1 次
    private static final long RETRY_DELAY_MS = 2000;
    private static final long PROGRESS_THROTTLE_MS = 500; // 进度刷新最小间隔

    private final BlockingQueue<SendJob> queue = new LinkedBlockingQueue<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final List<TransferListener> listeners = new CopyOnWriteArrayList<>();
    private final HistoryStore historyStore;
    private final Context appContext;

    /** 队列状态 LiveData：入队/出队/取消时更新，供 UI 响应式观察。 */
    private final MutableLiveData<List<SendJob>> queueJobsLiveData = new MutableLiveData<>();

    /** PFD 注册表：transferId → FileChannel，供重试/继续时复用内核文件句柄。 */
    private final Map<String, PfdHandle> pfdHandles = new ConcurrentHashMap<>();

    private volatile State state = State.IDLE;
    private volatile SendJob currentJob;
    private volatile TransferClient activeClient;
    private volatile boolean cancelRequested;
    private volatile long lastProgressNotifyMs;

    public TransferManager(Context context, HistoryStore historyStore) {
        this.appContext = context.getApplicationContext();
        this.historyStore = historyStore;
        worker.execute(this::consumeLoop);
    }

    public void addListener(TransferListener listener) {
        listeners.add(listener);
    }

    public void removeListener(TransferListener listener) {
        listeners.remove(listener);
    }

    public State getState() {
        return state;
    }

    /** 当前正在处理的任务，无任务时返回 null。 */
    @Nullable
    public SendJob getCurrentJob() {
        return currentJob;
    }

    /** 当前正在传输的任务的设备标识（host:port），无任务时返回 null。 */
    public String getCurrentDeviceKey() {
        SendJob job = currentJob;
        return job != null ? job.host + ":" + job.port : null;
    }

    /** 当前任务的关联历史记录 id（重试/补发时存在），否则返回 null。 */
    @Nullable
    public String getCurrentRecordId() {
        SendJob job = currentJob;
        return job != null ? job.recordId : null;
    }

    /** 当前正在传输的任务显示名（文件名或"X 个文件"），无任务时返回 null。 */
    public String getCurrentJobName() {
        SendJob job = currentJob;
        return job != null ? job.displayName() : null;
    }

    /** 获取队列状态 LiveData。 */
    public LiveData<List<SendJob>> getQueueJobsLiveData() {
        return queueJobsLiveData;
    }

    /** 拍快照并通过 LiveData 发出（入队/出队/取消后调用）。 */
    private void emitQueueChanged() {
        List<SendJob> snapshot = new ArrayList<>(queue);
        mainHandler.post(() -> queueJobsLiveData.setValue(snapshot));
    }

    /** 取消当前正在传输的任务。
     * 只设置取消标志，由 worker 线程在 chunk 循环中检测到后自行发送 CANCEL 帧并关闭连接。 */
    public void cancelCurrentJob() {
        cancelRequested = true;
        TransferClient c = activeClient;
        if (c != null) {
            c.cancel();
        }
    }

    /** 取消队列中指定 id 的任务（不影响正在执行的）。 */
    public boolean cancelQueuedJob(String jobId) {
        for (SendJob job : queue) {
            if (job.id.equals(jobId)) {
                if (queue.remove(job)) {
                    // 更新历史记录为已取消（否则 SENDING/PENDING 记录会在设备弹窗里消失）
                    for (PendingContent item : job.items) {
                        if (job.recordId != null) {
                            // 重试/补发：按 recordId 更新原记录，同时写入 host/port
                            // （补发场景下原 PENDING 记录的 host/port 可能为空）
                            historyStore.updateStatus(job.recordId, job.host, job.port,
                                    job.deviceName, false, TransferRecord.STATUS_CANCELLED);
                        } else {
                            // 首次发送且尚未被 take 出队列→ process() 中的 recordHistory() 未执行
                            // → 没有 SENDING 记录可更新，无需操作
                        }
                    }
                    mainHandler.post(() -> {
                        for (TransferListener l : listeners) {
                            l.onHistoryChanged();
                        }
                    });
                    emitQueueChanged();
                    LogX.d("cancelled queued job: " + job.displayName());
                    return true;
                }
            }
        }
        return false;
    }

    /** 重发一条历史记录（完成后原地更新该记录状态）。 */
    public String retry(TransferRecord record) {
        PendingContent content = record.toPendingContent();
        // 从 registry 中查找未释放的 PFD，附加到 PendingContent 以支持可靠续传
        if (content.transferId != null && content.type == PendingContent.TYPE_FILE) {
            PfdHandle handle = pfdHandles.get(content.transferId);
            if (handle != null) {
                content.pfd = handle.pfd;
            }
        }
        List<PendingContent> items = new ArrayList<>();
        items.add(content);
        return enqueue(items, record.host, record.port, record.targetName, record.id);
    }

    public String enqueue(List<PendingContent> items, String host, int port,
                          String deviceName) {
        return enqueue(items, host, port, deviceName, null);
    }

    public String enqueue(List<PendingContent> items, String host, int port,
                          String deviceName, String recordId) {
        SendJob job = new SendJob(items, host, port, deviceName, recordId);
        // 注册 PFD（如果 item 携带了预打开的文件句柄）
        for (PendingContent item : items) {
            registerPfdIfNeeded(item);
        }
        queue.offer(job);
        emitQueueChanged();
        LogX.d("enqueue job: " + job.displayName() + " -> " + host + ":" + port);
        return job.id;
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
                try {
                    setState(State.FAILED, job != null ? job.displayName() : "");
                } catch (Throwable ignored) {
                }
                try {
                    setState(State.IDLE, null);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private void process(SendJob job) {
        currentJob = job;
        cancelRequested = false;
        String error = null;
        List<PendingContent> succeeded = new ArrayList<>();
        List<PendingContent> failed = new ArrayList<>();

        // 先为所有 item 创建/更新历史记录为 SENDING，确保后续即使 connect/send 失败，结算时也有记录可更新
        for (PendingContent item : job.items) {
            recordHistory(job, item, false, null);
        }

        try {
            setState(State.CONNECTING, job.displayName());

            for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
                if (cancelRequested) {
                    break;
                }
                TransferClient client = new TransferClient();
                activeClient = client;
                try {
                    client.connect(job.host, job.port);
                    if (cancelRequested) {
                        break;
                    }
                    client.hello();
                    setState(State.SENDING, job.displayName());
                    for (PendingContent item : job.items) {
                        if (cancelRequested) {
                            break;
                        }
                        if (succeeded.contains(item)) {
                            continue;
                        }
                        sendItem(client, job, item);
                        succeeded.add(item);
                        updateHistoryStatus(job, item, true, null);
                    }
                    if (cancelRequested) {
                        break;
                    }
                    error = null;
                    break;
                } catch (Exception e) {
                    if (cancelRequested) {
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
                    activeClient = null;
                }
            }
        } catch (Throwable t) {
            // 防御：connect/send 阶段出现未预期异常也不会崩溃
            LogX.e("unexpected error in transfer for job: " + job.displayName(), t);
            error = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
        } finally {
            activeClient = null;
        }

        // 结算阶段（内部有 try-catch 防护，确保走到 IDLE）
        try {
            for (PendingContent item : job.items) {
                if (!succeeded.contains(item)) {
                    if (cancelRequested) {
                        updateHistoryStatus(job, item, false, TransferRecord.STATUS_CANCELLED);
                    } else {
                        failed.add(item);
                        updateHistoryStatus(job, item, false, error);
                    }
                }
            }

            if (cancelRequested) {
                setState(State.CANCELLED, job.displayName());
                notifyJobCancelled(job.id, job.displayName());
                // 取消不释放 PFD：用户可能点"继续"重试
            } else if (failed.isEmpty()) {
                setState(State.DONE, job.displayName());
                notifyJobFinished(job.id, true, null);
                // 全部成功 → 释放 PFD
                releasePfds(job.items);
            } else {
                setState(State.FAILED, job.displayName());
                notifyJobFinished(job.id, false, error);
                // 最终失败（已重试 MAX_ATTEMPTS 次）→ 释放 PFD
                releasePfds(job.items);
            }
        } catch (Throwable t) {
            LogX.e("bookkeeping failed for job: " + job.displayName(), t);
        } finally {
            try {
                setState(State.IDLE, null);
            } catch (Throwable ignored) {
            }
            cancelRequested = false;
            currentJob = null;
            activeClient = null;
        }
    }

    private void sendItem(TransferClient client, SendJob job, PendingContent item) throws Exception {
        if (item.isText()) {
            client.sendText(item.text);
        } else {
            // 优先通过 PFD→FileChannel 读文件（内核文件句柄，skip/seek 可靠，支持断点续传）
            if (item.transferId != null) {
                PfdHandle handle = pfdHandles.get(item.transferId);
                if (handle != null) {
                    try {
                        InputStream in = handle.newInputStream();
                        client.sendFileFromStream(item.name, item.mime, item.size, in, item.transferId,
                                (sent, total) -> notifyProgress(item.name, sent, total, item.transferId));
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
                        throw new Exception("无法读取内容：" + item.name);
                    }
                    client.sendFileFromStream(item.name, item.mime, item.size, in, item.transferId,
                            (sent, total) -> notifyProgress(item.name, sent, total, item.transferId));
                    return;
                }
            }
            // 历史兼容：从本地 inbox 文件发送
            if (item.localPath != null) {
                File file = new File(item.localPath);
                if (file.exists()) {
                    client.sendFile(item.name, item.mime, file, item.transferId,
                            (sent, total) -> notifyProgress(item.name, sent, total, item.transferId));
                    return;
                }
            }
            throw new Exception("无可读取的源：" + item.name);
        }
    }

    /** 发送前插入一条初始记录或更新已有记录为 SENDING（重试/补发时复用原记录）。 */
    private void recordHistory(SendJob job, PendingContent item, boolean success, String error) {
        if (job.recordId != null) {
            // 重试/补发：更新已有记录为 SENDING
            historyStore.updateStatus(job.recordId, job.host, job.port,
                    job.deviceName, false, TransferRecord.STATUS_SENDING);
        } else {
            // 首次发送：创建新记录
            TransferRecord r = TransferRecord.sending(item, job.host, job.port,
                    job.deviceName, "");
            historyStore.add(r);
        }
        mainHandler.post(() -> {
            for (TransferListener l : listeners) {
                l.onHistoryChanged();
            }
        });
    }

    /** 更新历史记录状态（发送成功后 / 取消 / 失败）。 */
    private void updateHistoryStatus(SendJob job, PendingContent item, boolean success, String errorOrStatus) {
        if (job.recordId != null) {
            // 重试/补发：按 recordId 更新原记录
            historyStore.updateStatus(job.recordId, job.host, job.port,
                    job.deviceName, success, errorOrStatus);
        } else {
            // 首次发送：按 transferId 匹配
            historyStore.updateStatusByTransferId(item.transferId, job.host, job.port,
                    job.deviceName, success, errorOrStatus);
        }
        mainHandler.post(() -> {
            for (TransferListener l : listeners) {
                l.onHistoryChanged();
            }
        });
    }

    private void setState(State newState, String jobName) {
        state = newState;
        // 在 post 之前同步捕获 deviceKey，避免主线程回调时 currentJob 已被清空
        String deviceKey = currentJob != null ? currentJob.host + ":" + currentJob.port : null;
        mainHandler.post(() -> {
            for (TransferListener l : listeners) {
                l.onStateChanged(newState, jobName, deviceKey);
            }
        });
    }

    private void notifyProgress(String itemName, long sent, long total) {
        notifyProgress(itemName, sent, total, null);
    }

    private void notifyProgress(String itemName, long sent, long total, String transferId) {
        long now = System.currentTimeMillis();
        // 节流：上次回调不到 200ms 则跳过（但 100% 必须通知）
        boolean isComplete = total > 0 && sent >= total;
        if (!isComplete && now - lastProgressNotifyMs < PROGRESS_THROTTLE_MS) {
            return;
        }
        lastProgressNotifyMs = now;
        // 持久化进度（为断点续传预留）
        if (transferId != null) {
            historyStore.updateProgressByTransferId(transferId, sent, total);
        }
        String deviceKey = currentJob != null ? currentJob.host + ":" + currentJob.port : null;
        mainHandler.post(() -> {
            for (TransferListener l : listeners) {
                l.onProgress(itemName, sent, total, deviceKey);
            }
        });
    }

    private void notifyJobFinished(String jobId, boolean success, String error) {
        mainHandler.post(() -> {
            for (TransferListener l : listeners) {
                l.onJobFinished(jobId, success, error);
            }
        });
    }

    private void notifyJobCancelled(String jobId, String jobName) {
        mainHandler.post(() -> {
            for (TransferListener l : listeners) {
                l.onJobCancelled(jobId, jobName);
            }
        });
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

    /** 为 PendingContent 注册 PFD（如果尚未注册）。 */
    private void registerPfdIfNeeded(PendingContent item) {
        if (item.pfd == null || item.transferId == null) return;
        pfdHandles.computeIfAbsent(item.transferId, id -> {
            try {
                return new PfdHandle(item.pfd);
            } catch (Exception e) {
                LogX.e("Failed to create PfdHandle for " + item.name, e);
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

    /** 释放一组 PendingContent 中所有文件的 PFD。 */
    private void releasePfds(List<PendingContent> items) {
        for (PendingContent item : items) {
            if (item.transferId != null) {
                releasePfd(item.transferId);
            }
        }
    }

    /** 查询指定 transferId 是否仍有 PFD 可用（用于 UI 判断是否允许重试/继续）。 */
    public boolean hasPfd(String transferId) {
        return transferId != null && pfdHandles.containsKey(transferId);
    }
}
