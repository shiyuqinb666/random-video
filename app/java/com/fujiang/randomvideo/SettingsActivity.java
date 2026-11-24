package com.fujiang.randomvideo;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

public class SettingsActivity extends Activity {
    private static final int REQ_PICK = 2001;

    private Library lib;
    private EditText etSource;
    private EditText etManual;
    private TextView tvSourceStatus;
    private TextView tvScanStatus;
    private TextView tvPick;
    private TextView tvAbout;
    private String selectedCat = "jk";

    @Override
    protected void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        setContentView(R.layout.activity_settings);
        lib = Library.get(this);

        etSource = (EditText) findViewById(R.id.et_source_url);
        etManual = (EditText) findViewById(R.id.et_manual_urls);
        tvSourceStatus = (TextView) findViewById(R.id.tv_source_status);
        tvScanStatus = (TextView) findViewById(R.id.tv_scan_status);
        tvPick = (TextView) findViewById(R.id.tv_pick_category);
        tvAbout = (TextView) findViewById(R.id.tv_about);

        etSource.setText(lib.getSourceUrl());
        tvPick.setText("分类：" + nameOf(selectedCat));

        findViewById(R.id.btn_back).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        tvPick.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickCategory();
            }
        });
        findViewById(R.id.btn_load_source).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                loadSource();
            }
        });
        findViewById(R.id.btn_add_manual).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                addManual();
            }
        });
        findViewById(R.id.btn_rescan).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                rescan();
            }
        });
        findViewById(R.id.btn_clear_history).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                lib.clearHistory();
                Util.toast(SettingsActivity.this, "历史已清空");
            }
        });
        findViewById(R.id.btn_reset_dislike).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                lib.clearDislikes();
                tvSourceStatus.setText(buildSourceStatus());
                Util.toast(SettingsActivity.this, "源权重已重置");
            }
        });
        findViewById(R.id.btn_clear_fav).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                lib.clearFavs();
                lib.dirty = true;
                Util.toast(SettingsActivity.this, "收藏已清空");
            }
        });
        findViewById(R.id.btn_pick_files).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickFiles();
            }
        });
        tvAbout.setOnClickListener(new View.OnClickListener() {
            long lastTapAt = 0L;
            int taps = 0;

            @Override
            public void onClick(View v) {
                // 5 次快速点击解锁隐藏片库；平时点一次进「关于」
                long now = android.os.SystemClock.elapsedRealtime();
                if (now - lastTapAt > 1500L) {
                    taps = 0;
                }
                lastTapAt = now;
                taps++;
                if (taps >= 5) {
                    taps = 0;
                    Hidden.openPicker(SettingsActivity.this);
                    return;
                }
                if (taps == 1) {
                    Util.toast(SettingsActivity.this, "连点 5 下可以解锁隐藏片库哦");
                }
            }
        });

        tvScanStatus.setText(TextUtils.isEmpty(lib.statusOnline)
                ? lib.statusLocal : lib.statusLocal + " / " + lib.statusOnline);
        tvSourceStatus.setText(buildSourceStatus());

        injectHistoryRow();
        injectHiddenEntry();
    }

    /** 「数据」卡片里加一行「历史记录 / 下载箱」入口。 */
    private void injectHistoryRow() {
        try {
            LinearLayout card = null;
            View content = findViewById(android.R.id.content);
            if (content instanceof ViewGroup) {
                View first = ((ViewGroup) content).getChildAt(0);
                if (first instanceof ScrollView) {
                    View inner = ((ScrollView) first).getChildAt(0);
                    if (inner instanceof LinearLayout) {
                        LinearLayout box = (LinearLayout) inner;
                        View reset = findViewById(R.id.btn_reset_dislike);
                        if (reset != null && reset.getParent() instanceof LinearLayout) {
                            card = (LinearLayout) reset.getParent();
                        }
                    }
                }
            }
            if (card == null) {
                return;
            }
            LinearLayout btn = new LinearLayout(this);
            btn.setTag("history_row");
            btn.setOrientation(LinearLayout.HORIZONTAL);
            btn.setGravity(android.view.Gravity.CENTER);
            btn.setBackgroundResource(R.drawable.bg_btn_ghost);
            btn.setClickable(true);
            btn.setFocusable(true);
            TextView label = new TextView(this);
            label.setText("🕘 历史记录（下载箱 / 看过）");
            label.setTextSize(13f);
            label.setTextColor(0xccffffff);
            btn.addView(label);
            btn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    HistoryActivity.start(SettingsActivity.this);
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, Util.dp(this, 44f));
            lp.topMargin = Util.dp(this, 10f);
            card.addView(btn, card.getChildCount(), lp);
        } catch (Throwable th) {
            IO.log("injectHistoryRow " + th);
        }
    }

    // ------------------------------------------------------------ 隐藏片库入口

    /**
     * 在「数据」卡片下面、关于上面插一个显眼的入口按钮，
     * 点一下直接进隐藏片库面板。不改资源、纯代码注入，样式复用现有 drawable。
     */
    private void injectHiddenEntry() {
        try {
            View content = findViewById(android.R.id.content);
            if (!(content instanceof ViewGroup)) {
                return;
            }
            View first = ((ViewGroup) content).getChildAt(0);
            LinearLayout container = null;
            if (first instanceof ScrollView) {
                View inner = ((ScrollView) first).getChildAt(0);
                if (inner instanceof LinearLayout) {
                    container = (LinearLayout) inner;
                }
            } else if (first instanceof LinearLayout) {
                container = (LinearLayout) first;
            }
            if (container == null) {
                return;
            }
            for (int i = 0; i < container.getChildCount(); i++) {
                View child = container.getChildAt(i);
                if (child instanceof ViewGroup && "hidden_entry".equals(child.getTag())) {
                    return;
                }
            }

            LinearLayout box = new LinearLayout(this);
            box.setTag("hidden_entry");
            box.setOrientation(LinearLayout.VERTICAL);
            box.setBackgroundResource(R.drawable.bg_panel);
            int pad = Util.dp(this, 16f);
            box.setPadding(pad, pad, pad, pad);

            TextView title = new TextView(this);
            title.setText("颜色区（隐藏）");
            title.setTextSize(15f);
            title.setTextColor(0xffffffff);
            title.getPaint().setFakeBoldText(true);
            box.addView(title);

            TextView hint = new TextView(this);
            hint.setText("深夜 / 吊带热舞 / 完美身材 / 丝袜 / 泳装 / 甜妹 / 全站，外加「我的颜色源」和「私密柜」。\n原来的深夜・洋装・漫展・甜妹・日常・全站已经搬到首页了。");
            hint.setTextSize(11f);
            hint.setTextColor(0x8cffffff);
            LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            hintLp.topMargin = Util.dp(this, 6f);
            box.addView(hint, hintLp);

            LinearLayout btn = new LinearLayout(this);
            btn.setGravity(Gravity.CENTER);
            btn.setOrientation(LinearLayout.HORIZONTAL);
            btn.setBackgroundResource(R.drawable.bg_btn_random);
            btn.setClickable(true);
            btn.setFocusable(true);
            LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, Util.dp(this, 48f));
            btnLp.topMargin = Util.dp(this, 12f);
            TextView label = new TextView(this);
            label.setText("🔓 进入颜色区");
            label.setTextSize(14f);
            label.setTextColor(0xffffffff);
            label.getPaint().setFakeBoldText(true);
            btn.addView(label);
            btn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Hidden.openPicker(SettingsActivity.this);
                }
            });
            box.addView(btn, btnLp);

            LinearLayout.LayoutParams boxLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            boxLp.topMargin = Util.dp(this, 14f);

            int at = container.getChildCount();
            if (tvAbout != null) {
                for (int i = 0; i < container.getChildCount(); i++) {
                    if (container.getChildAt(i) == tvAbout) {
                        at = i;
                        break;
                    }
                }
            }
            container.addView(box, at, boxLp);
        } catch (Throwable th) {
            IO.log("injectHiddenEntry " + th);
        }
    }

    // ------------------------------------------------------------ 交互

    private void loadSource() {
        final String url = etSource.getText().toString().trim();
        lib.setSourceUrl(url);
        if (TextUtils.isEmpty(url)) {
            tvSourceStatus.setText("已清空在线源");
            lib.dirty = true;
            return;
        }
        tvSourceStatus.setText("正在加载…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                final int n = lib.loadRemoteSource(url);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing()) {
                            return;
                        }
                        tvSourceStatus.setText(lib.statusOnline);
                        lib.dirty = true;
                        Util.toast(SettingsActivity.this, n > 0 ? "已加载 " + n + " 条" : lib.statusOnline);
                    }
                });
            }
        }, "source-loader").start();
    }

    private void addManual() {
        final String text = etManual.getText().toString().trim();
        tvScanStatus.setText("正在加入…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                final int n = lib.addManualVideos(selectedCat, text);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing()) {
                            return;
                        }
                        if (n > 0) {
                            etManual.setText("");
                            lib.dirty = true;
                            tvScanStatus.setText(lib.statusLocal + " / " + lib.statusOnline);
                            Util.toast(SettingsActivity.this, "已加入 " + n + " 条到「" + nameOf(selectedCat) + "」");
                        } else {
                            Util.toast(SettingsActivity.this, "没识别到新链接，每行一条 http/https 直链");
                        }
                    }
                });
            }
        }, "manual-adder").start();
    }

    private void rescan() {
        tvScanStatus.setText("正在扫描…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                lib.loadAll();
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing()) {
                            return;
                        }
                        tvScanStatus.setText(lib.statusLocal + " / " + lib.statusOnline);
                        tvSourceStatus.setText(buildSourceStatus());
                        lib.dirty = true;
                        Util.toast(SettingsActivity.this, "扫描完成");
                    }
                });
            }
        }, "rescan").start();
    }

    private void pickFiles() {
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("video/*");
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            startActivityForResult(intent, REQ_PICK);
        } catch (Throwable th) {
            Util.toast(this, "打不开文件选择器：" + th.getClass().getSimpleName());
        }
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null) {
            return;
        }
        if (request == Hidden.REQ_VAULT) {
            int added = Hidden.handleVaultResult(this, data);
            Util.toast(this, added > 0 ? "已放进私密柜 " + added + " 个视频" : "这些视频已经在私密柜了");
            return;
        }
        if (request != REQ_PICK) {
            return;
        }
        List<String> uris = new ArrayList<String>();
        List<String> names = new ArrayList<String>();
        ClipData clip = data.getClipData();
        if (clip != null) {
            for (int i = 0; i < clip.getItemCount(); i++) {
                Uri uri = clip.getItemAt(i).getUri();
                if (uri != null) {
                    uris.add(uri.toString());
                    names.add(queryName(uri));
                }
            }
        } else if (data.getData() != null) {
            uris.add(data.getData().toString());
            names.add(queryName(data.getData()));
        }
        for (int i = 0; i < uris.size(); i++) {
            try {
                getContentResolver().takePersistableUriPermission(
                        Uri.parse(uris.get(i)), Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Throwable ignored) {
            }
        }
        final int n = lib.addManualUris(selectedCat, uris, names);
        if (n > 0) {
            lib.dirty = true;
            Util.toast(this, "已导入 " + n + " 个视频到「" + nameOf(selectedCat) + "」");
        } else {
            Util.toast(this, "这些视频已经在片库里了");
        }
    }

    private String queryName(Uri uri) {
        android.database.Cursor cursor = null;
        try {
            cursor = getContentResolver().query(uri, null, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndex("_display_name");
                if (idx >= 0) {
                    String s = cursor.getString(idx);
                    if (!TextUtils.isEmpty(s)) {
                        return stripExt(s);
                    }
                }
            }
        } catch (Throwable ignored) {
        } finally {
            if (cursor != null) {
                try {
                    cursor.close();
                } catch (Throwable ignored) {
                }
            }
        }
        return "导入视频";
    }

    private static String stripExt(String s) {
        if (s == null) {
            return "";
        }
        int dot = s.lastIndexOf('.');
        return dot > 0 ? s.substring(0, dot) : s;
    }

    private String buildSourceStatus() {
        StringBuilder sb = new StringBuilder("在线源状态（✕ = 按「不对」次数 · 成/败 = 解析成功与失败次数）\n");
        for (int i = 0; i < lib.categories.size(); i++) {
            Category c = lib.categories.get(i);
            if (!c.isApi()) {
                continue;
            }
            sb.append("· ").append(c.name).append("：");
            for (int j = 0; j < c.apis.length; j++) {
                if (j > 0) {
                    sb.append("、");
                }
                String api = c.apis[j];
                sb.append(Util.nameFromUrl(api).replace(".php", ""));
                int d = lib.dislikes(api);
                int[] st = lib.apiStat(api);
                if (d > 0) {
                    sb.append(" ✕").append(d);
                }
                if (st[0] + st[1] > 0) {
                    sb.append(" ").append(st[0]).append("成/").append(st[1]).append("败");
                }
            }
            sb.append("\n");
        }
        if (!TextUtils.isEmpty(lib.getSourceUrl())) {
            sb.append("\n自定义源：").append(lib.getSourceUrl());
        }
        return sb.toString();
    }

    private String nameOf(String key) {
        Category c = lib.category(key);
        return c == null ? key : c.name;
    }

    private void pickCategory() {
        final List<Category> list = new ArrayList<Category>();
        for (int i = 0; i < lib.categories.size(); i++) {
            Category c = lib.categories.get(i);
            if (!c.isSpecial()) {
                list.add(c);
            }
        }
        // 隐藏分类也允许手动塞链接
        String[] hiddenKeys = Hidden.keys();
        for (int i = 0; i < hiddenKeys.length; i++) {
            Category h = Hidden.safeGet(this, hiddenKeys[i]);
            if (h != null && !"hidden_vault".equals(hiddenKeys[i])) {
                list.add(h);
            }
        }
        String[] names = new String[list.size()];
        for (int i = 0; i < list.size(); i++) {
            names[i] = list.get(i).name;
        }
        new AlertDialog.Builder(this)
                .setTitle("选择分类")
                .setItems(names, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        selectedCat = list.get(which).key;
                        tvPick.setText("分类：" + list.get(which).name);
                    }
                })
                .show();
    }
}