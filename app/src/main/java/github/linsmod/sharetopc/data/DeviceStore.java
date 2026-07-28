package github.linsmod.sharetopc.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.util.ArrayList;
import java.util.List;

import github.linsmod.sharetopc.discovery.DiscoveredDevice;

/** 已知设备持久化存储（SQLite），用于离线设备仍可显示。 */
public class DeviceStore {

    private final SQLiteDatabase db;

    public DeviceStore(Context context) {
        DBHelper helper = new DBHelper(context);
        this.db = helper.getWritableDatabase();
    }

    /** 加载所有已知设备（isOnline = false），按最后出现时间降序（最新的在前）。 */
    public synchronized List<DiscoveredDevice> loadAll() {
        List<DiscoveredDevice> result = new ArrayList<>();
        Cursor c = db.query(DBHelper.TABLE_DEVICE,
                null,
                null, null, null, null,
                DBHelper.COL_DEV_LAST_SEEN + " DESC");
        if (c != null) {
            while (c.moveToNext()) {
                String host = c.getString(c.getColumnIndexOrThrow(DBHelper.COL_DEV_HOST));
                int port = c.getInt(c.getColumnIndexOrThrow(DBHelper.COL_DEV_PORT));
                String name = c.getString(c.getColumnIndexOrThrow(DBHelper.COL_DEV_NAME));
                String lastJobId = c.getString(c.getColumnIndexOrThrow(DBHelper.COL_DEV_LAST_SEND_ID));
                DiscoveredDevice d = new DiscoveredDevice(name, host, port, false);
                d.lastJobId = lastJobId;
                result.add(d);
            }
            c.close();
        }
        return result;
    }

    /** 记录一个设备（在线发现时调用），按 (host, port) 去重，并更新最后出现时间。 */
    public synchronized void saveDevice(DiscoveredDevice device) {
        ContentValues values = new ContentValues();
        values.put(DBHelper.COL_DEV_HOST, device.host);
        values.put(DBHelper.COL_DEV_PORT, device.port);
        values.put(DBHelper.COL_DEV_NAME, device.name);
        values.put(DBHelper.COL_DEV_LAST_SEEN, System.currentTimeMillis());

        // 保留已有的 lastJobId（如果存在）
        String existingId = getLastJobId(device.host, device.port);
        values.put(DBHelper.COL_DEV_LAST_SEND_ID, existingId);

        // 插入或替换（主键冲突时更新）
        db.insertWithOnConflict(DBHelper.TABLE_DEVICE, null, values,
                SQLiteDatabase.CONFLICT_REPLACE);
    }

    /** 获取某个设备的 lastJobId（如果不存在返回 null）。 */
    private String getLastJobId(String host, int port) {
        Cursor c = db.query(DBHelper.TABLE_DEVICE,
                new String[]{DBHelper.COL_DEV_LAST_SEND_ID},
                DBHelper.COL_DEV_HOST + "=? AND " + DBHelper.COL_DEV_PORT + "=?",
                new String[]{host, String.valueOf(port)},
                null, null, null);
        String id = null;
        if (c.moveToFirst()) {
            id = c.getString(0);
            c.close();
        }
        return id;
    }

    /** 删除最旧的记录，使总数不超过 maxCount。 */
    private void trimToMax(int maxCount) {
        // 先查询总数
        Cursor countCursor = db.query(DBHelper.TABLE_DEVICE,
                new String[]{"COUNT(*)"}, null, null, null, null, null);
        long count = 0;
        if (countCursor.moveToFirst()) {
            count = countCursor.getLong(0);
        }
        countCursor.close();
        if (count <= maxCount) return;

        // 删除最旧的 (count - maxCount) 条（按 lastSeen ASC）
        String deleteSql = "DELETE FROM " + DBHelper.TABLE_DEVICE +
                " WHERE (" + DBHelper.COL_DEV_HOST + ", " + DBHelper.COL_DEV_PORT + ") IN (" +
                "  SELECT " + DBHelper.COL_DEV_HOST + ", " + DBHelper.COL_DEV_PORT +
                "  FROM " + DBHelper.TABLE_DEVICE +
                "  ORDER BY " + DBHelper.COL_DEV_LAST_SEEN + " ASC" +
                "  LIMIT " + (count - maxCount) +
                ")";
        db.execSQL(deleteSql);
    }

    /** 更新设备的 lastJobId 并更新最后出现时间。 */
    public synchronized void setLastJobId(String host, int port, String sendId) {
        ContentValues values = new ContentValues();
        values.put(DBHelper.COL_DEV_LAST_SEND_ID, sendId);
        values.put(DBHelper.COL_DEV_LAST_SEEN, System.currentTimeMillis());
        db.update(DBHelper.TABLE_DEVICE, values,
                DBHelper.COL_DEV_HOST + "=? AND " + DBHelper.COL_DEV_PORT + "=?",
                new String[]{host, String.valueOf(port)});
    }
}