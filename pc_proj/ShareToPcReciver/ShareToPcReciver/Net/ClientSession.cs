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
            _ct = _server.Cancellation;
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

        private async Task HandleHello(Stream stream, JsonElement header)
        {
            string device = header.TryGetProperty("device", out JsonElement d) ? d.GetString() ?? "Unknown" : "Unknown";
            DeviceName = device;
            Util.Log.Info($"HELLO from {device}");
            _server.OnClientConnected(new ClientInfo { Name = device, Address = ((IPEndPoint)_client.Client.RemoteEndPoint).Address.ToString() });
            await Protocol.WriteAckAsync(stream, true, "", _ct);
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

            Util.Log.Info($"FILE from {DeviceName}: {name} ({size} bytes)");

            string filePath = _storage.CreateFile(name, out string partPath);
            ReceivedFile received = new ReceivedFile
            {
                FileName = name,
                FilePath = null,
                Size = size,
                DeviceName = DeviceName,
                Time = time,
                Progress = 0
            };

            var progressWatch = System.Diagnostics.Stopwatch.StartNew();
            long lastReportMs = 0;
            Action<long> reportProgress = done =>
            {
                long now = progressWatch.ElapsedMilliseconds;
                if (now - lastReportMs < 100 && done < size)
                    return;
                lastReportMs = now;
                _server.OnFileProgress(new FileProgressInfo
                {
                    FileName = name,
                    DeviceName = DeviceName,
                    Received = done,
                    Total = size
                });
            };

            try
            {
                using var fs = new FileStream(partPath, FileMode.CreateNew, FileAccess.Write, FileShare.None, 64 * 1024, FileOptions.Asynchronous);
                await Protocol.ReadPayloadAsync(stream, size, fs, _ct, reportProgress);
                fs.Close();

                _storage.CompleteFile(partPath);
                received.FilePath = filePath;
                received.Progress = 100;

                Util.Log.Info($"FILE saved: {filePath}");
                _server.OnFileReceived(received);
                await Protocol.WriteAckAsync(stream, true, "", _ct);
            }
            catch (Exception ex)
            {
                Util.Log.Error($"Failed to receive file {name}", ex);
                _storage.DiscardFile(partPath);
                _server.OnFileProgress(new FileProgressInfo
                {
                    FileName = name,
                    DeviceName = DeviceName,
                    Total = size,
                    Failed = true
                });
                await Protocol.WriteAckAsync(stream, false, ex.Message, _ct);
            }
        }

        private async Task HandlePing(Stream stream)
        {
            await Protocol.WritePongAsync(stream, _ct);
        }
    }
}