using System;
using System.Collections.Generic;
using System.Drawing;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using System.Windows.Forms;
using ShareToPcReciver.Config;
using ShareToPcReciver.Models;
using ShareToPcReciver.Net;
using ShareToPcReciver.Storage;
using ShareToPcReciver.Util;
using static ShareToPcReciver.Net.TransferServer;

namespace ShareToPcReciver
{
    public partial class MainForm : Form
    {
        private readonly AppConfig _config;
        private ReceiveStorage _storage;
        private readonly ServiceAdvertiser _advertiser;
        private readonly TransferServer _server;
        private readonly List<object> _history = new List<object>();
        private readonly object _historyLock = new object();
        private readonly HistoryStore _historyStore = new HistoryStore();
        private readonly DeviceStore _deviceStore = new DeviceStore();
        private readonly PairStore _pairStore = new PairStore();
        private string _lastFilePath;
        private ListViewItem _activeRow;
        private const int ProgressColumnIndex = 6;

        public MainForm()
        {
            InitializeComponent();

            Icon appIcon = SystemIcons.Application;
            this.Icon = appIcon;
            notifyIcon.Icon = appIcon;
            notifyIcon.Visible = true;
            notifyIcon.BalloonTipClicked += (s, ev) =>
            {
                if (!string.IsNullOrEmpty(_lastFilePath))
                    _storage.OpenFolder(_lastFilePath);
            };

            _config = AppConfig.Load();
            _storage = new ReceiveStorage(_config.SaveDir);
            _advertiser = new ServiceAdvertiser();
            _server = new TransferServer(_config, _storage, _pairStore);
            _server.ClientConnected += OnClientConnected;
            _server.TextReceived += OnTextReceived;
            _server.FileReceived += OnFileReceived;
            _server.FileProgressChanged += OnFileProgress;
            _server.AskApprovoal += OnAuthRequested;

            lvHistory.ShowGroups = true;
            lvHistory.DrawColumnHeader += (s, ev) => ev.DrawDefault = true;
            lvHistory.DrawSubItem += LvHistory_DrawSubItem;
            lvHistory.KeyDown += LvHistory_KeyDown;

            UpdateStatus("服务未运行", Color.DarkRed);
            UpdateIpLabel();
        }

        private void MainForm_Load(object sender, EventArgs e)
        {
            LoadHistory();
            StartServices();
        }

        private void LoadHistory()
        {
            var items = _historyStore.Load();
            lock (_historyLock)
            {
                _history.Clear();
                _history.AddRange(items);
            }
            foreach (var item in items.AsEnumerable().Reverse())
            {
                switch (item)
                {
                    case ReceivedText text:
                    {
                        string preview = text.Text.Length > 80 ? text.Text.Substring(0, 80) + "..." : text.Text;
                        AddHistoryRow(text.ReceivedAt.ToString("HH:mm:ss"), "文本", preview, "", text.Text.Length + " 字符", text.DeviceName, text);
                        break;
                    }
                    case ReceivedFile file:
                    {
                        string ext = GetFileExtension(file.FileName);
                        string sizeText;
                        if ((file.Status == "cancelled") && file.ReceivedBytes > 0)
                            sizeText = $"{FormatSize(file.ReceivedBytes)} / {FormatSize(file.Size)}";
                        else
                            sizeText = FormatSize(file.Size);
                        AddHistoryRow(file.ReceivedAt.ToString("HH:mm:ss"), "文件", file.FileName, ext, sizeText, file.DeviceName, file);
                        break;
                    }
                }
            }
        }

        private void SaveHistory()
        {
            List<object> snapshot;
            lock (_historyLock)
            {
                snapshot = new List<object>(_history);
            }
            _historyStore.Save(snapshot);
        }

        private void StartServices()
        {
            try
            {
                _server.StartAsync(_config.Port).GetAwaiter().GetResult();
                _advertiser.Start(_config.Port, _config.DeviceId);
                UpdateStatus($"服务运行中 (端口 {_config.Port})", Color.DarkGreen);
                lblPort.Text = $"端口 {_config.Port}";
            }
            catch (Exception ex)
            {
                Log.Error("Failed to start server", ex);
                UpdateStatus($"启动失败: {ex.Message}", Color.Red);
                MessageBox.Show($"服务启动失败: {ex.Message}\n请检查端口是否被占用。", "错误", MessageBoxButtons.OK, MessageBoxIcon.Error);
            }
        }

        private void UpdateStatus(string text, Color color)
        {
            if (InvokeRequired)
            {
                BeginInvoke(new Action(() => UpdateStatus(text, color)));
                return;
            }
            toolStripStatusLabel1.Text = $"状态：{text}";
            toolStripStatusLabel1.ForeColor = color;
        }

        private void UpdateIpLabel()
        {
            string ip = GetLocalIpAddress();
            lblIp.Text = $"本机IP：{ip}";
        }

        private string GetLocalIpAddress()
        {
            try
            {
                var host = Dns.GetHostEntry(Dns.GetHostName());
                foreach (var addr in host.AddressList)
                {
                    if (addr.AddressFamily == AddressFamily.InterNetwork && !addr.ToString().StartsWith("127."))
                        return addr.ToString();
                }
            }
            catch { }
            return "127.0.0.1";
        }

        private void BtnCopyIp_Click(object sender, EventArgs e)
        {
            string ip = lblIp.Text.Replace("本机IP：", "");
            Clipboard.SetText(ip);
            lblIp.Text = "本机IP：" + ip + " (已复制)";
            System.Windows.Forms.Timer t = new System.Windows.Forms.Timer { Interval = 2000 };
            t.Tick += (s, ev) => { lblIp.Text = "本机IP：" + ip; t.Stop(); t.Dispose(); };
            t.Start();
        }

        private void BtnOpenDir_Click(object sender, EventArgs e)
        {
            try
            {
                string dir = _config.SaveDir;
                if (!string.IsNullOrEmpty(dir) && Directory.Exists(dir))
                {
                    System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo
                    {
                        FileName = dir,
                        UseShellExecute = true
                    });
                }
                else
                {
                    MessageBox.Show($"接收目录不存在：\n{dir}", "提示", MessageBoxButtons.OK, MessageBoxIcon.Information);
                }
            }
            catch (Exception ex)
            {
                Log.Error($"Failed to open folder: {_config.SaveDir}", ex);
            }
        }

        private void BtnSettings_Click(object sender, EventArgs e)
        {
            using var dlg = new SettingsDialog(_config, _server.Port);
            if (dlg.ShowDialog() == DialogResult.OK)
            {
                bool needRestart = _config.Port != dlg.Port || _config.SaveDir != dlg.SaveDir;
                _config.Port = dlg.Port;
                _config.SaveDir = dlg.SaveDir;
                _config.TextAction = dlg.TextAction;
                _config.FileAction = dlg.FileAction;
                _config.ShowBalloon = dlg.ShowBalloon;
                _config.RunAtStartup = dlg.RunAtStartup;
                _config.Save();
                _config.ApplyRunAtStartup();
                if (needRestart)
                {
                    _storage = new ReceiveStorage(_config.SaveDir);
                    _server.Storage = _storage;
                    RestartServices();
                }
            }
        }

        private void BtnDevices_Click(object sender, EventArgs e)
        {
            using var dlg = new DeviceManagerDialog(_pairStore, _deviceStore);
            dlg.ShowDialog(this);
        }

        private void BtnMinimize_Click(object sender, EventArgs e)
        {
            this.WindowState = FormWindowState.Minimized;
            this.Hide();
            notifyIcon.Visible = true;
        }

        private void NotifyIcon_DoubleClick(object sender, EventArgs e)
        {
            ShowWindow();
        }

        private void ToolStripMenuItemOpen_Click(object sender, EventArgs e)
        {
            ShowWindow();
        }

        private async void ToolStripMenuItemExit_Click(object sender, EventArgs e)
        {
            await ShutdownAsync();
            Application.Exit();
        }

        private void ShowWindow()
        {
            this.Show();
            this.WindowState = FormWindowState.Normal;
            this.BringToFront();
        }

        private async void MainForm_FormClosing(object sender, FormClosingEventArgs e)
        {
            if (e.CloseReason == CloseReason.UserClosing)
            {
                e.Cancel = true;
                this.WindowState = FormWindowState.Minimized;
                this.Hide();
                notifyIcon.Visible = true;
                return;
            }

            // Windows 关机 / 注销等 → 清理资源
            if (e.CloseReason == CloseReason.WindowsShutDown ||
                e.CloseReason == CloseReason.TaskManagerClosing)
            {
                await ShutdownAsync();
            }
        }

        private async Task ShutdownAsync()
        {
            try
            {
                // 1. 先停 mDNS 广播，Android 端更快感知
                _advertiser.Stop();

                // 2. 广播 OFFLINE 给所有已连接设备，然后停 TCP 服务
                await _server.ShutdownAsync();

                Log.Info("Services shut down cleanly");
            }
            catch (Exception ex)
            {
                Log.Error("Failed to shutdown services", ex);
            }
        }

        private void RestartServices()
        {
            try
            {
                _server.Stop();
                _advertiser.Stop();
                StartServices();
            }
            catch (Exception ex)
            {
                Log.Error("Failed to restart services", ex);
                MessageBox.Show($"重启服务失败: {ex.Message}", "错误", MessageBoxButtons.OK, MessageBoxIcon.Error);
            }
        }

        private void OnClientConnected(ClientInfo info)
        {
            _deviceStore.SaveDevice(info);
            this.Invoke(new Action(() =>
            {
                Log.Info($"设备已连接: {info.Name} ({info.Address}) v{info.Version} proto{info.ProtoVersion} id:{info.DeviceId}");
            }));
        }

        private void OnAuthRequested(AuthResult result, CancellationToken cancellation, TaskCompletionSource<AuthResult> tcs)
        {
            AuthInfo req = result.authJobInfo;
            this.Invoke(new Action(() =>
            {
                using var dlg = new Form
                {
                    Text = "文件传输请求",
                    StartPosition = FormStartPosition.CenterParent,
                    Size = new Size(440, 400),
                    FormBorderStyle = FormBorderStyle.FixedDialog,
                    MinimizeBox = false,
                    MaximizeBox = false,
                    ShowInTaskbar = false
                };

                var label = new Label
                {
                    Text = req.Summary,
                    Location = new Point(20, 20),
                    Size = new Size(350, 70),
                    AutoSize = false
                };
                dlg.Controls.Add(label);

                // 过期时间选择
                var lblExpire = new Label
                {
                    Text = "授权过期:",
                    Location = new Point(20, 105),
                    Size = new Size(60, 25),
                    TextAlign = ContentAlignment.MiddleLeft
                };
                var cmbExpire = new ComboBox
                {
                    DropDownStyle = ComboBoxStyle.DropDownList,
                    Location = new Point(85, 105),
                    Size = new Size(120, 23)
                };
                // 选项：天数 → 对应的毫秒偏移 (0 = 永不过期)
                var expireOptions = new[]
                {
                    new { Text = "永不过期", Days = 0 },
                    new { Text = "1 天", Days = 1 },
                    new { Text = "3 天", Days = 3 },
                    new { Text = "7 天", Days = 7 },
                    new { Text = "30 天", Days = 30 },
                    new { Text = "90 天", Days = 90 },
                };
                cmbExpire.BindingContext = new BindingContext();
                cmbExpire.DisplayMember = "Text";
                cmbExpire.DataSource = expireOptions;
                cmbExpire.SelectedIndex = 3; // 默认 7 天
                dlg.Controls.Add(lblExpire);
                dlg.Controls.Add(cmbExpire);

                // 收到文本时的动作
                var lblTextAction = new Label
                {
                    Text = "收到文本时:",
                    Location = new Point(20, 140),
                    Size = new Size(80, 25),
                    TextAlign = ContentAlignment.MiddleLeft
                };
                var cmbTextAction = new ComboBox
                {
                    DropDownStyle = ComboBoxStyle.DropDownList,
                    Location = new Point(105, 140),
                    Size = new Size(140, 23)
                };
                cmbTextAction.Items.AddRange(new[] { "跟随全局设定", "无操作", "复制文本到剪贴板" });
                cmbTextAction.SelectedIndex = 0;
                dlg.Controls.Add(lblTextAction);
                dlg.Controls.Add(cmbTextAction);

                var btnBlock = new Button
                {
                    Text = "拉黑",
                    DialogResult = DialogResult.Abort,
                    Location = new Point(30, 185),
                    Size = new Size(75, 30)
                };
                var btnReject = new Button
                {
                    Text = "拒绝 (30s)",
                    DialogResult = DialogResult.No,
                    Location = new Point(115, 185),
                    Size = new Size(100, 30)
                };
                var btnOnce = new Button
                {
                    Text = "本次",
                    DialogResult = DialogResult.Yes,
                    Location = new Point(225, 185),
                    Size = new Size(75, 30),
                };
                var btnAlways = new Button
                {
                    Text = "本次及后续",
                    DialogResult = DialogResult.OK,
                    Location = new Point(310, 185),
                    Size = new Size(100, 30)
                };

                dlg.Controls.Add(btnBlock);
                dlg.Controls.Add(btnReject);
                dlg.Controls.Add(btnOnce);
                dlg.Controls.Add(btnAlways);
                dlg.AcceptButton = btnOnce;
                dlg.CancelButton = btnReject;
                dlg.DialogResult = DialogResult.Cancel;
                // 倒计时：30 秒超时自动拒绝
                int countdown = 30;
                var timer = new System.Windows.Forms.Timer { Interval = 1000 };
                timer.Tick += (s, ev) =>
                {
                    countdown--;
                    if (countdown <= 0 || cancellation.IsCancellationRequested)
                    {
                        timer.Stop();
                        dlg.CancelButton.PerformClick();
                    }
                    else
                    {
                        btnReject.Text = $"拒绝 ({countdown}s)";

                    }
                };
                timer.Start();
                dlg.Disposed += (s, ev) => { timer.Stop(); timer.Dispose(); };

                var dr = dlg.ShowDialog(this);
                timer.Stop();

                if (dr == DialogResult.Abort)
                {
                    // 拉黑设备
                    _pairStore.BlockDevice(req.DeviceId);
                    Util.Log.Info($"Device {req.DeviceName} has been blocked");
                    result.Status = "REJECTED";
                    tcs.SetResult(result);
                }
                else if (dr == DialogResult.OK)
                {
                    dynamic selected = cmbExpire.SelectedItem;
                    int days = selected.Days;
                    if (days > 0)
                        result.ExpiresAt = DateTimeOffset.UtcNow.AddDays(days).ToUnixTimeMilliseconds();
                    result.Credits = -1;                    // 永久授权
                    result.MaxItemsPerJob = int.MaxValue;
                    result.MaxBytesPerJob = long.MaxValue;
                    result.TextAction = cmbTextAction.SelectedIndex switch
                    {
                        1 => ArrivalAction.None,
                        2 => ArrivalAction.CopyText,
                        _ => null
                    };
                    result.Status = "ACCEPTED";
                    tcs.SetResult(result);
                }
                else if (dr == DialogResult.Yes)
                {
                    result.Status = "ACCEPTED";
                    result.Credits = 1;
                    result.TextAction = cmbTextAction.SelectedIndex switch
                    {
                        1 => ArrivalAction.None,
                        2 => ArrivalAction.CopyText,
                        _ => null
                    };
                    tcs.SetResult(result);
                }
                else
                {
                    result.Status = "REJECTED";
                    tcs.SetResult(result);
                }
            }));
        }

        private void OnTextReceived(ReceivedText msg)
        {
            lock (_historyLock)
            {
                _history.Insert(0, msg);
                if (_history.Count > 500)
                    _history.RemoveAt(_history.Count - 1);
            }
            SaveHistory();

            this.Invoke(new Action(() =>
            {
                string preview = msg.Text.Length > 80 ? msg.Text.Substring(0, 80) + "..." : msg.Text;
                AddHistoryRow(msg.ReceivedAt.ToString("HH:mm:ss"), "文本", preview, "", msg.Text.Length + " 字符", msg.DeviceName, msg);
                PerformTextAction(msg);

                if (_config.ShowBalloon)
                {
                    notifyIcon.BalloonTipTitle = "收到文本";
                    notifyIcon.BalloonTipText = preview;
                    notifyIcon.ShowBalloonTip(3000);
                }
            }));
        }

        private void OnFileReceived(ReceivedFile msg)
        {
            lock (_historyLock)
            {
                ReplaceHistoryEntry(msg);
                if (_history.Count > 500)
                    _history.RemoveAt(_history.Count - 1);
            }
            SaveHistory();

            this.Invoke(new Action(() =>
            {
                if (_activeRow != null)
                {
                    // 传输中插入的行转为正式文件行
                    _activeRow.Text = msg.ReceivedAt.ToString("HH:mm:ss");
                    _activeRow.SubItems[1].Text = "文件";
                    _activeRow.SubItems[2].Text = msg.FileName;
                    _activeRow.SubItems[3].Text = GetFileExtension(msg.FileName);
                    _activeRow.SubItems[4].Text = FormatSize(msg.Size);
                    _activeRow.SubItems[5].Text = msg.DeviceName;
                    _activeRow.Tag = msg;
                    RefreshRow(_activeRow);
                    _activeRow = null;
                }
                else
                {
                    string ext = GetFileExtension(msg.FileName);
                    AddHistoryRow(msg.ReceivedAt.ToString("HH:mm:ss"), "文件", msg.FileName, ext, FormatSize(msg.Size), msg.DeviceName, msg);
                }
                PerformFileAction(msg);

                if (_config.ShowBalloon)
                {
                    _lastFilePath = msg.FilePath;
                    notifyIcon.BalloonTipTitle = "收到文件";
                    notifyIcon.BalloonTipText = $"{msg.FileName} ({FormatSize(msg.Size)})";
                    notifyIcon.ShowBalloonTip(5000);
                }
            }));
        }

        private void OnFileProgress(FileProgressInfo p)
        {
            if (IsDisposed || !IsHandleCreated)
                return;
            this.Invoke(new Action(() =>
            {
                if (p.Cancelled)
                {
                    if (_activeRow != null)
                    {
                        _activeRow.Tag = p;
                        RefreshRow(_activeRow);
                        // 写入历史，重启后可恢复
                        var rf = MakeFailedRecord(p, "canceled");
                        lock (_historyLock) { ReplaceHistoryEntry(rf); }
                        SaveHistory();
                        _activeRow = null;
                    }
                    return;
                }
                if (p.Cancelled)
                {
                    if (_activeRow != null)
                    {
                        _activeRow.Tag = p;
                        RefreshRow(_activeRow);
                        // 写入历史
                        var rf = MakeFailedRecord(p, "cancelled");
                        lock (_historyLock) { ReplaceHistoryEntry(rf); }
                        SaveHistory();
                        _activeRow = null;
                    }
                    else
                    {
                        // 取消时可能还没有 activeRow（传输未开始时取消）
                        string name = p.FileName.Length > 0 ? p.FileName : "(未知文件)";
                        string ext = GetFileExtension(p.FileName);
                        var existingRow = FindRowByTransferId(p.TransferId);
                        ListViewItem item;
                        if (existingRow != null)
                        {
                            existingRow.Tag = p;
                            item = existingRow;
                        }
                        else
                        {
                            item = AddHistoryRow(DateTime.Now.ToString("HH:mm:ss"), "文件", name, ext, FormatSize(p.Total), p.DeviceName, p);
                        }
                        RefreshRow(item);
                        var rf = MakeFailedRecord(p, "cancelled");
                        lock (_historyLock) { ReplaceHistoryEntry(rf); }
                        SaveHistory();
                    }
                    return;
                }
                if (_activeRow == null)
                {
                    // 检查是否已有同 TransferId 的行（恢复取消的传输），有则复用
                    var existingRow = FindRowByTransferId(p.TransferId);
                    if (existingRow != null)
                    {
                        existingRow.Tag = p;
                        _activeRow = existingRow;
                        lock (_historyLock) { RemoveHistoryByTransferId(p.TransferId); }
                        SaveHistory();
                    }
                    else
                    {
                        string ext = GetFileExtension(p.FileName);
                        _activeRow = AddHistoryRow(DateTime.Now.ToString("HH:mm:ss"), "文件", p.FileName, ext, FormatSize(p.Total), p.DeviceName, p);
                    }
                }
                else
                {
                    _activeRow.Tag = p;
                }
                RefreshRow(_activeRow);
            }));
        }

        private ReceivedFile MakeFailedRecord(FileProgressInfo p, string status)
        {
            return new ReceivedFile
            {
                FileName = p.FileName,
                Size = p.Total,
                DeviceName = p.DeviceName,
                Time = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(),
                Status = status,
                TotalBytes = p.Total,
                ReceivedBytes = p.Received,
                TransferId = p.TransferId ?? "",
                JobId = p.JobId ?? ""
            };
        }

        private void RefreshRow(ListViewItem item)
        {
            if (item != null && item.Index >= 0)
                lvHistory.RedrawItems(item.Index, item.Index, true);
        }

        private void LvHistory_DrawSubItem(object sender, DrawListViewSubItemEventArgs e)
        {
            if (e.ColumnIndex != ProgressColumnIndex)
            {
                e.DrawDefault = true;
                return;
            }

            // 传输中：FileProgressInfo（实时进度条）
            if (e.Item.Tag is FileProgressInfo p)
            {
                string label = $"{p.Percent}%";
                Color clr = SystemColors.ControlText;
                DrawProgressCell(e, p.Percent, label, clr);
                return;
            }

            // 重启后加载的历史记录：ReceivedFile
            if (e.Item.Tag is ReceivedFile rf)
            {
                if (rf.Status == "cancelled")
                {
                    int pct = rf.TotalBytes > 0 ? (int)(rf.ReceivedBytes * 100 / rf.TotalBytes) : 0;
                    Color clr =  Color.Gray;
                    string label = $"{pct}%";
                    DrawProgressCell(e, pct, label, clr);
                }
                else
                {
                    bool sel = e.Item.Selected;
                    var bg = sel ? SystemColors.Highlight : e.Item.BackColor;
                    using (var bgBrush = new SolidBrush(bg))
                        e.Graphics.FillRectangle(bgBrush, e.Bounds);
                    var fg = sel ? SystemColors.HighlightText : Color.Green;
                    TextRenderer.DrawText(e.Graphics, "完成", lvHistory.Font, e.Bounds, fg,
                        TextFormatFlags.HorizontalCenter | TextFormatFlags.VerticalCenter);
                }
                return;
            }

            e.DrawDefault = true;
        }

        private void DrawProgressCell(DrawListViewSubItemEventArgs e, int percent,
                                       string label, Color textColor)
        {
            bool selected = e.Item.Selected;

            // 显式绘制背景（选中/未选中），避免 e.DrawBackground 不可靠
            Color bgColor = selected ? SystemColors.Highlight : e.Item.BackColor;
            using (var bgBrush = new SolidBrush(bgColor))
                e.Graphics.FillRectangle(bgBrush, e.Bounds);

            var rect = new Rectangle(e.Bounds.X + 3, e.Bounds.Y + 3,
                Math.Max(0, e.Bounds.Width - 6), Math.Max(0, e.Bounds.Height - 6));

            if (ProgressBarRenderer.IsSupported && !selected && rect.Width > 4 && rect.Height > 4)
            {
                ProgressBarRenderer.DrawHorizontalBar(e.Graphics, rect);
                int fillWidth = (rect.Width - 2) * percent / 100;
                if (fillWidth > 0)
                {
                    var fill = new Rectangle(rect.X + 1, rect.Y + 1, fillWidth, rect.Height - 2);
                    ProgressBarRenderer.DrawHorizontalChunks(e.Graphics, fill);
                }
            }
            else if (rect.Width > 4 && rect.Height > 4)
            {
                // 选中或 ProgressBarRenderer 不支持时，自绘进度条
                Color trackColor = selected ? SystemColors.Highlight : SystemColors.ControlLight;
                Color fillColor = selected ? SystemColors.HighlightText : Color.ForestGreen;
                Color borderColor = selected ? SystemColors.HighlightText : SystemColors.ControlDark;

                using (var fillBrush = new SolidBrush(fillColor))
                {
                    int fillWidth = selected
                        ? (rect.Width - 2) * percent / 100
                        : rect.Width * percent / 100;
                    if (fillWidth > 0)
                        e.Graphics.FillRectangle(fillBrush,
                            selected ? rect.X + 1 : rect.X,
                            selected ? rect.Y + 1 : rect.Y,
                            fillWidth,
                            selected ? rect.Height - 2 : rect.Height);
                }
                using (var pen = new Pen(borderColor))
                    e.Graphics.DrawRectangle(pen, rect);
            }

            Color finalColor = selected ? SystemColors.HighlightText : textColor;
            TextRenderer.DrawText(e.Graphics, label, lvHistory.Font, e.Bounds, finalColor,
                TextFormatFlags.HorizontalCenter | TextFormatFlags.VerticalCenter);
        }

        private static string FormatSize(long size)
        {
            return size >= 1024 * 1024
                ? $"{size / (1024.0 * 1024.0):F1}MB"
                : size >= 1024
                    ? $"{size / 1024.0:F1}KB"
                    : $"{size}B";
        }

        private static string GetFileExtension(string fileName)
        {
            if (string.IsNullOrEmpty(fileName))
                return "-";
            string ext = Path.GetExtension(fileName);
            return string.IsNullOrEmpty(ext) ? "-" : ext.TrimStart('.').ToLowerInvariant();
        }

        private ListViewItem AddHistoryRow(string time, string type, string name, string extension, string size, string source, object tag)
        {
            var item = new ListViewItem(time);
            item.SubItems.Add(type);
            item.SubItems.Add(name);
            item.SubItems.Add(extension);
            item.SubItems.Add(size);
            item.SubItems.Add(source);
            item.SubItems.Add(""); // 进度列（自绘，文本留空）
            item.Tag = tag;
            item.Group = GetOrCreateGroup(tag, source);
            lvHistory.Items.Insert(0, item);

            while (lvHistory.Items.Count > 200)
            {
                var last = lvHistory.Items[lvHistory.Items.Count - 1];
                if (ReferenceEquals(last, _activeRow))
                    break; // 不裁剪正在传输的行
                lvHistory.Items.RemoveAt(lvHistory.Items.Count - 1);
            }
            return item;
        }

        /// <summary>将毫秒时间戳转为对人友好文案，如"今天 14:30""昨天 09:15""7月25日 16:00"。</summary>
        private static string FriendlyTime(long timeMillis)
        {
            var now = DateTime.Now;
            var target = DateTimeOffset.FromUnixTimeMilliseconds(timeMillis).LocalDateTime;
            string hourMin = target.ToString("HH:mm");

            if (now.Year == target.Year && now.DayOfYear == target.DayOfYear)
                return "今天 " + hourMin;

            var yesterday = now.AddDays(-1);
            if (yesterday.Year == target.Year && yesterday.DayOfYear == target.DayOfYear)
                return "昨天 " + hourMin;

            return target.ToString("M月d日 HH:mm");
        }

        /// <summary>从 tag 中提取时间戳（毫秒），用于分组显示。</summary>
        private static long GetTagTime(object tag)
        {
            if (tag is ReceivedText rt) return rt.Time;
            if (tag is ReceivedFile rf) return rf.Time;
            return DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
        }

        /// <summary>按 jobId 分组，若无 jobId 则按设备名分组。活跃分组排在最前。</summary>
        private ListViewGroup GetOrCreateGroup(object tag, string deviceName)
        {
            string jobId = null;
            if (tag is ReceivedText rt) jobId = rt.JobId;
            else if (tag is ReceivedFile rf) jobId = rf.JobId;
            else if (tag is FileProgressInfo pi) jobId = pi.JobId;

            string key = !string.IsNullOrEmpty(jobId) ? "job:" + jobId : "dev:" + (deviceName ?? "(未知)");
            string header = (deviceName ?? "(未知)") + " · " + FriendlyTime(GetTagTime(tag));

            foreach (ListViewGroup g in lvHistory.Groups)
            {
                if (g.Name == key)
                {
                    if (lvHistory.Groups[0] != g)
                    {
                        lvHistory.Groups.Remove(g);
                        lvHistory.Groups.Insert(0, g);
                    }
                    return g;
                }
            }
            var group = new ListViewGroup(key, header);
            lvHistory.Groups.Insert(0, group);
            return group;
        }

        /// <summary>按 TransferId 在列表中查找已有行，用于恢复取消的传输时复用</summary>
        private ListViewItem FindRowByTransferId(string transferId)
        {
            if (string.IsNullOrEmpty(transferId))
                return null;
            foreach (ListViewItem item in lvHistory.Items)
            {
                if (item.Tag is ReceivedFile rf && rf.TransferId == transferId)
                    return item;
                if (item.Tag is FileProgressInfo pi && pi.TransferId == transferId)
                    return item;
            }
            return null;
        }

        /// <summary>将新记录写入 _history，同时移除同 TransferId 的旧条目，避免重复</summary>
        private void ReplaceHistoryEntry(object newEntry)
        {
            string tid = null;
            if (newEntry is ReceivedFile rf)
                tid = rf.TransferId;
            else if (newEntry is FileProgressInfo pi)
                tid = pi.TransferId;

            if (!string.IsNullOrEmpty(tid))
                _history.RemoveAll(obj =>
                    (obj is ReceivedFile r && r.TransferId == tid) ||
                    (obj is FileProgressInfo p && p.TransferId == tid));

            _history.Insert(0, newEntry);
        }

        /// <summary>仅移除历史中同 TransferId 的条目（不插入新条目）</summary>
        private void RemoveHistoryByTransferId(string transferId)
        {
            if (string.IsNullOrEmpty(transferId))
                return;
            _history.RemoveAll(obj =>
                (obj is ReceivedFile r && r.TransferId == transferId) ||
                (obj is FileProgressInfo p && p.TransferId == transferId));
        }

        // ---- 内容到达动作 ----

        private void PerformTextAction(ReceivedText msg)
        {
            // 优先使用设备级设定，null = 跟随全局设定
            var pairSession = _pairStore.LoadAll().FirstOrDefault(s => s.DeviceName == msg.DeviceName);
            var action = pairSession?.TextAction ?? _config.TextAction;

            if (action != ArrivalAction.CopyText)
                return;
            try { Clipboard.SetText(msg.Text); }
            catch (Exception ex) { Log.Error("Copy text failed", ex); }
        }

        private void PerformFileAction(ReceivedFile msg)
        {
            if (_config.FileAction == ArrivalAction.None)
                return;
            if (string.IsNullOrEmpty(msg.FilePath) || !File.Exists(msg.FilePath))
                return;
            try
            {
                switch (_config.FileAction)
                {
                    case ArrivalAction.CopyFile:
                        var files = new System.Collections.Specialized.StringCollection { msg.FilePath };
                        Clipboard.SetFileDropList(files);
                        break;
                    case ArrivalAction.CopyFilePath:
                        Clipboard.SetText(msg.FilePath);
                        break;
                    case ArrivalAction.OpenFile:
                        OpenReceivedFile(msg);
                        break;
                    case ArrivalAction.OpenDir:
                        _storage.OpenFolder(msg.FilePath);
                        break;
                }
            }
            catch (Exception ex)
            {
                Log.Error($"File arrival action {_config.FileAction} failed", ex);
            }
        }

        // ---- 历史记录交互：双击打开 / 键盘操作 / 右键菜单 ----

        private List<object> GetSelectedRecords()
        {
            var result = new List<object>();
            foreach (ListViewItem item in lvHistory.SelectedItems)
            {
                if (item.Tag is ReceivedFile || item.Tag is ReceivedText || item.Tag is FileProgressInfo)
                    result.Add(item.Tag);
            }
            return result;
        }

        private object GetSelectedRecord()
        {
            return lvHistory.SelectedItems.Count > 0 ? lvHistory.SelectedItems[0].Tag : null;
        }

        private void LvHistory_KeyDown(object sender, KeyEventArgs e)
        {
            if (e.Control && e.KeyCode == Keys.A)
            {
                foreach (ListViewItem item in lvHistory.Items)
                    item.Selected = true;
                e.Handled = true;
            }
            else if (e.KeyCode == Keys.Delete)
            {
                DeleteSelectedRecords(false);
                e.Handled = true;
            }
            else if (e.Control && e.KeyCode == Keys.D)
            {
                DeleteSelectedRecords(true);
                e.Handled = true;
            }
        }

        private void LvHistory_DoubleClick(object sender, EventArgs e)
        {
            switch (GetSelectedRecord())
            {
                case ReceivedFile file:
                    OpenReceivedFile(file);
                    break;
                case ReceivedText text:
                    ShowTextDetail(text);
                    break;
            }
        }

        private void LvHistoryMenu_Opening(object sender, System.ComponentModel.CancelEventArgs e)
        {
            var selected = GetSelectedRecords();
            if (selected.Count == 0)
            {
                e.Cancel = true;
                return;
            }

            bool singleSelect = selected.Count == 1;
            object record = selected[0];
            bool isFile = record is ReceivedFile;
            bool isActiveTransfer = record is FileProgressInfo;
            bool fileExists = isFile
                && !string.IsNullOrEmpty(((ReceivedFile)record).FilePath)
                && File.Exists(((ReceivedFile)record).FilePath);

            // 单条时显示操作类菜单（传输中不适用）
            menuOpenFile.Visible = singleSelect && isFile;
            menuOpenFile.Enabled = fileExists;
            menuOpenFolder.Visible = singleSelect && isFile;
            menuOpenFolder.Enabled = fileExists;
            menuCopyContent.Text = singleSelect && isFile ? "复制文件路径" : "复制文本";
            menuCopyContent.Visible = singleSelect && !isActiveTransfer;
            menuViewDetail.Visible = singleSelect && !isFile && !isActiveTransfer;

            // 取消/继续传输菜单
            bool isCancelledTransfer = isActiveTransfer && ((FileProgressInfo)record).Cancelled;
            menuCancelTransfer.Visible = singleSelect && isActiveTransfer && !isCancelledTransfer;
            menuContinueTransfer.Visible = singleSelect && isCancelledTransfer;
            menuSeparator1.Visible = !isActiveTransfer || isCancelledTransfer;

            // 删除菜单始终可见
            int n = selected.Count;
            menuDeleteRecord.Text = singleSelect ? "删除记录" : $"删除 {n} 条记录";

            bool hasFileToDelete = selected.Any(s => s is ReceivedFile rf
                && !string.IsNullOrEmpty(rf.FilePath) && File.Exists(rf.FilePath));
            menuDeleteWithFile.Visible = hasFileToDelete;
            menuDeleteWithFile.Text = singleSelect ? "删除记录 + 文件" : $"删除 {n} 条记录 + 文件";
        }

        private void MenuOpenFile_Click(object sender, EventArgs e)
        {
            if (GetSelectedRecord() is ReceivedFile file)
                OpenReceivedFile(file);
        }

        private void MenuOpenFolder_Click(object sender, EventArgs e)
        {
            if (GetSelectedRecord() is ReceivedFile file && !string.IsNullOrEmpty(file.FilePath))
                _storage.OpenFolder(file.FilePath);
        }

        private void MenuCopyContent_Click(object sender, EventArgs e)
        {
            try
            {
                switch (GetSelectedRecord())
                {
                    case ReceivedText text:
                        Clipboard.SetText(text.Text);
                        break;
                    case ReceivedFile file when !string.IsNullOrEmpty(file.FilePath):
                        Clipboard.SetText(file.FilePath);
                        break;
                }
            }
            catch (Exception ex)
            {
                Log.Error("Copy to clipboard failed", ex);
            }
        }

        private void MenuViewDetail_Click(object sender, EventArgs e)
        {
            if (GetSelectedRecord() is ReceivedText text)
                ShowTextDetail(text);
        }

        private void MenuCancelTransfer_Click(object sender, EventArgs e)
        {
            // 从选中行提取 jobId 并标记为已取消，Android 重发时会收到 reciverCanceled=true
            if (GetSelectedRecord() is FileProgressInfo pi && !string.IsNullOrEmpty(pi.JobId))
                _server.MarkJobCanceled(pi.JobId);

            _server.DisconnectAllSessions();
            UpdateStatus("已取消当前传输", Color.Black);
        }

        private void MenuContinueTransfer_Click(object sender, EventArgs e)
        {
            // 清除取消标记，Android 重发 JOB_INFO 时不再返回 reciverCanceled=true
            if (GetSelectedRecord() is FileProgressInfo pi && !string.IsNullOrEmpty(pi.JobId))
                _server.ClearJobCanceled(pi.JobId);

            UpdateStatus("已允许继续传输，请在手机上重试", Color.Black);
        }

        private void MenuDeleteRecord_Click(object sender, EventArgs e)
        {
            DeleteSelectedRecords(false);
        }

        private void MenuDeleteWithFile_Click(object sender, EventArgs e)
        {
            DeleteSelectedRecords(true);
        }

        private void DeleteSelectedRecords(bool deleteFiles)
        {
            var selectedItems = new List<ListViewItem>();
            foreach (ListViewItem item in lvHistory.SelectedItems)
            {
                if (item.Tag is ReceivedFile || item.Tag is ReceivedText)
                    selectedItems.Add(item);
            }

            if (selectedItems.Count == 0)
                return;

            string msg = deleteFiles
                ? $"确定删除选中的 {selectedItems.Count} 条记录及其关联文件？\n此操作不可恢复。"
                : $"确定删除选中的 {selectedItems.Count} 条记录？";
            if (MessageBox.Show(msg, "确认删除", MessageBoxButtons.OKCancel, MessageBoxIcon.Warning) != DialogResult.OK)
                return;

            int deletedFiles = 0;
            foreach (ListViewItem item in selectedItems)
            {
                if (item.Tag is ReceivedFile rf)
                {
                    // 删除已完成文件的实体
                    if (deleteFiles
                        && !string.IsNullOrEmpty(rf.FilePath) && File.Exists(rf.FilePath))
                    {
                        try { File.Delete(rf.FilePath); deletedFiles++; }
                        catch (Exception ex) { Log.Error($"Failed to delete file: {rf.FilePath}", ex); }
                    }

                    // 删除未完成/失败传输的 .part 临时文件
                    if (rf.Status == "cancelled")
                    {
                        if (!string.IsNullOrEmpty(rf.TransferId))
                        {
                            try { _storage.DiscardPartByTransferId(rf.TransferId); }
                            catch (Exception ex) { Log.Error($"Failed to discard part for {rf.FileName}", ex); }
                        }
                    }
                }

                lock (_historyLock)
                {
                    _history.Remove(item.Tag);
                }

                lvHistory.Items.Remove(item);
            }

            SaveHistory();

            string doneMsg = deleteFiles
                ? $"已删除 {selectedItems.Count} 条记录，{deletedFiles} 个文件"
                : $"已删除 {selectedItems.Count} 条记录";
            UpdateStatus(doneMsg, Color.Black);
        }

        private void OpenReceivedFile(ReceivedFile file)
        {
            if (string.IsNullOrEmpty(file.FilePath) || !File.Exists(file.FilePath))
            {
                MessageBox.Show($"文件不存在或已被移动：\n{file.FilePath}", "无法打开",
                    MessageBoxButtons.OK, MessageBoxIcon.Warning);
                return;
            }
            try
            {
                System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo
                {
                    FileName = file.FilePath,
                    UseShellExecute = true
                });
            }
            catch (Exception ex)
            {
                Log.Error($"Failed to open file: {file.FilePath}", ex);
                MessageBox.Show($"打开文件失败：{ex.Message}", "错误",
                    MessageBoxButtons.OK, MessageBoxIcon.Error);
            }
        }

        private void ShowTextDetail(ReceivedText text)
        {
            using var dlg = new Form
            {
                Text = $"来自 {text.DeviceName} 的文本 · {text.ReceivedAt:yyyy-MM-dd HH:mm:ss}",
                StartPosition = FormStartPosition.CenterParent,
                Size = new Size(520, 360),
                MinimizeBox = false,
                MaximizeBox = false
            };
            var txt = new TextBox
            {
                Multiline = true,
                ReadOnly = true,
                ScrollBars = ScrollBars.Vertical,
                Dock = DockStyle.Fill
            };
            txt.Text = text.Text.Replace("\n", Environment.NewLine);
            var btnCopy = new Button { Text = "复制", AutoSize = true };
            btnCopy.Click += (s, ev) =>
            {
                try { Clipboard.SetText(text.Text); btnCopy.Text = "已复制"; } catch { }
            };
            var btnClose = new Button { Text = "关闭", DialogResult = DialogResult.OK, AutoSize = true };
            var panel = new FlowLayoutPanel
            {
                Dock = DockStyle.Bottom,
                FlowDirection = FlowDirection.RightToLeft,
                Height = 40,
                Padding = new Padding(4)
            };
            panel.Controls.Add(btnClose);
            panel.Controls.Add(btnCopy);
            dlg.Controls.Add(txt);
            dlg.Controls.Add(panel);
            dlg.AcceptButton = btnClose;
            dlg.ShowDialog(this);
        }
    }
}