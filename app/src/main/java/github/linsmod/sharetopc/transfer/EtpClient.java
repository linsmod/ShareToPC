package github.linsmod.sharetopc.transfer;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import github.linsmod.sharetopc.util.LogX;

/**
 * ETP (Everything Transfer Protocol) 客户端。
 * 连接 PC 上的 Everything ETP/FTP Server，执行文件搜索。
 *
 * 协议：基于 FTP 控制连接的文本命令，不需要 PASV/PORT 数据通道。
 *
 * 典型用法：
 * <pre>
 *   EtpClient c = new EtpClient(host, port);
 *   c.login(user, pass);
 *   List<EtpSearchResult> results = c.query("*.doc", EtpClient.SIZE_COLUMN | EtpClient.PATH_COLUMN);
 *   c.close();
 * </pre>
 */
public class EtpClient implements Closeable {

    public static final int DEFAULT_PORT = 21;
    public static final int CONNECT_TIMEOUT_MS = 5000;
    public static final int READ_TIMEOUT_MS = 15000;

    // 列标志位
    public static final int SIZE_COLUMN = 1;
    public static final int ATTRIBUTES_COLUMN = 1 << 1;
    public static final int DATE_MODIFIED_COLUMN = 1 << 2;
    public static final int DATE_CREATED_COLUMN = 1 << 3;
    public static final int PATH_COLUMN = 1 << 4;
    public static final int FILE_LIST_FILENAME_COLUMN = 1 << 5;
    public static final int DATE_RECENTLY_CHANGED_COLUMN = 1 << 6;

    private final String host;
    private final int port;
    private Socket socket;
    private BufferedReader reader;
    private OutputStream writer;
    private boolean loggedIn;

    public EtpClient(String host, int port) {
        this.host = host;
        this.port = port > 0 ? port : DEFAULT_PORT;
    }

    // ---- 连接 ----

    public void connect() throws IOException {
        if (host == null || host.isEmpty()) {
            throw new IOException("主机地址为空");
        }
        socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
        socket.setSoTimeout(READ_TIMEOUT_MS);
        socket.setTcpNoDelay(true);
        reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        writer = socket.getOutputStream();
        // 读取欢迎信息（220 ...）
        readWelcome();
        LogX.d("EtpClient connected to " + host + ":" + port);
    }

    private void readWelcome() throws IOException {
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.startsWith("220 ") || line.startsWith("421 ")) {
                break;
            }
            // 220- 前缀表示多行欢迎信息继续
        }
    }

    // ---- 登录 ----

    /**
     * 登录 ETP 服务器。如果用户名为空则尝试匿名登录。
     */
    public boolean login(String username, String password) throws IOException {
        if (username == null || username.isEmpty()) {
            username = "anonymous";
            password = "anonymous";
        }
        if (password == null) password = "";

        sendCommand("USER " + username);
        String resp = readResponse();
        if (resp.startsWith("331")) {
            sendCommand("PASS " + password);
            resp = readResponse();
        }
        if (resp.startsWith("230") || resp.startsWith("202")) {
            loggedIn = true;
            LogX.d("EtpClient logged in as " + username);
            return true;
        }
        LogX.d("EtpClient login failed: " + resp);
        return false;
    }

    // ---- EVERYTHING 搜索 ----

    /**
     * 发送 EVERYTHING 子命令（如 SEARCH、CASE、QUERY 等）。
     */
    private String sendEverything(String subCommand, String param) throws IOException {
        if (param != null && !param.isEmpty()) {
            sendCommand("EVERYTHING " + subCommand + " " + param);
        } else {
            sendCommand("EVERYTHING " + subCommand);
        }
        return readResponse();
    }

    /**
     * 执行搜索并返回结果列表。
     *
     * @param search  搜索关键字（支持 Everything 语法，如 "*.doc"、"abc"）
     * @param columns 需要的列，按位组合（如 {@code SIZE_COLUMN | PATH_COLUMN}）
     */
    public List<EtpSearchResult> query(String search, int columns) throws IOException {
        return query(search, columns, 0 /* matchCase */, 0 /* matchPath */, 0 /* matchRegex */,
                0 /* matchWholeWord */, null /* sortName */, true /* sortAsc */,
                0 /* offset */, 0 /* count */).items;
    }

    /**
     * 执行搜索并返回结果列表（完整参数，不含翻页）。
     *
     * @param search         搜索关键字
     * @param columns        需要的列（按位组合）
     * @param matchCase      区分大小写
     * @param matchPath      匹配完整路径
     * @param matchRegex     使用正则表达式
     * @param matchWholeWord 全字匹配
     * @param sortName       排序列名，如 "name"、"size"、"date_modified"，null 表示默认
     * @param sortAscending  true 升序 / false 降序
     */
    public List<EtpSearchResult> query(String search, int columns,
                                       int matchCase, int matchPath, int matchRegex,
                                       int matchWholeWord,
                                       String sortName, boolean sortAscending) throws IOException {
        return query(search, columns, matchCase, matchPath, matchRegex, matchWholeWord,
                sortName, sortAscending, 0 /* offset */, 0 /* count */).items;
    }

    // ---- 目录浏览（Everything parent: 查询） ----

    /**
     * 构造"列目录"搜索串：{@code parent:"<path>"}。
     * Everything 的 parent: 函数返回指定路径内的直接子项（文件+文件夹，不含递归子文件夹），
     * 适合逐级浏览。路径含空格时用双引号包裹。
     */
    public static String buildParentSearch(String path) {
        String p = path != null ? path : "";
        return "parent:\"" + p + "\"";
    }

    /**
     * 列出指定目录的直接子项（文件夹/文件），支持翻页。
     * 与 {@link #query} 相同参数语义，仅搜索串固定为 {@code parent:"<path>"}。
     */
    public QueryResult queryChildren(String path, int columns,
                                     int matchCase, int matchPath, int matchRegex,
                                     int matchWholeWord,
                                     String sortName, boolean sortAscending,
                                     int offset, int count) throws IOException {
        return query(buildParentSearch(path), columns, matchCase, matchPath, matchRegex,
                matchWholeWord, sortName, sortAscending, offset, count);
    }

    /**
     * 执行搜索并返回结果列表（完整参数 + 翻页）。
     *
     * @param search         搜索关键字
     * @param columns        需要的列（按位组合）
     * @param matchCase      区分大小写
     * @param matchPath      匹配完整路径
     * @param matchRegex     使用正则表达式
     * @param matchWholeWord 全字匹配
     * @param sortName       排序列名，如 "name"、"size"、"date_modified"，null 表示默认
     * @param sortAscending  true 升序 / false 降序
     * @param offset         翻页偏移（0 起始），≤0 表示不限制
     * @param count          最大返回条数，≤0 表示不限制
     */
    public QueryResult query(String search, int columns,
                             int matchCase, int matchPath, int matchRegex,
                             int matchWholeWord,
                             String sortName, boolean sortAscending,
                             int offset, int count) throws IOException {
        if (!loggedIn) throw new IOException("未登录");

        // 设置搜索选项
        sendEverything("CASE", String.valueOf(matchCase));
        sendEverything("PATH", String.valueOf(matchPath));
        sendEverything("REGEX", String.valueOf(matchRegex));
        sendEverything("WHOLE_WORD", String.valueOf(matchWholeWord));

        // 设置列
        sendEverything("SIZE_COLUMN", (columns & SIZE_COLUMN) != 0 ? "1" : "0");
        sendEverything("ATTRIBUTES_COLUMN", (columns & ATTRIBUTES_COLUMN) != 0 ? "1" : "0");
        sendEverything("DATE_MODIFIED_COLUMN", (columns & DATE_MODIFIED_COLUMN) != 0 ? "1" : "0");
        sendEverything("DATE_CREATED_COLUMN", (columns & DATE_CREATED_COLUMN) != 0 ? "1" : "0");
        sendEverything("PATH_COLUMN", (columns & PATH_COLUMN) != 0 ? "1" : "0");
        sendEverything("FILE_LIST_FILENAME_COLUMN", (columns & FILE_LIST_FILENAME_COLUMN) != 0 ? "1" : "0");
        sendEverything("DATE_RECENTLY_CHANGED_COLUMN", (columns & DATE_RECENTLY_CHANGED_COLUMN) != 0 ? "1" : "0");

        // 设置排序
        if (sortName != null && !sortName.isEmpty()) {
            String sortWithDir = sortAscending ? sortName + "_ascending" : sortName + "_descending";
            sendEverything("SORT", sortWithDir);
        }

        // 设置翻页
        if (offset > 0) sendEverything("OFFSET", String.valueOf(offset));
        if (count > 0) sendEverything("COUNT", String.valueOf(count));

        // 设置搜索字符串
        String resp = sendEverything("SEARCH", search);

        // 执行查询
        sendCommand("EVERYTHING QUERY");

        // 解析返回的多行结果
        return readQueryResults(columns);
    }

    // ---- 结果解析 ----

    /**
     * 解析 QUERY 命令返回的多行结果。
     * 格式：
     *   200-Query results
     *    RESULT_COUNT 123
     *    PATH C:\...
     *    SIZE 12345
     *    DATE_MODIFIED 133...
     *    FILE readme.txt
     *   200 End.
     */
    private QueryResult readQueryResults(int columns) throws IOException {
        List<EtpSearchResult> results = new ArrayList<>();
        long resultCount = 0;
        String currentPath = null;
        long currentSize = -1;
        long currentDateModified = -1;
        long currentDateCreated = -1;
        int currentAttributes = -1;
        String currentFileListFilename = null;
        long currentDateRecentlyChanged = -1;
        boolean inResults = false;

        String line;
        while ((line = reader.readLine()) != null) {
            // 去除行首可能的空格
            String trimmed = line.stripLeading();

            if (trimmed.startsWith("200-Query results")) {
                inResults = true;
                currentPath = null;
                currentSize = -1;
                currentDateModified = -1;
                currentDateCreated = -1;
                currentAttributes = -1;
                currentFileListFilename = null;
                currentDateRecentlyChanged = -1;
                continue;
            }

            if (trimmed.startsWith("200 End.")) {
                break;
            }

            if (!inResults) continue;

            if (trimmed.startsWith("RESULT_COUNT ")) {
                // 服务器返回的匹配总条数（与 COUNT 设置无关）
                try {
                    resultCount = Long.parseLong(trimmed.substring(13));
                } catch (NumberFormatException ignored) {
                }
                continue;
            }

            if ((columns & PATH_COLUMN) != 0 && trimmed.startsWith("PATH ")) {
                currentPath = trimmed.substring(5);
                continue;
            }

            if ((columns & SIZE_COLUMN) != 0 && trimmed.startsWith("SIZE ")) {
                try {
                    currentSize = Long.parseLong(trimmed.substring(5));
                } catch (NumberFormatException ignored) {
                }
                continue;
            }

            if ((columns & DATE_MODIFIED_COLUMN) != 0 && trimmed.startsWith("DATE_MODIFIED ")) {
                try {
                    currentDateModified = Long.parseLong(trimmed.substring(14));
                } catch (NumberFormatException ignored) {
                }
                continue;
            }

            if ((columns & DATE_CREATED_COLUMN) != 0 && trimmed.startsWith("DATE_CREATED ")) {
                try {
                    currentDateCreated = Long.parseLong(trimmed.substring(13));
                } catch (NumberFormatException ignored) {
                }
                continue;
            }

            if ((columns & ATTRIBUTES_COLUMN) != 0 && trimmed.startsWith("ATTRIBUTES ")) {
                try {
                    currentAttributes = Integer.parseInt(trimmed.substring(11));
                } catch (NumberFormatException ignored) {
                }
                continue;
            }

            if ((columns & FILE_LIST_FILENAME_COLUMN) != 0 && trimmed.startsWith("FILE_LIST_FILENAME ")) {
                currentFileListFilename = trimmed.substring(20);
                continue;
            }

            if ((columns & DATE_RECENTLY_CHANGED_COLUMN) != 0 && trimmed.startsWith("DATE_RECENTLY_CHANGED ")) {
                try {
                    currentDateRecentlyChanged = Long.parseLong(trimmed.substring(22));
                } catch (NumberFormatException ignored) {
                }
                continue;
            }

            // 文件名行: "FILE <name>" 或 "FOLDER <name>"
            if (trimmed.startsWith("FILE ")) {
                String name = trimmed.substring(5);
                results.add(new EtpSearchResult(false, name, currentPath,
                        currentSize, currentDateModified, currentDateCreated,
                        currentAttributes, currentFileListFilename, currentDateRecentlyChanged));
                // 重置 per-file 的列值
                currentPath = null;
                currentSize = -1;
                currentDateModified = -1;
                currentDateCreated = -1;
                currentAttributes = -1;
                currentFileListFilename = null;
                currentDateRecentlyChanged = -1;
            } else if (trimmed.startsWith("FOLDER ")) {
                String name = trimmed.substring(7);
                results.add(new EtpSearchResult(true, name, currentPath,
                        currentSize, currentDateModified, currentDateCreated,
                        currentAttributes, currentFileListFilename, currentDateRecentlyChanged));
                currentPath = null;
                currentSize = -1;
                currentDateModified = -1;
                currentDateCreated = -1;
                currentAttributes = -1;
                currentFileListFilename = null;
                currentDateRecentlyChanged = -1;
            }
        }

        LogX.d("EtpClient query: " + results.size() + " results, total " + resultCount);
        return new QueryResult(results, resultCount);
    }

    // ---- 基础命令 ----

    private void sendCommand(String cmd) throws IOException {
        LogX.d("ETP> " + cmd);
        writer.write((cmd + "\r\n").getBytes(StandardCharsets.UTF_8));
        writer.flush();
    }

    /**
     * 读取单行响应（以 3 位数字开头的 FTP 响应行或多行响应）。
     * 多行响应格式：nnn-...（中间行），nnn ...（最后一行）。
     */
    private String readResponse() throws IOException {
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line);
            // 单行响应（nnn 后是空格）或多行最后一行（nnn 后是空格）
            if (line.length() >= 4 && line.charAt(3) == ' ') {
                return sb.toString();
            }
            // 如果行不以 nnn- 开头，也视为结束
            if (line.length() < 4 || line.charAt(3) != '-') {
                return sb.toString();
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    // ---- 关闭 ----

    public boolean isConnected() {
        return socket != null && socket.isConnected() && !socket.isClosed();
    }

    public boolean isLoggedIn() {
        return loggedIn;
    }

    public String getHost() { return host; }
    public int getPort() { return port; }

    @Override
    public void close() {
        loggedIn = false;
        if (writer != null && socket != null && socket.isConnected()) {
            try {
                sendCommand("QUIT");
            } catch (IOException ignored) {
            }
        }
        try {
            if (reader != null) reader.close();
        } catch (IOException ignored) {
        }
        try {
            if (writer != null) writer.close();
        } catch (IOException ignored) {
        }
        try {
            if (socket != null) socket.close();
        } catch (IOException ignored) {
        }
        reader = null;
        writer = null;
        socket = null;
        LogX.d("EtpClient closed");
    }

    // ---- 搜索结果数据类 ----

    /** 查询结果：条目列表 + 服务器返回的匹配总条数（RESULT_COUNT）。 */
    public static class QueryResult {
        public final List<EtpSearchResult> items;
        public final long resultCount;
        public QueryResult(List<EtpSearchResult> items, long resultCount) {
            this.items = items;
            this.resultCount = resultCount;
        }
    }

    /** ETP 搜索结果条目。 */
    public static class EtpSearchResult {
        public final boolean isFolder;
        public final String name;
        public final String path;
        public final long size;            // -1 表示未请求
        public final long dateModified;    // Windows FILETIME（100ns 单位，自 1601-01-01），-1 表示未请求
        public final long dateCreated;
        public final int attributes;       // -1 表示未请求
        public final String fileListFilename;
        public final long dateRecentlyChanged;

        public EtpSearchResult(boolean isFolder, String name, String path,
                               long size, long dateModified, long dateCreated,
                               int attributes, String fileListFilename,
                               long dateRecentlyChanged) {
            this.isFolder = isFolder;
            this.name = name;
            this.path = path;
            this.size = size;
            this.dateModified = dateModified;
            this.dateCreated = dateCreated;
            this.attributes = attributes;
            this.fileListFilename = fileListFilename;
            this.dateRecentlyChanged = dateRecentlyChanged;
        }

        /** 文件的完整路径。 */
        public String fullPath() {
            if (path != null && !path.isEmpty()) {
                if (path.endsWith("\\")) return path + name;
                return path + "\\" + name;
            }
            return name;
        }

        /** 将 Windows FILETIME 转换为 Java 毫秒时间戳。 */
        public static long filetimeToMillis(long filetime) {
            if (filetime <= 0) return 0;
            // FILETIME: 100-nanosecond intervals since 1601-01-01
            // Java: milliseconds since 1970-01-01
            // Difference: 11644473600000 milliseconds
            return filetime / 10000 - 11644473600000L;
        }

        /** 格式化文件大小。 */
        public static String formatSize(long bytes) {
            if (bytes < 0) return "";
            if (bytes < 1024) return bytes + " B";
            if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
            if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
            return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
        }
    }
}
