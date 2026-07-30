using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;
using System.Threading.Tasks;

namespace ShareToPcReciver.Net
{
    using System;
    using System.Collections.Generic;
    using System.Threading;
        /// <summary>
        /// 线程安全的阻塞队列，支持有界容量和超时操作。
        /// </summary>
        /// <typeparam name="T">队列中元素的类型</typeparam>
        public class BlockingQueue<T> : IDisposable
        {
            private readonly Queue<T> _queue = new Queue<T>();
            private readonly int _capacity;
            private bool _isAddingCompleted;
            private bool _disposed;
            private readonly object _lock = new object();

            /// <summary>
            /// 初始化阻塞队列的新实例。
            /// </summary>
            /// <param name="capacity">队列容量，默认 int.MaxValue 表示无界</param>
            /// <exception cref="ArgumentOutOfRangeException">容量小于或等于 0</exception>
            public BlockingQueue(int capacity = int.MaxValue)
            {
                if (capacity <= 0)
                    throw new ArgumentOutOfRangeException(nameof(capacity), "容量必须大于 0。");
                _capacity = capacity;
            }

            /// <summary>
            /// 将元素加入队列，若队列已满则阻塞。
            /// </summary>
            public void Enqueue(T item)
            {
                ThrowIfDisposed();
                lock (_lock)
                {
                    // 当队列已满且添加未完成时，等待
                    while (_queue.Count >= _capacity && !_isAddingCompleted)
                    {
                        Monitor.Wait(_lock);
                    }
                    // 如果添加已完成，不再接受新元素
                    if (_isAddingCompleted)
                        throw new InvalidOperationException("队列已标记为完成添加，无法再入队。");

                    _queue.Enqueue(item);
                    Monitor.PulseAll(_lock);
                }
            }

            /// <summary>
            /// 尝试将元素加入队列，在指定超时内若队列满则返回 false。
            /// </summary>
            /// <param name="item">要加入的元素</param>
            /// <param name="timeout">等待超时时间</param>
            /// <returns>成功入队返回 true，否则 false</returns>
            public bool TryEnqueue(T item, TimeSpan timeout)
            {
                ThrowIfDisposed();
                lock (_lock)
                {
                    if (_isAddingCompleted)
                        throw new InvalidOperationException("队列已标记为完成添加，无法再入队。");

                    DateTime deadline = DateTime.UtcNow + timeout;
                    while (_queue.Count >= _capacity)
                    {
                        TimeSpan remaining = deadline - DateTime.UtcNow;
                        if (remaining <= TimeSpan.Zero)
                            return false;
                        if (!Monitor.Wait(_lock, remaining))
                            return false;
                    }

                    _queue.Enqueue(item);
                    Monitor.PulseAll(_lock);
                    return true;
                }
            }

            /// <summary>
            /// 从队列中移除并返回一个元素，若队列为空则阻塞。
            /// </summary>
            /// <exception cref="InvalidOperationException">队列为空且添加已完成</exception>
            public T Dequeue()
            {
                ThrowIfDisposed();
                lock (_lock)
                {
                    // 当队列为空且添加未完成时，等待
                    while (_queue.Count == 0 && !_isAddingCompleted)
                    {
                        Monitor.Wait(_lock);
                    }
                    // 如果队列为空且添加已完成，则无元素可取
                    if (_queue.Count == 0)
                        throw new InvalidOperationException("队列为空且添加已完成。");

                    T item = _queue.Dequeue();
                    Monitor.PulseAll(_lock);
                    return item;
                }
            }

            /// <summary>
            /// 尝试从队列中取出一个元素，在指定超时内若队列为空则返回 false。
            /// </summary>
            /// <param name="item">取出的元素</param>
            /// <param name="timeout">等待超时时间</param>
            /// <returns>成功取出返回 true，否则 false</returns>
            public bool TryDequeue(out T item, TimeSpan timeout)
            {
                ThrowIfDisposed();
                item = default(T);
                lock (_lock)
                {
                    DateTime deadline = DateTime.UtcNow + timeout;
                    while (_queue.Count == 0 && !_isAddingCompleted)
                    {
                        TimeSpan remaining = deadline - DateTime.UtcNow;
                        if (remaining <= TimeSpan.Zero)
                            return false;
                        if (!Monitor.Wait(_lock, remaining))
                            return false;
                    }
                    // 如果队列为空且添加已完成，则无法返回元素
                    if (_queue.Count == 0)
                        return false;

                    item = _queue.Dequeue();
                    Monitor.PulseAll(_lock);
                    return true;
                }
            }

            /// <summary>
            /// 支持取消令牌的入队操作，若取消触发则抛出 OperationCanceledException。
            /// </summary>
            public void Enqueue(T item, CancellationToken cancellationToken)
            {
                ThrowIfDisposed();
                lock (_lock)
                {
                    while (_queue.Count >= _capacity && !_isAddingCompleted)
                    {
                        cancellationToken.ThrowIfCancellationRequested();
                        Monitor.Wait(_lock);
                    }
                    if (_isAddingCompleted)
                        throw new InvalidOperationException("队列已标记为完成添加，无法再入队。");
                    _queue.Enqueue(item);
                    Monitor.PulseAll(_lock);
                }
            }

            /// <summary>
            /// 支持取消令牌的出队操作，若取消触发则抛出 OperationCanceledException。
            /// </summary>
            public T Dequeue(CancellationToken cancellationToken)
            {
                ThrowIfDisposed();
                lock (_lock)
                {
                    while (_queue.Count == 0 && !_isAddingCompleted)
                    {
                        cancellationToken.ThrowIfCancellationRequested();
                        Monitor.Wait(_lock);
                    }
                    if (_queue.Count == 0)
                        throw new InvalidOperationException("队列为空且添加已完成。");
                    T item = _queue.Dequeue();
                    Monitor.PulseAll(_lock);
                    return item;
                }
            }

            /// <summary>
            /// 标记队列不再接受新元素，并唤醒所有等待的消费者。
            /// </summary>
            public void CompleteAdding()
            {
                ThrowIfDisposed();
                lock (_lock)
                {
                    _isAddingCompleted = true;
                    Monitor.PulseAll(_lock);
                }
            }

            /// <summary>
            /// 当前队列中的元素数量。
            /// </summary>
            public int Count
            {
                get
                {
                    lock (_lock) return _queue.Count;
                }
            }

            /// <summary>
            /// 队列是否已为空。
            /// </summary>
            public bool IsEmpty
            {
                get
                {
                    lock (_lock) return _queue.Count == 0;
                }
            }

            /// <summary>
            /// 释放资源。
            /// </summary>
            public void Dispose()
            {
                Dispose(true);
                GC.SuppressFinalize(this);
            }

            protected virtual void Dispose(bool disposing)
            {
                if (_disposed) return;
                if (disposing)
                {
                    // 唤醒所有等待线程，让它们检查状态并退出
                    lock (_lock)
                    {
                        _isAddingCompleted = true;
                        Monitor.PulseAll(_lock);
                    }
                }
                _disposed = true;
            }

            private void ThrowIfDisposed()
            {
                if (_disposed)
                    throw new ObjectDisposedException(GetType().Name);
            }
        }
    }
