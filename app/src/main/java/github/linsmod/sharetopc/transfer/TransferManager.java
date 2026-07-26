package github.linsmod.sharetopc.transfer;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;

import github.linsmod.sharetopc.cleanup.CleanupPolicy;
import github.linsmod.sharetopc.cleanup.CleanupScheduler;
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
        IDLE, CONNECTING, SENDING, DONE, FAILED
    }

    public static class SendJob {
        public final String id = UUID.randomUUID().toString();
        public final List<PendingContent> items;
        public final String host;
        public final int port;
        public final String deviceName;
        public final CleanupPolicy policy;
        /** 关联的历史记录 id（重试/补发）：完成时原地更新该记录，不再新增条目。 */
        public final String recordId;

        public SendJob(List<PendingContent> items, String host, int port,
                       String deviceName, CleanupPolicy policy) {
            this(items, host, port, deviceName, policy, null);
        }

        public SendJob(List<PendingContent> items, String host, int port,
                       String deviceName, CleanupPolicy policy, String recordId) {
            this.items = items;
            this.host = host;
            this.port = port;
            this.deviceName = deviceName;
            this.policy = policy;
            this.recordId = recordId;
        }

        public String displayName() {
            if (items.size() == 1) {
                return items.get(0).name;
            }
            return items.size() + " 个文件";
        }
    }

    private static final int MAX_ATTEMPTS = 2; // 首次 + 重试 1 次
    private static final long RETRY_DELAY_MS = 2000;

    private final BlockingQueue<SendJob> queue = new LinkedBlockingQueue<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final List<TransferListener> listeners = new CopyOnWriteArrayList<>();
    private final HistoryStore historyStore;
    private final CleanupScheduler cleanupScheduler;

    private volatile State state = State.IDLE;

    public TransferManager(Context context, HistoryStore historyStore,
                           CleanupScheduler cleanupScheduler) {
        this.historyStore = historyStore;
        this.cleanupScheduler = cleanupScheduler;
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

    /** 重发一条历史记录（完成后原地更新该记录状态）。 */
    public String retry(TransferRecord record) {
        PendingContent content = record.toPendingContent();
        List<PendingContent> items = new ArrayList<>();
        items.add(content);
        CleanupPolicy policy = CleanupPolicy.fromName(record.policy, CleanupPolicy.AFTER_SEND);
        return enqueue(items, record.host, record.port, record.targetName, policy, record.id);
    }

    public String enqueue(List<PendingContent> items, String host, int port,
                          String deviceName, CleanupPolicy policy) {
        return enqueue(items, host, port, deviceName, policy, null);
    }

    public String enqueue(List<PendingContent> items, String host, int port,
                          String deviceName, CleanupPolicy policy, String recordId) {
        SendJob job = new SendJob(items, host, port, deviceName, policy, recordId);
        queue.offer(job);
        LogX.d("enqueue job: " + job.displayName() + " -> " + host + ":" + port);
        return job.id;
    }

    private void consumeLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            SendJob job;
            try {
                job = queue.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            process(job);
        }
    }

    private void process(SendJob job) {
        setState(State.CONNECTING, job.displayName());
        String error = null;
        List<PendingContent> succeeded = new ArrayList<>();
        List<PendingContent> failed = new ArrayList<>();

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            TransferClient client = new TransferClient();
            try {
                client.connect(job.host, job.port);
                client.hello();
                setState(State.SENDING, job.displayName());
                for (PendingContent item : job.items) {
                    if (succeeded.contains(item)) {
                        continue;
                    }
                    sendItem(client, job, item);
                    succeeded.add(item);
                    recordHistory(job, item, true, null);
                }
                error = null;
                break;
            } catch (Exception e) {
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

        for (PendingContent item : job.items) {
            if (!succeeded.contains(item)) {
                failed.add(item);
                recordHistory(job, item, false, error);
            }
        }

        if (failed.isEmpty()) {
            cleanupScheduler.onTransferSuccess(job.items, job.policy);
            setState(State.DONE, job.displayName());
            notifyJobFinished(job.id, true, null);
        } else {
            // 部分成功：成功部分仍按策略处理
            if (!succeeded.isEmpty()) {
                cleanupScheduler.onTransferSuccess(succeeded, job.policy);
            }
            setState(State.FAILED, job.displayName());
            notifyJobFinished(job.id, false, error);
        }
        setState(State.IDLE, null);
    }

    private void sendItem(TransferClient client, SendJob job, PendingContent item) throws Exception {
        if (item.isText()) {
            client.sendText(item.text);
        } else {
            File file = new File(item.localPath);
            if (!file.exists()) {
                throw new Exception("本地文件不存在：" + item.name);
            }
            client.sendFile(item, (sent, total) -> notifyProgress(item.name, sent, total));
        }
    }

    private void recordHistory(SendJob job, PendingContent item, boolean success, String error) {
        if (job.recordId != null) {
            // 重试/补发：原地更新已有记录，保持同一内容只有单个条目
            historyStore.updateStatus(job.recordId, job.host, job.port,
                    job.deviceName, success, error);
        } else {
            historyStore.add(TransferRecord.from(item, job.host, job.port,
                    job.deviceName, job.policy.name(), success, error));
        }
        mainHandler.post(() -> {
            for (TransferListener l : listeners) {
                l.onHistoryChanged();
            }
        });
    }

    private void setState(State newState, String jobName) {
        state = newState;
        mainHandler.post(() -> {
            for (TransferListener l : listeners) {
                l.onStateChanged(newState, jobName);
            }
        });
    }

    private void notifyProgress(String itemName, long sent, long total) {
        mainHandler.post(() -> {
            for (TransferListener l : listeners) {
                l.onProgress(itemName, sent, total);
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
}
