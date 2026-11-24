package com.fujiang.randomvideo;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 本地视频自动分拣器（纯 Java，方便单元测试）。
 *
 * 1.2 的做法是「按关键词长度排序，取第一个 contains 命中」，问题很多：
 *   · 文件夹名和文件名同权，`JK/` 被文件名里的 "cos" 抢走；
 *   · 短英文关键词（jk / cos / ol）会在 "jksp"、"costco"、"color" 里误命中；
 *   · 文件名里的日期、IMG_、WeChat、mmexport、剪辑、副本这类噪声参与匹配；
 *   · 多个关键词都命中时不比"谁更具体"，只看分类表里的先后顺序。
 *
 * 现在改成打分制：
 *   score = Σ 命中关键词的权重
 *   权重 = 关键词长度因子 × 命中位置因子（文件夹 3 / 文件名 1）× 命中次数（封顶 3）
 *   关键词长度 2 及以下的纯拉丁词要求词边界，避免子串误命中
 *   命中噪声先剔除（日期、相机前缀、社交 App 前缀、编辑后缀）
 */
public final class Classifier {

    /** 噪声词：出现在文件名里但没有分类意义。 */
    private static final String[] NOISE = {
            "img_", "vid_", "video_", "photo_", "pic_", "dsc_", "dscf", "dji_", "gopr",
            "mmexport", "wechat", "wx_camera", "qq视频", "qqvideo", "qq_", "qqmusic",
            "screenrecord", "screencap", "screen_record", "record", "screenshot",
            "trim", "trimmed", "edit", "edited", "最终", "副本", "剪辑", "导出", "合成",
            "watermark", "无水印", "带水印", "hdr", "slow", "fast", "burst", "panorama",
            "livephoto", "live_photo", "untitled", "未命名", "新建",
            "1080p", "720p", "480p", "4k", "8k", "hd", "fhd", "uhd", "超清", "高清", "蓝光",
            "无水印", "带水印", "完整版", "加长版", "中字", "字幕", "原版",
            "download", "下载", "缓存", "cache", "temp", "tmp", "final"
    };

    private static final class Hit {
        int score;
        int bestLen;
        String cat;
    }

    private Classifier() {
    }

    /** 归一化：小写、全角转半角、去括号/标点/下划线/连字符、压缩空白、剥离噪声。 */
    public static String normalize(String raw) {
        if (raw == null || raw.length() == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c >= 'Ａ' && c <= 'Ｚ') {
                c = (char) (c - 0xFEE0);
            } else if (c >= 'ａ' && c <= 'ｚ') {
                c = (char) (c - 0xFEE0);
            } else if (c >= '０' && c <= '９') {
                c = (char) (c - 0xFEE0);
            }
            if (c == '\\' || c == '/' || c == '_' || c == '-' || c == '.' || c == '·'
                    || c == '(' || c == ')' || c == '[' || c == ']' || c == '【' || c == '】'
                    || c == '{' || c == '}' || c == '、' || c == ',' || c == '，' || c == ';'
                    || c == '：' || c == ':' || c == '!' || c == '？' || c == '?' || c == '"'
                    || c == '\'' || c == '#') {
                sb.append(' ');
            } else {
                sb.append(c);
            }
        }
        String s = sb.toString().toLowerCase(Locale.ROOT);
        // 日期 / 时间戳 / 长数字串
        s = s.replaceAll("\\d{4}[-_年]?\\d{1,2}[-_月]?\\d{1,2}[日]?", " ");
        s = s.replaceAll("\\d{8,}", " ");
        s = s.replaceAll("\\d{2,}\\s*\\d{2,}", " ");
        // 噪声：纯拉丁且很短的（hd/4k/tmp/ts...）要求词边界，避免把别的词切坏
        for (int i = 0; i < NOISE.length; i++) {
            String n = NOISE[i];
            if (n.length() <= 3 && n.matches("[0-9a-z]+")) {
                s = s.replaceAll("(?<![a-z0-9])" + java.util.regex.Pattern.quote(n) + "(?![a-z0-9])", " ");
            } else {
                s = s.replace(n, " ");
            }
        }
        s = s.replaceAll("\\s+", " ").trim();
        return s;
    }

    private static int prio(Map<String, Integer> priority, String cat) {
        if (priority == null) {
            return 1000;
        }
        Integer v = priority.get(cat);
        return v == null ? 1000 : v.intValue();
    }

    private static boolean better(Hit a, Hit b, Map<String, Integer> priority) {
        if (a == null) {
            return false;
        }
        if (b == null) {
            return true;
        }
        if (a.score != b.score) {
            return a.score > b.score;
        }
        if (a.bestLen != b.bestLen) {
            return a.bestLen > b.bestLen;
        }
        int pa = prio(priority, a.cat);
        int pb = prio(priority, b.cat);
        if (pa != pb) {
            return pa < pb;
        }
        return a.cat.compareTo(b.cat) < 0;
    }

    private static boolean isLatinLetter(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    /**
     * 计算某个关键词在文本里的命中次数（封顶 3）。
     *
     * @param occupied 已占用区间（同一段文字被更长的关键词吃掉后，短词不再重复计分），
     *                 null 表示不遮蔽
     */
    static int count(String text, String keyword, boolean[] occupied) {
        if (text.length() == 0 || keyword.length() == 0) {
            return 0;
        }
        boolean latin = true;
        for (int i = 0; i < keyword.length(); i++) {
            if (!isLatinLetter(keyword.charAt(i)) && keyword.charAt(i) != ' ') {
                latin = false;
                break;
            }
        }
        boolean boundary = latin && keyword.length() <= 3;
        int hits = 0;
        int from = 0;
        while (true) {
            int idx = text.indexOf(keyword, from);
            if (idx < 0) {
                break;
            }
            boolean ok = true;
            if (boundary) {
                char before = idx > 0 ? text.charAt(idx - 1) : ' ';
                int end = idx + keyword.length();
                char after = end < text.length() ? text.charAt(end) : ' ';
                ok = !isLatinLetter(before) && !isLatinLetter(after);
            }
            if (ok) {
                int end2 = idx + keyword.length();
                boolean free = true;
                if (occupied != null) {
                    for (int k = idx; k < end2 && k < occupied.length; k++) {
                        if (occupied[k]) {
                            free = false;
                            break;
                        }
                    }
                }
                if (free) {
                    if (occupied != null) {
                        for (int k = idx; k < end2 && k < occupied.length; k++) {
                            occupied[k] = true;
                        }
                    }
                    hits++;
                    if (hits >= 3) {
                        return hits;
                    }
                }
            }
            from = idx + 1;
        }
        return hits;
    }

    static int count(String text, String keyword) {
        return count(text, keyword, null);
    }

    /**
     * @param primary   文件夹路径 + 相册名（权重高）
     * @param secondary 文件名 / 标题（权重低）
     * @param entries   {关键词, 分类 key} 列表
     * @return 分类 key；判定不出来时返回 fallback
     */
    public static String classify(String primary, String secondary,
                                   List<Map.Entry<String, String>> entries,
                                   String fallback) {
        return classify(primary, secondary, entries, null, fallback);
    }

    /**
     * @param priority 分类优先级（越小越优先），同分时用它裁决；可为 null
     */
    public static String classify(String primary, String secondary,
                                   List<Map.Entry<String, String>> entries,
                                   Map<String, Integer> priority,
                                   String fallback) {
        String a = normalize(primary);
        String b = normalize(secondary);
        if (a.length() == 0 && b.length() == 0) {
            return fallback;
        }
        HashMap<String, Hit> hits = new HashMap<String, Hit>();

        // 关键改动：先长后短 + 逐字段遮蔽。
        // 这样「白丝袜」只会记给「白丝袜」，不会被「丝袜」「袜」重复计分，
        // 从而避免 silk 和 baisi 同分、靠分类顺序瞎裁决。
        java.util.List<Map.Entry<String, String>> sorted =
                new java.util.ArrayList<Map.Entry<String, String>>(entries);
        java.util.Collections.sort(sorted, new java.util.Comparator<Map.Entry<String, String>>() {
            @Override
            public int compare(Map.Entry<String, String> x, Map.Entry<String, String> y) {
                int lx = x.getKey() == null ? 0 : x.getKey().length();
                int ly = y.getKey() == null ? 0 : y.getKey().length();
                return ly - lx; // 长的在前
            }
        });

        boolean[] usedA = new boolean[a.length()];
        boolean[] usedB = new boolean[b.length()];

        for (int i = 0; i < sorted.size(); i++) {
            Map.Entry<String, String> e = sorted.get(i);
            String kw = e.getKey();
            String cat = e.getValue();
            if (kw == null || kw.length() == 0 || cat == null) {
                continue;
            }
            int inA = count(a, kw, usedA);
            int inB = count(b, kw, usedB);
            if (inA == 0 && inB == 0) {
                continue;
            }
            // 关键词越长越具体
            int lenBonus = kw.length() >= 6 ? 3 : (kw.length() >= 4 ? 2 : 1);
            int weight = lenBonus * (inA * 3 + inB * 1);
            Hit h = hits.get(cat);
            if (h == null) {
                h = new Hit();
                h.cat = cat;
                hits.put(cat, h);
            }
            h.score += weight;
            if (kw.length() > h.bestLen) {
                h.bestLen = kw.length();
            }
        }

        Hit best = null;
        Hit second = null;
        for (Hit h : hits.values()) {
            if (better(h, best, priority)) {
                second = best;
                best = h;
            } else if (better(h, second, priority)) {
                second = h;
            }
        }
        if (best == null) {
            return fallback;
        }
        // 只命中了单字关键词（比如「腿」「裙」）时太弱，宁可不硬分；
        // 命中两个汉字以上的词（变装/快影/合集）已经算明确信号，照常归类。
        if (best.score <= 1 && best.bestLen <= 1) {
            return fallback;
        }
        if (best.score <= 1 && second != null && second.score > best.score) {
            return fallback;
        }
        return best.cat;
    }
}