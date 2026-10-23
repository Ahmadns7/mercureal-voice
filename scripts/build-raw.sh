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
# Wearable API client (phone side of the Wear OS bridge). Fetched from Google Maven, classes packed into the APK.
WEAR_DIR="$ROOT/dist/wear-deps"
GMS="https://dl.google.com/dl/android/maven2/com/google/android/gms"
mkdir -p "$WEAR_DIR"
WEAR_JARS=()
for a in play-services-base:18.5.0 play-services-basement:18.4.0 play-services-tasks:18.2.0 play-services-wearable:18.2.0; do
  n="${a%%:*}"; v="${a##*:}"
  if [ ! -f "$WEAR_DIR/$n-$v.jar" ]; then
    curl -fsSL -o "$WEAR_DIR/$n.aar" "$GMS/$n/$v/$n-$v.aar"
    unzip -q -o "$WEAR_DIR/$n.aar" classes.jar -d "$WEAR_DIR/tmp-$n"
    mv "$WEAR_DIR/tmp-$n/classes.jar" "$WEAR_DIR/$n-$v.jar"; rm -rf "$WEAR_DIR/tmp-$n" "$WEAR_DIR/$n.aar"
  fi
  WEAR_JARS+=("$WEAR_DIR/$n-$v.jar")
done
WEAR_CP="$(IFS=:; echo "${WEAR_JARS[*]}")"
rm -rf "$OUT" && mkdir -p "$OUT/classes" "$OUT/dex"

echo "[0/7] naija phrase unit tests (pure Kotlin, no device)"
K_LIB="$KOTLINC/lib/kotlin-stdlib.jar"
TEST_OUT="$(mktemp -d)"
"$KOTLINC/bin/kotlinc" -no-stdlib -jvm-target 17 -classpath "$K_LIB" -d "$TEST_OUT" \
  "$ROOT/app/src/main/java/com/mercureal/voice/Commands.kt" "$ROOT/app/src/main/java/com/mercureal/voice/NaijaPhrases.kt" "$ROOT/app/src/test/java/com/mercureal/voice/NaijaPhrasesTest.kt"
java -cp "$TEST_OUT:$K_LIB" com.mercureal.voice.NaijaPhrasesTestKt

echo "[1/7] compile resources"
"$BT/aapt2" compile --dir "$ROOT/app/src/main/res" -o "$OUT/res.zip"

echo "[2/7] link manifest + resources"
"$BT/aapt2" link -o "$OUT/base.apk" -I "$ANDROID_JAR" --manifest "$ROOT/app/src/main/AndroidManifest.xml" \
  --min-sdk-version 26 --target-sdk-version 35 --version-code 13 --version-name 2.0.0 \
  --java "$OUT/gen" "$OUT/res.zip"

echo "[3/7] compile Kotlin"
"$KOTLINC/bin/kotlinc" -no-stdlib -no-reflect -jvm-target 17 \
  -classpath "$ANDROID_JAR:$KOTLINC/lib/kotlin-stdlib.jar:$WEAR_CP" \
  -d "$OUT/classes" "$ROOT"/app/src/main/java/com/mercureal/voice/*.kt

echo "[4/7] dex with d8 (app classes + kotlin-stdlib, both explicit)"
STDLIB="$KOTLINC/lib/kotlin-stdlib.jar"
[ -f "$STDLIB" ] || { echo "missing kotlin-stdlib at $STDLIB" >&2; exit 1; }
(cd "$OUT/classes" && zip -qr "$OUT/app-classes.jar" .)
java -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 --release --min-api 26 --lib "$ANDROID_JAR" \
  --output "$OUT/dex" "$OUT/app-classes.jar" "$STDLIB" ${WEAR_JARS[@]}

echo "[4b/7] verify class list: every app class and kotlin/jvm/internal/Intrinsics must be in classes*.dex"
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

echo "[5/7] package + align"
cp "$OUT/base.apk" "$OUT/unsigned.apk"
(cd "$OUT/dex" && zip -q "$OUT/unsigned.apk" classes*.dex)
# Native libs: arm64-v8a only (the llama.cpp JNI bridge). Stored uncompressed so the .so loads straight from the APK.
if [ -d "$ROOT/app/src/main/jniLibs" ]; then
  rm -rf "$OUT/lib" && mkdir -p "$OUT/lib" && cp -r "$ROOT/app/src/main/jniLibs/." "$OUT/lib/"
  (cd "$OUT" && zip -q -0 "$OUT/unsigned.apk" $(cd lib && find . -name "*.so" | sed "s|^\./|lib/|"))
  unzip -l "$OUT/unsigned.apk" | grep -q "lib/arm64-v8a/libmercureal_llm.so" || echo "warn: native lib not packed under lib/arm64-v8a" >&2
fi
"$BT/zipalign" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

echo "[6/7] sign: release keystore if MERCUREAL_KEYSTORE is set, else debug key"
if [ -n "${MERCUREAL_KEYSTORE:-}" ]; then
  : "${MERCUREAL_KEY_ALIAS:?set MERCUREAL_KEY_ALIAS}"; : "${MERCUREAL_STORE_PASS:?set MERCUREAL_STORE_PASS}"; : "${MERCUREAL_KEY_PASS:?set MERCUREAL_KEY_PASS}"
  SIGNED="$OUT/mercureal-voice-2.0.0-release.apk"
  SIGN_ARGS=(--ks "$MERCUREAL_KEYSTORE" --ks-key-alias "$MERCUREAL_KEY_ALIAS" --ks-pass env:MERCUREAL_STORE_PASS --key-pass env:MERCUREAL_KEY_PASS)
  SIGN_MODE=release
else
  KS="$OUT/debug.keystore"
  if [ ! -f "$KS" ]; then
    keytool -genkeypair -v -keystore "$KS" -storepass android -keypass android -alias androiddebugkey \
      -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug,O=Android,C=US" >/dev/null 2>&1
  fi
  SIGNED="$OUT/mercureal-voice-2.0.0-debug.apk"
  SIGN_ARGS=(--ks "$KS" --ks-key-alias androiddebugkey --ks-pass pass:android --key-pass pass:android)
  SIGN_MODE=debug
fi
"$BT/apksigner" sign "${SIGN_ARGS[@]}" --out "$SIGNED" "$OUT/aligned.apk"
"$BT/apksigner" verify --verbose "$SIGNED"
echo "signing mode: $SIGN_MODE"

echo "[7/7] AAB via bundletool (release keystore required for Play; debug-signed AAB is rejected by Play)"
BUNDLETOOL="${BUNDLETOOL_JAR:-}"
if [ -n "$BUNDLETOOL" ] && [ -f "$BUNDLETOOL" ]; then
  # bundletool wants a module zip with manifest/AndroidManifest.xml, dex/, res/, lib/ at the zip root.
  rm -rf "$OUT/aab-root" && mkdir -p "$OUT/aab-root/manifest" "$OUT/aab-root/dex"
  "$BT/aapt2" link --proto-format -o "$OUT/base.zip" -I "$ANDROID_JAR" --manifest "$ROOT/app/src/main/AndroidManifest.xml" \
    --min-sdk-version 26 --target-sdk-version 35 --version-code 13 --version-name 2.0.0 "$OUT/res.zip"
  unzip -q -o "$OUT/base.zip" -d "$OUT/aab-root/proto-tmp"
  cp "$OUT/aab-root/proto-tmp/AndroidManifest.xml" "$OUT/aab-root/manifest/AndroidManifest.xml"
  cp "$OUT"/dex/classes*.dex "$OUT/aab-root/dex/"
  if [ -d "$OUT/lib" ]; then cp -r "$OUT/lib" "$OUT/aab-root/lib"; fi
  (cd "$OUT/aab-root" && rm -f "$OUT/base-module.zip" && zip -qr "$OUT/base-module.zip" manifest dex $( [ -d lib ] && echo lib ))
  rm -f "$OUT/mercureal-voice-2.0.0.aab"
  java -jar "$BUNDLETOOL" build-bundle --modules="$OUT/base-module.zip" --output="$OUT/mercureal-voice-2.0.0.aab" --overwrite
  if [ "$SIGN_MODE" = release ]; then
    jarsigner -keystore "$MERCUREAL_KEYSTORE" -storepass:env MERCUREAL_STORE_PASS -keypass:env MERCUREAL_KEY_PASS "$OUT/mercureal-voice-2.0.0.aab" "$MERCUREAL_KEY_ALIAS" >/dev/null
    jarsigner -verify "$OUT/mercureal-voice-2.0.0.aab" | tail -1
  else
    echo "aab left unsigned: debug mode. Build with MERCUREAL_KEYSTORE set to sign it for Play."
  fi
    echo "aab: $OUT/mercureal-voice-2.0.0.aab"
else
  echo "bundletool jar not set (BUNDLETOOL_JAR); AAB skipped. See RELEASING.md for the gradle route."
fi

echo "[smoke] aapt2 badging + zip test"
"$BT/aapt2" dump badging "$SIGNED" | grep -E "^package|sdkVersion|targetSdk|launchable-activity|uses-permission" 
unzip -tq "$SIGNED"
echo "built: $SIGNED"
