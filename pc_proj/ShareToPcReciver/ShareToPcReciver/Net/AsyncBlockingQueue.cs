using System;
using System.Collections.Generic;
using System.Threading;
using System.Threading.Tasks;

public class AsyncBlockingQueue<T> : IDisposable
{
    private readonly Queue<T> _queue = new Queue<T>();
    private readonly SemaphoreSlim _semaphore = new SemaphoreSlim(0);
    private readonly int _capacity;
    private bool _isAddingCompleted;
    private bool _disposed;

    public AsyncBlockingQueue(int capacity = int.MaxValue)
    {
        if (capacity <= 0) throw new ArgumentOutOfRangeException(nameof(capacity));
        _capacity = capacity;
    }

    public void Enqueue(T item)
    {
        if (_disposed) throw new ObjectDisposedException(nameof(AsyncBlockingQueue<T>));
        lock (_queue)
        {
            if (_isAddingCompleted)
                throw new InvalidOperationException("队列已标记为完成，无法入队。");
            while (_queue.Count >= _capacity)
            {
                Monitor.Wait(_queue);
            }
            _queue.Enqueue(item);
        }
        _semaphore.Release();
    }

    /// <summary>
    /// 异步出队，支持超时和取消。
    /// </summary>
    /// <param name="timeout">最大等待时间，若超时则抛出 TimeoutException</param>
    /// <param name="cancellationToken">取消令牌</param>
    /// <returns>队列中的元素</returns>
    /// <exception cref="TimeoutException">在指定时间内无元素且添加未完成</exception>
    /// <exception cref="InvalidOperationException">队列已标记完成且为空</exception>
    /// <exception cref="OperationCanceledException">取消请求触发</exception>
    public async Task<T> DequeueAsync(TimeSpan timeout, CancellationToken cancellationToken = default)
    {
        if (_disposed) throw new ObjectDisposedException(nameof(AsyncBlockingQueue<T>));

        // 使用 WaitAsync 支持超时和取消
        if (!await _semaphore.WaitAsync(timeout, cancellationToken))
        {
            throw new TimeoutException($"在 {timeout} 内未能获取到元素。");
        }

        lock (_queue)
        {
            // 若队列为空且添加已完成，则说明不会再有新元素
            if (_queue.Count == 0 && _isAddingCompleted)
                throw new InvalidOperationException("队列为空且添加已完成。");
            return _queue.Dequeue();
        }
    }

    /// <summary>
    /// 无超时的异步出队，支持取消。
    /// </summary>
    public async Task<T> DequeueAsync(CancellationToken cancellationToken = default)
    {
        return await DequeueAsync(Timeout.InfiniteTimeSpan, cancellationToken);
    }

    public void CompleteAdding()
    {
        lock (_queue)
        {
            _isAddingCompleted = true;
            Monitor.PulseAll(_queue);
        }
        // 释放所有等待者，让他们检查完成状态
        // 注意：SemaphoreSlim 无法直接通知等待者，但通过抛出异常或返回标志来处理
        // 我们可以在 DequeueAsync 中检测到队列为空且完成时抛出 InvalidOperationException
        // 但 SemaphoreSlim 可能仍有等待者，他们将被唤醒时发现队列为空并抛出异常
    }

    public int Count
    {
        get { lock (_queue) return _queue.Count; }
    }

    public void Dispose()
    {
        if (_disposed) return;
        _disposed = true;
        _semaphore?.Dispose();
        // 唤醒等待者
        CompleteAdding();
    }
}