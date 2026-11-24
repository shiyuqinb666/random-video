package com.fujiang.randomvideo;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

public class CategoryActivity extends Activity {
    private static final String EXTRA_KEY = "category_key";

    private Library lib;
    private VideoAdapter adapter;
    private String catKey;
    private TextView tvEmpty;
    private TextView tvSubtitle;
    private android.widget.EditText etSearch;
    private final List<Video> allData = new ArrayList<Video>();
    private String query = "";

    public static void start(Context context, String key) {
        Intent intent = new Intent(context, CategoryActivity.class);
        intent.putExtra(EXTRA_KEY, key);
        context.startActivity(intent);
    }

    @Override
    protected void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        setContentView(R.layout.activity_category);
        lib = Library.get(this);
        String key = getIntent().getStringExtra(EXTRA_KEY);
        catKey = key == null ? Library.C_ALL : key;

        Category category = lib.category(catKey);
        TextView title = (TextView) findViewById(R.id.tv_title);
        tvSubtitle = (TextView) findViewById(R.id.tv_subtitle);
        tvEmpty = (TextView) findViewById(R.id.tv_empty);
        ListView list = (ListView) findViewById(R.id.list_videos);
        if (category != null) {
            title.setText(category.name);
            tvSubtitle.setText(category.sub);
        } else {
            title.setText("分类");
        }

        adapter = new VideoAdapter(this, lib, new VideoAdapter.Listener() {
            @Override
            public void onPlay(Video video, int position) {
                Hidden.play(CategoryActivity.this, catKey, video.url);
            }

            @Override
            public void onToggleFav(Video video, int position) {
                boolean added = lib.toggleFav(video);
                Util.toast(CategoryActivity.this, added ? "已收藏 💗" : "已取消收藏");
                refresh();
            }

            @Override
            public void onLongPress(Video video, final int position) {
                showItemMenu(video);
            }
        });
        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                Video v = (Video) adapter.getItem(position);
                if (v != null) {
                    Hidden.play(CategoryActivity.this, catKey, v.url);
                }
            }
        });

        injectSearch();
        ((ImageButton) findViewById(R.id.btn_back)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        ((LinearLayout) findViewById(R.id.btn_random_cat)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (lib.apiCount(catKey) > 0) {
                    PlayerActivity.startApi(CategoryActivity.this, catKey);
                    return;
                }
                Video picked = lib.random(catKey, null);
                if (picked == null) {
                    Util.toast(CategoryActivity.this, "这里还是空的，加几条再来");
                } else {
                    PlayerActivity.start(CategoryActivity.this, catKey, picked.url);
                }
            }
        });
        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    /** 顶部插入搜索框：按标题 / 来源实时过滤当前分类。 */
    private void injectSearch() {
        try {
            View content = findViewById(android.R.id.content);
            if (!(content instanceof ViewGroup)) {
                return;
            }
            View first = ((ViewGroup) content).getChildAt(0);
            if (!(first instanceof LinearLayout)) {
                return;
            }
            LinearLayout root = (LinearLayout) first;
            for (int i = 0; i < root.getChildCount(); i++) {
                if ("search_row".equals(root.getChildAt(i).getTag())) {
                    return;
                }
            }
            LinearLayout row = new LinearLayout(this);
            row.setTag("search_row");
            row.setOrientation(LinearLayout.HORIZONTAL);
            int pad = Util.dp(this, 14f);
            row.setPadding(pad, 0, pad, Util.dp(this, 6f));

            etSearch = new android.widget.EditText(this);
            etSearch.setHint("搜索标题 / 来源");
            etSearch.setTextSize(13f);
            etSearch.setTextColor(0xffffffff);
            etSearch.setHintTextColor(0x66ffffff);
            etSearch.setSingleLine(true);
            etSearch.setBackgroundResource(R.drawable.bg_input);
            etSearch.setPadding(Util.dp(this, 12f), 0, Util.dp(this, 12f), 0);
            etSearch.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
            etSearch.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
            row.addView(etSearch, new LinearLayout.LayoutParams(0,
                    Util.dp(this, 42f), 1f));

            etSearch.addTextChangedListener(new android.text.TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int a, int b, int c) {
                }

                @Override
                public void onTextChanged(CharSequence s, int a, int b, int c) {
                }

                @Override
                public void afterTextChanged(android.text.Editable e) {
                    query = e == null ? "" : e.toString().trim();
                    applyFilter();
                }
            });

            // 插在标题栏下方（索引 1），列表前面
            int at = root.getChildCount() > 1 ? 1 : root.getChildCount();
            root.addView(row, at, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        } catch (Throwable th) {
            IO.log("injectSearch " + th);
        }
    }

    /** 按 query 过滤 allData，并刷新列表与空状态。 */
    private void applyFilter() {
        List<Video> shown;
        if (query.length() == 0) {
            shown = new ArrayList<Video>(allData);
        } else {
            String q = IO.lower(query);
            shown = new ArrayList<Video>();
            for (int i = 0; i < allData.size(); i++) {
                Video v = allData.get(i);
                if (IO.lower(v.title).contains(q) || IO.lower(v.sourceLabel).contains(q)
                        || IO.lower(v.category).contains(q)) {
                    shown.add(v);
                }
            }
        }
        if (adapter != null) {
            adapter.setData(shown);
        }
        tvEmpty.setVisibility(shown.isEmpty() ? View.VISIBLE : View.GONE);
        if (shown.isEmpty() && query.length() > 0) {
            tvEmpty.setText("没有匹配「" + query + "」的视频");
        } else {
            tvEmpty.setText("这个分类还没有视频\n把视频放进对应文件夹，或在设置里加在线源");
        }
        Category category = lib.category(catKey);
        if (category != null && tvSubtitle != null) {
            String text = category.sub + " · 共 " + allData.size() + " 条";
            if (query.length() > 0) {
                text = text + "（筛选 " + shown.size() + "）";
            }
            tvSubtitle.setText(text);
        }
    }

    private void showItemMenu(final Video video) {
        final boolean fav = lib.isFav(video);
        String[] items = new String[]{
                fav ? "取消收藏" : "收藏这条",
                "复制链接",
                "分享",
                "播放",
                "从这个分类移除"
        };
        new AlertDialog.Builder(this)
                .setTitle(video.title)
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        switch (which) {
                            case 0:
                                Util.toast(CategoryActivity.this, lib.toggleFav(video) ? "已收藏 💗" : "已取消收藏");
                                refresh();
                                break;
                            case 1:
                                copy(video.url);
                                break;
                            case 2:
                                share(video);
                                break;
                            case 3:
                                Hidden.play(CategoryActivity.this, catKey, video.url);
                                break;
                            case 4:
                                lib.removeVideo(video);
                                lib.dirty = true;
                                refresh();
                                Util.toast(CategoryActivity.this, "已移除");
                                break;
                            default:
                                break;
                        }
                    }
                })
                .show();
    }

    private void share(Video video) {
        try {
            android.content.Intent intent = new android.content.Intent(android.content.Intent.ACTION_SEND);
            intent.setType("text/plain");
            intent.putExtra(android.content.Intent.EXTRA_SUBJECT, video.title);
            intent.putExtra(android.content.Intent.EXTRA_TEXT, video.title + "\n" + video.url);
            startActivity(android.content.Intent.createChooser(intent, "分享这条视频"));
        } catch (Throwable th) {
            Util.toast(this, "分享失败：" + th.getClass().getSimpleName());
        }
    }

    private void copy(String text) {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("url", text));
                Util.toast(this, "链接已复制");
            }
        } catch (Throwable th) {
            Util.toast(this, "复制失败");
        }
    }

    private void refresh() {
        List<Video> list = lib.list(catKey);
        if (list.isEmpty() && Hidden.isHidden(catKey)) {
            if ("hidden_vault".equals(catKey)) {
                list = Hidden.vaultVideos(this);
            } else if ("hidden_custom".equals(catKey)) {
                list = Hidden.customVideos(this);
            }
        }
        allData.clear();
        allData.addAll(list);
        applyFilter();
    }
}