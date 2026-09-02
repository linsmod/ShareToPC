using System;
using System.Net;
using System.Net.Sockets;
using System.Threading;
using System.Threading.Tasks;

public class TcpPortForwarder
{
    private readonly IPAddress _localBind;
    private readonly int _localPort;
    private readonly string _targetHost;
    private readonly int _targetPort;
    private TcpListener _listener;
    private CancellationTokenSource _cts;

    public TcpPortForwarder(IPAddress localBind,int localPort, string targetHost, int targetPort)
    {
        _localBind = localBind;
        _localPort = localPort;
        _targetHost = targetHost;
        _targetPort = targetPort;
    }

    public void Start()
    {
        _cts = new CancellationTokenSource();
        _listener = new TcpListener(_localBind, _localPort);
        _listener.Start();
        Console.WriteLine($"[转发器] 启动：本地 0.0.0.0:{_localPort} → {_targetHost}:{_targetPort}");

        Task.Run(() => AcceptClients(_cts.Token));
    }

    public void Stop()
    {
        _cts?.Cancel();
        _listener?.Stop();
        Console.WriteLine("[转发器] 已停止");
    }

    private async Task AcceptClients(CancellationToken token)
    {
        try
        {
            while (!token.IsCancellationRequested)
            {
                var client = await _listener.AcceptTcpClientAsync();
                _ = HandleClientAsync(client, token); // 不等待，并发处理
            }
        }
        catch (OperationCanceledException)
        {
            // 正常停止
        }
        catch (Exception ex)
        {
            Console.WriteLine($"[转发器] 接受客户端异常: {ex.Message}");
        }
    }

    private async Task HandleClientAsync(TcpClient client, CancellationToken token)
    {
        var clientEndpoint = client.Client.RemoteEndPoint;
        Console.WriteLine($"[连接] {clientEndpoint} 已连接");

        TcpClient target = null;
        NetworkStream clientStream = null;
        NetworkStream targetStream = null;

        try
        {
            // 连接到目标服务器
            target = new TcpClient();
            await target.ConnectAsync(_targetHost, _targetPort);
            targetStream = target.GetStream();
            clientStream = client.GetStream();

            Console.WriteLine($"[转发] {clientEndpoint} → {_targetHost}:{_targetPort} 已建立");

            // 双向数据转发
            using (var cts = CancellationTokenSource.CreateLinkedTokenSource(token))
            {
                var clientToTarget = ForwardDataAsync(clientStream, targetStream, cts.Token);
                var targetToClient = ForwardDataAsync(targetStream, clientStream, cts.Token);

                // 任意一个方向完成（连接断开）就取消另一个
                await Task.WhenAny(clientToTarget, targetToClient);
                cts.Cancel();
            }
        }
        catch (Exception ex)
        {
            Console.WriteLine($"[错误] {clientEndpoint} → {_targetHost}:{_targetPort} : {ex.Message}");
        }
        finally
        {
            clientStream?.Close();
            targetStream?.Close();
            target?.Close();
            client?.Close();
            Console.WriteLine($"[断开] {clientEndpoint} 已断开");
        }
    }

    private async Task ForwardDataAsync(NetworkStream source, NetworkStream destination, CancellationToken token)
    {
        byte[] buffer = new byte[8192];
        try
        {
            while (!token.IsCancellationRequested)
            {
                int bytesRead = await source.ReadAsync(buffer, 0, buffer.Length, token);
                if (bytesRead == 0) break; // 连接关闭
                await destination.WriteAsync(buffer, 0, bytesRead, token);
                await destination.FlushAsync(token);
            }
        }
        catch (OperationCanceledException)
        {
            // 正常取消
        }
        catch (Exception ex) when (!(ex is OperationCanceledException))
        {
            Console.WriteLine($"[转发异常] {ex.Message}");
        }
    }

    internal int GetLocalPort()
    {
        return _localPort;
    }
}