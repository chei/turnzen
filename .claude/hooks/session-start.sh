#!/bin/bash
# Prepares a Claude Code cloud session (a fresh container) to build and test Turnzen:
# a Maven Central mirror for Gradle, and the Android SDK the app module needs.
# Does nothing on a developer's own machine.
set -euo pipefail

if [ "${CLAUDE_CODE_REMOTE:-}" != "true" ]; then
  exit 0
fi

cd "${CLAUDE_PROJECT_DIR:-$(dirname "$0")/../..}"

# Retries a command a few times with a growing pause: downloads from a shared egress
# address are sometimes refused with 429 Too Many Requests for a moment.
retry() {
  local delay=5
  for attempt in 1 2 3 4; do
    if "$@"; then return 0; fi
    [ "$attempt" = 4 ] && return 1
    echo "Retrying in ${delay}s: $*" >&2
    sleep "$delay"
    delay=$((delay * 2))
  done
}

# 1. Gradle: try Google's mirror of Maven Central before Maven Central itself. Every cloud
# session starts with an empty Gradle cache and many sessions share one outbound address,
# so repo.maven.apache.org often answers 429. The mirror serves the same artifacts. An
# init script, so the project's own build (and CI, and local builds) stay as they are.
mkdir -p "$HOME/.gradle/init.d"
cat > "$HOME/.gradle/init.d/maven-central-mirror.gradle" <<'GRADLE'
// Written by .claude/hooks/session-start.sh for Claude Code cloud sessions only.
def mirror = 'https://maven-central.storage-download.googleapis.com/maven2/'
beforeSettings { settings ->
    settings.pluginManagement.repositories {
        maven { name = 'MavenCentralMirror'; url = mirror }
    }
    settings.dependencyResolutionManagement.repositories {
        maven { name = 'MavenCentralMirror'; url = mirror }
    }
}
GRADLE

# 2. Android SDK: the container has none, and :app-android cannot even be configured
# without one. Installed once; the container is cached after this hook finishes.
SDK="$HOME/android-sdk"
COMPILE_SDK=$(sed -n 's/^ *compileSdk *= *\([0-9]*\).*/\1/p' app-android/build.gradle.kts)
if [ ! -x "$SDK/cmdline-tools/latest/bin/sdkmanager" ]; then
  tmp=$(mktemp -d)
  retry curl -sSfL -o "$tmp/tools.zip" \
    https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip
  unzip -q "$tmp/tools.zip" -d "$tmp"
  mkdir -p "$SDK/cmdline-tools"
  rm -rf "$SDK/cmdline-tools/latest"
  mv "$tmp/cmdline-tools" "$SDK/cmdline-tools/latest"
  rm -rf "$tmp"
fi
yes | "$SDK/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$SDK" --licenses >/dev/null 2>&1 || true
if [ ! -d "$SDK/platforms/android-$COMPILE_SDK.0" ] && [ ! -d "$SDK/platforms/android-$COMPILE_SDK" ]; then
  retry "$SDK/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$SDK" \
    "platforms;android-$COMPILE_SDK.0" "build-tools;36.0.0" "platform-tools" >/dev/null
fi
# local.properties is git-ignored; leave one alone that points somewhere else.
[ -f local.properties ] || echo "sdk.dir=$SDK" > local.properties
if [ -n "${CLAUDE_ENV_FILE:-}" ]; then
  echo "export ANDROID_HOME=\"$SDK\"" >> "$CLAUDE_ENV_FILE"
fi

# 3. Fetch Gradle itself and the build's plugins now, so the first build of the
# session does not start with that download.
retry ./gradlew -q help >/dev/null
