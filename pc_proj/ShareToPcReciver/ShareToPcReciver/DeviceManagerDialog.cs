using System;
using System.Collections.Generic;
using System.Drawing;
using System.Linq;
using System.Windows.Forms;
using ShareToPcReciver.Config;
using ShareToPcReciver.Models;
using ShareToPcReciver.Storage;

namespace ShareToPcReciver
{
    public class DeviceManagerDialog : Form
    {
        private PairStore _pairStore;
        private DeviceStore _deviceStore;
        private DataGridView dgvDevices;
        private Button btnEdit;
        private Button btnBlock;
        private Button btnDeleteAuth;
        private Button btnDeleteDevice;
        private DataGridViewTextBoxColumn colName;
        private DataGridViewTextBoxColumn colDeviceId;
        private DataGridViewTextBoxColumn colCredits;
        private DataGridViewTextBoxColumn colIssuedAt;
        private DataGridViewTextBoxColumn colExpiresAt;
        private DataGridViewTextBoxColumn colLastSeen;
        private DataGridViewTextBoxColumn colBlocked;
        private Button btnClose;

        /// <summary>
        /// 无参构造函数：仅供 WinForms 设计器使用，不要在代码中直接调用
        /// </summary>
        public DeviceManagerDialog()
        {
            InitializeComponent();
        }

        public DeviceManagerDialog(PairStore pairStore, DeviceStore deviceStore)
        {
            _pairStore = pairStore;
            _deviceStore = deviceStore;
            InitializeComponent();
            LoadDevices();
        }

        private void InitializeComponent()
        {
            this.dgvDevices = new System.Windows.Forms.DataGridView();
            this.colName = new System.Windows.Forms.DataGridViewTextBoxColumn();
            this.colDeviceId = new System.Windows.Forms.DataGridViewTextBoxColumn();
            this.colCredits = new System.Windows.Forms.DataGridViewTextBoxColumn();
            this.colIssuedAt = new System.Windows.Forms.DataGridViewTextBoxColumn();
            this.colExpiresAt = new System.Windows.Forms.DataGridViewTextBoxColumn();
            this.colLastSeen = new System.Windows.Forms.DataGridViewTextBoxColumn();
            this.colBlocked = new System.Windows.Forms.DataGridViewTextBoxColumn();
            this.btnDeleteAuth = new System.Windows.Forms.Button();
            this.btnClose = new System.Windows.Forms.Button();
            this.btnEdit = new System.Windows.Forms.Button();
            this.btnBlock = new System.Windows.Forms.Button();
            this.btnDeleteDevice = new System.Windows.Forms.Button();
            ((System.ComponentModel.ISupportInitialize)(this.dgvDevices)).BeginInit();
            this.SuspendLayout();
            // 
            // dgvDevices
            // 
            this.dgvDevices.AllowUserToAddRows = false;
            this.dgvDevices.AllowUserToDeleteRows = false;
            this.dgvDevices.AllowUserToResizeRows = false;
            this.dgvDevices.Anchor = ((System.Windows.Forms.AnchorStyles)((((System.Windows.Forms.AnchorStyles.Top | System.Windows.Forms.AnchorStyles.Bottom) 
            | System.Windows.Forms.AnchorStyles.Left) 
            | System.Windows.Forms.AnchorStyles.Right)));
            this.dgvDevices.AutoSizeColumnsMode = System.Windows.Forms.DataGridViewAutoSizeColumnsMode.Fill;
            this.dgvDevices.ColumnHeadersHeightSizeMode = System.Windows.Forms.DataGridViewColumnHeadersHeightSizeMode.AutoSize;
            this.dgvDevices.Columns.AddRange(new System.Windows.Forms.DataGridViewColumn[] {
            this.colName,
            this.colDeviceId,
            this.colCredits,
            this.colIssuedAt,
            this.colExpiresAt,
            this.colLastSeen,
            this.colBlocked});
            this.dgvDevices.Location = new System.Drawing.Point(12, 12);
            this.dgvDevices.MultiSelect = false;
            this.dgvDevices.Name = "dgvDevices";
            this.dgvDevices.ReadOnly = true;
            this.dgvDevices.RowHeadersVisible = false;
            this.dgvDevices.RowHeadersWidth = 51;
            this.dgvDevices.SelectionMode = System.Windows.Forms.DataGridViewSelectionMode.FullRowSelect;
            this.dgvDevices.Size = new System.Drawing.Size(987, 320);
            this.dgvDevices.TabIndex = 0;
            // 
            // colName
            // 
            this.colName.FillWeight = 25F;
            this.colName.HeaderText = "设备名称";
            this.colName.MinimumWidth = 100;
            this.colName.Name = "colName";
            this.colName.ReadOnly = true;
            // 
            // colDeviceId
            // 
            this.colDeviceId.FillWeight = 20F;
            this.colDeviceId.HeaderText = "设备ID";
            this.colDeviceId.MinimumWidth = 100;
            this.colDeviceId.Name = "colDeviceId";
            this.colDeviceId.ReadOnly = true;
            // 
            // colCredits
            // 
            this.colCredits.FillWeight = 10F;
            this.colCredits.HeaderText = "发送次数";
            this.colCredits.MinimumWidth = 70;
            this.colCredits.Name = "colCredits";
            this.colCredits.ReadOnly = true;
            // 
            // colIssuedAt
            // 
            this.colIssuedAt.FillWeight = 15F;
            this.colIssuedAt.HeaderText = "授权时间";
            this.colIssuedAt.MinimumWidth = 120;
            this.colIssuedAt.Name = "colIssuedAt";
            this.colIssuedAt.ReadOnly = true;
            // 
            // colExpiresAt
            // 
            this.colExpiresAt.FillWeight = 15F;
            this.colExpiresAt.HeaderText = "过期时间";
            this.colExpiresAt.MinimumWidth = 120;
            this.colExpiresAt.Name = "colExpiresAt";
            this.colExpiresAt.ReadOnly = true;
            // 
            // colLastSeen
            // 
            this.colLastSeen.FillWeight = 15F;
            this.colLastSeen.HeaderText = "上次连接";
            this.colLastSeen.MinimumWidth = 120;
            this.colLastSeen.Name = "colLastSeen";
            this.colLastSeen.ReadOnly = true;
            // 
            // colBlocked
            // 
            this.colBlocked.FillWeight = 8F;
            this.colBlocked.HeaderText = "状态";
            this.colBlocked.MinimumWidth = 60;
            this.colBlocked.Name = "colBlocked";
            this.colBlocked.ReadOnly = true;
            // 
            // btnDeleteAuth
            // 
            this.btnDeleteAuth.Anchor = ((System.Windows.Forms.AnchorStyles)((System.Windows.Forms.AnchorStyles.Bottom | System.Windows.Forms.AnchorStyles.Left)));
            this.btnDeleteAuth.Enabled = false;
            this.btnDeleteAuth.Location = new System.Drawing.Point(174, 342);
            this.btnDeleteAuth.Name = "btnDeleteAuth";
            this.btnDeleteAuth.Size = new System.Drawing.Size(100, 28);
            this.btnDeleteAuth.TabIndex = 1;
            this.btnDeleteAuth.Text = "删除授权";
            this.btnDeleteAuth.UseVisualStyleBackColor = true;
            // 
            // btnClose
            // 
            this.btnClose.Anchor = ((System.Windows.Forms.AnchorStyles)((System.Windows.Forms.AnchorStyles.Bottom | System.Windows.Forms.AnchorStyles.Right)));
            this.btnClose.DialogResult = System.Windows.Forms.DialogResult.OK;
            this.btnClose.Location = new System.Drawing.Point(924, 342);
            this.btnClose.Name = "btnClose";
            this.btnClose.Size = new System.Drawing.Size(75, 28);
            this.btnClose.TabIndex = 2;
            this.btnClose.Text = "关闭";
            this.btnClose.UseVisualStyleBackColor = true;
            // 
            // btnEdit
            // 
            this.btnEdit.Anchor = ((System.Windows.Forms.AnchorStyles)((System.Windows.Forms.AnchorStyles.Bottom | System.Windows.Forms.AnchorStyles.Left)));
            this.btnEdit.Enabled = false;
            this.btnEdit.Location = new System.Drawing.Point(12, 342);
            this.btnEdit.Name = "btnEdit";
            this.btnEdit.Size = new System.Drawing.Size(75, 28);
            this.btnEdit.TabIndex = 3;
            this.btnEdit.Text = "编辑";
            this.btnEdit.UseVisualStyleBackColor = true;
            // 
            // btnBlock
            // 
            this.btnBlock.Anchor = ((System.Windows.Forms.AnchorStyles)((System.Windows.Forms.AnchorStyles.Bottom | System.Windows.Forms.AnchorStyles.Left)));
            this.btnBlock.Enabled = false;
            this.btnBlock.Location = new System.Drawing.Point(93, 342);
            this.btnBlock.Name = "btnBlock";
            this.btnBlock.Size = new System.Drawing.Size(75, 28);
            this.btnBlock.TabIndex = 4;
            this.btnBlock.Text = "拉黑";
            this.btnBlock.UseVisualStyleBackColor = true;
            // 
            // btnDeleteDevice
            // 
            this.btnDeleteDevice.Anchor = ((System.Windows.Forms.AnchorStyles)((System.Windows.Forms.AnchorStyles.Bottom | System.Windows.Forms.AnchorStyles.Left)));
            this.btnDeleteDevice.Enabled = false;
            this.btnDeleteDevice.Location = new System.Drawing.Point(280, 342);
            this.btnDeleteDevice.Name = "btnDeleteDevice";
            this.btnDeleteDevice.Size = new System.Drawing.Size(100, 28);
            this.btnDeleteDevice.TabIndex = 5;
            this.btnDeleteDevice.Text = "删除设备";
            this.btnDeleteDevice.UseVisualStyleBackColor = true;
            // 
            // DeviceManagerDialog
            // 
            this.AcceptButton = this.btnClose;
            this.CancelButton = this.btnClose;
            this.ClientSize = new System.Drawing.Size(1011, 382);
            this.Controls.Add(this.btnClose);
            this.Controls.Add(this.btnEdit);
            this.Controls.Add(this.btnBlock);
            this.Controls.Add(this.btnDeleteAuth);
            this.Controls.Add(this.btnDeleteDevice);
            this.Controls.Add(this.dgvDevices);
            this.FormBorderStyle = System.Windows.Forms.FormBorderStyle.FixedDialog;
            this.MaximizeBox = false;
            this.MinimizeBox = false;
            this.Name = "DeviceManagerDialog";
            this.StartPosition = System.Windows.Forms.FormStartPosition.CenterParent;
            this.Text = "设备管理";
            ((System.ComponentModel.ISupportInitialize)(this.dgvDevices)).EndInit();
            this.ResumeLayout(false);

        }

        private void LoadDevices()
        {
            dgvDevices.Rows.Clear();

            var pairs = _pairStore.LoadAll();
            var devices = _deviceStore.LoadAll();

            // 为 deviceId 建立 LastSeen 查找表
            var deviceSeenMap = new Dictionary<string, DateTime>();
            foreach (var d in devices)
            {
                if (!string.IsNullOrEmpty(d.DeviceId))
                {
                    if (!deviceSeenMap.ContainsKey(d.DeviceId) || d.LastSeen > deviceSeenMap[d.DeviceId])
                        deviceSeenMap[d.DeviceId] = d.LastSeen;
                }
            }

            // 汇总所有已知设备（授权记录 + 连接记录）
            var allDeviceIds = new HashSet<string>();
            var nameMap = new Dictionary<string, string>(); // deviceId -> name

            foreach (var p in pairs)
            {
                allDeviceIds.Add(p.DeviceId);
                if (!string.IsNullOrEmpty(p.DeviceName))
                    nameMap[p.DeviceId] = p.DeviceName;
            }
            foreach (var d in devices)
            {
                if (!string.IsNullOrEmpty(d.DeviceId))
                {
                    allDeviceIds.Add(d.DeviceId);
                    if (!string.IsNullOrEmpty(d.Name) && !nameMap.ContainsKey(d.DeviceId))
                        nameMap[d.DeviceId] = d.Name;
                }
            }

            // 对于只有 IP 没有 deviceId 的设备（devices.json 中可能存在），也加入列表
            var ipOnlyDevices = devices.Where(d => string.IsNullOrEmpty(d.DeviceId)).ToList();

            foreach (var deviceId in allDeviceIds)
            {
                var pair = pairs.FirstOrDefault(p => p.DeviceId == deviceId);
                string name = nameMap.TryGetValue(deviceId, out var n) ? n : deviceId;
                string lastSeen = deviceSeenMap.TryGetValue(deviceId, out var ls) ? FormatTime(ls) : "-";

                string credits;
                string issuedAt;
                string expiresAt;
                Color rowColor;

                if (pair != null)
                {
                    bool expired = _pairStore.IsExpired(pair);

                    if (pair.Credits == -1)
                    {
                        credits = expired ? "-" : "不限次数";
                        rowColor = expired ? Color.FromArgb(255, 230, 230) : Color.FromArgb(230, 255, 230);
                    }
                    else if (pair.Credits > 0)
                    {
                        credits = pair.Credits.ToString();
                        rowColor = Color.FromArgb(255, 255, 230);
                    }
                    else
                    {
                        credits = "0";
                        rowColor = Color.FromArgb(255, 230, 230);
                    }
                    issuedAt = FormatTime(DateTimeOffset.FromUnixTimeMilliseconds(pair.IssuedAt).LocalDateTime);
                    expiresAt = pair.ExpiresAt > 0
                        ? FormatTime(DateTimeOffset.FromUnixTimeMilliseconds(pair.ExpiresAt).LocalDateTime)
                        : "永不过期";
                }
                else
                {
                    credits = "-";
                    issuedAt = "-";
                    expiresAt = "-";
                    rowColor = SystemColors.Window;
                }

                // 拉黑状态独立于授权，不依赖 PairSession
                var isBlocked = _pairStore.IsBlocked(deviceId);
                var blockedText = isBlocked ? "已拉黑" : "正常";
                if (isBlocked)
                    rowColor = Color.FromArgb(255, 200, 200); // 拉黑用红色背景

                var rowIdx = dgvDevices.Rows.Add(name, deviceId, credits, issuedAt, expiresAt, lastSeen, blockedText);
                dgvDevices.Rows[rowIdx].DefaultCellStyle.BackColor = rowColor;
                dgvDevices.Rows[rowIdx].Tag = pair;
            }

            // 添加仅有 IP 记录（无 deviceId）的设备
            foreach (var d in ipOnlyDevices)
            {
                string name = d.Name.Length > 0 ? d.Name : d.Address;
                string lastSeen = d.LastSeen != default ? FormatTime(d.LastSeen) : "-";
                var isBlocked = _pairStore.IsBlocked(d.Address);
                var rowIdx = dgvDevices.Rows.Add(name, d.Address, "-", "-", "-", lastSeen, isBlocked ? "已拉黑" : "-");
                dgvDevices.Rows[rowIdx].DefaultCellStyle.BackColor = isBlocked ? Color.FromArgb(255, 200, 200) : SystemColors.Control;
                dgvDevices.Rows[rowIdx].Tag = null;
            }
        }

        private void DgvDevices_SelectionChanged(object sender, EventArgs e)
        {
            UpdateButtonState();
        }

        private void DgvDevices_CellDoubleClick(object sender, DataGridViewCellEventArgs e)
        {
            if (e.RowIndex < 0) return;
            EditSelectedRow(dgvDevices.Rows[e.RowIndex]);
        }

        private void BtnEdit_Click(object sender, EventArgs e)
        {
            if (dgvDevices.SelectedRows.Count == 0) return;
            EditSelectedRow(dgvDevices.SelectedRows[0]);
        }

        private void EditSelectedRow(DataGridViewRow row)
        {
            var session = row.Tag as PairStore.PairSession;
            if (session == null)
            {
                // 未授权设备：创建一个新的 PairSession
                string deviceId = row.Cells["colDeviceId"].Value?.ToString() ?? "";
                string name = row.Cells["colName"].Value?.ToString() ?? deviceId;
                if (string.IsNullOrEmpty(deviceId)) return;
                session = new PairStore.PairSession
                {
                    DeviceId = deviceId,
                    DeviceName = name,
                    Credits = -1
                };
            }
            EditSession(session);
        }

        private void EditSession(PairStore.PairSession session)
        {
            var dlg = new Form();
            dlg.Text = "编辑设备 - " + session.DeviceName;
            dlg.StartPosition = FormStartPosition.CenterParent;
            dlg.Size = new Size(380, 280);
            dlg.FormBorderStyle = FormBorderStyle.FixedDialog;
            dlg.MinimizeBox = false;
            dlg.MaximizeBox = false;
            dlg.ShowInTaskbar = false;

            // 设备名称
            var lblName = new Label
            {
                Text = "设备名称:",
                Location = new Point(20, 20),
                Size = new Size(80, 25),
                TextAlign = ContentAlignment.MiddleLeft
            };
            var txtName = new TextBox
            {
                Text = session.DeviceName,
                Location = new Point(110, 20),
                Size = new Size(220, 23)
            };
            dlg.Controls.Add(lblName);
            dlg.Controls.Add(txtName);

            // 发送次数
            var lblCredits = new Label
            {
                Text = "发送次数:",
                Location = new Point(20, 55),
                Size = new Size(80, 25),
                TextAlign = ContentAlignment.MiddleLeft
            };
            var cmbCredits = new ComboBox
            {
                DropDownStyle = ComboBoxStyle.DropDownList,
                Location = new Point(110, 55),
                Size = new Size(140, 23)
            };
            var creditOptions = new ComboOption[]
            {
                new ComboOption("不限次数", -1),
                new ComboOption("1 次", 1),
                new ComboOption("3 次", 3),
                new ComboOption("5 次", 5),
                new ComboOption("10 次", 10),
                new ComboOption("20 次", 20),
                new ComboOption("50 次", 50),
                new ComboOption("100 次", 100),
            };
            cmbCredits.BindingContext = new BindingContext();
            cmbCredits.DisplayMember = "Text";
            cmbCredits.DataSource = creditOptions;
            int creditsIdx = 0;
            for (int i = 0; i < creditOptions.Length; i++)
                if (creditOptions[i].Value >= session.Credits) { creditsIdx = i; break; }
            cmbCredits.SelectedIndex = creditsIdx;
            dlg.Controls.Add(lblCredits);
            dlg.Controls.Add(cmbCredits);

            // 收到文本时的动作
            var lblTextAction = new Label
            {
                Text = "收到文本时:",
                Location = new Point(20, 90),
                Size = new Size(80, 25),
                TextAlign = ContentAlignment.MiddleLeft
            };
            var cmbTextAction = new ComboBox
            {
                DropDownStyle = ComboBoxStyle.DropDownList,
                Location = new Point(110, 90),
                Size = new Size(150, 23)
            };
            cmbTextAction.Items.AddRange(new object[] { "跟随全局设定", "无操作", "复制文本到剪贴板" });
            cmbTextAction.SelectedIndex = GetIndexFromTextAction(session.TextAction);
            dlg.Controls.Add(lblTextAction);
            dlg.Controls.Add(cmbTextAction);

            // 过期时间
            var lblExpire = new Label
            {
                Text = "过期时间:",
                Location = new Point(20, 125),
                Size = new Size(80, 25),
                TextAlign = ContentAlignment.MiddleLeft
            };
            var cmbExpire = new ComboBox
            {
                DropDownStyle = ComboBoxStyle.DropDownList,
                Location = new Point(110, 125),
                Size = new Size(150, 23)
            };
            var expireOptions = new ComboOption[]
            {
                new ComboOption("永不过期", 0),
                new ComboOption("1 天", 1),
                new ComboOption("3 天", 3),
                new ComboOption("7 天", 7),
                new ComboOption("30 天", 30),
                new ComboOption("90 天", 90),
            };
            cmbExpire.BindingContext = new BindingContext();
            cmbExpire.DisplayMember = "Text";
            cmbExpire.DataSource = expireOptions;

            // 根据当前过期时间计算默认选项
            if (session.ExpiresAt <= 0)
                cmbExpire.SelectedIndex = 0;
            else
            {
                var remainDays = (DateTimeOffset.FromUnixTimeMilliseconds(session.ExpiresAt) - DateTimeOffset.UtcNow).TotalDays;
                int bestIdx = 5; // 默认 90 天
                for (int i = 0; i < expireOptions.Length; i++)
                {
                    if (expireOptions[i].Value > 0 && remainDays <= expireOptions[i].Value + 1)
                    {
                        bestIdx = i;
                        break;
                    }
                }
                cmbExpire.SelectedIndex = bestIdx;
            }
            dlg.Controls.Add(lblExpire);
            dlg.Controls.Add(cmbExpire);

            // 按钮
            var btnSave = new Button
            {
                Text = "保存",
                DialogResult = DialogResult.OK,
                Location = new Point(110, 200),
                Size = new Size(80, 30)
            };
            var btnCancel = new Button
            {
                Text = "取消",
                DialogResult = DialogResult.Cancel,
                Location = new Point(200, 200),
                Size = new Size(80, 30)
            };
            dlg.Controls.Add(btnSave);
            dlg.Controls.Add(btnCancel);
            dlg.AcceptButton = btnSave;
            dlg.CancelButton = btnCancel;

            if (dlg.ShowDialog(this) == DialogResult.OK)
            {
                session.DeviceName = txtName.Text.Trim().Length > 0 ? txtName.Text.Trim() : session.DeviceName;
                var creditsSelected = (ComboOption)cmbCredits.SelectedItem;
                session.Credits = creditsSelected.Value;
                session.TextAction = GetTextActionFromIndex(cmbTextAction.SelectedIndex);
                var expSelected = (ComboOption)cmbExpire.SelectedItem;
                int expDays = expSelected.Value;
                session.ExpiresAt = expDays > 0
                    ? DateTimeOffset.UtcNow.AddDays(expDays).ToUnixTimeMilliseconds()
                    : 0;

                _pairStore.Save(session);
                ReloadAndReselect(session.DeviceId);
            }

            dlg.Dispose();
        }

        private void ReloadAndReselect(string deviceId)
        {
            dgvDevices.SuspendLayout();
            LoadDevices();
            foreach (DataGridViewRow row in dgvDevices.Rows)
            {
                if (row.Cells["colDeviceId"].Value?.ToString() == deviceId)
                {
                    row.Selected = true;
                    break;
                }
            }
            dgvDevices.ResumeLayout();
            // 强制刷新按钮状态
            UpdateButtonState();
        }

        private void UpdateButtonState()
        {
            if (dgvDevices.SelectedRows.Count > 0)
            {
                var tag = dgvDevices.SelectedRows[0].Tag;
                bool hasPair = tag is PairStore.PairSession;
                btnEdit.Enabled = true; // 编辑始终可用
                btnDeleteAuth.Enabled = hasPair;
                btnDeleteDevice.Enabled = true; // 删除设备记录始终可用
                btnBlock.Enabled = true; // 拉黑始终可用（设备级）
                string deviceId = dgvDevices.SelectedRows[0].Cells["colDeviceId"].Value?.ToString() ?? "";
                btnBlock.Text = _pairStore.IsBlocked(deviceId) ? "取消拉黑" : "拉黑";
            }
            else
            {
                btnEdit.Enabled = false;
                btnDeleteAuth.Enabled = false;
                btnDeleteDevice.Enabled = false;
                btnBlock.Enabled = false;
            }
        }

        private void BtnBlock_Click(object sender, EventArgs e)
        {
            if (dgvDevices.SelectedRows.Count == 0) return;
            var row = dgvDevices.SelectedRows[0];
            string deviceId = row.Cells["colDeviceId"].Value?.ToString() ?? "";
            if (string.IsNullOrEmpty(deviceId)) return;

            if (_pairStore.IsBlocked(deviceId))
                _pairStore.UnblockDevice(deviceId);
            else
                _pairStore.BlockDevice(deviceId);

            ReloadAndReselect(deviceId);
        }

        private void BtnDeleteAuth_Click(object sender, EventArgs e)
        {
            if (dgvDevices.SelectedRows.Count == 0)
                return;

            var row = dgvDevices.SelectedRows[0];
            if (!(row.Tag is PairStore.PairSession session))
                return;

            string deviceId = row.Cells["colDeviceId"].Value?.ToString() ?? "";
            string deviceName = row.Cells["colName"].Value?.ToString() ?? deviceId;

            var result = MessageBox.Show(
                "确定要撤销 \"" + deviceName + "\" 的授权吗？\n\n撤销后该设备再次发送文件时将需要重新授权。",
                "撤销授权", MessageBoxButtons.OKCancel, MessageBoxIcon.Warning);

            if (result == DialogResult.OK)
            {
                _pairStore.Delete(session.DeviceId);
                LoadDevices();
                dgvDevices.ClearSelection();
                UpdateButtonState();
            }
        }

        private void BtnDeleteDevice_Click(object sender, EventArgs e)
        {
            if (dgvDevices.SelectedRows.Count == 0)
                return;

            var row = dgvDevices.SelectedRows[0];
            string deviceId = row.Cells["colDeviceId"].Value?.ToString() ?? "";
            string deviceName = row.Cells["colName"].Value?.ToString() ?? deviceId;

            var result = MessageBox.Show(
                "确定要删除 \"" + deviceName + "\" 的设备记录吗？\n\n此操作将同时清除该设备的连接记录和授权信息。",
                "删除设备记录", MessageBoxButtons.OKCancel, MessageBoxIcon.Warning);

            if (result == DialogResult.OK)
            {
                // 同时删除授权记录和设备记录
                if (row.Tag is PairStore.PairSession session)
                    _pairStore.Delete(session.DeviceId);
                _deviceStore?.DeleteDevice(deviceId);
                LoadDevices();
                dgvDevices.ClearSelection();
                UpdateButtonState();
            }
        }

        private static string FormatTime(DateTime dt)
        {
            if (dt == default)
                return "-";
            return dt.ToString("yyyy-MM-dd HH:mm");
        }

        private static int GetIndexFromTextAction(ArrivalAction? action)
        {
            if (action == ArrivalAction.None) return 1;
            if (action == ArrivalAction.CopyText) return 2;
            return 0;
        }

        private static ArrivalAction? GetTextActionFromIndex(int index)
        {
            if (index == 1) return ArrivalAction.None;
            if (index == 2) return ArrivalAction.CopyText;
            return null;
        }

        private class ComboOption
        {
            public string Text { get; }
            public int Value { get; }
            public ComboOption(string text, int value)
            {
                Text = text;
                Value = value;
            }
        }
    }
}
