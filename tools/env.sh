# Source this file to use the project-local toolchain:  source tools/env.sh
_PCR_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export JAVA_HOME="$_PCR_ROOT/.toolchain/jdk"
export ANDROID_HOME="$_PCR_ROOT/.toolchain/sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export ANDROID_USER_HOME="$_PCR_ROOT/.toolchain/android-user"   # instead of ~/.android
export GRADLE_USER_HOME="$_PCR_ROOT/.toolchain/gradle-home"     # instead of ~/.gradle
export PATH="$JAVA_HOME/bin:$_PCR_ROOT/.toolchain/gradle/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"
unset _PCR_ROOT
