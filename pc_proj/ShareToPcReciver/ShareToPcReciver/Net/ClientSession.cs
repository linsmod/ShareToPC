using System;
using System.IO;
using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;
using ShareToPcReciver.Config;
using ShareToPcReciver.Models;
using ShareToPcReciver.Storage;

namespace ShareToPcReciver.Net
{
    public class ClientSession
    {
        private readonly TcpClient _client;
        private readonly TransferServer _server;
        private readonly CancellationTokenSource _sessionCts;
        private readonly CancellationToken _ct;
        private readonly ReceiveStorage _storage;
        private readonly AppConfig _config;

        public string DeviceName { get; private set; } = "Unknown";

        /// <summary>会话断开时触发，用于 TransferServer 清理会话列表。</summary>
        public event Action<ClientSession> Disconnected;

        public ClientSession(TcpClient client, TransferServer server)
        {
            _client = client;
            _server = server;
            _sessionCts = CancellationTokenSource.CreateLinkedTokenSource(server.Cancellation);
            _ct = _sessionCts.Token;
            _storage = _server.Storage;
            _config = _server.Config;
        }

        public async Task ProcessAsync()
        {
            try
            {
                var stream = _client.GetStream();
                stream.ReadTimeout = 30000;
                stream.WriteTimeout = 10000;

                while (!_ct.IsCancellationRequested)
                {
                    var (headerJson, payloadLength) = await Protocol.ReadFrameAsync(stream, _ct);
                    var header = JsonSerializer.Deserialize<JsonElement>(headerJson);
                    string type = header.GetProperty("type").GetString() ?? "";

                    switch (type)
                    {
                        case "HELLO":
                            await HandleHello(stream, header);
                            break;
                        case "TEXT":
                            await HandleText(stream, header);
                            break;
                        case "FILE":
                            await HandleFile(stream, header, payloadLength);
                            break;
                        case "PING":
                            await HandlePing(stream);
                            break;
                        case "CANCEL":
                            HandleCancel();
                            return;
                        default:
                            await Protocol.WriteAckAsync(stream, false, $"Unknown type: {type}", _ct);
                            return;
                    }
                }
            }
            catch (OperationCanceledException)
            {
            }
            catch (IOException ex)
            {
                Util.Log.Info($"Client disconnected: {ex.Message}");
            }
            catch (ProtocolViolationException ex)
            {
                Util.Log.Warn($"Protocol violation: {ex.Message}");
                try
                {
                    var stream = _client.GetStream();
                    await Protocol.WriteAckAsync(stream, false, ex.Message, _ct);
                }
                catch { }
            }
            catch (Exception ex)
            {
                Util.Log.Error($"Session error with {DeviceName}", ex);
            }
            finally
            {
                Disconnected?.Invoke(this);
                _client.Close();
            }
        }

        /// <summary>向客户端发送 OFFLINE 通知。</summary>
        public async Task SendOfflineAsync()
        {
            try
            {
                var stream = _client.GetStream();
                await Protocol.WriteOfflineAsync(stream, CancellationToken.None);
                Util.Log.Info($"Sent OFFLINE to {DeviceName}");
            }
            catch (Exception ex)
            {
                Util.Log.Warn($"Failed to send OFFLINE to {DeviceName}: {ex.Message}");
            }
        }

        /// <summary>断开此会话连接。</summary>
        public void Disconnect()
        {
            try
            {
                _sessionCts.Cancel();
                _client.Close();
            }
            catch (Exception ex)
            {
                Util.Log.Warn($"Failed to disconnect {DeviceName}: {ex.Message}");
            }
        }

        private async Task HandleHello(Stream stream, JsonElement header)
        {
            string device = header.TryGetProperty("device", out JsonElement d) ? d.GetString() ?? "Unknown" : "Unknown";
            string clientVersion = header.TryGetProperty("version", out JsonElement v) ? v.GetString() ?? "unknown" : "unknown";
            int clientProto = header.TryGetProperty("protoVersion", out JsonElement pv) ? pv.GetInt32() : 0;
            DeviceName = device;
            Util.Log.Info($"HELLO from {device} (app: {clientVersion}, proto v{clientProto}, self: {Protocol.AppVersion} proto v{Protocol.ProtocolVersion})");
            _server.OnClientConnected(new ClientInfo { Name = device, Address = ((IPEndPoint)_client.Client.RemoteEndPoint).Address.ToString() });
            await Protocol.WriteHelloAckAsync(stream, _ct);
        }

        private async Task HandleText(Stream stream, JsonElement header)
        {
            string text = header.TryGetProperty("text", out JsonElement t) ? t.GetString() ?? "" : "";
            long time = header.TryGetProperty("time", out JsonElement ts) ? ts.GetInt64() : DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
            Util.Log.Info($"TEXT from {DeviceName}: {text}");
            _server.OnTextReceived(new ReceivedText
            {
                DeviceName = DeviceName,
                Text = text,
                Time = time
            });
            await Protocol.WriteAckAsync(stream, true, "", _ct);
        }

        private async Task HandleFile(Stream stream, JsonElement header, long payloadLength)
        {
            string name = header.TryGetProperty("name", out JsonElement n) ? n.GetString() ?? "file" : "file";
            long size = header.TryGetProperty("size", out JsonElement s) ? s.GetInt64() : 0;
            string mime = header.TryGetProperty("mime", out JsonElement m) ? m.GetString() ?? "" : "";
            long time = header.TryGetProperty("time", out JsonElement ts) ? ts.GetInt64() : DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
            string transferId = header.TryGetProperty("id", out JsonElement idProp) ? idProp.GetString() ?? "" : "";

            // ---- 断点续传：通过 transferId 查找未完成的 .part 文件 ----
            string partPath;
            string filePath;
            long resumeOffset = 0;
            bool isResume = false;

            if (!string.IsNullOrEmpty(transferId))
            {
                isResume = _storage.FindPartFile(transferId, out partPath, out resumeOffset, out filePath, out string resumedName)
                           && resumeOffset > 0;
                if (isResume && !string.IsNullOrEmpty(resumedName))
                    name = resumedName;
            }
            else
            {
                partPath = null;
                filePath = null;
            }

            if (isResume)
            {
                Util.Log.Info($"RESUME FILE from {DeviceName}: {name} (id={transferId}) at offset {resumeOffset}/{size} bytes");
            }
            else
            {
                filePath = _storage.CreateFile(name, transferId, out partPath);
                Util.Log.Info($"FILE from {DeviceName}: {name} ({size} bytes, id={transferId})");
            }

            ReceivedFile received = new ReceivedFile
            {
                FileName = name,
                FilePath = null,
                Size = size,
                DeviceName = DeviceName,
                Time = time,
                Progress = 0,
                TotalBytes = size,
                ReceivedBytes = resumeOffset
            };

            var progressWatch = System.Diagnostics.Stopwatch.StartNew();
            long lastReportMs = 0;
            long totalReceived = resumeOffset;

            try
            {
                // 告诉安卓端从哪里开始传
                if (isResume)
                    await Protocol.WriteResumeAckAsync(stream, resumeOffset, _ct);
                else
                    await Protocol.WriteAckAsync(stream, true, "", _ct);

                FileStream fs;
                if (isResume)
                {
                    fs = new FileStream(partPath, FileMode.Open, FileAccess.Write,
                        FileShare.None, 64 * 1024, FileOptions.Asynchronous);
                    fs.Seek(resumeOffset, SeekOrigin.Begin);
                }
                else
                {
                    fs = new FileStream(partPath, FileMode.CreateNew, FileAccess.Write,
                        FileShare.None, 64 * 1024, FileOptions.Asynchronous);
                }

                using (fs)
                {
                    bool needResumeChunk = isResume;

                    // 逐 chunk 读取，CANCEL 帧可以在 chunk 之间插入
                    while (totalReceived < size && !_ct.IsCancellationRequested)
                    {
                        var (headerJson, _) = await Protocol.ReadFrameAsync(stream, _ct);
                        var frame = JsonSerializer.Deserialize<JsonElement>(headerJson);
                        string frameType = frame.GetProperty("type").GetString() ?? "";

                        if (frameType == "CANCEL")
                        {
                            Util.Log.Info($"Transfer cancelled from {DeviceName} for {name}");
                            _storage.DiscardFile(partPath);
                            _server.OnFileProgress(new FileProgressInfo
                            {
                                FileName = name,
                                DeviceName = DeviceName,
                                TransferId = transferId,
                                Received = totalReceived,
                                Total = size,
                                Cancelled = true
                            });
                            _sessionCts.Cancel();
                            return;
                        }

                        // 断点续传首包必须是 RESUME_CHUNK
                        if (needResumeChunk)
                        {
                            if (frameType != "RESUME_CHUNK")
                                throw new ProtocolViolationException(
                                    $"Expected RESUME_CHUNK for resume, got {frameType}");

                            int chunkOffset = frame.TryGetProperty("offset", out JsonElement offProp)
                                ? offProp.GetInt32() : -1;
                            if (chunkOffset != resumeOffset)
                                throw new ProtocolViolationException(
                                    $"RESUME_CHUNK offset mismatch: expected {resumeOffset}, got {chunkOffset}");

                            int chunkLen = frame.TryGetProperty("len", out JsonElement lenProp)
                                ? lenProp.GetInt32() : 0;
                            if (chunkLen <= 0) continue;

                            byte[] chunkBuf = new byte[chunkLen];
                            int read = 0;
                            while (read < chunkLen)
                            {
                                int r = await stream.ReadAsync(chunkBuf, read, chunkLen - read, _ct);
                                if (r == 0)
                                    throw new IOException("Connection closed while reading resume chunk");
                                read += r;
                            }
                            await fs.WriteAsync(chunkBuf, 0, chunkLen, _ct);
                            totalReceived += chunkLen;
                            needResumeChunk = false;

                            // 进度上报
                            lastReportMs = progressWatch.ElapsedMilliseconds;
                            _server.OnFileProgress(new FileProgressInfo
                            {
                                FileName = name,
                                DeviceName = DeviceName,
                                TransferId = transferId,
                                Received = totalReceived,
                                Total = size
                            });
                            continue;
                        }

                        if (frameType == "CHUNK")
                        {
                            int chunkLen = frame.TryGetProperty("len", out JsonElement lenProp2)
                                ? lenProp2.GetInt32() : 0;
                            if (chunkLen <= 0) continue;

                            byte[] chunkBuf = new byte[chunkLen];
                            int read = 0;
                            while (read < chunkLen)
                            {
                                int r = await stream.ReadAsync(chunkBuf, read, chunkLen - read, _ct);
                                if (r == 0)
                                    throw new IOException("Connection closed while reading chunk");
                                read += r;
                            }
                            await fs.WriteAsync(chunkBuf, 0, chunkLen, _ct);
                            totalReceived += chunkLen;

                            // 进度节流上报
                            long now = progressWatch.ElapsedMilliseconds;
                            if (now - lastReportMs >= 100 || totalReceived >= size)
                            {
                                lastReportMs = now;
                                _server.OnFileProgress(new FileProgressInfo
                                {
                                    FileName = name,
                                    DeviceName = DeviceName,
                                    TransferId = transferId,
                                    Received = totalReceived,
                                    Total = size
                                });
                            }
                        }
                        else
                        {
                            throw new ProtocolViolationException(
                                $"Unexpected frame in file transfer: {frameType}");
                        }
                    }

                    fs.Close();
                    _storage.CompleteFile(partPath);
                    received.FilePath = filePath;
                    received.Progress = 100;
                    received.ReceivedBytes = size;

                    Util.Log.Info($"FILE saved: {filePath}");
                    _server.OnFileReceived(received);
                    await Protocol.WriteAckAsync(stream, true, "", _ct);
                }
            }
            catch (OperationCanceledException)
            {
                // PC 侧主动取消，资源已在外部清理
                throw;
            }
            catch (IOException ex)
            {
                // 连接断开：保留 .part 文件用于断点续传
                Util.Log.Info($"File transfer interrupted for {name} at {totalReceived}/{size} bytes: {ex.Message}");
                _server.OnFileProgress(new FileProgressInfo
                {
                    FileName = name,
                    DeviceName = DeviceName,
                    TransferId = transferId,
                    Received = totalReceived,
                    Total = size,
                    Failed = true
                });
                try { await Protocol.WriteAckAsync(stream, false, ex.Message, _ct); } catch { }
            }
            catch (Exception ex)
            {
                Util.Log.Error($"Failed to receive file {name}", ex);
                // 非 IO 错误也保留 .part（如协议异常），方便重试
                _server.OnFileProgress(new FileProgressInfo
                {
                    FileName = name,
                    DeviceName = DeviceName,
                    TransferId = transferId,
                    Received = totalReceived,
                    Total = size,
                    Failed = true
                });
                try { await Protocol.WriteAckAsync(stream, false, ex.Message, _ct); } catch { }
            }
        }

        private async Task HandlePing(Stream stream)
        {
            await Protocol.WritePongAsync(stream, _ct);
        }

        private void HandleCancel()
        {
            Util.Log.Info($"CANCEL from {DeviceName}");
            _server.OnFileProgress(new FileProgressInfo
            {
                FileName = "",
                DeviceName = DeviceName,
                Total = 0,
                Cancelled = true
            });
        }
    }
}