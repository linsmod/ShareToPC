package github.linsmod.sharetopc.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import github.linsmod.sharetopc.discovery.PairSession;

/**
 * PairSession 持久化存储，记录已配对的设备授权信息。
 */
public class PairStore {

    private final SQLiteDatabase db;

    public PairStore(Context context) {
        DBHelper helper = new DBHelper(context);
        this.db = helper.getWritableDatabase();
    }

    /**
     * 根据 deviceId 查找已有的 PairSession，未找到返回 null。
     */
    public synchronized PairSession getByDeviceId(String deviceId) {
        if (deviceId == null) return null;
        Cursor c = null;
        try {
            c = db.query(DBHelper.TABLE_PAIR_SESSION,
                    null,
                    DBHelper.COL_PAIR_DEVICE_ID + " = ?",
                    new String[]{deviceId},
                    null, null, null);
            if (c.moveToFirst()) {
                return cursorToPairSession(c);
            }
        } finally {
            if (c != null) c.close();
        }
        return null;
    }

    /**
     * 根据 pairKey 查找，未找到返回 null。
     */
    public synchronized PairSession getByKey(String pairKey) {
        if (pairKey == null) return null;
        Cursor c = null;
        try {
            c = db.query(DBHelper.TABLE_PAIR_SESSION,
                    null,
                    DBHelper.COL_PAIR_KEY + " = ?",
                    new String[]{pairKey},
                    null, null, null);
            if (c.moveToFirst()) {
                return cursorToPairSession(c);
            }
        } finally {
            if (c != null) c.close();
        }
        return null;
    }

    /**
     * 保存或更新 PairSession（按 deviceId 去重，冲突时替换）。
     */
    public synchronized void save(PairSession session) {
        ContentValues values = new ContentValues();
        values.put(DBHelper.COL_PAIR_DEVICE_ID, session.deviceId);
        values.put(DBHelper.COL_PAIR_KEY, session.key);
        values.put(DBHelper.COL_PAIR_EXPIRES_AT, session.expiresAt);
        values.put(DBHelper.COL_PAIR_ISSUED_AT, session.issuedAt);
        values.put(DBHelper.COL_PAIR_CREDITS, session.credits);
        values.put(DBHelper.COL_PAIR_MAX_ITEMS, session.maxItemsPerJob);
        values.put(DBHelper.COL_PAIR_MAX_BYTES, session.maxBytesPerJob);

        if (session.id > 0) {
            values.put(DBHelper.COL_PAIR_ID, session.id);
        }

        db.insertWithOnConflict(DBHelper.TABLE_PAIR_SESSION, null, values,
                SQLiteDatabase.CONFLICT_REPLACE);
    }

    /**
     * 更新 credits 值。
     */
    public synchronized void updateCredits(String deviceId, int credits) {
        ContentValues values = new ContentValues();
        values.put(DBHelper.COL_PAIR_CREDITS, credits);
        db.update(DBHelper.TABLE_PAIR_SESSION, values,
                DBHelper.COL_PAIR_DEVICE_ID + " = ?",
                new String[]{deviceId});
    }

    /**
     * 删除指定 deviceId 的配对记录。
     */
    public synchronized void delete(String deviceId) {
        db.delete(DBHelper.TABLE_PAIR_SESSION,
                DBHelper.COL_PAIR_DEVICE_ID + " = ?",
                new String[]{deviceId});
    }

    /**
     * 清空所有配对记录。
     */
    public synchronized void clearAll() {
        db.delete(DBHelper.TABLE_PAIR_SESSION, null, null);
    }

    private PairSession cursorToPairSession(Cursor c) {
        PairSession s = new PairSession();
        s.id = c.getInt(c.getColumnIndexOrThrow(DBHelper.COL_PAIR_ID));
        s.deviceId = c.getString(c.getColumnIndexOrThrow(DBHelper.COL_PAIR_DEVICE_ID));
        s.key = c.getString(c.getColumnIndexOrThrow(DBHelper.COL_PAIR_KEY));
        s.expiresAt = c.getLong(c.getColumnIndexOrThrow(DBHelper.COL_PAIR_EXPIRES_AT));
        s.issuedAt = c.getLong(c.getColumnIndexOrThrow(DBHelper.COL_PAIR_ISSUED_AT));
        s.credits = c.getInt(c.getColumnIndexOrThrow(DBHelper.COL_PAIR_CREDITS));
        s.maxItemsPerJob = c.getInt(c.getColumnIndexOrThrow(DBHelper.COL_PAIR_MAX_ITEMS));
        s.maxBytesPerJob = c.getLong(c.getColumnIndexOrThrow(DBHelper.COL_PAIR_MAX_BYTES));
        return s;
    }
}
