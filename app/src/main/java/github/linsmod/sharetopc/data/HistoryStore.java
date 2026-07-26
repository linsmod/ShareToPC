package github.linsmod.sharetopc.data;

import android.content.Context;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

import github.linsmod.sharetopc.util.LogX;

/** 历史记录：JSON 文件持久化（方案A）。最多保留 200 条。 */
public class HistoryStore {

    private static final int MAX_RECORDS = 200;

    private final File file;
    private final Gson gson = new Gson();
    private final List<TransferRecord> records = new ArrayList<>();
    private boolean loaded = false;

    public HistoryStore(Context context) {
        this.file = new File(context.getFilesDir(), "history.json");
    }

    private synchronized void ensureLoaded() {
        if (loaded) {
            return;
        }
        loaded = true;
        if (!file.exists()) {
            return;
        }
        try (FileReader reader = new FileReader(file)) {
            Type type = new TypeToken<List<TransferRecord>>() {
            }.getType();
            List<TransferRecord> list = gson.fromJson(reader, type);
            if (list != null) {
                records.addAll(list);
            }
        } catch (Exception e) {
            LogX.e("load history failed", e);
        }
    }

    private synchronized void persist() {
        try (FileWriter writer = new FileWriter(file)) {
            gson.toJson(records, writer);
        } catch (IOException e) {
            LogX.e("save history failed", e);
        }
    }

    /** 新记录插入到最前。 */
    public synchronized void add(TransferRecord record) {
        ensureLoaded();
        records.add(0, record);
        while (records.size() > MAX_RECORDS) {
            records.remove(records.size() - 1);
        }
        persist();
    }

    /** 按 id 原地更新记录的最终状态并移到最前（重试/补发完成后调用，保证同一内容只有单个条目）。 */
    public synchronized void updateStatus(String id, String host, int port, String targetName,
                                          boolean success, String errorMsg) {
        if (id == null) {
            return;
        }
        ensureLoaded();
        TransferRecord target = null;
        for (TransferRecord r : records) {
            if (id.equals(r.id)) {
                target = r;
                break;
            }
        }
        if (target == null) {
            return;
        }
        applyUpdate(target, host, port, targetName, success, errorMsg);
    }

    /** 按 transferId 匹配最近一条记录并更新状态（发送前插入 → 成功后/取消/失败时更新）。 */
    public synchronized void updateStatusByTransferId(String transferId, String host, int port,
                                                      String targetName, boolean success, String errorMsg) {
        if (transferId == null) {
            return;
        }
        ensureLoaded();
        TransferRecord target = null;
        for (TransferRecord r : records) {
            if (transferId.equals(r.transferId)) {
                target = r;
                break;
            }
        }
        if (target == null) {
            return;
        }
        applyUpdate(target, host, port, targetName, success, errorMsg);
    }

    private void applyUpdate(TransferRecord target, String host, int port, String targetName,
                             boolean success, String errorMsg) {
        target.host = host;
        target.port = port;
        target.targetName = targetName;
        target.success = success;
        // errorMsg 可能是 STATUS_CANCELLED / STATUS_SENDING / STATUS_PENDING 等状态字符串
        if (errorMsg != null && (errorMsg.equals(TransferRecord.STATUS_CANCELLED)
                || errorMsg.equals(TransferRecord.STATUS_SENDING)
                || errorMsg.equals(TransferRecord.STATUS_PENDING))) {
            target.status = errorMsg;
        } else {
            target.status = success ? TransferRecord.STATUS_SUCCESS : TransferRecord.STATUS_FAILED;
        }
        target.errorMsg = errorMsg;
        persist();
    }

    /** 按 id 删除记录（暂存记录补发成功后移除）。 */
    public synchronized void removeById(String id) {
        if (id == null) {
            return;
        }
        ensureLoaded();
        boolean changed = records.removeIf(r -> id.equals(r.id));
        if (changed) {
            persist();
        }
    }

    /** 批量删除记录。 */
    public synchronized void removeByIds(java.util.Collection<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        ensureLoaded();
        boolean changed = records.removeIf(r -> ids.contains(r.id));
        if (changed) {
            persist();
        }
    }

    /** 返回按时间倒序的副本（浅拷贝每个记录，避免原地修改导致 DiffUtil 失效）。 */
    public synchronized List<TransferRecord> all() {
        ensureLoaded();
        List<TransferRecord> snapshot = new ArrayList<>(records.size());
        for (TransferRecord r : records) {
            snapshot.add(copy(r));
        }
        return snapshot;
    }

    /** 浅拷贝 TransferRecord，DiffUtil 比较时需要不同对象引用。 */
    private static TransferRecord copy(TransferRecord src) {
        TransferRecord r = new TransferRecord();
        r.id = src.id;
        r.timeMillis = src.timeMillis;
        r.contentType = src.contentType;
        r.name = src.name;
        r.size = src.size;
        r.mime = src.mime;
        r.transferId = src.transferId;
        r.success = src.success;
        r.status = src.status;
        r.sentBytes = src.sentBytes;
        r.totalBytes = src.totalBytes;
        r.errorMsg = src.errorMsg;
        r.textContent = src.textContent;
        r.localPath = src.localPath;
        r.contentUri = src.contentUri;
        r.host = src.host;
        r.port = src.port;
        r.targetName = src.targetName;
        r.policy = src.policy;
        return r;
    }

    public synchronized void clear() {
        records.clear();
        persist();
    }

    /** 启动时修复中断的传输记录：sentBytes=0 → PENDING（等待发送），sentBytes>0 → FAILED。 */
    public void fixStaleSendingRecords() {
        ensureLoaded();
        boolean changed = false;
        for (TransferRecord r : records) {
            if (TransferRecord.STATUS_SENDING.equals(r.status)) {
                if (r.sentBytes == 0) {
                    r.status = TransferRecord.STATUS_PENDING;
                } else {
                    r.status = TransferRecord.STATUS_FAILED;
                }
                r.success = false;
                changed = true;
            }
        }
        if (changed) {
            persist();
        }
    }

    /** 传输过程中更新指定 transferId 的已发送/总字节数。 */
    public synchronized void updateProgressByTransferId(String transferId, long sent, long total) {
        if (transferId == null) return;
        for (TransferRecord r : records) {
            if (transferId.equals(r.transferId)) {
                r.sentBytes = sent;
                r.totalBytes = total;
                return;
            }
        }
    }
}
