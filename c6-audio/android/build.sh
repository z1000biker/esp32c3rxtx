#!/usr/bin/env bash
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
output=$(realpath -m "${1:-$root/dist/ESP32-C6-Audio-Android.apk}")
sdk=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}
: "${sdk:?Set ANDROID_SDK_ROOT}"
tools=${ANDROID_BUILD_TOOLS:-$sdk/build-tools/35.0.0}
android=${ANDROID_PLATFORM_JAR:-$sdk/platforms/android-35/android.jar}
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
mkdir -p "$work/classes" "$work/dex" "$(dirname "$output")"

if command -v javac >/dev/null; then
  javac --release 8 -classpath "$android" -d "$work/classes" "$root/android/src/MainActivity.java"
else
  : "${ECJ_JAR:?Install a JDK or set ECJ_JAR}"
  java -jar "$ECJ_JAR" -8 -classpath "$android" -d "$work/classes" "$root/android/src/MainActivity.java"
fi
(cd "$work/classes" && zip -qr "$work/classes.zip" .)
"$tools/d8" --min-api 26 --lib "$android" --output "$work/dex" "$work/classes.zip"
"$tools/aapt2" link -I "$android" --manifest "$root/android/AndroidManifest.xml" \
  -A "$root/web" -o "$work/unsigned.apk"
(cd "$work/dex" && zip -q "$work/unsigned.apk" classes.dex)
"$tools/zipalign" -f -p 4 "$work/unsigned.apk" "$work/aligned.apk"

if [[ -n ${C6_KEYSTORE:-} ]]; then
  : "${C6_KEY_ALIAS:?Set C6_KEY_ALIAS}"
  : "${C6_KEYSTORE_PASSWORD:?Set C6_KEYSTORE_PASSWORD}"
else
  C6_KEYSTORE="$work/preview.p12"
  C6_KEY_ALIAS=c6-preview
  C6_KEYSTORE_PASSWORD=$(openssl rand -hex 24)
  export C6_KEYSTORE_PASSWORD
  keytool -genkeypair -keystore "$C6_KEYSTORE" -storetype PKCS12 \
    -storepass "$C6_KEYSTORE_PASSWORD" -alias "$C6_KEY_ALIAS" -keyalg RSA -keysize 2048 \
    -validity 3650 -dname "CN=ESP32-C6 Workbench preview"
fi
"$tools/apksigner" sign --ks "$C6_KEYSTORE" --ks-key-alias "$C6_KEY_ALIAS" \
  --ks-pass env:C6_KEYSTORE_PASSWORD --out "$output" "$work/aligned.apk"
"$tools/apksigner" verify --verbose "$output"
rm -f "$output.idsig"
