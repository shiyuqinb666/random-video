package com.fujiang.randomvideo;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把「随机视频接口」解析成真正的视频直链。
 *
 * 相比 1.2 的版本：
 * 1. 支持多跳 302（原来只跟一跳，很多接口套了两层跳转就直接失败）；
 * 2. 先看 Content-Type，真正的 video/* 直接用，不再额外发一次探测请求；
 * 3. 文本/JSON 里抓候选链接时给候选打分排序（mp4 优先，m3u8 次之），
 *    并过滤掉封面图 / 头像 / 图标这类明显不是视频的地址；
 * 4. 支持 JSON 转义（\/）和 HTML 实体还原；
 * 5. 整条链路超时更短，失败可以更快换下一个源。
 */
public final class ApiResolver {
    private static final String UA = "Mozilla/5.0 (Linux; Android 13; SM-G991B) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/119.0.0.0 Mobile Safari/537.36";

    private static final int MAX_HOPS = 6;
    private static final int BODY_LIMIT = 512 * 1024;
    private static final int CONNECT_TIMEOUT = 3000;
    private static final int READ_TIMEOUT = 4500;
    /** 快通道（?type=json）超时更短：不支持的接口要尽快放弃 */
    private static final int FAST_CONNECT = 2500;
    private static final int FAST_READ = 3000;

    private static final Pattern P_VIDEO = Pattern.compile(
            "https?://[^\\s\"'<>\\\\\\]\\[\\)\\(，。]+\\.(?:mp4|m3u8|m4v|mov|webm|flv|ts|mkv)(?:\\?[^\\s\"'<>\\\\\\]\\[\\)\\(]*)?",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern P_ANY = Pattern.compile(
            "https?://[^\\s\"'<>\\\\\\]\\[\\)\\(，。]+", Pattern.CASE_INSENSITIVE);

    private static final String[] IMAGE_HINTS = {
            ".jpg", ".jpeg", ".png", ".gif", ".webp", ".bmp", ".svg", ".ico",
            "avatar", "cover", "thumb", "poster", "logo", "icon", "/img/", "qlogo", "headimg"
    };

    private ApiResolver() {
    }

    /** 解析结果：直链 + 源站真实标题 + 源站封面。 */
    public static final class Meta {
        public final String url;
        public final String title;
        public final String cover;
        public final int count;

        public Meta(String url, String title, String cover, int count) {
            this.url = url == null ? "" : url;
            this.title = title == null ? "" : title.trim();
            this.cover = cover == null ? "" : cover.trim();
            this.count = count;
        }

        public boolean hasTitle() {
            return this.title.length() > 0;
        }

        public boolean hasCover() {
            return this.cover.startsWith("http");
        }
    }

    /** 解析一个接口地址，成功返回可播放直链，失败返回 null。 */
    public static String resolve(String apiUrl) {
        Meta m = resolveFull(apiUrl);
        return m == null || m.url.length() < 8 ? null : m.url;
    }

    /**
     * 带元数据的解析：能拿到源站给的 title / cover 就一并返回
     * （快手随机 ks_sj 那类接口直接给 title + cover + video）。
     */
    public static Meta resolveFull(String apiUrl) {
        if (apiUrl == null || apiUrl.length() < 8) {
            return null;
        }
        if (looksLikeDirectVideo(apiUrl)) {
            return new Meta(apiUrl, "", "", 0);
        }
        // 第一发先顺序试：普通接口（baisis 这类 302 直链）一次就中，不浪费请求
        Meta first = resolveOnceFull(apiUrl);
        if (first != null && first.url.length() > 8) {
            return first;
        }
        // 没中就说明碰上「随机一条 + 上游反爬」的接口（实测单次约 1/3 命中）。
        // 改成 3 路并发补打、最多 2 轮：耗时压到一次往返，命中率 1-(2/3)^6 ≈ 91%
        Meta m = parallelTry(apiUrl, 3);
        if (m != null && m.url.length() > 8) {
            return m;
        }
        Meta last = parallelTry(apiUrl, 2);
        return last != null && last.url.length() > 8 ? last : null;
    }

    private static final java.util.concurrent.ExecutorService RETRY_POOL =
            java.util.concurrent.Executors.newCachedThreadPool();

    /** 同时打 n 个请求，谁先成功用谁。 */
    private static Meta parallelTry(final String apiUrl, int n) {
        final java.util.concurrent.CompletionService<Meta> cs =
                new java.util.concurrent.ExecutorCompletionService<Meta>(RETRY_POOL);
        for (int i = 0; i < n; i++) {
            cs.submit(new java.util.concurrent.Callable<Meta>() {
                @Override
                public Meta call() {
                    try {
                        // 补打走纯 302/JSON 流程，不再浪费快通道超时
                        return resolveOnceFull(apiUrl, false);
                    } catch (Throwable ignored) {
                        return null;
                    }
                }
            });
        }
        try {
            for (int i = 0; i < n; i++) {
                java.util.concurrent.Future<Meta> f =
                        cs.poll(8, java.util.concurrent.TimeUnit.SECONDS);
                if (f == null) {
                    break;
                }
                Meta m;
                try {
                    m = f.get();
                } catch (Throwable ignored) {
                    continue;
                }
                if (m != null && m.url.length() > 8) {
                    return m;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static Meta resolveOnceFull(String apiUrl) {
        return resolveOnceFull(apiUrl, true);
    }

    private static Meta resolveOnceFull(String apiUrl, boolean allowFast) {
        // 先试 ?type=json 快通道（一次请求直接拿直链，省掉 302 往返）
        if (allowFast && apiUrl.indexOf('?') < 0 && !FAST_FAILED.contains(apiUrl)) {
            String fastBody = simpleGet(apiUrl + "?type=json");
            Meta fast = parseJsonBody(fastBody, apiUrl);
            if (fast != null && fast.url.length() > 8) {
                return fast;
            }
            if (fastBody == null) {
                FAST_FAILED.add(apiUrl);
            }
        }
        // 再试 ?type=text：部分接口在这个模式下直接吐纯文本直链，比 302 少一次往返
        if (allowFast && apiUrl.indexOf('?') < 0 && !TEXT_FAILED.contains(apiUrl)) {
            String textBody = simpleGet(apiUrl + "?type=text");
            if (textBody == null) {
                TEXT_FAILED.add(apiUrl);
            } else {
                java.util.regex.Matcher m = P_ANY.matcher(normalize(textBody));
                while (m.find()) {
                    String u = trimUrl(m.group());
                    if (u != null && looksLikeDirectVideo(u)) {
                        return new Meta(u, "", "", 0);
                    }
                }
                TEXT_FAILED.add(apiUrl);
            }
        }
        HttpURLConnection conn = null;
        try {
            String current = apiUrl;
            for (int hop = 0; hop < MAX_HOPS; hop++) {
                conn = open(current, "bytes=0-65535");
                int code = conn.getResponseCode();
                if (code >= 300 && code < 400) {
                    String loc = locationOf(conn);
                    if (loc == null || loc.length() == 0) {
                        return null;
                    }
                    current = absolute(current, loc);
                    conn.disconnect();
                    conn = null;
                    continue;
                }
                if (code != 200 && code != 206) {
                    return null;
                }
                String type = IO.lower(conn.getContentType());
                if (type.startsWith("video/") || type.contains("mpeg") || type.contains("octet-stream")) {
                    return new Meta(current, "", "", 0);
                }
                if (!isTextual(type)) {
                    return new Meta(current, "", "", 0);
                }
                String body = IO.readLimited(conn.getInputStream(), BODY_LIMIT);
                Meta meta = parseJsonBody(body, current);
                if (meta != null && meta.url.length() > 8) {
                    return meta;
                }
                String best = pickBest(extractCandidates(body));
                if (best != null) {
                    return new Meta(best, "", "", 0);
                }
                return null;
            }
            return null;
        } catch (Throwable th) {
            IO.log("resolveFull error " + th);
            return null;
        } finally {
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** 极简 GET，只要 body 字符串；失败返回 null。 */
    /** 试过 ?type=text 但拿不到东西的接口。 */
    private static final java.util.Set<String> TEXT_FAILED =
            java.util.Collections.synchronizedSet(new java.util.HashSet<String>());

    /** 试过 ?type=json 但拿不到东西的接口，后面就别再浪费一次超时。 */
    private static final java.util.Set<String> FAST_FAILED =
            java.util.Collections.synchronizedSet(new java.util.HashSet<String>());

    static {
        // 这几个实测不支持 ?type=json（返回 201/图文），提前登记免空等
        FAST_FAILED.add("https://api.yujn.cn/api/ks_sj.php");
        FAST_FAILED.add("https://api.yujn.cn/api/dy_sj.php");
        FAST_FAILED.add("https://api.yujn.cn/api/dy_hot.php");
    }

    private static String simpleGet(String url) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(FAST_CONNECT);
            conn.setReadTimeout(FAST_READ);
            conn.setRequestProperty("User-Agent", UA);
            conn.setRequestProperty("Accept", "application/json, */*");
            if (conn.getResponseCode() != 200) {
                return null;
            }
            return IO.readLimited(conn.getInputStream(), 128 * 1024);
        } catch (Throwable ignored) {
            return null;
        } finally {
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /**
     * 从 JSON 正文里挖出 {video, title, cover}。
     *
     * 兼容几种常见形态：
     *  · {"code":200,"data":"https://...mp4","video_count":2050}                      （type=json 快通道）
     *  · {"code":200,"data":[{"title":"@xx 的精彩视频","cover":"...","video":"..."}]}  （快手随机）
     *  · {"desc":"...","author":"...","cover":"...","video":"..."}                     （抖音热门）
     */
    static Meta parseJsonBody(String body, String baseUrl) {
        if (body == null || body.length() == 0) {
            return null;
        }
        String text = normalize(body);
        int brace = text.indexOf('{');
        if (brace < 0) {
            return null;
        }
        try {
            org.json.JSONObject root = new org.json.JSONObject(text.substring(brace));
            int count = root.optInt("video_count", 0);
            Object data = root.opt("data");
            if (data instanceof String) {
                String u = ((String) data).trim();
                if (u.startsWith("http")) {
                    if (count > 0) {
                        lastVideoCount = count;
                    }
                    return new Meta(u, root.optString("title", ""), root.optString("cover", ""), count);
                }
            }
            if (data instanceof org.json.JSONArray) {
                org.json.JSONArray arr = (org.json.JSONArray) data;
                for (int i = 0; i < arr.length(); i++) {
                    Object item = arr.opt(i);
                    if (item instanceof org.json.JSONObject) {
                        Meta m = metaFromObject((org.json.JSONObject) item, count);
                        if (m != null) {
                            return m;
                        }
                    }
                }
            }
            Meta self = metaFromObject(root, count);
            if (self != null) {
                return self;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static Meta metaFromObject(org.json.JSONObject o, int count) {
        if (o == null) {
            return null;
        }
        String url = firstHttp(o, "video", "url", "src", "play", "mp4", "vid", "videoUrl", "playUrl");
        if (url == null) {
            return null;
        }
        String title = firstText(o, "title", "desc", "description", "caption", "name", "content");
        String cover = firstHttp(o, "cover", "pic", "img", "image", "poster", "thumbnail", "avatar");
        if (count <= 0) {
            count = o.optInt("video_count", 0);
        }
        return new Meta(url, title, cover, count);
    }

    private static String firstHttp(org.json.JSONObject o, String... keys) {
        for (int i = 0; i < keys.length; i++) {
            String v = o.optString(keys[i], "").trim();
            if (v.startsWith("http")) {
                return v;
            }
        }
        return null;
    }

    private static String firstText(org.json.JSONObject o, String... keys) {
        for (int i = 0; i < keys.length; i++) {
            String v = o.optString(keys[i], "").trim();
            if (v.length() > 0 && !"null".equals(v)) {
                return v;
            }
        }
        return "";
    }


    private static String resolveOnce(String apiUrl) {
        HttpURLConnection conn = null;
        try {
            String current = apiUrl;
            for (int hop = 0; hop < MAX_HOPS; hop++) {
                conn = open(current, "bytes=0-65535");
                int code = conn.getResponseCode();
                if (code >= 300 && code < 400) {
                    String loc = locationOf(conn);
                    if (loc == null || loc.length() == 0) {
                        return null;
                    }
                    current = absolute(current, loc);
                    conn.disconnect();
                    conn = null;
                    continue;
                }
                if (code != 200 && code != 206) {
                    IO.log("resolve http " + code + " " + current);
                    return null;
                }
                String type = IO.lower(conn.getContentType());
                if (type.startsWith("video/") || type.contains("mpeg") || type.contains("octet-stream")) {
                    return current;
                }
                if (!isTextual(type)) {
                    return current;
                }
                String body = IO.readLimited(conn.getInputStream(), BODY_LIMIT);
                String best = pickBest(extractCandidates(body));
                if (best != null) {
                    return best;
                }
                return null;
            }
            return null;
        } catch (Throwable th) {
            IO.log("resolve error " + th);
            return null;
        } finally {
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /**
     * 走 JSON 形态：GET {api}?type=json → {"code":200,"video_count":2050,"data":"https://...mp4"}
     * 成功返回直链；接口不支持或返回异常时返回 null，交给 302 流程兜底。
     */
    static String resolveJsonApi(String apiUrl) {
        if (!apiUrl.contains(".php") || !apiUrl.contains("api.") || apiUrl.indexOf('?') > 0) {
            return null;
        }
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(apiUrl + "?type=json").openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT);
            conn.setReadTimeout(READ_TIMEOUT);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", UA);
            conn.setRequestProperty("Accept", "application/json, */*");
            int code = conn.getResponseCode();
            if (code != 200) {
                return null;
            }
            String body = IO.readLimited(conn.getInputStream(), 64 * 1024);
            if (body == null || body.length() == 0) {
                return null;
            }
            org.json.JSONObject json = new org.json.JSONObject(normalize(body));
            if (json.optInt("code", 0) != 200) {
                return null;
            }
            String data = json.optString("data", "");
            data = data == null ? "" : data.trim();
            if (data.startsWith("http") && data.length() > 12) {
                int count = json.optInt("video_count", 0);
                lastVideoCount = count;
                lastApiWithCount = apiUrl;
                if (count > 0) {
                    COUNTS.put(apiUrl, Integer.valueOf(count));
                }
                return data;
            }
            return null;
        } catch (Throwable th) {
            return null;
        } finally {
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** 每个接口的片库数量缓存（来自 video_count）。 */
    private static final java.util.HashMap<String, Integer> COUNTS =
            new java.util.HashMap<String, Integer>();
    private static volatile int lastVideoCount = 0;

    public static int lastVideoCount() {
        return lastVideoCount;
    }

    /** 取某个接口已知的片库数量，未知返回 0。 */
    public static int countOf(String api) {
        if (api == null) {
            return 0;
        }
        Integer v = COUNTS.get(api);
        if (v != null) {
            return v.intValue();
        }
        if (api.equals(lastApiWithCount)) {
            return lastVideoCount;
        }
        return 0;
    }

    private static volatile String lastApiWithCount = null;

    /** 单独查一个接口的片库数量，给设置页做容量检测用。 */
    public static int videoCount(String apiUrl) {
        if (apiUrl == null || apiUrl.indexOf('?') > 0) {
            return 0;
        }
        String u = apiUrl.contains(".php") ? apiUrl + "?type=json" : apiUrl;
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(u).openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT);
            conn.setReadTimeout(READ_TIMEOUT);
            conn.setRequestProperty("User-Agent", UA);
            int code = conn.getResponseCode();
            if (code != 200) {
                return 0;
            }
            String body = IO.readLimited(conn.getInputStream(), 64 * 1024);
            int n = new org.json.JSONObject(normalize(body)).optInt("video_count", 0);
            if (n > 0) {
                COUNTS.put(apiUrl, Integer.valueOf(n));
            }
            return n;
        } catch (Throwable ignored) {
            return 0;
        } finally {
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static boolean isTextual(String type) {
        return type.length() == 0 || type.contains("text") || type.contains("json")
                || type.contains("html") || type.contains("xml") || type.contains("javascript");
    }

    private static HttpURLConnection open(String url, String range) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setInstanceFollowRedirects(false);
        conn.setConnectTimeout(CONNECT_TIMEOUT);
        conn.setReadTimeout(READ_TIMEOUT);
        conn.setRequestMethod("GET");
        conn.setRequestProperty("User-Agent", UA);
        conn.setRequestProperty("Accept", "*/*");
        conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9");
        if (range != null) {
            conn.setRequestProperty("Range", range);
        }
        return conn;
    }

    private static String locationOf(HttpURLConnection conn) {
        try {
            String loc = conn.getHeaderField("Location");
            if (loc == null) {
                loc = conn.getHeaderField("location");
            }
            return loc;
        } catch (Throwable ignored) {
            return null;
        }
    }

    // ------------------------------------------------------------ 候选提取

    /** 从响应正文里抓出所有可能的视频地址，按「越像视频越靠前」排序。 */
    public static List<String> extractCandidates(String body) {
        List<String> out = new ArrayList<String>();
        if (body == null || body.length() == 0) {
            return out;
        }
        String text = normalize(body);
        LinkedHashSet<String> seen = new LinkedHashSet<String>();
        Matcher mv = P_VIDEO.matcher(text);
        while (mv.find()) {
            String u = trimUrl(mv.group());
            if (u != null && !isImage(u)) {
                seen.add(u);
            }
        }
        if (seen.isEmpty()) {
            // 没有带扩展名的，退一步：所有 http 链接里挑像视频的
            Matcher ma = P_ANY.matcher(text);
            while (ma.find()) {
                String u = trimUrl(ma.group());
                if (u != null && !isImage(u) && isProbablyVideo(u)) {
                    seen.add(u);
                }
            }
        }
        out.addAll(seen);
        Collections.sort(out, new Comparator<String>() {
            @Override
            public int compare(String a, String b) {
                int sa = score(a);
                int sb = score(b);
                return sa != sb ? sa - sb : a.compareTo(b);
            }
        });
        return out;
    }

    private static String pickBest(List<String> list) {
        if (list == null || list.isEmpty()) {
            return null;
        }
        // 只对最好的几个做一次轻量可用性确认，避免把 404 的封面当成视频
        for (int i = 0; i < Math.min(3, list.size()); i++) {
            String u = list.get(i);
            if (score(u) <= 2 || headOk(u)) {
                return u;
            }
        }
        return list.get(0);
    }

    private static int score(String url) {
        String s = IO.lower(url);
        int q = s.indexOf('?');
        String path = q > 0 ? s.substring(0, q) : s;
        if (path.endsWith(".mp4")) {
            return 0;
        }
        if (path.endsWith(".m4v") || path.endsWith(".mov") || path.endsWith(".webm") || path.endsWith(".mkv")) {
            return 1;
        }
        if (path.endsWith(".m3u8")) {
            return 2;
        }
        if (path.endsWith(".flv") || path.endsWith(".ts")) {
            return 3;
        }
        if (s.contains("mp4") || s.contains("video") || s.contains("vod")) {
            return 4;
        }
        return 5;
    }

    private static boolean isImage(String url) {
        String s = IO.lower(url);
        for (int i = 0; i < IMAGE_HINTS.length; i++) {
            if (s.contains(IMAGE_HINTS[i])) {
                return true;
            }
        }
        return false;
    }

    private static boolean isProbablyVideo(String url) {
        String s = IO.lower(url);
        return s.contains("mp4") || s.contains("m3u8") || s.contains("video")
                || s.contains("vod") || s.contains("play") || s.contains("stream");
    }

    private static String trimUrl(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim();
        int end = s.length();
        while (end > 0) {
            char c = s.charAt(end - 1);
            if (c == ',' || c == ';' || c == '"' || c == '\'' || c == '}' || c == ']'
                    || c == ')' || c == '>' || c == '.' || c == '\\') {
                end--;
            } else {
                break;
            }
        }
        s = s.substring(0, end);
        return s.length() < 12 ? null : s;
    }

    /** 还原 JSON 转义、HTML 实体、URL 编码的斜杠。 */
    static String normalize(String body) {
        String s = body;
        try {
            s = s.replace("\\/", "/");
            s = s.replace("\\u002F", "/").replace("\\u002f", "/");
            s = s.replace("&amp;", "&").replace("&#38;", "&")
                    .replace("&quot;", "\"").replace("&#39;", "'").replace("&#x2F;", "/");
            s = s.replace("%2F", "/").replace("%3A", ":");
        } catch (Throwable ignored) {
        }
        return s;
    }

    static String unescape(String str) {
        if (str == null) {
            return null;
        }
        return str.replace("&amp;", "&").replace("&#38;", "&")
                .replace("&quot;", "\"").replace("&#39;", "'");
    }

    static String absolute(String base, String ref) {
        try {
            if (ref.startsWith("http://") || ref.startsWith("https://")) {
                return ref;
            }
            return new URL(new URL(base), ref).toString();
        } catch (Throwable ignored) {
            return ref;
        }
    }

    /** HEAD 探测：只判断「地址还活着」，不下载内容。 */
    static boolean headOk(String url) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setInstanceFollowRedirects(true);
            conn.setConnectTimeout(3500);
            conn.setReadTimeout(3500);
            conn.setRequestMethod("HEAD");
            conn.setRequestProperty("User-Agent", UA);
            int code = conn.getResponseCode();
            if (code == 405 || code == 501 || code == 403) {
                return true; // 服务器不支持 HEAD，别因此误杀
            }
            return code >= 200 && code < 400;
        } catch (Throwable ignored) {
            return false;
        } finally {
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    public static boolean looksLikeDirectVideo(String url) {
        if (url == null) {
            return false;
        }
        String s = IO.lower(url);
        int q = s.indexOf('?');
        String path = q > 0 ? s.substring(0, q) : s;
        return path.endsWith(".mp4") || path.endsWith(".m3u8") || path.endsWith(".flv")
                || path.endsWith(".webm") || path.endsWith(".mov") || path.endsWith(".m4v")
                || path.endsWith(".mkv") || path.endsWith(".ts");
    }

    public static boolean looksLikeVideo(String str) {
        if (str == null) {
            return false;
        }
        return looksLikeDirectVideo(str) || looksLikeDirectVideo(str.replace(":/", "/"));
    }
}
