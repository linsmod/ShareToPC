package github.linsmod.sharetopc.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Pattern;

import github.linsmod.sharetopc.R;
import github.linsmod.sharetopc.transfer.EtpClient;

/**
 * 搜索结果适配器 — 用 ListAdapter + DiffUtil 避免全量刷新闪烁。
 * 支持底部"加载更多"状态。
 */
class EtpResultAdapter extends ListAdapter<EtpClient.EtpSearchResult, RecyclerView.ViewHolder> {

    private static final int TYPE_ITEM = 0;
    private static final int TYPE_FOOTER = 1;

    // 匹配 C:\Users\{username}\Desktop\ 等已知文件夹路径
    private static final Pattern PATTERN_USER_FOLDER = Pattern.compile(
            "\\\\users\\\\[^\\\\]+\\\\(desktop|documents|downloads|videos|pictures|music)(\\\\|$)",
            Pattern.CASE_INSENSITIVE);

    private static String getLocationLabel(EtpClient.EtpSearchResult r) {
        String path = r.path != null ? r.path : (r.name != null ? r.name : "");
        if (path.isEmpty()) return null;

        java.util.regex.Matcher m = PATTERN_USER_FOLDER.matcher(path);
        if (m.find()) {
            String folder = m.group(1).toLowerCase(Locale.getDefault());
            switch (folder) {
                case "desktop":  return "桌面";
                case "documents": return "文档";
                case "downloads": return "下载";
                case "videos":   return "视频";
                case "pictures": return "图片";
                case "music":    return "音乐";
            }
        }

        // 否则显示盘符
        if (path.length() >= 2 && path.charAt(1) == ':') {
            return path.substring(0, 2);
        }
        return null;
    }

    private static final DiffUtil.ItemCallback<EtpClient.EtpSearchResult> DIFF =
            new DiffUtil.ItemCallback<EtpClient.EtpSearchResult>() {
                @Override
                public boolean areItemsTheSame(@NonNull EtpClient.EtpSearchResult o,
                                               @NonNull EtpClient.EtpSearchResult n) {
                    return o.fullPath().equals(n.fullPath());
                }

                @Override
                public boolean areContentsTheSame(@NonNull EtpClient.EtpSearchResult o,
                                                  @NonNull EtpClient.EtpSearchResult n) {
                    return o.size == n.size && o.dateModified == n.dateModified
                            && o.isFolder == n.isFolder
                            && (o.path == null ? n.path == null : o.path.equals(n.path));
                }
            };

    private OnItemClickListener onItemClickListener;
    private boolean showFooter;       // 是否显示"加载更多"底部
    private boolean footerError;      // 加载失败状态

    interface OnItemClickListener {
        void onItemClick(EtpClient.EtpSearchResult item);
    }

    EtpResultAdapter(OnItemClickListener listener) {
        super(DIFF);
        this.onItemClickListener = listener;
    }

    void setShowFooter(boolean show) {
        if (this.showFooter != show) {
            this.showFooter = show;
            notifyItemChanged(getItemCount()); // footer 位置变化
        }
    }

    void setFooterError(boolean error) {
        this.footerError = error;
        if (showFooter) {
            notifyItemChanged(getItemCount());
        }
    }

    @Override
    public int getItemViewType(int position) {
        if (showFooter && position == getCurrentList().size()) return TYPE_FOOTER;
        return TYPE_ITEM;
    }

    @Override
    public int getItemCount() {
        int count = getCurrentList().size();
        if (showFooter) count++;
        return count;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (viewType == TYPE_FOOTER) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_etp_load_more, parent, false);
            return new FooterViewHolder(v);
        }
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_etp_result, parent, false);
        return new ItemViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        if (holder instanceof FooterViewHolder) {
            FooterViewHolder fh = (FooterViewHolder) holder;
            if (footerError) {
                fh.progress.setVisibility(View.GONE);
                fh.tvMessage.setVisibility(View.VISIBLE);
                fh.tvMessage.setText("加载失败，点击重试");
                fh.itemView.setOnClickListener(v -> {
                    if (onLoadMoreListener != null) onLoadMoreListener.onLoadMore();
                });
            } else {
                fh.progress.setVisibility(View.VISIBLE);
                fh.tvMessage.setVisibility(View.GONE);
                fh.itemView.setOnClickListener(null);
            }
            return;
        }

        ItemViewHolder ivh = (ItemViewHolder) holder;
        EtpClient.EtpSearchResult r = getItem(position);
        ivh.tvName.setText(r.name);
        ivh.ivIcon.setImageResource(r.isFolder ? R.drawable.ic_folder : R.drawable.ic_file);

        // 文件大小
        if (r.size >= 0) {
            ivh.tvFileSize.setVisibility(View.VISIBLE);
            ivh.tvFileSize.setText(EtpClient.EtpSearchResult.formatSize(r.size));
        } else {
            ivh.tvFileSize.setVisibility(View.GONE);
        }

        // 位置标签：匹配 presets paths 获取标签，显示 C:\Users\{user}\Documents 等，或盘符
        String locationLabel = getLocationLabel(r);

        // 第二行：位置标签 + 路径 + 日期
        boolean hasLocation = locationLabel != null;
        boolean hasPath = r.path != null && !r.path.isEmpty();
        boolean hasDate = r.dateModified > 0;
        if (hasLocation || hasPath || hasDate) {
            ivh.layoutPathRow.setVisibility(View.VISIBLE);

            if (hasLocation) {
                ivh.tvLocation.setText(locationLabel);
                ivh.tvLocation.setVisibility(View.VISIBLE);
            } else {
                ivh.tvLocation.setVisibility(View.GONE);
            }

            if (hasPath) {
                ivh.tvPath.setVisibility(View.VISIBLE);
                ivh.tvPath.setText(r.path + "\\" + r.name);
            } else {
                ivh.tvPath.setVisibility(View.GONE);
            }
        } else {
            ivh.layoutPathRow.setVisibility(View.GONE);
        }

        if (hasDate) {
            ivh.tvFileDate.setVisibility(View.VISIBLE);
            long millis = EtpClient.EtpSearchResult.filetimeToMillis(r.dateModified);
            ivh.tvFileDate.setText(new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                    .format(new Date(millis)));
        } else {
            ivh.tvFileDate.setVisibility(View.GONE);
        }

        ivh.itemView.setOnClickListener(v -> {
            if (onItemClickListener != null) onItemClickListener.onItemClick(r);
        });
    }

    // ---- LoadMore 回调 ----

    private OnLoadMoreListener onLoadMoreListener;

    interface OnLoadMoreListener {
        void onLoadMore();
    }

    void setOnLoadMoreListener(OnLoadMoreListener listener) {
        this.onLoadMoreListener = listener;
    }

    // ---- ViewHolder ----

    static class ItemViewHolder extends RecyclerView.ViewHolder {
        final ImageView ivIcon;
        final TextView tvName, tvLocation, tvFileSize, tvPath, tvFileDate;
        final LinearLayout layoutPathRow;
        ItemViewHolder(@NonNull View itemView) {
            super(itemView);
            ivIcon = itemView.findViewById(R.id.ivIcon);
            tvName = itemView.findViewById(R.id.tvName);
            tvLocation = itemView.findViewById(R.id.tvLocation);
            tvFileSize = itemView.findViewById(R.id.tvFileSize);
            tvPath = itemView.findViewById(R.id.tvPath);
            tvFileDate = itemView.findViewById(R.id.tvFileDate);
            layoutPathRow = itemView.findViewById(R.id.layoutPathRow);
        }
    }

    static class FooterViewHolder extends RecyclerView.ViewHolder {
        final ProgressBar progress;
        final TextView tvMessage;
        FooterViewHolder(@NonNull View itemView) {
            super(itemView);
            progress = itemView.findViewById(R.id.progressLoadMore);
            tvMessage = itemView.findViewById(R.id.tvLoadMoreMsg);
        }
    }
}
