using System;
using System.Collections.Generic;
using System.Linq;
using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
/// <summary>
/// 数据方向
/// </summary>
public enum DataDirection
{
    ClientToTarget,
    TargetToClient
}

/// <summary>
/// 转发结果
/// </summary>
public class ForwardResult
{
    /// <summary>
    /// 是否继续转发
    /// </summary>
    public bool ShouldForward { get; set; } = true;

    /// <summary>
    /// 修改后的数据（如果为null则使用原始数据）
    /// </summary>
    public byte[] ModifiedData { get; set; }

    /// <summary>
    /// 直接响应数据（不为null时会直接发送给数据接收方，不经过目标服务器）
    /// </summary>
    public byte[] DirectResponse { get; set; }

    /// <summary>
    /// 创建透传结果
    /// </summary>
    public static ForwardResult PassThrough() => new ForwardResult();

    /// <summary>
    /// 创建修改数据结果
    /// </summary>
    public static ForwardResult Modify(byte[] data) => new ForwardResult
    {
        ModifiedData = data
    };

    /// <summary>
    /// 创建直接响应结果（不转发到目标）
    /// </summary>
    public static ForwardResult Respond(byte[] response) => new ForwardResult
    {
        ShouldForward = false,
        DirectResponse = response
    };

    /// <summary>
    /// 创建丢弃数据结果（既不转发也不响应）
    /// </summary>
    public static ForwardResult Drop() => new ForwardResult
    {
        ShouldForward = false
    };
}
/// <summary>
/// 转发中间件委托
/// </summary>
public delegate Task<ForwardResult> ForwardDelegate(
    DataDirection direction,
    byte[] data,
    IDictionary<string, object> connectionContext);

/// <summary>
/// 转发中间件接口
/// </summary>
public interface IForwardMiddleware
{
    /// <summary>
    /// 中间件名称
    /// </summary>
    string Name { get; }

    /// <summary>
    /// 处理转发请求
    /// </summary>
    /// <param name="direction">数据方向</param>
    /// <param name="data">数据</param>
    /// <param name="connectionContext">上下文</param>
    /// <param name="next">下一个中间件</param>
    /// <returns>转发结果</returns>
    Task<ForwardResult> InvokeAsync(
        DataDirection direction,
        byte[] data,
        IDictionary<string, object> connectionContext,
        ForwardDelegate next);
}

/// <summary>
/// 连接生命周期中间件接口（可选）
/// </summary>
public interface IConnectionLifecycleMiddleware : IForwardMiddleware
{
    /// <summary>
    /// 客户端连接时触发
    /// </summary>
    Task<bool> OnClientConnectedAsync(EndPoint clientEndpoint, IDictionary<string, object> connectionContext);

    /// <summary>
    /// 目标连接时触发
    /// </summary>
    Task<bool> OnTargetConnectedAsync(EndPoint targetEndpoint, IDictionary<string, object> connectionContext);

    /// <summary>
    /// 连接关闭时触发
    /// </summary>
    Task OnConnectionClosedAsync(IDictionary<string, object> connectionContext, string reason);
}
public class ForwardMiddlewarePipeline
{
    private readonly List<IForwardMiddleware> _middlewares = new List<IForwardMiddleware>();
    private ForwardDelegate _pipeline;

    public void Use(IForwardMiddleware middleware)
    {
        _middlewares.Add(middleware);
        RebuildPipeline();
    }

    public void Remove(IForwardMiddleware middleware)
    {
        _middlewares.Remove(middleware);
        RebuildPipeline();
    }

    public IReadOnlyList<IForwardMiddleware> Middlewares => _middlewares;

    private void RebuildPipeline()
    {
        // 从后向前构建管道
        ForwardDelegate pipeline = (direction, data, context) =>
        {
            // 最后一个中间件：直接透传
            return Task.FromResult(ForwardResult.PassThrough());
        };

        // 倒序构建
        for (int i = _middlewares.Count - 1; i >= 0; i--)
        {
            var middleware = _middlewares[i];
            var next = pipeline;
            pipeline = async (direction, data, context) =>
            {
                try
                {
                    return await middleware.InvokeAsync(direction, data, context, next);
                }
                catch (Exception ex)
                {
                    Console.WriteLine($"[中间件错误] {middleware.Name}: {ex.Message}");
                    // 发生错误时透传数据
                    return ForwardResult.PassThrough();
                }
            };
        }

        _pipeline = pipeline;
    }

    public async Task<ForwardResult> ExecuteAsync(
        DataDirection direction,
        byte[] data,
        IDictionary<string, object> connectionContext)
    {
        if (_pipeline == null)
        {
            return ForwardResult.PassThrough();
        }

        return await _pipeline(direction, data, connectionContext);
    }
}
public class TcpPortForwarder
{
    private readonly IPAddress _localBind;
    private readonly int _localPort;
    private readonly string _targetHost;
    private readonly int _targetPort;
    private TcpListener _listener;
    private CancellationTokenSource _cts;
    private readonly ForwardMiddlewarePipeline _pipeline;

    public TcpPortForwarder(IPAddress localBind, int localPort, string targetHost, int targetPort)
    {
        _localBind = localBind;
        _localPort = localPort;
        _targetHost = targetHost;
        _targetPort = targetPort;
        _pipeline = new ForwardMiddlewarePipeline();
    }

    // 使用中间件
    public void UseMiddleware(IForwardMiddleware middleware)
    {
        _pipeline.Use(middleware);
    }

    // 支持泛型注册
    public void UseMiddleware<T>() where T : IForwardMiddleware, new()
    {
        UseMiddleware(new T());
    }

    public void Start()
    {
        _cts = new CancellationTokenSource();
        _listener = new TcpListener(_localBind, _localPort);
        _listener.Start();

        Console.WriteLine($"[转发器] 启动：本地 {_localBind}:{_localPort} → {_targetHost}:{_targetPort}");
        Console.WriteLine($"[转发器] 已加载 {_pipeline.Middlewares.Count} 个中间件");
        foreach (var mw in _pipeline.Middlewares)
        {
            Console.WriteLine($"  - {mw.Name}");
        }

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
                _ = HandleClientAsync(client, token);
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
        var connectionContext = new Dictionary<string, object>
        {
            ["ClientEndpoint"] = clientEndpoint,
            ["Client"] = client,
            ["StartTime"] = DateTime.Now
        };

        Console.WriteLine($"[连接] {clientEndpoint} 已连接");

        // 触发连接生命周期中间件
        foreach (var middleware in _pipeline.Middlewares.OfType<IConnectionLifecycleMiddleware>())
        {
            try
            {
                var allow = await middleware.OnClientConnectedAsync(clientEndpoint, connectionContext);
                if (!allow)
                {
                    Console.WriteLine($"[中间件] {middleware.Name} 拒绝连接 {clientEndpoint}");
                    client.Close();
                    return;
                }
            }
            catch (Exception ex)
            {
                Console.WriteLine($"[中间件错误] {middleware.Name}.OnClientConnected: {ex.Message}");
            }
        }

        TcpClient target = null;
        NetworkStream clientStream = null;
        NetworkStream targetStream = null;

        try
        {
            target = new TcpClient();
            await target.ConnectAsync(_targetHost, _targetPort);
            var targetEndpoint = target.Client.RemoteEndPoint;
            connectionContext["TargetEndpoint"] = targetEndpoint;
            connectionContext["Target"] = target;

            // 触发目标连接中间件
            foreach (var middleware in _pipeline.Middlewares.OfType<IConnectionLifecycleMiddleware>())
            {
                try
                {
                    var allow = await middleware.OnTargetConnectedAsync(targetEndpoint, connectionContext);
                    if (!allow)
                    {
                        Console.WriteLine($"[中间件] {middleware.Name} 拒绝目标连接 {targetEndpoint}");
                        target.Close();
                        client.Close();
                        return;
                    }
                }
                catch (Exception ex)
                {
                    Console.WriteLine($"[中间件错误] {middleware.Name}.OnTargetConnected: {ex.Message}");
                }
            }

            targetStream = target.GetStream();
            clientStream = client.GetStream();

            Console.WriteLine($"[转发] {clientEndpoint} → {_targetHost}:{_targetPort} 已建立");

            using (var cts = CancellationTokenSource.CreateLinkedTokenSource(token))
            {
                var clientToTarget = ForwardDataWithPipelineAsync(
                    clientStream, targetStream, DataDirection.ClientToTarget, connectionContext, cts.Token);
                var targetToClient = ForwardDataWithPipelineAsync(
                    targetStream, clientStream, DataDirection.TargetToClient, connectionContext, cts.Token);

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
            // 触发连接关闭中间件
            foreach (var middleware in _pipeline.Middlewares.OfType<IConnectionLifecycleMiddleware>())
            {
                try
                {
                    await middleware.OnConnectionClosedAsync(connectionContext, "Normal");
                }
                catch (Exception ex)
                {
                    Console.WriteLine($"[中间件错误] {middleware.Name}.OnConnectionClosed: {ex.Message}");
                }
            }

            clientStream?.Close();
            targetStream?.Close();
            target?.Close();
            client?.Close();
            Console.WriteLine($"[断开] {clientEndpoint} 已断开");
        }
    }

    private async Task ForwardDataWithPipelineAsync(
        NetworkStream source,
        NetworkStream destination,
        DataDirection direction,
        IDictionary<string, object> connectionContext,
        CancellationToken token)
    {
        byte[] buffer = new byte[8192];
        try
        {
            while (!token.IsCancellationRequested)
            {
                int bytesRead = await source.ReadAsync(buffer, 0, buffer.Length, token);
                if (bytesRead == 0) break;

                // 通过中间件管道处理数据
                var dataToSend = buffer.Take(bytesRead).ToArray();
                var result = await _pipeline.ExecuteAsync(direction, dataToSend, connectionContext);

                // 处理结果
                if (result.DirectResponse != null)
                {
                    // 直接响应（不转发）
                    await destination.WriteAsync(result.DirectResponse, 0, result.DirectResponse.Length, token);
                    await destination.FlushAsync(token);
                    continue;
                }

                if (!result.ShouldForward)
                {
                    // 丢弃数据
                    continue;
                }

                // 转发数据（使用修改后的数据或原始数据）
                var finalData = result.ModifiedData ?? dataToSend;
                if (finalData.Length > 0)
                {
                    await destination.WriteAsync(finalData, 0, finalData.Length, token);
                    await destination.FlushAsync(token);
                }
            }
        }
        catch (OperationCanceledException)
        {
            // 正常取消
        }
        catch (Exception ex) when (!(ex is OperationCanceledException))
        {
            Console.WriteLine($"[转发异常] {direction}: {ex.Message}");
        }
    }

    public int GetLocalPort()
    {
        return _localPort;
    }
}
/// <summary>
/// 会话处理器上下文
/// </summary>
public class SessionContext
{
    public DataDirection Direction { get; set; }
    public byte[] Data { get; set; }
    public string DataString { get; set; }
    public EndPoint ClientEndpoint { get; set; }
    public IDictionary<string, object> ConnectionContext { get; set; }
    public Dictionary<string, object> Session { get; set; }
    public bool Handled { get; set; }
    public byte[] Response { get; set; }
}

/// <summary>
/// 会话处理器接口
/// </summary>
public interface ISessionHandler
{
    string Name { get; }
    int Priority { get; }
    bool CanHandle(SessionContext context);
    Task<ForwardResult> HandleAsync(SessionContext context, ForwardDelegate next);
}
/// <summary>
/// 会话中间件 - 管理连接会话和处理器链
/// </summary>
public class SessionMiddleware : IForwardMiddleware, IConnectionLifecycleMiddleware
{
    public string Name => "Session Middleware";

    private readonly List<ISessionHandler> _handlers;
    private readonly Dictionary<string, Dictionary<string, object>> _sessions = new Dictionary<string, Dictionary<string, object>>();

    public SessionMiddleware()
    {
        _handlers = new List<ISessionHandler>();
    }

    public SessionMiddleware(params ISessionHandler[] handlers) : this()
    {
        foreach (var handler in handlers)
        {
            RegisterHandler(handler);
        }
    }

    public void RegisterHandler(ISessionHandler handler)
    {
        _handlers.Add(handler);
        _handlers.Sort((a, b) => a.Priority.CompareTo(b.Priority));
        Console.WriteLine($"[{Name}] 注册处理器: {handler.Name} (优先级: {handler.Priority})");
    }

    public void RegisterHandlers(IEnumerable<ISessionHandler> handlers)
    {
        foreach (var handler in handlers)
        {
            RegisterHandler(handler);
        }
    }

    public async Task<ForwardResult> InvokeAsync(
        DataDirection direction,
        byte[] data,
        IDictionary<string, object> connectionContext,
        ForwardDelegate next)
    {
        var clientEndpoint = (EndPoint)connectionContext["ClientEndpoint"];
        var sessionKey = clientEndpoint.ToString();

        // 获取或创建会话
        if (!_sessions.ContainsKey(sessionKey))
        {
            _sessions[sessionKey] = new Dictionary<string, object>();
        }
        var session = _sessions[sessionKey];

        // 构建上下文
        var context = new SessionContext
        {
            Direction = direction,
            Data = data,
            DataString = Encoding.ASCII.GetString(data),
            ClientEndpoint = clientEndpoint,
            ConnectionContext = connectionContext,
            Session = session,
            Handled = false
        };

        // 遍历所有处理器
        foreach (var handler in _handlers)
        {
            try
            {
                if (handler.CanHandle(context))
                {
                    var result = await handler.HandleAsync(context, next);

                    if (context.Handled)
                    {
                        return result;
                    }

                    if (result != null && (result.DirectResponse != null || result.ModifiedData != null || !result.ShouldForward))
                    {
                        return result;
                    }
                }
            }
            catch (Exception ex)
            {
                Console.WriteLine($"[{Name}] 处理器 {handler.Name} 异常: {ex.Message}");
            }
        }

        // 默认：透传
        return await next(direction, data, connectionContext);
    }

    public Task<bool> OnClientConnectedAsync(EndPoint clientEndpoint, IDictionary<string, object> connectionContext)
    {
        return Task.FromResult(true);
    }

    public Task<bool> OnTargetConnectedAsync(EndPoint targetEndpoint, IDictionary<string, object> connectionContext)
    {
        return Task.FromResult(true);
    }

    public Task OnConnectionClosedAsync(IDictionary<string, object> connectionContext, string reason)
    {
        var clientEndpoint = (EndPoint)connectionContext["ClientEndpoint"];
        _sessions.Remove(clientEndpoint.ToString());
        return Task.CompletedTask;
    }
}