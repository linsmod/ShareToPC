package github.linsmod.sharetopc.data;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import androidx.preference.PreferenceManager;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;

import github.linsmod.sharetopc.cleanup.CleanupPolicy;
import github.linsmod.sharetopc.discovery.DiscoveredDevice;

/** 设置存储：默认 SharedPreferences（与 PreferenceFragmentCompat 共用）。 */
public class SettingsStore {

    public static final String KEY_POLICY = "cleanup_policy";
    public static final String KEY_MANUAL_HOST = "manual_host";
    public static final String KEY_MANUAL_PORT = "manual_port";
    public static final String KEY_ALIASES = "device_aliases";
    public static final String KEY_SCAN_TIMEOUT = "scan_timeout";
    public static final int DEFAULT_PORT = 51234;
    public static final int DEFAULT_SCAN_TIMEOUT_SEC = 30;

    private final SharedPreferences prefs;
    private final Gson gson = new Gson();

    public SettingsStore(Context context) {
        this.prefs = PreferenceManager.getDefaultSharedPreferences(context);
    }

    public CleanupPolicy getCleanupPolicy() {
        String value = prefs.getString(KEY_POLICY, CleanupPolicy.AFTER_SEND.name());
        try {
            return CleanupPolicy.valueOf(value);
        } catch (IllegalArgumentException e) {
            return CleanupPolicy.AFTER_SEND;
        }
    }

    public String getManualHost() {
        return prefs.getString(KEY_MANUAL_HOST, "");
    }

    public int getManualPort() {
        String value = prefs.getString(KEY_MANUAL_PORT, String.valueOf(DEFAULT_PORT));
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return DEFAULT_PORT;
        }
    }

    /** 保存手动设备（主界面“手动添加”对话框）。 */
    public void saveManualDevice(String host, String port) {
        prefs.edit()
                .putString(KEY_MANUAL_HOST, host)
                .putString(KEY_MANUAL_PORT, port)
                .apply();
    }

    /** 配置了手动设备时返回之，否则 null。 */
    public DiscoveredDevice getManualDevice() {
        String host = getManualHost();
        if (TextUtils.isEmpty(host)) {
            return null;
        }
        DiscoveredDevice device = new DiscoveredDevice("手动设备", host.trim(), getManualPort());
        device.alias = getDeviceAlias(device.host, device.port);
        return device;
    }

    /** 扫描超时时长（秒），默认 30，限制在 5~120。 */
    public int getScanTimeoutSec() {
        String value = prefs.getString(KEY_SCAN_TIMEOUT, String.valueOf(DEFAULT_SCAN_TIMEOUT_SEC));
        try {
            int sec = Integer.parseInt(value.trim());
            return Math.max(5, Math.min(120, sec));
        } catch (NumberFormatException e) {
            return DEFAULT_SCAN_TIMEOUT_SEC;
        }
    }

    // ---- 设备备注名（key = host:port） ----

    public String getDeviceAlias(String host, int port) {
        return getAliases().get(host + ":" + port);
    }

    /** 设置备注名；null 或空白表示清除（恢复默认名）。 */
    public void setDeviceAlias(String host, int port, String alias) {
        Map<String, String> map = getAliases();
        String key = host + ":" + port;
        if (TextUtils.isEmpty(alias) || alias.trim().isEmpty()) {
            map.remove(key);
        } else {
            map.put(key, alias.trim());
        }
        prefs.edit().putString(KEY_ALIASES, gson.toJson(map)).apply();
    }

    private Map<String, String> getAliases() {
        String json = prefs.getString(KEY_ALIASES, null);
        if (json == null) {
            return new HashMap<>();
        }
        try {
            Type type = new TypeToken<Map<String, String>>() {
            }.getType();
            Map<String, String> map = gson.fromJson(json, type);
            return map != null ? map : new HashMap<>();
        } catch (Exception e) {
            return new HashMap<>();
        }
    }
}
