package github.linsmod.sharetopc.transfer;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 临时 ETP 凭证缓存：deviceId → (sid, etpPass, etpDownloadPass, createdAt)。
 * HELLO 握手后存入，ETP 登录时取出并消费，60 秒过期自动清理。
 * 不持久化，跨 socket 授权用。
 */
public final class EtpCredentialsCache {

    private static final long EXPIRE_MS = 60_000; // 60 秒过期

    private static final ConcurrentHashMap<String, EtpCredentials> cache = new ConcurrentHashMap<>();

    private EtpCredentialsCache() {}

    /** 存入 ETP 凭证（由 sendFeat 后写入）。 */
    public static void put(String deviceId, String sid, int etpPort, String etpPass, String etpDownloadPass) {
        cache.put(deviceId, new EtpCredentials(sid, etpPort, etpPass, etpDownloadPass, System.currentTimeMillis()));
    }

    /** 取出并消费 ETP 凭证（一次性使用）。返回 null 表示无有效凭证。 */
    public static EtpCredentials take(String deviceId) {
        EtpCredentials cred = cache.remove(deviceId);
        if (cred != null && System.currentTimeMillis() - cred.createdAt > EXPIRE_MS) {
            return null; // 过期
        }
        return cred;
    }

    /** 查看但不消费（用于调试）。 */
    public static EtpCredentials peek(String deviceId) {
        EtpCredentials cred = cache.get(deviceId);
        if (cred != null && System.currentTimeMillis() - cred.createdAt > EXPIRE_MS) {
            cache.remove(deviceId);
            return null;
        }
        return cred;
    }

    /** 清理过期条目。 */
    public static void cleanup() {
        long now = System.currentTimeMillis();
        cache.entrySet().removeIf(e -> now - e.getValue().createdAt > EXPIRE_MS);
    }

    /** ETP 凭证数据类。 */
    public static final class EtpCredentials {
        public final String sid;
        public final int etpPort;
        public final String etpPass;
        public final String etpDownloadPass;
        public final long createdAt;

        public EtpCredentials(String sid, int etpPort, String etpPass, String etpDownloadPass, long createdAt) {
            this.sid = sid;
            this.etpPort = etpPort;
            this.etpPass = etpPass;
            this.etpDownloadPass = etpDownloadPass;
            this.createdAt = createdAt;
        }

        public boolean hasEtpPass() {
            return etpPass != null && !etpPass.isEmpty();
        }

        public boolean hasEtpDownloadPass() {
            return etpDownloadPass != null && !etpDownloadPass.isEmpty();
        }
    }
}
