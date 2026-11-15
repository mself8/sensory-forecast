#!/usr/bin/env bash
# 차 안 소리 받기에 쓰는 두 파일을 cache/에 준비합니다. 이미 있으면 건너뜁니다.
# - scrcpy 서버(Apache 2.0, github.com/Genymobile/scrcpy). 소리 받기만 쓰고 화면 받기와 조작은 끕니다.
# - SockCat.java를 묶은 sockcat.jar. JDK 17과 안드로이드 SDK(ANDROID_HOME)가 있어야 합니다.
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
C="$HERE/cache"
mkdir -p "$C"
VER=5.0.1
SHA=764eb6f79811d5211fe9df341120882ba9994c7a61b897d7bf3fb662e53bc536
if [ ! -f "$C/scrcpy-server.jar" ]; then
  curl -sSL -m 120 -A "sensory-forecast-dev/0.1" -o "$C/scrcpy-server.jar" \
    "https://github.com/Genymobile/scrcpy/releases/download/v$VER/scrcpy-server-v$VER"
fi
echo "$SHA  $C/scrcpy-server.jar" | sha256sum -c --quiet
if [ ! -f "$C/sockcat.jar" ]; then
  JDK=${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-amd64}
  SDK=${ANDROID_HOME:?ANDROID_HOME}
  B=$(mktemp -d)
  "$JDK/bin/javac" -encoding UTF-8 --release 8 -nowarn -cp "$SDK/platforms/android-35/android.jar" -d "$B" "$HERE/SockCat.java"
  JAVA_HOME="$JDK" PATH="$JDK/bin:$PATH" "$SDK/build-tools/35.0.0/d8" --min-api 28 \
    --lib "$SDK/platforms/android-35/android.jar" --output "$B" "$B/SockCat.class"
  (cd "$B" && python3 -c "import zipfile; z = zipfile.ZipFile('$C/sockcat.jar', 'w'); z.write('classes.dex'); z.close()")
  rm -rf "$B"
fi
echo "$VER"
