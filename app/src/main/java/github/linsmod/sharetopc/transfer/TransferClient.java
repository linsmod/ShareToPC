package github.linsmod.sharetopc.transfer;

import com.google.gson.JsonObject;

import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

import github.linsmod.sharetopc.App;
import github.linsmod.sharetopc.data.SendEntry;
import github.linsmod.sharetopc.util.FileUtils;
import github.linsmod.sharetopc.util.LogX;

/** 单次 TCP 会话：用于注册设备或发送 TEXT/FILE。 */
public class TransferClient implements Closeable {
    public static final int CONNECT_TIMEOUT_MS = 5000;
    public static final int ACK_TIMEOUT_MS = 10000;
    private String host;
    private int port;
    final String pcDeviceId;

    private Socket socket;
    private DataOutputStream out;
    private DataInputStream in;
    private int state;

    public static final int DISCONNECTED = 0;
    public static final int CONNECTING = 1;
    public static final int CONNECTED = 2;
    public static final int CONNECTION_LOST = 3;
    private RemoteDeviceInfo rdi;

    public TransferClient(String pcDeviceId, String host, int port) {
        this.pcDeviceId = pcDeviceId;
        this.host = host;
        this.port = port;
    }

    /**
     * 更新连接目标地址（PC 换 IP 后由上层在重连前调用）。
     */
    public void updateTarget(String host, int port) {
        this.host = host;
        this.port = port;
    }

    public int getState(){
        return this.state;
    }
    public void connect() throws IOException {
        if(state==CONNECTED){
            return;
        }
        if (host == null || host.isEmpty()) {
            throw new IOException("主机地址为空");
        }
        if (port <= 0 || port > 65535) {
            throw new IOException("无效端口: " + port);
        }
        socket = new Socket();
        state = CONNECTING;
        try {
            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(ACK_TIMEOUT_MS);
            socket.setSendBufferSize(FileUtils.BUFFER_SIZE * 4);
            // 禁用 Nagle 算法：立即发送小块数据，避免 ACK 交互在后台被延迟合并
            socket.setTcpNoDelay(true);
            // 开启 TCP Keep-Alive：防止长时间空闲时连接被中间设备断开
            socket.setKeepAlive(true);
            out = new DataOutputStream(socket.getOutputStream());
            in = new DataInputStream(socket.getInputStream());
            state = CONNECTED;
        } catch (Exception e) {
            // 连接失败时主动关闭 socket，避免资源泄漏
            close();
            throw new NetworkError("连接失败: "+host+":"+port,e);
        }
    }

    /** 从 File 读文件发送（历史兼容：本地 inbox 仍有副本时使用）。 */
    public void sendFile(TransferManager.SendJob job, SendEntry entry, File file,String pairKey,
                         TransferProtocol.Progress progress) throws IOException {
        try (java.io.FileInputStream fin = new java.io.FileInputStream(file)) {
            sendFileFromStream(job,entry,fin,pairKey,progress);
        }
    }

    /** 从 InputStream 读文件发送（新路径：直接从 content:// URI 读流，无需本地文件）。 */
    public void sendFileFromStream(TransferManager.SendJob job, SendEntry entry,
                                   InputStream contentStream,
                                   String pairKey,
                                   TransferProtocol.Progress progress) throws IOException {
        sendFileHeader(entry.name, entry.mime,entry.objectType, entry.size, entry.id, job.id,pairKey);
        if(!job.isCancelRequested()){
            long resumeOffset = expectAckForFile();
            skipExactly(contentStream, resumeOffset);
            TransferProtocol.writeFileContent(out, contentStream, entry.size, progress,resumeOffset,
                        job,entry);
            expectAck(job);
        }
    }

    /** PULL_ACK 解析结果：status=OK 时 ok=true 并携带 size/seek/mime；status=REJECTED 时 ok=false 并携带 msg 原因。 */
    public static class PullAck {
        public final boolean ok;
        public final String msg;
        public final long size;
        public final long seek;
        public final String mime;

        public PullAck(boolean ok, String msg, long size, long seek, String mime) {
            this.ok = ok;
            this.msg = msg != null ? msg : "";
            this.size = size;
            this.seek = seek;
            this.mime = mime != null ? mime : "";
        }

        public boolean rejected() {
            return !ok;
        }
    }

    /** 读取并解析 PULL_ACK：status=OK→ok=true；status=REJECTED→ok=false（显式协议状态，非异常）。 */
    private PullAck expectPullAck() throws IOException {
        JsonObject ack = TransferProtocol.readHeader(in);
        if (!"PULL_ACK".equals(TransferProtocol.optString(ack, "type"))) {
            throw new IOException("expected PULL_ACK, got: " + ack);
        }
        boolean ok = "OK".equals(TransferProtocol.optString(ack, "status"));
        String msg = TransferProtocol.optString(ack, "msg");
        long size = ack.has("size") && !ack.get("size").isJsonNull() ? ack.get("size").getAsLong() : -1;
        long seek = ack.has("seek") && !ack.get("seek").isJsonNull() ? ack.get("seek").getAsLong() : 0;
        String mime = TransferProtocol.optString(ack, "mime");
        return new PullAck(ok, msg, size, seek, mime != null ? mime : "");
    }
    public PullAck pullFile(TransferManager.SendJob job, SendEntry entry,
                            TransferProtocol.Progress progress) throws IOException {
        File cacheDir = new File(App.get().getCacheDir(), "pulled");
        if (!cacheDir.exists()) {
            cacheDir.mkdirs();
        }
        File outFile = new File(cacheDir, FileUtils.sanitizeFileName(entry.name));
        long partialLen = outFile.exists() ? outFile.length() : 0L;

        TransferProtocol.writePullRequest(out, job.deviceId, entry.name, entry.remotePath,
                entry.size >= 0 ? entry.size : 0, partialLen, job.id);

        PullAck ack = expectPullAck();
        // 显式协议拒绝（status=REJECTED）：不流式，返回结果让上层标记"远程拒绝"
        if (ack.rejected()) {
            return ack;
        }
        long total = ack.size >= 0 ? ack.size : entry.size;
        long seek = ack.seek;
        // 优先用 PC 端注册表提供的 MIME（比本机按扩展名猜测更准）
        if (ack.mime != null && !ack.mime.isEmpty()) {
            entry.mime = ack.mime;
        }

        // 续传：仅当 PC 确认的 seek 与本地分片一致时追加写入，否则从头覆盖
        boolean append = seek > 0 && outFile.exists() && outFile.length() == seek;
        if (!append && outFile.exists()) {
            outFile.delete();
        }
        entry.localPath = outFile.getAbsolutePath();

        long received = seek;
        try (java.io.FileOutputStream fos = new java.io.FileOutputStream(outFile, append)) {
            while (true) {
                if (job.isCancelRequested()) break;
                JsonObject h = TransferProtocol.readHeader(in);
                String type = TransferProtocol.optString(h, "type");
                if ("PULL_END".equals(type)) break;
                if ("PULL_CHUNK".equals(type)) {
                    int len = h.has("len") && !h.get("len").isJsonNull() ? h.get("len").getAsInt() : 0;
                    if (len < 0 || len > 16 * 1024 * 1024) {
                        throw new IOException("invalid chunk len: " + len);
                    }
                    byte[] buf = new byte[len];
                    in.readFully(buf);
                    fos.write(buf);
                    received += len;
                    if (progress != null) progress.onProgress(entry, received, total);
                } else if ("ACK".equals(type)) {
                    throw new IOException("下载中断: " + TransferProtocol.ackError(h));
                } else {
                    throw new IOException("unexpected frame: " + type);
                }
            }
            fos.flush();
        }
        if (job.isCancelRequested()) {
            outFile.delete();
            if (progress != null) progress.onCanceled(entry, received, total);
            return ack;
        }
        if (progress != null) progress.onFinish(entry, received, total);
        return ack;
    }

    /** 发送 FILE 帧头（仅 JSON 头，不含 payload）。 */
    private void sendFileHeader(String fileName, String mime,int objectType, long size, String entryId, String jobId,String pairKey) throws IOException {
        JsonObject header = new JsonObject();
        header.addProperty("type", "OBJECT");
        header.addProperty("objectType", objectType);
        header.addProperty("name", FileUtils.sanitizeFileName(fileName));
        header.addProperty("size", size);
        header.addProperty("key",pairKey);
        header.addProperty("mime", mime != null ? mime
                : FileUtils.guessMimeType(fileName));
        header.addProperty("time", System.currentTimeMillis());
        if (entryId != null) {
            header.addProperty("id", entryId);
        }
        if (jobId != null) {
            header.addProperty("jobId", jobId);
        }
        TransferProtocol.writeFrame(out, header);
    }
    public static class NetworkError extends RuntimeException{
        public NetworkError(String s) {
            super(s);
        }
        public NetworkError(String s,Exception inner) {
            super(s,inner);
        }
    }
    public static class BadProtocol extends RuntimeException{
        public BadProtocol(String s) {
            super(s);
        }
        public BadProtocol(String s,Exception inner) {
            super(s,inner);
        }
    }

    /** 读取 FILE 的 ACK：普通 ACK/OK → 返回 0；RESUME → 返回 offset；否则抛异常。 */
    private long expectAckForFile() throws IOException {
        JsonObject ack = TransferProtocol.readHeader(in);
        if (TransferProtocol.isAckOk(ack)) {
            return 0;
        }
        if (TransferProtocol.isAckResume(ack)) {
            return TransferProtocol.getAckOffset(ack);
        }
        if(TransferProtocol.isAckBadProtocol(ack)){
            throw new BadProtocol("Server rejected communication with BadProtocol");
        }
        throw new NetworkError("unexpected frame: " + ack);
    }

    /** 跳过 InputStream 中指定字节数（兼容 skip() 返回 < bytesToSkip 的情况）。 */
    private static void skipExactly(InputStream in, long bytesToSkip) throws IOException {
        if (bytesToSkip <= 0) return;
        long remaining = bytesToSkip;
        while (remaining > 0) {
            long skipped = in.skip(remaining);
            if (skipped > 0) {
                remaining -= skipped;
            } else {
                // skip() 返回 0：逐字节跳过
                int b = in.read();
                if (b < 0) throw new IOException("Unexpected end of stream during skip");
                remaining--;
            }
        }
    }

    /** 阻塞等待 ACK；收到 ERROR 或异常类型时抛 IOException。 */
    private boolean expectAck(TransferManager.SendJob job) throws IOException {
        if(job.isCancelRequested()){
            return false;
        }
        JsonObject ack = TransferProtocol.readHeader(in);
        if (TransferProtocol.isAckOk(ack)) {
            return true;
        }
        if ("ACK".equals(TransferProtocol.optString(ack, "type"))) {
            throw new IOException("peer error: " + TransferProtocol.ackError(ack));
        }
        throw new IOException("unexpected frame: " + ack);
    }

    /** JOB_INFO 交换结果。 */
    public static class JobInfoResult {
        public final String pairKey;
        public final int credits;
        public final int maxItemsPerJob;
        public final long maxBytesPerJob;
        public final boolean cancelJob;
        private final String status;

        public JobInfoResult(String status, String pairKey, int credits,
                             int maxItemsPerJob, long maxBytesPerJob,boolean cancelJob) {
            this.status = status;
            this.pairKey = pairKey;
            this.credits = credits;
            this.maxItemsPerJob = maxItemsPerJob;
            this.maxBytesPerJob = maxBytesPerJob;
            this.cancelJob =cancelJob;
        }
        public final boolean accepted(){
            return "ACCEPTED".equals(this.status);
        }

        /**
         * @return is canceled by remote
         */
        public final boolean canceled(){
            return "CANCELED".equals(this.status);
        }
    }

    /**
     * 发送 JOB_INFO 帧，通知 PC 端本批次 job 的元信息，等待 PC 授权。
     * @return JobInfoResult，含 ACCEPTED/REJECTED 状态和 PC 返回的约束。
     */
    public JobInfoResult sendJobInfoAndWaitRemoteApproval(TransferManager.SendJob job, String deviceId, String pairKey, String jobId,
                                                          int itemCount, long totalBytes, TransferProtocol.Progress progress) throws IOException {
        JsonObject header = new JsonObject();
        header.addProperty("type", "JOB_INFO");
        header.addProperty("deviceId", deviceId);
        header.addProperty("pairKey", pairKey != null ? pairKey : "");
        header.addProperty("jobId", jobId != null ? jobId : "");
        header.addProperty("itemCount", itemCount);
        header.addProperty("totalBytes", totalBytes);
        TransferProtocol.writeFrame(out, header);
        return expectJobAck(job,progress);
    }
    void hello(TransferManager.SendJob job, boolean oneway) throws IOException {
        JsonObject header = new JsonObject();
        header.addProperty("type", "HELLO");
        String deviceName = App.get().settingsStore.getDeviceName();
            if (deviceName == null) {
                deviceName = android.os.Build.MODEL != null ? android.os.Build.MODEL : "Android";
            }
        header.addProperty("device", deviceName);
        header.addProperty("version", TransferProtocol.APP_VERSION);
        header.addProperty("protoVersion", TransferProtocol.PROTOCOL_VERSION);
        header.addProperty("deviceId", App.get().settingsStore.getDeviceId());
        header.addProperty("oneway", oneway);
        TransferProtocol.writeFrame(out, header);

        if(oneway || job.isCancelRequested()){
            return;
        }
        // 读 PC 的 HELLO ACK，其中携带 PC 程序版本、协议版本、设备 ID
        JsonObject ack = TransferProtocol.readHeader(in);
        if (!TransferProtocol.isAckOk(ack)) {
            if ("ACK".equals(TransferProtocol.optString(ack, "type"))) {
                throw new IOException("peer error: " + TransferProtocol.ackError(ack));
            }
            throw new IOException("unexpected frame: " + ack);
        }
        var deviceId = ack.has("deviceId") && !ack.get("deviceId").isJsonNull()
                ? ack.get("deviceId").getAsString() : "";
        if(!pcDeviceId.equals(deviceId)){
            LogX.d("HELLO: Mismatched deviceId: desired:"+ pcDeviceId +", got "+deviceId);
            return;
        }
        rdi.deviceName = ack.has("deviceName") && !ack.get("deviceName").isJsonNull()
                ? ack.get("version").getAsString() : "unknown";
        rdi.clientVersion = ack.has("version") && !ack.get("version").isJsonNull()
                ? ack.get("version").getAsString() : "unknown";
        rdi.protoVer = ack.has("protoVersion") && !ack.get("protoVersion").isJsonNull()
                ? ack.get("protoVersion").getAsInt() : 0;
        rdi.deviceId = ack.has("deviceId") && !ack.get("deviceId").isJsonNull()
                ? ack.get("deviceId").getAsString() : "";
        rdi.userName = ack.has("userName") && !ack.get("userName").isJsonNull()
                ? ack.get("userName").getAsString() : "";
        DeviceUserInfo.putUserName(host, rdi.userName);
        rdi.machineName = TransferProtocol.optString(ack, "machineName");
        if (rdi.machineName != null && !rdi.machineName.isEmpty()) {
            DeviceUserInfo.putMachineName(host, rdi.machineName);
        }
        rdi.userFolders = TransferProtocol.optStringMap(ack, "userFolders");
        if (rdi.userFolders != null && !rdi.userFolders.isEmpty()) {
            DeviceUserInfo.putUserFolders(host, rdi.userFolders);
        }
        rdi.drives = TransferProtocol.optDriveList(ack, "drives");
        if (rdi.drives != null && !rdi.drives.isEmpty()) {
            DeviceUserInfo.putDrives(host, rdi.drives);
        }
        String etpPort = TransferProtocol.optString(ack, "etpPort");
        if(etpPort!=null){
            rdi.etpPort = Integer.parseInt(etpPort);
            if(rdi.etpPort<21){
                rdi.etpPort=TransferProtocol.DEFAULT_ETP_PORT;
            }
            if(rdi.etpPort>65534){
                rdi.etpPort=TransferProtocol.DEFAULT_ETP_PORT;
            }
        }
        else{
            rdi.etpPort=TransferProtocol.DEFAULT_ETP_PORT;
        }
        android.util.Log.d("TransferClient",
                "HELLO OK: " + rdi.clientVersion + " proto v" + rdi.protoVer + " id=" + rdi.deviceId
                + " user=" + rdi.userName + " machine=" + rdi.machineName
                + " folders=" + rdi.userFolders + " drives=" + (rdi.drives != null ? rdi.drives.size() : 0)
                + ", self " + TransferProtocol.APP_VERSION + " proto v" + TransferProtocol.PROTOCOL_VERSION);
        // 持久化 PC 上报的 ETP 端口等身份信息到设备记录，供 Etp 页面按 deviceId 动态读取最新连接地址
            App.get().discoveryManager.updateDeviceInfo(host, port, rdi.clientVersion, rdi.protoVer, rdi.deviceId, rdi.etpPort);
        this.rdi = new RemoteDeviceInfo();
    }
    /** 读取 PC 返回的 JOB_ACK 帧，解析出授权结果。 */
    private JobInfoResult expectJobAck(TransferManager.SendJob job, TransferProtocol.Progress progress) throws IOException {
        long totalMillis = 0;
        boolean still = true;
        JsonObject ack = null;
        String status = "STILL";
        while (still && !job.isCancelRequested()){
            long start = System.currentTimeMillis();
            ack = TransferProtocol.readHeader(in);
            String type = TransferProtocol.optString(ack, "type");
            if (!"JOB_ACK".equals(type)) {
                throw new IOException("expected JOB_ACK, got: " + type);
            }
            status = TransferProtocol.optString(ack, "status");
            still="STILL".equals(status);
            totalMillis+= (System.currentTimeMillis()-start);
            if(still && totalMillis>30*1000){
                return null;
            }
            if(totalMillis>5){
                progress.onWaitingApproval(job.sendingItem);
            }
        }
        if(ack == null || job.isCancelRequested()){
            return null;
        }
        String key = TransferProtocol.optString(ack, "pairKey");
        int credits = ack.has("credits") && !ack.get("credits").isJsonNull()
                ? ack.get("credits").getAsInt() : 0;
        int maxItems = ack.has("maxItemsPerJob") && !ack.get("maxItemsPerJob").isJsonNull()
                ? ack.get("maxItemsPerJob").getAsInt() : 0;
        boolean cancelJob = ack.has("cancelJob") && !ack.get("cancelJob").isJsonNull() && ack.get("cancelJob").getAsBoolean();
        long maxBytes = ack.has("maxBytesPerJob") && !ack.get("maxBytesPerJob").isJsonNull()
                ? ack.get("maxBytesPerJob").getAsLong() : 0L;
        return new JobInfoResult(status, key, credits, maxItems, maxBytes,cancelJob);
    }

    @Override
    public void close() {
        // 先 flush 确保 CANCEL 帧等缓冲数据推入 socket 发送缓冲区
        try {
            if (out != null) out.flush();
        } catch (IOException ignored) {
        }
        // 优雅关闭输出方向：FIN 在所有缓冲数据之后发送，PC 能完整读到 CANCEL 帧
        try {
            if (socket != null && socket.isConnected()) socket.shutdownOutput();
        } catch (IOException ignored) {
        }
        FileUtils.closeQuietly(out);
        FileUtils.closeQuietly(in);
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
        this.rdi = null; // need resend job info
        this.state= CONNECTION_LOST;
    }
}
