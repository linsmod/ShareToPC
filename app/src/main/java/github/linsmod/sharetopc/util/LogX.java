package github.linsmod.sharetopc.util;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArraySet;

/** 统一日志入口，同步输出到 logcat 和内部存储文件。启动时覆盖旧日志。 */
public final class LogX {

    private static final String TAG = "ShareToPC";
    private static PrintWriter fileWriter;
    private static final SimpleDateFormat sdf = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US);
    private static final CopyOnWriteArraySet<OnLogListener> listeners = new CopyOnWriteArraySet<>();
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    public interface OnLogListener {
        void onNewLine(String line);
    }

    private LogX() {
    }

    /** 初始化文件日志，应在 Application.onCreate() 中调用。启动时覆盖旧文件。 */
    public static synchronized void init(Context context) {
        if (fileWriter != null) return;
        try {
            File logFile = new File(context.getFilesDir(), "share_to_pc.log");
            fileWriter = new PrintWriter(new BufferedWriter(new FileWriter(logFile, false)), true);
        } catch (Exception ignored) {
        }
    }

    /** 注册日志监听器（UI 实时刷新用）。 */
    public static void addListener(OnLogListener listener) {
        listeners.add(listener);
    }

    /** 移除日志监听器。 */
    public static void removeListener(OnLogListener listener) {
        listeners.remove(listener);
    }

    private static String now() {
        return sdf.format(new Date());
    }

    private static synchronized void write(char level, String msg) {
        String line = now() + " [" + level + "] " + msg;
        if (fileWriter != null) {
            try {
                fileWriter.println(line);
            } catch (Exception ignored) {
            }
        }
        // 通知所有监听器，切换到主线程
        if (!listeners.isEmpty()) {
            mainHandler.post(() -> {
                for (OnLogListener l : listeners) {
                    l.onNewLine(line);
                }
            });
        }
    }

    public static void d(String msg) {
        Log.d(TAG, msg);
        write('D', msg);
    }

    public static void i(String msg) {
        Log.i(TAG, msg);
        write('I', msg);
    }

    public static void e(String msg) {
        Log.e(TAG, msg);
        write('E', msg);
    }

    public static void e(String msg, Throwable t) {
        Log.e(TAG, msg, t);
        write('E', msg + "\n" + throwableToString(t));
    }

    private static String throwableToString(Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }
}
