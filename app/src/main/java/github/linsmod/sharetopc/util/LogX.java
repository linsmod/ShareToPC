package github.linsmod.sharetopc.util;

import android.util.Log;

/** 统一日志入口。 */
public final class LogX {

    private static final String TAG = "ShareToPC";

    private LogX() {
    }

    public static void d(String msg) {
        Log.d(TAG, msg);
    }

    public static void i(String msg) {
        Log.i(TAG, msg);
    }

    public static void e(String msg) {
        Log.e(TAG, msg);
    }

    public static void e(String msg, Throwable t) {
        Log.e(TAG, msg, t);
    }
}
