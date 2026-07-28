package github.linsmod.sharetopc.data;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

public class DBHelper extends SQLiteOpenHelper {

    private static final String DB_NAME = "sendjob.db";
    private static final int DB_VERSION = 5;

    // ---------- job entry 表 ----------
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
    public static final String COL_POLICY = "policy";
    public static final String COL_PENDING_ACTION = "pendingAction";

    // ---------- Device 表 ----------
    public static final String TABLE_DEVICE = "device";
    public static final String COL_DEV_HOST = "host";
    public static final String COL_DEV_PORT = "port";
    public static final String COL_DEV_ID = "id";
    public static final String COL_DEV_NAME = "name";
    public static final String COL_DEV_LAST_SEND_ID = "latestJobId";
    public static final String COL_DEV_LAST_SEEN = "lastSeen";
    public static final String COL_DEV_VERSION = "version";
    public static final String COL_DEV_PROTO_VERSION = "protoVersion";

    // ---------- Job 表 ----------
    public static final String TABLE_JOB = "job";
    public static final String COL_JOB_ID_PK = "jobId";
    public static final String COL_JOB_DEVICE_HOST = "deviceHost";
    public static final String COL_JOB_DEVICE_PORT = "devicePort";
    public static final String COL_JOB_DEVICE_NAME = "deviceName";
    public static final String COL_JOB_TIME_MILLIS = "timeMillis";
    public static final String COL_JOB_TOTAL_ENTRIES = "totalEntries";
    public static final String COL_JOB_COMPLETED_ENTRIES = "completedEntries";

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
                    COL_POLICY + " TEXT, " +
                    COL_PENDING_ACTION + " INTEGER DEFAULT 0" +
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
                    "PRIMARY KEY (" + COL_DEV_HOST + ", " + COL_DEV_PORT + ")" +
                    ")";

    private static final String CREATE_JOB_TABLE =
            "CREATE TABLE " + TABLE_JOB + " (" +
                    COL_JOB_ID_PK + " TEXT PRIMARY KEY, " +
                    COL_JOB_DEVICE_HOST + " TEXT, " +
                    COL_JOB_DEVICE_PORT + " INTEGER, " +
                    COL_JOB_DEVICE_NAME + " TEXT, " +
                    COL_JOB_TIME_MILLIS + " INTEGER, " +
                    COL_JOB_TOTAL_ENTRIES + " INTEGER, " +
                    COL_JOB_COMPLETED_ENTRIES + " INTEGER" +
                    ")";

    public DBHelper(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL(CREATE_JOB_ENTRY_TABLE);
        db.execSQL(CREATE_DEVICE_TABLE);
        db.execSQL(CREATE_JOB_TABLE);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            db.execSQL(CREATE_JOB_TABLE);
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE " + TABLE_JOB_ENTRY + " ADD COLUMN " + COL_PENDING_ACTION + " INTEGER DEFAULT 0");
        }
        if (oldVersion < 4) {
            db.execSQL("ALTER TABLE " + TABLE_DEVICE + " ADD COLUMN " + COL_DEV_VERSION + " TEXT");
        }
        if (oldVersion < 5) {
            db.execSQL("ALTER TABLE " + TABLE_DEVICE + " ADD COLUMN " + COL_DEV_PROTO_VERSION + " TEXT");
        }
    }
}