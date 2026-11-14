#!/usr/bin/env bash
# Build-only verification. This script never installs an APK or changes a phone.
set -euo pipefail
cd -- "$(dirname -- "$0")/.."
export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk}"
python3 scripts/validate_scope.py
workers="${WW_GRADLE_MAX_WORKERS:-1}"
heap_mb="${WW_GRADLE_HEAP_MB:-1280}"
if [[ ! "$workers" =~ ^[1-9][0-9]*$ || ! "$heap_mb" =~ ^[1-9][0-9]*$ ]]; then
  printf '%s\n' 'WW_GRADLE_MAX_WORKERS and WW_GRADLE_HEAP_MB must be positive integers.' >&2
  exit 2
fi
common=(--console=plain --no-daemon --max-workers="$workers"
  "-Dorg.gradle.jvmargs=-Xmx${heap_mb}m -Dfile.encoding=UTF-8")
# Separate heavy phases so compiler/lint state is released before test forks.
# Reused daemons can otherwise outlive their original tool memory cgroup.
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest "${common[@]}" "$@"
./gradlew :app:testDebugUnitTest "${common[@]}" "$@"
./gradlew :app:lintDebug "${common[@]}" "$@"
