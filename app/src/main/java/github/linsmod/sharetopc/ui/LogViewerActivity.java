package github.linsmod.sharetopc.ui;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import com.google.android.material.appbar.MaterialToolbar;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import github.linsmod.sharetopc.R;
import github.linsmod.sharetopc.util.LogX;

/** 查看 ShareToPC 的本地日志文件，实时追加新日志。 */
public class LogViewerActivity extends AppCompatActivity implements LogX.OnLogListener {

    private TextView tvLogs;
    private ProgressBar progressLog;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean initialLoadDone;

    private File getLogFile() {
        return new File(getFilesDir(), "share_to_pc.log");
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_log_viewer);

        MaterialToolbar toolbar = findViewById(R.id.toolbarLogViewer);
        toolbar.setNavigationOnClickListener(v -> finish());

        tvLogs = findViewById(R.id.tvLogs);
        tvLogs.setTypeface(getResources().getFont(R.font.jetbrains_mono));
        progressLog = findViewById(R.id.progressLog);

        findViewById(R.id.btnShareLogs).setOnClickListener(v -> shareLogs());
        findViewById(R.id.btnClearLogs).setOnClickListener(v -> clearLogs());

        loadLogs();
        LogX.addListener(this);
    }

    /** LogX.OnLogListener —— 新日志追加到末尾。 */
    @Override
    public void onNewLine(String line) {
        if (isFinishing() || isDestroyed()) return;
        tvLogs.append(line + '\n');
        scrollToBottom();
    }

    private void loadLogs() {
        progressLog.setVisibility(View.VISIBLE);
        tvLogs.setText("");
        executor.execute(() -> {
            StringBuilder sb = new StringBuilder();
            File logFile = getLogFile();
            try {
                if (!logFile.exists()) {
                    sb.append(getString(R.string.log_empty));
                } else {
                    BufferedReader reader = new BufferedReader(new FileReader(logFile));
                    String line;
                    int count = 0;
                    while ((line = reader.readLine()) != null) {
                        sb.append(line).append('\n');
                        count++;
                    }
                    reader.close();
                    if (count == 0) {
                        sb.append(getString(R.string.log_empty));
                    }
                }
            } catch (Exception e) {
                sb.append("读取日志失败: ").append(e.getMessage());
            }

            String text = sb.toString();
            handler.post(() -> {
                tvLogs.setText(text);
                initialLoadDone = true;
                progressLog.setVisibility(View.GONE);
                scrollToBottom();
            });
        });
    }

    private void clearLogs() {
        progressLog.setVisibility(View.VISIBLE);
        executor.execute(() -> {
            try {
                File logFile = getLogFile();
                if (logFile.exists()) {
                    new FileWriter(logFile, false).close();
                }
            } catch (Exception ignored) {
            }
            handler.post(() -> {
                tvLogs.setText(R.string.log_empty);
                progressLog.setVisibility(View.GONE);
                Toast.makeText(this, R.string.task_cleared, Toast.LENGTH_SHORT).show();
            });
        });
    }

    private void shareLogs() {
        progressLog.setVisibility(View.VISIBLE);
        executor.execute(() -> {
            try {
                File src = getLogFile();
                if (!src.exists() || src.length() == 0) {
                    handler.post(() -> {
                        progressLog.setVisibility(View.GONE);
                        Toast.makeText(this, R.string.log_empty, Toast.LENGTH_SHORT).show();
                    });
                    return;
                }
                // 复制到缓存目录（FileProvider 只授权 cache-path）
                String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
                File cacheDir = new File(getCacheDir(), "shared_logs");
                cacheDir.mkdirs();
                File dst = new File(cacheDir, "share_to_pc_" + ts + ".log");
                Files.copy(src.toPath(), dst.toPath(), StandardCopyOption.REPLACE_EXISTING);

                Uri uri = FileProvider.getUriForFile(this,
                        getPackageName() + ".fileprovider", dst);
                Intent intent = new Intent(Intent.ACTION_SEND);
                intent.setType("text/plain");
                intent.putExtra(Intent.EXTRA_STREAM, uri);
                intent.putExtra(Intent.EXTRA_SUBJECT, "ShareToPC 运行日志 " + ts);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

                handler.post(() -> {
                    progressLog.setVisibility(View.GONE);
                    startActivity(Intent.createChooser(intent, getString(R.string.log_share)));
                });
            } catch (IOException e) {
                handler.post(() -> {
                    progressLog.setVisibility(View.GONE);
                    Toast.makeText(this, "分享失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void scrollToBottom() {
        tvLogs.post(() -> {
            int scrollAmount = tvLogs.getLayout() != null
                    ? tvLogs.getLayout().getHeight() - tvLogs.getHeight()
                    : 0;
            tvLogs.scrollTo(0, Math.max(0, scrollAmount));
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        LogX.removeListener(this);
        executor.shutdownNow();
    }
}
