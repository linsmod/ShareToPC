package github.linsmod.sharetopc.discovery;

import com.google.gson.JsonObject;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import github.linsmod.sharetopc.App;
import github.linsmod.sharetopc.transfer.TransferProtocol;
import github.linsmod.sharetopc.util.LogX;

/**
 * POLL/BEAT 心跳管理器：每个设备一条 TCP 长连接，用阻塞读接收服务端主动推送的 BEAT。
 * <p>
 * 机制：
 * <ul>
 *   <li>发 POLL 一次 → 服务端开始定期推 BEAT</li>
 *   <li>阻塞读 BEAT → 收到即确认在线（修正 mDNS onServiceLost 误标记）</li>
 *   <li>收到 OFFLINE 或读超时 → 判定离线</li>
 * </ul>
 */
public class HeartbeatManager {

    private static final int CONNECT_TIMEOUT_MS = 5_000;      // TCP 建连超时
    private static final int BEAT_TIMEOUT_MARGIN_MS = 5_000;  // 等 BEAT 比服务端心跳间隔多等 5 秒
    private static final int DEFAULT_HB_INTERVAL_MS = 30_000; // 服务端未下发时的默认心跳间隔

    // 指数退避重连：基础 2s，每次 ×2，上限 60s，加全抖动
    private static final long BASE_DELAY_MS = 2_000;
    private static final long MAX_DELAY_MS = 60_000;
    private static final Random RANDOM = new Random();

    private final NsdDiscoveryManager discoveryManager;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final ExecutorService connectionPool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "hb-conn");
        t.setDaemon(true);
        return t;
    });
    private final ConcurrentHashMap<String, Future<?>> connections = new ConcurrentHashMap<>();
    private volatile boolean running = false;

    public HeartbeatManager(NsdDiscoveryManager discoveryManager) {
        this.discoveryManager = discoveryManager;
    }

    /** 启动心跳。 */
    public void start() {
        if (running) return;
        running = true;
        LogX.d("Heartbeat started (persistent)");
        scheduler.scheduleWithFixedDelay(this::syncConnections, 0, 5, TimeUnit.SECONDS);
    }

    /** 停止所有心跳连接。 */
    public void stop() {
        running = false;
        scheduler.shutdownNow();
        for (Future<?> f : connections.values()) f.cancel(true);
        connections.clear();
        connectionPool.shutdownNow();
        LogX.d("Heartbeat stopped");
    }

    // ---- 同步设备列表 ----

    private void syncConnections() {
        if (!running) return;

        List<DiscoveredDevice> devices = discoveryManager.getDevices();
        Set<String> currentKeys = new HashSet<>();
        for (DiscoveredDevice d : devices) currentKeys.add(d.address());

        // 新设备 → 启连接
        for (DiscoveredDevice d : devices) {
            connections.computeIfAbsent(d.address(), k ->
                    connectionPool.submit(() -> connectionLoop(d)));
        }

        // 移除设备（不在列表中）→ 断连接
        Iterator<Map.Entry<String, Future<?>>> it = connections.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Future<?>> entry = it.next();
            if (!currentKeys.contains(entry.getKey())) {
                entry.getValue().cancel(true);
                it.remove();
            }
        }
    }

    // ---- 长连接循环 ----

    private void connectionLoop(DiscoveredDevice device) {
        int attempt = 1;
        while (running) {
            try {
                maintainConnection(device);
                // 正常断开（OFFLINE / BEAT 超时）→ 重置计数，下次快速重连
                attempt = 1;
            } catch (Exception e) {
                // 连接异常 → 指数退避
                LogX.d("Hb-" + device.address() + ": " + e.getMessage());
            }
            // maintainConnection 退出 = 断线，确保标记离线
            discoveryManager.markOffline(device.host, device.port);
            if (running) {
                long exponentialDelay = BASE_DELAY_MS * (1L << (attempt - 1)); // 2^(n-1)
                int capped = (int) Math.min(MAX_DELAY_MS, exponentialDelay);
                long jitter = (capped == 0) ? 0 : RANDOM.nextInt(capped + 1);
                long delay = capped + jitter;
                LogX.d("Hb-" + device.address() + ": reconnect #" + attempt + ", sleep " + delay + "ms");
                try { Thread.sleep(delay); } catch (InterruptedException e) { break; }
                attempt++;
            }
        }
    }

    /** 建立并维护长连接：发 POLL 一次，阻塞读服务端推送的 BEAT。 */
    private void maintainConnection(DiscoveredDevice device) throws Exception {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(device.host, device.port), CONNECT_TIMEOUT_MS);

            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            DataInputStream in = new DataInputStream(socket.getInputStream());

            LogX.d("Hb-" + device.address() + ": connected");

            // 先发 HELLO 握手，让 PC 注册此设备并回复 ACK
            JsonObject hello = new JsonObject();
            hello.addProperty("type", "HELLO");
            String deviceName = App.get().settingsStore.getDeviceName();
            if (deviceName == null) {
                deviceName = android.os.Build.MODEL != null ? android.os.Build.MODEL : "Android";
            }
            hello.addProperty("device", deviceName);
            hello.addProperty("version", TransferProtocol.APP_VERSION);
            hello.addProperty("protoVersion", TransferProtocol.PROTOCOL_VERSION);
            hello.addProperty("deviceId", App.get().settingsStore.getDeviceId());
            TransferProtocol.writeFrame(out, hello);
            JsonObject ack = TransferProtocol.readHeader(in);
            if (!"ACK".equals(TransferProtocol.optString(ack, "type"))
                    || !"OK".equals(TransferProtocol.optString(ack, "status"))) {
                LogX.e("Hb-" + device.address() + ": HELLO handshake failed");
                return;
            }
            // 服务端下发心跳间隔，读 BEAT 超时 = hbInterval + 余量
            long hbInterval = DEFAULT_HB_INTERVAL_MS;
            if (ack.has("hbInterval") && !ack.get("hbInterval").isJsonNull()) {
                hbInterval = ack.get("hbInterval").getAsLong();
            }
            int beatReadTimeout = (int) (hbInterval + BEAT_TIMEOUT_MARGIN_MS);
            LogX.d("Hb-" + device.address() + ": HELLO OK, hbInterval=" + hbInterval + "ms");

            discoveryManager.markOnline(device.host, device.port);

            // 发 POLL 一次，服务端收到后开始定期推送 BEAT
            JsonObject poll = new JsonObject();
            poll.addProperty("type", "POLL");
            TransferProtocol.writeFrame(out, poll);

            // 循环阻塞读 BEAT，直到断开或超时
            while (running) {
                socket.setSoTimeout(beatReadTimeout);
                try {
                    JsonObject resp = TransferProtocol.readHeader(in);
                    String type = TransferProtocol.optString(resp, "type");

                    if ("OFFLINE".equals(type)) {
                        LogX.d("Hb-" + device.address() + ": OFFLINE from server");
                        discoveryManager.markOffline(device.host, device.port);
                        return;
                    }
                    if ("BEAT".equals(type)) {
                        // 收到 BEAT → re-confirm 在线
                        discoveryManager.markOnline(device.host, device.port);
                        continue;
                    }
                    LogX.d("Hb-" + device.address() + ": unexpected type=" + type);
                    discoveryManager.markOffline(device.host, device.port);
                    return;
                } catch (SocketTimeoutException e) {
                    // 超时没收到 BEAT → 判定离线
                    LogX.d("Hb-" + device.address() + ": BEAT timeout");
                    return;
                }
            }
        } catch (SocketTimeoutException e) {
            LogX.d("Hb-" + device.address() + ": BEAT timeout");
        } catch (IOException e) {
            LogX.d("Hb-" + device.address() + ": connection lost (" + e.getMessage() + ")");
        } finally {
            try { socket.close(); } catch (IOException ignored) {}
        }
    }
}
