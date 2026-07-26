using System;
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

        private async Task RunLoopAsync(CancellationToken ct)
        {
            try
            {
                while (!ct.IsCancellationRequested)
                {
                    TcpClient client = await _listener.AcceptTcpClientAsync();
                    var session = new ClientSession(client, this);
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

        public void Dispose()
        {
            Stop();
            _cts?.Dispose();
        }
    }
}