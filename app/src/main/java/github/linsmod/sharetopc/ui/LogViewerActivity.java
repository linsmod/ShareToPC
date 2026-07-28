package github.linsmod.sharetopc.ui;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import github.linsmod.sharetopc.R;

/** 查看 ShareToPC 的 logcat 日志。 */
public class LogViewerActivity extends AppCompatActivity {

    private static final String LOG_TAG = "ShareToPC";
    private TextView tvLogs;
    private ProgressBar progressLog;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_log_viewer);

        MaterialToolbar toolbar = findViewById(R.id.toolbarLogViewer);
        toolbar.setNavigationOnClickListener(v -> finish());

        tvLogs = findViewById(R.id.tvLogs);
        progressLog = findViewById(R.id.progressLog);

        findViewById(R.id.btnClearLogs).setOnClickListener(v -> clearLogs());

        loadLogs();
    }

    private void loadLogs() {
        progressLog.setVisibility(View.VISIBLE);
        tvLogs.setText("");
        executor.execute(() -> {
            StringBuilder sb = new StringBuilder();
            try {
                // logcat -d 只 dump 已缓存的日志，不阻塞等待
                Process process = Runtime.getRuntime().exec(
                        new String[]{"logcat", "-d", "-s", LOG_TAG + ":*"});
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream()));
                String line;
                int count = 0;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append('\n');
                    count++;
                }
                reader.close();
                process.waitFor();

                if (count == 0) {
                    sb.append(getString(R.string.log_empty));
                }
            } catch (Exception e) {
                sb.append("读取日志失败: ").append(e.getMessage());
            }

            String text = sb.toString();
            handler.post(() -> {
                tvLogs.setText(text);
                progressLog.setVisibility(View.GONE);
                // 滚动到底部
                tvLogs.post(() -> {
                    int scrollAmount = tvLogs.getLayout() != null
                            ? tvLogs.getLayout().getHeight() - tvLogs.getHeight()
                            : 0;
                    tvLogs.scrollTo(0, Math.max(0, scrollAmount));
                });
            });
        });
    }

    private void clearLogs() {
        progressLog.setVisibility(View.VISIBLE);
        executor.execute(() -> {
            try {
                Runtime.getRuntime().exec(new String[]{"logcat", "-c"});
            } catch (Exception ignored) {
            }
            handler.post(() -> {
                tvLogs.setText(R.string.log_empty);
                progressLog.setVisibility(View.GONE);
                Toast.makeText(this, R.string.task_cleared, Toast.LENGTH_SHORT).show();
            });
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }
}
