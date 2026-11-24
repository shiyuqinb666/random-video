package com.fujiang.randomvideo;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.SystemClock;
import android.text.InputType;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * 隐藏区（颜色区）。
 *
 * 1.3.1 起，原来摆在隐藏区里的「深夜 / 甜妹 / 洋装 / 漫展 / 日常 / 全站」全部搬到首页
 * 变成正常分类，隐藏区只放偏成人的内容：
 *
 *  - 深夜 / 吊带热舞 / 完美身材 / 丝袜美腿 / 泳装湿身 / 甜妹软萌 / 全站无差别
 *    这几个用实测可用的视频源，口味按「露肤度」从重到轻排
 *  - 我的颜色源：自己粘 API 或直链进来，独立于首页片库
 *  - 私密柜：从手机里挑视频放进来，只在隐藏区出现、首页看不到
 */
public final class Hidden {
    public static final int REQ_VAULT = 3001;

    private static final String SP = "random_video_prefs";
    private static final String K_CUSTOM = "hidden_custom_src";
    private static final String K_VAULT = "hidden_vault_items";

    /** 隐藏区条目（key、名字、副标题）。 */
    private static final String[] KEYS = {
            "hidden_night", "hidden_dance", "hidden_body", "hidden_silk",
            "hidden_swim", "hidden_sweet", "hidden_all", "hidden_custom", "hidden_vault"
    };
    private static final String[] NAMES = {
            "深夜 · 黑丝网袜", "吊带 · 热舞", "完美身材 · 露背", "丝袜 · 美腿",
            "泳装 · 湿身", "甜妹 · 软萌", "全站 · 无差别随机", "我的颜色源", "私密柜"
    };
    private static final String[] SUBS = {
            "黑丝·网袜·吊带", "吊带·贴身·热舞", "曲线·露背·身材", "白丝·黑丝·长腿",
            "泳装·湿身·沙滩", "甜系·清纯·软萌", "一次到底，不看分类", "自己粘的源", "手机里的私密视频"
    };

    private static final HashMap<String, String[]> APIS = new HashMap<String, String[]>();
    private static final HashMap<String, int[]> COLORS = new HashMap<String, int[]>();
    private static boolean INITED;
    private static boolean ANNOUNCED;
    private static long LAST;
    private static int TAPS;

    private Hidden() {
    }

    private static synchronized void init() {
        if (INITED) {
            return;
        }
        APIS.put("hidden_night", new String[]{
                "https://api.yujn.cn/api/heisis.php", "https://api.yujn.cn/api/wmsc.php",
                "https://api.yujn.cn/api/diaodai.php", "https://api.yujn.cn/api/rewu.php"});
        APIS.put("hidden_dance", new String[]{
                "https://api.yujn.cn/api/rewu.php", "https://api.yujn.cn/api/diaodai.php",
                "https://api.yujn.cn/api/heisis.php"});
        APIS.put("hidden_body", new String[]{
                "https://api.yujn.cn/api/wmsc.php", "https://api.yujn.cn/api/rewu.php",
                "https://api.yujn.cn/api/yuzu.php"});
        APIS.put("hidden_silk", new String[]{
                "https://api.yujn.cn/api/heisis.php", "https://api.yujn.cn/api/baisis.php",
                "https://api.yujn.cn/api/wmsc.php", "https://api.yujn.cn/api/yuzu.php"});
        APIS.put("hidden_swim", new String[]{
                "https://api.yujn.cn/api/rewu.php", "https://api.yujn.cn/api/wmsc.php",
                "https://api.yujn.cn/api/yuzu.php"});
        APIS.put("hidden_sweet", new String[]{
                "https://api.yujn.cn/api/tianmei.php", "https://api.yujn.cn/api/qingchun.php",
                "https://api.yujn.cn/api/xiaoxiao.php", "https://api.yujn.cn/api/luoli.php"});
        APIS.put("hidden_all", new String[]{
                "https://api.yujn.cn/api/juhexjj.php", "https://api.yujn.cn/api/zzxjj.php",
                "https://api.yujn.cn/api/ksxjjsp.php", "https://api.yujn.cn/api/rewu.php",
                "https://api.yujn.cn/api/tianmei.php", "https://api.yujn.cn/api/wmsc.php"});
        APIS.put("hidden_custom", new String[0]);
        APIS.put("hidden_vault", new String[0]);

        for (int i = 0; i < KEYS.length; i++) {
            COLORS.put(KEYS[i], new int[]{-15003613 + i * 131071, -12968115 - i * 262139});
        }
        INITED = true;
    }

    public static boolean isHidden(String key) {
        return key != null && key.startsWith("hidden_");
    }

    public static String[] keys() {
        return KEYS.clone();
    }

    public static CharSequence[] names() {
        return NAMES.clone();
    }

    /**
     * 拿到隐藏分类。custom / vault 每次按当前存储现算，
     * 用户新加了源或视频立刻生效。
     */
    public static Category get(Context c, String key) {
        if (!isHidden(key)) {
            return null;
        }
        init();
        if (!APIS.containsKey(key)) {
            return null;
        }
        String[] apis;
        if ("hidden_custom".equals(key)) {
            apis = customApis(c);
        } else if ("hidden_vault".equals(key)) {
            apis = vaultApis(c);
        } else {
            apis = APIS.get(key);
        }
        int idx = indexOf(key);
        String name = idx >= 0 ? NAMES[idx] : key;
        String sub = idx >= 0 ? SUBS[idx] : "隐藏";
        int[] color = COLORS.get(key);
        return new Category(key, name, sub, color[0], color[1], true, apis, new String[0]);
    }

    private static int indexOf(String key) {
        for (int i = 0; i < KEYS.length; i++) {
            if (KEYS[i].equals(key)) {
                return i;
            }
        }
        return -1;
    }

    public static Category safeGet(Context c, String key) {
        try {
            return get(c, key);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(SP, 0);
    }

    // ------------------------------------------------------------ 我的颜色源

    private static String[] customApis(Context c) {
        if (c == null) {
            return new String[0];
        }
        List<String> out = new ArrayList<String>();
        try {
            JSONArray arr = new JSONArray(sp(c).getString(K_CUSTOM, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                String u = arr.optString(i, "");
                if (!TextUtils.isEmpty(u)) {
                    out.add(u);
                }
            }
        } catch (Throwable ignored) {
        }
        return out.toArray(new String[0]);
    }

    public static int customCount(Context c) {
        return customApis(c).length;
    }

    /** 粘进来的每行一条：可以是接口地址，也可以是 mp4/m3u8 直链。 */
    public static int addCustomSources(Context c, String text) {
        if (c == null || TextUtils.isEmpty(text)) {
            return 0;
        }
        JSONArray arr = new JSONArray();
        try {
            arr = new JSONArray(sp(c).getString(K_CUSTOM, "[]"));
        } catch (Throwable ignored) {
        }
        java.util.HashSet<String> exist = new java.util.HashSet<String>();
        for (int i = 0; i < arr.length(); i++) {
            exist.add(arr.optString(i, ""));
        }
        int added = 0;
        String[] lines = text.split("\\r?\\n");
        for (int i = 0; i < lines.length; i++) {
            String s = lines[i].trim();
            if (s.length() < 8 || !s.startsWith("http")) {
                continue;
            }
            if (exist.add(s)) {
                arr.put(s);
                added++;
            }
        }
        if (added > 0) {
            sp(c).edit().putString(K_CUSTOM, arr.toString()).apply();
        }
        return added;
    }

    public static void clearCustom(Context c) {
        if (c != null) {
            sp(c).edit().remove(K_CUSTOM).apply();
        }
    }

    // ------------------------------------------------------------ 私密柜

    private static String[] vaultApis(Context c) {
        if (c == null) {
            return new String[0];
        }
        List<String> out = new ArrayList<String>();
        try {
            JSONArray arr = new JSONArray(sp(c).getString(K_VAULT, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                String u = o == null ? null : o.optString("u", "");
                if (!TextUtils.isEmpty(u)) {
                    out.add(u);
                }
            }
        } catch (Throwable ignored) {
        }
        return out.toArray(new String[0]);
    }

    public static int vaultCount(Context c) {
        return vaultApis(c).length;
    }

    public static int addVaultVideos(Context c, List<String> uris, List<String> names) {
        if (c == null || uris == null || uris.isEmpty()) {
            return 0;
        }
        JSONArray arr = new JSONArray();
        try {
            arr = new JSONArray(sp(c).getString(K_VAULT, "[]"));
        } catch (Throwable ignored) {
        }
        java.util.HashSet<String> exist = new java.util.HashSet<String>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null) {
                exist.add(o.optString("u", ""));
            }
        }
        int added = 0;
        for (int i = 0; i < uris.size(); i++) {
            String u = uris.get(i);
            if (TextUtils.isEmpty(u) || !exist.add(u)) {
                continue;
            }
            try {
                JSONObject o = new JSONObject();
                o.put("u", u);
                o.put("t", names != null && i < names.size() ? names.get(i) : "私密视频");
                arr.put(o);
                added++;
            } catch (Throwable ignored) {
            }
        }
        if (added > 0) {
            sp(c).edit().putString(K_VAULT, arr.toString()).apply();
        }
        return added;
    }

    public static void clearVault(Context c) {
        if (c != null) {
            sp(c).edit().remove(K_VAULT).apply();
        }
    }

    public static void startVaultPicker(Activity activity) {
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("video/*");
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            activity.startActivityForResult(intent, REQ_VAULT);
        } catch (Throwable th) {
            Util.toast(activity, "打不开文件选择器：" + th.getClass().getSimpleName());
        }
    }

    /** 交回给设置页的 onActivityResult 用。 */
    public static int handleVaultResult(Context c, Intent data) {
        if (data == null) {
            return 0;
        }
        List<String> uris = new ArrayList<String>();
        List<String> names = new ArrayList<String>();
        android.content.ClipData clip = data.getClipData();
        if (clip != null) {
            for (int i = 0; i < clip.getItemCount(); i++) {
                Uri uri = clip.getItemAt(i).getUri();
                if (uri != null) {
                    uris.add(uri.toString());
                    names.add("私密视频 " + (i + 1));
                }
            }
        } else if (data.getData() != null) {
            uris.add(data.getData().toString());
            names.add("私密视频");
        }
        for (int i = 0; i < uris.size(); i++) {
            try {
                c.getContentResolver().takePersistableUriPermission(
                        Uri.parse(uris.get(i)), Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Throwable ignored) {
            }
        }
        return addVaultVideos(c, uris, names);
    }

    /** 私密柜里的视频，供分类页展示。 */
    public static List<Video> vaultVideos(Context c) {
        List<Video> out = new ArrayList<Video>();
        if (c == null) {
            return out;
        }
        try {
            JSONArray arr = new JSONArray(sp(c).getString(K_VAULT, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                String u = o.optString("u", "");
                if (TextUtils.isEmpty(u)) {
                    continue;
                }
                out.add(new Video(o.optString("t", "私密视频"), u, "hidden_vault", "私密柜", 0L, true));
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /** 我的颜色源列表，供分类页展示。 */
    public static List<Video> customVideos(Context c) {
        List<Video> out = new ArrayList<Video>();
        String[] apis = customApis(c);
        for (int i = 0; i < apis.length; i++) {
            out.add(new Video(Util.nameFromUrl(apis[i]), apis[i], "hidden_custom", "我的颜色源", 0L, false));
        }
        return out;
    }

    public static List<Video> vaultList() {
        List<Video> out = new ArrayList<Video>();
        for (int i = 0; i < KEYS.length; i++) {
            if ("hidden_vault".equals(KEYS[i])) {
                continue;
            }
            out.add(new Video(String.valueOf(NAMES[i]), "vault:" + KEYS[i],
                    "hidden_custom", "隐藏区", 0L, false));
        }
        return out;
    }

    // ------------------------------------------------------------ 面板

    public static void openPicker(final Activity activity) {
        init();
        final String[] items = new String[NAMES.length + 2];
        for (int i = 0; i < NAMES.length; i++) {
            String extra;
            if ("hidden_custom".equals(KEYS[i])) {
                extra = "（" + customCount(activity) + " 条）";
            } else if ("hidden_vault".equals(KEYS[i])) {
                extra = "（" + vaultCount(activity) + " 个）";
            } else {
                extra = "";
            }
            items[i] = NAMES[i] + extra;
        }
        items[NAMES.length] = "＋ 添加我的颜色源链接";
        items[NAMES.length + 1] = "管理私密柜（加视频 / 清空）";

        new AlertDialog.Builder(activity)
                .setTitle("颜色区")
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (which < NAMES.length) {
                            String key = KEYS[which];
                            if ("hidden_custom".equals(key) && customCount(activity) == 0) {
                                promptCustom(activity, true);
                            } else if ("hidden_vault".equals(key) && vaultCount(activity) == 0) {
                                startVaultPicker(activity);
                                Util.toast(activity, "先挑几个视频放进私密柜");
                            } else {
                                PlayerActivity.startApi(activity, key);
                            }
                        } else if (which == NAMES.length) {
                            promptCustom(activity, false);
                        } else {
                            manageVault(activity);
                        }
                    }
                })
                .setNegativeButton("关闭", null)
                .setNeutralButton("关于", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        activity.startActivity(new Intent(activity, AboutActivity.class));
                    }
                })
                .show();
    }

    private static void promptCustom(final Activity activity, final boolean playAfter) {
        final EditText input = new EditText(activity);
        input.setHint("每行一条：接口地址 或 mp4/m3u8 直链");
        input.setMinLines(4);
        input.setGravity(android.view.Gravity.TOP | android.view.Gravity.LEFT);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        new AlertDialog.Builder(activity)
                .setTitle("我的颜色源")
                .setView(input)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        int n = addCustomSources(activity, input.getText().toString());
                        Util.toast(activity, n > 0 ? "加了 " + n + " 条源" : "没识别到新链接");
                        if (playAfter && n > 0) {
                            PlayerActivity.startApi(activity, "hidden_custom");
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private static void manageVault(final Activity activity) {
        int n = vaultCount(activity);
        String[] actions = {"＋ 从手机里加视频", "🎲 随机播一条", "清空私密柜（" + n + " 个）"};
        new AlertDialog.Builder(activity)
                .setTitle("私密柜")
                .setItems(actions, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (which == 0) {
                            startVaultPicker(activity);
                        } else if (which == 1) {
                            if (vaultCount(activity) == 0) {
                                Util.toast(activity, "私密柜还是空的");
                            } else {
                                PlayerActivity.startApi(activity, "hidden_vault");
                            }
                        } else {
                            new AlertDialog.Builder(activity)
                                    .setTitle("确定清空私密柜？")
                                    .setPositiveButton("清空", new DialogInterface.OnClickListener() {
                                        @Override
                                        public void onClick(DialogInterface d, int w) {
                                            clearVault(activity);
                                            Util.toast(activity, "已清空");
                                        }
                                    })
                                    .setNegativeButton("算了", null)
                                    .show();
                        }
                    }
                })
                .show();
    }

    // ------------------------------------------------------------ 五连点解锁

    public static void onTap(Activity activity) {
        long now = SystemClock.elapsedRealtime();
        long gap = now - LAST;
        LAST = now;
        if (gap > 1500L) {
            TAPS = 0;
        }
        TAPS++;
        if (TAPS < 5) {
            return;
        }
        TAPS = 0;
        if (!ANNOUNCED) {
            ANNOUNCED = true;
            Util.toast(activity, "颜色区已解锁");
        }
        openPicker(activity);
    }

    public static void openVault(Activity activity) {
        openPicker(activity);
    }

    public static void play(Activity activity, int index) {
        init();
        if (index >= 0 && index < KEYS.length) {
            PlayerActivity.startApi(activity, KEYS[index]);
        }
    }

    public static void play(Activity activity, String catKey, String url) {
        if (url != null && url.startsWith("vault:")) {
            PlayerActivity.startApi(activity, url.substring(6));
        } else {
            PlayerActivity.start(activity, catKey, url);
        }
    }

    /** 旧的注入口子，现在设置页有自己的卡片，这里保留空实现避免外部调用报错。 */
    public static void safeInject(Activity activity) {
    }

}