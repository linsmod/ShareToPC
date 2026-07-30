using ShareToPcReciver.Config;
using ShareToPcReciver.Models;
using ShareToPcReciver.Storage;
using System;
using System.IO;
using System.Net;
using System.Net.Sockets;
using System.Runtime.InteropServices.ComTypes;
using System.Text;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;
using static ShareToPcReciver.Net.TransferServer;

namespace ShareToPcReciver.Net
{
    public partial class ClientSession
    {
        private readonly TcpClient _client;
        private readonly TransferServer _server;
        private readonly CancellationTokenSource _sessionCts;
        private readonly CancellationToken _ct;
        private readonly ReceiveStorage _storage;
        private readonly AppConfig _config;
        private AuthResult authResult;
        private CancellationTokenSource authCts;

        public string DeviceName { get; private set; } = "Unknown";
        public string DeviceId { get; private set; } = "Unknown";

        private DeviceInfo sender;
        private AuthInfo currentJobAuth;

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
                    var (header, payloadLength, type) = await Protocol.ReadFrameAsync(stream, _ct);

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
                        case "JOB_INFO":
                            await HandleJobInfo(stream, header);
                            break;
                        case "CANCEL":
                            HandleCancel(header);
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
                if (authCts != null) {
                    authCts.Cancel();
                }
                _sessionCts.Cancel();
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
                if (authCts != null)
                {
                    authCts.Cancel();
                }
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
            string clientDeviceId = header.TryGetProperty("deviceId", out JsonElement did) ? did.GetString() ?? "" : "";
            DeviceName = device;
            DeviceId = clientDeviceId;
            this.sender = new DeviceInfo
            {
                DeviceName = device,
                DeviceId = clientDeviceId,
                AppVersion = clientVersion,
                ProtoVersion = clientProto
            };
            Util.Log.Info($"HELLO from {device} (app: {clientVersion}, proto v{clientProto}, id: {clientDeviceId}, self: {Protocol.AppVersion} proto v{Protocol.ProtocolVersion})");
            _server.OnClientConnected(new ClientInfo
            {
                Name = device,
                Address = ((IPEndPoint)_client.Client.RemoteEndPoint).Address.ToString(),
                DeviceId = clientDeviceId,
                Version = clientVersion,
                ProtoVersion = clientProto,
                LastSeen = DateTime.UtcNow
            });
            await Protocol.WriteHelloAckAsync(stream, _config.DeviceId, _ct);
        }

        private async Task HandleText(Stream stream, JsonElement header)
        {
            string text = header.TryGetProperty("text", out JsonElement t) ? t.GetString() ?? "" : "";
            long time = header.TryGetProperty("time", out JsonElement ts) ? ts.GetInt64() : DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
            string jobId = header.TryGetProperty("jobId", out JsonElement jid) ? jid.GetString() ?? "" : "";
            Util.Log.Info($"TEXT from {DeviceName}: {text}");
            _server.OnTextReceived(new ReceivedText
            {
                DeviceName = DeviceName,
                Text = text,
                Time = time,
                JobId = jobId
            });
            await Protocol.WriteAckAsync(stream, true, "", _ct);
        }

        private async Task HandleFile(Stream stream, JsonElement header, long payloadLength)
        {
            if (authResult == null || !authResult.IsAccepted)
            {
                Util.Log.Warn($"FILE from {DeviceName} rejected due to no accepted job");
                await Protocol.WriteAckAsync(stream, false, "BAD_PROTOCOL", _ct);
                return;
            }
            string name = header.TryGetProperty("name", out JsonElement n) ? n.GetString() ?? "file" : "file";
            long size = header.TryGetProperty("size", out JsonElement s) ? s.GetInt64() : 0;
            string mime = header.TryGetProperty("mime", out JsonElement m) ? m.GetString() ?? "" : "";
            string key = header.TryGetProperty("key", out JsonElement k) ? m.GetString() ?? "" : "";
            long time = header.TryGetProperty("time", out JsonElement ts) ? ts.GetInt64() : DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
            string transferId = header.TryGetProperty("id", out JsonElement idProp) ? idProp.GetString() ?? "" : "";
            string jobId = header.TryGetProperty("jobId", out JsonElement jidProp) ? jidProp.GetString() ?? "" : "";

            if (key != authResult?.PairKey)
            {
                Util.Log.Warn($"FILE from {DeviceName} rejected due to invalid key: {key}");
                await Protocol.WriteAckAsync(stream, false, "BAD_PROTOCOL", _ct);
                return;
            }

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
                ReceivedBytes = resumeOffset,
                JobId = jobId
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
                        var (frame, framePayloadLength, frameType) = await Protocol.ReadFrameAsync(stream, _ct);
                        if (frameType == "CANCEL")
                        {
                            Util.Log.Info($"Transfer cancelled from {DeviceName} for {name}");
                            _storage.DiscardFile(partPath);
                            _server.OnFileProgress(new FileProgressInfo
                            {
                                FileName = name,
                                DeviceName = DeviceName,
                                TransferId = transferId,
                                JobId = jobId,
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
                                JobId = jobId,
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
                                    JobId = jobId,
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
                    filePath = _storage.CompleteFile(partPath);
                    received.FilePath = filePath;
                    received.FileName = Path.GetFileName(filePath);
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
                    JobId = jobId,
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
                    JobId = jobId,
                    Received = totalReceived,
                    Total = size,
                    Failed = true
                });
                try { await Protocol.WriteAckAsync(stream, false, ex.Message, _ct); } catch { }
            }
        }

        private async Task HandleJobInfo(Stream stream, JsonElement header)
        {
            string deviceId = header.TryGetProperty("deviceId", out JsonElement did) ? did.GetString() ?? "" : "";
            string pairKey = header.TryGetProperty("pairKey", out JsonElement pk) ? pk.GetString() ?? "" : "";
            string jobId = header.TryGetProperty("jobId", out JsonElement jid) ? jid.GetString() ?? "" : "";
            int itemCount = header.TryGetProperty("itemCount", out JsonElement ic) ? ic.GetInt32() : 0;
            long totalBytes = header.TryGetProperty("totalBytes", out JsonElement tb) ? tb.GetInt64() : 0L;

            this.currentJobAuth = new AuthInfo
            {
                DeviceName = DeviceName,
                DeviceId = deviceId,
                PairKey = pairKey,
                JobId = jobId,
                ItemCount = itemCount,
                TotalBytes = totalBytes
            };

            Util.Log.Info($"JOB_INFO raw JSON: {header.GetRawText()}");
            Util.Log.Info($"JOB_INFO from {DeviceName}: id={deviceId} key={pairKey} job={jobId} items={itemCount} bytes={totalBytes}");

            if (_server.PairStore.IsBlocked(deviceId))
            {
                this.authResult = AuthResult.CreateRejected();
            }
            else
            {
                PairStore.PairSession session = _server.PairStore.GetByDeviceId(deviceId);
                if (_server.PairStore.IsExpired(session))
                {
                    this.authResult = AuthResult.CreatePending(DeviceName,itemCount,totalBytes);
                }
                else if (_server.PairStore.IsOutofCredits(session))
                {
                    this.authResult = AuthResult.CreatePending(DeviceName, itemCount, totalBytes);
                }
                else if (_server.PairStore.IsItemCountExceeded(session, itemCount))
                {
                    this.authResult = AuthResult.CreateRejected();
                }
                else if (_server.PairStore.IsByteCountExceeded(session, totalBytes))
                {
                    this.authResult = AuthResult.CreateRejected();
                }
                else
                {
                    this.authResult = AuthResult.FromApprovedSession(session);
                }
            }
            if (this.authResult.IsPending)
            {
                // duplicated auth is not allowed
                if (this.authCts != null)
                {
                    await Protocol.WriteBadProtocolAckAsync(stream, _ct);
                    _sessionCts.Cancel();
                    return;
                }
                // 请求授权弹窗，30 秒超时
                this.authCts = new CancellationTokenSource(TimeSpan.FromSeconds(30));
                using var reg = this._ct.Register(() => this.authCts.Cancel());

                // wait for approval result
                var tcs = new TaskCompletionSource<AuthResult>(TaskCreationOptions.RunContinuationsAsynchronously);
                this.authResult.authJobInfo = this.currentJobAuth;
                _server.StartRequestApproval(this.authResult, this.authCts.Token, tcs);
                int elapsed = 0;
                while (!authCts.IsCancellationRequested && !tcs.Task.IsCompleted)
                {
                    try
                    {
                        await Task.Delay(1000, authCts.Token);
                    }
                    catch (OperationCanceledException)
                    {
                        break;
                    }
                    elapsed += 1000;
                    // 单向心跳：通知 Android 端仍在等待授权，无需等待回应
                    await Protocol.WriteStillAckAsync(stream, elapsed, 0, _ct);
                }
                // 消费 TCS 结果，确保异常也能被捕获
                this.authResult = tcs.Task.IsCompleted
                    ? await tcs.Task
                    : AuthResult.CreateRejected();
            }
            // ACCEPTED 且需要生成新 key（首次配对或 credits 不是默认）
            if (authResult.Status == "ACCEPTED")
            {
                _server.DecreaseCredits(deviceId);

                Util.Log.Info($"JOB_INFO accepted for {DeviceName}, credits={authResult.Credits}");
            }
            else
            {
                Util.Log.Info($"JOB_INFO rejected for {DeviceName}");
            }

            // 回复 JOB_ACK
            await Protocol.WriteJobAckAsync(stream, authResult.Status, authResult.PairKey,
                authResult.Credits, authResult.MaxItemsPerJob, authResult.MaxBytesPerJob, _ct);

            // 拒绝后断开连接，Android 端收到 JOB_ACK(REJECTED) 后 cancel job
            if (authResult.Status != "ACCEPTED")
            {
                _sessionCts.Cancel();
            }
        }


        private async Task HandlePing(Stream stream)
        {
            await Protocol.WritePongAsync(stream, _ct);
        }

        private void HandleCancel(JsonElement header)
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