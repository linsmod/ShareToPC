package github.linsmod.sharetopc.transfer;

import android.os.Build;

import com.google.gson.JsonObject;

import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

import github.linsmod.sharetopc.util.FileUtils;

/** 单次 TCP 会话：HELLO → 连续发送 TEXT/FILE，每条阻塞等 ACK。 */
public class TransferClient implements Closeable {

    public static final int CONNECT_TIMEOUT_MS = 5000;
    public static final int ACK_TIMEOUT_MS = 10000;

    private Socket socket;
    private DataOutputStream out;
    private DataInputStream in;
    private volatile boolean cancelled;

    public void connect(String host, int port) throws IOException {
        if (host == null || host.isEmpty()) {
            throw new IOException("主机地址为空");
        }
        if (port <= 0 || port > 65535) {
            throw new IOException("无效端口: " + port);
        }
        socket = new Socket();
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
        } catch (IOException e) {
            // 连接失败时主动关闭 socket，避免资源泄漏
            close();
            throw e;
        }
    }

    public void hello() throws IOException {
        JsonObject header = new JsonObject();
        header.addProperty("type", "HELLO");
        header.addProperty("device", Build.MODEL != null ? Build.MODEL : "Android");
        header.addProperty("version", TransferProtocol.PROTOCOL_VERSION);
        TransferProtocol.writeFrame(out, header);
        expectAck();
    }

    public void sendText(String text) throws IOException {
        JsonObject header = new JsonObject();
        header.addProperty("type", "TEXT");
        header.addProperty("text", text);
        header.addProperty("time", System.currentTimeMillis());
        TransferProtocol.writeFrame(out, header);
        expectAck();
    }

    /** 从 File 读文件发送（历史兼容：本地 inbox 仍有副本时使用）。 */
    public void sendFile(String fileName, String mime, File file,
                         TransferProtocol.Progress progress) throws IOException {
        JsonObject header = new JsonObject();
        header.addProperty("type", "FILE");
        header.addProperty("name", FileUtils.sanitizeFileName(fileName));
        header.addProperty("size", file.length());
        header.addProperty("mime", mime != null ? mime
                : FileUtils.guessMimeType(fileName));
        header.addProperty("time", System.currentTimeMillis());
        TransferProtocol.writeFileFrame(out, header, file, progress, () -> cancelled);
        expectAck();
    }

    /** 从 InputStream 读文件发送（新路径：直接从 content:// URI 读流，无需本地文件）。 */
    public void sendFileFromStream(String fileName, String mime, long totalSize,
                                   InputStream contentStream,
                                   TransferProtocol.Progress progress) throws IOException {
        JsonObject header = new JsonObject();
        header.addProperty("type", "FILE");
        header.addProperty("name", FileUtils.sanitizeFileName(fileName));
        header.addProperty("size", totalSize);
        header.addProperty("mime", mime != null ? mime
                : FileUtils.guessMimeType(fileName));
        header.addProperty("time", System.currentTimeMillis());
        TransferProtocol.writeFileFrame(out, header, contentStream, totalSize,
                progress, () -> cancelled);
        expectAck();
    }

    /** 阻塞等待 ACK；收到 ERROR 或异常类型时抛 IOException。 */
    private void expectAck() throws IOException {
        JsonObject ack = TransferProtocol.readHeader(in);
        if (TransferProtocol.isAckOk(ack)) {
            return;
        }
        if ("ACK".equals(TransferProtocol.optString(ack, "type"))) {
            throw new IOException("peer error: " + TransferProtocol.ackError(ack));
        }
        throw new IOException("unexpected frame: " + ack);
    }

    /** 标记为已取消，writeFileFrame 的 chunk 循环会在下一轮检查并发送 CANCEL 帧后停止。 */
    public void cancel() {
        this.cancelled = true;
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
    }
}
