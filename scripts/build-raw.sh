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
  --min-sdk-version 26 --target-sdk-version 35 --version-code 6 --version-name 1.4.0 \
  --java "$OUT/gen" "$OUT/res.zip"

echo "[3/6] compile Kotlin"
"$KOTLINC/bin/kotlinc" -no-stdlib -no-reflect -jvm-target 17 \
  -classpath "$ANDROID_JAR:$KOTLINC/lib/kotlin-stdlib.jar" \
  -d "$OUT/classes" "$ROOT"/app/src/main/java/com/mercureal/voice/*.kt

echo "[4/6] dex with d8 (app classes + kotlin-stdlib, both explicit)"
STDLIB="$KOTLINC/lib/kotlin-stdlib.jar"
[ -f "$STDLIB" ] || { echo "missing kotlin-stdlib at $STDLIB" >&2; exit 1; }
(cd "$OUT/classes" && zip -qr "$OUT/app-classes.jar" .)
java -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 --release --min-api 26 --lib "$ANDROID_JAR" \
  --output "$OUT/dex" "$OUT/app-classes.jar" "$STDLIB"

echo "[4b/6] verify class list: every app class and kotlin/jvm/internal/Intrinsics must be in classes*.dex"
# Dump every class descriptor in the dex files d8 produced.
for d in "$OUT"/dex/classes*.dex; do "$BT/dexdump" -f "$d"; done \
  | sed -n "s/.*Class descriptor *: 'L\(.*\);'.*/\1/p" | sort -u > "$OUT/dex-classes.txt"
# Expected = every class in the compiled app jar + the stdlib classes the app references.
unzip -Z1 "$OUT/app-classes.jar" | grep '\.class$' | sed 's/\.class$//' | sort -u > "$OUT/expected-app-classes.txt"
MISSING="$(comm -23 "$OUT/expected-app-classes.txt" "$OUT/dex-classes.txt" || true)"
if [ -n "$MISSING" ]; then echo "FAIL: app classes missing from dex:" >&2; echo "$MISSING" >&2; exit 1; fi
grep -qx 'kotlin/jvm/internal/Intrinsics' "$OUT/dex-classes.txt" || { echo "FAIL: kotlin/jvm/internal/Intrinsics not in dex" >&2; exit 1; }
# Every @JvmName/@JvmStatic-style stdlib facade referenced by the app must also be present.
for c in kotlin/jvm/internal/Intrinsics kotlin/collections/CollectionsKt kotlin/text/StringsKt kotlin/collections/ArraysKt; do
  grep -qx "$c" "$OUT/dex-classes.txt" || { echo "FAIL: stdlib facade $c not in dex" >&2; exit 1; }
done
echo "class check ok: $(wc -l < "$OUT/expected-app-classes.txt") app classes, $(wc -l < "$OUT/dex-classes.txt") total classes in dex"

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
  --out "$OUT/mercureal-voice-1.4.0-debug.apk" "$OUT/aligned.apk"
"$BT/apksigner" verify --verbose "$OUT/mercureal-voice-1.4.0-debug.apk"

echo "[smoke] aapt2 badging + zip test"
"$BT/aapt2" dump badging "$OUT/mercureal-voice-1.4.0-debug.apk" | grep -E "^package|sdkVersion|targetSdk|launchable-activity|uses-permission" 
unzip -tq "$OUT/mercureal-voice-1.4.0-debug.apk"
echo "built: $OUT/mercureal-voice-1.4.0-debug.apk"
