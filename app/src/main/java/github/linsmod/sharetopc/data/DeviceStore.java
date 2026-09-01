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
                String version = c.getString(c.getColumnIndexOrThrow(DBHelper.COL_DEV_VERSION));
                String protoVersion = c.getString(c.getColumnIndexOrThrow(DBHelper.COL_DEV_PROTO_VERSION));
                String latestJobId = c.getString(c.getColumnIndexOrThrow(DBHelper.COL_DEV_LAST_SEND_ID));
                String deviceId = c.getString(c.getColumnIndexOrThrow(DBHelper.COL_DEV_ID));
                long addedTime = c.getLong(c.getColumnIndexOrThrow(DBHelper.COL_DEV_ADD_TIME));
                DiscoveredDevice d = new DiscoveredDevice(name, host, port, false);
                d.version = version;
                d.protoVersion = protoVersion;
                d.deviceId = deviceId;
                d.latestJobId = latestJobId;
                d.addedTimeMillis = addedTime;
                result.add(d);
            }
            c.close();
        }
        return result;
    }
    public synchronized DiscoveredDevice getById(String deviceId) {
        if (deviceId == null) return null;
        Cursor c = null;
        try {
            c = db.query(DBHelper.TABLE_DEVICE,
                    null, // 所有列
                    DBHelper.COL_DEV_ID + " = ?",
                    new String[]{deviceId},
                    null, null, null);
            if (c.moveToFirst()) {
                String host = c.getString(c.getColumnIndexOrThrow(DBHelper.COL_DEV_HOST));
                int port = c.getInt(c.getColumnIndexOrThrow(DBHelper.COL_DEV_PORT));
                String name = c.getString(c.getColumnIndexOrThrow(DBHelper.COL_DEV_NAME));
                String version = c.getString(c.getColumnIndexOrThrow(DBHelper.COL_DEV_VERSION));
                String protoVersion = c.getString(c.getColumnIndexOrThrow(DBHelper.COL_DEV_PROTO_VERSION));
                String latestJobId = c.getString(c.getColumnIndexOrThrow(DBHelper.COL_DEV_LAST_SEND_ID));
                String id = c.getString(c.getColumnIndexOrThrow(DBHelper.COL_DEV_ID));
                long addedTime = c.getLong(c.getColumnIndexOrThrow(DBHelper.COL_DEV_ADD_TIME));

                DiscoveredDevice device = new DiscoveredDevice(name, host, port, false);
                device.version = version;
                device.protoVersion = protoVersion;
                device.deviceId = id;
                device.latestJobId = latestJobId;
                device.addedTimeMillis = addedTime;
                return device;
            }
        } finally {
            if (c != null) c.close();
        }
        return null;
    }

    /**
     * 记录一个设备（在线发现时调用）。
     * 客户端唯一身份 = deviceId；deviceId 为空视为未确认身份，不作为有效客户端持久化（无效客户端）。
     * 去重规则：同一 deviceId 只允许保留一行，IP 变化即更新所在行（旧地址行自动清理）。
     */
    public synchronized void saveDevice(DiscoveredDevice device) {
        if (device.deviceId == null || device.deviceId.isEmpty()) {
            return; // 未确认身份的客户端不持久化
        }

        Long existing = findExistingAddTime(device);
        long addTime = existing != null ? existing : System.currentTimeMillis();
        device.addedTimeMillis = addTime;

        ContentValues values = new ContentValues();
        values.put(DBHelper.COL_DEV_HOST, device.host);
        values.put(DBHelper.COL_DEV_PORT, device.port);
        values.put(DBHelper.COL_DEV_NAME, device.name);
        values.put(DBHelper.COL_DEV_VERSION, device.version);
        values.put(DBHelper.COL_DEV_PROTO_VERSION, device.protoVersion);
        values.put(DBHelper.COL_DEV_ID, device.deviceId);
        values.put(DBHelper.COL_DEV_LAST_SEEN, System.currentTimeMillis());
        values.put(DBHelper.COL_DEV_ADD_TIME, addTime);

        values.put(DBHelper.COL_DEV_LAST_SEND_ID, device.latestJobId);

        // 插入或替换（主键冲突时更新）
        db.insertWithOnConflict(DBHelper.TABLE_DEVICE, null, values,
                SQLiteDatabase.CONFLICT_REPLACE);

        // 以 deviceId 为唯一身份标识：同一 deviceId 只允许存在一行，IP 变化即更新所在行。
        // 防御性清理（正常由 deviceId 唯一索引在插入时替换，此处兜底处理索引缺失/历史脏数据）。
        db.delete(DBHelper.TABLE_DEVICE,
                DBHelper.COL_DEV_ID + "=? AND NOT ("
                        + DBHelper.COL_DEV_HOST + "=? AND " + DBHelper.COL_DEV_PORT + "=?)",
                new String[]{device.deviceId, device.host, String.valueOf(device.port)});
        // 旧地址上 deviceId 仍为空的同名同端口残留记录视为同一台，一并清理，
        // 避免 PC 重启换 IP 前 mDNS TXT 解析失败写入的无 id 行长期残留。
        if (device.name != null && !device.name.isEmpty()) {
            db.delete(DBHelper.TABLE_DEVICE,
                    "((" + DBHelper.COL_DEV_ID + " IS NULL) OR (" + DBHelper.COL_DEV_ID + "='')) AND "
                            + DBHelper.COL_DEV_NAME + "=? AND " + DBHelper.COL_DEV_PORT + "=? AND NOT ("
                            + DBHelper.COL_DEV_HOST + "=? AND " + DBHelper.COL_DEV_PORT + "=?)",
                    new String[]{device.name, String.valueOf(device.port),
                            device.host, String.valueOf(device.port)});
        }
    }

    /** 查询已有记录的加入时间：优先按 deviceId 匹配（IP 变化视为同一台），其次按 host:port。 */
    private Long findExistingAddTime(DiscoveredDevice device) {
        Cursor c = null;
        try {
            if (device.deviceId != null) {
                c = db.query(DBHelper.TABLE_DEVICE, new String[]{DBHelper.COL_DEV_ADD_TIME},
                        DBHelper.COL_DEV_ID + "=?", new String[]{device.deviceId},
                        null, null, null, "1");
                if (c.moveToFirst()) {
                    long t = c.getLong(0);
                    if (t > 0) return t;
                }
                c.close();
                c = null;
            }
            c = db.query(DBHelper.TABLE_DEVICE, new String[]{DBHelper.COL_DEV_ADD_TIME},
                    DBHelper.COL_DEV_HOST + "=? AND " + DBHelper.COL_DEV_PORT + "=?",
                    new String[]{device.host, String.valueOf(device.port)},
                    null, null, null, "1");
            if (c.moveToFirst()) {
                long t = c.getLong(0);
                if (t > 0) return t;
            }
        } finally {
            if (c != null) c.close();
        }
        return null;
    }

    /** 删除指定设备。 */
    public synchronized void deleteDevice(String host, int port) {
        db.delete(DBHelper.TABLE_DEVICE,
                DBHelper.COL_DEV_HOST + "=? AND " + DBHelper.COL_DEV_PORT + "=?",
                new String[]{host, String.valueOf(port)});
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

    /** 更新设备的 latestJobId 并更新最后出现时间。 */
    public synchronized void setLatestJobId(String host, int port, String sendId) {
        ContentValues values = new ContentValues();
        values.put(DBHelper.COL_DEV_LAST_SEND_ID, sendId);
        values.put(DBHelper.COL_DEV_LAST_SEEN, System.currentTimeMillis());
        db.update(DBHelper.TABLE_DEVICE, values,
                DBHelper.COL_DEV_HOST + "=? AND " + DBHelper.COL_DEV_PORT + "=?",
                new String[]{host, String.valueOf(port)});
    }
}