package github.linsmod.sharetopc.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import github.linsmod.sharetopc.transfer.TransferManager;

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

    private ContentValues toValues(SendEntry r) {
        ContentValues values = new ContentValues();
        values.put(DBHelper.COL_ID, r.id);
        values.put(DBHelper.COL_JOB_ID, r.jobId);
        values.put(DBHelper.COL_TIME_MILLIS, r.timeMillis);
        values.put(DBHelper.COL_CONTENT_TYPE, r.objectType);
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
        values.put(DBHelper.COL_UPDATE_MILLIS, r.updateMillis);
        values.put(DBHelper.COL_DIRECTION, r.direction);
        values.put(DBHelper.COL_REMOTE_PATH, r.remotePath);
        return values;
    }

    private SendEntry fromCursor(Cursor c) {
        SendEntry r = new SendEntry();
        r.id = c.getString(c.getColumnIndexOrThrow(DBHelper.COL_ID));
        r.jobId = c.getString(c.getColumnIndexOrThrow(DBHelper.COL_JOB_ID));
        r.timeMillis = c.getLong(c.getColumnIndexOrThrow(DBHelper.COL_TIME_MILLIS));
        r.objectType = c.getInt(c.getColumnIndexOrThrow(DBHelper.COL_CONTENT_TYPE));
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
        r.updateMillis = c.getLong(c.getColumnIndexOrThrow(DBHelper.COL_UPDATE_MILLIS));
        r.direction = c.getInt(c.getColumnIndexOrThrow(DBHelper.COL_DIRECTION));
        r.remotePath = c.getString(c.getColumnIndexOrThrow(DBHelper.COL_REMOTE_PATH));
        return r;
    }

    // ---------- 内部方法：按 jobId 分组，超出上限时删除最旧的任务 ----------

    public synchronized void trimJobsIfNeeded() {
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
                new String[]{String.valueOf(SendEntry.STATUS_SENDING),
                        String.valueOf(SendEntry.STATUS_IDLE), String.valueOf(SendEntry.IDLE_REASON_SUCCESS)},
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
        values.put(DBHelper.COL_STATUS, SendEntry.STATUS_IDLE);
        values.put(DBHelper.COL_IDLE_REASON, SendEntry.IDLE_REASON_OUTDATE);
        db.update(DBHelper.TABLE_JOB_ENTRY, values,
                DBHelper.COL_STATUS + "=?", new String[]{String.valueOf(SendEntry.STATUS_SENDING)});

        // 标记所有 STATUS_IDLE 且非 SUCCESS 的记录 → OUTDATE（重启后 PFD 已丢失，不可恢复）
        db.update(DBHelper.TABLE_JOB_ENTRY, values,
                DBHelper.COL_STATUS + "=? AND " + DBHelper.COL_IDLE_REASON + "!=?",
                new String[]{String.valueOf(SendEntry.STATUS_IDLE), String.valueOf(SendEntry.IDLE_REASON_SUCCESS)});
    }

    /** 插入记录，若任务数超过上限则删除最旧的任务（同 jobId 的条目整体删除）。 */
    public synchronized void addJobEntry(SendEntry record) {
        db.insertWithOnConflict(DBHelper.TABLE_JOB_ENTRY, null,
                toValues(record), SQLiteDatabase.CONFLICT_REPLACE);
    }

    /** 按 id 更新主机、端口、目标名，并更新最终状态。 */
    public synchronized void updateJobEntry(String id, String host, int port,
                                            String targetName, int status, long sentBytes, long totalBytes, int idle_reason, String errorMsg,
                                            long updateMillis) {
        if (id == null) return;
        ContentValues values = new ContentValues();
        values.put(DBHelper.COL_HOST, host);
        values.put(DBHelper.COL_PORT, port);
        if (targetName != null) values.put(DBHelper.COL_TARGET_NAME, targetName);
        values.put(DBHelper.COL_STATUS, status);
        values.put(DBHelper.COL_IDLE_REASON, idle_reason);
        values.put(DBHelper.COL_ERROR_MSG, errorMsg);
        values.put(DBHelper.COL_SENT_BYTES, sentBytes);
        values.put(DBHelper.COL_TOTAL_BYTES, totalBytes);
        values.put(DBHelper.COL_UPDATE_MILLIS, updateMillis);
        db.update(DBHelper.TABLE_JOB_ENTRY, values,
                DBHelper.COL_ID + "=?", new String[]{id});
    }

    /** 更新拉取任务成功后本机缓存文件路径（供任务列表打开已拉取的文件）。 */
    public synchronized void updateLocalPath(String id, String localPath) {
        if (id == null) return;
        ContentValues values = new ContentValues();
        values.put(DBHelper.COL_LOCAL_PATH, localPath);
        db.update(DBHelper.TABLE_JOB_ENTRY, values,
                DBHelper.COL_ID + "=?", new String[]{id});
    }

    /** 按 jobId 删除所有关联的 entry 记录。 */
    public synchronized void deleteEntriesByJobId(String jobId) {
        if (jobId == null) return;
        db.delete(DBHelper.TABLE_JOB_ENTRY,
                DBHelper.COL_JOB_ID + "=?", new String[]{jobId});
    }

    /** 删除指定设备的所有传输记录（entry + job 聚合记录）。 */
    public synchronized void deleteByDevice(String host, int port) {
        if (host == null) return;
        db.delete(DBHelper.TABLE_JOB_ENTRY,
                DBHelper.COL_HOST + "=? AND " + DBHelper.COL_PORT + "=?",
                new String[]{host, String.valueOf(port)});
        db.delete(DBHelper.TABLE_JOB,
                DBHelper.COL_JOB_DEVICE_HOST + "=? AND " + DBHelper.COL_JOB_DEVICE_PORT + "=?",
                new String[]{host, String.valueOf(port)});
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
    public synchronized List<SendEntry> all() {
        List<SendEntry> result = new ArrayList<>();
        Cursor c = db.query(DBHelper.TABLE_JOB_ENTRY, null,
                null, null, null, null,
                DBHelper.COL_TIME_MILLIS + " DESC");
        if (c != null) {
            while (c.moveToNext()) {
                result.add(fromCursor(c));
            }
            c.close();
        }
        return result;
    }
    public synchronized Map<String, List<SendEntry>> allMap() {
        // 直接使用 Stream 流按 jobId 分组
        return all().stream().collect(Collectors.groupingBy(item -> item.jobId));
    }
    // Device.Id -> latest sendJob
     public synchronized Map<String, TransferManager.SendJob> allDeviceLatestJobs(int hoursSpan) {
        Map<String, TransferManager.SendJob> result = new HashMap<>();
        long cutoffTime = System.currentTimeMillis() - (long) hoursSpan * 3600 * 1000;

        Cursor cursor = null;
        try {
            // 一次性联结查询：device + job，限制 job.timeMillis >= cutoffTime
            String sql = "SELECT d." + DBHelper.COL_DEV_ID + ", " +
                               "j." + DBHelper.COL_JOB_ID_PK + ", " +
                               "j." + DBHelper.COL_JOB_DEVICE_HOST + ", " +
                               "j." + DBHelper.COL_JOB_DEVICE_PORT + ", " +
                               "j." + DBHelper.COL_JOB_DEVICE_NAME + ", " +
                               "j." + DBHelper.COL_JOB_TIME_MILLIS + ", " +
                               "j." + DBHelper.COL_JOB_UPDATE_MILLIS +
                        " FROM " + DBHelper.TABLE_DEVICE + " d" +
                        " INNER JOIN " + DBHelper.TABLE_JOB + " j" +
                            " ON d." + DBHelper.COL_DEV_LAST_SEND_ID + " = j." + DBHelper.COL_JOB_ID_PK +
                        " WHERE d." + DBHelper.COL_DEV_LAST_SEND_ID + " IS NOT NULL";
            if (hoursSpan > 0) {
                sql += " AND j." + DBHelper.COL_JOB_TIME_MILLIS + " >= " + cutoffTime;
            }
            cursor = db.rawQuery(sql, null);

            while (cursor.moveToNext()) {
                String deviceId = cursor.getString(0);
                String jobId = cursor.getString(1);
                String host = cursor.getString(2);
                int port = cursor.getInt(3);
                String deviceName = cursor.getString(4);
                long jobCreateMillis = cursor.getLong(5);
                long jobUpdateMillis = cursor.getLong(6);

                List<SendEntry> entries = entriesByJobId(jobId);
                TransferManager.SendJob job = new TransferManager.SendJob(jobId,deviceId, entries, host, port);
                job.createMillis = jobCreateMillis;
                job.updateMillis = jobUpdateMillis;
                result.put(deviceId, job);
            }
        } finally {
            if (cursor != null) cursor.close();
        }
        return result;
    }
    private List<SendEntry> entriesByJobId(String jobId) {
        List<SendEntry> entries = new ArrayList<>();
        Cursor cursor = db.query(
                DBHelper.TABLE_JOB_ENTRY, null,
                DBHelper.COL_JOB_ID + " = ?",
                new String[]{jobId}, null, null, null
        );
        try {
            while (cursor.moveToNext()) {
                SendEntry record = fromCursor(cursor); // 如果 fromCursor 是 public
                if (record instanceof SendEntry) {
                    entries.add((SendEntry) record);
                }
            }
        } finally {
            cursor.close();
        }
        return entries;
    }

    /** 插入或更新 job 聚合记录（TABLE_JOB），使 allDeviceLatestJobs 能读到正确的 updateMillis。 */
    public synchronized void upsertJob(TransferManager.SendJob job) {
        if (job == null) return;
        ContentValues values = new ContentValues();
        values.put(DBHelper.COL_JOB_ID_PK, job.id);
        values.put(DBHelper.COL_JOB_DEVICE_HOST, job.host);
        values.put(DBHelper.COL_JOB_DEVICE_PORT, job.port);
        values.put(DBHelper.COL_JOB_DEVICE_NAME, job.device != null ? job.device.displayName() : "");
        values.put(DBHelper.COL_JOB_DEVICE_ID, job.deviceId);
        values.put(DBHelper.COL_JOB_TIME_MILLIS, job.createMillis != null ? job.createMillis : 0);
        values.put(DBHelper.COL_JOB_UPDATE_MILLIS, job.updateMillis != null ? job.updateMillis : 0);
        db.insertWithOnConflict(DBHelper.TABLE_JOB, null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }

    /** 清空所有记录。 */
    public synchronized void clear() {
        db.delete(DBHelper.TABLE_JOB_ENTRY, null, null);
        db.delete(DBHelper.TABLE_JOB, null, null);
    }

    public synchronized SendEntry getSendEntry(String id) {
        if (id == null) return null;
        Cursor c = db.query(DBHelper.TABLE_JOB_ENTRY, null,
                DBHelper.COL_ID + "=?", new String[]{id},
                null, null, null);
        SendEntry record = null;
        if (c.moveToFirst()) {
            record = fromCursor(c);
        }
        c.close();
        return record;
    }

    /** 查询某个 job 的聚合信息。 */
    public synchronized TransferManager.SendJob getJob(String jobId) {
        if (jobId == null) return null;

        Cursor c = db.query(DBHelper.TABLE_JOB, null,
                DBHelper.COL_JOB_ID_PK + "=?", new String[]{jobId},
                null, null, null);
        TransferManager.SendJob info = null;
        if (c.moveToFirst()) {
            String deviceId = c.getString(c.getColumnIndexOrThrow(DBHelper.COL_JOB_DEVICE_ID));
            String host = c.getString(c.getColumnIndexOrThrow(DBHelper.COL_JOB_DEVICE_HOST));
            int port = c.getInt(c.getColumnIndexOrThrow(DBHelper.COL_JOB_DEVICE_PORT));
            long createMillis = c.getLong(c.getColumnIndexOrThrow(DBHelper.COL_JOB_TIME_MILLIS));
            long updateMillis = c.getLong(c.getColumnIndexOrThrow(DBHelper.COL_JOB_UPDATE_MILLIS));

            List<SendEntry> entries = entriesByJobId(jobId);

            info = new TransferManager.SendJob(jobId, deviceId, entries, host, port);
            info.createMillis = createMillis;
            info.updateMillis = updateMillis;
        }
        c.close();
        return info;
    }
}