package github.linsmod.sharetopc.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 传输任务：SQLite 持久化。按 jobId 分组，最多保留 200 个任务。
 */
public class JobStore {

    private static final int MAX_JOBS = 200;

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
        values.put(DBHelper.COL_IDLE_REASON, r.idleReason);
        values.put(DBHelper.COL_HOST, r.host);
        values.put(DBHelper.COL_PORT, r.port);
        values.put(DBHelper.COL_TARGET_NAME, r.targetName);
        values.put(DBHelper.COL_POLICY, r.policy);
        values.put(DBHelper.COL_PENDING_ACTION, r.pendingAction);
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
        r.idleReason = c.getInt(c.getColumnIndexOrThrow(DBHelper.COL_IDLE_REASON));
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
        r.pendingAction = c.getInt(c.getColumnIndexOrThrow(DBHelper.COL_PENDING_ACTION));
        return r;
    }

    // ---------- 内部方法：按 jobId 分组，超出上限时删除最旧的任务 ----------

    private synchronized void trimJobsIfNeeded() {
        Cursor countCursor = db.rawQuery(
                "SELECT COUNT(DISTINCT " + DBHelper.COL_JOB_ID
                + ") FROM " + DBHelper.TABLE_JOB_ENTRY, null);
        long jobCount = 0;
        if (countCursor != null) {
            if (countCursor.moveToFirst()) {
                jobCount = countCursor.getLong(0);
            }
            countCursor.close();
        }
        if (jobCount <= MAX_JOBS) return;

        long excess = jobCount - MAX_JOBS;
        String deleteSql = "DELETE FROM " + DBHelper.TABLE_JOB_ENTRY
                + " WHERE " + DBHelper.COL_JOB_ID + " IN ("
                + "  SELECT " + DBHelper.COL_JOB_ID + " FROM ("
                + "    SELECT " + DBHelper.COL_JOB_ID
                + ", MAX(" + DBHelper.COL_TIME_MILLIS + ") AS _mt"
                + "    FROM " + DBHelper.TABLE_JOB_ENTRY
                + "    WHERE " + DBHelper.COL_JOB_ID + " IS NOT NULL"
                + "    GROUP BY " + DBHelper.COL_JOB_ID
                + "    ORDER BY _mt ASC"
                + "    LIMIT " + excess
                + "  )"
                + ")";
        db.execSQL(deleteSql);
        // 清理 job 表中已无 entry 的空 job
        db.execSQL("DELETE FROM " + DBHelper.TABLE_JOB
                + " WHERE " + DBHelper.COL_JOB_ID_PK
                + " NOT IN (SELECT DISTINCT " + DBHelper.COL_JOB_ID
                + " FROM " + DBHelper.TABLE_JOB_ENTRY + ")");
    }

    // ---------- 公开 API ----------

    /** App 重启时，将所有 STATUS_SENDING 和 STATUS_IDLE+非SUCCESS 的记录标记为 OUTDATE，并同步更新对应 job。 */
    public synchronized void markOutdateOnStartup() {
        // 查出所有将受影响的 jobId（在更新前查）
        Cursor c = db.query(true, DBHelper.TABLE_JOB_ENTRY,
                new String[]{DBHelper.COL_JOB_ID},
                DBHelper.COL_STATUS + "=? OR (" + DBHelper.COL_STATUS + "=? AND " + DBHelper.COL_IDLE_REASON + "!=?)",
                new String[]{String.valueOf(TransferRecord.STATUS_SENDING),
                        String.valueOf(TransferRecord.STATUS_IDLE), String.valueOf(TransferRecord.IDLE_REASON_SUCCESS)},
                null, null, null, null);
        List<String> affectedJobIds = new ArrayList<>();
        if (c != null) {
            while (c.moveToNext()) {
                String jid = c.getString(0);
                if (jid != null && !affectedJobIds.contains(jid)) {
                    affectedJobIds.add(jid);
                }
            }
            c.close();
        }

        // 标记所有 STATUS_SENDING → OUTDATE
        ContentValues values = new ContentValues();
        values.put(DBHelper.COL_STATUS, TransferRecord.STATUS_IDLE);
        values.put(DBHelper.COL_IDLE_REASON, TransferRecord.IDLE_REASON_OUTDATE);
        db.update(DBHelper.TABLE_JOB_ENTRY, values,
                DBHelper.COL_STATUS + "=?", new String[]{String.valueOf(TransferRecord.STATUS_SENDING)});

        // 标记所有 STATUS_IDLE 且非 SUCCESS 的记录 → OUTDATE（重启后 PFD 已丢失，不可恢复）
        db.update(DBHelper.TABLE_JOB_ENTRY, values,
                DBHelper.COL_STATUS + "=? AND " + DBHelper.COL_IDLE_REASON + "!=?",
                new String[]{String.valueOf(TransferRecord.STATUS_IDLE), String.valueOf(TransferRecord.IDLE_REASON_SUCCESS)});

        // 同步受影响的 job 聚合信息
        for (String jid : affectedJobIds) {
            syncJobFromEntries(jid);
        }
    }

    /** 根据 job_entry 重新计算 job 聚合并 upsert 到 job 表。 */
    private void syncJobFromEntries(String jobId) {
        if (jobId == null) return;
        Cursor c = db.query(DBHelper.TABLE_JOB_ENTRY, null,
                DBHelper.COL_JOB_ID + "=?", new String[]{jobId},
                null, null, null);
        if (c == null) return;

        long maxTime = 0;
        int totalEntries = 0;
        int completedEntries = 0;
        String host = null;
        int port = 0;
        String deviceName = null;

        while (c.moveToNext()) {
            totalEntries++;
            long t = c.getLong(c.getColumnIndexOrThrow(DBHelper.COL_TIME_MILLIS));
            if (t > maxTime) {
                maxTime = t;
                host = c.getString(c.getColumnIndexOrThrow(DBHelper.COL_HOST));
                port = c.getInt(c.getColumnIndexOrThrow(DBHelper.COL_PORT));
                deviceName = c.getString(c.getColumnIndexOrThrow(DBHelper.COL_TARGET_NAME));
            }
            int st = c.getInt(c.getColumnIndexOrThrow(DBHelper.COL_STATUS));
            int ir = c.getInt(c.getColumnIndexOrThrow(DBHelper.COL_IDLE_REASON));
            if (st == TransferRecord.STATUS_IDLE && ir == TransferRecord.IDLE_REASON_SUCCESS) {
                completedEntries++;
            }
        }
        c.close();

        if (totalEntries == 0) {
            db.delete(DBHelper.TABLE_JOB, DBHelper.COL_JOB_ID_PK + "=?", new String[]{jobId});
            return;
        }

        ContentValues vals = new ContentValues();
        vals.put(DBHelper.COL_JOB_ID_PK, jobId);
        vals.put(DBHelper.COL_JOB_DEVICE_HOST, host);
        vals.put(DBHelper.COL_JOB_DEVICE_PORT, port);
        vals.put(DBHelper.COL_JOB_DEVICE_NAME, deviceName);
        vals.put(DBHelper.COL_JOB_TIME_MILLIS, maxTime);
        vals.put(DBHelper.COL_JOB_TOTAL_ENTRIES, totalEntries);
        vals.put(DBHelper.COL_JOB_COMPLETED_ENTRIES, completedEntries);
        db.insertWithOnConflict(DBHelper.TABLE_JOB, null, vals, SQLiteDatabase.CONFLICT_REPLACE);
    }

    /** 插入记录，若任务数超过上限则删除最旧的任务（同 jobId 的条目整体删除）。 */
    public synchronized void addJobEntry(TransferRecord record) {
        db.insertWithOnConflict(DBHelper.TABLE_JOB_ENTRY, null,
                toValues(record), SQLiteDatabase.CONFLICT_REPLACE);
        trimJobsIfNeeded();
        if (record.jobId != null) {
            syncJobFromEntries(record.jobId);
        }
    }

    /** 按 id 更新主机、端口、目标名，并更新最终状态。 */
    public synchronized void updateJobEntry(String id, String host, int port,
                                            String targetName, int status, int idle_reason, String errorMsg,
                                            int pendingAction) {
        if (id == null) return;
        ContentValues values = new ContentValues();
        values.put(DBHelper.COL_HOST, host);
        values.put(DBHelper.COL_PORT, port);
        values.put(DBHelper.COL_TARGET_NAME, targetName);
        values.put(DBHelper.COL_STATUS, status);
        values.put(DBHelper.COL_IDLE_REASON, idle_reason);
        values.put(DBHelper.COL_ERROR_MSG, errorMsg);
        values.put(DBHelper.COL_PENDING_ACTION, pendingAction);
        db.update(DBHelper.TABLE_JOB_ENTRY, values,
                DBHelper.COL_ID + "=?", new String[]{id});
    }

    public synchronized void updateStatus(String id, int status, int idle_reason, int pendingAction) {
        if (id == null) return;
        ContentValues values = new ContentValues();
        values.put(DBHelper.COL_STATUS, status);
        values.put(DBHelper.COL_IDLE_REASON, idle_reason);
        values.put(DBHelper.COL_ERROR_MSG, "");
        values.put(DBHelper.COL_PENDING_ACTION, pendingAction);
        db.update(DBHelper.TABLE_JOB_ENTRY, values,
                DBHelper.COL_ID + "=?", new String[]{id});
    }

    /** 按 id 删除记录。 */
    public synchronized void removeById(String id) {
        if (id == null) return;
        TransferRecord r = getSendEntry(id);
        String jobId = r != null ? r.jobId : null;
        db.delete(DBHelper.TABLE_JOB_ENTRY,
                DBHelper.COL_ID + "=?", new String[]{id});
        if (jobId != null) {
            syncJobFromEntries(jobId);
        }
    }

    /** 批量删除记录。 */
    public synchronized void removeByIds(Collection<String> ids) {
        if (ids == null || ids.isEmpty()) return;
        Set<String> affectedJobIds = new HashSet<>();
        for (String id : ids) {
            TransferRecord r = getSendEntry(id);
            if (r != null && r.jobId != null) {
                affectedJobIds.add(r.jobId);
            }
        }
        String placeholders = makePlaceholders(ids.size());
        db.delete(DBHelper.TABLE_JOB_ENTRY,
                DBHelper.COL_ID + " IN (" + placeholders + ")",
                ids.toArray(new String[0]));
        for (String jid : affectedJobIds) {
            syncJobFromEntries(jid);
        }
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
        r.jobId = src.jobId;
        r.timeMillis = src.timeMillis;
        r.contentType = src.contentType;
        r.name = src.name;
        r.size = src.size;
        r.mime = src.mime;
        r.status = src.status;
        r.idleReason = src.idleReason;
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
        r.pendingAction = src.pendingAction;
        return r;
    }

    /** 清空所有记录。 */
    public synchronized void clear() {
        db.delete(DBHelper.TABLE_JOB_ENTRY, null, null);
        db.delete(DBHelper.TABLE_JOB, null, null);
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

    /** 查询某个 job 的聚合信息。 */
    public synchronized JobInfo getJob(String jobId) {
        if (jobId == null) return null;
        Cursor c = db.query(DBHelper.TABLE_JOB, null,
                DBHelper.COL_JOB_ID_PK + "=?", new String[]{jobId},
                null, null, null);
        JobInfo info = null;
        if (c.moveToFirst()) {
            info = new JobInfo();
            info.jobId = c.getString(c.getColumnIndexOrThrow(DBHelper.COL_JOB_ID_PK));
            info.deviceHost = c.getString(c.getColumnIndexOrThrow(DBHelper.COL_JOB_DEVICE_HOST));
            info.devicePort = c.getInt(c.getColumnIndexOrThrow(DBHelper.COL_JOB_DEVICE_PORT));
            info.deviceName = c.getString(c.getColumnIndexOrThrow(DBHelper.COL_JOB_DEVICE_NAME));
            info.timeMillis = c.getLong(c.getColumnIndexOrThrow(DBHelper.COL_JOB_TIME_MILLIS));
            info.totalEntries = c.getInt(c.getColumnIndexOrThrow(DBHelper.COL_JOB_TOTAL_ENTRIES));
            info.completedEntries = c.getInt(c.getColumnIndexOrThrow(DBHelper.COL_JOB_COMPLETED_ENTRIES));
        }
        c.close();
        return info;
    }

    /** Job 聚合信息。 */
    public static class JobInfo {
        public String jobId;
        public String deviceHost;
        public int devicePort;
        public String deviceName;
        public long timeMillis;
        public int totalEntries;
        public int completedEntries;

        public boolean isAllDone() {
            return completedEntries >= totalEntries && totalEntries > 0;
        }
    }
}