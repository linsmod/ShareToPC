using System;
using System.Drawing;
using System.Threading;
using System.Windows.Forms;
using ShareToPcReciver.Config;
using ShareToPcReciver.Net;
using static ShareToPcReciver.Net.TransferServer;

namespace ShareToPcReciver
{
    internal class AuthDialog
    {
        private static readonly object[] ExpireOptions = new object[]
        {
            new { Text = "永不过期", Days = 0 },
            new { Text = "1 天", Days = 1 },
            new { Text = "3 天", Days = 3 },
            new { Text = "7 天", Days = 7 },
            new { Text = "30 天", Days = 30 },
            new { Text = "90 天", Days = 90 },
        };

        /// <summary>
        /// 弹出授权对话框，用户操作后返回 AuthResult。
        /// </summary>
        public static AuthResult ShowDialog(AuthInfo req, CancellationToken cancellation)
        {
            var result = new AuthResult { authJobInfo = req };

            using var dlg = new Form
            {
                Text = "文件传输请求",
                StartPosition = FormStartPosition.CenterScreen,
                Size = new Size(440, 260),
                FormBorderStyle = FormBorderStyle.FixedDialog,
                MinimizeBox = false,
                MaximizeBox = false,
                ShowInTaskbar = false,
                TopMost = true
            };

            var label = new Label
            {
                Text = req.Summary,
                Location = new Point(20, 20),
                Size = new Size(380, 70),
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
            cmbExpire.BindingContext = new BindingContext();
            cmbExpire.DisplayMember = "Text";
            cmbExpire.DataSource = ExpireOptions;
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
            cmbTextAction.Items.AddRange(new object[] { "跟随全局设定", "无操作", "复制文本到剪贴板" });
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
                Size = new Size(75, 30)
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

            var dr = dlg.ShowDialog();
            timer.Stop();

            switch (dr)
            {
                case DialogResult.Abort:
                    result.IsBlocked = true;
                    result.Status = "REJECTED";
                    break;
                case DialogResult.OK:
                    {
                        dynamic selected = cmbExpire.SelectedItem;
                        int days = selected.Days;
                        if (days > 0)
                            result.ExpiresAt = DateTimeOffset.UtcNow.AddDays(days).ToUnixTimeMilliseconds();
                        result.Credits = -1;
                        result.MaxItemsPerJob = int.MaxValue;
                        result.MaxBytesPerJob = long.MaxValue;
                        result.TextAction = GetTextAction(cmbTextAction.SelectedIndex);
                        result.Status = "ACCEPTED";
                    }
                    break;
                case DialogResult.Yes:
                    result.Status = "ACCEPTED";
                    result.Credits = 1;
                    result.TextAction = GetTextAction(cmbTextAction.SelectedIndex);
                    break;
                default:
                    result.Status = "REJECTED";
                    break;
            }

            return result;
        }

        private static ArrivalAction? GetTextAction(int index)
        {
            return index switch
            {
                1 => ArrivalAction.None,
                2 => ArrivalAction.CopyText,
                _ => null
            };
        }
    }
}
