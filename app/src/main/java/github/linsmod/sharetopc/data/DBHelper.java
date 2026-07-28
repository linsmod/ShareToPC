package github.linsmod.sharetopc.data;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

public class DBHelper extends SQLiteOpenHelper {

    private static final String DB_NAME = "sendjob.db";
    private static final int DB_VERSION = 1;

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

    // ---------- Device 表 ----------
    public static final String TABLE_DEVICE = "device";
    public static final String COL_DEV_HOST = "host";
    public static final String COL_DEV_PORT = "port";
    public static final String COL_DEV_ID = "id";
    public static final String COL_DEV_NAME = "name";
    public static final String COL_DEV_LAST_SEND_ID = "lastJobId";
    public static final String COL_DEV_LAST_SEEN = "lastSeen";

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
                    COL_POLICY + " TEXT" +
                    ")";

    private static final String CREATE_DEVICE_TABLE =
            "CREATE TABLE " + TABLE_DEVICE + " (" +
                    COL_DEV_HOST + " TEXT NOT NULL, " +
                    COL_DEV_PORT + " INTEGER NOT NULL, " +
                    COL_DEV_NAME + " TEXT, " +
                    COL_DEV_ID + " TEXT, " +
                    COL_DEV_LAST_SEND_ID + " TEXT, " +
                    COL_DEV_LAST_SEEN + " INTEGER, " +
                    "PRIMARY KEY (" + COL_DEV_HOST + ", " + COL_DEV_PORT + ")" +
                    ")";

    public DBHelper(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL(CREATE_JOB_ENTRY_TABLE);
        db.execSQL(CREATE_DEVICE_TABLE);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
    }
}