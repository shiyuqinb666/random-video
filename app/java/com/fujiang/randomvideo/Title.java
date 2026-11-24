package com.fujiang.randomvideo;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 标题生成/还原（纯 Java，不依赖 Android，方便单元测试）。
 *
 * 历史记录和下载箱的标题都从这里出，保证「任何情况下都不会是空白」。
 */
public final class Title {

    private Title() {
    }

    /** 一条记录的标题：已有标题优先，其余情况现推一个。 */
    public static String resolve(String stored, String libraryTitle, String catName,
                                 String url, long timeMs) {
        if (stored != null && stored.trim().length() > 0) {
            String t = stored.trim();
            // 1.3.3 及更早把落盘文件名当标题存了，这里还原成人话
            if (looksLikeFileName(t)) {
                String cleaned = fromFileName(t);
                if (cleaned.length() > 0) {
                    return cleaned;
                }
            }
            return t;
        }
        if (libraryTitle != null && libraryTitle.trim().length() > 0) {
            return libraryTitle.trim();
        }
        return derive(url, catName, timeMs);
    }

    /** 从分类名 / 时间 / URL 推标题。 */
    public static String derive(String url, String catName, long timeMs) {
        boolean local = url != null && !url.startsWith("http");
        if (local) {
            String name = nameFromUrl(url);
            if (weak(name)) {
                return "本地视频";
            }
            return name;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(catName == null || catName.trim().length() == 0 ? "在线视频" : catName.trim());
        if (timeMs > 0) {
            try {
                SimpleDateFormat f = new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA);
                sb.append(" · ").append(f.format(new Date(timeMs)));
            } catch (Throwable ignored) {
            }
        }
        String fromUrl = nameFromUrl(url);
        if (readable(fromUrl)) {
            sb.append(" · ").append(fromUrl);
        }
        return sb.toString();
    }

    /** 名字太弱（纯数字 / 协议占位词），不适合当标题。 */
    static boolean weak(String s) {
        if (s == null || s.length() == 0) {
            return true;
        }
        if (s.matches("[0-9]+")) {
            return true;
        }
        String lower = s.toLowerCase(Locale.ROOT);
        return lower.equals("document") || lower.equals("raw") || lower.equals("video")
                || lower.equals("file") || lower.equals("download") || lower.equals("content");
    }

    static boolean looksLikeFileName(String s) {
        String lower = s.toLowerCase(Locale.ROOT);
        String[] exts = {".mp4", ".m4v", ".mov", ".webm", ".mkv", ".flv", ".ts", ".m3u8"};
        for (int i = 0; i < exts.length; i++) {
            if (lower.endsWith(exts[i])) {
                return true;
            }
        }
        return false;
    }

    /** URL 末段是否「像人话」：抖音那种 BMjAy... 编码串就丢掉。 */
    static boolean readable(String s) {
        if (s == null || s.length() == 0 || s.length() > 24) {
            return false;
        }
        if (s.startsWith("BMjAy") || s.startsWith("MJ") || s.matches("[0-9a-fA-F]{16,}")) {
            return false;
        }
        int letters = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isLetter(c)) {
                letters++;
            }
        }
        return letters >= 2;
    }

    /** 从 "标题_82cbd1.mp4" 还原 "标题"。 */
    public static String fromFileName(String fileName) {
        if (fileName == null || fileName.trim().length() == 0) {
            return "";
        }
        String s = fileName.trim();
        int dot = s.lastIndexOf('.');
        if (dot > 0) {
            s = s.substring(0, dot);
        }
        int us = s.lastIndexOf('_');
        if (us > 0) {
            String tail = s.substring(us + 1);
            if (tail.length() >= 4 && tail.length() <= 8 && isHex(tail)) {
                s = s.substring(0, us);
            }
        }
        return s.trim();
    }

    private static boolean isHex(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    /** URL 末段当作名字（去 query、去扩展名、去 %20）。 */
    public static String nameFromUrl(String url) {
        if (url == null || url.length() == 0) {
            return "";
        }
        String s = url;
        try {
            int q = s.indexOf('?');
            if (q > 0) {
                s = s.substring(0, q);
            }
            int f = s.indexOf('#');
            if (f > 0) {
                s = s.substring(0, f);
            }
            int slash = s.lastIndexOf('/');
            if (slash >= 0 && slash < s.length() - 1) {
                s = s.substring(slash + 1);
            }
            int dot = s.lastIndexOf('.');
            if (dot > 1) {
                s = s.substring(0, dot);
            }
            s = s.replace("%20", " ").trim();
        } catch (Throwable ignored) {
            return "";
        }
        if (s.equals("VID") || s.equals("video") || s.equals("index")) {
            return "";
        }
        return s;
    }
}