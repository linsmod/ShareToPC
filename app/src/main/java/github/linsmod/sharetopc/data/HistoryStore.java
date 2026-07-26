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
        target.host = host;
        target.port = port;
        target.targetName = targetName;
        target.success = success;
        target.status = success ? TransferRecord.STATUS_SUCCESS : TransferRecord.STATUS_FAILED;
        target.errorMsg = errorMsg;
        target.timeMillis = System.currentTimeMillis();
        records.remove(target);
        records.add(0, target);
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

    /** 返回按时间倒序的副本。 */
    public synchronized List<TransferRecord> all() {
        ensureLoaded();
        return new ArrayList<>(records);
    }

    public synchronized void clear() {
        records.clear();
        persist();
    }
}
