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
            _server = new TransferServer(_config, _storage);
            _server.ClientConnected += OnClientConnected;
            _server.TextReceived += OnTextReceived;
            _server.FileReceived += OnFileReceived;
            _server.FileProgressChanged += OnFileProgress;

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
                        if ((file.Status == "failed" || file.Status == "cancelled") && file.ReceivedBytes > 0)
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
            Timer t = new Timer { Interval = 2000 };
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

            this.BeginInvoke(new Action(() =>
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
            this.BeginInvoke(new Action(() =>
            {
                if (p.Failed)
                {
                    if (_activeRow != null)
                    {
                        _activeRow.Tag = p;
                        RefreshRow(_activeRow);
                        // 写入历史，重启后可恢复
                        var rf = MakeFailedRecord(p, "failed");
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
                string label = p.Failed ? $"{p.Percent}% 失败"
                             : p.Cancelled ? $"{p.Percent}% 已取消"
                             : $"{p.Percent}%";
                Color clr = p.Failed ? Color.DarkRed : p.Cancelled ? Color.Gray : SystemColors.ControlText;
                DrawProgressCell(e, p.Percent, label, clr);
                return;
            }

            // 重启后加载的历史记录：ReceivedFile
            if (e.Item.Tag is ReceivedFile rf)
            {
                if (rf.Status == "failed" || rf.Status == "cancelled")
                {
                    int pct = rf.TotalBytes > 0 ? (int)(rf.ReceivedBytes * 100 / rf.TotalBytes) : 0;
                    Color clr = rf.Status == "failed" ? Color.DarkRed : Color.Gray;
                    string label = $"{pct}% {(rf.Status == "failed" ? "失败" : "已取消")}";
                    DrawProgressCell(e, pct, label, clr);
                }
                else
                {
                    e.DrawBackground();
                    TextRenderer.DrawText(e.Graphics, "完成", lvHistory.Font, e.Bounds,
                        e.Item.Selected ? SystemColors.HighlightText : Color.Green,
                        TextFormatFlags.HorizontalCenter | TextFormatFlags.VerticalCenter);
                }
                return;
            }

            e.DrawDefault = true;
        }

        private void DrawProgressCell(DrawListViewSubItemEventArgs e, int percent,
                                       string label, Color textColor)
        {
            e.DrawBackground();

            var rect = new Rectangle(e.Bounds.X + 3, e.Bounds.Y + 3,
                Math.Max(0, e.Bounds.Width - 6), Math.Max(0, e.Bounds.Height - 6));
            if (ProgressBarRenderer.IsSupported && rect.Width > 4 && rect.Height > 4)
            {
                ProgressBarRenderer.DrawHorizontalBar(e.Graphics, rect);
                int fillWidth = (rect.Width - 2) * percent / 100;
                if (fillWidth > 0)
                {
                    var fill = new Rectangle(rect.X + 1, rect.Y + 1, fillWidth, rect.Height - 2);
                    ProgressBarRenderer.DrawHorizontalChunks(e.Graphics, fill);
                }
            }
            else
            {
                e.Graphics.FillRectangle(SystemBrushes.ControlLight, rect);
                int fillWidth = rect.Width * percent / 100;
                if (fillWidth > 0)
                    e.Graphics.FillRectangle(Brushes.ForestGreen, rect.X, rect.Y, fillWidth, rect.Height);
                e.Graphics.DrawRectangle(SystemPens.ControlDark, rect);
            }

            Color finalColor = e.Item.Selected ? SystemColors.HighlightText : textColor;
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

        /// <summary>按 jobId 分组，若无 jobId 则按设备名分组。活跃分组排在最前。</summary>
        private ListViewGroup GetOrCreateGroup(object tag, string deviceName)
        {
            string jobId = null;
            if (tag is ReceivedText rt) jobId = rt.JobId;
            else if (tag is ReceivedFile rf) jobId = rf.JobId;
            else if (tag is FileProgressInfo pi) jobId = pi.JobId;

            string key = !string.IsNullOrEmpty(jobId) ? "job:" + jobId : "dev:" + (deviceName ?? "(未知)");
            string header = deviceName ?? "(未知)";

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

        // ---- 历史记录交互：双击打开 / 键盘操作 / 右键菜单 ----

        private List<object> GetSelectedRecords()
        {
            var result = new List<object>();
            foreach (ListViewItem item in lvHistory.SelectedItems)
            {
                if (item.Tag is ReceivedFile || item.Tag is ReceivedText)
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
            bool isActiveTransfer = lvHistory.SelectedItems.Count > 0
                && ReferenceEquals(lvHistory.SelectedItems[0], _activeRow);
            bool fileExists = isFile
                && !string.IsNullOrEmpty(((ReceivedFile)record).FilePath)
                && File.Exists(((ReceivedFile)record).FilePath);

            // 单条时显示操作类菜单
            menuOpenFile.Visible = singleSelect && isFile;
            menuOpenFile.Enabled = fileExists;
            menuOpenFolder.Visible = singleSelect && isFile;
            menuOpenFolder.Enabled = fileExists;
            menuCopyContent.Text = singleSelect && isFile ? "复制文件路径" : "复制文本";
            menuCopyContent.Visible = singleSelect;
            menuViewDetail.Visible = singleSelect && !isFile;

            // 取消传输菜单：仅对正在进行传输的行显示
            menuCancelTransfer.Visible = singleSelect && isActiveTransfer;
            menuSeparator2.Visible = menuCancelTransfer.Visible;

            // 删除菜单始终可见
            menuSeparator1.Visible = true;
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
            _server.DisconnectAllSessions();
            UpdateStatus("已取消当前传输", Color.Black);
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
                    if (rf.Status == "failed" || rf.Status == "cancelled")
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