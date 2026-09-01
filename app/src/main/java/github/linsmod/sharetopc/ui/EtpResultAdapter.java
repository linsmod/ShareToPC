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
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Pattern;

import github.linsmod.sharetopc.R;
import github.linsmod.sharetopc.transfer.EtpClient;

/**
 * 搜索结果适配器 — 用 ListAdapter + DiffUtil 避免全量刷新闪烁。
 * 列表末尾支持"加载更多"footer（可加载 / 加载中 / 失败重试）。
 */
class EtpResultAdapter extends ListAdapter<EtpClient.EtpSearchResult, RecyclerView.ViewHolder> {

    private static final int TYPE_ITEM = 0;
    private static final int TYPE_FOOTER = 1;

    // 匹配 C:\Users\{username}\Desktop\ 等已知文件夹路径
    private static final Pattern PATTERN_USER_FOLDER = Pattern.compile(
            "\\\\users\\\\[^\\\\]+\\\\(desktop|documents|downloads|videos|pictures|music)(\\\\|$)",
            Pattern.CASE_INSENSITIVE);

    /**
     * 位置信息：{标签, 相对路径}。
     * 常见用户目录（桌面/文档等）时返回标签 + 该目录下的相对子路径（可直接展示）；
     * 盘符时返回盘符 + null（相对路径保持完整）；其他返回 null。
     */
    private static String[] getLocationInfo(EtpClient.EtpSearchResult r) {
        String path = r.path != null ? r.path : (r.name != null ? r.name : "");
        if (path.isEmpty()) return null;

        java.util.regex.Matcher m = PATTERN_USER_FOLDER.matcher(path);
        if (m.find()) {
            String folder = m.group(1).toLowerCase(Locale.getDefault());
            String label;
            switch (folder) {
                case "desktop":   label = "桌面"; break;
                case "documents": label = "文档"; break;
                case "downloads": label = "下载"; break;
                case "videos":    label = "视频"; break;
                case "pictures":  label = "图片"; break;
                case "music":     label = "音乐"; break;
                default:          label = null; break;
            }
            if (label != null) {
                return new String[]{label, path.substring(m.end())};
            }
        }

        // 否则显示盘符
        if (path.length() >= 2 && path.charAt(1) == ':') {
            return new String[]{path.substring(0, 2), null};
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
    private OnItemLongClickListener onItemLongClickListener;

    // footer 状态：隐藏 / 可加载更多 / 加载中 / 失败重试 / 全部加载完成
    static final int FOOTER_HIDDEN = 0;
    static final int FOOTER_LOAD_MORE = 1;
    static final int FOOTER_LOADING = 2;
    static final int FOOTER_ERROR = 3;
    static final int FOOTER_ALL_LOADED = 4;

    private int footerState = FOOTER_HIDDEN;
    private int loadedCount = 0; // FOOTER_ALL_LOADED 时显示"已加载全部 N 条"

    interface OnItemClickListener {
        void onItemClick(EtpClient.EtpSearchResult item);
    }

    interface OnItemLongClickListener {
        boolean onItemLongClick(EtpClient.EtpSearchResult item);
    }

    /**
     * 是否显示"位置标签 + 完整路径"信息行（第二行）。
     * 搜索结果页为 true；按目录浏览时当前目录已由面包屑展示，整行只保留日期。
     */
    private final boolean showPathInfo;

    EtpResultAdapter(OnItemClickListener listener) {
        this(listener, true);
    }

    EtpResultAdapter(OnItemClickListener listener, boolean showPathInfo) {
        super(DIFF);
        this.onItemClickListener = listener;
        this.showPathInfo = showPathInfo;
    }

    void setItemLongClickListener(OnItemLongClickListener listener) {
        this.onItemLongClickListener = listener;
    }

    /** 更新 footer 状态（隐藏/可加载/加载中/失败重试/全部加载完成）。 */
    void setFooterState(int state, int loadedCount) {
        if (this.footerState == state && this.loadedCount == loadedCount) return;
        boolean wasShow = this.footerState != FOOTER_HIDDEN;
        this.footerState = state;
        this.loadedCount = loadedCount;
        // footer 恒在列表末尾：出现→insert，消失→remove，状态变化→update
        int pos = getCurrentList().size();
        if (state != FOOTER_HIDDEN && !wasShow) {
            notifyItemInserted(pos);
        } else if (state == FOOTER_HIDDEN && wasShow) {
            notifyItemRemoved(pos);
        } else {
            notifyItemChanged(pos);
        }
    }

    @Override
    public int getItemViewType(int position) {
        if (footerState != FOOTER_HIDDEN && position == getCurrentList().size()) {
            return TYPE_FOOTER;
        }
        return TYPE_ITEM;
    }

    @Override
    public int getItemCount() {
        int count = getCurrentList().size();
        if (footerState != FOOTER_HIDDEN) count++;
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
            bindLoadMore((FooterViewHolder) holder);
            return;
        }

        ItemViewHolder ivh = (ItemViewHolder) holder;
        EtpClient.EtpSearchResult r = getItem(position);
        ivh.tvName.setText(r.name);
        ivh.ivIcon.setImageResource(r.isFolder
                ? R.drawable.ic_folder : FileTypeIcon.forName(r.name));

        // 文件大小
        if (r.size >= 0) {
            ivh.tvFileSize.setVisibility(View.VISIBLE);
            ivh.tvFileSize.setText(EtpClient.EtpSearchResult.formatSize(r.size));
        } else {
            ivh.tvFileSize.setVisibility(View.GONE);
        }

        // 位置标签：匹配 presets paths 获取标签，显示 C:\Users\{user}\Documents 等，或盘符
        String[] loc = getLocationInfo(r);
        String locationLabel = loc != null ? loc[0] : null;

        // 路径文本：常见目录显示其下的相对子路径；其他显示完整路径
        String pathText = null;
        if (loc != null && loc[1] != null) {
            pathText = loc[1].isEmpty() ? null : loc[1] + "\\" + r.name;
        } else if (r.path != null && !r.path.isEmpty()) {
            pathText = r.path + "\\" + r.name;
        }

        // 第二行：位置标签 + 路径 + 日期；浏览模式下只保留日期
        boolean hasLocation = locationLabel != null;
        boolean hasPath = pathText != null;
        boolean hasDate = r.dateModified > 0;
        if (showPathInfo) {
            if (hasLocation || hasPath || hasDate) {
                ivh.layoutPathRow.setVisibility(View.VISIBLE);

                if (hasLocation) {
                    ivh.tvLocation.setText(locationLabel);
                    android.content.Context ctx = ivh.itemView.getContext();
                    int color = CategoryColors.color(ctx, locationLabel);
                    if (color != 0) {
                        ivh.tvLocation.setBackground(CategoryColors.tagBackground(ctx, color));
                    } else {
                        ivh.tvLocation.setBackgroundResource(R.drawable.bg_location_tag);
                    }
                    ivh.tvLocation.setVisibility(View.VISIBLE);
                } else {
                    ivh.tvLocation.setVisibility(View.GONE);
                }

                if (hasPath) {
                    ivh.tvPath.setVisibility(View.VISIBLE);
                    ivh.tvPath.setText(pathText);
                } else {
                    // 无路径时保留 weight 占位，让日期贴右对齐
                    ivh.tvPath.setVisibility(View.VISIBLE);
                    ivh.tvPath.setText("");
                }
            } else {
                ivh.layoutPathRow.setVisibility(View.GONE);
            }
        } else {
            ivh.tvLocation.setVisibility(View.GONE);
            ivh.tvPath.setVisibility(View.VISIBLE);
            ivh.tvPath.setText("");
            ivh.layoutPathRow.setVisibility(hasDate ? View.VISIBLE : View.GONE);
        }

        if (hasDate) {
            ivh.tvFileDate.setVisibility(View.VISIBLE);
            long millis = EtpClient.EtpSearchResult.filetimeToMillis(r.dateModified);
            ivh.tvFileDate.setText(formatFileDate(millis));
        } else {
            ivh.tvFileDate.setVisibility(View.GONE);
        }

        ivh.itemView.setOnClickListener(v -> {
            if (onItemClickListener != null) onItemClickListener.onItemClick(r);
        });
        ivh.itemView.setOnLongClickListener(v -> {
            if (onItemLongClickListener != null) return onItemLongClickListener.onItemLongClick(r);
            return false;
        });
    }

    /** 当年记录显示"MM-dd HH:mm"，往年记录显示"yyyy-MM-dd"。 */
    private static String formatFileDate(long millis) {
        Calendar cal = Calendar.getInstance();
        int currentYear = cal.get(Calendar.YEAR);
        cal.setTimeInMillis(millis);
        String pattern = cal.get(Calendar.YEAR) == currentYear
                ? "MM-dd HH:mm" : "yyyy-MM-dd";
        return new SimpleDateFormat(pattern, Locale.getDefault()).format(new Date(millis));
    }

    /** 绑定加载更多 footer：四态切换（可加载 / 加载中 / 失败重试 / 全部加载完成）。 */
    private void bindLoadMore(FooterViewHolder fh) {
        fh.progress.setVisibility(View.GONE);
        fh.tvMessage.setVisibility(View.VISIBLE);
        fh.itemView.setOnClickListener(null);
        switch (footerState) {
            case FOOTER_LOAD_MORE:
                fh.tvMessage.setText(R.string.etp_load_more);
                fh.itemView.setOnClickListener(v -> {
                    if (onLoadMoreListener != null) onLoadMoreListener.onLoadMore();
                });
                break;
            case FOOTER_LOADING:
                fh.progress.setVisibility(View.VISIBLE);
                fh.tvMessage.setText(R.string.etp_load_more_loading);
                break;
            case FOOTER_ERROR:
                fh.tvMessage.setText(R.string.etp_load_more_error);
                fh.itemView.setOnClickListener(v -> {
                    if (onLoadMoreListener != null) onLoadMoreListener.onLoadMore();
                });
                break;
            case FOOTER_ALL_LOADED:
                fh.tvMessage.setText(fh.itemView.getContext()
                        .getString(R.string.etp_load_more_all_loaded, loadedCount));
                break;
            default:
                break; // FOOTER_HIDDEN 不应被绑定
        }
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
