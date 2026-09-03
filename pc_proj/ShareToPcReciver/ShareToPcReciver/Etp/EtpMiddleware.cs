using System;
using System.Text;
using System.Threading.Tasks;
using System.Collections.Generic;
using ShareToPcReciver.Net;

// Dictionary<TKey, TValue> 有一个扩展方法
public static class DictionaryExtensions
{
    public static TValue GetValueOrDefault<TKey, TValue>(
        this IDictionary<TKey, TValue> dictionary,
        TKey key)
    {
        dictionary.TryGetValue(key, out TValue value);
        return value;
    }

    public static TValue GetValueOrDefault<TKey, TValue>(
        this IDictionary<TKey, TValue> dictionary,
        TKey key,
        TValue defaultValue)
    {
        return dictionary.TryGetValue(key, out TValue value) ? value : defaultValue;
    }
}
public class EtpSessionMiddleware : SessionMiddleware
{
    WelcomeHandler welcomeHandler = new WelcomeHandler();
    LoginHandler loginHandler;

    public EtpSessionMiddleware(TransferServer server)
    {
        loginHandler = new LoginHandler(server);
        this.RegisterHandler(welcomeHandler);
        this.RegisterHandler(loginHandler);
    }
}
/// <summary>
/// 欢迎消息处理器
/// </summary>
public class WelcomeHandler : ISessionHandler
{
    public string Name => "Welcome Handler";
    public int Priority => 10;

    private readonly string _welcomeMessage;

    public WelcomeHandler(string welcomeMessage = null)
    {
        _welcomeMessage = welcomeMessage ?? "220 Welcome to Server\r\n";
    }

    public bool CanHandle(SessionContext context)
    {
        return context.Direction == DataDirection.TargetToClient &&
               context.DataString.StartsWith("220 ");
    }

    public async Task<ForwardResult> HandleAsync(SessionContext context, ForwardDelegate next)
    {
        context.Handled = true;
        return ForwardResult.Modify(Encoding.ASCII.GetBytes(_welcomeMessage));
    }
}

/// <summary>
/// 登录处理器 - 验证一次性 pass，拒绝未授权访问
/// </summary>
public class LoginHandler : ISessionHandler
{
    public string Name => "Login Handler";
    public int Priority => 20;

    private readonly TransferServer _server;

    public LoginHandler(TransferServer server)
    {
        _server = server;
    }

    public bool CanHandle(SessionContext context)
    {
        // 处理 USER 和 PASS 命令
        if (context.Direction == DataDirection.ClientToTarget)
        {
            return context.DataString.StartsWith("USER ", StringComparison.OrdinalIgnoreCase) ||
                   context.DataString.StartsWith("PASS ", StringComparison.OrdinalIgnoreCase);
        }

        // 处理登录响应
        if (context.Direction == DataDirection.TargetToClient)
        {
            return context.DataString.StartsWith("230 ") || // 登录成功
                   context.DataString.StartsWith("530 ");   // 登录失败
        }

        return false;
    }

    public async Task<ForwardResult> HandleAsync(SessionContext context, ForwardDelegate next)
    {
        if (context.Direction == DataDirection.ClientToTarget)
        {
            // USER 命令：记录用户名
            if (context.DataString.StartsWith("USER ", StringComparison.OrdinalIgnoreCase))
            {
                var username = context.DataString.Substring(5).Trim();
                context.Session["Username"] = username;
                Console.WriteLine($"[{Name}] USER: {username}");
                // 透传给 Everything ETP，让它回复 331
                return await next(context.Direction, context.Data, context.ConnectionContext);
            }

            // PASS 命令：验证 pass
            if (context.DataString.StartsWith("PASS ", StringComparison.OrdinalIgnoreCase))
            {
                var pass = context.DataString.Substring(5).Trim();
                var username = context.Session.GetValueOrDefault("Username", "")?.ToString();

                Console.WriteLine($"[{Name}] PASS for user: {username}");

                // 验证 pass
                if (_server.ValidateEtpPass(username, pass))
                {
                    Console.WriteLine($"[{Name}] PASS validated, forwarding to ETP");
                    context.Session["LoggedIn"] = true;
                    // 透传给 Everything，让它回复 230
                    return await next(context.Direction, context.Data, context.ConnectionContext);
                }
                else
                {
                    Console.WriteLine($"[{Name}] PASS rejected for user: {username}");
                    // 直接返回 530 拒绝，不转发给 Everything
                    return ForwardResult.Respond(Encoding.ASCII.GetBytes("530 Login incorrect\r\n"));
                }
            }

            // 其他命令：透传
            return await next(context.Direction, context.Data, context.ConnectionContext);
        }

        if (context.Direction == DataDirection.TargetToClient)
        {
            // Everything 的登录响应：透传
            return await next(context.Direction, context.Data, context.ConnectionContext);
        }

        return await next(context.Direction, context.Data, context.ConnectionContext);
    }
}

// 简单日志接口
public interface ILogger
{
    void Log(string message);
}

public class ConsoleLogger : ILogger
{
    public void Log(string message)
    {
        Console.WriteLine($"[日志] {message}");
    }
}
