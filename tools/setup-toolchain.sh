#!/usr/bin/env bash
# Installs a self-contained build toolchain (JDK 17, Android SDK, Gradle) into
# ./.toolchain. Nothing is written outside that folder; `rm -rf .toolchain`
# removes everything. Use tools/env.sh to point a shell at it.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TC="$ROOT/.toolchain"
DL="$TC/dl"
mkdir -p "$DL"
cd "$DL"

JDK_URL="https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.13%2B11/OpenJDK17U-jdk_x64_linux_hotspot_17.0.13_11.tar.gz"
SDK_URL="https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
GRADLE_URL="https://services.gradle.org/distributions/gradle-8.11.1-bin.zip"

fetch() {
  [ -s "$2" ] && return 0
  curl -fL --retry 3 -o "$2.part" "$1"
  mv -f "$2.part" "$2"
}

if [ ! -x "$TC/jdk/bin/java" ]; then
  fetch "$JDK_URL" jdk.tar.gz
  rm -rf "$TC/jdk" && mkdir -p "$TC/jdk"
  tar -xzf jdk.tar.gz -C "$TC/jdk" --strip-components=1
fi

if [ ! -x "$TC/gradle/bin/gradle" ]; then
  fetch "$GRADLE_URL" gradle.zip
  rm -rf "$TC/gradle-tmp" "$TC/gradle"
  unzip -q gradle.zip -d "$TC/gradle-tmp"
  mv "$TC"/gradle-tmp/gradle-* "$TC/gradle" && rmdir "$TC/gradle-tmp"
fi

if [ ! -x "$TC/sdk/cmdline-tools/latest/bin/sdkmanager" ]; then
  fetch "$SDK_URL" cmdline-tools.zip
  rm -rf "$TC/sdk/cmdline-tools" && mkdir -p "$TC/sdk/cmdline-tools"
  unzip -q cmdline-tools.zip -d "$TC/sdk/cmdline-tools"
  mv "$TC/sdk/cmdline-tools/cmdline-tools" "$TC/sdk/cmdline-tools/latest"
fi

# shellcheck source=env.sh
source "$ROOT/tools/env.sh"
yes | sdkmanager --sdk_root="$ANDROID_HOME" --licenses >/dev/null || true
sdkmanager --sdk_root="$ANDROID_HOME" "platform-tools" "platforms;android-35" "build-tools;35.0.0"
echo "Toolchain ready in $TC"
