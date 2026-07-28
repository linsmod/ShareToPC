package github.linsmod.sharetopc.transfer;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import github.linsmod.sharetopc.BuildConfig;
import github.linsmod.sharetopc.util.FileUtils;

/**
 * 帧协议编解码（与电脑端共同约定 v1）：
 * [4字节 big-endian 头长度 H][H字节 UTF-8 JSON 头][可选 payload]
 */
public final class TransferProtocol {

    public static final int MAX_HEADER_BYTES = 64 * 1024;
    public static final int PROTOCOL_VERSION = 1;
    public static final String APP_VERSION = "ShareToPC/" + BuildConfig.VERSION_NAME;

    private static final Gson gson = new Gson();

    public interface Progress {
        void onProgress(long sentBytes, long totalBytes);
    }

    /** 供 writeFileFrame 在 chunk 间检查是否取消。 */
    public interface Cancellable {
        boolean isCancelRequested();
        void onCancelled();

        void cancel();
    }

    private TransferProtocol() {
    }

    /** 写无 payload 的帧（HELLO / TEXT / PING）。 */
    public static void writeFrame(DataOutputStream out, JsonObject header) throws IOException {
        writeHeader(out, header);
        out.flush();
    }

    /** 写带文件 payload 的帧（FILE），每个 chunk 都带帧头，chunk 之间允许插入 CANCEL 等控制帧。 */
    public static void writeFileFrame(DataOutputStream out, JsonObject header,
                                      File file, Progress progress,
                                      Cancellable cancellable) throws IOException {
        writeHeader(out, header);
        long total = file.length();
        long sent = 0;
        byte[] buf = new byte[FileUtils.BUFFER_SIZE];
        try (FileInputStream in = new FileInputStream(file)) {
            int n;
            while ((n = in.read(buf)) != -1) {
                // chunk 间检查取消：连续发 3 次 CANCEL 帧确保 PC 收到
                if (cancellable != null && cancellable.isCancelRequested()) {
                    JsonObject cancel = createCancelHeader();
                    writeFrame(out, cancel);
                    writeFrame(out, cancel);
                    writeFrame(out, cancel);
                    out.flush();
                    throw new IOException("Transfer cancelled by user");
                }
                writeChunkFrame(out, buf, 0, n);
                sent += n;
                if (progress != null) {
                    progress.onProgress(sent, total);
                }
            }
        }
        out.flush();
    }

    /** 向后兼容：不带 cancelChecker 的版本（内部调用等场景）。 */
    public static void writeFileFrame(DataOutputStream out, JsonObject header,
                                      File file, Progress progress) throws IOException {
        writeFileFrame(out, header, file, progress, null);
    }

    /** 从 InputStream 发送文件帧（不依赖本地 File，直接读流）。 */
    public static void writeFileFrame(DataOutputStream out, JsonObject header,
                                      InputStream contentStream, long total,
                                      Progress progress,
                                      Cancellable cancellable) throws IOException {
        writeHeader(out, header);
        long sent = 0;
        byte[] buf = new byte[FileUtils.BUFFER_SIZE];
        int n;
        while ((n = contentStream.read(buf)) != -1) {
            if (cancellable != null && cancellable.isCancelRequested()) {
                JsonObject cancel = createCancelHeader();
                writeFrame(out, cancel);
                writeFrame(out, cancel);
                writeFrame(out, cancel);
                out.flush();
                throw new IOException("Transfer cancelled by user");
            }
            writeChunkFrame(out, buf, 0, n);
            sent += n;
            if (progress != null) {
                progress.onProgress(sent, total > 0 ? total : sent);
            }
        }
        out.flush();
    }

    /** 发送一个文件数据 chunk 帧：[headerLen][{"type":"CHUNK","len":L}][L bytes data]。 */
    private static void writeChunkFrame(DataOutputStream out, byte[] data,
                                        int offset, int length) throws IOException {
        JsonObject chunk = new JsonObject();
        chunk.addProperty("type", "CHUNK");
        chunk.addProperty("len", length);
        writeHeader(out, chunk);
        out.write(data, offset, length);
    }

    /** 发送断点续传首帧：[headerLen][{"type":"RESUME_CHUNK","offset":O,"len":L}][L bytes data]。 */
    private static void writeResumeChunkFrame(DataOutputStream out, byte[] data,
                                              int offset, int length, long resumeOffset) throws IOException {
        JsonObject chunk = new JsonObject();
        chunk.addProperty("type", "RESUME_CHUNK");
        chunk.addProperty("offset", resumeOffset);
        chunk.addProperty("len", length);
        writeHeader(out, chunk);
        out.write(data, offset, length);
    }

    // ---- 仅写内容（不含 FILE 头）的变体，用于头已单独发送的场景 ----

    /** 仅写数据块（CHUNK + payload），不写 FILE 头。headless 模式，FILE 头已在之前单独发送。 */
    public static void writeFileContent(DataOutputStream out, InputStream contentStream,
                                        long total, Progress progress,
                                        Cancellable cancellable) throws IOException {
        writeContentLoop(out, contentStream, total, 0, progress, cancellable);
    }

    /** 断点续传数据写入：首包 RESUME_CHUNK，后续 CHUNK。不写 FILE 头。 */
    public static void writeFileContentWithResume(DataOutputStream out, InputStream contentStream,
                                                  long total, long startOffset,
                                                  Progress progress,
                                                  Cancellable cancellable) throws IOException {
        writeContentLoop(out, contentStream, total, startOffset, progress, cancellable);
    }

    /** 通用数据块循环：startOffset == 0 时全部用 CHUNK；> 0 时首包用 RESUME_CHUNK。 */
    private static void writeContentLoop(DataOutputStream out, InputStream contentStream,
                                         long total, long startOffset,
                                         Progress progress,
                                         Cancellable cancellable) throws IOException {
        long sent = 0;
        byte[] buf = new byte[FileUtils.BUFFER_SIZE];
        int n;
        boolean isResume = startOffset > 0;
        boolean firstChunk = true;
        while ((n = contentStream.read(buf)) != -1) {
            if (cancellable != null && cancellable.isCancelRequested()) {
                JsonObject cancel = createCancelHeader();
                writeFrame(out, cancel);
                writeFrame(out, cancel);
                writeFrame(out, cancel);
                out.flush();
                throw new IOException("Transfer cancelled by user");
            }
            if (isResume && firstChunk) {
                writeResumeChunkFrame(out, buf, 0, n, startOffset);
                firstChunk = false;
            } else {
                writeChunkFrame(out, buf, 0, n);
            }
            sent += n;
            if (progress != null) {
                progress.onProgress(startOffset + sent, total > 0 ? total : startOffset + sent);
            }
        }
        out.flush();
    }

    private static void writeHeader(DataOutputStream out, JsonObject header) throws IOException {
        byte[] bytes = gson.toJson(header).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_HEADER_BYTES) {
            throw new IOException("header too large: " + bytes.length);
        }
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    /** 读一帧的 JSON 头（ACK / PONG 等无 payload 帧）。 */
    public static JsonObject readHeader(DataInputStream in) throws IOException {
        int len = in.readInt();
        if (len <= 0 || len > MAX_HEADER_BYTES) {
            throw new IOException("invalid header length: " + len);
        }
        byte[] bytes = new byte[len];
        in.readFully(bytes);
        String json = new String(bytes, StandardCharsets.UTF_8);
        try {
            return JsonParser.parseString(json).getAsJsonObject();
        } catch (Exception e) {
            throw new IOException("bad header json", e);
        }
    }

    public static boolean isAckOk(JsonObject header) {
        return header != null
                && "ACK".equals(optString(header, "type"))
                && "OK".equals(optString(header, "status"));
    }

    /** PC 返回断点续传偏移量时，ACK status 为 "RESUME"。 */
    public static boolean isAckResume(JsonObject header) {
        return header != null
                && "ACK".equals(optString(header, "type"))
                && "RESUME".equals(optString(header, "status"));
    }

    /** 从 RESUME ACK 中提取 offset 字段，若缺失则返回 0。 */
    public static long getAckOffset(JsonObject header) {
        return header != null && header.has("offset") && !header.get("offset").isJsonNull()
                ? header.get("offset").getAsLong() : 0L;
    }

    public static String ackError(JsonObject header) {
        String msg = optString(header, "msg");
        return msg != null ? msg : "unknown";
    }

    public static String optString(JsonObject obj, String key) {
        return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsString() : null;
    }

    public static boolean isEof(Throwable t) {
        return t instanceof EOFException;
    }

    /** 构造 CANCEL 帧头（Android 取消传输时通知 PC）。 */
    public static JsonObject createCancelHeader() {
        JsonObject header = new JsonObject();
        header.addProperty("type", "CANCEL");
        return header;
    }
}
