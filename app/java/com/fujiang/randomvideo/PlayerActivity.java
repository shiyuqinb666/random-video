package com.fujiang.randomvideo;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.VideoView;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class PlayerActivity extends Activity {
    private static final String EXTRA_CAT = "cat";
    private static final String EXTRA_URL = "url";
    private static final String EXTRA_API = "api";

    private static final int BARS_AUTO_HIDE_MS = 3200;
    private static final int TICK_MS = 500;
    /** 进程内的断点续播位置（同一支视频再打开会接着播）。 */
    private static final Map<String, Integer> POSITIONS = new HashMap<String, Integer>();

    private VideoView vv;
    private SeekBar seek;
    private ProgressBar pb;
    private TextView tvTitle;
    private TextView tvSource;
    private TextView tvTime;
    private TextView tvLoop;
    private ImageButton btnFav;
    private LinearLayout topBar;
    private LinearLayout bottomBar;
    private View root;
    private GestureDetector gesture;

    private Library lib;
    private Video current;
    private String currentUrl;
    private String catKey = Library.C_ALL;
    private String lastApi;

    private boolean apiMode = false;
    private boolean autoNext = true;
    private boolean dragging = false;
    private boolean barsShown = true;
    private boolean resumed = false;

    private int apiCursor = 0;
    private int apiFail = 0;
    private int playCount = 0;
    private int generation = 0;
    private int lastVideoWidth = 0;
    private int lastVideoHeight = 0;
    private View btnDownload;

    private final Set<String> badApis = Collections.synchronizedSet(new HashSet<String>());
    private volatile String prefetchedUrl;
    private volatile String prefetchedApi;
    private volatile String prefetchedTitle;
    private volatile String prefetchedCover;
    private volatile boolean prefetching = false;

    private final Handler ui = new Handler(Looper.getMainLooper());

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            updateProgress();
            if (resumed) {
                ui.postDelayed(this, TICK_MS);
            }
        }
    };

    private final Runnable hideBars = new Runnable() {
        @Override
        public void run() {
            if (vv != null && vv.isPlaying()) {
                setBarsVisible(false);
            }
        }
    };

    public static void start(Context context, String catKey, String url) {
        Intent intent = new Intent(context, PlayerActivity.class);
        intent.putExtra(EXTRA_CAT, catKey);
        intent.putExtra(EXTRA_URL, url);
        context.startActivity(intent);
    }

    public static void startApi(Context context, String catKey) {
        Intent intent = new Intent(context, PlayerActivity.class);
        intent.putExtra(EXTRA_CAT, catKey);
        intent.putExtra(EXTRA_API, true);
        context.startActivity(intent);
    }

    // ------------------------------------------------------------ 生命周期

    @Override
    protected void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_player);

        lib = Library.get(this);
        String cat = getIntent().getStringExtra(EXTRA_CAT);
        catKey = cat == null ? Library.C_ALL : cat;
        currentUrl = getIntent().getStringExtra(EXTRA_URL);
        apiMode = getIntent().getBooleanExtra(EXTRA_API, false);

        root = getWindow().getDecorView();
        vv = (VideoView) findViewById(R.id.video);
        seek = (SeekBar) findViewById(R.id.seek);
        pb = (ProgressBar) findViewById(R.id.pb_loading);
        tvTitle = (TextView) findViewById(R.id.tv_title);
        tvSource = (TextView) findViewById(R.id.tv_source);
        tvTime = (TextView) findViewById(R.id.tv_time);
        tvLoop = (TextView) findViewById(R.id.tv_loop);
        btnFav = (ImageButton) findViewById(R.id.btn_fav);
        topBar = (LinearLayout) findViewById(R.id.top_bar);
        bottomBar = (LinearLayout) findViewById(R.id.bottom_bar);

        bindControls();
        injectDownloadButton();
        bindGestures();
        bindPlayer();

        pb.setVisibility(View.VISIBLE);
        if (apiMode) {
            Category c = lib.category(catKey);
            tvTitle.setText(c == null ? "在线随机" : c.name);
            tvLoop.setText(autoNext ? "自动连播 开" : "自动连播 关");
            playNextApi(true);
            return;
        }
        tvLoop.setText(autoNext ? "自动连播 开" : "自动连播 关");
        if (currentUrl != null) {
            playResolved(currentUrl);
        } else {
            nextRandom(false);
        }
    }

    private void bindControls() {
        findViewById(R.id.btn_back).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        btnFav.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (current == null) {
                    return;
                }
                boolean added = lib.toggleFav(current);
                refreshFavIcon();
                Util.toast(PlayerActivity.this, added ? "已收藏 💗" : "已取消收藏");
            }
        });
        findViewById(R.id.btn_dislike).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (currentUrl != null) {
                    lib.noteRecent(catKey, currentUrl);
                }
                if (lastApi != null) {
                    lib.addDislike(lastApi);
                    badApis.add(lastApi);
                    if (lib.dislikes(lastApi) == 3) {
                        Util.toast(PlayerActivity.this, "这个源老串味，已把它排到后面");
                    } else {
                        Util.toast(PlayerActivity.this, "记下了，换一条");
                    }
                }
                prefetchedUrl = null;
                nextRandom(true);
            }
        });
        findViewById(R.id.btn_next).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                nextRandom(true);
            }
        });
        findViewById(R.id.btn_loop).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                autoNext = !autoNext;
                tvLoop.setText(autoNext ? "自动连播 开" : "自动连播 关");
            }
        });
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (fromUser) {
                    int dur = currentDuration();
                    if (dur > 0) {
                        tvTime.setText(Util.fmtTime((int) ((long) dur * progress / 1000L)) + " / " + Util.fmtTime(dur));
                    }
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
                dragging = true;
                ui.removeCallbacks(hideBars);
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                dragging = false;
                int dur = currentDuration();
                if (dur > 0) {
                    try {
                        vv.seekTo((int) ((long) dur * bar.getProgress() / 1000L));
                    } catch (Throwable ignored) {
                    }
                }
                scheduleHideBars();
            }
        });
    }

    /** 在底部栏里插一个「⬇ 下载」按钮（不改布局资源，纯代码注入）。 */
    private void injectDownloadButton() {
        try {
            if (bottomBar == null) {
                return;
            }
            for (int i = 0; i < bottomBar.getChildCount(); i++) {
                View row = bottomBar.getChildAt(i);
                if (row instanceof ViewGroup) {
                    ViewGroup g = (ViewGroup) row;
                    for (int j = 0; j < g.getChildCount(); j++) {
                        if (g.getChildAt(j) != null && "dl_btn".equals(g.getChildAt(j).getTag())) {
                            return;
                        }
                    }
                }
            }
            // 底部栏第二行：seek / 时间 / 不对 / 连播 / 下一条
            ViewGroup row = null;
            for (int i = 0; i < bottomBar.getChildCount(); i++) {
                View child = bottomBar.getChildAt(i);
                if (child instanceof LinearLayout) {
                    LinearLayout ll = (LinearLayout) child;
                    if (ll.getOrientation() == LinearLayout.HORIZONTAL && ll.getChildCount() > 2) {
                        row = ll;
                    }
                }
            }
            if (row == null) {
                return;
            }
            LinearLayout btn = new LinearLayout(this);
            btn.setTag("dl_btn");
            btn.setOrientation(LinearLayout.HORIZONTAL);
            btn.setGravity(android.view.Gravity.CENTER);
            btn.setBackgroundResource(R.drawable.bg_btn_ghost);
            btn.setClickable(true);
            btn.setFocusable(true);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, Util.dp(this, 38f));
            lp.rightMargin = Util.dp(this, 8f);
            TextView label = new TextView(this);
            label.setText("⬇ 下载");
            label.setTextSize(11f);
            label.setTextColor(0xccffffff);
            btn.addView(label);
            btn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    downloadCurrent();
                }
            });
            // 插在「🚫 不对」和「自动连播」之间，顺序更顺
            int at = Math.max(0, row.getChildCount() - 2);
            row.addView(btn, at, lp);
            this.btnDownload = btn;
        } catch (Throwable th) {
            IO.log("injectDownload " + th);
        }
    }

    /** 下载当前这条。在线直链走系统下载；本地内容已经在手机上，不用下。 */
    private void downloadCurrent() {
        if (currentUrl == null) {
            Util.toast(this, "还没有正在播的视频");
            return;
        }
        if (!Downloader.canDownload(currentUrl)) {
            Util.toast(this, "这条已经在手机里了（" + (current == null ? "本地" : current.sourceLabel) + "）");
            return;
        }
        String title = current == null ? Util.nameFromUrl(currentUrl) : current.title;
        Category cat = lib.category(catKey);
        String source = cat == null ? (apiMode ? "在线源" : "在线") : cat.name;
        if (lastApi != null) {
            source = source + " · " + Util.nameFromUrl(lastApi);
        }
        if (Downloader.start(this, currentUrl, title, source) > 0) {
            Util.toast(this, "开始下载 → " + Downloader.dirHint() + "\n进度在通知栏，历史记录里也能看");
        } else {
            Util.toast(this, "下载没启动成功，换个源再试");
        }
    }

    private void showLongPressMenu() {
        final String[] items = new String[]{"⬇ 下载这条", "复制链接", current != null && lib.isFav(current) ? "取消收藏" : "收藏这条", "打开" + Downloader.dirHint()};
        new android.app.AlertDialog.Builder(this)
                .setTitle(current == null ? "当前视频" : Util.ellipsis(current.title, 30))
                .setItems(items, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        if (which == 0) {
                            downloadCurrent();
                        } else if (which == 1) {
                            copyUrl();
                        } else if (which == 2) {
                            if (current != null) {
                                boolean added = lib.toggleFav(current);
                                refreshFavIcon();
                                Util.toast(PlayerActivity.this, added ? "已收藏 💗" : "已取消收藏");
                            }
                        } else {
                            if (!Downloader.openDownloads(PlayerActivity.this)) {
                                Util.toast(PlayerActivity.this, "文件在 " + Downloader.dirHint());
                            }
                        }
                    }
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    private void copyUrl() {
        if (currentUrl == null) {
            return;
        }
        try {
            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                    getSystemService(CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(android.content.ClipData.newPlainText("url", currentUrl));
                Util.toast(this, "链接已复制");
            }
        } catch (Throwable ignored) {
        }
    }

    private void bindGestures() {
        gesture = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                setBarsVisible(!barsShown);
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                int w = root.getWidth();
                float x = e.getX();
                try {
                    if (x < w / 3f) {
                        int pos = Math.max(0, vv.getCurrentPosition() - 10000);
                        vv.seekTo(pos);
                        Util.toast(PlayerActivity.this, "◀◀ 后退 10 秒");
                    } else if (x > w * 2f / 3f) {
                        int dur = currentDuration();
                        int target = vv.getCurrentPosition() + 10000;
                        if (dur > 0 && target > dur - 1000) {
                            target = Math.max(0, dur - 1000);
                        }
                        vv.seekTo(target);
                        Util.toast(PlayerActivity.this, "快进 10 秒 ▶▶");
                    } else {
                        if (vv.isPlaying()) {
                            vv.pause();
                            Util.toast(PlayerActivity.this, "已暂停");
                            setBarsVisible(true);
                        } else {
                            vv.start();
                            Util.toast(PlayerActivity.this, "继续播放");
                            scheduleHideBars();
                        }
                    }
                    updateProgress();
                } catch (Throwable ignored) {
                }
                return true;
            }
        });
        View.OnTouchListener touch = new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                return gesture.onTouchEvent(event);
            }
        };
        vv.setOnTouchListener(touch);
        root.setOnTouchListener(touch);
        vv.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // 手势已经处理，这里留空避免双击被 View 的点击吃掉
            }
        });
        vv.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                showLongPressMenu();
                return true;
            }
        });
    }

    private void bindPlayer() {
        vv.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
            @Override
            public void onPrepared(final MediaPlayer mp) {
                pb.setVisibility(View.GONE);
                try {
                    mp.setLooping(false);
                } catch (Throwable ignored) {
                }
                mp.setOnVideoSizeChangedListener(new MediaPlayer.OnVideoSizeChangedListener() {
                    @Override
                    public void onVideoSizeChanged(MediaPlayer player, int width, int height) {
                        fitAspect(width, height);
                    }
                });
                fitAspect(mp.getVideoWidth(), mp.getVideoHeight());
                Integer saved = currentUrl == null ? null : POSITIONS.get(currentUrl);
                if (saved != null && saved.intValue() > 2000) {
                    try {
                        vv.seekTo(saved.intValue());
                    } catch (Throwable ignored) {
                    }
                }
                try {
                    vv.start();
                } catch (Throwable ignored) {
                }
                updateProgress();
                scheduleHideBars();
            }
        });
        vv.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
            @Override
            public void onCompletion(MediaPlayer mp) {
                if (currentUrl != null) {
                    POSITIONS.remove(currentUrl);
                }
                if (autoNext) {
                    nextRandom(false);
                } else {
                    setBarsVisible(true);
                    Util.toast(PlayerActivity.this, "播放结束");
                }
            }
        });
        vv.setOnErrorListener(new MediaPlayer.OnErrorListener() {
            @Override
            public boolean onError(MediaPlayer mp, int what, int extra) {
                pb.setVisibility(View.GONE);
                final String failed = currentUrl;
                boolean stream = failed != null && IO.lower(failed).contains(".m3u8");
                if (stream) {
                    Util.toastOnce(PlayerActivity.this, "这条是 m3u8 流媒体，系统播放器解不了，换一条");
                } else {
                    Util.toastOnce(PlayerActivity.this, "这条播不了，换一条…");
                }
                if (lastApi != null) {
                    badApis.add(lastApi);
                    if (apiMode) {
                        lib.recordApiFail(lastApi);
                    }
                }
                ui.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (!isFinishing()) {
                            nextRandom(false);
                        }
                    }
                }, 800L);
                return true;
            }
        });
    }

    // ------------------------------------------------------------ 播放

    private void playResolved(String url) {
        playResolved(url, null, null);
    }

    /**
     * @param realTitle 源站给的真实标题（快手随机那类接口会给），没有就传 null
     * @param realCover 源站给的封面图，没有就传 null
     */
    private void playResolved(String url, String realTitle, String realCover) {
        if (url == null || url.length() == 0) {
            return;
        }
        currentUrl = url;
        Video found = lib.find(url);
        if (found == null) {
            String name;
            if (realTitle != null && realTitle.length() > 0) {
                // 源站标题优先，这才是「视频原本的标题」
                name = realTitle;
            } else if (apiMode) {
                Category c = lib.category(catKey);
                String base = c == null ? "在线" : c.name;
                playCount++;
                name = base + " · 第 " + playCount + " 条";
            } else {
                name = Util.nameFromUrl(url);
            }
            found = new Video(name, url, catKey, apiMode ? "内置在线源" : "在线", 0L, false);
            if (realCover != null && realCover.length() > 0) {
                found.cover = realCover;
            }
        } else if (realTitle != null && realTitle.length() > 0) {
            found.title = realTitle;
            if (realCover != null && realCover.length() > 0) {
                found.cover = realCover;
            }
        }
        current = found;
        tvTitle.setText(found.title);
        tvSource.setText(found.metaText());
        if (apiMode) {
            int count = ApiResolver.countOf(lastApi);
            String label = "在线源 " + (lastApi == null ? "" : Util.nameFromUrl(lastApi));
            tvSource.setText(count > 0 ? label + " · 片库 " + count : label);
        }
        refreshFavIcon();
        pb.setVisibility(View.VISIBLE);
        seek.setProgress(0);
        tvTime.setText("00:00 / 00:00");
        try {
            vv.stopPlayback();
            vv.setVideoURI(Uri.parse(url));
            vv.requestFocus();
            vv.start();
        } catch (Throwable th) {
            pb.setVisibility(View.GONE);
            Util.toast(this, "播放失败：" + th.getClass().getSimpleName());
        }
        lib.addHistory(found);
        ui.removeCallbacks(tick);
        if (resumed) {
            ui.post(tick);
        }
        setBarsVisible(true);
        scheduleHideBars();
    }

    private void nextRandom(boolean userAction) {
        if (apiMode) {
            playNextApi(userAction);
            return;
        }
        if (currentUrl != null) {
            lib.noteRecent(catKey, currentUrl);
        }
        Video v = lib.random(catKey, currentUrl);
        if (v == null) {
            if (userAction) {
                Util.toast(this, "这个分类里没有更多了");
            }
            return;
        }
        playResolved(v.url);
    }

    // ------------------------------------------------------------ 在线源 + 预取

    /** 下一个要试的接口地址（跳过本次会话里已经失败的）。 */
    private synchronized String nextApiUrl() {
        String[] apis = lib.apisSorted(catKey);
        if (apis.length == 0) {
            return null;
        }
        for (int i = 0; i < apis.length; i++) {
            String u = apis[apiCursor % apis.length];
            apiCursor++;
            if (apiCursor > 100000) {
                apiCursor = 0;
            }
            if (!badApis.contains(u)) {
                return u;
            }
        }
        badApis.clear();
        return apis[apiCursor++ % apis.length];
    }

    private void playNextApi(boolean userAction) {
        String[] apis = lib.apisSorted(catKey);
        if (apis.length == 0) {
            pb.setVisibility(View.GONE);
            Util.toast(this, "这个分类暂时没有内置源");
            return;
        }
        final int gen = ++generation;
        String readyUrl = prefetchedUrl;
        String readyApi = prefetchedApi;
        String readyTitle = prefetchedTitle;
        String readyCover = prefetchedCover;
        prefetchedUrl = null;
        prefetchedApi = null;
        prefetchedTitle = null;
        prefetchedCover = null;
        if (readyUrl != null && readyApi != null && !badApis.contains(readyApi)) {
            lastApi = readyApi;
            playResolved(readyUrl, readyTitle, readyCover);
            prefetchNext();
            return;
        }
        final String api = nextApiUrl();
        if (api == null) {
            return;
        }
        lastApi = api;
        if (!api.startsWith("http")) {
            // 私密柜里的 content:// 或本地 file:// 直接播
            playResolved(api);
            prefetchNext();
            return;
        }
        pb.setVisibility(View.VISIBLE);
        tvSource.setText("正在连接 " + Util.nameFromUrl(api) + " …");
        if (!Util.hasNetwork(this) && userAction) {
            Util.toastOnce(this, "好像没有网络");
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                final ApiResolver.Meta meta = ApiResolver.resolveFull(api);
                final String url = meta == null ? null : meta.url;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (gen != generation || isFinishing()) {
                            return;
                        }
                        if (url != null && url.length() > 8) {
                            apiFail = 0;
                            lib.recordApiOk(api);
                            playResolved(url, meta.title, meta.cover);
                            prefetchNext();
                            return;
                        }
                        apiFail++;
                        lib.recordApiFail(api);
                        badApis.add(api);
                        if (apiFail >= 6) {
                            pb.setVisibility(View.GONE);
                            Util.toast(PlayerActivity.this, "在线源都连不上，检查下网络或稍后再试");
                            return;
                        }
                        // 立刻换下一个源，但不打断用户正在看的内容
                        if (currentUrl == null) {
                            playNextApi(false);
                        } else {
                            Util.toastOnce(PlayerActivity.this, "这个源没给视频，换一个…");
                            playNextApi(false);
                        }
                    }
                });
            }
        }, "api-resolver").start();
    }

    /** 后台先把下一条解析好，用户点「下一条」就不用等。 */
    private void prefetchNext() {
        if (!apiMode || prefetching) {
            return;
        }
        prefetching = true;
        final int gen = generation;
        new Thread(new Runnable() {
            @Override
            public void run() {
                String api = null;
                String url = null;
                String preTitle = null;
                String preCover = null;
                try {
                    api = nextApiUrl();
                    if (api != null && api.equals(lastApi)) {
                        api = nextApiUrl();
                    }
                    if (api != null && api.startsWith("http")) {
                        ApiResolver.Meta m = ApiResolver.resolveFull(api);
                        if (m != null) {
                            url = m.url;
                            preTitle = m.title;
                            preCover = m.cover;
                        }
                    } else if (api != null) {
                        url = api; // content:// / file:// 不需要解析
                    }
                } catch (Throwable th) {
                    IO.log("prefetch " + th);
                }
                final String fApi = api;
                final String fUrl = url;
                final String fTitle = preTitle;
                final String fCover = preCover;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        prefetching = false;
                        if (gen == generation && fUrl != null && !isFinishing()) {
                            prefetchedUrl = fUrl;
                            prefetchedApi = fApi;
                            prefetchedTitle = fTitle;
                            prefetchedCover = fCover;
                        }
                    }
                });
            }
        }, "api-prefetch").start();
    }

    // ------------------------------------------------------------ 画面 / 进度

    /** 保持原始比例，居中留黑边，不再把画面拉变形。 */
    @Override
    public void onConfigurationChanged(android.content.res.Configuration config) {
        super.onConfigurationChanged(config);
        // 清单里声明了 configChanges，Activity 不会重建，这里手动重新按比例摆放
        if (lastVideoWidth > 0 && lastVideoHeight > 0) {
            final int w = lastVideoWidth;
            final int h = lastVideoHeight;
            root.post(new Runnable() {
                @Override
                public void run() {
                    fitAspect(w, h);
                }
            });
        }
    }

    private void fitAspect(int videoWidth, int videoHeight) {
        if (videoWidth <= 0 || videoHeight <= 0) {
            return;
        }
        lastVideoWidth = videoWidth;
        lastVideoHeight = videoHeight;
        int boxW = root.getWidth();
        int boxH = root.getHeight();
        if (boxW == 0 || boxH == 0) {
            DisplayMetrics dm = getResources().getDisplayMetrics();
            boxW = dm.widthPixels;
            boxH = dm.heightPixels;
        }
        float scale = Math.min((float) boxW / videoWidth, (float) boxH / videoHeight);
        int w = Math.round(videoWidth * scale);
        int h = Math.round(videoHeight * scale);
        ViewGroup.LayoutParams lp = vv.getLayoutParams();
        if (lp != null) {
            lp.width = w;
            lp.height = h;
            vv.setLayoutParams(lp);
        }
    }

    private int currentDuration() {
        int d;
        try {
            d = vv.getDuration();
        } catch (Throwable th) {
            d = 0;
        }
        if (d > 0) {
            return d;
        }
        return current != null && current.durationMs > 0 ? (int) current.durationMs : 0;
    }

    private void updateProgress() {
        if (vv == null) {
            return;
        }
        int pos;
        try {
            pos = vv.getCurrentPosition();
        } catch (Throwable th) {
            pos = 0;
        }
        int dur = currentDuration();
        if (dur > 0) {
            if (!dragging) {
                seek.setProgress((int) ((long) pos * 1000L / dur));
            }
            tvTime.setText(Util.fmtTime(pos) + " / " + Util.fmtTime(dur));
        }
    }

    private void refreshFavIcon() {
        btnFav.setColorFilter(current != null && lib.isFav(current) ? 0xffff4f9a : 0x33ffffff);
    }

    private void setBarsVisible(boolean visible) {
        barsShown = visible;
        int v = visible ? View.VISIBLE : View.GONE;
        topBar.setVisibility(v);
        bottomBar.setVisibility(v);
        if (visible) {
            scheduleHideBars();
        } else {
            ui.removeCallbacks(hideBars);
        }
    }

    private void scheduleHideBars() {
        ui.removeCallbacks(hideBars);
        ui.postDelayed(hideBars, BARS_AUTO_HIDE_MS);
    }

    // ------------------------------------------------------------ 前后台

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        ui.removeCallbacks(tick);
        ui.post(tick);
        try {
            if (vv != null && currentUrl != null && !vv.isPlaying()) {
                vv.start();
            }
        } catch (Throwable ignored) {
        }
        scheduleHideBars();
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        ui.removeCallbacks(tick);
        ui.removeCallbacks(hideBars);
        try {
            if (vv != null && vv.isPlaying()) {
                int pos = vv.getCurrentPosition();
                if (currentUrl != null && pos > 2000) {
                    POSITIONS.put(currentUrl, Integer.valueOf(pos));
                }
                vv.pause();
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacks(tick);
        ui.removeCallbacks(hideBars);
        generation++;
        try {
            if (vv != null && vv.isPlaying()) {
                int pos = vv.getCurrentPosition();
                if (currentUrl != null && pos > 2000) {
                    POSITIONS.put(currentUrl, Integer.valueOf(pos));
                }
            }
            vv.stopPlayback();
        } catch (Throwable ignored) {
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        super.onBackPressed();
    }
}