#!/usr/bin/env bash
set -Eeuo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
android_root="${repo_root}/android-native"
diagnostics_dir="${android_root}/app/build/outputs/ci-diagnostics"
app_metadata="${android_root}/app/build/outputs/apk/debug/output-metadata.json"
test_metadata="${android_root}/app/build/outputs/apk/androidTest/debug/output-metadata.json"
app_package='cloud.dcompany.erp'
test_package='cloud.dcompany.erp.test'
test_runner="${test_package}/androidx.test.runner.AndroidJUnitRunner"
connected_results_dir="${android_root}/app/build/outputs/androidTest-results/connected/debug"
connected_report_dir="${android_root}/app/build/reports/androidTests/connected/debug"
shard_evidence_root="${diagnostics_dir}/instrumentation-shards"
discovered_tests_file="${shard_evidence_root}/discovered-tests.txt"
shard_verifier="${repo_root}/scripts/verify_android_instrumentation_shards.py"
startup_diagnostics="${repo_root}/scripts/capture_android_startup_diagnostics.py"
provenance_helper="${repo_root}/scripts/android_instrumentation_provenance.py"
provenance_dir="${shard_evidence_root}/provenance"
provenance_receipt_sha256=''
source_inventory_sha256=''
target_apk_sha256=''
test_apk_sha256=''
capture_run_id=''
persistence_run_id=''
gradle_runner_args=()
raw_runner_args=()
functional_shard_count=4
stress_class='cloud.dcompany.erp.ui.PhysicalComponentFrameStressUiTest'
gaming_board_class='cloud.dcompany.erp.ui.screens.gaming.GamingBoardUiTest'
persistence_class='cloud.dcompany.erp.ui.theme.WorkspaceBackgroundPersistenceUiTest'
expected_notification_skip='cloud.dcompany.erp.core.alarm.AlarmLifecycleDeviceTest#grantedNotificationCanBePostedWithPrivateVisibilityAndCancelled'
expected_exact_alarm_skip='cloud.dcompany.erp.core.alarm.AlarmLifecycleDeviceTest#exactAlarmReachesFailClosedReceiverDuringEmulatedDeepIdle'
device_serial=''
deep_idle_initial_state=''
display_size_override_initial=''
display_density_override_initial=''
accelerometer_rotation_initial=''
user_rotation_initial=''
tablet_viewport_configured=0
owned_emulator_pid=''
owned_emulator_log=''
frame_acceptance='physical'

mkdir -p "${diagnostics_dir}"
cd "${android_root}"

# Hosted macOS ran out of Kotlin compiler heap while assembling the debug test
# APK with the project default 2 GiB heap and two Gradle workers. Use the same
# bounded, in-process compiler envelope as the passing Android release build.
run_gradle_ci() {
  ./gradlew --no-daemon --max-workers=1 --stacktrace \
    '-Dorg.gradle.jvmargs=-Xmx4096m -Dfile.encoding=UTF-8' \
    -Pkotlin.compiler.execution.strategy=in-process "$@"
}

if [[ "${1:-}" == '--assemble-only' && "$#" -eq 1 ]]; then
  run_gradle_ci :app:assembleDebug :app:assembleDebugAndroidTest \
    2>&1 | tee "${diagnostics_dir}/preboot-assemble.log"
  exit
fi

capture_startup_diagnostics() {
  local phase=$1
  if ! python3 "${startup_diagnostics}" \
    --phase "${phase}" \
    --output-dir "${diagnostics_dir}/startup" \
    --serial "${device_serial}" \
    --overall-timeout 45; then
    echo "Android startup diagnostic capture failed during ${phase}; preserving the original CI status." >&2
  fi
  return 0
}

device_is_connected() {
  [[ -n "${device_serial}" ]] || return 1
  local state=''
  if command -v timeout >/dev/null 2>&1; then
    state="$(timeout 3s adb -s "${device_serial}" get-state 2>/dev/null)" || return 1
  else
    state="$(adb -s "${device_serial}" get-state 2>/dev/null)" || return 1
  fi
  [[ "${state}" == 'device' ]]
}

cleanup_alarm_environment() {
  [[ -n "${device_serial}" ]] || return 0
  if ! device_is_connected; then
    echo "Android emulator ${device_serial} disconnected before cleanup." >&2
    return 1
  fi
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

stop_owned_emulator() {
  [[ -n "${owned_emulator_pid}" ]] || return 0
  local expected_shutdown=0 exit_status=0 attempt
  if kill -0 "${owned_emulator_pid}" 2>/dev/null; then
    if timeout 10s adb -s "${device_serial}" emu kill >/dev/null 2>&1; then
      expected_shutdown=1
    fi
    for ((attempt = 0; attempt < 30; attempt++)); do
      kill -0 "${owned_emulator_pid}" 2>/dev/null || break
      sleep 1
    done
    if kill -0 "${owned_emulator_pid}" 2>/dev/null; then
      expected_shutdown=0
      kill -KILL "${owned_emulator_pid}" 2>/dev/null || true
      for ((attempt = 0; attempt < 5; attempt++)); do
        kill -0 "${owned_emulator_pid}" 2>/dev/null || break
        sleep 1
      done
      if kill -0 "${owned_emulator_pid}" 2>/dev/null; then
        echo "Owned emulator did not exit after SIGKILL." >&2
        return 1
      fi
    fi
  fi
  wait "${owned_emulator_pid}" || exit_status=$?
  owned_emulator_pid=''
  {
    printf 'expected_shutdown=%s\nprocess_exit_status=%s\n' "${expected_shutdown}" "${exit_status}"
    grep -E 'Android emulator version|Graphics Adapter |gles_mode_selected|Feature .Vulkan.|Storing crashdata|FATAL|ERROR' \
      "${owned_emulator_log}" | tail -n 60 || true
  } >> "${diagnostics_dir}/emulator-cold-boot.txt"
  [[ "${expected_shutdown}" -eq 1 && "${exit_status}" -eq 0 ]]
}

cold_boot_failed() {
  local stage=$1 status=$2
  printf 'cold_boot_failed_stage=%s\ncold_boot_failed_status=%s\n' "${stage}" "${status}" |
    tee -a "${diagnostics_dir}/emulator-cold-boot.txt" >&2
  return "${status}"
}

cold_boot_check() {
  local stage=$1
  shift
  "$@" || cold_boot_failed "${stage}" "$?"
}

cold_boot_hosted_emulator() {
  local emulator="${ANDROID_HOME:-}/emulator/emulator"
  local original_name original_pid boot_id original_boot_id boot_completed user_state overlays cpu_online attempt
  [[ "${device_serial}" == 'emulator-5554' && -x "${emulator}" && -n "${RUNNER_TEMP:-}" ]] ||
    { cold_boot_failed hosted_configuration "$?"; return 1; }
  original_name="$(timeout 5s adb -s "${device_serial}" emu avd name | tr -d '\r')" ||
    { cold_boot_failed discovery_avd "$?"; return 1; }
  [[ "${original_name}" == $'test\nOK' ]] || { cold_boot_failed discovery_avd "$?"; return 1; }
  original_boot_id="$(timeout 5s adb -s "${device_serial}" shell cat /proc/sys/kernel/random/boot_id | tr -d '\r')" ||
    { cold_boot_failed discovery_boot_id "$?"; return 1; }
  [[ "${original_boot_id}" =~ ^[[:xdigit:]-]{36}$ ]] || { cold_boot_failed discovery_boot_id "$?"; return 1; }
  original_pid="$(ps -eo pid=,comm=,args= | awk '$2 ~ /^qemu-system/ && $0 ~ / -port 5554( |$)/ && $0 ~ / -avd test( |$)/ {print $1}')" ||
    { cold_boot_failed discovery_process "$?"; return 1; }
  [[ "${original_pid}" =~ ^[1-9][0-9]*$ ]] || { cold_boot_failed discovery_process "$?"; return 1; }
  cold_boot_check discovery_guest_cleanup cleanup_alarm_environment || return 1
  cold_boot_check discovery_stop timeout 10s adb -s "${device_serial}" emu kill >/dev/null || return 1
  for ((attempt = 0; attempt < 40; attempt++)); do
    kill -0 "${original_pid}" 2>/dev/null || break
    sleep 1
  done
  if kill -0 "${original_pid}" 2>/dev/null; then
    cold_boot_failed discovery_shutdown 124
    return 1
  fi

  owned_emulator_log="$(mktemp "${RUNNER_TEMP}/dcompany-emulator-cold-boot.XXXXXX")" ||
    { cold_boot_failed native_log "$?"; return 1; }
  "${emulator}" -port 5554 -avd test -no-window -gpu swangle -feature -Vulkan \
    -no-snapshot -noaudio -no-boot-anim > "${owned_emulator_log}" 2>&1 &
  owned_emulator_pid=$!
  local boot_deadline=$((SECONDS + 180))
  while (( SECONDS < boot_deadline )); do
    if ! kill -0 "${owned_emulator_pid}" 2>/dev/null; then
      cold_boot_failed replacement_process 1
      return 1
    fi
    boot_completed="$(timeout 3s adb -s "${device_serial}" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" || boot_completed=''
    if [[ "${boot_completed}" == '1' ]]; then
      break
    fi
    sleep 2
  done
  [[ "${boot_completed}" == '1' ]] || { cold_boot_failed boot_completed 124; return 1; }
  boot_id="$(timeout 5s adb -s "${device_serial}" shell cat /proc/sys/kernel/random/boot_id | tr -d '\r')" ||
    { cold_boot_failed replacement_boot_id "$?"; return 1; }
  [[ "${boot_id}" =~ ^[[:xdigit:]-]{36}$ && "${boot_id}" != "${original_boot_id}" ]] ||
    { cold_boot_failed replacement_boot_id "$?"; return 1; }
  cold_boot_check emulator_version grep -Fq 'Android emulator version 37.2.13.0 (build_id 16740729)' "${owned_emulator_log}" || return 1
  cold_boot_check vulkan_disabled grep -Eq "Feature 'Vulkan' \([0-9]+\) is overridden to 'disabled'" "${owned_emulator_log}" || return 1
  cold_boot_check renderer_selection grep -Eq 'emuglConfig_init: vulkan_mode_selected:swiftshader gles_mode_selected:swangle[[:space:]]*$' "${owned_emulator_log}" || return 1
  cold_boot_check renderer_adapter grep -Eq 'Graphics Adapter Android Emulator OpenGL ES Translator \(ANGLE \([^[:cntrl:]]*SwiftShader Device[^[:cntrl:]]*\)\)[[:space:]]*$' "${owned_emulator_log}" || return 1

  cold_boot_check guest_api select_api_35_emulator >/dev/null || return 1
  cpu_online="$(timeout 5s adb -s "${device_serial}" shell cat /sys/devices/system/cpu/online | tr -d '\r')" ||
    { cold_boot_failed guest_cpu "$?"; return 1; }
  [[ "${cpu_online}" == '0-1' ]] || { cold_boot_failed guest_cpu "$?"; return 1; }

  # Android reports boot complete before user 0, SystemUI and Home finish starting.
  local user_deadline=$((SECONDS + 30))
  while (( SECONDS < user_deadline )); do
    user_state="$(timeout 5s adb -s "${device_serial}" shell cmd activity get-started-user-state 0 2>/dev/null | tr -d '\r')" || user_state=''
    [[ "${user_state}" == 'RUNNING_UNLOCKED' ]] && break
    sleep 1
  done
  [[ "${user_state:-}" == 'RUNNING_UNLOCKED' ]] || { cold_boot_failed user_unlock 124; return 1; }
  cold_boot_check keyguard_input timeout 20s adb -s "${device_serial}" shell input keyevent 82 || return 1
  local setting
  for setting in window_animation_scale transition_animation_scale animator_duration_scale; do
    cold_boot_check "${setting}" timeout 5s adb -s "${device_serial}" shell settings put global "${setting}" 0.0 || return 1
  done
  cold_boot_check gesture_apply timeout 10s adb -s "${device_serial}" shell cmd overlay enable-exclusive --category \
    com.android.internal.systemui.navbar.gestural || return 1
  local overlay_deadline=$((SECONDS + 15))
  while (( SECONDS < overlay_deadline )); do
    overlays="$(timeout 5s adb -s "${device_serial}" shell cmd overlay list --user 0 2>/dev/null | tr -d '\r' | \
      sed -n 's/^[[:space:]]*\(\[x\] com\.android\.internal\.systemui\.navbar\..*\)$/\1/p')" || overlays=''
    [[ "${overlays}" == '[x] com.android.internal.systemui.navbar.gestural' ]] && break
    sleep 1
  done
  [[ "${overlays:-}" == '[x] com.android.internal.systemui.navbar.gestural' ]] ||
    { cold_boot_failed gesture_readiness 124; return 1; }
  cold_boot_check tablet_viewport configure_tablet_viewport || return 1
  printf 'cold_boot_verified=true\nguest_cpu_online=%s\nguest_navigation=gestural\n' "${cpu_online}" \
    > "${diagnostics_dir}/emulator-cold-boot.txt"
}

configure_tablet_viewport() {
  local effective_size=''
  local effective_density=''
  local physical_size=''
  local physical_density=''
  local report="${diagnostics_dir}/tablet-viewport.txt"

  physical_size="$(adb -s "${device_serial}" shell wm size | tr -d '\r' | sed -n 's/^Physical size: //p')"
  physical_density="$(adb -s "${device_serial}" shell wm density | tr -d '\r' | sed -n 's/^Physical density: //p')"
  if [[ "${D_COMPANY_REQUIRE_NATIVE_AVD_SIZE:-0}" == '1' ]] && \
     { [[ "${physical_size}" != '2560x1600' ]] || [[ "${physical_density}" != '320' ]]; }; then
    echo "Hosted emulator has unexpected physical display ${physical_size:-unknown} at ${physical_density:-unknown} dpi." >&2
    return 1
  fi

  display_size_override_initial="$({ adb -s "${device_serial}" shell wm size || true; } | tr -d '\r' | sed -n 's/^Override size: //p')"
  display_density_override_initial="$({ adb -s "${device_serial}" shell wm density || true; } | tr -d '\r' | sed -n 's/^Override density: //p')"
  accelerometer_rotation_initial="$(adb -s "${device_serial}" shell settings get system accelerometer_rotation | tr -d '\r')"
  user_rotation_initial="$(adb -s "${device_serial}" shell settings get system user_rotation | tr -d '\r')"
  tablet_viewport_configured=1

  # Keep the 1280x800dp tablet layout. CI also verifies the AVD's physical
  # 2560x1600/320dpi framebuffer required by full-resolution capture and system
  # gesture tests. A post-boot override alone does not change the physical host.
  # Physical-device rendering remains a separate release gate.
  adb -s "${device_serial}" shell wm size 2560x1600 >/dev/null || return 1
  adb -s "${device_serial}" shell wm density 320 >/dev/null || return 1
  adb -s "${device_serial}" shell settings put system accelerometer_rotation 0 >/dev/null || return 1
  adb -s "${device_serial}" shell settings put system user_rotation 0 >/dev/null || return 1

  effective_size="$(adb -s "${device_serial}" shell wm size | tr -d '\r' | awk -F': ' '/Physical size:/{value=$2} /Override size:/{value=$2} END{print value}')"
  effective_density="$(adb -s "${device_serial}" shell wm density | tr -d '\r' | awk -F': ' '/Physical density:/{value=$2} /Override density:/{value=$2} END{print value}')"
  {
    echo "serial=${device_serial}"
    echo "physical_size=${physical_size}"
    echo "physical_density=${physical_density}"
    echo "effective_size=${effective_size}"
    echo "effective_density=${effective_density}"
    echo "logical_viewport=1280x800dp"
    echo "rotation=$(adb -s "${device_serial}" shell settings get system user_rotation | tr -d '\r')"
  } | tee "${report}"

  [[ "${effective_size}" == '2560x1600' ]] &&
  [[ "${effective_density}" == '320' ]] &&
  [[ "$(adb -s "${device_serial}" shell settings get system user_rotation | tr -d '\r')" == '0' ]]
}

configure_frame_acceptance() {
  [[ "${D_COMPANY_HOSTED_COLD_BOOT:-0}" == '1' ]] || return 0
  [[ "${GITHUB_ACTIONS:-}" == 'true' && "${RUNNER_OS:-}" == 'Linux' &&
     "${device_serial}" == 'emulator-5554' && -n "${owned_emulator_pid}" ]] || return 1
  kill -0 "${owned_emulator_pid}" || return 1
  grep -Fxq 'cold_boot_verified=true' "${diagnostics_dir}/emulator-cold-boot.txt" || return 1
  grep -Eq 'emuglConfig_init: vulkan_mode_selected:swiftshader gles_mode_selected:swangle[[:space:]]*$' "${owned_emulator_log}" || return 1
  grep -Eq 'Graphics Adapter Android Emulator OpenGL ES Translator \(ANGLE \([^[:cntrl:]]*SwiftShader Device[^[:cntrl:]]*\)\)[[:space:]]*$' "${owned_emulator_log}" || return 1
  local qemu
  qemu="$(timeout 5s adb -s "${device_serial}" shell getprop ro.kernel.qemu | tr -d '\r')" || return 1
  [[ "${qemu}" == '1' ]] || return 1
  frame_acceptance='hosted-software-telemetry'
}

reassert_tablet_viewport() {
  local effective_size=''
  local effective_density=''
  local effective_rotation=''

  adb -s "${device_serial}" shell wm size 2560x1600 >/dev/null || return 1
  adb -s "${device_serial}" shell wm density 320 >/dev/null || return 1
  adb -s "${device_serial}" shell settings put system accelerometer_rotation 0 >/dev/null || return 1
  adb -s "${device_serial}" shell settings put system user_rotation 0 >/dev/null || return 1
  effective_size="$(adb -s "${device_serial}" shell wm size | tr -d '\r' | awk -F': ' '/Physical size:/{value=$2} /Override size:/{value=$2} END{print value}')" || return 1
  [[ "${effective_size}" == '2560x1600' ]] || return 1
  effective_density="$(adb -s "${device_serial}" shell wm density | tr -d '\r' | awk -F': ' '/Physical density:/{value=$2} /Override density:/{value=$2} END{print value}')" || return 1
  [[ "${effective_density}" == '320' ]] || return 1
  effective_rotation="$(adb -s "${device_serial}" shell settings get system user_rotation | tr -d '\r')" || return 1
  [[ "${effective_rotation}" == '0' ]] || return 1
}

wait_for_launcher_focus() {
  local attempt
  local focused_window=''
  for attempt in {1..40}; do
    focused_window="$(adb -s "${device_serial}" shell dumpsys window | tr -d '\r' | sed -n 's/.*mCurrentFocus=//p' | head -n 1)" || return 1
    if [[ "${focused_window}" == *launcher* ]]; then
      return 0
    fi
    sleep 0.25 || return 1
  done
  echo "Launcher did not regain focus before the next instrumentation lane: ${focused_window:-unknown}." >&2
  return 1
}

reset_emulator_for_instrumentation_lane() {
  # Every lane starts outside the prior app/test process and with the exact
  # tablet geometry. This prevents hundreds of Compose dialogs, IME windows,
  # and frame-stress surfaces from accumulating in one instrumentation run.
  adb -s "${device_serial}" shell am force-stop "${test_package}" >/dev/null || return 1
  adb -s "${device_serial}" shell am force-stop "${app_package}" >/dev/null || return 1
  adb -s "${device_serial}" shell input keyevent KEYCODE_WAKEUP >/dev/null || return 1
  adb -s "${device_serial}" shell input keyevent KEYCODE_HOME >/dev/null || return 1
  adb -s "${device_serial}" shell cmd statusbar collapse >/dev/null 2>&1 || true
  wait_for_launcher_focus || return 1
  adb -s "${device_serial}" shell am wait-for-broadcast-idle >/dev/null || return 1
  reassert_tablet_viewport || return 1
}

read_deep_idle_enabled() {
  adb -s "${device_serial}" shell dumpsys deviceidle enabled deep | tr -d '[:space:]'
}

capture_diagnostics() {
  [[ -n "${device_serial}" ]] || return 0
  if ! device_is_connected; then
    printf 'Android emulator %s disconnected before on-device diagnostics.\n' \
      "${device_serial}" > "${diagnostics_dir}/device-disconnected.txt"
    return 0
  fi
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
output = pathlib.Path(outputs[0])
if output.is_absolute() or len(output.parts) != 1:
    raise SystemExit("APK output must be a basename within its metadata directory")
print(metadata.parent / output)
PY
}

prepare_instrumentation_provenance() {
  local app_apk
  local test_apk
  local values
  app_apk="$(resolve_apk_from_metadata "${app_metadata}")" || return 1
  test_apk="$(resolve_apk_from_metadata "${test_metadata}")" || return 1
  provenance_receipt_sha256="$(python3 "${provenance_helper}" create --repo-root "${repo_root}" \
    --target-apk "${app_apk}" --test-apk "${test_apk}" --output "${provenance_dir}")" || return 1
  [[ "${provenance_receipt_sha256}" =~ ^[0-9a-f]{64}$ ]] || return 1
  printf '%s\n' "${provenance_receipt_sha256}" > "${provenance_dir}/initial-receipt-sha256.txt"
  values="$(python3 "${provenance_helper}" values --repo-root "${repo_root}" --output "${provenance_dir}" \
    --expected-receipt-sha256 "${provenance_receipt_sha256}")" || return 1
  read -r source_inventory_sha256 target_apk_sha256 test_apk_sha256 capture_run_id persistence_run_id <<< "${values}"
  gradle_runner_args=(
    "-Pandroid.testInstrumentationRunnerArguments.dcompany.sourceInventorySha256=${source_inventory_sha256}"
    "-Pandroid.testInstrumentationRunnerArguments.dcompany.targetApkSha256=${target_apk_sha256}"
    "-Pandroid.testInstrumentationRunnerArguments.dcompany.testApkSha256=${test_apk_sha256}"
    "-Pandroid.testInstrumentationRunnerArguments.dcompany.captureRunId=${capture_run_id}"
    '-Pandroid.testInstrumentationRunnerArguments.dcompany.testDisplayId=0'
  )
  raw_runner_args=(
    -e dcompany.sourceInventorySha256 "${source_inventory_sha256}"
    -e dcompany.targetApkSha256 "${target_apk_sha256}"
    -e dcompany.testApkSha256 "${test_apk_sha256}"
    -e dcompany.captureRunId "${capture_run_id}"
    -e dcompany.testDisplayId 0
  )
}

verify_instrumentation_provenance() {
  python3 "${provenance_helper}" verify --repo-root "${repo_root}" --output "${provenance_dir}" \
    --expected-receipt-sha256 "${provenance_receipt_sha256}"
}

install_discovery_test_apks() {
  local app_apk
  local test_apk
  app_apk="$(resolve_apk_from_metadata "${app_metadata}")" || return 1
  test_apk="$(resolve_apk_from_metadata "${test_metadata}")" || return 1
  [[ -f "${app_apk}" && -f "${test_apk}" ]] || {
    echo "Instrumentation discovery APKs are missing after assembly." >&2
    return 1
  }
  adb -s "${device_serial}" install -r -t "${app_apk}" >/dev/null || return 1
  adb -s "${device_serial}" install -r -t "${test_apk}" >/dev/null || return 1
  adb -s "${device_serial}" shell pm clear "${app_package}" >/dev/null || return 1
}

discover_instrumentation_tests() {
  local discovery_log="${shard_evidence_root}/discovery.log"
  local discovery_status=0

  mkdir -p "${shard_evidence_root}"
  : > "${discovery_log}"
  : > "${discovered_tests_file}"

  if ! install_discovery_test_apks; then
    discovery_status=1
  elif ! adb -s "${device_serial}" shell am instrument -w -r \
    "${raw_runner_args[@]}" -e log true "${test_runner}" | tee "${discovery_log}"; then
    discovery_status=1
  fi

  if [[ "${discovery_status}" -eq 0 ]] \
    && ! python3 "${shard_verifier}" discover \
      --log "${discovery_log}" \
      --expected-tests "${discovered_tests_file}"; then
    discovery_status=1
  fi

  if [[ "${discovery_status}" -ne 0 ]]; then
    capture_startup_diagnostics discovery-failure
  fi

  adb -s "${device_serial}" shell am force-stop "${test_package}" >/dev/null 2>&1 || true
  adb -s "${device_serial}" shell am force-stop "${app_package}" >/dev/null 2>&1 || true
  adb -s "${device_serial}" uninstall "${test_package}" >/dev/null 2>&1 || true
  adb -s "${device_serial}" uninstall "${app_package}" >/dev/null 2>&1 || true
  return "${discovery_status}"
}

clear_connected_outputs() {
  [[ "${connected_results_dir}" == "${android_root}/app/build/outputs/androidTest-results/connected/debug" ]] || return 1
  [[ "${connected_report_dir}" == "${android_root}/app/build/reports/androidTests/connected/debug" ]] || return 1
  rm -rf -- "${connected_results_dir}" "${connected_report_dir}" || return 1
}

preserve_instrumentation_lane() {
  local lane_dir=$1
  local preserved=0
  local results_ready=0
  local report_ready=0
  if mkdir -p "${lane_dir}/results"; then results_ready=1; else preserved=1; fi
  if mkdir -p "${lane_dir}/report"; then report_ready=1; else preserved=1; fi
  if [[ -d "${connected_results_dir}" ]]; then
    if (( results_ready )); then
      cp -R "${connected_results_dir}/." "${lane_dir}/results/" || preserved=1
    fi
  else
    echo "Instrumentation lane produced no connected test results: ${lane_dir##*/}." >&2
    preserved=1
  fi
  if [[ -d "${connected_report_dir}" ]]; then
    if (( report_ready )); then
      cp -R "${connected_report_dir}/." "${lane_dir}/report/" || preserved=1
    fi
  else
    echo "Instrumentation lane produced no connected HTML report: ${lane_dir##*/}." >&2
    preserved=1
  fi
  return "${preserved}"
}

sample_host_health() {
  local lane_dir=$1
  local phase=$2
  {
    printf 'phase=%s utc=%s\n' "${phase}" "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    if [[ -r /proc/meminfo ]]; then
      awk '/^(MemAvailable|SwapFree):/ { print }' /proc/meminfo
    fi
    for counter in /sys/fs/cgroup/memory.current /sys/fs/cgroup/memory.max; do
      if [[ -r "${counter}" ]]; then
        printf '%s=' "${counter##*/}"
        cat "${counter}"
      fi
    done
    if [[ -r /sys/fs/cgroup/memory.events ]]; then
      awk '/^(oom|oom_kill|oom_group_kill) / { print "cgroup_" $0 }' \
        /sys/fs/cgroup/memory.events
    fi
    ps -eo pid=,ppid=,rss=,comm= 2>/dev/null \
      | awk '$4 ~ /(qemu|emulator)/ { print "process " $0; if (++count == 8) exit }'
  } >> "${lane_dir}/host-health.txt" 2>&1 || true
}

# Read only the preserved XML and existing health sample, after artifact retention.
# A broken/missing diagnostic helper cannot alter the original lane verdict.
summarize_failed_instrumentation_lane() {
  local lane_dir=$1
  if ! python3 - "${shard_verifier}" "${lane_dir}" <<'PY_FAILURE_DIAGNOSTICS'
import subprocess
import sys

try:
    result = subprocess.run(
        [sys.executable, sys.argv[1], "failure-diagnostics", "--lane", sys.argv[2]],
        stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, timeout=10, check=False,
    )
    if result.returncode:
        raise ValueError("diagnostic child failed")
    sys.stdout.write(result.stdout[:32_000].decode("ascii"))
except (OSError, ValueError, subprocess.TimeoutExpired):
    print("Instrumentation failure diagnostics unavailable; original lane failure retained.")
PY_FAILURE_DIAGNOSTICS
  then
    echo 'Instrumentation failure diagnostics unavailable; original lane failure retained.' >&2
  fi
  return 0
}

run_connected_instrumentation_lane() {
  local lane_name=$1
  shift
  local lane_dir="${shard_evidence_root}/${lane_name}"
  local lane_status=0
  local outputs_cleared=0
  local gradle_attempted=0
  local lane_args=()

  if [[ "${lane_name}" == 'physical-frame-stress' ]]; then
    lane_args=(
      "-Pandroid.testInstrumentationRunnerArguments.erpFrameAcceptance=${frame_acceptance}"
      '-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true'
    )
  fi

  mkdir -p "${lane_dir}" || return 1
  sample_host_health "${lane_dir}" before || true
  if ! clear_connected_outputs; then
    lane_status=1
  else
    outputs_cleared=1
    if ! verify_instrumentation_provenance; then
      lane_status=1
    elif ! reset_emulator_for_instrumentation_lane 2>&1 | tee "${lane_dir}/reset.log"; then
      lane_status=1
    else
      gradle_attempted=1
      if ! run_gradle_ci "${gradle_runner_args[@]}" "${lane_args[@]}" "$@" :app:connectedDebugAndroidTest \
        2>&1 | tee "${lane_dir}/gradle.log"; then
        lane_status=1
      fi
    fi
  fi
  if ! verify_instrumentation_provenance; then
    lane_status=1
  fi
  sample_host_health "${lane_dir}" after || true
  # Never relabel prior XML after a failed clear or a lane that never ran.
  if (( outputs_cleared && gradle_attempted )); then
    if ! preserve_instrumentation_lane "${lane_dir}"; then
      lane_status=1
    fi
    if [[ "${lane_name}" == 'physical-frame-stress' ]]; then
      if ! preserve_frame_evidence "${lane_dir}"; then lane_status=1; fi
      if ! adb -s "${device_serial}" uninstall "${test_package}" >/dev/null; then lane_status=1; fi
      if ! adb -s "${device_serial}" uninstall "${app_package}" >/dev/null; then lane_status=1; fi
    fi
  fi
  if (( lane_status != 0 )); then
    summarize_failed_instrumentation_lane "${lane_dir}" || true
  fi
  return "${lane_status}"
}

preserve_frame_evidence() {
  local lane_dir=$1
  local evidence_dir="${lane_dir}/frame-evidence"
  local filename
  local result=0
  mkdir -p "${evidence_dir}" || return 1
  for filename in \
    frame-metrics.txt frame-metrics-component-gaming-timer-1hz.txt \
    frame-metrics-component-connectivity-only-3s.txt \
    01-gaming-online.png 02-gaming-server-issue.png 03-gaming-after-connectivity-stress.png \
    component-gaming-timer-1hz-before.png component-gaming-timer-1hz-after.png \
    component-connectivity-only-3s-before.png component-connectivity-only-3s-after.png; do
    if ! timeout 15s adb -s "${device_serial}" pull \
      "/sdcard/Android/data/${app_package}/files/testlab/${filename}" "${evidence_dir}/${filename}"; then
      result=1
    fi
  done
  if [[ "${frame_acceptance}" == 'hosted-software-telemetry' ]]; then
    grep -E 'Android emulator version|Graphics Adapter |gles_mode_selected|Feature .Vulkan.' \
      "${owned_emulator_log}" > "${evidence_dir}/host-renderer.txt" || result=1
  fi
  if ! python3 "${shard_verifier}" frame-evidence --evidence-dir "${evidence_dir}" \
    --frame-acceptance "${frame_acceptance}" --provenance "${provenance_dir}/provenance.json"; then
    result=1
  fi
  return "${result}"
}


run_persistence_stage() {
  local stage=$1
  local lane_name=$2
  local lane_dir="${shard_evidence_root}/${lane_name}"
  local stage_status=0
  mkdir -p "${lane_dir}" || return 1
  sample_host_health "${lane_dir}" before
  verify_instrumentation_provenance || return 1
  reset_emulator_for_instrumentation_lane 2>&1 | tee "${lane_dir}/reset.log" || return 1
  # Python provides a portable deadline; adb exit alone does not prove a test passed.
  if ! python3 - "${device_serial}" "${raw_runner_args[@]}" \
    -e dcompany.persistenceRunId "${persistence_run_id}" \
    -e class "${persistence_class}#${stage}" "${test_runner}" <<'PY_RUN' \
    | tee "${lane_dir}/instrumentation.log"; then
import subprocess
import sys
try:
    result = subprocess.run(
        ["adb", "-s", sys.argv[1], "shell", "am", "instrument", "-w", "-r", *sys.argv[2:]],
        timeout=300,
        check=False,
    )
    raise SystemExit(result.returncode)
except subprocess.TimeoutExpired:
    print("Persistence instrumentation exceeded 300 seconds", file=sys.stderr)
    raise SystemExit(1)
PY_RUN
    stage_status=1
  fi
  if ! python3 "${shard_verifier}" single \
    --log "${lane_dir}/instrumentation.log" --expected-test "${persistence_class}#${stage}"; then
    stage_status=1
  fi
  if ! verify_instrumentation_provenance; then
    stage_status=1
  fi
  sample_host_health "${lane_dir}" after
  return "${stage_status}"
}

run_persistence_chain() {
  # UTP lanes uninstall packages. Install once here, then preserve app data and
  # both APKs between stages; reset only force-stops the app/test processes.
  install_discovery_test_apks || return 1
  run_persistence_stage stageAImportBothSlotsAndPersist persistence-stage-a || return 1
  run_persistence_stage stageBVerifyBothSlotsAfterProcessRestart persistence-stage-b
}

install_alarm_test_apks() {
  local app_apk
  local test_apk
  local verified_deep_idle
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
  verified_deep_idle="$(read_deep_idle_enabled)" || return 1
  [[ "${verified_deep_idle}" == '1' ]] || {
    echo "Deep idle is not enabled after CI alarm setup." >&2
    return 1
  }
}

trap 'cleanup_alarm_environment || true; stop_owned_emulator || true' EXIT

if ! device_serial="$(select_api_35_emulator)"; then
  exit 1
fi
export ANDROID_SERIAL="${device_serial}"
configure_tablet_viewport

status=0
[[ "${shard_evidence_root}" == "${android_root}/app/build/"* ]]
rm -rf -- "${shard_evidence_root}"
mkdir -p "${shard_evidence_root}"

# Discover the exact runner inventory once, without executing test bodies. The
# later verifier rejects a missing, duplicated or unexpected test across all
# functional shards and both isolated Gaming and physical-frame lanes.
if [[ "${D_COMPANY_HOSTED_COLD_BOOT:-0}" == '1' ]]; then
  if [[ "${D_COMPANY_REQUIRE_NATIVE_AVD_SIZE:-0}" != '1' || ! -s "${diagnostics_dir}/preboot-assemble.log" ]] ||
     ! cp "${diagnostics_dir}/preboot-assemble.log" "${shard_evidence_root}/assemble.log"; then
    echo "Hosted instrumentation requires native tablet geometry and preboot assembly evidence." >&2
    status=1
  fi
else
  capture_startup_diagnostics pre-assembly
  if ! run_gradle_ci :app:assembleDebug :app:assembleDebugAndroidTest \
    2>&1 | tee "${shard_evidence_root}/assemble.log"; then
    status=1
  fi
fi
if [[ "${status}" -eq 0 ]]; then
  if ! prepare_instrumentation_provenance; then
    status=1
  else
    capture_startup_diagnostics pre-discovery
    if ! discover_instrumentation_tests; then
      status=1
    fi
  fi
fi

if [[ "${status}" -eq 0 && "${D_COMPANY_HOSTED_COLD_BOOT:-0}" == '1' ]]; then
  echo "Cold booting the full-resolution emulator after inventory discovery."
  if ! cold_boot_hosted_emulator; then
    echo "The planned emulator cold boot failed; instrumentation will not continue." >&2
    status=1
  fi
fi

if [[ "${status}" -eq 0 ]] && ! configure_frame_acceptance; then
  echo "Could not verify the hosted software renderer for frame telemetry." >&2
  status=1
fi

if [[ "${status}" -eq 0 ]]; then
  if ! run_connected_instrumentation_lane \
    'gaming-board' \
    "-Pandroid.testInstrumentationRunnerArguments.class=${gaming_board_class}"; then
    status=1
  fi
fi

if [[ "${status}" -eq 0 ]]; then
  for ((shard_index = 0; shard_index < functional_shard_count; shard_index++)); do
    if ! run_connected_instrumentation_lane \
      "functional-shard-${shard_index}" \
      "-Pandroid.testInstrumentationRunnerArguments.numShards=${functional_shard_count}" \
      "-Pandroid.testInstrumentationRunnerArguments.shardIndex=${shard_index}" \
      "-Pandroid.testInstrumentationRunnerArguments.notClass=${stress_class},${gaming_board_class},${persistence_class}"; then
      status=1
      break
    fi
  done
fi

if [[ "${status}" -eq 0 ]]; then
  # These long render-cadence tests intentionally create sustained physical
  # frame load. Run them last in their own fresh instrumentation process so
  # they cannot poison functional Compose/IME/window tests that follow.
  if ! run_connected_instrumentation_lane \
    'physical-frame-stress' \
    "-Pandroid.testInstrumentationRunnerArguments.class=${stress_class}"; then
    status=1
  fi
fi

if [[ "${status}" -eq 0 ]]; then
  if ! run_persistence_chain; then
    status=1
  fi
fi

if [[ "${status}" -eq 0 ]]; then
  if ! python3 "${shard_verifier}" verify \
    --expected-tests "${discovered_tests_file}" \
    --evidence-root "${shard_evidence_root}" \
    --functional-shards "${functional_shard_count}" \
    --stress-class "${stress_class}" \
    --gaming-board-class "${gaming_board_class}" \
    --persistence-class "${persistence_class}" \
    --frame-acceptance "${frame_acceptance}" \
    --provenance "${provenance_dir}/provenance.json" \
    --expected-skip "${expected_notification_skip}" \
    --expected-skip "${expected_exact_alarm_skip}" \
    --summary "${shard_evidence_root}/summary.txt"; then
    status=1
  fi
fi

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
  elif ! {
    reset_emulator_for_instrumentation_lane &&
      install_alarm_test_apks
  } 2>&1 | tee "${alarm_setup_report}"; then
    alarm_setup_status=1
  fi
  if [[ "${alarm_setup_status}" -ne 0 ]]; then
    echo "Could not prepare the granted alarm instrumentation pass." >&2
    status=1
  else
    alarm_status=0
    if ! adb -s "${device_serial}" shell am instrument -w -r \
      "${raw_runner_args[@]}" -e class "${alarm_tests}" "${alarm_runner}" | tee "${alarm_report}"; then
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

if [[ "${status}" -eq 0 ]] && ! verify_instrumentation_provenance; then
  status=1
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
if ! stop_owned_emulator; then
  echo "The owned Android emulator did not shut down normally." >&2
  status=1
fi

exit "${status}"
