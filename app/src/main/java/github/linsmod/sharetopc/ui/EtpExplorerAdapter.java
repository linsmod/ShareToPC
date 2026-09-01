package github.linsmod.sharetopc.ui;

import android.content.Context;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import github.linsmod.sharetopc.R;
import github.linsmod.sharetopc.transfer.EtpClient;

/**
 * 文件浏览分类列表适配器：分类 header + 该分类下的文件行。
 * 文件行复用 item_etp_result 布局（与搜索结果页一致）。
 */
class EtpExplorerAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int TYPE_HEADER = 0;
    private static final int TYPE_ITEM = 1;

    // 搜索命中高亮背景色（半透明黄）
    private static final int HIGHLIGHT_COLOR = 0x66FFEB3B;

    // 匹配 C:\Users\{username}\Desktop\ 等已知文件夹路径
    private static final Pattern PATTERN_USER_FOLDER = Pattern.compile(
            "\\\\users\\\\[^\\\\]+\\\\(desktop|documents|downloads|videos|pictures|music)(\\\\|$)",
            Pattern.CASE_INSENSITIVE);

    interface OnItemClickListener {
        void onItemClick(EtpClient.EtpSearchResult item);
    }

    interface OnItemLongClickListener {
        boolean onItemLongClick(EtpClient.EtpSearchResult item);
    }

    private final OnItemClickListener onItemClickListener;
    private OnItemLongClickListener onItemLongClickListener;

    // 本地搜索命中词（用于高亮），null/空表示不高亮
    private List<String> highlightTerms;

    private final List<Row> rows = new ArrayList<>();
    private final List<Integer> headerPositions = new ArrayList<>();

    static class Row {
        final boolean header;
        final EtpExplorerViewModel.CategoryResult category; // header 时非空
        final EtpClient.EtpSearchResult item;               // item 时非空

        Row(EtpExplorerViewModel.CategoryResult category) {
            this.header = true;
            this.category = category;
            this.item = null;
        }

        Row(EtpClient.EtpSearchResult item) {
            this.header = false;
            this.category = null;
            this.item = item;
        }
    }

    EtpExplorerAdapter(OnItemClickListener listener) {
        this.onItemClickListener = listener;
    }

    void setOnItemLongClickListener(OnItemLongClickListener listener) {
        this.onItemLongClickListener = listener;
    }

    /** 按分类顺序重建扁平列表（含 header 位置记录，供分类 chips 跳转）。 */
    void submitCategories(List<EtpExplorerViewModel.CategoryResult> categories) {
        rows.clear();
        headerPositions.clear();
        for (EtpExplorerViewModel.CategoryResult c : categories) {
            if (c.items == null || c.items.isEmpty()) continue;
            headerPositions.add(rows.size());
            rows.add(new Row(c));
            for (EtpClient.EtpSearchResult r : c.items) {
                rows.add(new Row(r));
            }
        }
        notifyDataSetChanged();
    }

    /** 各分类 header 在扁平列表中的位置（与非空分类顺序一致）。 */
    List<Integer> getHeaderPositions() {
        return new ArrayList<>(headerPositions);
    }

    /** 设置本地搜索命中词；null/空清除高亮。 */
    void setHighlightTerms(List<String> terms) {
        this.highlightTerms = (terms != null && !terms.isEmpty()) ? terms : null;
        notifyDataSetChanged();
    }

    @Override
    public int getItemViewType(int position) {
        return rows.get(position).header ? TYPE_HEADER : TYPE_ITEM;
    }

    @Override
    public int getItemCount() {
        return rows.size();
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (viewType == TYPE_HEADER) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_etp_explorer_header, parent, false);
            return new HeaderViewHolder(v);
        }
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_etp_result, parent, false);
        return new ItemViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        Row row = rows.get(position);
        if (row.header) {
            HeaderViewHolder hh = (HeaderViewHolder) holder;
            hh.tvLabel.setText(row.category.label);
            hh.tvCount.setText(hh.itemView.getContext().getString(
                    R.string.etp_explorer_count, row.category.items.size()));
            Context ctx = hh.itemView.getContext();
            int color = CategoryColors.color(ctx, row.category.label);
            hh.tvLabel.setTextColor(color != 0
                    ? color : CategoryColors.headerDefault(ctx));
            return;
        }

        ItemViewHolder vh = (ItemViewHolder) holder;
        EtpClient.EtpSearchResult r = row.item;
        vh.tvName.setText(highlight(r.name, highlightTerms));
        vh.ivIcon.setImageResource(r.isFolder
                ? R.drawable.ic_folder : FileTypeIcon.forName(r.name));

        if (r.size >= 0) {
            vh.tvFileSize.setVisibility(View.VISIBLE);
            vh.tvFileSize.setText(EtpClient.EtpSearchResult.formatSize(r.size));
        } else {
            vh.tvFileSize.setVisibility(View.GONE);
        }

        String[] loc = getLocationInfo(r);
        String locationLabel = loc != null ? loc[0] : null;
        boolean hasLocation = locationLabel != null;

        // 路径文本：常见目录显示其下的相对子路径；其他显示完整路径
        String pathText = null;
        if (loc != null && loc[1] != null) {
            pathText = loc[1].isEmpty() ? null : loc[1] + "\\" + r.name;
        } else if (r.path != null && !r.path.isEmpty()) {
            pathText = r.path + "\\" + r.name;
        }
        boolean hasPath = pathText != null;
        boolean hasDate = r.dateModified > 0;

        if (hasLocation || hasPath || hasDate) {
            vh.layoutPathRow.setVisibility(View.VISIBLE);
        if (hasLocation) {
            vh.tvLocation.setText(locationLabel);
            Context ctx = vh.itemView.getContext();
            int color = CategoryColors.color(ctx, locationLabel);
            if (color != 0) {
                vh.tvLocation.setBackground(CategoryColors.tagBackground(ctx, color));
            } else {
                vh.tvLocation.setBackgroundResource(R.drawable.bg_location_tag);
            }
            vh.tvLocation.setVisibility(View.VISIBLE);
        } else {
            vh.tvLocation.setVisibility(View.GONE);
        }
            if (hasPath) {
                vh.tvPath.setVisibility(View.VISIBLE);
                vh.tvPath.setText(highlight(pathText, highlightTerms));
            } else {
                // 无路径时保留 weight 占位，让日期贴右对齐
                vh.tvPath.setVisibility(View.VISIBLE);
                vh.tvPath.setText("");
            }
        } else {
            vh.layoutPathRow.setVisibility(View.GONE);
        }

        if (hasDate) {
            vh.tvFileDate.setVisibility(View.VISIBLE);
            vh.tvFileDate.setText(formatFileDate(
                    EtpClient.EtpSearchResult.filetimeToMillis(r.dateModified)));
        } else {
            vh.tvFileDate.setVisibility(View.GONE);
        }

        vh.itemView.setOnClickListener(v -> {
            if (onItemClickListener != null) onItemClickListener.onItemClick(r);
        });
        vh.itemView.setOnLongClickListener(v -> {
            if (onItemLongClickListener != null) return onItemLongClickListener.onItemLongClick(r);
            return false;
        });
    }

    /**
     * 位置信息：{标签, 相对路径}。
     * 常见用户目录（桌面/文档等）时返回标签 + 该目录下的相对子路径（可直接展示）；
     * 盘符时返回盘符 + null（相对路径保持完整）；其他返回 null。
     */
    private static String[] getLocationInfo(EtpClient.EtpSearchResult r) {
        String path = r.path != null ? r.path : (r.name != null ? r.name : "");
        if (path.isEmpty()) return null;

        Matcher m = PATTERN_USER_FOLDER.matcher(path);
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
        if (path.length() >= 2 && path.charAt(1) == ':') {
            return new String[]{path.substring(0, 2), null};
        }
        return null;
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

    /** 对命中的关键字加背景高亮（大小写不敏感，可多词多次命中）。 */
    private static CharSequence highlight(String text, List<String> terms) {
        if (text == null || terms == null || terms.isEmpty()) return text;
        String lower = text.toLowerCase();
        SpannableString ss = new SpannableString(text);
        for (String t : terms) {
            if (t == null || t.isEmpty()) continue;
            int from = 0;
            while (true) {
                int idx = lower.indexOf(t, from);
                if (idx < 0) break;
                ss.setSpan(new BackgroundColorSpan(HIGHLIGHT_COLOR),
                        idx, idx + t.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                from = idx + t.length();
            }
        }
        return ss;
    }

    static class HeaderViewHolder extends RecyclerView.ViewHolder {
        final TextView tvLabel, tvCount;
        HeaderViewHolder(@NonNull View itemView) {
            super(itemView);
            tvLabel = itemView.findViewById(R.id.tvCategoryLabel);
            tvCount = itemView.findViewById(R.id.tvCategoryCount);
        }
    }

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
}
