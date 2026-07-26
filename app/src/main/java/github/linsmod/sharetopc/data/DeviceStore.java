package github.linsmod.sharetopc.data;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

import github.linsmod.sharetopc.discovery.DiscoveredDevice;

/** 已知设备持久化存储（SharedPreferences + Gson），用于离线设备仍可显示。 */
public class DeviceStore {

    private static final String KEY_DEVICES = "known_devices";

    private final SharedPreferences prefs;
    private final Gson gson = new Gson();

    public DeviceStore(Context context) {
        this.prefs = PreferenceManager.getDefaultSharedPreferences(context);
    }

    /** 加载所有已知设备（isOnline = false）。 */
    public List<DiscoveredDevice> loadAll() {
        String json = prefs.getString(KEY_DEVICES, null);
        if (json == null) {
            return new ArrayList<>();
        }
        try {
            Type type = new TypeToken<List<KnownDevice>>() {}.getType();
            List<KnownDevice> list = gson.fromJson(json, type);
            List<DiscoveredDevice> result = new ArrayList<>();
            if (list != null) {
                for (KnownDevice kd : list) {
                    DiscoveredDevice d = new DiscoveredDevice(kd.name, kd.host, kd.port, false);
                    d.lastTransferId = kd.lastTransferId;
                    result.add(d);
                }
            }
            return result;
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    /** 记录一个设备（在线发现时调用），按 key 去重保存。 */
    public void saveDevice(DiscoveredDevice device) {
        List<KnownDevice> list = loadRawList();
        KnownDevice item = new KnownDevice(device.name, device.host, device.port);
        // 按 host:port 去重
        list.removeIf(kd -> kd.key().equals(item.key()));
        list.add(0, item);
        // 最多保留 50 条
        while (list.size() > 50) {
            list.remove(list.size() - 1);
        }
        prefs.edit().putString(KEY_DEVICES, gson.toJson(list)).apply();
    }

    private List<KnownDevice> loadRawList() {
        String json = prefs.getString(KEY_DEVICES, null);
        if (json == null) {
            return new ArrayList<>();
        }
        try {
            Type type = new TypeToken<List<KnownDevice>>() {}.getType();
            List<KnownDevice> list = gson.fromJson(json, type);
            return list != null ? list : new ArrayList<>();
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    /** 序列化用内部类。 */
    @SuppressWarnings("unused")
    private static class KnownDevice {
        String name;
        String host;
        int port;
        String lastTransferId;

        KnownDevice(String name, String host, int port) {
            this(name, host, port, null);
        }

        KnownDevice(String name, String host, int port, String lastTransferId) {
            this.name = name;
            this.host = host;
            this.port = port;
            this.lastTransferId = lastTransferId;
        }

        String key() {
            return host + ":" + port;
        }
    }

    /** 更新设备的 lastTransferId 并持久化。 */
    public void setLastTransferId(String host, int port, String transferId) {
        List<KnownDevice> list = loadRawList();
        String key = host + ":" + port;
        for (KnownDevice kd : list) {
            if (kd.key().equals(key)) {
                kd.lastTransferId = transferId;
                prefs.edit().putString(KEY_DEVICES, gson.toJson(list)).apply();
                return;
            }
        }
    }
}
