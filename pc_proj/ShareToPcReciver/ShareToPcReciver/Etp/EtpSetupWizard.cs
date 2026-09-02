using System;
using System.Collections.Generic;
using System.Drawing;
using System.Threading.Tasks;
using System.Windows.Forms;

namespace ShareToPcReciver.Etp
{
    /// <summary>
    /// “启用 ETP 远程访问”配置向导。
    /// 上部为步骤清单（checklist，逐项显示完成/进行/失败），底部在结束后给出结果汇总。
    /// </summary>
    public sealed class EtpSetupWizard : Form
    {
        private readonly Label _lblHead;
        private readonly ListView _lvSteps;
        private readonly Button _btnOk;
        private readonly Button _btnCancel;
        private readonly EtpBootstrap _bootstrap = new EtpBootstrap();
        private EtpEnableResult _result;
        private bool _ran;

        private const bool AutoDownload = true;

        public EtpSetupWizard()
        {
            Text = "启用 ETP 远程访问";
            FormBorderStyle = FormBorderStyle.FixedDialog;
            StartPosition = FormStartPosition.CenterParent;
            MaximizeBox = false;
            MinimizeBox = false;
            ClientSize = new Size(520, 404);
            this.DialogResult = DialogResult.Cancel;

            _lblHead = new Label
            {
                Location = new Point(16, 12),
                Size = new Size(488, 18),
                AutoSize = false,
                Text = "将检测并配置 Everything 的 ETP Server，让手机可远程搜索本机文件。"
            };

            _lvSteps = new ListView
            {
                Location = new Point(16, 38),
                Size = new Size(488, 200),
                View = View.Details,
                FullRowSelect = true,
                HeaderStyle = ColumnHeaderStyle.Nonclickable,
                HideSelection = false
            };
            _lvSteps.Columns.Add(new ColumnHeader { Text = "", Width = 34, TextAlign = HorizontalAlignment.Center });
            _lvSteps.Columns.Add(new ColumnHeader { Text = "步骤", Width = 158 });
            _lvSteps.Columns.Add(new ColumnHeader { Text = "说明", Width = 296 });

            _btnOk = new Button
            {
                Location = new Point(404, 366),
                Size = new Size(96, 26),
                Text = "继续",
            };
            _btnOk.Click += async (s, e) => await OnOkClickAsync();

            _btnCancel = new Button
            {
                Text = "关闭",
                Location = new Point(290, 366),
                Size = new Size(96, 26),
                DialogResult = DialogResult.Cancel
            };

            Controls.Add(_lblHead);
            Controls.Add(_lvSteps);
            Controls.Add(_btnOk);
            Controls.Add(_btnCancel);
            AcceptButton = _btnOk;
            CancelButton = _btnCancel;

            BuildInitialSteps();
            _bootstrap.StepChanged += OnStepChanged;

            this.Shown += EtpSetupWizard_Shown;
        }

        private async void EtpSetupWizard_Shown(object sender, EventArgs e)
        {
            await this.OnOkClickAsync();
        }

        /// <summary>初始 checklist：全部待办。</summary>
        private void BuildInitialSteps()
        {
            _lvSteps.Items.Clear();
            for (int i = 0; i < EtpBootstrap._steps.Count; i++)
            {
                var item = new ListViewItem("○") { Tag = i, ForeColor = Color.Gray };
                item.SubItems.Add(EtpBootstrap._steps[i].Title);
                item.SubItems.Add("待执行");
                _lvSteps.Items.Add(item);
            }
        }

        private void OnStepChanged(EtpStep step, int done)
        {
            if (InvokeRequired)
            {
                BeginInvoke((Action)(() => OnStepChanged(step, done)));
                return;
            }
            if (step.Index < 0 || step.Index >= _lvSteps.Items.Count) return;
            var item = _lvSteps.Items[step.Index];
            switch (step.Status)
            {
                case EtpStepStatus.Running:
                    item.SubItems[0].Text = "▶";
                    item.SubItems[0].ForeColor = Color.DodgerBlue;
                    break;
                case EtpStepStatus.Done:
                    item.SubItems[0].Text = "✔";
                    item.SubItems[0].ForeColor = Color.SeaGreen;
                    item.SubItems[0].Font = new Font(Font, FontStyle.Bold);
                    break;
                case EtpStepStatus.Failed:
                    item.SubItems[0].Text = "✘";
                    item.SubItems[0].ForeColor = Color.IndianRed;
                    break;
                default:
                    item.SubItems[0].Text = "○";
                    item.SubItems[0].ForeColor = Color.Gray;
                    break;
            }
            item.SubItems[2].Text = step.Detail ?? "";
        }

        private async Task OnOkClickAsync()
        {
            if (!_ran)
            {
                _ran = true;
                _btnOk.Enabled = false;
                _btnCancel.Enabled = false;
                try
                {
                    _result = await Task.Run(() => _bootstrap.Run(AutoDownload));
                }
                catch (Exception ex)
                {
                    _result = new EtpEnableResult(new List<EtpStep>())
                    {
                        Success = false,
                        Summary = "配置遇到意外错误：\n" + ex.Message
                    };
                }
                RenderFinal();
            }
            else
            {
                // 重试
                _ran = false;
                BuildInitialSteps();
                _btnOk.Text = "开始";
                _btnOk.Enabled = true;
                _btnCancel.Enabled = true;
            }
        }

        private void RenderFinal()
        {
            if (_result != null && _result.Success)
            {
                _btnOk.Text = "完成";
                _btnOk.Enabled = true;
                _btnOk.DialogResult = DialogResult.OK;
                this.DialogResult = DialogResult.OK;
            }
            else
            {
                _btnOk.Text = "重试";
                _btnOk.Enabled = true;
                _btnCancel.Enabled = true;
            }
        }
    }
}