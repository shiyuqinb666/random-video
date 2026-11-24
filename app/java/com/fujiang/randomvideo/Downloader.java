package com.fujiang.randomvideo;

import android.app.DownloadManager;
import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.text.TextUtils;


/**
 * 在线视频下载：交给系统 DownloadManager，通知栏有进度、断网自动续、不卡界面。
 * 落盘位置：Movies/随机视界/xxx.mp4 —— 下次「重新扫描」就能在本地区里看到。
 */
public final class Downloader {
    private static final String DIR = "随机视界";
    private static final String UA = "Mozilla/5.0 (Linux; Android 13; SM-G991B) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/119.0.0.0 Mobile Safari/537.36";

    private Downloader() {
    }

    public static boolean canDownload(String url) {
        return url != null && (url.startsWith("http://") || url.startsWith("https://"));
    }

    private static final String K_RECORDS = "dl_records";

    /**
     * @return 成功入队返回任务 id，失败返回 0
     */
    public static long start(Context context, String url, String title) {
        return start(context, url, title, "");
    }

    /**
     * @param displayTitle 记录里展示的标题（干净，不带时间戳）
     * @param source       来源标注，例如分类名
     */
    public static long start(Context context, String url, String displayTitle, String source) {
        if (context == null || !canDownload(url)) {
            return 0L;
        }
        try {
            DownloadManager dm = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
            if (dm == null) {
                return 0L;
            }
            String shown = TextUtils.isEmpty(displayTitle) ? Util.nameFromUrl(url) : displayTitle;
            String name = uniqueName(context, shown, url);
            DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
            req.setTitle(shown);
            req.setDescription("随机视界 · 保存到 Movies/" + DIR);
            req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            req.setAllowedOverMetered(true);
            req.setAllowedOverRoaming(false);
            try {
                req.addRequestHeader("User-Agent", UA);
                req.addRequestHeader("Referer", "https://api.yujn.cn/");
            } catch (Throwable ignored) {
            }
            req.setDestinationInExternalPublicDir(Environment.DIRECTORY_MOVIES, DIR + "/" + name);
            long id = dm.enqueue(req);
            saveRecord(context, id, shown, name, url, source);
            USED_NAMES.add(name);
            return id;
        } catch (Throwable th) {
            IO.log("download error " + th);
            return 0L;
        }
    }

    // ------------------------------------------------------------ 下载记录

    private static android.content.SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences("random_video_prefs", 0);
    }

    private static void saveRecord(Context c, long id, String title, String fileName,
                                   String url, String source) {
        try {
            org.json.JSONArray arr = new org.json.JSONArray(prefs(c).getString(K_RECORDS, "[]"));
            org.json.JSONObject o = new org.json.JSONObject();
            o.put("id", id);
            o.put("t", title);
            o.put("f", fileName);
            o.put("s", source == null ? "" : source);
            o.put("u", url);
            o.put("ts", System.currentTimeMillis());
            arr.put(o);
            prefs(c).edit().putString(K_RECORDS, arr.toString()).apply();
        } catch (Throwable th) {
            IO.log("saveRecord " + th);
        }
    }

    /** 下载箱里的一条。 */
    public static final class Dl {
        public long id;
        public String title = "";
        public String fileName = "";
        public String source = "";
        public String url = "";
        public long time;
        public int status;
        public long done;
        public long total;
        public String localUri;

        public boolean finished() {
            return status == DownloadManager.STATUS_SUCCESSFUL;
        }

        public boolean failed() {
            return status == DownloadManager.STATUS_FAILED;
        }

        public int percent() {
            if (total <= 0) {
                return finished() ? 100 : 0;
            }
            int p = (int) (done * 100L / total);
            return Math.max(0, Math.min(100, p));
        }

        public String stateText() {
            if (finished()) {
                return "已下载";
            }
            if (failed()) {
                return "下载失败";
            }
            if (status == DownloadManager.STATUS_RUNNING || status == DownloadManager.STATUS_PENDING
                    || status == DownloadManager.STATUS_PAUSED) {
                return "下载中 " + percent() + "%";
            }
            return "等待中";
        }

        public String sizeText() {
            if (total <= 0) {
                return "";
            }
            return Util.fmtSize(total);
        }
    }

    /** 读下载记录，并按 DownloadManager 的真实状态补全。 */
    public static java.util.List<Dl> records(Context c) {
        java.util.List<Dl> out = new java.util.ArrayList<Dl>();
        if (c == null) {
            return out;
        }
        java.util.List<Long> ids = new java.util.ArrayList<Long>();
        java.util.HashMap<Long, Dl> map = new java.util.HashMap<Long, Dl>();
        try {
            org.json.JSONArray arr = new org.json.JSONArray(prefs(c).getString(K_RECORDS, "[]"));
            for (int i = arr.length() - 1; i >= 0; i--) {
                org.json.JSONObject o = arr.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                Dl d = new Dl();
                d.id = o.optLong("id", 0L);
                d.title = o.optString("t", "");
                d.fileName = o.optString("f", "");
                d.source = o.optString("s", "");
                d.url = o.optString("u", "");
                if (TextUtils.isEmpty(d.title)) {
                    d.title = titleFromFileName(d.fileName);
                }
                if (TextUtils.isEmpty(d.title)) {
                    d.title = Util.nameFromUrl(d.url);
                }
                d.time = o.optLong("ts", 0L);
                if (d.id == 0L) {
                    continue;
                }
                ids.add(Long.valueOf(d.id));
                map.put(Long.valueOf(d.id), d);
            }
        } catch (Throwable ignored) {
        }
        if (ids.isEmpty()) {
            return out;
        }
        DownloadManager dm = (DownloadManager) c.getSystemService(Context.DOWNLOAD_SERVICE);
        if (dm != null) {
            android.database.Cursor cur = null;
            try {
                long[] idArr = new long[ids.size()];
                for (int i = 0; i < ids.size(); i++) {
                    idArr[i] = ids.get(i).longValue();
                }
                DownloadManager.Query q = new DownloadManager.Query().setFilterById(idArr);
                cur = dm.query(q);
                while (cur != null && cur.moveToNext()) {
                    long id = cur.getLong(cur.getColumnIndex(DownloadManager.COLUMN_ID));
                    Dl d = map.get(Long.valueOf(id));
                    if (d == null) {
                        continue;
                    }
                    d.status = cur.getInt(cur.getColumnIndex(DownloadManager.COLUMN_STATUS));
                    d.done = cur.getLong(cur.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                    d.total = cur.getLong(cur.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
                    if (d.finished()) {
                        try {
                            android.net.Uri u = dm.getUriForDownloadedFile(id);
                            d.localUri = u == null ? null : u.toString();
                        } catch (Throwable ignored) {
                        }
                    }
                }
            } catch (Throwable th) {
                IO.log("query downloads " + th);
            } finally {
                if (cur != null) {
                    try {
                        cur.close();
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
        for (int i = 0; i < ids.size(); i++) {
            Dl d = map.get(ids.get(i));
            if (d != null) {
                out.add(d);
            }
        }
        return out;
    }

    /** 删掉一条下载记录（可选同时删掉文件）。 */
    public static void removeRecord(Context c, long id, boolean deleteFile) {
        if (c == null || id == 0L) {
            return;
        }
        try {
            if (deleteFile) {
                DownloadManager dm = (DownloadManager) c.getSystemService(Context.DOWNLOAD_SERVICE);
                if (dm != null) {
                    dm.remove(id);
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            org.json.JSONArray old = new org.json.JSONArray(prefs(c).getString(K_RECORDS, "[]"));
            org.json.JSONArray out = new org.json.JSONArray();
            for (int i = 0; i < old.length(); i++) {
                org.json.JSONObject o = old.optJSONObject(i);
                if (o == null || o.optLong("id", 0L) == id) {
                    continue;
                }
                out.put(o);
            }
            prefs(c).edit().putString(K_RECORDS, out.toString()).apply();
        } catch (Throwable ignored) {
        }
    }

    static String titleFromFileName(String fileName) {
        return Title.fromFileName(fileName);
    }

    public static int count(Context c) {
        return records(c).size();
    }

    /** 打开系统下载管理界面。 */
    public static boolean openDownloads(Context context) {
        try {
            android.content.Intent intent = new android.content.Intent(DownloadManager.ACTION_VIEW_DOWNLOADS);
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            return true;
        } catch (Throwable th) {
            return false;
        }
    }

    /** 记录里已经用过的文件名，避免同标题覆盖。 */
    private static final java.util.HashSet<String> USED_NAMES = new java.util.HashSet<String>();

    private static boolean usedName(Context c, String name) {
        if (name.length() == 0) {
            return false;
        }
        if (USED_NAMES.contains(name)) {
            return true;
        }
        try {
            org.json.JSONArray arr = new org.json.JSONArray(prefs(c).getString(K_RECORDS, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                org.json.JSONObject o = arr.optJSONObject(i);
                if (o != null && name.equals(o.optString("f", ""))) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static String uniqueName(Context c, String title, String url) {
        String clean = buildFileName(title, url, false);
        if (!usedName(c, clean)) {
            return clean;
        }
        return buildFileName(title, url, true);
    }

    static String buildFileName(String title, String url) {
        return buildFileName(title, url, true);
    }

    static String buildFileName(String title, String url, boolean withStamp) {
        String base = TextUtils.isEmpty(title) ? Util.nameFromUrl(url) : title;
        base = sanitize(base);
        String ext = extensionOf(url);
        if (base.length() == 0) {
            base = "视频";
        }
        if (base.length() > 60) {
            base = base.substring(0, 60);
        }
        if (!withStamp) {
            return base + ext;
        }
        // 重名时加短时间戳
        String stamp = Long.toHexString(System.currentTimeMillis() & 0xFFFFFFL);
        return base + "_" + stamp + ext;
    }

    private static String extensionOf(String url) {
        String s = IO.lower(url);
        int q = s.indexOf('?');
        if (q > 0) {
            s = s.substring(0, q);
        }
        if (s.endsWith(".mp4")) {
            return ".mp4";
        }
        if (s.endsWith(".m4v")) {
            return ".m4v";
        }
        if (s.endsWith(".mov")) {
            return ".mov";
        }
        if (s.endsWith(".webm")) {
            return ".webm";
        }
        if (s.endsWith(".mkv")) {
            return ".mkv";
        }
        if (s.endsWith(".flv")) {
            return ".flv";
        }
        if (s.endsWith(".ts")) {
            return ".ts";
        }
        if (s.endsWith(".m3u8")) {
            // 播放器解不了，但先按分片列表存下来
            return ".m3u8";
        }
        return ".mp4";
    }

    static String sanitize(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            boolean bad = c == '/' || c == '\\' || c == ':' || c == '*' || c == '?' || c == '"'
                    || c == '<' || c == '>' || c == '|' || c == '\n' || c == '\r' || c == '\t';
            sb.append(bad ? '_' : c);
        }
        return sb.toString().trim().replaceAll("_{2,}", "_").replaceAll("^_+|_+$", "");
    }

    public static String dirHint() {
        return "Movies/" + DIR + "/";
    }
}