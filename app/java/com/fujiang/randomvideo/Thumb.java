package com.fujiang.randomvideo;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.File;
import java.io.FileOutputStream;
import java.util.HashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 封面缩略图：抓视频首帧，落盘缓存，内存 LRU。
 *
 * · 本地 / 私密柜 / 下载好的文件（content:// file://）直接读帧，很快
 * · 在线 mp4 用 MediaMetadataRetriever 走 http 读帧（只取靠前的关键帧，流量很小）
 * · 成功的结果按 URL 哈希存到 cache/thumbs/<hash>.jpg，失败的记一个标记文件避免反复重试
 * · 只在主线程回填，并且用 setTag(URL) 校验，滚动复用不会串图
 */
public final class Thumb {
    private static final String UA = "Mozilla/5.0 (Linux; Android 13; SM-G991B) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/119.0.0.0 Mobile Safari/537.36";

    private static final int MAX_MEM_BYTES = 12 * 1024 * 1024;
    private static final int TARGET_W = 320;
    private static final int TARGET_H = 180;

    private static final Handler UI = new Handler(Looper.getMainLooper());
    /** 本地文件读帧很快，单独一个池；在线读帧慢且可能卡住，只给 1 个线程，互不拖累。 */
    private static final ExecutorService POOL_LOCAL = Executors.newFixedThreadPool(2);
    private static final ExecutorService POOL_REMOTE = Executors.newSingleThreadExecutor();
    private static final HashMap<String, Integer> FAILED = new HashMap<String, Integer>();
    private static final java.util.concurrent.atomic.AtomicInteger INFLIGHT =
            new java.util.concurrent.atomic.AtomicInteger(0);
    private static final int MAX_INFLIGHT = 16;

    private static LruCache<String, Bitmap> cache;

    private Thumb() {
    }

    private static synchronized LruCache<String, Bitmap> mem() {
        if (cache == null) {
            cache = new LruCache<String, Bitmap>(MAX_MEM_BYTES) {
                @Override
                protected int sizeOf(String key, Bitmap value) {
                    return value == null ? 0 : value.getByteCount();
                }
            };
        }
        return cache;
    }

    private static File cacheDir(Context c) {
        File dir = new File(c.getCacheDir(), "thumbs");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    private static String keyOf(String url) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] d = md.digest(url.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < d.length; i++) {
                sb.append(String.format("%02x", Byte.valueOf(d[i])));
            }
            return sb.toString();
        } catch (Throwable th) {
            return Integer.toHexString(url.hashCode());
        }
    }

    /** 先把封面挂到 ImageView 上；没有缓存就后台生成。 */
    public static void attach(Context context, final ImageView view, final String url) {
        if (view == null || url == null || url.length() == 0) {
            return;
        }
        final Context app = context.getApplicationContext();
        view.setTag(url);
        Bitmap hit = mem().get(url);
        if (hit != null) {
            view.setImageBitmap(hit);
            return;
        }
        view.setImageResource(R.drawable.ic_video);
        view.setColorFilter(0x33ffffff);
        final boolean remote = IO.lower(url).startsWith("http");
        final ExecutorService pool = remote ? POOL_REMOTE : POOL_LOCAL;
        final File f = new File(cacheDir(app), keyOf(url) + ".jpg");
        if (f.exists() && f.length() > 0) {
            pool.execute(new Runnable() {
                @Override
                public void run() {
                    final Bitmap bmp = decodeFile(f);
                    if (bmp == null) {
                        return;
                    }
                    mem().put(url, bmp);
                    UI.post(new Runnable() {
                        @Override
                        public void run() {
                            if (url.equals(view.getTag())) {
                                view.clearColorFilter();
                                view.setImageBitmap(bmp);
                            }
                        }
                    });
                }
            });
            return;
        }
        synchronized (FAILED) {
            Integer bad = FAILED.get(url);
            if (bad != null) {
                return; // 已经失败过，别反复重试
            }
        }
        if (INFLIGHT.get() >= MAX_INFLIGHT) {
            return; // 队列太挤，先不排，等下次滚动再说
        }
        INFLIGHT.incrementAndGet();
        pool.execute(new Runnable() {
            @Override
            public void run() {
                Bitmap grabbed = null;
                try {
                    grabbed = grab(app, url);
                } finally {
                    INFLIGHT.decrementAndGet();
                }
                final Bitmap bmp = grabbed;
                if (bmp == null) {
                    synchronized (FAILED) {
                        FAILED.put(url, Integer.valueOf(1));
                    }
                    return;
                }
                mem().put(url, bmp);
                saveJpeg(f, bmp);
                UI.post(new Runnable() {
                    @Override
                    public void run() {
                        if (url.equals(view.getTag())) {
                            view.clearColorFilter();
                            view.setImageBitmap(bmp);
                        }
                    }
                });
            }
        });
    }

    /** 判断是不是图片直链（源站封面），这类直接下载解码，不用抓帧。 */
    static boolean isImageUrl(String url) {
        if (url == null) {
            return false;
        }
        String s = IO.lower(url);
        int q = s.indexOf('?');
        if (q > 0) {
            s = s.substring(0, q);
        }
        return s.endsWith(".jpg") || s.endsWith(".jpeg") || s.endsWith(".png")
                || s.endsWith(".webp") || s.endsWith(".bmp");
    }

    /** 下载并解码一张网络图片。 */
    static Bitmap download(String url) {
        java.io.InputStream in = null;
        try {
            java.net.HttpURLConnection conn =
                    (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(8000);
            conn.setRequestProperty("User-Agent", UA);
            conn.setRequestProperty("Referer", "https://api.yujn.cn/");
            if (conn.getResponseCode() != 200) {
                return null;
            }
            in = conn.getInputStream();
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = 2;
            Bitmap bmp = BitmapFactory.decodeStream(in, null, o);
            return bmp == null ? null : scale(bmp);
        } catch (Throwable th) {
            return null;
        } finally {
            IO.close(in);
        }
    }

    /** 同步取一帧（调用方自己在后台线程里用）。 */
    public static Bitmap grab(Context context, String url) {
        if (isImageUrl(url)) {
            return download(url);
        }
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            if (IO.lower(url).startsWith("http")) {
                HashMap<String, String> headers = new HashMap<String, String>();
                headers.put("User-Agent", UA);
                headers.put("Referer", "https://api.yujn.cn/");
                r.setDataSource(url, headers);
            } else {
                r.setDataSource(context, Uri.parse(url));
            }
            Bitmap bmp = null;
            try {
                bmp = r.getFrameAtTime(1000000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            } catch (Throwable ignored) {
            }
            if (bmp == null) {
                bmp = r.getFrameAtTime();
            }
            return bmp == null ? null : scale(bmp);
        } catch (Throwable th) {
            IO.log("thumb " + th);
            return null;
        } finally {
            try {
                r.release();
            } catch (Throwable ignored) {
            }
        }
    }

    private static Bitmap scale(Bitmap src) {
        try {
            int w = src.getWidth();
            int h = src.getHeight();
            if (w <= 0 || h <= 0) {
                return src;
            }
            float s = Math.min((float) TARGET_W / w, (float) TARGET_H / h);
            if (s >= 1f) {
                return src;
            }
            Bitmap out = Bitmap.createScaledBitmap(src,
                    Math.max(1, Math.round(w * s)), Math.max(1, Math.round(h * s)), true);
            if (out != src) {
                src.recycle();
            }
            return out;
        } catch (Throwable ignored) {
            return src;
        }
    }

    private static Bitmap decodeFile(File f) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getAbsolutePath(), o);
            int sample = 1;
            while (o.outWidth / sample > TARGET_W * 2 && sample < 8) {
                sample *= 2;
            }
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = sample;
            return BitmapFactory.decodeFile(f.getAbsolutePath(), o2);
        } catch (Throwable th) {
            return null;
        }
    }

    private static void saveJpeg(File f, Bitmap bmp) {
        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(f);
            bmp.compress(Bitmap.CompressFormat.JPEG, 82, fos);
            fos.flush();
        } catch (Throwable ignored) {
        } finally {
            IO.close(fos);
        }
    }

    /** 清缓存（设置里或历史页清空时调用）。 */
    public static void clear(Context c) {
        mem().evictAll();
        synchronized (FAILED) {
            FAILED.clear();
        }
        try {
            File[] files = cacheDir(c).listFiles();
            if (files != null) {
                for (int i = 0; i < files.length; i++) {
                    files[i].delete();
                }
            }
        } catch (Throwable ignored) {
        }
    }
}