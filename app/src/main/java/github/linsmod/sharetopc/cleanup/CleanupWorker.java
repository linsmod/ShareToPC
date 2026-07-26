package github.linsmod.sharetopc.cleanup;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.io.File;

import github.linsmod.sharetopc.util.FileUtils;
import github.linsmod.sharetopc.util.LogX;

/** 清理 Worker：按文件路径删除（FILE 模式），或兜底扫描孤儿文件（SCAN 模式）。 */
public class CleanupWorker extends Worker {

    public static final String KEY_MODE = "mode";
    public static final String KEY_PATH = "path";
    public static final String MODE_FILE = "file";
    public static final String MODE_SCAN = "scan";

    public CleanupWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        String mode = getInputData().getString(KEY_MODE);
        if (MODE_SCAN.equals(mode)) {
            return doScan();
        }
        String path = getInputData().getString(KEY_PATH);
        if (path == null) {
            return Result.failure();
        }
        File target = new File(path);
        FileUtils.deleteQuietly(target);
        CleanupScheduler.removeMeta(getApplicationContext(), path);
        LogX.d("cleanup done: " + path);
        return Result.success();
    }

    /** 兜底扫描：删除已到期的文件、超过 30 天且无元数据的孤儿文件，以及空目录。 */
    private Result doScan() {
        Context context = getApplicationContext();
        File root = FileUtils.inboxRoot(context);
        long now = System.currentTimeMillis();
        long orphanLimit = now - 30L * 24 * 3600 * 1000;
        File[] dateDirs = root.listFiles();
        if (dateDirs != null) {
            for (File dir : dateDirs) {
                if (!dir.isDirectory()) {
                    continue;
                }
                File[] files = dir.listFiles();
                if (files != null) {
                    for (File f : files) {
                        if (CleanupScheduler.META_FILE_NAME.equals(f.getName())) {
                            continue;
                        }
                        Long expireAt = CleanupScheduler.getMetaExpireAt(context, f.getAbsolutePath());
                        if (expireAt != null) {
                            if (expireAt > 0 && expireAt <= now) {
                                FileUtils.deleteQuietly(f);
                                CleanupScheduler.removeMeta(context, f.getAbsolutePath());
                            }
                        } else if (f.lastModified() < orphanLimit) {
                            // 无元数据且超过 30 天的孤儿文件
                            FileUtils.deleteQuietly(f);
                        }
                    }
                }
                // 清理空日期目录
                File[] remain = dir.listFiles();
                if (remain != null && remain.length == 0) {
                    FileUtils.deleteQuietly(dir);
                }
            }
        }
        LogX.d("cleanup scan done");
        return Result.success();
    }
}
