#!/bin/bash
# 随机视界 · 一键打包
#
# 流程：javac 编译 -> d8 生成 dex -> apktool 重建资源 -> 换入 dex -> 对齐 -> 签名
#
# 需要准备：
#   ANDROID_JAR  android-34.jar（或对应平台的 android.jar）
#   R8_JAR       r8.jar（含 com.android.tools.r8.D8）
#   工具链        apktool 2.7+ / JDK 8+ / zipalign / apksigner
#
# 用法：
#   ANDROID_JAR=/path/android-34.jar R8_JAR=/path/r8.jar ./build.sh
#
# 首次打包需要签名：先执行
#   keytool -genkeypair -keystore release.ks -alias app -keyalg RSA -keysize 2048 -validity 10950 \
#           -storepass 你的密码 -keypass 你的密码 -dname "CN=YourName, O=YourOrg, C=CN"
#   export KS=release.ks KS_PASS=你的密码 KS_ALIAS=app

set -e

ROOT="$(cd "$(dirname "$0")" && pwd)"
APP="$ROOT/app"
OUT="$ROOT/build"
ANDROID_JAR="${ANDROID_JAR:-}"
R8_JAR="${R8_JAR:-}"
KS="${KS:-$ROOT/release.ks}"
KS_PASS="${KS_PASS:-}"
KS_ALIAS="${KS_ALIAS:-app}"
VERSION="1.3.9"

die() { echo "错误: $*" >&2; exit 1; }

[ -n "$ANDROID_JAR" ] && [ -f "$ANDROID_JAR" ] || die "请设置 ANDROID_JAR 指向 android.jar"
[ -n "$R8_JAR" ] && [ -f "$R8_JAR" ] || die "请设置 R8_JAR 指向 r8.jar"
command -v apktool  >/dev/null || die "缺少 apktool"
command -v javac    >/dev/null || die "缺少 JDK"
command -v zipalign >/dev/null || die "缺少 zipalign（Android SDK build-tools）"
command -v apksigner>/dev/null || die "缺少 apksigner（Android SDK build-tools）"

rm -rf "$OUT"
mkdir -p "$OUT/classes" "$OUT/dex"

echo "[1/6] 编译 Java 源码"
javac --release 8 -nowarn -cp "$ANDROID_JAR" -d "$OUT/classes" \
      $(find "$APP/java" -name '*.java') 2>&1 | grep -vE 'obsolete|Xlint|deprecat|^Note|^warning' || true
[ -n "$(find "$OUT/classes" -name '*.class' | head -1)" ] || die "编译产物为空"

echo "[2/6] 生成 classes.dex"
java -Xmx1g -cp "$R8_JAR" com.android.tools.r8.D8 --release --min-api 21 \
     --lib "$ANDROID_JAR" --output "$OUT/dex" \
     $(find "$OUT/classes" -name '*.class' | tr '\n' ' ')

echo "[3/6] 重建资源（apktool）"
apktool b --use-aapt2 -q "$APP" -o "$OUT/base.apk"

echo "[4/6] 换入新 dex"
python3 - "$OUT/base.apk" "$OUT/dex/classes.dex" "$OUT/stage.apk" <<'PY'
import sys, zipfile
base, dex, out = sys.argv[1], sys.argv[2], sys.argv[3]
data = open(dex, 'rb').read()
zin = zipfile.ZipFile(base)
entries = zin.infolist()
names = {i.filename for i in entries}
replaced = False
with zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED) as zout:
    for item in entries:
        if item.filename == 'classes.dex':
            zout.writestr(item, data)
            replaced = True
        else:
            zout.writestr(item, zin.read(item.filename))
    # app/ 目录里没有 smali 时，apktool 产出的包里不会有 classes.dex，需要主动补进去
    if not replaced:
        zout.writestr('classes.dex', data, zipfile.ZIP_DEFLATED)
zin.close()
print("   dex 注入完成:", len(data), "bytes", "(替换)" if replaced else "(新增)")
PY

echo "[5/6] 对齐"
zipalign -p -f 4 "$OUT/stage.apk" "$OUT/aligned.apk"

echo "[6/6] 签名"
if [ -f "$KS" ] && [ -n "$KS_PASS" ]; then
    apksigner sign --ks "$KS" --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" \
        --ks-key-alias "$KS_ALIAS" --v1-signing-enabled true --v2-signing-enabled true \
        --v3-signing-enabled true --min-sdk-version 21 \
        --out "$OUT/random-video-$VERSION.apk" "$OUT/aligned.apk"
    apksigner verify -v "$OUT/random-video-$VERSION.apk" | head -4
    echo
    echo "完成: $OUT/random-video-$VERSION.apk"
    ls -l "$OUT/random-video-$VERSION.apk"
else
    echo "跳过签名（未提供 KS / KS_PASS）"
    cp "$OUT/aligned.apk" "$OUT/random-video-$VERSION-unsigned.apk"
    echo "完成（未签名）: $OUT/random-video-$VERSION-unsigned.apk"
fi
