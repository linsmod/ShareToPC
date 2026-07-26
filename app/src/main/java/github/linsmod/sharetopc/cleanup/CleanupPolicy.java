package github.linsmod.sharetopc.cleanup;

/** 清理策略。 */
public enum CleanupPolicy {
    /** 发送成功后立即删除 */
    AFTER_SEND,
    /** 保留 7 天 */
    ONE_WEEK,
    /** 保留 30 天 */
    ONE_MONTH,
    /** 不自动清理 */
    NEVER;

    public static CleanupPolicy fromName(String name, CleanupPolicy fallback) {
        if (name == null) {
            return fallback;
        }
        try {
            return valueOf(name);
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    /** 到期时间戳；不自动清理返回 0。 */
    public long expireAt(long fromMillis) {
        switch (this) {
            case ONE_WEEK:
                return fromMillis + 7L * 24 * 3600 * 1000;
            case ONE_MONTH:
                return fromMillis + 30L * 24 * 3600 * 1000;
            default:
                return 0L;
        }
    }
}
