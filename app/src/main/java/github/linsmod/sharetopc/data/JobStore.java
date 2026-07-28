package github.linsmod.sharetopc.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 传输任务：SQLite 持久化。最多保留 200 条。
 * 注意：已修复原代码中 updateStatus 缺少参数的 Bug。
 */
public class JobStore {

    private static final int MAX_RECORDS = 200;

    private final SQLiteDatabase db;   // 单例，应用生命周期内打开

    public JobStore(Context context) {
        DBHelper dbHelper = new DBHelper(context);
        this.db = dbHelper.getWritableDatabase();
    }

    // ---------- 辅助方法：Record ⇄ ContentValues ----------

    private ContentValues toValues(TransferRecord r) {
        ContentValues values = new ContentValues();
        values.put(DBHelper.COL_ID, r.id);
        values.put(DBHelper.COL_JOB_ID, r.jobId);
        values.put(DBHelper.COL_TIME_MILLIS, r.timeMillis);
        values.put(DBHelper.COL_CONTENT_TYPE, r.contentType);
        values.put(DBHelper.COL_NAME, r.name);
        values.put(DBHelper.COL_SIZE, r.size);
        values.put(DBHelper.COL_MIME, r.mime);
        values.put(DBHelper.COL_TRANSFER_ID, r.id);
        values.put(DBHelper.COL_STATUS, r.status);
        values.put(DBHelper.COL_SENT_BYTES, r.sentBytes);
        values.put(DBHelper.COL_TOTAL_BYTES, r.totalBytes);
        values.put(DBHelper.COL_ERROR_MSG, r.errorMsg);
        values.put(DBHelper.COL_TEXT_CONTENT, r.textContent);
        values.put(DBHelper.COL_LOCAL_PATH, r.localPath);
        values.put(DBHelper.COL_CONTENT_URI, r.contentUri);
        values.put(DBHelper.COL_HOST, r.host);
        values.put(DBHelper.COL_PORT, r.port);
        values.put(DBHelper.COL_TARGET_NAME, r.targetName);
        values.put(DBHelper.COL_POLICY, r.policy);
        return values;
    }

    private TransferRecord fromCursor(Cursor c) {
        TransferRecord r = new TransferRecord();
        r.id = c.getString(c.getColumnIndexOrThrow(DBHelper.COL_ID));
        r.jobId = c.getString(c.getColumnIndexOrThrow(DBHelper.COL_JOB_ID));
        r.timeMillis = c.getLong(c.getColumnIndexOrThrow(DBHelper.COL_TIME_MILLIS));
        r.contentType = c.getInt(c.getColumnIndexOrThrow(DBHelper.COL_CONTENT_TYPE));
        r.name = c.getString(c.getColumnIndexOrThrow(DBHelper.COL_NAME));
        r.size = c.getLong(c.getColumnIndexOrThrow(DBHelper.COL_SIZE));
        r.mime = c.getString(c.getColumnIndexOrThrow(DBHelper.COL_MIME));
        r.id = c.getString(c.getColumnIndexOrThrow(DBHelper.COL_TRANSFER_ID));
        r.status = c.getInt(c.getColumnIndexOrThrow(DBHelper.COL_STATUS));
        r.sentBytes = c.getLong(c.getColumnIndexOrThrow(DBHelper.COL_SENT_BYTES));
        r.totalBytes = c.getLong(c.getColumnIndexOrThrow(DBHelper.COL_TOTAL_BYTES));
        r.errorMsg = c.getString(c.getColumnIndexOrThrow(DBHelper.COL_ERROR_MSG));
        r.textContent = c.getString(c.getColumnIndexOrThrow(DBHelper.COL_TEXT_CONTENT));
        r.localPath = c.getString(c.getColumnIndexOrThrow(DBHelper.COL_LOCAL_PATH));
        r.contentUri = c.getString(c.getColumnIndexOrThrow(DBHelper.COL_CONTENT_URI));
        r.host = c.getString(c.getColumnIndexOrThrow(DBHelper.COL_HOST));
        r.port = c.getInt(c.getColumnIndexOrThrow(DBHelper.COL_PORT));
        r.targetName = c.getString(c.getColumnIndexOrThrow(DBHelper.COL_TARGET_NAME));
        r.policy = c.getString(c.getColumnIndexOrThrow(DBHelper.COL_POLICY));
        return r;
    }

    // ---------- 内部方法：删除超出上限的记录 ----------

    private synchronized void trimIfNeeded() {
        // 查询当前记录数
        Cursor countCursor = db.query(DBHelper.TABLE_JOB_ENTRY,
                new String[]{"COUNT(*)"}, null, null, null, null, null);
        long count = 0;
        if (countCursor != null) {
            if (countCursor.moveToFirst()) {
                count = countCursor.getLong(0);
            }
            countCursor.close();
        }
        if (count <= MAX_RECORDS) return;

        // 删除最旧的多余记录（保留最新的 MAX_RECORDS 条）
        String deleteSql = "DELETE FROM " + DBHelper.TABLE_JOB_ENTRY +
                " WHERE " + DBHelper.COL_ID + " IN (" +
                "  SELECT " + DBHelper.COL_ID +
                "  FROM " + DBHelper.TABLE_JOB_ENTRY +
                "  ORDER BY " + DBHelper.COL_TIME_MILLIS + " ASC" +
                "  LIMIT " + (count - MAX_RECORDS) +
                ")";
        db.execSQL(deleteSql);
    }

    // ---------- 公开 API ----------

    /** 新记录插入到最前（按时间倒序），自动裁切到 200 条。 */
    public synchronized void addJobEntry(TransferRecord record) {
        db.insertWithOnConflict(DBHelper.TABLE_JOB_ENTRY, null,
                toValues(record), SQLiteDatabase.CONFLICT_REPLACE);
        trimIfNeeded();
    }

    /** 按 id 更新主机、端口、目标名，并更新最终状态。 */
    public synchronized void updateJobEntry(String id, String host, int port,
                                            String targetName, int status, int idle_reason, String errorMsg) {
        if (id == null) return;
        ContentValues values = new ContentValues();
        values.put(DBHelper.COL_HOST, host);
        values.put(DBHelper.COL_PORT, port);
        values.put(DBHelper.COL_TARGET_NAME, targetName);
        values.put(DBHelper.COL_STATUS, status);
        values.put(DBHelper.COL_IDLE_REASON, idle_reason);
        values.put(DBHelper.COL_ERROR_MSG, errorMsg);
        db.update(DBHelper.TABLE_JOB_ENTRY, values,
                DBHelper.COL_ID + "=?", new String[]{id});
    }

    public synchronized void updateStatus(String id, int status, int idle_reason) {
        if (id == null) return;
        ContentValues values = new ContentValues();
        values.put(DBHelper.COL_STATUS, status);
        values.put(DBHelper.COL_IDLE_REASON, idle_reason);
        values.put(DBHelper.COL_ERROR_MSG, "");
        db.update(DBHelper.TABLE_JOB_ENTRY, values,
                DBHelper.COL_ID + "=?", new String[]{id});
    }

    /** 按 id 删除记录。 */
    public synchronized void removeById(String id) {
        if (id == null) return;
        db.delete(DBHelper.TABLE_JOB_ENTRY,
                DBHelper.COL_ID + "=?", new String[]{id});
    }

    /** 批量删除记录。 */
    public synchronized void removeByIds(Collection<String> ids) {
        if (ids == null || ids.isEmpty()) return;
        String placeholders = makePlaceholders(ids.size());
        db.delete(DBHelper.TABLE_JOB_ENTRY,
                DBHelper.COL_ID + " IN (" + placeholders + ")",
                ids.toArray(new String[0]));
    }

    private String makePlaceholders(int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) sb.append(",");
            sb.append("?");
        }
        return sb.toString();
    }

    /** 返回所有记录，按时间倒序（新->旧），每条记录为独立副本。 */
    public synchronized List<TransferRecord> all() {
        List<TransferRecord> result = new ArrayList<>();
        Cursor c = db.query(DBHelper.TABLE_JOB_ENTRY, null,
                null, null, null, null,
                DBHelper.COL_TIME_MILLIS + " DESC");
        if (c != null) {
            while (c.moveToNext()) {
                result.add(copy(fromCursor(c)));
            }
            c.close();
        }
        return result;
    }

    /** 拷贝方法，与原实现保持一致。 */
    private static TransferRecord copy(TransferRecord src) {
        TransferRecord r = new TransferRecord();
        r.id = src.id;
        r.timeMillis = src.timeMillis;
        r.contentType = src.contentType;
        r.name = src.name;
        r.size = src.size;
        r.mime = src.mime;
        r.id = src.id;
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

    /** 清空所有记录。 */
    public synchronized void clear() {
        db.delete(DBHelper.TABLE_JOB_ENTRY, null, null);
    }

    /** 更新传输进度。 */
    public synchronized void updateProgress(String transferId, long sent, long total) {
        if (transferId == null) return;
        ContentValues values = new ContentValues();
        values.put(DBHelper.COL_SENT_BYTES, sent);
        values.put(DBHelper.COL_TOTAL_BYTES, total);
        db.update(DBHelper.TABLE_JOB_ENTRY, values,
                DBHelper.COL_TRANSFER_ID + "=?", new String[]{transferId});
    }

    public synchronized TransferRecord getSendEntry(String id) {
        if (id == null) return null;
        Cursor c = db.query(DBHelper.TABLE_JOB_ENTRY, null,
                DBHelper.COL_ID + "=?", new String[]{id},
                null, null, null);
        TransferRecord record = null;
        if (c.moveToFirst()) {
            record = fromCursor(c);
        }
        c.close();
        return record;
    }
}