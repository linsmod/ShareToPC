package github.linsmod.sharetopc.cleanup;

import android.content.Context;

import androidx.work.Data;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import github.linsmod.sharetopc.data.PendingContent;
import github.linsmod.sharetopc.util.FileUtils;
import github.linsmod.sharetopc.util.LogX;

/** 按清理策略处理已发送文件，并维护 meta.json 元数据。 */
public class CleanupScheduler {

    public static final String META_FILE_NAME = "meta.json";
    private static final String SCAN_WORK_NAME = "inbox_orphan_scan";
    private static final String WORK_NAME_PREFIX = "cleanup_";

    private static final Gson gson = new Gson();
    private static final Object metaLock = new Object();

    private final Context context;

    public CleanupScheduler(Context context) {
        this.context = context.getApplicationContext();
    }

    /** 传输全部成功后调用：按策略处理任务关联的文件。 */
    public void onTransferSuccess(List<PendingContent> items, CleanupPolicy policy) {
        for (PendingContent item : items) {
            if (item.type != PendingContent.TYPE_FILE || item.localPath == null) {
                continue;
            }
            File file = new File(item.localPath);
            switch (policy) {
                case AFTER_SEND:
                    FileUtils.deleteQuietly(file);
                    removeMeta(context, item.localPath);
                    break;
                case ONE_WEEK:
                case ONE_MONTH:
                    putMeta(context, item.localPath, policy,
                            policy.expireAt(System.currentTimeMillis()));
                    scheduleFileWork(item.localPath, policy);
                    break;
                case NEVER:
                    putMeta(context, item.localPath, policy, 0L);
                    break;
                default:
                    break;
            }
        }
    }

    /** 为尚未调度的文件补调度（如策略从设置页变更后重新入列时）。 */
    public void scheduleFileWork(String path, CleanupPolicy policy) {
        long delayMillis = policy == CleanupPolicy.ONE_WEEK
                ? 7L * 24 * 3600 * 1000
                : 30L * 24 * 3600 * 1000;
        Long expireAt = getMetaExpireAt(context, path);
        if (expireAt != null && expireAt > 0) {
            delayMillis = Math.max(0, expireAt - System.currentTimeMillis());
        }
        Data input = new Data.Builder()
                .putString(CleanupWorker.KEY_MODE, CleanupWorker.MODE_FILE)
                .putString(CleanupWorker.KEY_PATH, path)
                .build();
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(CleanupWorker.class)
                .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
                .setInputData(input)
                .addTag(tagFor(path))
                .build();
        // 每个文件唯一工作：策略变更时 REPLACE 重新调度
        WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME_PREFIX + path.hashCode(), ExistingWorkPolicy.REPLACE, request);
    }

    /** 兜底周期扫描：每 24h 清理孤儿文件。 */
    public void ensurePeriodicScan() {
        PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(
                CleanupWorker.class, 24, TimeUnit.HOURS)
                .setInputData(new Data.Builder()
                        .putString(CleanupWorker.KEY_MODE, CleanupWorker.MODE_SCAN)
                        .build())
                .build();
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                SCAN_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request);
    }

    private static String tagFor(String path) {
        return "cleanup_tag_" + path.hashCode();
    }

    // ---------------- meta.json 维护 ----------------

    private static File metaFile(Context context) {
        return new File(FileUtils.inboxRoot(context), META_FILE_NAME);
    }

    private static Map<String, Meta> loadMeta(Context context) {
        synchronized (metaLock) {
            File f = metaFile(context);
            if (!f.exists()) {
                return new HashMap<>();
            }
            try (FileReader reader = new FileReader(f)) {
                Type type = new TypeToken<Map<String, Meta>>() {
                }.getType();
                Map<String, Meta> map = gson.fromJson(reader, type);
                return map != null ? map : new HashMap<>();
            } catch (Exception e) {
                LogX.e("load meta failed", e);
                return new HashMap<>();
            }
        }
    }

    private static void saveMeta(Context context, Map<String, Meta> map) {
        synchronized (metaLock) {
            try (FileWriter writer = new FileWriter(metaFile(context))) {
                gson.toJson(map, writer);
            } catch (Exception e) {
                LogX.e("save meta failed", e);
            }
        }
    }

    public static void putMeta(Context context, String path, CleanupPolicy policy, long expireAt) {
        Map<String, Meta> map = loadMeta(context);
        Meta meta = new Meta();
        meta.policy = policy.name();
        meta.expireAt = expireAt;
        map.put(path, meta);
        saveMeta(context, map);
    }

    public static void removeMeta(Context context, String path) {
        Map<String, Meta> map = loadMeta(context);
        if (map.remove(path) != null) {
            saveMeta(context, map);
        }
    }

    /** 返回文件的到期时间；无记录返回 null，永不清理返回 0。 */
    public static Long getMetaExpireAt(Context context, String path) {
        Meta meta = loadMeta(context).get(path);
        return meta != null ? meta.expireAt : null;
    }

    private static class Meta {
        String policy;
        long expireAt;
    }
}
