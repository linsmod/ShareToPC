package github.linsmod.sharetopc.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import github.linsmod.sharetopc.R;
import github.linsmod.sharetopc.transfer.EtpClient;

/**
 * 搜索结果适配器 — 用 ListAdapter + DiffUtil 避免全量刷新闪烁。
 * 支持底部"加载更多"状态。
 */
class EtpResultAdapter extends ListAdapter<EtpClient.EtpSearchResult, RecyclerView.ViewHolder> {

    private static final int TYPE_ITEM = 0;
    private static final int TYPE_FOOTER = 1;

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
                            && o.isFolder == n.isFolder;
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
        ivh.tvIcon.setText(r.isFolder ? "📁" : "📄");

        StringBuilder info = new StringBuilder();
        if (r.size >= 0) info.append(EtpClient.EtpSearchResult.formatSize(r.size));
        if (r.dateModified > 0) {
            if (info.length() > 0) info.append("  |  ");
            long millis = EtpClient.EtpSearchResult.filetimeToMillis(r.dateModified);
            info.append(new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                    .format(new Date(millis)));
        }
        ivh.tvInfo.setText(info.toString());

        if (r.path != null && !r.path.isEmpty()) {
            ivh.tvPath.setVisibility(View.VISIBLE);
            ivh.tvPath.setText(r.path + "\\" + r.name);
        } else {
            ivh.tvPath.setVisibility(View.GONE);
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
        final TextView tvIcon, tvName, tvInfo, tvPath;
        ItemViewHolder(@NonNull View itemView) {
            super(itemView);
            tvIcon = itemView.findViewById(R.id.tvIcon);
            tvName = itemView.findViewById(R.id.tvName);
            tvInfo = itemView.findViewById(R.id.tvInfo);
            tvPath = itemView.findViewById(R.id.tvPath);
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
