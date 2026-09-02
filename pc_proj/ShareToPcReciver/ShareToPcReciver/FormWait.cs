using System;
using System.Collections.Generic;
using System.ComponentModel;
using System.Data;
using System.Drawing;
using System.Linq;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using System.Windows.Forms;

namespace ShareToPcReciver
{
    public partial class FormWait : Form
    {
        private Task waitFor;
        private TimeSpan timeout;

        public FormWait(Task waitFor, TimeSpan timeout)
        {
            InitializeComponent();
            this.waitFor = waitFor;
            this.Shown += FormWait_Shown;
            this.timeout = timeout;
            this.StartPosition = FormStartPosition.CenterParent;

        }

        private void FormWait_Shown(object sender, EventArgs e)
        {
            Task.Run(() => {
                int ms = 0;
                if (!waitFor.Wait(1000)) {
                    ms += 1000;
                    try
                    {
                        this.Invoke(new Action(() =>
                        {
                            this.Text = timeout.Subtract(TimeSpan.FromMilliseconds(ms)).TotalSeconds + "s";
                        }));
                    }
                    catch { }
                    return;
                }
                Task.Delay(ms < 1000?1000:0).ContinueWith((x) => {
                    try
                    {
                        this.Invoke(new Action(() =>
                        {
                            this.Close();
                        }));
                    }
                    catch { }
                });
            });
        }

        private void button1_Click(object sender, EventArgs e)
        {
            this.DialogResult = DialogResult.Cancel;
            this.Close();
        }

        private void button2_Click(object sender, EventArgs e)
        {
            this.DialogResult = DialogResult.OK; 
            this.Close();
        }
    }
}
