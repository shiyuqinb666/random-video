package com.fujiang.randomvideo;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.GridView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

public class MainActivity extends Activity {
    private static final int REQ_PERM = 1001;

    private Library lib;
    private CategoryAdapter adapter;
    private GridView grid;
    private TextView tvStats;
    private volatile boolean loading = false;
    private volatile boolean pendingReload = false;

    @Override
    protected void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        setContentView(R.layout.activity_main);
        lib = Library.get(this);
        tvStats = (TextView) findViewById(R.id.tv_stats);
        grid = (GridView) findViewById(R.id.grid_categories);

        adapter = new CategoryAdapter(this, lib, new CategoryAdapter.OnPick() {
            @Override
            public void onPick(Category category) {
                openCategory(category);
            }
        });
        grid.setAdapter(adapter);
        grid.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                if (position >= 0 && position < lib.categories.size()) {
                    openCategory(lib.categories.get(position));
                }
            }
        });
        grid.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                if (position >= 0 && position < lib.categories.size()) {
                    CategoryActivity.start(MainActivity.this, lib.categories.get(position).key);
                }
                return true;
            }
        });

        ((LinearLayout) findViewById(R.id.btn_random_all)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                playRandom(Library.C_ALL);
            }
        });
        ((ImageButton) findViewById(R.id.btn_refresh)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                reload(true);
            }
        });
        ((ImageButton) findViewById(R.id.btn_settings)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, SettingsActivity.class));
            }
        });

        injectHistoryButton();
        ensurePermission();
        reload(false);
    }

    /** 首页「随机播放」下面塞一个历史入口（不改布局资源）。 */
    private void injectHistoryButton() {
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
                if ("history_btn".equals(root.getChildAt(i).getTag())) {
                    return;
                }
            }
            LinearLayout btn = new LinearLayout(this);
            btn.setTag("history_btn");
            btn.setOrientation(LinearLayout.HORIZONTAL);
            btn.setGravity(android.view.Gravity.CENTER);
            btn.setBackgroundResource(R.drawable.bg_btn_ghost);
            btn.setClickable(true);
            btn.setFocusable(true);
            TextView label = new TextView(this);
            label.setText("🕘 历史记录 · 下载箱 / 看过");
            label.setTextSize(13f);
            label.setTextColor(0xccffffff);
            btn.addView(label);
            btn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    HistoryActivity.start(MainActivity.this);
                }
            });
            View randomBtn = findViewById(R.id.btn_random_all);
            int at = root.getChildCount();
            for (int i = 0; i < root.getChildCount(); i++) {
                if (root.getChildAt(i) == randomBtn) {
                    at = i + 1;
                    break;
                }
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, Util.dp(this, 42f));
            lp.topMargin = Util.dp(this, 10f);
            root.addView(btn, at, lp);
        } catch (Throwable th) {
            IO.log("injectHistory " + th);
        }
    }

    private void ensurePermission() {
        if (android.os.Build.VERSION.SDK_INT < 23) {
            return;
        }
        String perm = android.os.Build.VERSION.SDK_INT >= 33
                ? "android.permission.READ_MEDIA_VIDEO"
                : "android.permission.READ_EXTERNAL_STORAGE";
        try {
            if (checkSelfPermission(perm) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{perm}, REQ_PERM);
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onRequestPermissionsResult(int request, String[] perms, int[] results) {
        super.onRequestPermissionsResult(request, perms, results);
        if (request != REQ_PERM) {
            return;
        }
        boolean granted = results.length > 0 && results[0] == android.content.pm.PackageManager.PERMISSION_GRANTED;
        if (!granted) {
            Util.toast(this, "没给读取视频的权限，只能玩在线源了");
        }
        reload(true);
    }

    private void reload(final boolean force) {
        if (loading) {
            pendingReload = true;
            return;
        }
        loading = true;
        tvStats.setText(force ? "正在重新整理片库…" : "正在整理你的片库…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    lib.loadAll();
                } catch (Throwable th) {
                    IO.log("loadAll failed " + th);
                }
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        loading = false;
                        if (isFinishing()) {
                            return;
                        }
                        if (adapter != null) {
                            adapter.notifyDataSetChanged();
                        }
                        tvStats.setText(lib.statsText());
                        if (pendingReload) {
                            pendingReload = false;
                            reload(true);
                        }
                    }
                });
            }
        }, "library-loader").start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (lib == null) {
            return;
        }
        if (lib.dirty && !loading) {
            reload(false);
            return;
        }
        if (adapter != null) {
            adapter.notifyDataSetChanged();
            tvStats.setText(lib.statsText());
        }
    }

    private void openCategory(Category category) {
        if (category == null) {
            return;
        }
        if (category.isApi()) {
            PlayerActivity.startApi(this, category.key);
            return;
        }
        if (lib.count(category.key) == 0) {
            Util.toast(this, "「" + category.name + "」还是空的，去设置里加视频");
        }
        CategoryActivity.start(this, category.key);
    }

    private void playRandom(String catKey) {
        if (lib.apiCount(catKey) > 0) {
            PlayerActivity.startApi(this, catKey);
            return;
        }
        Video v = lib.random(catKey, null);
        if (v == null) {
            Util.toast(this, "片库是空的，先去设置里加视频，或把视频放进 Movies 目录");
        } else {
            PlayerActivity.start(this, catKey, v.url);
        }
    }
}