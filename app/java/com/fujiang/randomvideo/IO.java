package com.fujiang.randomvideo;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.BufferedReader;

public final class IO {
    private static final String TAG = "RandomVideo";

    private IO() {
    }

    public static String readAll(InputStream in) throws Exception {
        if (in == null) {
            return "";
        }
        BufferedReader br = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        try {
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[4096];
            int n;
            while ((n = br.read(buf)) > 0) {
                sb.append(buf, 0, n);
            }
            return sb.toString();
        } finally {
            close(br);
        }
    }

    /** 只读前 maxBytes 个字节，避免一次把大文件读进内存。 */
    public static String readLimited(InputStream in, int maxBytes) {
        if (in == null) {
            return "";
        }
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(8192);
            byte[] buf = new byte[8192];
            int total = 0;
            int n;
            while (total < maxBytes && (n = in.read(buf, 0, Math.min(buf.length, maxBytes - total))) > 0) {
                bos.write(buf, 0, n);
                total += n;
            }
            return new String(bos.toByteArray(), "UTF-8");
        } catch (Throwable th) {
            log("readLimited " + th);
            return "";
        } finally {
            close(in);
        }
    }

    public static long drain(InputStream in) {
        if (in == null) {
            return 0L;
        }
        long total = 0L;
        try {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                total += n;
            }
        } catch (Throwable ignored) {
        } finally {
            close(in);
        }
        return total;
    }

    public static void close(Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (Throwable ignored) {
            }
        }
    }

    public static void log(String str) {
        try {
            android.util.Log.d(TAG, String.valueOf(str));
        } catch (Throwable ignored) {
        }
    }

    public static String lower(String str) {
        return str == null ? "" : str.toLowerCase(java.util.Locale.ROOT);
    }
}