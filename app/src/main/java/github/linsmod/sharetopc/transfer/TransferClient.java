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

import github.linsmod.sharetopc.data.SendEntry;
import github.linsmod.sharetopc.util.FileUtils;

/** 单次 TCP 会话：HELLO → 连续发送 TEXT/FILE，每条阻塞等 ACK。 */
public class TransferClient implements Closeable {

    public static final int CONNECT_TIMEOUT_MS = 5000;
    public static final int ACK_TIMEOUT_MS = 10000;

    private Socket socket;
    private DataOutputStream out;
    private DataInputStream in;
    private int state;

    public static class State {
        public static final int IDLE = 0;
        public static final int CONNECTING = 1;
        public static final int SENDING = 1;
    }

    public int getState(){
        return this.state;
    }
    public void connect(String host, int port) throws IOException {
        if (host == null || host.isEmpty()) {
            throw new IOException("主机地址为空");
        }
        if (port <= 0 || port > 65535) {
            throw new IOException("无效端口: " + port);
        }
        socket = new Socket();
        state = State.CONNECTING;
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
            state = State.IDLE;
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
        this.state = State.SENDING;
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
    public void sendFile(TransferManager.SendJob job, SendEntry entry, File file,
                         TransferProtocol.Progress progress) throws IOException {
        sendFileHeader(entry.name, entry.mime, file.length(), entry.id);
        long resumeOffset = expectAckForFile();
        try (java.io.FileInputStream fin = new java.io.FileInputStream(file)) {
            skipExactly(fin, resumeOffset);
            if (resumeOffset > 0) {
                TransferProtocol.writeFileContentWithResume(out, fin, file.length(),
                        resumeOffset, progress, job);
            } else {
                TransferProtocol.writeFileContent(out, fin, file.length(), progress,
                        job);
            }
        }
        expectAck();
    }

    /** 从 InputStream 读文件发送（新路径：直接从 content:// URI 读流，无需本地文件）。 */
    public void sendFileFromStream(TransferManager.SendJob job, SendEntry entry,
                                   InputStream contentStream,
                                   TransferProtocol.Progress progress) throws IOException {
        sendFileHeader(entry.name, entry.mime, entry.size, entry.id);
        long resumeOffset = expectAckForFile();
        skipExactly(contentStream, resumeOffset);
        if (resumeOffset > 0) {
            TransferProtocol.writeFileContentWithResume(out, contentStream, entry.size,
                    resumeOffset, progress,job);
        } else {
            TransferProtocol.writeFileContent(out, contentStream, entry.size, progress,
                    job);
        }
        expectAck();
    }

    /** 发送 FILE 帧头（仅 JSON 头，不含 payload）。 */
    private void sendFileHeader(String fileName, String mime, long size, String transferId) throws IOException {
        JsonObject header = new JsonObject();
        header.addProperty("type", "FILE");
        header.addProperty("name", FileUtils.sanitizeFileName(fileName));
        header.addProperty("size", size);
        header.addProperty("mime", mime != null ? mime
                : FileUtils.guessMimeType(fileName));
        header.addProperty("time", System.currentTimeMillis());
        if (transferId != null) {
            header.addProperty("id", transferId);
        }
        TransferProtocol.writeFrame(out, header);
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
        if ("ACK".equals(TransferProtocol.optString(ack, "type"))) {
            throw new IOException("peer error: " + TransferProtocol.ackError(ack));
        }
        throw new IOException("unexpected frame: " + ack);
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
