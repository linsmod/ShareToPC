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

/** 设置存储：默认 SharedPreferences（与 PreferenceFragmentCompat 共用）。 */
public class SettingsStore {

    public static final String KEY_DEVICE_ID = "device_id";
    public static final String KEY_DEVICE_NAME = "device_name";
    public static final String KEY_ALIASES = "device_aliases";
    public static final String KEY_SCAN_TIMEOUT = "scan_timeout";
    public static final String KEY_LAST_HOST = "last_device_host";
    public static final String KEY_LAST_PORT = "last_device_port";
    public static final int DEFAULT_PORT = 51234;
    public static final int DEFAULT_SCAN_TIMEOUT_SEC = 30;

    private final SharedPreferences prefs;
    private final Gson gson = new Gson();

    public SettingsStore(Context context) {
        this.prefs = PreferenceManager.getDefaultSharedPreferences(context);
    }

    /** 记录上次分享使用的目标设备。 */
    public void saveLastDevice(String host, int port) {
        prefs.edit()
                .putString(KEY_LAST_HOST, host)
                .putInt(KEY_LAST_PORT, port)
                .apply();
    }

    /** 返回上次分享使用的目标设备的 host:port；无记录时返回 null。 */
    @androidx.annotation.Nullable
    public String getLastDeviceKey() {
        String host = prefs.getString(KEY_LAST_HOST, null);
        if (TextUtils.isEmpty(host)) return null;
        int port = prefs.getInt(KEY_LAST_PORT, DEFAULT_PORT);
        return host + ":" + port;
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

    /** 用户在设置中配的本机设备名（如未设置返回 null）。 */
    @androidx.annotation.Nullable
    public String getDeviceName() {
        String name = prefs.getString(KEY_DEVICE_NAME, null);
        if (name != null) {
            name = name.trim();
            if (name.isEmpty()) return null;
        }
        return name;
    }

    /** 获取本机设备唯一 ID，首次启动时自动生成并持久化。 */
    public String getDeviceId() {
        String id = prefs.getString(KEY_DEVICE_ID, null);
        if (TextUtils.isEmpty(id)) {
            id = java.util.UUID.randomUUID().toString();
            prefs.edit().putString(KEY_DEVICE_ID, id).apply();
        }
        return id;
    }
}
