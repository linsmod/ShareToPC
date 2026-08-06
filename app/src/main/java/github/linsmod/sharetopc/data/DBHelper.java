package github.linsmod.sharetopc.data;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

public class DBHelper extends SQLiteOpenHelper {

    private static final String DB_NAME = "sendjob.db";
    private static final int DB_VERSION = 3;

    // ---------- job_entry 表 ----------
    public static final String TABLE_JOB_ENTRY = "job_entry";
    public static final String COL_ID = "id";
    public static final String COL_JOB_ID = "jobId";
    public static final String COL_TIME_MILLIS = "timeMillis";
    public static final String COL_CONTENT_TYPE = "contentType";
    public static final String COL_NAME = "name";
    public static final String COL_SIZE = "size";
    public static final String COL_MIME = "mime";
    public static final String COL_TRANSFER_ID = "transferId";
    public static final String COL_IDLE_REASON = "idleReason";
    public static final String COL_STATUS = "status";
    public static final String COL_SENT_BYTES = "sentBytes";
    public static final String COL_TOTAL_BYTES = "totalBytes";
    public static final String COL_ERROR_MSG = "errorMsg";
    public static final String COL_TEXT_CONTENT = "textContent";
    public static final String COL_LOCAL_PATH = "localPath";
    public static final String COL_CONTENT_URI = "contentUri";
    public static final String COL_HOST = "host";
    public static final String COL_PORT = "port";
    public static final String COL_TARGET_NAME = "targetName";
    public static final String COL_UPDATE_MILLIS = "updateMillis";   // 原 v6 添加

    // ---------- Device 表 ----------
    public static final String TABLE_DEVICE = "device";
    public static final String COL_DEV_HOST = "host";
    public static final String COL_DEV_PORT = "port";
    public static final String COL_DEV_ID = "id";
    public static final String COL_DEV_NAME = "name";
    public static final String COL_DEV_LAST_SEND_ID = "latestJobId";
    public static final String COL_DEV_LAST_SEEN = "lastSeen";
    public static final String COL_DEV_VERSION = "version";          // 原 v4 添加
    public static final String COL_DEV_PROTO_VERSION = "protoVersion"; // 原 v5 添加
    public static final String COL_DEV_ADD_TIME = "addTime";          // v3 添加

    // ---------- PairSession 表 ----------
    public static final String TABLE_PAIR_SESSION = "pair_session";
    public static final String COL_PAIR_ID = "id";
    public static final String COL_PAIR_DEVICE_ID = "deviceId";
    public static final String COL_PAIR_KEY = "pairKey";
    public static final String COL_PAIR_EXPIRES_AT = "expiresAt";
    public static final String COL_PAIR_ISSUED_AT = "issuedAt";
    public static final String COL_PAIR_CREDITS = "credits";
    public static final String COL_PAIR_MAX_ITEMS = "maxItemsPerJob";
    public static final String COL_PAIR_MAX_BYTES = "maxBytesPerJob";

    // ---------- Job 表 ----------
    public static final String TABLE_JOB = "job";
    public static final String COL_JOB_ID_PK = "jobId";
    public static final String COL_JOB_DEVICE_HOST = "deviceHost";
    public static final String COL_JOB_DEVICE_PORT = "devicePort";
    public static final String COL_JOB_DEVICE_NAME = "deviceName";
    public static final String COL_JOB_DEVICE_ID = "deviceId";
    public static final String COL_JOB_TIME_MILLIS = "timeMillis";
    public static final String COL_JOB_UPDATE_MILLIS = "updateMillis"; // 原 v7 添加

    // 建表语句 —— 所有字段一次性定义完整
    private static final String CREATE_JOB_ENTRY_TABLE =
            "CREATE TABLE " + TABLE_JOB_ENTRY + " (" +
                    COL_ID + " TEXT PRIMARY KEY, " +
                    COL_JOB_ID + " TEXT, " +
                    COL_TIME_MILLIS + " INTEGER, " +
                    COL_CONTENT_TYPE + " TEXT, " +
                    COL_NAME + " TEXT, " +
                    COL_SIZE + " INTEGER, " +
                    COL_MIME + " TEXT, " +
                    COL_TRANSFER_ID + " TEXT, " +
                    COL_IDLE_REASON + " INTEGER, " +
                    COL_STATUS + " TEXT, " +
                    COL_SENT_BYTES + " INTEGER, " +
                    COL_TOTAL_BYTES + " INTEGER, " +
                    COL_ERROR_MSG + " TEXT, " +
                    COL_TEXT_CONTENT + " TEXT, " +
                    COL_LOCAL_PATH + " TEXT, " +
                    COL_CONTENT_URI + " TEXT, " +
                    COL_HOST + " TEXT, " +
                    COL_PORT + " INTEGER, " +
                    COL_TARGET_NAME + " TEXT, " +
                    COL_UPDATE_MILLIS + " INTEGER DEFAULT 0" +
                    ")";

    private static final String CREATE_DEVICE_TABLE =
            "CREATE TABLE " + TABLE_DEVICE + " (" +
                    COL_DEV_HOST + " TEXT NOT NULL, " +
                    COL_DEV_PORT + " INTEGER NOT NULL, " +
                    COL_DEV_NAME + " TEXT, " +
                    COL_DEV_ID + " TEXT, " +
                    COL_DEV_LAST_SEND_ID + " TEXT, " +
                    COL_DEV_LAST_SEEN + " INTEGER, " +
                    COL_DEV_VERSION + " TEXT, " +
                    COL_DEV_PROTO_VERSION + " TEXT, " +
                    COL_DEV_ADD_TIME + " INTEGER DEFAULT 0, " +
                    "PRIMARY KEY (" + COL_DEV_HOST + ", " + COL_DEV_PORT + ")" +
                    ")";

    private static final String CREATE_PAIR_SESSION_TABLE =
            "CREATE TABLE " + TABLE_PAIR_SESSION + " (" +
                    COL_PAIR_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    COL_PAIR_DEVICE_ID + " TEXT UNIQUE NOT NULL, " +
                    COL_PAIR_KEY + " TEXT NOT NULL, " +
                    COL_PAIR_EXPIRES_AT + " INTEGER DEFAULT 0, " +
                    COL_PAIR_ISSUED_AT + " INTEGER DEFAULT 0, " +
                    COL_PAIR_CREDITS + " INTEGER DEFAULT 0, " +
                    COL_PAIR_MAX_ITEMS + " INTEGER DEFAULT 0, " +
                    COL_PAIR_MAX_BYTES + " INTEGER DEFAULT 0" +
                    ")";

    private static final String CREATE_JOB_TABLE =
            "CREATE TABLE " + TABLE_JOB + " (" +
                    COL_JOB_ID_PK + " TEXT PRIMARY KEY, " +
                    COL_JOB_DEVICE_HOST + " TEXT, " +
                    COL_JOB_DEVICE_PORT + " INTEGER, " +
                    COL_JOB_DEVICE_NAME + " TEXT, " +
                    COL_JOB_DEVICE_ID + " TEXT, " +
                    COL_JOB_TIME_MILLIS + " INTEGER, " +
                    COL_JOB_UPDATE_MILLIS + " INTEGER DEFAULT 0" +   // 去掉了末尾多余的逗号
                    ")";

    public DBHelper(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL(CREATE_JOB_ENTRY_TABLE);
        db.execSQL(CREATE_DEVICE_TABLE);
        db.execSQL(CREATE_JOB_TABLE);
        db.execSQL(CREATE_PAIR_SESSION_TABLE);
        createDeviceIdUniqueIndex(db);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            // v2: 添加 deviceId 唯一索引
            // 建索引前先清理历史脏数据：若同一 deviceId 对应多条记录，仅保留最近出现的
            db.execSQL("DELETE FROM " + TABLE_DEVICE +
                    " WHERE " + COL_DEV_ID + " IS NOT NULL AND (" +
                    COL_DEV_HOST + ", " + COL_DEV_PORT + ") NOT IN (" +
                    "  SELECT " + COL_DEV_HOST + ", " + COL_DEV_PORT +
                    "  FROM (" +
                    "    SELECT " + COL_DEV_HOST + ", " + COL_DEV_PORT +
                    "           , MAX(" + COL_DEV_LAST_SEEN + ") AS mx" +
                    "    FROM " + TABLE_DEVICE +
                    "    WHERE " + COL_DEV_ID + " IS NOT NULL" +
                    "    GROUP BY " + COL_DEV_ID +
                    "  )" +
                    ")");
            createDeviceIdUniqueIndex(db);
        }
        if (oldVersion < 3) {
            // v3: 设备加入时间
            db.execSQL("ALTER TABLE " + TABLE_DEVICE
                    + " ADD COLUMN " + COL_DEV_ADD_TIME + " INTEGER DEFAULT 0");
        }
    }

    /** v2: deviceId 唯一索引，用于按设备身份去重（host:port 变化时合并为同一条记录）。 */
    private void createDeviceIdUniqueIndex(SQLiteDatabase db) {
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_device_id ON " + TABLE_DEVICE +
                "(" + COL_DEV_ID + ") WHERE " + COL_DEV_ID + " IS NOT NULL");
    }
}