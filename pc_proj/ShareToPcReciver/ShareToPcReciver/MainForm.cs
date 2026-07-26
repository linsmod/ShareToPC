using System;
using System.Collections.Generic;
using System.Drawing;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Threading.Tasks;
using System.Windows.Forms;
using ShareToPcReciver.Config;
using ShareToPcReciver.Models;
using ShareToPcReciver.Net;
using ShareToPcReciver.Storage;
using ShareToPcReciver.Util;

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
        private string _lastFilePath;
        private ListViewItem _activeRow;
        private const int ProgressColumnIndex = 4;

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
            _server = new TransferServer(_config, _storage);
            _server.ClientConnected += OnClientConnected;
            _server.TextReceived += OnTextReceived;
            _server.FileReceived += OnFileReceived;
            _server.FileProgressChanged += OnFileProgress;

            lvHistory.DrawColumnHeader += (s, ev) => ev.DrawDefault = true;
            lvHistory.DrawSubItem += LvHistory_DrawSubItem;

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
                        AddHistoryRow(text.ReceivedAt.ToString("HH:mm:ss"), "文本", preview, text.DeviceName, text);
                        break;
                    }
                    case ReceivedFile file:
                    {
                        string preview = $"{file.FileName} ({FormatSize(file.Size)})";
                        AddHistoryRow(file.ReceivedAt.ToString("HH:mm:ss"), "文件", preview, file.DeviceName, file);
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
                _advertiser.Start(_config.Port);
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
            Timer t = new Timer { Interval = 2000 };
            t.Tick += (s, ev) => { lblIp.Text = "本机IP：" + ip; t.Stop(); t.Dispose(); };
            t.Start();
        }

        private void BtnOpenDir_Click(object sender, EventArgs e)
        {
            _storage.OpenFolder(_config.SaveDir);
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
            this.BeginInvoke(new Action(() =>
            {
                Log.Info($"设备已连接: {info.Name} ({info.Address})");
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

            this.BeginInvoke(new Action(() =>
            {
                string preview = msg.Text.Length > 80 ? msg.Text.Substring(0, 80) + "..." : msg.Text;
                AddHistoryRow(msg.ReceivedAt.ToString("HH:mm:ss"), "文本", preview, msg.DeviceName, msg);
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
                _history.Insert(0, msg);
                if (_history.Count > 500)
                    _history.RemoveAt(_history.Count - 1);
            }
            SaveHistory();

            this.BeginInvoke(new Action(() =>
            {
                string preview = $"{msg.FileName} ({FormatSize(msg.Size)})";
                if (_activeRow != null)
                {
                    // 传输中插入的行转为正式文件行
                    _activeRow.Text = msg.ReceivedAt.ToString("HH:mm:ss");
                    _activeRow.SubItems[2].Text = preview;
                    _activeRow.Tag = msg;
                    RefreshRow(_activeRow);
                    _activeRow = null;
                }
                else
                {
                    AddHistoryRow(msg.ReceivedAt.ToString("HH:mm:ss"), "文件", preview, msg.DeviceName, msg);
                }
                PerformFileAction(msg);

                if (_config.ShowBalloon)
                {
                    _lastFilePath = msg.FilePath;
                    notifyIcon.BalloonTipTitle = "收到文件";
                    notifyIcon.BalloonTipText = preview;
                    notifyIcon.ShowBalloonTip(5000);
                }
            }));
        }

        private void OnFileProgress(FileProgressInfo p)
        {
            if (IsDisposed || !IsHandleCreated)
                return;
            this.BeginInvoke(new Action(() =>
            {
                if (p.Failed)
                {
                    if (_activeRow != null)
                    {
                        _activeRow.Tag = p;
                        RefreshRow(_activeRow);
                        _activeRow = null;
                    }
                    return;
                }
                if (_activeRow == null)
                {
                    string content = $"{p.FileName} ({FormatSize(p.Total)})";
                    _activeRow = AddHistoryRow(DateTime.Now.ToString("HH:mm:ss"), "文件", content, p.DeviceName, p);
                }
                else
                {
                    _activeRow.Tag = p;
                }
                RefreshRow(_activeRow);
            }));
        }

        private void RefreshRow(ListViewItem item)
        {
            if (item != null && item.Index >= 0)
                lvHistory.RedrawItems(item.Index, item.Index, true);
        }

        private void LvHistory_DrawSubItem(object sender, DrawListViewSubItemEventArgs e)
        {
            if (e.ColumnIndex != ProgressColumnIndex || !(e.Item.Tag is FileProgressInfo p))
            {
                e.DrawDefault = true;
                return;
            }

            e.DrawBackground();

            if (p.Failed)
            {
                TextRenderer.DrawText(e.Graphics, "失败", lvHistory.Font, e.Bounds, Color.DarkRed,
                    TextFormatFlags.HorizontalCenter | TextFormatFlags.VerticalCenter);
                return;
            }

            var rect = new Rectangle(e.Bounds.X + 3, e.Bounds.Y + 3,
                Math.Max(0, e.Bounds.Width - 6), Math.Max(0, e.Bounds.Height - 6));
            if (ProgressBarRenderer.IsSupported && rect.Width > 4 && rect.Height > 4)
            {
                ProgressBarRenderer.DrawHorizontalBar(e.Graphics, rect);
                int fillWidth = (rect.Width - 2) * p.Percent / 100;
                if (fillWidth > 0)
                {
                    var fill = new Rectangle(rect.X + 1, rect.Y + 1, fillWidth, rect.Height - 2);
                    ProgressBarRenderer.DrawHorizontalChunks(e.Graphics, fill);
                }
            }
            else
            {
                e.Graphics.FillRectangle(SystemBrushes.ControlLight, rect);
                int fillWidth = rect.Width * p.Percent / 100;
                if (fillWidth > 0)
                    e.Graphics.FillRectangle(Brushes.ForestGreen, rect.X, rect.Y, fillWidth, rect.Height);
                e.Graphics.DrawRectangle(SystemPens.ControlDark, rect);
            }

            Color textColor = e.Item.Selected ? SystemColors.HighlightText : SystemColors.ControlText;
            TextRenderer.DrawText(e.Graphics, $"{p.Percent}%", lvHistory.Font, e.Bounds, textColor,
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

        private ListViewItem AddHistoryRow(string time, string type, string content, string source, object tag)
        {
            var item = new ListViewItem(time);
            item.SubItems.Add(type);
            item.SubItems.Add(content);
            item.SubItems.Add(source);
            item.SubItems.Add(""); // 进度列（自绘，文本留空）
            item.Tag = tag;
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

        // ---- 内容到达动作 ----

        private void PerformTextAction(ReceivedText msg)
        {
            if (_config.TextAction != ArrivalAction.CopyText)
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

        // ---- 历史记录交互：双击打开 / 右键菜单 ----

        private object GetSelectedRecord()
        {
            return lvHistory.SelectedItems.Count > 0 ? lvHistory.SelectedItems[0].Tag : null;
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
            object record = GetSelectedRecord();
            if (record == null || record is FileProgressInfo)
            {
                e.Cancel = true;
                return;
            }

            bool isFile = record is ReceivedFile;
            bool fileExists = isFile
                && !string.IsNullOrEmpty(((ReceivedFile)record).FilePath)
                && File.Exists(((ReceivedFile)record).FilePath);

            menuOpenFile.Visible = isFile;
            menuOpenFile.Enabled = fileExists;
            menuOpenFolder.Visible = isFile;
            menuOpenFolder.Enabled = fileExists;
            menuCopyContent.Text = isFile ? "复制文件路径" : "复制文本";
            menuViewDetail.Visible = !isFile;
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
                Dock = DockStyle.Fill,
                Text = text.Text
            };
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