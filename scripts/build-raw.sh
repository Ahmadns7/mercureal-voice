#!/usr/bin/env bash
# Raw-toolchain build: kotlinc -> d8 -> aapt2 link -> zipalign -> apksigner.
# Needs: ANDROID_HOME (platform android-35, build-tools 35.0.0), KOTLINC_HOME, JDK 17+.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SDK="${ANDROID_HOME:?set ANDROID_HOME}"
KOTLINC="${KOTLINC_HOME:?set KOTLINC_HOME (kotlinc root dir)}"
BT="$SDK/build-tools/35.0.0"
ANDROID_JAR="$SDK/platforms/android-35/android.jar"
OUT="$ROOT/dist/raw"
rm -rf "$OUT" && mkdir -p "$OUT/classes" "$OUT/dex"

echo "[1/6] compile resources"
"$BT/aapt2" compile --dir "$ROOT/app/src/main/res" -o "$OUT/res.zip"

echo "[2/6] link manifest + resources"
"$BT/aapt2" link -o "$OUT/base.apk" -I "$ANDROID_JAR" --manifest "$ROOT/app/src/main/AndroidManifest.xml" \
  --min-sdk-version 26 --target-sdk-version 35 --version-code 1 --version-name 1.0.0 \
  --java "$OUT/gen" "$OUT/res.zip"

echo "[3/6] compile Kotlin"
"$KOTLINC/bin/kotlinc" -no-stdlib -no-reflect -jvm-target 17 \
  -classpath "$ANDROID_JAR:$KOTLINC/lib/kotlin-stdlib.jar" \
  -d "$OUT/classes" "$ROOT"/app/src/main/java/com/mercureal/voice/*.kt

echo "[4/6] dex with d8"
(cd "$OUT/classes" && zip -qr "$OUT/app-classes.jar" .)
java -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 --release --min-api 26 --lib "$ANDROID_JAR" \
  --output "$OUT/dex" "$OUT/app-classes.jar" "$KOTLINC/lib/kotlin-stdlib.jar"

echo "[5/6] package + align"
cp "$OUT/base.apk" "$OUT/unsigned.apk"
(cd "$OUT/dex" && zip -q "$OUT/unsigned.apk" classes*.dex)
"$BT/zipalign" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

echo "[6/6] sign with debug key"
KS="$OUT/debug.keystore"
if [ ! -f "$KS" ]; then
  keytool -genkeypair -v -keystore "$KS" -storepass android -keypass android -alias androiddebugkey \
    -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug,O=Android,C=US" >/dev/null 2>&1
fi
"$BT/apksigner" sign --ks "$KS" --ks-key-alias androiddebugkey --ks-pass pass:android --key-pass pass:android \
  --out "$OUT/mercureal-voice-1.0.0-debug.apk" "$OUT/aligned.apk"
"$BT/apksigner" verify --verbose "$OUT/mercureal-voice-1.0.0-debug.apk"
echo "built: $OUT/mercureal-voice-1.0.0-debug.apk"
