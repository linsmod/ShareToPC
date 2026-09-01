using ShareToPcReciver.Config;
using ShareToPcReciver.Models;
using ShareToPcReciver.Storage;
using System;
using System.Collections.Generic;
using System.Net;
using System.Net.Sockets;
using System.Threading;
using System.Threading.Tasks;
using static ShareToPcReciver.Net.TransferServer;
using static System.Collections.Specialized.BitVector32;

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
        public Storage.HistoryStore HistoryStore { get; set; }
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
            /// <summary>是否拉黑设备。</summary>
            public bool IsBlocked { get; set; }

            public bool IsPending => Status == "PENDING";
            public bool IsAccepted => Status == "ACCEPTED";

            internal AuthInfo authJobInfo { get; set; }

            internal static AuthResult CreateApproved()
            {
                return new AuthResult
                {
                    Status = "ACCEPTED",
                    PairKey = "",
                };
            }

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
            internal static AuthResult CreateCanceled()
            {
                return new AuthResult
                {
                    Status = "CANCELED",
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
            IPAddress listenAddr = IPAddress.Any;
            if (!string.IsNullOrWhiteSpace(Config.ListenIp) && IPAddress.TryParse(Config.ListenIp, out var parsed))
                listenAddr = parsed;
            _listener = new TcpListener(listenAddr, port);
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
                    if (session.clientDevice!=null) { 
                        Util.Log.Warn($"Failed to send OFFLINE to {session.clientDevice.DeviceName}: {ex.Message}");
                    }
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

        private readonly HashSet<string> _canceledJobs = new HashSet<string>();
        private readonly object _canceledJobsLock = new object();

        /// <summary>标记 job 已被接收方取消，Android 重发 JOB_INFO 时会收到 reciverCanceled=true。</summary>
        public void MarkJobCanceled(string jobId)
        {
            if (string.IsNullOrEmpty(jobId)) return;
            lock (_canceledJobsLock)
                _canceledJobs.Add(jobId);
        }

        /// <summary>检查 job 是否已被接收方取消。</summary>
        public bool IsJobCanceled(string jobId)
        {
            if (string.IsNullOrEmpty(jobId)) return false;
            lock (_canceledJobsLock)
                return _canceledJobs.Contains(jobId);
        }

        /// <summary>清除 job 的取消标记，允许 Android 重连后继续传输。</summary>
        public void ClearJobCanceled(string jobId)
        {
            if (string.IsNullOrEmpty(jobId)) return;
            lock (_canceledJobsLock)
                _canceledJobs.Remove(jobId);
        }

        /// <summary>通过 HB 通道通知指定设备的 Android 端暂停传输。</summary>
        public void PauseJob(string deviceName, string jobId)
        {
            List<ClientSession> snapshot;
            lock (_sessionsLock)
                snapshot = new List<ClientSession>(_sessions);

            foreach (var session in snapshot)
            {
                if (session.clientDevice?.DeviceName == deviceName)
                    session.SendPauseAsync(jobId);
            }
        }

        /// <summary>通过 HB 通道通知指定设备的 Android 端恢复传输。</summary>
        public void ResumeJob(string deviceName, string jobId)
        {
            List<ClientSession> snapshot;
            lock (_sessionsLock)
                snapshot = new List<ClientSession>(_sessions);

            foreach (var session in snapshot)
            {
                if (session.clientDevice?.DeviceName == deviceName)
                    session.SendResumeAsync(jobId);
            }
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
        internal PairStore.PairSession DecreaseCredits(String deviceId, AuthResult result)
        {
            PairStore.PairSession session = PairStore.GetByDeviceId(deviceId);
            // 只有可计数的授权（Credits > 0）才递减；永久授权（Credits == -1）不递减
            if (session.Credits > 0)
                session.Credits -= 1;
            else
                session.Credits = result.Credits;
            // 同步持久化过期时间、约束等（之前这些字段都没写入 PairStore）
            session.ExpiresAt = result.ExpiresAt;
            session.MaxItemsPerJob = result.MaxItemsPerJob;
            session.MaxBytesPerJob = result.MaxBytesPerJob;
            session.TextAction = result.TextAction;
            if (string.IsNullOrEmpty(session.Key))
                session.Key = Guid.NewGuid().ToString();
            PairStore?.Save(session);
            return session;
        }
    }
}