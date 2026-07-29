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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import github.linsmod.sharetopc.transfer.TransferProtocol;
import github.linsmod.sharetopc.util.LogX;

/**
 * PING/PONG 心跳管理器：每个设备一条 TCP 长连接，用阻塞读代替 sleep 实现秒级断线感知。
 * <p>
 * 检测机制：
 * <ul>
 *   <li>PING 后读 PONG（超时 10s）→ 无响应即离线</li>
 *   <li>PONG 后继续阻塞读（超时 = PING 间隔）→ 收到 OFFLINE 或连接断开即时感知</li>
 *   <li>每次收到 PONG 都 re-confirm 在线，修正 mDNS onServiceLost 等误标记</li>
 * </ul>
 */
public class HeartbeatManager {

    private static final long PING_INTERVAL_MS = 15_000;  // PING 间隔
    private static final int CONNECT_TIMEOUT_MS = 5_000;   // TCP 建连超时
    private static final int READ_TIMEOUT_MS = 10_000;     // PING 后等 PONG 的超时

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
        while (running) {
            try {
                maintainConnection(device);
            } catch (Exception e) {
                LogX.d("Hb-" + device.address() + ": " + e.getMessage());
            }
            // maintainConnection 退出 = 断线，确保标记离线
            discoveryManager.markOffline(device.host, device.port);
            // 稍等再重连
            if (running) {
                try { Thread.sleep(PING_INTERVAL_MS); } catch (InterruptedException e) { break; }
            }
        }
    }

    /** 建立并维护长连接。用阻塞读代替 sleep，即时感知 OFFLINE / 断线。 */
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
            hello.addProperty("device", android.os.Build.MODEL != null ? android.os.Build.MODEL : "Android");
            hello.addProperty("version", TransferProtocol.APP_VERSION);
            hello.addProperty("protoVersion", TransferProtocol.PROTOCOL_VERSION);
            hello.addProperty("deviceId", github.linsmod.sharetopc.App.get().settingsStore.getDeviceId());
            TransferProtocol.writeFrame(out, hello);
            JsonObject ack = TransferProtocol.readHeader(in);
            if (!"ACK".equals(TransferProtocol.optString(ack, "type"))
                    || !"OK".equals(TransferProtocol.optString(ack, "status"))) {
                LogX.e("Hb-" + device.address() + ": HELLO handshake failed");
                return;
            }
            LogX.d("Hb-" + device.address() + ": HELLO OK, online now");

            discoveryManager.markOnline(device.host, device.port);

            while (running) {
                // ① 发 PING 等 PONG（超时 10s）
                socket.setSoTimeout(READ_TIMEOUT_MS);
                JsonObject ping = new JsonObject();
                ping.addProperty("type", "PING");
                TransferProtocol.writeFrame(out, ping);

                JsonObject resp = TransferProtocol.readHeader(in);
                String type = TransferProtocol.optString(resp, "type");

                if ("OFFLINE".equals(type)) {
                    LogX.d("Hb-" + device.address() + ": OFFLINE → server shutting down");
                    discoveryManager.markOffline(device.host, device.port);
                    return;
                }
                if (!"PONG".equals(type)) {
                    LogX.d("Hb-" + device.address() + ": unexpected type=" + type);
                    discoveryManager.markOffline(device.host, device.port);
                    return;
                }

                // 收到 PONG → re-confirm 在线（修正 mDNS onServiceLost 误标记）
                discoveryManager.markOnline(device.host, device.port);

                // ② 阻塞等待下一个事件，超时 = PING 间隔
                //     收到 OFFLINE → 立即处理
                //     连接断开 → IOException → 外层 catch
                //     超时 → 正常，该发下一个 PING 了
                socket.setSoTimeout((int) PING_INTERVAL_MS);
                try {
                    resp = TransferProtocol.readHeader(in);
                    type = TransferProtocol.optString(resp, "type");
                    if ("OFFLINE".equals(type)) {
                        LogX.d("Hb-" + device.address() + ": OFFLINE (idle read)");
                        discoveryManager.markOffline(device.host, device.port);
                        return;
                    }
                    LogX.d("Hb-" + device.address() + ": unsolicited type=" + type);
                } catch (SocketTimeoutException e) {
                    // 超时 = 空闲了 PING_INTERVAL_MS，该发下个 PING 了
                }
            }
        } catch (SocketTimeoutException e) {
            // PING 后没等到 PONG
            LogX.d("Hb-" + device.address() + ": PING timeout");
        } catch (IOException e) {
            LogX.d("Hb-" + device.address() + ": connection lost (" + e.getMessage() + ")");
        } finally {
            try { socket.close(); } catch (IOException ignored) {}
        }
    }
}
