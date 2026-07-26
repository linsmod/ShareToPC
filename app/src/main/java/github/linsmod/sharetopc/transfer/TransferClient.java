package github.linsmod.sharetopc.transfer;

import android.os.Build;

import com.google.gson.JsonObject;

import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

import github.linsmod.sharetopc.data.PendingContent;
import github.linsmod.sharetopc.util.FileUtils;

/** 单次 TCP 会话：HELLO → 连续发送 TEXT/FILE，每条阻塞等 ACK。 */
public class TransferClient implements Closeable {

    public static final int CONNECT_TIMEOUT_MS = 5000;
    public static final int ACK_TIMEOUT_MS = 10000;

    private Socket socket;
    private DataOutputStream out;
    private DataInputStream in;

    public void connect(String host, int port) throws IOException {
        socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
        socket.setSoTimeout(ACK_TIMEOUT_MS);
        socket.setSendBufferSize(FileUtils.BUFFER_SIZE * 4);
        out = new DataOutputStream(socket.getOutputStream());
        in = new DataInputStream(socket.getInputStream());
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

    public void sendFile(PendingContent content, TransferProtocol.Progress progress) throws IOException {
        File file = new File(content.localPath);
        JsonObject header = new JsonObject();
        header.addProperty("type", "FILE");
        header.addProperty("name", FileUtils.sanitizeFileName(content.name));
        header.addProperty("size", file.length());
        header.addProperty("mime", content.mime != null ? content.mime
                : FileUtils.guessMimeType(content.name));
        header.addProperty("time", System.currentTimeMillis());
        TransferProtocol.writeFileFrame(out, header, file, progress);
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

    @Override
    public void close() {
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
