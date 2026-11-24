package com.fujiang.randomvideo;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * 历史记录：两个页签
 *  · 下载箱：系统 DownloadManager 下的视频，能看进度、播、删
 *  · 看过：播放记录，带封面（首帧）+ 真实标题 + 相对时间
 *
 * 行视图用代码搭，封面由 Thumb 抓首帧并缓存，不新增任何资源。
 */
public class HistoryActivity extends Activity {
    private static final int TAB_DOWNLOAD = 0;
    private static final int TAB_WATCHED = 1;

    private Library lib;
    private int tab = TAB_DOWNLOAD;
    private TextView tabDownload;
    private TextView tabWatched;
    private ListView list;
    private TextView empty;
    private RowAdapter adapter;

    private boolean reloading = false;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable autoRefresh = new Runnable() {
        @Override
        public void run() {
            if (tab == TAB_DOWNLOAD && !isFinishing()) {
                reload();
            }
        }
    };

    public static void start(Context context) {
        context.startActivity(new Intent(context, HistoryActivity.class));
    }

    @Override
    protected void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        lib = Library.get(this);
        setContentView(buildRoot());
        selectTab(getIntent().getIntExtra("tab", TAB_DOWNLOAD));
    }

    private View buildRoot() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundResource(R.drawable.bg_app);
        int pad = Util.dp(this, 14f);
        root.setPadding(pad, Util.dp(this, 20f), pad, Util.dp(this, 6f));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        ImageButton back = new ImageButton(this);
        back.setBackgroundResource(android.R.color.transparent);
        back.setImageResource(R.drawable.ic_back);
        back.setColorFilter(0xffffffff);
        back.setPadding(Util.dp(this, 8f), Util.dp(this, 8f), Util.dp(this, 8f), Util.dp(this, 8f));
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        header.addView(back, new LinearLayout.LayoutParams(Util.dp(this, 40f), Util.dp(this, 40f)));
        TextView title = new TextView(this);
        title.setText("历史记录");
        title.setTextSize(21f);
        title.setTextColor(0xffffffff);
        title.getPaint().setFakeBoldText(true);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleLp.leftMargin = Util.dp(this, 6f);
        header.addView(title, titleLp);
        root.addView(header);

        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams tabsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Util.dp(this, 44f));
        tabsLp.topMargin = Util.dp(this, 16f);
        tabDownload = makeTab("⬇ 下载好的视频");
        tabWatched = makeTab("🕘 看过的视频");
        tabs.addView(tabDownload, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        LinearLayout.LayoutParams half2 = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        half2.leftMargin = Util.dp(this, 10f);
        tabs.addView(tabWatched, half2);
        tabDownload.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                selectTab(TAB_DOWNLOAD);
            }
        });
        tabWatched.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                selectTab(TAB_WATCHED);
            }
        });
        root.addView(tabs, tabsLp);

        FrameLayout box = new FrameLayout(this);
        LinearLayout.LayoutParams boxLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        boxLp.topMargin = Util.dp(this, 12f);
        list = new ListView(this);
        list.setDivider(null);
        list.setDividerHeight(0);
        list.setSelector(android.R.color.transparent);
        list.setPadding(0, 0, 0, Util.dp(this, 12f));
        list.setClipToPadding(false);
        adapter = new RowAdapter();
        list.setAdapter(adapter);
        box.addView(list, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        empty = new TextView(this);
        empty.setTextSize(13f);
        empty.setTextColor(0x77ffffff);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(Util.dp(this, 30f), Util.dp(this, 40f), Util.dp(this, 30f), Util.dp(this, 30f));
        box.addView(empty, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(box, boxLp);

        list.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(android.widget.AdapterView<?> parent, View view, int position, long id) {
                Row row = adapter.getItem(position);
                if (row != null) {
                    open(row);
                }
            }
        });
        list.setOnItemLongClickListener(new android.widget.AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(android.widget.AdapterView<?> parent, View view, int position, long id) {
                Row row = adapter.getItem(position);
                if (row != null) {
                    if (row.dl != null) {
                        confirmDeleteDownload(row.dl);
                    } else if (row.hist != null) {
                        confirmDeleteHistory(row.hist);
                    }
                }
                return true;
            }
        });
        return root;
    }

    private TextView makeTab(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(13f);
        tv.setGravity(Gravity.CENTER);
        tv.setClickable(true);
        tv.setFocusable(true);
        return tv;
    }

    private void selectTab(int which) {
        tab = which;
        styleTab(tabDownload, which == TAB_DOWNLOAD);
        styleTab(tabWatched, which == TAB_WATCHED);
        reload();
    }

    private void styleTab(TextView tv, boolean selected) {
        tv.setTextColor(selected ? 0xffffffff : 0xccffffff);
        tv.setBackgroundResource(selected ? R.drawable.bg_btn_random : R.drawable.bg_btn_ghost);
        tv.getPaint().setFakeBoldText(selected);
    }

    // ------------------------------------------------------------ 数据装载

    private void reload() {
        if (adapter == null || reloading) {
            return;
        }
        reloading = true;
        final int whichTab = tab;
        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<Row> rows = new ArrayList<Row>();
                boolean running = false;
                String hint;
                if (whichTab == TAB_DOWNLOAD) {
                    List<Downloader.Dl> recs = Downloader.records(HistoryActivity.this);
                    for (int i = 0; i < recs.size(); i++) {
                        Downloader.Dl d = recs.get(i);
                        rows.add(Row.ofDownload(d));
                        if (!d.finished() && !d.failed()) {
                            running = true;
                        }
                    }
                    hint = "还没有下载。\n在播放页点「⬇ 下载」就会出现在这里，\n文件同时在 Movies/随机视界/ 里。";
                } else {
                    List<Library.Hist> hist = lib.historyList();
                    for (int i = 0; i < hist.size(); i++) {
                        Library.Hist h = hist.get(i);
                        Video v = lib.find(h.url);
                        Category cat = lib.category(h.category);
                        rows.add(Row.ofHistory(h, v, cat == null ? null : cat.name));
                    }
                    hint = "还没有看过任何视频。\n播一条就会记在这里（带封面和标题），最多 200 条。";
                }
                final boolean hasRunning = running;
                final String emptyHint = hint;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        reloading = false;
                        if (isFinishing() || whichTab != tab) {
                            return;
                        }
                        adapter.setRows(rows);
                        empty.setText(emptyHint);
                        empty.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
                        list.setVisibility(rows.isEmpty() ? View.GONE : View.VISIBLE);
                        ui.removeCallbacks(autoRefresh);
                        if (hasRunning) {
                            ui.postDelayed(autoRefresh, 1500L);
                        }
                    }
                });
            }
        }, "history-loader").start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacks(autoRefresh);
        super.onDestroy();
    }

    // ------------------------------------------------------------ 交互

    private void open(Row row) {
        if (row.dl != null) {
            Downloader.Dl d = row.dl;
            String target = d.localUri;
            if (target == null || target.length() == 0) {
                if (!d.finished()) {
                    Util.toast(this, "还在下载中（" + d.percent() + "%），先看别的吧");
                    return;
                }
                target = d.url;
            }
            PlayerActivity.start(this, Library.C_ALL, target);
            return;
        }
        if (row.hist != null) {
            PlayerActivity.start(this, row.hist.category, row.hist.url);
        }
    }

    private void confirmDeleteDownload(final Downloader.Dl d) {
        new AlertDialog.Builder(this)
                .setTitle(Util.ellipsis(d.title, 30))
                .setItems(new String[]{"只删记录", "删记录并删掉文件"}, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        Downloader.removeRecord(HistoryActivity.this, d.id, which == 1);
                        reload();
                        Util.toast(HistoryActivity.this, which == 1 ? "已删除" : "记录已删除");
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void confirmDeleteHistory(final Library.Hist h) {
        new AlertDialog.Builder(this)
                .setTitle(Util.ellipsis(h.title, 30))
                .setItems(new String[]{"从历史里删掉这条"}, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        lib.removeHistory(h.url);
                        reload();
                        Util.toast(HistoryActivity.this, "已删除");
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ------------------------------------------------------------ 列表

    static final class Row {
        final Downloader.Dl dl;
        final Library.Hist hist;
        final Video video;
        final String coverUrl;
        final String title;
        final String meta;
        final long time;
        final boolean isDownload;

        private Row(Downloader.Dl d, Library.Hist h, Video v, String cover, String title,
                    String meta, long time, boolean isDownload) {
            this.dl = d;
            this.hist = h;
            this.video = v;
            this.coverUrl = cover;
            this.title = title;
            this.meta = meta;
            this.time = time;
            this.isDownload = isDownload;
        }

        static Row ofDownload(Downloader.Dl d) {
            // 封面上：下完的用本地 content://，还在下的用源地址抓首帧
            String cover = d.localUri != null ? d.localUri : d.url;
            StringBuilder meta = new StringBuilder();
            if (d.source != null && d.source.length() > 0) {
                meta.append(d.source).append(" · ");
            }
            meta.append(d.stateText());
            String size = d.sizeText();
            if (size.length() > 0) {
                meta.append(" · ").append(size);
            }
            meta.append(" · ").append(ago(d.time));
            String title = Title.resolve(d.title, null, d.source, d.url, d.time);
            if (title == null || title.trim().length() == 0) {
                title = "下载的视频";
            }
            return new Row(d, null, null, cover, title, meta.toString(), d.time, true);
        }

        static Row ofHistory(Library.Hist h, Video v, String catName) {
            String cover = h.cover != null && h.cover.startsWith("http") ? h.cover
                    : (v != null && v.cover != null && v.cover.startsWith("http") ? v.cover : h.url);
            String title = Title.resolve(h.title, v == null ? null : v.title,
                    catName, h.url, h.time);
            boolean local = !h.url.startsWith("http");
            StringBuilder meta = new StringBuilder();
            meta.append(local ? "本地" : (v != null ? v.sourceLabel : "看过"));
            if (v != null && v.durationMs > 0) {
                meta.append(" · ").append(Util.fmtTime((int) v.durationMs));
            }
            meta.append(" · ").append(ago(h.time));
            return new Row(null, h, v, cover, title.trim(), meta.toString(), h.time, false);
        }

        static String ago(long ts) {
            if (ts <= 0L) {
                return "早前";
            }
            long d = System.currentTimeMillis() - ts;
            if (d < 60_000L) {
                return "刚刚";
            }
            if (d < 3_600_000L) {
                return (d / 60_000L) + " 分钟前";
            }
            if (d < 86_400_000L) {
                return (d / 3_600_000L) + " 小时前";
            }
            if (d < 30L * 86_400_000L) {
                return (d / 86_400_000L) + " 天前";
            }
            java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("MM-dd", java.util.Locale.CHINA);
            return f.format(new java.util.Date(ts));
        }
    }

    private final class RowAdapter extends BaseAdapter {
        private final List<Row> rows = new ArrayList<Row>();

        void setRows(List<Row> list) {
            rows.clear();
            if (list != null) {
                rows.addAll(list);
            }
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return rows.size();
        }

        @Override
        public Row getItem(int position) {
            return position >= 0 && position < rows.size() ? rows.get(position) : null;
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            ViewHolder h;
            View view = convertView;
            if (view == null) {
                view = buildRow();
                h = (ViewHolder) view.getTag();
            } else {
                h = (ViewHolder) view.getTag();
            }
            Row row = rows.get(position);

            String shown = row.title;
            if (shown == null || shown.trim().length() == 0) {
                shown = "视频";
            }
            h.title.setText(shown);
            h.meta.setText(row.meta);

            // 封面：抓到之前先显示占位图标
            if (row.coverUrl != null && row.coverUrl.length() > 0) {
                h.cover.setVisibility(View.VISIBLE);
                h.placeholder.setVisibility(View.GONE);
                Thumb.attach(HistoryActivity.this, h.cover, row.coverUrl);
            } else {
                h.cover.setVisibility(View.GONE);
                h.placeholder.setVisibility(View.VISIBLE);
            }
            if (row.isDownload) {
                boolean done = row.dl != null && row.dl.finished();
                h.badge.setText(done ? "⬇" : "%");
                h.badge.setTextColor(done ? 0xffff4f9a : 0x99ffffff);
            } else {
                h.badge.setText("▶");
                h.badge.setTextColor(0x66ffffff);
            }
            return view;
        }

        /** 一行：封面(16:9) + 标题/副标题 + 右侧小标记 */
        private View buildRow() {
            LinearLayout outer = new LinearLayout(HistoryActivity.this);
            outer.setOrientation(LinearLayout.VERTICAL);
            outer.setPadding(0, 0, 0, Util.dp(HistoryActivity.this, 8f));

            LinearLayout row = new LinearLayout(HistoryActivity.this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setBackgroundResource(R.drawable.bg_item_video);
            int p = Util.dp(HistoryActivity.this, 8f);
            row.setPadding(p, p, p, p);
            outer.addView(row, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            FrameLayout box = new FrameLayout(HistoryActivity.this);
            GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    new int[]{0xff2a0b3d, 0xff12001b});
            g.setCornerRadius(Util.dp(HistoryActivity.this, 8f));
            box.setBackground(g);
            LinearLayout.LayoutParams boxLp = new LinearLayout.LayoutParams(
                    Util.dp(HistoryActivity.this, 92f), Util.dp(HistoryActivity.this, 54f));
            row.addView(box, boxLp);

            ImageView cover = new ImageView(HistoryActivity.this);
            cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
            box.addView(cover, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            ImageView placeholder = new ImageView(HistoryActivity.this);
            placeholder.setImageResource(R.drawable.ic_video);
            placeholder.setColorFilter(0x55ffffff);
            FrameLayout.LayoutParams phLp = new FrameLayout.LayoutParams(
                    Util.dp(HistoryActivity.this, 20f), Util.dp(HistoryActivity.this, 20f));
            phLp.gravity = Gravity.CENTER;
            box.addView(placeholder, phLp);

            LinearLayout texts = new LinearLayout(HistoryActivity.this);
            texts.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams textsLp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            textsLp.leftMargin = Util.dp(HistoryActivity.this, 10f);
            row.addView(texts, textsLp);

            TextView title = new TextView(HistoryActivity.this);
            title.setTextSize(14f);
            title.setTextColor(0xffffffff);
            title.setSingleLine(false);
            title.setMaxLines(2);
            title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            texts.addView(title, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            TextView meta = new TextView(HistoryActivity.this);
            meta.setTextSize(11f);
            meta.setTextColor(0x80ffffff);
            meta.setSingleLine(true);
            meta.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams metaLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            metaLp.topMargin = Util.dp(HistoryActivity.this, 3f);
            texts.addView(meta, metaLp);

            TextView badge = new TextView(HistoryActivity.this);
            badge.setTextSize(12f);
            badge.setGravity(Gravity.CENTER);
            badge.setText("▶");
            LinearLayout.LayoutParams badgeLp = new LinearLayout.LayoutParams(
                    Util.dp(HistoryActivity.this, 26f), Util.dp(HistoryActivity.this, 26f));
            row.addView(badge, badgeLp);

            ViewHolder holder = new ViewHolder();
            holder.cover = cover;
            holder.placeholder = placeholder;
            holder.title = title;
            holder.meta = meta;
            holder.badge = badge;
            outer.setTag(holder);
            return outer;
        }
    }

    private static final class ViewHolder {
        ImageView cover;
        ImageView placeholder;
        TextView title;
        TextView meta;
        TextView badge;
    }
}