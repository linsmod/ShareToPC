package github.linsmod.sharetopc.discovery;

public class PairSession {
    public int id;
    public String deviceId;
    public String key;
    public long expiresAt;    // 过期时间戳（毫秒）
    public long issuedAt;     // 签发时间戳（毫秒）
}