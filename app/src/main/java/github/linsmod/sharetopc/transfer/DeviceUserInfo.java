package github.linsmod.sharetopc.transfer;

import android.os.Handler;
import android.os.Looper;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * PC 端 HELLO 握手上报的信息缓存（按 host）。
 * 由 PC 端通过 Windows API（SHGetKnownFolderPath / DriveInfo）提供真实用户目录与磁盘列表，
 * 供 ETP 浏览等场景按主机直接使用，避免猜测用户名/拼接错误路径。
 * 上报更新时会通知监听者（主线程回调），供浏览根视图及时刷新。
 */
public final class DeviceUserInfo {

    /** 某主机的上报信息更新通知（主线程回调）。 */
    public interface Listener {
        void onInfoUpdated(String host);
    }

    private static final ConcurrentHashMap<String, String> USER_NAMES = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, String> MACHINE_NAMES = new ConcurrentHashMap<>();
    /** host → {desktop/documents/downloads/videos/pictures/music → 真实路径}。 */
    private static final ConcurrentHashMap<String, Map<String, String>> USER_FOLDERS = new ConcurrentHashMap<>();
    /** host → 磁盘列表。 */
    private static final ConcurrentHashMap<String, List<RemoteDrive>> DRIVES = new ConcurrentHashMap<>();

    private static final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    private DeviceUserInfo() {}

    public static void addListener(Listener l) {
        if (l != null) listeners.add(l);
    }

    public static void removeListener(Listener l) {
        listeners.remove(l);
    }

    private static void notifyUpdated(String host) {
        if (host == null) return;
        mainHandler.post(() -> {
            for (Listener l : listeners) l.onInfoUpdated(host);
        });
    }

    public static void putUserName(String host, String userName) {
        if (host != null && userName != null && !userName.trim().isEmpty()) {
            USER_NAMES.put(host, userName.trim());
            notifyUpdated(host);
        }
    }

    /** 该主机上报的登录用户名；未握过手或未上报时返回 null。 */
    public static String getUserName(String host) {
        return host != null ? USER_NAMES.get(host) : null;
    }

    public static void putMachineName(String host, String machineName) {
        if (host != null && machineName != null && !machineName.trim().isEmpty()) {
            MACHINE_NAMES.put(host, machineName.trim());
            notifyUpdated(host);
        }
    }

    /** 该主机上报的计算机名（Environment.MachineName）；未上报时返回 null。 */
    public static String getMachineName(String host) {
        return host != null ? MACHINE_NAMES.get(host) : null;
    }

    public static void putUserFolders(String host, Map<String, String> folders) {
        if (host != null && folders != null && !folders.isEmpty()) {
            USER_FOLDERS.put(host, folders);
            notifyUpdated(host);
        }
    }

    /** 该主机上报的真实用户目录映射；未上报时返回 null。 */
    public static Map<String, String> getUserFolders(String host) {
        return host != null ? USER_FOLDERS.get(host) : null;
    }

    public static void putDrives(String host, List<RemoteDrive> drives) {
        if (host != null && drives != null && !drives.isEmpty()) {
            DRIVES.put(host, drives);
            notifyUpdated(host);
        }
    }

    /** 该主机上报的磁盘列表；未上报时返回 null。 */
    public static List<RemoteDrive> getDrives(String host) {
        return host != null ? DRIVES.get(host) : null;
    }
}
