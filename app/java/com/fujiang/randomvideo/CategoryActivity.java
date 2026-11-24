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

    private void showItemMenu(final Video video) {
        final boolean fav = lib.isFav(video);
        String[] items = new String[]{
                fav ? "取消收藏" : "收藏这条",
                "复制链接",
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
                                Hidden.play(CategoryActivity.this, catKey, video.url);
                                break;
                            case 3:
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
        if (adapter != null) {
            adapter.setData(list);
        }
        tvEmpty.setVisibility(list.isEmpty() ? View.VISIBLE : View.GONE);
        Category category = lib.category(catKey);
        if (category != null && tvSubtitle != null) {
            tvSubtitle.setText(category.sub + " · 共 " + list.size() + " 条");
        }
    }
}