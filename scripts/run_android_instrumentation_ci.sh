#!/usr/bin/env bash
set -Eeuo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
android_root="${repo_root}/android-native"
diagnostics_dir="${android_root}/app/build/outputs/ci-diagnostics"
app_metadata="${android_root}/app/build/outputs/apk/debug/output-metadata.json"
test_metadata="${android_root}/app/build/outputs/apk/androidTest/debug/output-metadata.json"
app_package='cloud.dcompany.erp'
device_serial=''
deep_idle_initial_state=''
display_size_override_initial=''
display_density_override_initial=''
accelerometer_rotation_initial=''
user_rotation_initial=''
tablet_viewport_configured=0

mkdir -p "${diagnostics_dir}"
cd "${android_root}"

cleanup_alarm_environment() {
  [[ -n "${device_serial}" ]] || return 0
  local cleanup_status=0
  local restored_state=''
  # A failed/aborted deep-idle test must not poison subsequent CI steps.
  adb -s "${device_serial}" shell dumpsys deviceidle unforce >/dev/null 2>&1 || cleanup_status=1
  adb -s "${device_serial}" shell dumpsys battery reset >/dev/null 2>&1 || cleanup_status=1
  if [[ "${deep_idle_initial_state}" == '0' ]]; then
    adb -s "${device_serial}" shell dumpsys deviceidle disable deep >/dev/null 2>&1 || cleanup_status=1
  fi
  if [[ -n "${deep_idle_initial_state}" ]]; then
    restored_state="$(read_deep_idle_enabled)" || cleanup_status=1
    if [[ "${restored_state}" != "${deep_idle_initial_state}" ]]; then
      echo "Deep-idle state was not restored (expected ${deep_idle_initial_state}, got ${restored_state:-unreadable})." >&2
      cleanup_status=1
    fi
  fi
  if [[ "${tablet_viewport_configured}" -eq 1 ]]; then
    if [[ -n "${display_size_override_initial}" ]]; then
      adb -s "${device_serial}" shell wm size "${display_size_override_initial}" >/dev/null 2>&1 || cleanup_status=1
    else
      adb -s "${device_serial}" shell wm size reset >/dev/null 2>&1 || cleanup_status=1
    fi
    if [[ -n "${display_density_override_initial}" ]]; then
      adb -s "${device_serial}" shell wm density "${display_density_override_initial}" >/dev/null 2>&1 || cleanup_status=1
    else
      adb -s "${device_serial}" shell wm density reset >/dev/null 2>&1 || cleanup_status=1
    fi
    restore_system_setting accelerometer_rotation "${accelerometer_rotation_initial}" || cleanup_status=1
    restore_system_setting user_rotation "${user_rotation_initial}" || cleanup_status=1
    tablet_viewport_configured=0
  fi
  return "${cleanup_status}"
}

restore_system_setting() {
  local key=$1
  local value=$2
  if [[ -z "${value}" || "${value}" == 'null' ]]; then
    adb -s "${device_serial}" shell settings delete system "${key}" >/dev/null
  else
    adb -s "${device_serial}" shell settings put system "${key}" "${value}" >/dev/null
  fi
}

configure_tablet_viewport() {
  local effective_size=''
  local effective_density=''
  local report="${diagnostics_dir}/tablet-viewport.txt"

  display_size_override_initial="$({ adb -s "${device_serial}" shell wm size || true; } | tr -d '\r' | sed -n 's/^Override size: //p')"
  display_density_override_initial="$({ adb -s "${device_serial}" shell wm density || true; } | tr -d '\r' | sed -n 's/^Override density: //p')"
  accelerometer_rotation_initial="$(adb -s "${device_serial}" shell settings get system accelerometer_rotation | tr -d '\r')"
  user_rotation_initial="$(adb -s "${device_serial}" shell settings get system user_rotation | tr -d '\r')"
  tablet_viewport_configured=1

  adb -s "${device_serial}" shell wm size 2560x1600 >/dev/null
  adb -s "${device_serial}" shell wm density 320 >/dev/null
  adb -s "${device_serial}" shell settings put system accelerometer_rotation 0 >/dev/null
  adb -s "${device_serial}" shell settings put system user_rotation 0 >/dev/null

  effective_size="$(adb -s "${device_serial}" shell wm size | tr -d '\r' | awk -F': ' '/Physical size:/{value=$2} /Override size:/{value=$2} END{print value}')"
  effective_density="$(adb -s "${device_serial}" shell wm density | tr -d '\r' | awk -F': ' '/Physical density:/{value=$2} /Override density:/{value=$2} END{print value}')"
  {
    echo "serial=${device_serial}"
    echo "effective_size=${effective_size}"
    echo "effective_density=${effective_density}"
    echo "logical_viewport=1280x800dp"
    echo "rotation=$(adb -s "${device_serial}" shell settings get system user_rotation | tr -d '\r')"
  } | tee "${report}"

  [[ "${effective_size}" == '2560x1600' ]]
  [[ "${effective_density}" == '320' ]]
  [[ "$(adb -s "${device_serial}" shell settings get system user_rotation | tr -d '\r')" == '0' ]]
}

read_deep_idle_enabled() {
  adb -s "${device_serial}" shell dumpsys deviceidle enabled deep | tr -d '[:space:]'
}

capture_diagnostics() {
  [[ -n "${device_serial}" ]] || return 0
  adb -s "${device_serial}" logcat -d -v threadtime > "${diagnostics_dir}/logcat.txt" || true
  adb -s "${device_serial}" shell dumpsys activity processes > "${diagnostics_dir}/activity-processes.txt" || true
  adb -s "${device_serial}" shell dumpsys alarm > "${diagnostics_dir}/alarm-service.txt" || true
  adb -s "${device_serial}" shell dumpsys deviceidle > "${diagnostics_dir}/device-idle.txt" || true
}

select_api_35_emulator() {
  local serial=''
  local -a connected=()

  if [[ -n "${ANDROID_SERIAL:-}" ]]; then
    serial="${ANDROID_SERIAL}"
  else
    while read -r candidate state _; do
      [[ "${state:-}" == 'device' ]] || continue
      connected+=("${candidate}")
    done < <(adb devices)
    if [[ "${#connected[@]}" -ne 1 ]]; then
      echo "Expected exactly one connected API-35 emulator; found ${#connected[@]}. Set ANDROID_SERIAL explicitly." >&2
      return 1
    fi
    serial="${connected[0]}"
  fi

  [[ "$(adb -s "${serial}" get-state 2>/dev/null)" == 'device' ]] || {
    echo "Android device ${serial} is not ready." >&2
    return 1
  }
  [[ "$(adb -s "${serial}" shell getprop ro.kernel.qemu | tr -d '\r')" == '1' ]] || {
    echo "Refusing destructive instrumentation setup on non-emulator device ${serial}." >&2
    return 1
  }
  [[ "$(adb -s "${serial}" shell getprop ro.build.version.sdk | tr -d '\r')" == '35' ]] || {
    echo "Alarm release proof requires an API-35 emulator." >&2
    return 1
  }

  printf '%s\n' "${serial}"
}

resolve_apk_from_metadata() {
  python3 - "${1}" <<'PY'
import json
import pathlib
import sys

metadata = pathlib.Path(sys.argv[1]).resolve()
payload = json.loads(metadata.read_text(encoding="utf-8"))
outputs = [element.get("outputFile") for element in payload.get("elements", [])]
outputs = [output for output in outputs if output]
if len(outputs) != 1:
    raise SystemExit(f"Expected one APK output in {metadata}; found {len(outputs)}")
print((metadata.parent / outputs[0]).resolve())
PY
}

install_alarm_test_apks() {
  local app_apk
  local test_apk
  [[ -f "${app_metadata}" ]] || {
    echo "Missing debug APK metadata after connected tests: ${app_metadata}" >&2
    return 1
  }
  [[ -f "${test_metadata}" ]] || {
    echo "Missing instrumentation APK metadata after connected tests: ${test_metadata}" >&2
    return 1
  }
  app_apk="$(resolve_apk_from_metadata "${app_metadata}")" || return 1
  test_apk="$(resolve_apk_from_metadata "${test_metadata}")" || return 1
  [[ -f "${app_apk}" ]] || {
    echo "Missing debug APK after connected tests: ${app_apk}" >&2
    return 1
  }
  [[ -f "${test_apk}" ]] || {
    echo "Missing instrumentation APK after connected tests: ${test_apk}" >&2
    return 1
  }

  # connectedDebugAndroidTest's UTP runner uninstalls both packages after the
  # ordinary suite. Reinstall the exact artifacts it just built before the
  # permission-granted alarm proof.
  adb -s "${device_serial}" install -r -t "${app_apk}" || return 1
  adb -s "${device_serial}" install -r -t "${test_apk}" || return 1
  adb -s "${device_serial}" shell pm clear "${app_package}" >/dev/null || return 1
  adb -s "${device_serial}" shell pm grant "${app_package}" android.permission.POST_NOTIFICATIONS || return 1
  adb -s "${device_serial}" shell appops set "${app_package}" SCHEDULE_EXACT_ALARM allow || return 1
  # The API-35 default AOSP image can boot with deep idle disabled. Enable it
  # only for this disposable-emulator proof, verify the controller state, and
  # restore the original value in cleanup_alarm_environment.
  if [[ "${deep_idle_initial_state}" == '0' ]]; then
    adb -s "${device_serial}" shell dumpsys deviceidle enable deep || return 1
  fi
  [[ "$(read_deep_idle_enabled)" == '1' ]] || {
    echo "Deep idle is not enabled after CI alarm setup." >&2
    return 1
  }
}

trap cleanup_alarm_environment EXIT

if ! device_serial="$(select_api_35_emulator)"; then
  exit 1
fi
export ANDROID_SERIAL="${device_serial}"
configure_tablet_viewport

status=0
./gradlew --no-daemon --max-workers=2 --stacktrace connectedDebugAndroidTest || status=$?

# The ordinary suite deliberately exercises the denied-permission path on a
# fresh API-35 emulator. Its two positive alarm tests use assumptions, so a
# green Gradle report can otherwise hide that notification posting and exact
# allow-while-idle delivery were both skipped. Re-run only those positive
# cases in an explicitly granted, clean app state and reject any assumption
# skip. This remains emulator evidence; the physical-tablet checklist is a
# separate release gate.
alarm_report="${diagnostics_dir}/alarm-granted-deep-idle.txt"
alarm_setup_report="${diagnostics_dir}/alarm-granted-setup.txt"
alarm_class='cloud.dcompany.erp.core.alarm.AlarmLifecycleDeviceTest'
alarm_runner='cloud.dcompany.erp.test/androidx.test.runner.AndroidJUnitRunner'
alarm_tests="${alarm_class}#grantedNotificationCanBePostedWithPrivateVisibilityAndCancelled,${alarm_class}#exactAlarmReachesFailClosedReceiverDuringEmulatedDeepIdle"
rm -f "${alarm_report}" "${alarm_setup_report}"

if [[ "${status}" -eq 0 ]]; then
  alarm_setup_status=0
  if ! deep_idle_initial_state="$(read_deep_idle_enabled)"; then
    echo "Could not read the emulator's initial deep-idle state." | tee "${alarm_setup_report}" >&2
    alarm_setup_status=1
  elif [[ "${deep_idle_initial_state}" != '0' && "${deep_idle_initial_state}" != '1' ]]; then
    echo "Unexpected deep-idle state: ${deep_idle_initial_state:-empty}." | tee "${alarm_setup_report}" >&2
    alarm_setup_status=1
  elif ! install_alarm_test_apks 2>&1 | tee "${alarm_setup_report}"; then
    alarm_setup_status=1
  fi
  if [[ "${alarm_setup_status}" -ne 0 ]]; then
    echo "Could not prepare the granted alarm instrumentation pass." >&2
    status=1
  else
    alarm_status=0
    if ! adb -s "${device_serial}" shell am instrument -w -r \
      -e class "${alarm_tests}" "${alarm_runner}" | tee "${alarm_report}"; then
      alarm_status=1
    fi
    if [[ "${alarm_status}" -ne 0 ]] \
      || grep -q 'INSTRUMENTATION_STATUS_CODE: -4' "${alarm_report}" \
      || ! grep -Eq '^OK \(2 tests\)$' "${alarm_report}"; then
      echo "Granted notification/deep-idle alarm proof failed or was skipped." >&2
      status=1
    fi
  fi
fi

if [[ "${status}" -ne 0 ]]; then
  capture_diagnostics
fi

trap - EXIT
if ! cleanup_alarm_environment; then
  echo "Could not restore the emulator after alarm instrumentation." >&2
  status=1
  capture_diagnostics
fi

exit "${status}"
