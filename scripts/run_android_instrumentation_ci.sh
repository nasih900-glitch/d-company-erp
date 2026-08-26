#!/usr/bin/env bash
set -Eeuo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
android_root="${repo_root}/android-native"
diagnostics_dir="${android_root}/app/build/outputs/ci-diagnostics"

mkdir -p "${diagnostics_dir}"
cd "${android_root}"

status=0
./gradlew --no-daemon --max-workers=2 --stacktrace connectedDebugAndroidTest || status=$?

if [[ "${status}" -ne 0 ]]; then
  adb logcat -d -v threadtime > "${diagnostics_dir}/logcat.txt" || true
  adb shell dumpsys activity processes > "${diagnostics_dir}/activity-processes.txt" || true
fi

exit "${status}"
