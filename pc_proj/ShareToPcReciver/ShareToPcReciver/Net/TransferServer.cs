using System;
using System.Collections.Generic;
using System.Net;
using System.Net.Sockets;
using System.Threading;
using System.Threading.Tasks;
using ShareToPcReciver.Config;
using ShareToPcReciver.Models;
using ShareToPcReciver.Storage;

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
        public AppConfig Config { get; }

        public event Action<ClientInfo> ClientConnected;
        public event Action<ReceivedText> TextReceived;
        public event Action<ReceivedFile> FileReceived;
        public event Action<FileProgressInfo> FileProgressChanged;

        public CancellationToken Cancellation => _cts?.Token ?? CancellationToken.None;

        public TransferServer(AppConfig config, ReceiveStorage storage)
        {
            Config = config;
            Storage = storage;
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
    }
}