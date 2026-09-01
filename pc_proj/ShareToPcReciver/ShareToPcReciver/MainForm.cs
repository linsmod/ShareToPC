using ShareToPcReciver.Config;
using ShareToPcReciver.Models;
using ShareToPcReciver.Net;
using ShareToPcReciver.Storage;
using ShareToPcReciver.Util;
using System;
using System.Collections.Generic;
using System.Drawing;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading;
using System.Threading.Tasks;
using System.Windows.Forms;
using Microsoft.Win32;
using static ShareToPcReciver.Net.TransferServer;
using static System.Windows.Forms.VisualStyles.VisualStyleElement.StartPanel;

namespace ShareToPcReciver
{
    public partial class MainForm : Form
    {
        private readonly AppConfig _config;
        private ReceiveStorage _storage;
        private readonly ServiceAdvertiser _advertiser;
        private readonly TransferServer _server;
        private readonly List<ReceivedFile> _history = new List<ReceivedFile>();
        private readonly object _historyLock = new object();
        private readonly HistoryStore _historyStore = new HistoryStore();
        private readonly DeviceStore _deviceStore = new DeviceStore();
        private readonly PairStore _pairStore = new PairStore();
        private string _lastFilePath;
        private ListViewItem _activeRow;
        private const int ProgressColumnIndex = 6;
        private System.Windows.Forms.Timer _searchDebounceTimer;
        private readonly Dictionary<string, int> _jobIdIndexMap = new Dictionary<string, int>();
        private int _nextJobIdIndex = 0;
        private static readonly Color EvenRowColor = Color.White;
        private static readonly Color OddRowColor = Color.FromArgb(240, 240, 245);

        /// <summary>网络/IP 变化去抖定时器（WinForms 定时器，Tick 在 UI 线程触发）。</summary>
        private System.Windows.Forms.Timer _netChangeTimer;
        private bool _netChangePending;
        private string _advertisedIp;
        private readonly bool _startMinimized = Program.StartMinimized();

        public MainForm()
        {
            InitializeComponent();

            string iconPath = Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "app_icon.ico");
            if (File.Exists(iconPath))
            {
                var icon = new System.Drawing.Icon(iconPath);
                this.Icon = icon;
                notifyIcon.Icon = icon;
            }

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
            _server.HistoryStore = _historyStore;
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

            _advertisedIp = GetLocalIpAddress();
            _netChangeTimer = new System.Windows.Forms.Timer { Interval = 1500 };
            _netChangeTimer.Tick += (s, ev) => HandleNetworkChanged();
            // 睡眠恢复 / IP 变化时刷新 mDNS 广播与 IP 显示，不动已绑定的 TCP 监听
            NetworkChange.NetworkAddressChanged += OnNetworkAddressChanged;
            SystemEvents.PowerModeChanged += OnPowerModeChanged;

            // 让搜索框边框贴合文字高度，避免底部多出背景空隙显得边框粗
            pnlSearch.Height = txtSearch.PreferredHeight + 2;

            _searchDebounceTimer = new System.Windows.Forms.Timer { Interval = 300 };
            _searchDebounceTimer.Tick += (s, ev) =>
            {
                _searchDebounceTimer.Stop();
                ApplySearchFilter();
            };

            if (_startMinimized)
            {
                // 仅托盘启动：暂时从任务栏消失，点托盘图标后再恢复
                this.ShowInTaskbar = false;
                this.WindowState = FormWindowState.Minimized;
            }
        }

        private void MainForm_Load(object sender, EventArgs e)
        {
            LoadHistory();
            StartServices();
            // 开机自启：以最小化方式进托盘，不显示主窗口
            if (_startMinimized)
            {
                this.WindowState = FormWindowState.Minimized;
                this.Hide();
            }
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
                if (item is ReceivedFile file)
                {
                    if (file.IsText)
                    {
                        string preview = file.Text.Length > 80 ? file.Text.Substring(0, 80) + "..." : file.Text;
                        AddHistoryRow(file.ReceivedAt.ToString("HH:mm:ss"), "文本", preview, "", file.Text.Length + " 字符", file.DeviceName, file);
                    }
                    else
                    {
                        string ext = GetFileExtension(file.FileName);
                        string sizeText;
                        if ((file.Status == "cancelled" || file.Status == "incomplete") && file.ReceivedBytes > 0)
                            sizeText = $"{FormatSize(file.ReceivedBytes)} / {FormatSize(file.Size)}";
                        else
                            sizeText = FormatSize(file.Size);
                        AddHistoryRow(file.ReceivedAt.ToString("HH:mm:ss"), "文件", file.FileName, ext, sizeText, file.DeviceName, file);
                    }
                }
            }
        }

        private void SaveHistory()
        {
            List<ReceivedFile> snapshot;
            lock (_historyLock)
            {
                snapshot = new List<ReceivedFile>(_history);
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
            if (!string.IsNullOrWhiteSpace(_config.ListenIp) && IPAddress.TryParse(_config.ListenIp, out var pinned))
                return pinned.ToString();
            return LocalNetwork.GetPrimaryIp();
        }

        private void OnNetworkAddressChanged(object sender, EventArgs e)
        {
            ScheduleNetworkRefresh();
        }

        private void OnPowerModeChanged(object sender, PowerModeChangedEventArgs e)
        {
            if (e.Mode == PowerModes.Resume)
                ScheduleNetworkRefresh();
        }

        /// <summary>IP/网络变化事件可能一次触发多次，先合并去抖（1.5s）再在 UI 线程统一处理。</summary>
        private void ScheduleNetworkRefresh()
        {
            if (_netChangePending || _netChangeTimer == null) return;
            _netChangePending = true;
            if (IsDisposed || !IsHandleCreated) { _netChangePending = false; return; }
            try
            {
                BeginInvoke(new Action(() => _netChangeTimer.Start()));
            }
            catch
            {
                _netChangePending = false;
            }
        }

        /// <summary>睡眠恢复 / IP 变化后：重启 mDNS 广播使手机端发现新 IP，并刷新 IP 显示。TCP 监听绑定不变更。</summary>
        private void HandleNetworkChanged()
        {
            _netChangeTimer.Stop();
            _netChangePending = false;
            try
            {
                string ip = GetLocalIpAddress();
                bool ipChanged = !string.Equals(ip, _advertisedIp, StringComparison.Ordinal);
                _advertisedIp = ip;

                _advertiser.Stop();
                _advertiser.Start(_config.Port, _config.DeviceId);
                UpdateIpLabel();
                Log.Info($"Network changed, re-advertised mDNS (IP={ip}, addressChanged={ipChanged})");
            }
            catch (Exception ex)
            {
                Log.Error("Failed to refresh mDNS after network change", ex);
            }
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
                bool needRestart = _config.Port != dlg.Port
                    || _config.ListenIp != dlg.SelectedIp
                    || _config.SaveDir != dlg.SaveDir;
                _config.Port = dlg.Port;
                _config.ListenIp = dlg.SelectedIp;
                _config.SaveDir = dlg.SaveDir;
                _config.TextAction = dlg.TextAction;
                _config.FileAction = dlg.FileAction;
                _config.ShowBalloon = dlg.ShowBalloon;
                _config.RunAtStartup = dlg.RunAtStartup;
                _config.PullEnabled = dlg.PullEnabled;
                _config.PullAllowedExtensions = dlg.PullAllowedExtensions;
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
            // 模态弹窗（如设置页）打开时主窗体被禁用，需把弹窗带到前台
            foreach (Form f in Application.OpenForms)
            {
                if (f.Visible && f != this)
                {
                    f.Show();
                    f.WindowState = FormWindowState.Normal;
                    f.BringToFront();
                    f.Activate();
                    return;
                }
            }
            this.ShowInTaskbar = true;
            this.Show();
            this.WindowState = FormWindowState.Normal;
            this.BringToFront();
            this.Activate();
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
                // 取消网络变化监听，避免退出后仍有回调
                NetworkChange.NetworkAddressChanged -= OnNetworkAddressChanged;
                SystemEvents.PowerModeChanged -= OnPowerModeChanged;
                if (_netChangeTimer != null)
                {
                    _netChangeTimer.Stop();
                    _netChangeTimer.Dispose();
                    _netChangeTimer = null;
                }
                _netChangePending = false;

                if (_searchDebounceTimer != null)
                {
                    _searchDebounceTimer.Stop();
                    _searchDebounceTimer.Dispose();
                    _searchDebounceTimer = null;
                }

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
                var dr = AuthDialog.ShowDialog(req, cancellation);

                if (dr.IsBlocked)
                {
                    _pairStore.BlockDevice(req.DeviceId);
                    Util.Log.Info($"Device {req.DeviceName} has been blocked");
                }

                if (dr.Status == "REJECTED")
                {
                    result.Status = "REJECTED";
                }
                else
                {
                    result.ExpiresAt = dr.ExpiresAt;
                    result.Credits = dr.Credits;
                    result.MaxItemsPerJob = dr.MaxItemsPerJob;
                    result.MaxBytesPerJob = dr.MaxBytesPerJob;
                    result.TextAction = dr.TextAction;
                    result.Status = "ACCEPTED";
                }
                tcs.SetResult(result);
            }));
        }

        private void OnTextReceived(ReceivedFile msg)
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
                _lastFilePath = msg.FilePath;
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

        public static int TEXT_OBJECT = 1;
        public static int FILE_OBJECT = 2;
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
                if (msg.ObjectType == TEXT_OBJECT)
                {
                    var content = File.ReadAllText(msg.FilePath);
                    string preview = content.Length > 80 ? content.Substring(0, 80) + "..." : content;
                    AddHistoryRow(msg.ReceivedAt.ToString("HH:mm:ss"), "文本", preview, "", content.Length + " 字符", msg.DeviceName, msg);
                    PerformTextAction(msg);
                }
                else { 
                    PerformFileAction(msg);
                    if (_config.ShowBalloon)
                    {
                        _lastFilePath = msg.FilePath;
                        notifyIcon.BalloonTipTitle = "收到文件";
                        notifyIcon.BalloonTipText = $"{msg.FileName} ({FormatSize(msg.Size)})";
                        notifyIcon.ShowBalloonTip(5000);
                    }
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
                        var rf = MakeFailedRecord(p, "cancelled");
                        lock (_historyLock) { ReplaceHistoryEntry(rf); }
                        SaveHistory();
                        _activeRow = null;
                    }
                    else
                    {
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

                // 持久化传输进度到历史，重启后可恢复为 incomplete 状态
                var incomplete = new ReceivedFile
                {
                    FileName = p.FileName,
                    Size = p.Total,
                    DeviceName = p.DeviceName,
                    Time = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(),
                    Status = "incomplete",
                    TotalBytes = p.Total,
                    ReceivedBytes = p.Received,
                    TransferId = p.TransferId ?? "",
                    JobId = p.JobId ?? "",
                    ObjectType = 2
                };
                lock (_historyLock) { ReplaceHistoryEntry(incomplete); }
                SaveHistory();
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
                JobId = p.JobId ?? "",
                ObjectType = 2
            };
        }

        private void RefreshRow(ListViewItem item)
        {
            if (item != null && item.Index >= 0)
                lvHistory.RedrawItems(item.Index, item.Index, true);
        }

        /// <summary>按搜索框内容过滤历史列表。空关键字显示全部（最新 200 条），否则仅显示匹配项。</summary>
        private void ApplySearchFilter()
        {
            if (IsDisposed || !IsHandleCreated) return;

            bool matchCase = ToggleOn(btnSearchCase);
            bool wholeWord = ToggleOn(btnSearchWord);
            bool useRegex = ToggleOn(btnSearchRegex);
            UpdateSearchBorder();

            string q = txtSearch.Text.Trim();
            bool filtering = q.Length > 0;
            if (filtering && useRegex && !IsValidRegex(q)) return;

            List<object> source;
            lock (_historyLock) { source = new List<object>(_history); }

            var entries = new List<object>(source.Count);
            foreach (var e in source)
            {
                if (filtering && !MatchesQuery(e, q, matchCase, wholeWord, useRegex)) continue;
                entries.Add(e);
                if (!filtering && entries.Count >= 200) break;
            }

            // 记住传输中的行，重建后按 TransferId 恢复引用
            string activeTid = (_activeRow?.Tag as FileProgressInfo)?.TransferId;

            _jobIdIndexMap.Clear();
            _nextJobIdIndex = 0;

            lvHistory.BeginUpdate();
            try
            {
                lvHistory.Items.Clear();
                lvHistory.Groups.Clear();
                foreach (var e in entries)
                {
                    var item = CreateHistoryRow(e);
                    if (item != null) lvHistory.Items.Add(item);
                }
            }
            finally
            {
                lvHistory.EndUpdate();
            }

            _activeRow = activeTid != null ? FindRowByTransferId(activeTid) : null;
            if (_activeRow != null) RefreshRow(_activeRow);
        }

        private void TxtSearch_TextChanged(object sender, EventArgs e)
        {
            UpdateSearchBorder();
            _searchDebounceTimer.Stop();
            bool useRegex = ToggleOn(btnSearchRegex);
            bool invalid = useRegex && !string.IsNullOrEmpty(txtSearch.Text) && !IsValidRegex(txtSearch.Text);
            if (invalid) return;
            _searchDebounceTimer.Start();
        }

        private void TxtSearch_KeyDown(object sender, KeyEventArgs e)
        {
            if (e.KeyCode == Keys.Escape)
            {
                txtSearch.Text = "";
                e.Handled = true;
            }
        }

        private void BtnSearchCase_Click(object sender, EventArgs e) => ToggleSearchOption(btnSearchCase);
        private void BtnSearchWord_Click(object sender, EventArgs e) => ToggleSearchOption(btnSearchWord);
        private void BtnSearchRegex_Click(object sender, EventArgs e) => ToggleSearchOption(btnSearchRegex);

        /// <summary>VSCode 风格选项按钮：切换开关状态并高亮。</summary>
        private void ToggleSearchOption(Button btn)
        {
            bool on = !ToggleOn(btn);
            btn.Tag = on;
            btn.BackColor = on ? Color.FromArgb(0, 122, 204) : SystemColors.ButtonFace;
            btn.ForeColor = on ? Color.White : SystemColors.ControlText;
            _searchDebounceTimer.Stop();
            if (txtSearch.Text.Trim().Length > 0)
                ApplySearchFilter();
        }

        private static bool ToggleOn(Button btn)
        {
            return btn.Tag is bool b && b;
        }

        /// <summary>正则开启且表达式非法时，把搜索框边框染红提示；否则恢复默认边框色。</summary>
        private void UpdateSearchBorder()
        {
            bool useRegex = ToggleOn(btnSearchRegex);
            bool invalid = useRegex && !string.IsNullOrEmpty(txtSearch.Text) && !IsValidRegex(txtSearch.Text);
            pnlSearch.BackColor = invalid ? Color.FromArgb(230, 70, 70) : SystemColors.ControlDark;
        }

        private static bool IsValidRegex(string pattern)
        {
            if (string.IsNullOrEmpty(pattern)) return true;
            try
            {
                new Regex(pattern);
                return true;
            }
            catch (ArgumentException)
            {
                return false;
            }
        }

        /// <summary>判断历史记录是否命中搜索关键字（名称、来源设备、文本内容）。</summary>
        private static bool MatchesQuery(object entry, string q, bool matchCase, bool wholeWord, bool useRegex)
        {
            if (entry is ReceivedFile rf)
            {
                if (rf.IsText)
                    return TextMatches(rf.Text, q, matchCase, wholeWord, useRegex)
                        || TextMatches(rf.DeviceName, q, matchCase, wholeWord, useRegex);
                return TextMatches(rf.FileName, q, matchCase, wholeWord, useRegex)
                    || TextMatches(rf.DeviceName, q, matchCase, wholeWord, useRegex);
            }
            if (entry is FileProgressInfo pi)
            {
                return TextMatches(pi.FileName, q, matchCase, wholeWord, useRegex)
                    || TextMatches(pi.DeviceName, q, matchCase, wholeWord, useRegex);
            }
            return false;
        }

        /// <summary>按大小写/整词/正则选项判断文本是否命中关键字。非法正则视为不匹配。</summary>
        private static bool TextMatches(string text, string q, bool matchCase, bool wholeWord, bool useRegex)
        {
            if (string.IsNullOrEmpty(text) || string.IsNullOrEmpty(q)) return false;
            var opts = matchCase ? RegexOptions.None : RegexOptions.IgnoreCase;
            try
            {
                if (useRegex)
                    return Regex.IsMatch(text, q, opts);
                if (wholeWord)
                    return Regex.IsMatch(text, @"\b" + Regex.Escape(q) + @"\b", opts);
                return text.IndexOf(q, matchCase ? StringComparison.Ordinal : StringComparison.OrdinalIgnoreCase) >= 0;
            }
            catch (ArgumentException)
            {
                return false;
            }
        }

        /// <summary>按历史记录对象构建一行（用于重建列表），返回 null 表示未知类型。</summary>
        private ListViewItem CreateHistoryRow(object tag)
        {
            if (tag is ReceivedFile file)
            {
                if (file.IsText)
                {
                    string preview = file.Text.Length > 80 ? file.Text.Substring(0, 80) + "..." : file.Text;
                    var item = new ListViewItem(file.ReceivedAt.ToString("HH:mm:ss"));
                    item.SubItems.Add("文本");
                    item.SubItems.Add(preview);
                    item.SubItems.Add("");
                    item.SubItems.Add(file.Text.Length + " 字符");
                    item.SubItems.Add(file.DeviceName);
                    item.SubItems.Add("");
                    item.Tag = tag;
                    item.Group = GetOrCreateGroup(tag, file.DeviceName);
                    ApplyOddStyle(item, tag);
                    return item;
                }
                else
                {
                    string ext = GetFileExtension(file.FileName);
                    string sizeText = (file.Status == "cancelled" || file.Status == "incomplete") && file.ReceivedBytes > 0
                        ? $"{FormatSize(file.ReceivedBytes)} / {FormatSize(file.Size)}"
                        : FormatSize(file.Size);
                    var item = new ListViewItem(file.ReceivedAt.ToString("HH:mm:ss"));
                    item.SubItems.Add("文件");
                    item.SubItems.Add(file.FileName);
                    item.SubItems.Add(ext);
                    item.SubItems.Add(sizeText);
                    item.SubItems.Add(file.DeviceName);
                    item.SubItems.Add("");
                    item.Tag = tag;
                    item.Group = GetOrCreateGroup(tag, file.DeviceName);
                    ApplyOddStyle(item, tag);
                    return item;
                }
            }
            if (tag is FileProgressInfo pi)
            {
                var item = new ListViewItem(DateTime.Now.ToString("HH:mm:ss"));
                item.SubItems.Add("文件");
                item.SubItems.Add(pi.FileName);
                item.SubItems.Add(GetFileExtension(pi.FileName));
                item.SubItems.Add(FormatSize(pi.Total));
                item.SubItems.Add(pi.DeviceName);
                item.SubItems.Add("");
                item.Tag = tag;
                item.Group = GetOrCreateGroup(tag, pi.DeviceName);
                ApplyOddStyle(item, tag);
                return item;
            }
            return null;
        }

        private void LvHistory_DrawSubItem(object sender, DrawListViewSubItemEventArgs e)
        {
            bool selected = e.Item.Selected;
            Color bgColor = selected ? SystemColors.Highlight : e.Item.BackColor;
            Color fgColor = selected ? SystemColors.HighlightText : SystemColors.ControlText;

            using (var bgBrush = new SolidBrush(bgColor))
                e.Graphics.FillRectangle(bgBrush, e.Bounds);

            // 进度列：自绘进度条
            if (e.ColumnIndex == ProgressColumnIndex)
            {
                if (e.Item.Tag is FileProgressInfo p)
                {
                    DrawProgressCell(e, p.Percent, $"{p.Percent}%", fgColor);
                    return;
                }
                if (e.Item.Tag is ReceivedFile rf && !rf.IsText)
                {
                    if (rf.Status == "cancelled")
                    {
                        int pct = rf.TotalBytes > 0 ? (int)(rf.ReceivedBytes * 100 / rf.TotalBytes) : 0;
                        DrawProgressCell(e, pct, $"{pct}%", Color.Gray);
                    }
                    else if (rf.Status == "incomplete")
                    {
                        int pct = rf.TotalBytes > 0 ? (int)(rf.ReceivedBytes * 100 / rf.TotalBytes) : 0;
                        DrawProgressCell(e, pct, $"{pct}%", Color.Orange);
                    }
                    else
                    {
                        TextRenderer.DrawText(e.Graphics, "完成", lvHistory.Font, e.Bounds, selected ? SystemColors.HighlightText : Color.Green,
                            TextFormatFlags.Left | TextFormatFlags.VerticalCenter);
                    }
                    return;
                }
            }

            // 普通列：绘制文字
            var padding = new Padding(4, 0, 4, 0);
            var textRect = new Rectangle(e.Bounds.X + padding.Left, e.Bounds.Y,
                e.Bounds.Width - padding.Left - padding.Right, e.Bounds.Height);
            TextRenderer.DrawText(e.Graphics, e.SubItem.Text, lvHistory.Font, textRect, fgColor,
                TextFormatFlags.Left | TextFormatFlags.VerticalCenter | TextFormatFlags.EndEllipsis);

            // 名称列：已完成但文件已删除的记录画红色删除线
            if (e.ColumnIndex == 2 && e.Item.Tag is ReceivedFile file)
            {
                bool deleted = file.Status == "success"
                    && !file.IsText
                    && (string.IsNullOrEmpty(file.FilePath) || !File.Exists(file.FilePath));
                if (deleted)
                {
                    int y = e.Bounds.Top + e.Bounds.Height / 2;
                    using (var pen = new Pen(Color.Red, 1))
                        e.Graphics.DrawLine(pen, textRect.Left, y, textRect.Right, y);
                }
            }
        }

        private void DrawProgressCell(DrawListViewSubItemEventArgs e, int percent,
                                       string label, Color textColor)
        {
            bool selected = e.Item.Selected;
            var cell = e.Bounds;

            var barRect = new Rectangle(cell.X + 3, cell.Y + 3,
                Math.Max(0, cell.Width - 6), Math.Max(0, cell.Height - 6));

            if (ProgressBarRenderer.IsSupported && !selected && barRect.Width > 4 && barRect.Height > 4)
            {
                ProgressBarRenderer.DrawHorizontalBar(e.Graphics, barRect);
                int fillWidth = (barRect.Width - 2) * percent / 100;
                if (fillWidth > 0)
                {
                    var fill = new Rectangle(barRect.X + 1, barRect.Y + 1, fillWidth, barRect.Height - 2);
                    ProgressBarRenderer.DrawHorizontalChunks(e.Graphics, fill);
                }
            }
            else if (barRect.Width > 4 && barRect.Height > 4)
            {
                Color trackColor = selected ? SystemColors.Highlight : SystemColors.ControlLight;
                Color fillColor = selected ? SystemColors.HighlightText : Color.ForestGreen;
                Color borderColor = selected ? SystemColors.HighlightText : SystemColors.ControlDark;

                using (var fillBrush = new SolidBrush(fillColor))
                {
                    int fillWidth = selected
                        ? (barRect.Width - 2) * percent / 100
                        : barRect.Width * percent / 100;
                    if (fillWidth > 0)
                        e.Graphics.FillRectangle(fillBrush,
                            selected ? barRect.X + 1 : barRect.X,
                            selected ? barRect.Y + 1 : barRect.Y,
                            fillWidth,
                            selected ? barRect.Height - 2 : barRect.Height);
                }
                using (var pen = new Pen(borderColor))
                    e.Graphics.DrawRectangle(pen, barRect);
            }

            Color finalColor = selected ? SystemColors.HighlightText : textColor;
            TextRenderer.DrawText(e.Graphics, label, lvHistory.Font, cell, finalColor,
                TextFormatFlags.Left | TextFormatFlags.VerticalCenter);
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
            ApplyOddStyle(item, tag);
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
            if (tag is ReceivedFile rf) return rf.Time;
            return DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
        }

        /// <summary>按天分组（今天/昨天/日期），不同 JOB ID 使用交替行底色。</summary>
        private ListViewGroup GetOrCreateGroup(object tag, string deviceName)
        {
            string jobId = null;
            if (tag is ReceivedFile rf) jobId = rf.JobId;
            else if (tag is FileProgressInfo pi) jobId = pi.JobId;

            var time = GetTagTime(tag);
            var date = DateTimeOffset.FromUnixTimeMilliseconds(time).LocalDateTime.Date;
            var today = DateTime.Today;

            string dateKey;
            string header;
            if (date == today)
            {
                dateKey = "day:today";
                header = "今天";
            }
            else if (date == today.AddDays(-1))
            {
                dateKey = "day:yesterday";
                header = "昨天";
            }
            else
            {
                dateKey = $"day:{date:yyyy-MM-dd}";
                header = date.ToString("M月d日");
            }

            foreach (ListViewGroup g in lvHistory.Groups)
            {
                if (g.Name == dateKey)
                {
                    if (lvHistory.Groups[0] != g)
                    {
                        lvHistory.Groups.Remove(g);
                        lvHistory.Groups.Insert(0, g);
                    }
                    return g;
                }
            }
            var group = new ListViewGroup(dateKey, header);
            lvHistory.Groups.Insert(0, group);
            return group;
        }

        /// <summary>为 ListViewItem 设置基于 JOB ID 的交替行底色。</summary>
        private void ApplyOddStyle(ListViewItem item, object tag)
        {
            string jobId = null;
            if (tag is ReceivedFile rf) jobId = rf.JobId;
            else if (tag is FileProgressInfo pi) jobId = pi.JobId;

            if (string.IsNullOrEmpty(jobId))
            {
                item.BackColor = EvenRowColor;
                return;
            }
            if (!_jobIdIndexMap.TryGetValue(jobId, out int idx))
            {
                idx = _nextJobIdIndex++;
                _jobIdIndexMap[jobId] = idx;
            }
            item.BackColor = (idx % 2 == 0) ? EvenRowColor : OddRowColor;
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
            }
            return null;
        }

        /// <summary>将新记录写入 _history，同时移除同 TransferId 的旧条目，避免重复</summary>
        private void ReplaceHistoryEntry(ReceivedFile newEntry)
        {
            string tid = newEntry.TransferId;

            if (!string.IsNullOrEmpty(tid))
                _history.RemoveAll(r => r.TransferId == tid);

            _history.Insert(0, newEntry);
        }

        /// <summary>仅移除历史中同 TransferId 的条目（不插入新条目）</summary>
        private void RemoveHistoryByTransferId(string transferId)
        {
            if (string.IsNullOrEmpty(transferId))
                return;
            _history.RemoveAll(r => r.TransferId == transferId);
        }

        // ---- 内容到达动作 ----

        private void PerformTextAction(ReceivedFile msg)
        {
            // 优先使用设备级设定，null = 跟随全局设定
            var pairSession = _pairStore.LoadAll().FirstOrDefault(s => s.DeviceName == msg.DeviceName);
            var action = pairSession?.TextAction ?? _config.TextAction;

            if (action != ArrivalAction.CopyText)
                return;
            try {
                Clipboard.SetText(msg.Text);
                if (_config.ShowBalloon)
                {
                    notifyIcon.BalloonTipTitle = "SendToPC接收器";
                    notifyIcon.BalloonTipText = "收到文本，可以粘贴";
                    notifyIcon.ShowBalloonTip(3000);
                }
            }
            catch (Exception ex) { 
                Log.Error("Copy text failed", ex);
            }
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
                if (item.Tag is ReceivedFile || item.Tag is FileProgressInfo)
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
                case ReceivedFile file when file.IsText:
                    ShowTextDetail(file);
                    break;
                case ReceivedFile file:
                    OpenReceivedFile(file);
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
            bool isFile = record is ReceivedFile rf && !rf.IsText;
            bool isText = record is ReceivedFile rf2 && rf2.IsText;
            bool isActiveTransfer = record is FileProgressInfo;
            bool fileExists = isFile
                && !string.IsNullOrEmpty(((ReceivedFile)record).FilePath)
                && File.Exists(((ReceivedFile)record).FilePath);
            bool textFileExists = isText
                && !string.IsNullOrEmpty(((ReceivedFile)record).FilePath)
                && File.Exists(((ReceivedFile)record).FilePath);

            // 单条时显示操作类菜单（传输中不适用）
            menuOpenFile.Visible = singleSelect && isFile;
            menuOpenFile.Enabled = fileExists;
            menuOpenFolder.Visible = singleSelect && (isFile || (isText && textFileExists));
            menuOpenFolder.Enabled = fileExists || textFileExists;
            menuCopyContent.Text = singleSelect && isFile ? "复制文件路径" : "复制文本";
            menuCopyContent.Visible = singleSelect && !isActiveTransfer;
            menuViewDetail.Visible = singleSelect && isText && !isActiveTransfer;

            // 取消/继续传输菜单
            bool isCancelledTransfer = isActiveTransfer && ((FileProgressInfo)record).Cancelled;
            menuCancelTransfer.Visible = singleSelect && isActiveTransfer && !isCancelledTransfer;
            menuContinueTransfer.Visible = singleSelect && isCancelledTransfer;

            // 分割线：上方有可见项时才显示
            bool hasActionAbove = menuOpenFile.Visible || menuOpenFolder.Visible
                || menuCopyContent.Visible || menuViewDetail.Visible;
            menuSeparator1.Visible = hasActionAbove && (menuCancelTransfer.Visible || menuContinueTransfer.Visible);
            menuSeparator2.Visible = menuCancelTransfer.Visible || menuContinueTransfer.Visible;

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
            switch (GetSelectedRecord())
            {
                case ReceivedFile file when !string.IsNullOrEmpty(file.FilePath):
                    _storage.OpenFolder(file.FilePath);
                    break;
            }
        }

        private void MenuCopyContent_Click(object sender, EventArgs e)
        {
            try
            {
                switch (GetSelectedRecord())
                {
                    case ReceivedFile file when file.IsText:
                        Clipboard.SetText(file.Text);
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
            if (GetSelectedRecord() is ReceivedFile text && text.IsText)
                ShowTextDetail(text);
        }

        private void MenuCancelTransfer_Click(object sender, EventArgs e)
        {
            // 通过 HB 通道通知 Android 暂停传输，而非硬断连接
            if (GetSelectedRecord() is FileProgressInfo pi && !string.IsNullOrEmpty(pi.JobId))
            {
                _server.MarkJobCanceled(pi.JobId);
                _server.PauseJob(pi.DeviceName, pi.JobId);
            }
            UpdateStatus("已暂停当前传输", Color.Black);
        }

        private void MenuContinueTransfer_Click(object sender, EventArgs e)
        {
            // 通过 HB 通道通知 Android 恢复传输
            if (GetSelectedRecord() is FileProgressInfo pi && !string.IsNullOrEmpty(pi.JobId))
            {
                _server.ClearJobCanceled(pi.JobId);
                _server.ResumeJob(pi.DeviceName, pi.JobId);
            }
            UpdateStatus("已发送恢复指令", Color.Black);
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
                if (item.Tag is ReceivedFile)
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
                    if (rf.Status == "cancelled" || rf.Status == "incomplete")
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
                    if (item.Tag is ReceivedFile rf1)
                        _history.Remove(rf1);
                }

                lvHistory.Items.Remove(item);
            }

            SaveHistory();

            string doneMsg = deleteFiles
                ? $"已删除 {selectedItems.Count} 条记录，{deletedFiles} 个文件"
                : $"已删除 {selectedItems.Count} 条记录";
            UpdateStatus(doneMsg, Color.Black);
        }

        private static readonly HashSet<string> ExecutableExtensions = new HashSet<string>(StringComparer.OrdinalIgnoreCase)
        {
            ".exe", ".bat", ".cmd", ".com", ".msi", ".scr", ".pif", ".vbs", ".js", ".ws", ".wsf", ".ps1"
        };

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
                string ext = Path.GetExtension(file.FilePath);
                if (!string.IsNullOrEmpty(ext) && ExecutableExtensions.Contains(ext))
                {
                    _storage.OpenFolder(file.FilePath);
                    return;
                }
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

        private void ShowTextDetail(ReceivedFile text)
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