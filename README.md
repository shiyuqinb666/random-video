# 随机视界 · Random Video

> 每次打开，都是新的心动。
> 一个纯原生 Java 写的 Android 随机视频播放器 —— 单 dex、零第三方依赖、安装包不到 200 KB。

<p align="left">
  <img alt="platform" src="https://img.shields.io/badge/Android-5.0%2B-3ddc84?logo=android&logoColor=white">
  <img alt="language" src="https://img.shields.io/badge/Java-8-orange?logo=java">
  <img alt="size" src="https://img.shields.io/badge/APK-200%20KB-blue">
  <img alt="license" src="https://img.shields.io/badge/license-MIT-green">
</p>

---

## 这是什么

打开就能看，点一下换一条。不用注册、不用登录、不需要账号，也不上传任何个人数据。

内容分 25 个分类，覆盖校园、汉服、洛丽塔、Cosplay、街拍、热舞、泳装、丝袜、美腿、花嫁、漫展等；既能播放在线随机源，也能播放手机本地的视频。

## 功能

**播放**
- 随机播放 / 单分类随机 / 自动连播
- 等比缩放不变形（按视频真实分辨率居中留边）
- 双击左中右：后退 10 秒 / 暂停 / 快进 10 秒
- 控制栏 3.2 秒自动隐藏，单击唤出
- 断点续播、下一条后台预取（点下去基本秒开）
- **倍速播放** 0.5x ~ 2.0x，选择后记忆
- 坏源自动拉黑，本次会话不再重试

**下载**
- 一键下载当前视频到 `Movies/随机视界/`
- 走系统 DownloadManager：通知栏进度、断网自动续、不占界面
- 文件名自动清洗非法字符，同名自动加时间戳

**历史记录**
- 两个页签：**下载箱**（含实时进度/体积/删除）与 **看过**（最多 200 条）
- 每行带封面缩略图（首帧抓取 + 磁盘缓存）与真实标题
- 走能返回元数据的源时，直接显示**作品原标题与源站封面**

**片库**
- 自动扫描本地视频，按文件夹名 + 文件名智能归类
- 支持在线源 JSON 配置、手动批量加链接、文件选择器批量导入
- 收藏、历史、不喜欢权重
- **分类页搜索**：按标题 / 来源实时过滤
- **分享**：播放页与分类页长按即可分享链接
- **封面缓存管理**：设置页显示缓存大小、一键清除

**隐藏区**
- 配色独立的「颜色区」：自建源 + 私密柜（首页不可见）
- 设置页可直达，也支持连点「关于」5 次解锁

## 技术要点

| 项 | 说明 |
|---|---|
| 语言 | Java 8，无 Kotlin、无 AndroidX |
| 最低版本 | Android 5.0（API 21）/ targetSdk 34 |
| 打包后体积 | 约 200 KB（单 classes.dex ≈ 156 KB） |
| 依赖 | 仅 Android Framework + `org.json` |
| 播放器 | 原生 `VideoView` + `MediaPlayer` |
| 下载 | `DownloadManager` |
| 封面 | `MediaMetadataRetriever` 抓首帧 + LRU 内存缓存 + 磁盘缓存 |

### 三个自己做的小组件

**`ApiResolver`** —— 把"随机视频接口"解析成可播放直链
- 双通道：`?type=json` 一次拿直链（省掉 302 往返），不支持的走多跳重定向
- 正文里抓候选链接按 `mp4 > m4v > m3u8 > flv` 打分排序，过滤封面图/头像
- 命中元数据的源会一并取回 `title` / `cover`
- 对"随机一条 + 上游反爬"的接口做 3 路并发补打，命中率 1-(2/3)⁶ ≈ 91%

**`Classifier`** —— 本地视频自动分拣
- 归一化：全角转半角、去标点、剥离日期/相机前缀/社交 App 前缀/清晰度标记
- **最长匹配 + 区间遮蔽**：`白丝袜` 只记给「白丝」，不会被「丝袜」「袜」重复计分
- 加权打分：`长度因子 × (文件夹命中×3 + 文件名命中×1) × 命中次数`
- 短拉丁词（`jk`/`cos`/`ks`）要求词边界，`costco` 不会被认成 Cosplay
- 259 个关键词，逐词自检与真机样本测试均 100%

**`Title`** —— 标题兜底
- `已有标题 → 片库标题 → 分类名+时间 → URL 末段`，任何情况下都不留空行
- 能还原 `标题_82cbd1.mp4` 这类带时间戳的旧文件名

## 目录结构

```
src/com/fujiang/randomvideo/
├── MainActivity.java       首页：分类宫格 / 随机播放 / 历史入口
├── CategoryActivity.java   分类页：视频列表 / 长按菜单
├── PlayerActivity.java     播放页：手势 / 进度 / 下载 / 预取
├── HistoryActivity.java    历史页：下载箱 + 看过
├── SettingsActivity.java   设置页：在线源 / 手动添加 / 颜色区入口
├── AboutActivity.java
├── Library.java            片库：扫描 / 分类 / 收藏 / 历史 / 源权重
├── ApiResolver.java        接口解析（302 / JSON / 元数据）
├── Classifier.java         关键词分类器
├── Title.java              标题生成与还原
├── Thumb.java              封面抓取与缓存
├── Downloader.java         下载与下载记录
├── Hidden.java             颜色区 / 私密柜
└── Util.java · IO.java · Video.java · Category.java · R.java · *Adapter.java
```

## 构建

APK 用 `apktool` 保留原资源、`javac` + `d8` 重新编译 dex、`apksigner` 签名的流程构建：

```bash
# 1. 反编译原包（保留 res / manifest / arsc）
apktool d -f -o src 随机视界.apk

# 2. 编译 Java
javac --release 8 -cp android-34.jar -d classes $(find src -name '*.java')

# 3. 生成 dex
java -cp r8.jar com.android.tools.r8.D8 --release --min-api 21 \
     --lib android-34.jar --output dex $(find classes -name '*.class')

# 4. 重建资源并换入新 dex
apktool b --use-aapt2 -q src -o rebuilt.apk

# 5. 对齐 + 签名
zipalign -p -f 4 stage.apk aligned.apk
apksigner sign --ks fujiang.ks --v1-signing-enabled true \
               --v2-signing-enabled true --v3-signing-enabled true \
               --out 随机视界.apk aligned.apk
```

需要准备：`apktool 2.7+`、`JDK 8+`、`android-34.jar`、`r8`。

## 数据来源

在线内容来自第三方公开随机视频接口，本项目只做客户端播放与整理，不存储、不分发任何视频文件。
接口可能随时变更或失效，可自行在「设置 → 在线源 JSON 地址」里替换为自己的源。

## 版本

当前 **1.3.8**（versionCode 14）。完整改动记录见仓库内的优化说明文档。

## License

MIT
