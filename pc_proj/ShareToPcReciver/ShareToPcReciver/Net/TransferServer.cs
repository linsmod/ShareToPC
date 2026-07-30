using System;
using System.Collections.Generic;
using System.Net;
using System.Net.Sockets;
using System.Threading;
using System.Threading.Tasks;
using ShareToPcReciver.Config;
using ShareToPcReciver.Models;
using ShareToPcReciver.Storage;
using static ShareToPcReciver.Net.TransferServer;

namespace ShareToPcReciver.Net
{
    public class TransferServer : IDisposable
    {
        private TcpListener _listener;
        private CancellationTokenSource _cts;
        private Task _serverTask;
        private readonly List<ClientSession> _sessions = new List<ClientSession>();
        private readonly object _sessionsLock = new object();

        public int Port { get; private set; }
        public bool IsRunning => _listener?.Server != null && _listener.Server.IsBound;

        public ReceiveStorage Storage { get; set; }
        public Storage.PairStore PairStore { get; set; }
        public AppConfig Config { get; }

        public event Action<ClientInfo> ClientConnected;
        public event Action<ReceivedText> TextReceived;
        public event Action<ReceivedFile> FileReceived;
        public event Action<FileProgressInfo> FileProgressChanged;
        /// <summary>设备授权请求事件。MainForm 订阅后弹出对话框，完成时调用 callback。</summary>
        public event Action<AuthResult, CancellationToken, TaskCompletionSource<AuthResult>> AskApprovoal;

        public class AuthResult
        {
            public string Status { get; set; } = "";   // "ACCEPTED" / "REJECTED"
            public string PairKey { get; set; } = "";
            public int Credits { get; set; }
            public int MaxItemsPerJob { get; set; }
            public long MaxBytesPerJob { get; set; }
            /// <summary>过期时间（Unix 毫秒），0 表示永不过期。</summary>
            public long ExpiresAt { get; set; }
            /// <summary>收到文本时的动作，null 表示跟随全局设定。</summary>
            public ArrivalAction? TextAction { get; set; }

            public bool IsPending => Status == "PENDING";
            public bool IsAccepted => Status == "ACCEPTED";

            internal AuthInfo authJobInfo { get; set; }

            internal static AuthResult CreatePending(String deviceName,int itemCount,long totalBytes)
            {
                return new AuthResult
                {
                    Status = "PENDING",
                    PairKey = "",
                    Credits = 0,
                    MaxItemsPerJob = 0,
                    MaxBytesPerJob = 0,
                    ExpiresAt = 0,
                    TextAction = null
                };
            }

            internal static AuthResult CreateRejected()
            {
                return new AuthResult
                {
                    Status = "REJECTED",
                    PairKey = "",
                    Credits = 0,
                    MaxItemsPerJob = 0,
                    MaxBytesPerJob = 0,
                    ExpiresAt = 0,
                    TextAction = null
                };
            }

            internal static AuthResult FromApprovedSession(PairStore.PairSession session)
            {
                return new AuthResult
                {
                    Status = "ACCEPTED",
                    PairKey = session.Key,
                    Credits = session.Credits,
                    MaxItemsPerJob = session.MaxItemsPerJob,
                    MaxBytesPerJob = session.MaxBytesPerJob,
                    ExpiresAt = session.ExpiresAt,
                    TextAction = session.TextAction
                };
            }
        }


        /// <summary>请求设备发授权弹窗。</summary>
        public void StartRequestApproval(AuthResult authResult, CancellationToken authTimeout, TaskCompletionSource<AuthResult> tcs)
        {
            if (AskApprovoal == null)
            {
                authResult.Status = "REJECTED";
                tcs.SetResult(authResult); // 没有订阅事件，无法弹窗 → 直接拒绝
                return;
            }
            var handler = AskApprovoal;
            handler.BeginInvoke(authResult, authTimeout, tcs,null,null);
        }

        public CancellationToken Cancellation => _cts?.Token ?? CancellationToken.None;

        public TransferServer(AppConfig config, ReceiveStorage storage, Storage.PairStore pairStore = null)
        {
            Config = config;
            Storage = storage;
            PairStore = pairStore ?? new Storage.PairStore();
        }

        public async Task StartAsync(int port)
        {
            Stop();
            Port = port;
            _cts = new CancellationTokenSource();
            _listener = new TcpListener(IPAddress.Any, port);
            _listener.Start();
            Util.Log.Info($"TCP server started on port {port}");
            _serverTask = RunLoopAsync(_cts.Token);
        }

        public void Stop()
        {
            _cts?.Cancel();
            try { _listener?.Stop(); } catch { }
            try { _serverTask?.Wait(2000); } catch { }
            _listener = null;
            Util.Log.Info("TCP server stopped");
        }

        /// <summary>广播 OFFLINE 给所有活跃会话并停止服务。</summary>
        public async Task ShutdownAsync()
        {
            Util.Log.Info("Shutting down, sending OFFLINE to all clients...");
            await BroadcastOfflineAsync();
            Stop();
        }

        private async Task BroadcastOfflineAsync()
        {
            List<ClientSession> snapshot;
            lock (_sessionsLock)
            {
                snapshot = new List<ClientSession>(_sessions);
            }

            foreach (var session in snapshot)
            {
                try
                {
                    await session.SendOfflineAsync();
                }
                catch (Exception ex)
                {
                    Util.Log.Warn($"Failed to send OFFLINE to {session.DeviceName}: {ex.Message}");
                }
            }

            Util.Log.Info($"Sent OFFLINE to {snapshot.Count} client(s)");
        }

        internal void AddSession(ClientSession session)
        {
            lock (_sessionsLock)
            {
                _sessions.Add(session);
            }
        }

        internal void RemoveSession(ClientSession session)
        {
            lock (_sessionsLock)
            {
                _sessions.Remove(session);
            }
        }

        private async Task RunLoopAsync(CancellationToken ct)
        {
            try
            {
                while (!ct.IsCancellationRequested)
                {
                    TcpClient client = await _listener.AcceptTcpClientAsync();
                    var session = new ClientSession(client, this);
                    session.Disconnected += s => RemoveSession(s);
                    AddSession(session);
                    _ = Task.Run(() => session.ProcessAsync(), ct);
                }
            }
            catch (OperationCanceledException)
            {
            }
            catch (ObjectDisposedException)
            {
            }
            catch (Exception ex)
            {
                Util.Log.Error("TransferServer loop error", ex);
            }
        }

        public void OnClientConnected(ClientInfo info)
        {
            ClientConnected?.Invoke(info);
        }

        public void OnTextReceived(ReceivedText msg)
        {
            TextReceived?.Invoke(msg);
        }

        public void OnFileReceived(ReceivedFile msg)
        {
            FileReceived?.Invoke(msg);
        }

        public void OnFileProgress(FileProgressInfo info)
        {
            FileProgressChanged?.Invoke(info);
        }

        /// <summary>断开所有活跃会话。</summary>
        public void DisconnectAllSessions()
        {
            List<ClientSession> snapshot;
            lock (_sessionsLock)
            {
                snapshot = new List<ClientSession>(_sessions);
            }
            foreach (var session in snapshot)
            {
                session.Disconnect();
            }
            Util.Log.Info($"Disconnected {snapshot.Count} active session(s)");
        }

        public void Dispose()
        {
            Stop();
            _cts?.Dispose();
        }

        public bool GetNoneBlocked(String deviceId, string pairKey, out PairStore.PairSession session)
        {
            session = null;

            // 0. 拉黑检查：按 pairKey 或 deviceId 查找，已拉黑直接拒绝
            if (PairStore?.IsBlocked(deviceId) == true)
            {
                return false;
            }
            // 1. 查 PairStore：已有有效授权则自动放行
            if (!string.IsNullOrEmpty(pairKey))
            {
                session = PairStore?.GetByKey(pairKey);
                if (session != null && !PairStore.IsExpired(session))
                {
                    return true;
                }
            }
            return false;
        }

        public PairStore.PairSession CreateNewSession(string deviceId, String deviceName, AuthResult result)
        {
            if (result.Credits != 0 && string.IsNullOrEmpty(result.PairKey))
            {
                result.PairKey = Guid.NewGuid().ToString();
            }
            var session = new Storage.PairStore.PairSession
            {
                DeviceId = deviceId,
                DeviceName = deviceName,
                Key = result.PairKey,
                Credits = result.Credits,
                MaxItemsPerJob = result.MaxItemsPerJob,
                MaxBytesPerJob = result.MaxBytesPerJob,
                IssuedAt = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(),
                ExpiresAt = result.ExpiresAt,
                TextAction = result.TextAction
            };
            PairStore.Save(session);
            return session;
        }
        internal void DecreaseCredits(String deviceId)
        {
            PairStore.PairSession session = PairStore.GetByDeviceId(deviceId);
            session.Credits -= 1;
            PairStore?.Save(session);
        }
    }
}