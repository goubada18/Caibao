#!/usr/bin/env bash
# P0-2 探针 APK 构建脚本：aapt2 + javac + d8 + zipalign + apksigner（全离线，不依赖 Gradle）
# 产物: poc/p0-2_a11yprobe/build/a11yprobe.apk
set -e
cd "$(dirname "$0")"
mkdir -p build/classes

SDK="C:/Users/93343/AppData/Local/Android/Sdk"
BT="$SDK/build-tools/36.0.0"
AJ="$SDK/platforms/android-34/android.jar"
JDK="C:/Program Files/Eclipse Adoptium/jdk-17.0.20.101-hotspot/bin"
KS="C:/Users/93343/.android/debug.keystore"

echo "[1/6] aapt2 compile res"
"$BT/aapt2" compile --dir res -o build/res.zip

echo "[2/6] aapt2 link"
"$BT/aapt2" link -o build/base.apk -I "$AJ" \
  --manifest AndroidManifest.xml \
  --min-sdk-version 24 --target-sdk-version 34 \
  --version-code 1 --version-name 0.1 \
  build/res.zip

echo "[3/6] javac"
find src -name '*.java' > build/sources.txt
"$JDK/javac" -encoding UTF-8 -source 8 -target 8 \
  -classpath "$AJ" -d build/classes @build/sources.txt 2>&1 | grep -v "warning" || true
ls build/classes/com/caibao/a11yprobe/

echo "[4/6] d8 dex"
find build/classes -name '*.class' > build/classes.txt
"$JDK/java" -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 \
  --release --lib "$AJ" --min-api 24 \
  --output build @build/classes.txt

echo "[5/6] 打包 classes.dex 进 APK"
"C:/Users/93343/.workbuddy/binaries/python/versions/3.13.12/python.exe" - <<'EOF'
import zipfile
src = zipfile.ZipFile("build/base.apk")
out = zipfile.ZipFile("build/unsigned.apk", "w", zipfile.ZIP_DEFLATED)
for i in src.infolist():
    out.writestr(i.filename, src.read(i.filename))
out.write("build/classes.dex", "classes.dex")
out.close()
src.close()
print("repack ok")
EOF

echo "[6/6] zipalign + 签名"
"$BT/zipalign" -f 4 build/unsigned.apk build/aligned.apk
"$JDK/java" -jar "$BT/lib/apksigner.jar" sign \
  --ks "$KS" --ks-pass pass:android \
  --ks-key-alias androiddebugkey --key-pass pass:android \
  --out build/a11yprobe.apk build/aligned.apk
"$JDK/java" -jar "$BT/lib/apksigner.jar" verify build/a11yprobe.apk && echo "OK: build/a11yprobe.apk"
