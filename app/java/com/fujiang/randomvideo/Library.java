package com.fujiang.randomvideo;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class Library {
    public static final String C_ALL = "all";
    public static final String C_FAV = "fav";

    private static final String SP = "random_video_prefs";
    private static final String K_FAVS = "favs";
    private static final String K_HISTORY = "history";
    private static final String K_MANUAL = "manual";
    private static final String K_DISLIKE = "dislikes";
    private static final String K_SOURCE_URL = "source_url";
    private static final String K_API_STATS = "api_stats";

    private static Library instance;

    private final Context app;
    private final SharedPreferences sp;
    private final Random rnd = new Random();
    private final ExecutorService bg = Executors.newSingleThreadExecutor();

    public final List<Category> categories = new ArrayList<Category>();
    private final LinkedHashMap<String, List<Video>> byCat = new LinkedHashMap<String, List<Video>>();
    private final HashSet<String> urlSet = new HashSet<String>();
    private final LinkedHashSet<String> favs = new LinkedHashSet<String>();
    private final HashMap<String, Video> byUrl = new HashMap<String, Video>();
    /** 一条播放记录。 */
    public static final class Hist {
        public final String url;
        public String title;
        public String category;
        public long time;
        /** 源站封面地址（可为空）。 */
        public String cover;

        public Hist(String url, String title, String category, long time) {
            this(url, title, category, time, "");
        }

        public Hist(String url, String title, String category, long time, String cover) {
            this.url = url;
            this.title = title == null ? "" : title;
            this.category = category == null ? C_ALL : category;
            this.time = time;
            this.cover = cover == null ? "" : cover;
        }
    }

    private final LinkedList<Hist> history = new LinkedList<Hist>();
    private final HashMap<String, Hist> histIndex = new HashMap<String, Hist>();
    private final List<Map.Entry<String, String>> keywordIndex = new ArrayList<Map.Entry<String, String>>();

    /** 每个分类最近播过的若干条，随机时避开，避免同一条反复出现。 */
    private final HashMap<String, LinkedList<String>> recent = new HashMap<String, LinkedList<String>>();
    /** 「不对」次数缓存，避免每次排序都去解析 JSON。 */
    private final HashMap<String, Integer> dislikeCache = new HashMap<String, Integer>();
    private boolean dislikeLoaded = false;

    private String statsCache;
    private boolean statsDirty = true;

    public volatile boolean dirty = true;
    public boolean loaded = false;
    public String statusLocal = "";
    public String statusOnline = "";

    private Library(Context context) {
        this.app = context.getApplicationContext();
        this.sp = this.app.getSharedPreferences(SP, 0);
        initCategories();
        loadPrefs();
        for (int i = 0; i < this.categories.size(); i++) {
            this.byCat.put(this.categories.get(i).key, new ArrayList<Video>());
        }
    }

    public static synchronized Library get(Context context) {
        if (instance == null) {
            instance = new Library(context);
        }
        return instance;
    }

    // ---------------------------------------------------------------- 分类

    /** 本次实测可用的在线视频接口（全部返回 mp4 直链）。 */
    private static final String A_COS = "https://api.yujn.cn/api/COS.php";
    private static final String A_BAISIS = "https://api.yujn.cn/api/baisis.php";
    private static final String A_HEISIS = "https://api.yujn.cn/api/heisis.php";
    private static final String A_WMSC = "https://api.yujn.cn/api/wmsc.php";
    private static final String A_JKSP = "https://api.yujn.cn/api/jksp.php";
    private static final String A_LUOLI = "https://api.yujn.cn/api/luoli.php";
    private static final String A_TIANMEI = "https://api.yujn.cn/api/tianmei.php";
    private static final String A_QINGCHUN = "https://api.yujn.cn/api/qingchun.php";
    private static final String A_XIAOXIAO = "https://api.yujn.cn/api/xiaoxiao.php";
    private static final String A_REWU = "https://api.yujn.cn/api/rewu.php";
    private static final String A_DIAODAI = "https://api.yujn.cn/api/diaodai.php";
    private static final String A_HANFU = "https://api.yujn.cn/api/hanfu.php";
    private static final String A_MANZHAN = "https://api.yujn.cn/api/manzhan.php";
    private static final String A_KSDZ = "https://api.yujn.cn/api/ksbianzhuang.php";
    private static final String A_BIANZHUANG = "https://api.yujn.cn/api/bianzhuang.php";
    private static final String A_CHUANDA = "https://api.yujn.cn/api/chuanda.php";
    private static final String A_KSSP = "https://api.yujn.cn/api/ksxjjsp.php";
    private static final String A_KSFC = "https://api.yujn.cn/api/ks_fc.php";
    private static final String A_NVGAO = "https://api.yujn.cn/api/nvgao.php";
    private static final String A_NVDA = "https://api.yujn.cn/api/nvda.php";
    private static final String A_XJJ = "https://api.yujn.cn/api/xjj.php";
    private static final String A_ZZXJJ = "https://api.yujn.cn/api/zzxjj.php";
    private static final String A_ZZXJJ2 = "https://api.yujn.cn/api/zzxjj2.php";
    private static final String A_JUHE = "https://api.yujn.cn/api/juhexjj.php";
    private static final String A_SHEJIE = "https://api.yujn.cn/api/shejie.php";
    private static final String A_NDYM = "https://api.yujn.cn/api/ndym.php";
    private static final String A_SBKL = "https://api.yujn.cn/api/sbkl.php";
    private static final String A_SHWD = "https://api.yujn.cn/api/shwd.php";
    private static final String A_YUZU = "https://api.yujn.cn/api/yuzu.php";
    /**
     * 唯一实测能返回「视频原始标题 + 封面」的接口：
     * {"code":200,"data":[{"title":"@xx 的精彩视频","cover":"https://p23.a.yximgs.com/...jpg","video":"https://...mp4"}]}
     * 上游反爬，单次成功率约 1/3，解析器内部会重试多轮。
     */
    private static final String A_KS_SJ = "https://api.yujn.cn/api/ks_sj.php";

    private static String[] apis(String... items) {
        return items;
    }

    private void initCategories() {
        this.categories.clear();

        this.categories.add(new Category("jk", "JK制服", "水手服・百褶裙", -13711873, -9810433, true,
                apis(A_JKSP, A_BAISIS, A_NVGAO, A_XJJ, A_QINGCHUN, A_XIAOXIAO, A_KSSP),
                "jk", "jksp", "制服", "水手服", "水手", "百褶裙", "百褶", "校服", "seifuku", "school uniform", "jk制服", "jk裙", "jk服"));

        this.categories.add(new Category("loli", "洛丽", "甜系・轻洛丽", -34120, -23445, true,
                apis(A_LUOLI, A_COS, A_TIANMEI, A_QINGCHUN, A_XIAOXIAO, A_BAISIS),
                "loli", "洛丽", "轻洛丽", "甜系", "luoli", "萝莉", "lori", "小个子", "软妹"));

        this.categories.add(new Category("lolita", "洛丽塔", "洋装・蕾丝・女仆", -5153537, -9818923, true,
                apis(A_LUOLI, A_KSDZ, A_MANZHAN, A_COS, A_BIANZHUANG, A_CHUANDA),
                "lolita", "洛丽塔", "jsk", "茶会", "lolita裙", "蓬蓬裙", "连衣", "小裙"));

        this.categories.add(new Category("cosplay", "Cosplay", "二次元・角色", -14424619, -15822936, true,
                apis(A_COS, A_MANZHAN, A_KSDZ, A_KSSP, A_KSFC),
                "cosplay", "cos", "coser", "二次元", "动漫", "角色", "出c", "cos服", "角色扮演", "cos图", "cos照", "痛包"));

        this.categories.add(new Category("tianmei", "甜妹", "甜系・清纯・软萌", -2371585, -6249696, true,
                apis(A_TIANMEI, A_QINGCHUN, A_XIAOXIAO, A_LUOLI, A_BAISIS, A_JKSP, A_NDYM, A_NVGAO),
                "甜妹", "tianmei", "甜美", "清纯", "qingchun", "软萌", "可爱", "keai", "萌妹", "甜心", "治愈", "笑容"));

        this.categories.add(new Category("hanfu", "汉服", "古风・国风", -1545637, -7462080, true,
                apis(A_HANFU, A_MANZHAN, A_CHUANDA, A_BIANZHUANG, A_KSDZ),
                "汉服", "hanfu", "古风", "国风", "马面", "明制", "襦裙", "旗袍", "qipao", "唐装", "汉元素", "宋制", "齐胸"));

        this.categories.add(new Category("night", "深夜", "黑丝・网袜・吊带", -15003613, -12968115, true,
                apis(A_HEISIS, A_WMSC, A_DIAODAI, A_REWU, A_BAISIS, A_YUZU),
                "深夜", "夜里", "night", "午夜", "夜拍", "夜跑", "凌晨", "夜景", "夜晚", "深夜档"));

        this.categories.add(new Category("dress", "洋装", "蕾丝・女仆・花嫁", -13990085, -16740807, true,
                apis(A_KSDZ, A_BIANZHUANG, A_CHUANDA, A_MANZHAN, A_LUOLI, A_KSFC),
                "洋装", "蕾丝", "女仆", "dress", "公主", "礼服", "小裙子", "洋服", "洛可可", "maid", "蓬蓬", "蝴蝶结"));

        this.categories.add(new Category("bride", "花嫁", "婚纱・白纱・写真", -14013421, -14995249, true,
                apis(A_MANZHAN, A_KSDZ, A_CHUANDA, A_LUOLI, A_BIANZHUANG),
                "花嫁", "婚纱", "白纱", "wedding", "bride", "头纱", "婚纱照", "新娘", "敬酒服", "秀禾"));

        this.categories.add(new Category("expo", "漫展", "现场・玩家・角色", -13599024, -14752592, true,
                apis(A_MANZHAN, A_COS, A_KSFC, A_KSSP, A_KSDZ),
                "漫展", "现场", "玩家", "expo", "展子", "cp展", "cj", "chinajoy", "wf展", "展台", "返图"));

        this.categories.add(new Category("diaodai", "吊带", "贴身・露肩", -41846, -8712144, true,
                apis(A_DIAODAI, A_REWU, A_WMSC, A_HEISIS, A_YUZU, A_BAISIS),
                "吊带", "camisole", "吊带裙", "露肩", "背心", "小吊带", "抹胸", "一字肩"));

        this.categories.add(new Category("silk", "丝袜", "白丝・黑丝・连裤袜", -5005825, -10797154, true,
                apis(A_HEISIS, A_WMSC, A_BAISIS, A_REWU, A_YUZU, A_DIAODAI),
                "丝袜", "连裤袜", "pantyhose", "网袜", "丝袜腿", "stocking", "长筒袜", "丝袜高跟", "透肉"));

        this.categories.add(new Category("baisi", "白丝", "白丝・白袜・清纯", -1601965, -6250335, true,
                apis(A_BAISIS, A_JKSP, A_YUZU, A_QINGCHUN, A_WMSC),
                "白丝", "baisi", "白袜", "过膝袜", "白丝袜", "小白袜", "白丝腿", "白丝高跟"));

        this.categories.add(new Category("heisi", "黑丝", "黑丝・黑袜・御姐", -13686784, -14540254, true,
                apis(A_HEISIS, A_WMSC, A_BAISIS, A_REWU, A_DIAODAI),
                "黑丝", "heisi", "黑袜", "黑丝袜", "黑丝腿", "渔网"));

        this.categories.add(new Category("leg", "美腿", "长腿・腿部特写", -1602350, -7598616, true,
                apis(A_YUZU, A_WMSC, A_BAISIS, A_HEISIS, A_DIAODAI),
                "美腿", "长腿", "大长腿", "legging", "legs", "腿部", "高跟", "高跟鞋", "细腿", "小腿", "靴子", "短裤"));

        this.categories.add(new Category("dance", "热舞", "舞蹈・翻跳・舞台", -13399935, -14996966, true,
                apis(A_REWU, A_YUZU, A_DIAODAI, A_WMSC),
                "热舞", "舞蹈", "跳舞", "翻跳", "舞台", "dance", "dancing", "cover", "宅舞", "韩舞", "爵士", "kpop", "编舞", "直拍"));

        this.categories.add(new Category("swim", "泳装", "海边・泳池・比基尼", -13250305, -16089396, true,
                apis(A_REWU, A_WMSC, A_YUZU, A_DIAODAI, A_BAISIS),
                "泳装", "泳衣", "比基尼", "bikini", "swim", "沙滩", "泳池", "海边", "湿身", "冲浪", "溯溪", "水上"));

        this.categories.add(new Category("campus", "校园", "操场・走廊・教室", -10929, -1683200, true,
                apis(A_NVGAO, A_XJJ, A_QINGCHUN, A_XIAOXIAO, A_ZZXJJ, A_KSSP),
                "校园", "操场", "走廊", "教室", "宿舍", "课间", "升旗", "campus", "教学楼", "图书馆", "社团"));

        this.categories.add(new Category("student", "学生装", "校服・水手服・日常", -1162808, -9410920, true,
                apis(A_NVGAO, A_JKSP, A_XJJ, A_QINGCHUN, A_XIAOXIAO),
                "学生装", "女高", "nvgao", "高中生", "学妹", "学姐", "学妹装", "新生", "学园", "校花"));

        this.categories.add(new Category("outfit", "穿搭", "闺蜜・变装・OOTD", -8585269, -16750244, true,
                apis(A_BIANZHUANG, A_CHUANDA, A_KSDZ, A_KSFC, A_SHEJIE, A_KSSP),
                "穿搭", "变装", "闺蜜", "outfit", "ootd", "搭配", "换装", "lookbook", "开箱", "试穿", "上身", "开箱试穿"));

        this.categories.add(new Category("street", "街拍", "街拍・随拍・路人", -13408664, -14406270, true,
                apis(A_SHEJIE, A_NVDA, A_NDYM, A_SHWD, A_SBKL, A_XIAOXIAO),
                "街拍", "路人", "street", "街头", "抓拍", "自拍", "walk", "扫街", "路拍", "偶遇", "对镜自拍"));

        this.categories.add(new Category("kuaishou", "快手", "变装・街拍・翻唱", -14409621, -14239885, true,
                apis(A_KS_SJ, A_KSSP, A_KSDZ, A_KSFC, A_ZZXJJ2, A_JUHE, A_ZZXJJ),
                "快手", "kuaishou", "kssp", "翻唱", "老铁", "快影", "快手sp"));

        this.categories.add(new Category("recommend", "推荐", "带原始标题・作品名", -13599761, -12911949, true,
                apis(A_KS_SJ, A_JUHE, A_ZZXJJ, A_KSSP),
                "推荐", "热门", "recommend", "精选推荐", "作品", "上热门"));

        this.categories.add(new Category("daily", "日常", "街拍・随拍・生活", -12150, -5211332, true,
                apis(A_NVDA, A_NDYM, A_SHEJIE, A_QINGCHUN, A_XIAOXIAO, A_YUZU,
                        A_SBKL, A_SHWD, A_KSFC, A_ZZXJJ, A_ZZXJJ2, A_KSSP),
                "日常", "daily", "vlog", "生活", "随拍", "记录", "日记", "碎片", "上学", "通勤", "周末", "一天", "plog"));

        this.categories.add(new Category("mix", "合集", "多源聚合・一次到底", -13092849, -16047070, true,
                apis(A_KS_SJ, A_JUHE, A_ZZXJJ, A_ZZXJJ2, A_KSSP, A_XJJ, A_NVGAO, A_REWU,
                        A_TIANMEI, A_HEISIS, A_WMSC, A_LUOLI, A_COS, A_NDYM, A_SHEJIE),
                "合集", "精选", "聚合", "合集版", "mix", "collection", "合辑", "混剪", "汇总", "打包"));

        this.categories.add(new Category(C_ALL, "全部在线", "全站所有源一起随机", -7352065, -12624971, true,
                apis(A_KS_SJ, A_JUHE, A_ZZXJJ, A_ZZXJJ2, A_XJJ, A_NVGAO, A_REWU, A_TIANMEI,
                        A_KSSP, A_HEISIS, A_WMSC, A_LUOLI, A_COS, A_BAISIS, A_QINGCHUN,
                        A_NVDA, A_SHEJIE, A_KSDZ, A_KSFC),
                new String[0]));

        this.categories.add(new Category(C_FAV, "我的收藏", "手动收藏", -10929, -4684277, new String[0]));
        buildKeywordIndex();
    }

    private void buildKeywordIndex() {
        this.keywordIndex.clear();
        for (int i = 0; i < this.categories.size(); i++) {
            Category c = this.categories.get(i);
            if (c.isSpecial()) {
                continue;
            }
            for (int j = 0; j < c.keywords.length; j++) {
                if (!TextUtils.isEmpty(c.keywords[j])) {
                    this.keywordIndex.add(new java.util.AbstractMap.SimpleEntry<String, String>(
                            IO.lower(c.keywords[j]), c.key));
                }
            }
        }
        Collections.sort(this.keywordIndex, new Comparator<Map.Entry<String, String>>() {
            @Override
            public int compare(Map.Entry<String, String> a, Map.Entry<String, String> b) {
                int la = a.getKey().length();
                int lb = b.getKey().length();
                if (la != lb) {
                    return lb - la;
                }
                int pa = catPriority(a.getValue());
                int pb = catPriority(b.getValue());
                return pa != pb ? pa - pb : a.getKey().compareTo(b.getKey());
            }
        });
    }

    private int catPriority(String key) {
        for (int i = 0; i < this.categories.size(); i++) {
            if (this.categories.get(i).key.equals(key)) {
                return i;
            }
        }
        return 999;
    }

    private String displayNameOf(String key) {
        if (key == null || key.length() == 0) {
            return "自定义";
        }
        return key.substring(0, 1).toUpperCase(Locale.ROOT) + key.substring(1);
    }

    private String ensureCategory(String key) {
        String k = IO.lower(key).trim();
        if (k.startsWith("hidden_")) {
            // 隐藏区分类不参与首页分类表，避免出现重复的「hidden_xxx」分类
            return "daily";
        }
        if (k.length() == 0) {
            k = "daily";
        }
        if (C_ALL.equals(k) || C_FAV.equals(k)) {
            k = "daily";
        }
        for (int i = 0; i < this.categories.size(); i++) {
            if (this.categories.get(i).key.equals(k)) {
                return k;
            }
        }
        int[][] palettes = new int[][]{
                {-10036737, -12948481}, {-28720, -4965728}, {-8650832, -14775723}, {-15253, -5215714}};
        int[] pal = palettes[(k.hashCode() & Integer.MAX_VALUE) % palettes.length];
        Category category = new Category(k, displayNameOf(k), "自定义分类", pal[0], pal[1], new String[0]);
        int insertAt = this.categories.size();
        for (int i = 0; i < this.categories.size(); i++) {
            if (this.categories.get(i).isSpecial()) {
                insertAt = i;
                break;
            }
        }
        this.categories.add(insertAt, category);
        this.byCat.put(k, new ArrayList<Video>());
        return k;
    }

    private Map<String, Integer> catPriorityMap;

    private Map<String, Integer> priorityMap() {
        if (this.catPriorityMap == null || this.catPriorityMap.size() != this.categories.size()) {
            Map<String, Integer> m = new HashMap<String, Integer>();
            for (int i = 0; i < this.categories.size(); i++) {
                m.put(this.categories.get(i).key, Integer.valueOf(i));
            }
            this.catPriorityMap = m;
        }
        return this.catPriorityMap;
    }

    String classify(String primary, String secondary) {
        return Classifier.classify(primary, secondary, this.keywordIndex, priorityMap(), "daily");
    }

    // ---------------------------------------------------------------- 装载

    public synchronized void loadAll() {
        this.loaded = false;
        this.byCat.clear();
        for (int i = 0; i < this.categories.size(); i++) {
            this.byCat.put(this.categories.get(i).key, new ArrayList<Video>());
        }
        this.urlSet.clear();
        this.byUrl.clear();
        this.recent.clear();
        this.statusLocal = "";
        this.statusOnline = "";
        scanLocal();
        loadAssetSources();
        loadManual();
        String src = getSourceUrl();
        if (!TextUtils.isEmpty(src)) {
            loadRemoteSource(src);
        }
        this.loaded = true;
        this.dirty = false;
        this.statsDirty = true;
    }

    private void scanLocal() {
        Cursor cursor = null;
        int count = 0;
        try {
            ContentResolver cr = this.app.getContentResolver();
            Uri uri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
            String[] projection = Build.VERSION.SDK_INT >= 29
                    ? new String[]{"_id", "title", "_display_name", "duration", "bucket_display_name", "relative_path"}
                    : new String[]{"_id", "title", "_display_name", "duration", "bucket_display_name"};
            cursor = cr.query(uri, projection, null, null, "date_added DESC");
            if (cursor != null) {
                int iId = cursor.getColumnIndex("_id");
                int iTitle = cursor.getColumnIndex("title");
                int iName = cursor.getColumnIndex("_display_name");
                int iDur = cursor.getColumnIndex("duration");
                int iBucket = cursor.getColumnIndex("bucket_display_name");
                int iPath = cursor.getColumnIndex("relative_path");
                while (cursor.moveToNext()) {
                    long id = getLong(cursor, iId);
                    if (id <= 0) {
                        continue;
                    }
                    long dur = getLong(cursor, iDur);
                    if (dur > 0 && dur < 800) {
                        continue;
                    }
                    String name = getStr(cursor, iName);
                    String title = getStr(cursor, iTitle);
                    String bucket = getStr(cursor, iBucket);
                    String path = getStr(cursor, iPath);
                    String cat = classify(path + " " + bucket, name + " " + title);
                    String shown = stripExt(name);
                    if (TextUtils.isEmpty(shown)) {
                        shown = stripExt(title);
                    }
                    if (TextUtils.isEmpty(shown)) {
                        shown = "未命名视频";
                    }
                    addVideo(new Video(shown, ContentUris.withAppendedId(uri, id).toString(), cat, "本地", dur, true));
                    count++;
                }
            }
            this.statusLocal = "本地视频 " + count + " 个";
        } catch (Throwable th) {
            this.statusLocal = "本地扫描失败：" + th.getClass().getSimpleName();
            IO.log("scanLocal error " + th);
        } finally {
            if (cursor != null) {
                try {
                    cursor.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static long getLong(Cursor c, int index) {
        if (index >= 0 && !c.isNull(index)) {
            try {
                return c.getLong(index);
            } catch (Throwable ignored) {
            }
        }
        return 0L;
    }

    private static String getStr(Cursor c, int index) {
        if (index < 0 || c.isNull(index)) {
            return "";
        }
        try {
            String s = c.getString(index);
            return s == null ? "" : s;
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static String stripExt(String s) {
        if (s == null) {
            return "";
        }
        int dot = s.lastIndexOf('.');
        return dot > 0 ? s.substring(0, dot) : s;
    }

    private void loadAssetSources() {
        try {
            int n = parseSourceJson(IO.readAll(this.app.getAssets().open("sources.json")), "内置演示");
            if (n > 0) {
                this.statusOnline = "内置演示源 " + n + " 条";
            }
        } catch (Throwable th) {
            IO.log("asset source error " + th);
        }
    }

    public synchronized int parseSourceJson(String json, String label) {
        int total = 0;
        try {
            JSONObject root = new JSONObject(json);
            if (root.has("sources")) {
                JSONArray arr = root.optJSONArray("sources");
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        try {
                            JSONObject one = arr.optJSONObject(i);
                            if (one != null) {
                                String name = one.optString("name", label);
                                JSONObject videos = one.optJSONObject("videos");
                                if (videos != null) {
                                    total += addFromCategoryObject(videos, name);
                                }
                            }
                        } catch (Throwable th) {
                            IO.log("parse source item error " + th);
                        }
                    }
                }
            } else {
                total += addFromCategoryObject(root, label);
            }
        } catch (Throwable th) {
            IO.log("parse source error " + th);
        }
        return total;
    }

    private int addFromCategoryObject(JSONObject obj, String label) {
        int count = 0;
        java.util.Iterator<String> keys = obj.keys();
        while (keys.hasNext()) {
            String catKey = keys.next();
            JSONArray arr = obj.optJSONArray(catKey);
            if (arr == null) {
                continue;
            }
            String cat = ensureCategory(catKey);
            for (int i = 0; i < arr.length(); i++) {
                String url = null;
                String title = null;
                Object item = arr.opt(i);
                if (item instanceof String) {
                    url = (String) item;
                } else if (item instanceof JSONObject) {
                    JSONObject o = (JSONObject) item;
                    url = o.optString("url", null);
                    title = o.optString("title", null);
                }
                if (url == null || url.length() == 0 || "null".equals(url)) {
                    continue;
                }
                if (title == null || "null".equals(title) || title.length() == 0) {
                    title = guessTitle(url);
                }
                addVideo(new Video(title, url, cat, label, 0L, false));
                count++;
            }
        }
        return count;
    }

    private static String guessTitle(String url) {
        return Util.nameFromUrl(url);
    }

    public synchronized int loadRemoteSource(String url) {
        java.net.HttpURLConnection conn = null;
        try {
            conn = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(12000);
            conn.setRequestProperty("User-Agent", "RandomVideo/1.3 (Android)");
            conn.setInstanceFollowRedirects(true);
            int code = conn.getResponseCode();
            if (code != 200) {
                this.statusOnline = "在线源 HTTP " + code;
                return 0;
            }
            String body = IO.readLimited(conn.getInputStream(), 4 * 1024 * 1024);
            int n = parseSourceJson(body, "在线源");
            this.statusOnline = "在线源 " + n + " 条";
            this.statsDirty = true;
            return n;
        } catch (Throwable th) {
            this.statusOnline = "在线源失败：" + th.getClass().getSimpleName();
            IO.log("remote source error " + th);
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

    // ---------------------------------------------------------------- 收藏 / 历史 / 不喜欢

    private void loadPrefs() {
        this.favs.clear();
        java.util.Set<String> saved = this.sp.getStringSet(K_FAVS, null);
        if (saved != null) {
            this.favs.addAll(saved);
        }
        this.history.clear();
        this.histIndex.clear();
        boolean backfill = false;
        try {
            String s = this.sp.getString(K_HISTORY, "");
            if (!TextUtils.isEmpty(s)) {
                JSONArray arr = new JSONArray(s);
                for (int i = 0; i < arr.length(); i++) {
                    Object item = arr.opt(i);
                    Hist h = null;
                    if (item instanceof JSONObject) {
                        JSONObject o = (JSONObject) item;
                        String u = o.optString("u", "");
                        if (!TextUtils.isEmpty(u)) {
                            h = new Hist(u, o.optString("t", ""), o.optString("c", C_ALL),
                                    o.optLong("ts", 0L), o.optString("cv", ""));
                        }
                    } else if (item instanceof String) {
                        // 1.3.3 及更早只存了 URL
                        String u = (String) item;
                        if (!TextUtils.isEmpty(u)) {
                            h = new Hist(u, "", C_ALL, 0L);
                        }
                    }
                    if (h != null && !this.histIndex.containsKey(h.url)) {
                        if (TextUtils.isEmpty(h.title)) {
                            // 老版本只存了 URL，这里补一个可读标题，下次写回时持久化
                            h.title = deriveTitle(h.url, h.category, h.time);
                            backfill = true;
                        }
                        if (h.category == null || h.category.length() == 0) {
                            h.category = C_ALL;
                        }
                        this.history.add(h);
                        this.histIndex.put(h.url, h);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        if (backfill) {
            saveHistory();
        }
    }

    /** 标题兜底：记录里的标题 → 片库标题 → 分类名 + 时间 → URL 末段。 */
    public String titleOf(Hist h) {
        if (h == null) {
            return "视频";
        }
        Video v = find(h.url);
        Category c = h.category == null ? null : category(h.category);
        return Title.resolve(h.title, v == null ? null : v.title,
                c == null ? null : c.name, h.url, h.time);
    }

    /** 从分类 / 时间 / URL 推出一个能看的标题（老数据专用）。 */
    public String deriveTitle(String url, String category, long time) {
        Category c = category == null ? null : category(category);
        return Title.derive(url, c == null ? null : c.name, time);
    }

    public boolean isFav(Video v) {
        return v != null && this.favs.contains(v.url);
    }

    public boolean toggleFav(Video v) {
        if (v == null) {
            return false;
        }
        final boolean added;
        if (this.favs.contains(v.url)) {
            this.favs.remove(v.url);
            added = false;
        } else {
            this.favs.add(v.url);
            added = true;
        }
        final Set<String> snapshot = new HashSet<String>(this.favs);
        this.bg.execute(new Runnable() {
            @Override
            public void run() {
                sp.edit().putStringSet(K_FAVS, snapshot).apply();
            }
        });
        return added;
    }

    public int favCount() {
        return this.favs.size();
    }

    public synchronized void addHistory(Video v) {
        if (v == null || v.url.length() == 0) {
            return;
        }
        Hist old = this.histIndex.remove(v.url);
        this.history.remove(old);
        String cover = v.cover != null && v.cover.length() > 0 ? v.cover
                : (old == null ? "" : old.cover);
        Hist h = new Hist(v.url, v.title, v.category, System.currentTimeMillis(), cover);
        this.history.addFirst(h);
        this.histIndex.put(v.url, h);
        while (this.history.size() > 200) {
            Hist last = this.history.removeLast();
            if (last != null) {
                this.histIndex.remove(last.url);
            }
        }
        saveHistory();
    }

    private void saveHistory() {
        final JSONArray arr = new JSONArray();
        for (int i = 0; i < this.history.size(); i++) {
            Hist h = this.history.get(i);
            try {
                JSONObject o = new JSONObject();
                o.put("u", h.url);
                o.put("t", h.title);
                o.put("c", h.category);
                o.put("ts", h.time);
                if (h.cover != null && h.cover.length() > 0) {
                    o.put("cv", h.cover);
                }
                arr.put(o);
            } catch (Throwable ignored) {
            }
        }
        final String text = arr.toString();
        this.bg.execute(new Runnable() {
            @Override
            public void run() {
                sp.edit().putString(K_HISTORY, text).apply();
            }
        });
    }

    public void clearHistory() {
        this.history.clear();
        this.histIndex.clear();
        this.bg.execute(new Runnable() {
            @Override
            public void run() {
                sp.edit().remove(K_HISTORY).apply();
            }
        });
    }

    public void clearFavs() {
        this.favs.clear();
        this.bg.execute(new Runnable() {
            @Override
            public void run() {
                sp.edit().remove(K_FAVS).apply();
            }
        });
    }

    public int historySize() {
        return this.history.size();
    }

    /** 播放记录快照（最新的在前）。 */
    public synchronized List<Hist> historyList() {
        return new ArrayList<Hist>(this.history);
    }

    /** 看过的视频（最新的在前）：优先用片库里的标题，其次用记录里存的标题。 */
    public synchronized List<Video> historyVideos() {
        List<Video> out = new ArrayList<Video>();
        List<Hist> list = historyList();
        for (int i = 0; i < list.size(); i++) {
            Hist h = list.get(i);
            Video v = find(h.url);
            if (v != null) {
                out.add(v);
                continue;
            }
            boolean local = !h.url.startsWith("http");
            out.add(new Video(titleOf(h), h.url, h.category, local ? "本地" : "看过", 0L, local));
        }
        return out;
    }

    public synchronized void removeHistory(String url) {
        if (TextUtils.isEmpty(url)) {
            return;
        }
        Hist h = this.histIndex.remove(url);
        if (h != null) {
            this.history.remove(h);
        }
        saveHistory();
    }

    private void ensureDislikes() {
        if (this.dislikeLoaded) {
            return;
        }
        this.dislikeLoaded = true;
        try {
            JSONObject o = new JSONObject(this.sp.getString(K_DISLIKE, "{}"));
            java.util.Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String k = it.next();
                this.dislikeCache.put(k, o.optInt(k, 0));
            }
        } catch (Throwable ignored) {
        }
    }

    public void addDislike(String url) {
        if (TextUtils.isEmpty(url)) {
            return;
        }
        ensureDislikes();
        int now = dislikes(url) + 1;
        this.dislikeCache.put(url, now);
        final JSONObject o = new JSONObject();
        try {
            for (Map.Entry<String, Integer> e : this.dislikeCache.entrySet()) {
                if (e.getValue() != null && e.getValue() > 0) {
                    o.put(e.getKey(), e.getValue().intValue());
                }
            }
        } catch (Throwable ignored) {
        }
        final String text = o.toString();
        this.bg.execute(new Runnable() {
            @Override
            public void run() {
                sp.edit().putString(K_DISLIKE, text).apply();
            }
        });
    }

    public int dislikes(String url) {
        if (TextUtils.isEmpty(url)) {
            return 0;
        }
        ensureDislikes();
        Integer v = this.dislikeCache.get(url);
        return v == null ? 0 : v.intValue();
    }

    public void clearDislikes() {
        this.dislikeCache.clear();
        this.dislikeLoaded = true;
        this.bg.execute(new Runnable() {
            @Override
            public void run() {
                sp.edit().remove(K_DISLIKE).apply();
            }
        });
    }

    // ---------------------------------------------------------------- API 源

    // ---------------------------------------------------------------- 接口健康度

    private final HashMap<String, int[]> apiStats = new HashMap<String, int[]>();
    private boolean apiStatsLoaded = false;

    private void ensureApiStats() {
        if (this.apiStatsLoaded) {
            return;
        }
        this.apiStatsLoaded = true;
        try {
            JSONObject o = new JSONObject(this.sp.getString(K_API_STATS, "{}"));
            java.util.Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String k = it.next();
                JSONArray a = o.optJSONArray(k);
                if (a == null || a.length() < 2) {
                    continue;
                }
                this.apiStats.put(k, new int[]{a.optInt(0, 0), a.optInt(1, 0)});
            }
        } catch (Throwable ignored) {
        }
    }

    /** 成功一次：解析出能播的直链。 */
    public synchronized void recordApiOk(String api) {
        if (TextUtils.isEmpty(api) || !api.startsWith("http")) {
            return;
        }
        ensureApiStats();
        int[] v = this.apiStats.get(api);
        if (v == null) {
            v = new int[]{0, 0};
            this.apiStats.put(api, v);
        }
        v[0] = Math.min(v[0] + 1, 9999);
        saveApiStatsSoon();
    }

    /** 失败一次：解析失败或者播放出错。 */
    public synchronized void recordApiFail(String api) {
        if (TextUtils.isEmpty(api) || !api.startsWith("http")) {
            return;
        }
        ensureApiStats();
        int[] v = this.apiStats.get(api);
        if (v == null) {
            v = new int[]{0, 0};
            this.apiStats.put(api, v);
        }
        v[1] = Math.min(v[1] + 1, 9999);
        saveApiStatsSoon();
    }

    public int[] apiStat(String api) {
        ensureApiStats();
        int[] v = this.apiStats.get(api);
        return v == null ? new int[]{0, 0} : new int[]{v[0], v[1]};
    }

    /** 失败率 0~100，没数据时按 50 处理（排中间）。 */
    public int apiFailRate(String api) {
        int[] v = apiStat(api);
        int total = v[0] + v[1];
        if (total == 0) {
            return 50;
        }
        return v[1] * 100 / total;
    }

    private long lastApiStatsSave = 0L;

    private void saveApiStatsSoon() {
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - this.lastApiStatsSave < 1500L) {
            return;
        }
        this.lastApiStatsSave = now;
        final JSONObject o = new JSONObject();
        try {
            for (Map.Entry<String, int[]> e : this.apiStats.entrySet()) {
                JSONArray a = new JSONArray();
                a.put(e.getValue()[0]);
                a.put(e.getValue()[1]);
                o.put(e.getKey(), a);
            }
        } catch (Throwable ignored) {
        }
        final String text = o.toString();
        this.bg.execute(new Runnable() {
            @Override
            public void run() {
                sp.edit().putString(K_API_STATS, text).apply();
            }
        });
    }

    public synchronized void clearApiStats() {
        this.apiStats.clear();
        this.apiStatsLoaded = true;
        this.bg.execute(new Runnable() {
            @Override
            public void run() {
                sp.edit().remove(K_API_STATS).apply();
            }
        });
    }

    public String[] apis(String catKey) {
        Category c = category(catKey);
        return c == null ? new String[0] : c.apis;
    }

    /**
     * 线路排序：失败率低的优先 → 「不对」次数少的优先 → 原始顺序兜底。
     * 这样用久了会自动把坏线路沉到后面，好的线路顶上来。
     */
    public String[] apisSorted(String catKey) {
        String[] apis = apis(catKey);
        if (apis.length <= 1) {
            return apis;
        }
        final String[] copy = apis.clone();
        int n = copy.length;
        int[] fail = new int[n];
        int[] dislike = new int[n];
        for (int i = 0; i < n; i++) {
            fail[i] = apiFailRate(copy[i]);
            dislike[i] = dislikes(copy[i]);
        }
        for (int i = 1; i < n; i++) {
            String key = copy[i];
            int fi = fail[i];
            int di = dislike[i];
            int j = i - 1;
            while (j >= 0 && (fail[j] > fi || (fail[j] == fi && dislike[j] > di))) {
                copy[j + 1] = copy[j];
                fail[j + 1] = fail[j];
                dislike[j + 1] = dislike[j];
                j--;
            }
            copy[j + 1] = key;
            fail[j + 1] = fi;
            dislike[j + 1] = di;
        }
        return copy;
    }

    public int apiCount(String catKey) {
        return apis(catKey).length;
    }

    public String apiAt(String catKey, int index) {
        String[] apis = apis(catKey);
        if (apis.length == 0) {
            return null;
        }
        int i = index % apis.length;
        if (i < 0) {
            i += apis.length;
        }
        return apis[i];
    }

    // ---------------------------------------------------------------- 手动视频

    public synchronized void loadManual() {
        try {
            String s = this.sp.getString(K_MANUAL, "");
            if (TextUtils.isEmpty(s)) {
                return;
            }
            JSONArray arr = new JSONArray(s);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null) {
                    String url = o.optString("u", "");
                    if (!TextUtils.isEmpty(url)) {
                        addVideo(new Video(o.optString("t", guessTitle(url)), url,
                                ensureCategory(o.optString("c", "daily")), "手动添加", 0L, false));
                    }
                }
            }
        } catch (Throwable th) {
            IO.log("loadManual error " + th);
        }
    }

    /** 批量加手动链接：一次解析、一次落盘，导入几十条也不卡。 */
    public synchronized int addManualVideos(String catKey, String lines) {
        if (TextUtils.isEmpty(lines)) {
            return 0;
        }
        List<String> urls = new ArrayList<String>();
        String[] parts = lines.split("\\r?\\n");
        for (int i = 0; i < parts.length; i++) {
            String s = parts[i].trim();
            if (s.length() >= 6 && (s.startsWith("http://") || s.startsWith("https://")
                    || s.startsWith("file://") || s.startsWith("content://"))) {
                urls.add(s);
            }
        }
        List<String> titles = new ArrayList<String>();
        for (int i = 0; i < urls.size(); i++) {
            titles.add(guessTitle(urls.get(i)));
        }
        return addBatch(catKey, urls, titles, "手动添加");
    }

    /** 文件选择器批量导入。 */
    public synchronized int addManualUris(String catKey, List<String> uris, List<String> names) {
        return addBatch(catKey, uris, names, "设备导入");
    }

    public synchronized int addSingleManualVideo(String catKey, String url, String title) {
        if (TextUtils.isEmpty(url)) {
            return 0;
        }
        List<String> u = new ArrayList<String>();
        List<String> t = new ArrayList<String>();
        u.add(url);
        t.add(TextUtils.isEmpty(title) ? guessTitle(url) : title);
        return addBatch(catKey, u, t, "设备导入");
    }

    private synchronized int addBatch(String catKey, List<String> urls, List<String> titles, String sourceLabel) {
        if (urls == null || urls.isEmpty()) {
            return 0;
        }
        final String cat = ensureCategory(catKey);
        final JSONArray existing = readManualArray();
        int added = 0;
        for (int i = 0; i < urls.size(); i++) {
            String url = urls.get(i);
            if (TextUtils.isEmpty(url) || this.urlSet.contains(url)) {
                continue;
            }
            String title = (titles != null && i < titles.size() && !TextUtils.isEmpty(titles.get(i)))
                    ? titles.get(i) : guessTitle(url);
            try {
                JSONObject o = new JSONObject();
                o.put("c", cat);
                o.put("t", title);
                o.put("u", url);
                existing.put(o);
            } catch (Throwable th) {
                IO.log("addBatch json " + th);
            }
            addVideo(new Video(title, url, cat, sourceLabel, 0L, false));
            added++;
        }
        if (added > 0) {
            final String text = existing.toString();
            this.bg.execute(new Runnable() {
                @Override
                public void run() {
                    sp.edit().putString(K_MANUAL, text).apply();
                }
            });
            this.statsDirty = true;
        }
        return added;
    }

    private JSONArray readManualArray() {
        JSONArray out = new JSONArray();
        try {
            JSONArray arr = new JSONArray(this.sp.getString(K_MANUAL, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null) {
                    out.put(o);
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /** 从片库里移除一条（手动加入的会同步删掉记录）。 */
    public synchronized boolean removeVideo(Video v) {
        if (v == null) {
            return false;
        }
        boolean removed = false;
        List<Video> list = this.byCat.get(v.category);
        if (list != null) {
            removed = list.remove(v) || removed;
        }
        this.urlSet.remove(v.url);
        this.byUrl.remove(v.url);
        this.favs.remove(v.url);
        try {
            JSONArray old = new JSONArray(this.sp.getString(K_MANUAL, "[]"));
            JSONArray out = new JSONArray();
            for (int i = 0; i < old.length(); i++) {
                JSONObject o = old.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                if (o.optString("u", "").equals(v.url)) {
                    continue;
                }
                out.put(o);
            }
            final String text = out.toString();
            this.bg.execute(new Runnable() {
                @Override
                public void run() {
                    sp.edit().putString(K_MANUAL, text).apply();
                }
            });
        } catch (Throwable ignored) {
        }
        this.statsDirty = true;
        return removed;
    }

    private synchronized void addVideo(Video v) {
        if (v == null || TextUtils.isEmpty(v.url)) {
            return;
        }
        if (v.category == null || v.category.length() == 0) {
            v.category = "daily";
        }
        List<Video> list = this.byCat.get(v.category);
        if (list == null) {
            v.category = ensureCategory(v.category);
            list = this.byCat.get(v.category);
        }
        if (list != null && this.urlSet.add(v.url)) {
            list.add(v);
            this.byUrl.put(v.url, v);
            this.statsDirty = true;
        }
    }

    public synchronized List<Video> allVideos() {
        List<Video> out = new ArrayList<Video>();
        for (int i = 0; i < this.categories.size(); i++) {
            Category c = this.categories.get(i);
            if (c.isSpecial()) {
                continue;
            }
            List<Video> l = this.byCat.get(c.key);
            if (l != null) {
                out.addAll(l);
            }
        }
        return out;
    }

    public synchronized List<Video> list(String catKey) {
        if (C_ALL.equals(catKey)) {
            return allVideos();
        }
        if (C_FAV.equals(catKey)) {
            List<Video> all = allVideos();
            List<Video> out = new ArrayList<Video>();
            for (int i = 0; i < all.size(); i++) {
                if (isFav(all.get(i))) {
                    out.add(all.get(i));
                }
            }
            return out;
        }
        List<Video> l = this.byCat.get(catKey);
        // 返回快照：调用方在 UI 线程迭代时，后台扫描/导入可能正在改内部列表
        return l == null ? new ArrayList<Video>() : new ArrayList<Video>(l);
    }

    public synchronized int count(String catKey) {
        return list(catKey).size();
    }

    public Category category(String catKey) {
        for (int i = 0; i < this.categories.size(); i++) {
            if (this.categories.get(i).key.equals(catKey)) {
                return this.categories.get(i);
            }
        }
        try {
            return Hidden.safeGet(this.app, catKey);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public synchronized Video find(String url) {
        if (TextUtils.isEmpty(url)) {
            return null;
        }
        return this.byUrl.get(url);
    }

    /**
     * 随机取一条：避开 avoidUrl（当前正在播的）以及该分类最近播过的几条，
     * 这样同一个分类不太会来回放同一条。
     */
    public synchronized Video random(String catKey, String avoidUrl) {
        List<Video> all = list(catKey);
        if (all.isEmpty()) {
            return null;
        }
        if (all.size() == 1) {
            return all.get(0);
        }
        LinkedList<String> rec = this.recent.get(catKey);
        List<Video> pool = new ArrayList<Video>(all.size());
        for (int i = 0; i < all.size(); i++) {
            Video v = all.get(i);
            if (avoidUrl != null && avoidUrl.equals(v.url)) {
                continue;
            }
            if (rec != null && rec.contains(v.url)) {
                continue;
            }
            pool.add(v);
        }
        if (pool.isEmpty()) {
            pool = all;
        }
        Video picked = pool.get(this.rnd.nextInt(pool.size()));
        if (avoidUrl != null && all.size() > 1 && picked.url.equals(avoidUrl)) {
            picked = all.get(this.rnd.nextInt(all.size()));
        }
        noteRecent(catKey, picked.url);
        return picked;
    }

    public void noteRecent(String catKey, String url) {
        if (TextUtils.isEmpty(catKey) || TextUtils.isEmpty(url)) {
            return;
        }
        LinkedList<String> rec = this.recent.get(catKey);
        if (rec == null) {
            rec = new LinkedList<String>();
            this.recent.put(catKey, rec);
        }
        rec.remove(url);
        rec.addFirst(url);
        while (rec.size() > 6) {
            rec.removeLast();
        }
    }

    public synchronized String statsText() {
        if (!this.statsDirty && this.statsCache != null) {
            return this.statsCache;
        }
        List<Video> all = allVideos();
        int local = 0;
        int online = 0;
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).local) {
                local++;
            } else {
                online++;
            }
        }
        int apiCats = 0;
        for (int i = 0; i < this.categories.size(); i++) {
            if (this.categories.get(i).isApi()) {
                apiCats++;
            }
        }
        String text;
        if (local == 0 && online == 0) {
            text = "在线源 " + apiCats + " 个分类 · 点一下就播";
        } else if (local == 0) {
            text = "在线源 " + apiCats + " 个分类 · 静态源 " + online + " 部";
        } else if (online == 0) {
            text = "本地 " + local + " 部 · 在线源 " + apiCats + " 个分类";
        } else {
            text = "本地 " + local + " 部 · 静态源 " + online + " 部 · 在线源 " + apiCats + " 个分类";
        }
        this.statsCache = text;
        this.statsDirty = false;
        return text;
    }

    public void invalidateStats() {
        this.statsDirty = true;
    }

    // ---------------------------------------------------------------- 设置

    public String getSourceUrl() {
        return this.sp.getString(K_SOURCE_URL, "");
    }

    public void setSourceUrl(String url) {
        this.sp.edit().putString(K_SOURCE_URL, url == null ? "" : url.trim()).apply();
        this.dirty = true;
        this.statsDirty = true;
    }

    public SharedPreferences prefs() {
        return this.sp;
    }
}