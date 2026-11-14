#!/usr/bin/env bash
# Gera santa-lucia.apk sem Gradle. Requer: JDK, ANDROID_SDK com platforms;android-34 e build-tools;34.0.0
set -euo pipefail
SDK="${ANDROID_SDK:?defina ANDROID_SDK}"; BT="$SDK/build-tools/34.0.0"; JAR="$SDK/platforms/android-34/android.jar"
HERE="$(cd "$(dirname "$0")" && pwd)"; OUT="$HERE/build"; rm -rf "$OUT"; mkdir -p "$OUT/assets/www" "$OUT/classes" "$OUT/dex"
cp "$HERE/../index.html" "$HERE/../game.js" "$HERE/../three.module.js" "$OUT/assets/www/"
"$BT/aapt2" compile --dir "$HERE/res" -o "$OUT/res.zip"
"$BT/aapt2" link -o "$OUT/base.apk" -I "$JAR" --manifest "$HERE/AndroidManifest.xml" -A "$OUT/assets" "$OUT/res.zip" \
  --min-sdk-version 24 --target-sdk-version 34 --version-code 5 --version-name 1.4
javac -nowarn -Xlint:-options -source 8 -target 8 -bootclasspath "$JAR" -d "$OUT/classes" $(find "$HERE/src" -name '*.java')
"$BT/d8" --release --lib "$JAR" --min-api 24 --output "$OUT/dex" $(find "$OUT/classes" -name '*.class')
(cd "$OUT/dex" && zip -q "$OUT/base.apk" classes.dex)
"$BT/zipalign" -f -p 4 "$OUT/base.apk" "$OUT/aligned.apk"
KS="${KEYSTORE:-$HERE/debug.keystore}"
[ -f "$KS" ] || keytool -genkeypair -keystore "$KS" -storepass android -keypass android -alias santalucia \
  -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Santa Lucia Debug" >/dev/null 2>&1
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android --out "$HERE/../santa-lucia.apk" "$OUT/aligned.apk"
"$BT/apksigner" verify "$HERE/../santa-lucia.apk" && echo "OK -> $(cd "$HERE/.." && pwd)/santa-lucia.apk"
