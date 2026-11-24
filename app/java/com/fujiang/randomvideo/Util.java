package com.fujiang.randomvideo;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.util.TypedValue;
import android.widget.Toast;

import java.util.Locale;

public final class Util {
    private static long lastToastAt = 0L;
    private static String lastToastMsg = "";

    private Util() {
    }

    public static String safe(String str) {
        return str == null ? "" : str;
    }

    public static int dp(Context context, float f) {
        if (context == null) {
            return Math.round(f * 2f);
        }
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, f,
                context.getResources().getDisplayMetrics()));
    }

    /** 普通提示。 */
    public static void toast(Context context, String str) {
        show(context, str, false);
    }

    /**
     * 去重提示：同一条文案 1.6 秒内只弹一次，避免连环报错把屏幕刷满。
     */
    public static void toastOnce(Context context, String str) {
        show(context, str, true);
    }

    private static void show(Context context, String str, boolean dedup) {
        if (context == null || str == null || str.length() == 0) {
            return;
        }
        long now = android.os.SystemClock.elapsedRealtime();
        if (dedup && now - lastToastAt < 1600L && str.equals(lastToastMsg)) {
            return;
        }
        lastToastAt = now;
        lastToastMsg = str;
        try {
            Toast.makeText(context.getApplicationContext(), str, Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {
            try {
                Toast.makeText(context, str, Toast.LENGTH_SHORT).show();
            } catch (Throwable ignored2) {
                // 后台线程里弹 toast 在部分 ROM 上会抛异常，忽略即可
            }
        }
    }

    public static boolean hasNetwork(Context context) {
        if (context == null) {
            return false;
        }
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) {
                return true;
            }
            NetworkInfo info = cm.getActiveNetworkInfo();
            return info != null && info.isConnected();
        } catch (Throwable ignored) {
            return true;
        }
    }

    public static String fmtTime(int i) {
        if (i < 0) {
            i = 0;
        }
        int total = i / 1000;
        int h = total / 3600;
        int m = (total % 3600) / 60;
        int s = total % 60;
        if (h > 0) {
            return String.format(Locale.US, "%d:%02d:%02d", h, m, s);
        }
        return String.format(Locale.US, "%02d:%02d", m, s);
    }

    public static String fmtSize(long bytes) {
        if (bytes < 1024L) {
            return bytes + "B";
        }
        if (bytes < 1024L * 1024L) {
            return String.format(Locale.US, "%.1fKB", bytes / 1024.0);
        }
        return String.format(Locale.US, "%.1fMB", bytes / (1024.0 * 1024.0));
    }

    public static String ellipsis(String str, int max) {
        if (str == null) {
            return "";
        }
        if (str.length() <= max) {
            return str;
        }
        return str.substring(0, Math.max(0, max - 1)) + "…";
    }

    /** 把 URL 变成更好看的标题。 */
    public static String nameFromUrl(String url) {
        if (url == null || url.length() == 0) {
            return "视频";
        }
        String s = url;
        try {
            int q = s.indexOf('?');
            if (q > 0) {
                s = s.substring(0, q);
            }
            int frag = s.indexOf('#');
            if (frag > 0) {
                s = s.substring(0, frag);
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
            return "视频";
        }
        return s.length() > 0 && s.length() <= 60 ? s : "视频";
    }
}