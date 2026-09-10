#!/bin/bash
# Scan immutable Docker archives under one bounded, private scanner runtime.

set -euo pipefail

if [ "$#" -lt 10 ] || [ $((($# - 5) % 5)) -ne 0 ]; then
  echo "Usage: $0 WORK_ROOT METADATA SYFT_IMAGE GRYPE_IMAGE PROBE_IMAGE_ID SERVICE IMAGE_ID ARCHIVE SYFT_REPORT GRYPE_REPORT [...]" >&2
  exit 2
fi

WORK_ROOT=$1
METADATA=$2
SYFT_IMAGE=$3
GRYPE_IMAGE=$4
PROBE_IMAGE_ID=$5
shift 5

SCANNER_UID=65532
SCANNER_GID=65532
EXPECTED_SYFT_IMAGE='anchore/syft:v1.42.3@sha256:5999d209a342e55e9edf70bf8930fb5b86d8f2a783fa401178372c50e21b1d36'
EXPECTED_GRYPE_IMAGE='anchore/grype:v0.118.0@sha256:8a93fc48da96bd6ec5981279d099b69de11541dc68fdf222fb9161f8ff284af7'
TMPFS_SPEC='/tmp:rw,noexec,nosuid,nodev,size=512m,mode=1777'
MEMORY_LIMIT=768m
MEMORY_SWAP_LIMIT=1536m
GOMEMLIMIT=256MiB
GOMAXPROCS=1
DISK_WORKSPACE_BYTES=$((3 * 1024 * 1024 * 1024))
MIN_REMAINING_BYTES=$((1 * 1024 * 1024 * 1024))

for required_command in docker mount umount mountpoint findmnt python3 stat df tail timeout; do
  if ! command -v "$required_command" >/dev/null 2>&1; then
    echo "Required scanner command is unavailable: $required_command" >&2
    exit 1
  fi
done

if [ "$SYFT_IMAGE" != "$EXPECTED_SYFT_IMAGE" ] || \
   [ "$GRYPE_IMAGE" != "$EXPECTED_GRYPE_IMAGE" ]; then
  echo "Scanner images must use the reviewed version and digest pins." >&2
  exit 1
fi
if ! [[ "$PROBE_IMAGE_ID" =~ ^sha256:[0-9a-f]{64}$ ]]; then
  echo "Mount probe image must be an immutable image ID." >&2
  exit 1
fi

canonical_path() {
  python3 - "$1" <<'PY'
import os
import sys
print(os.path.realpath(sys.argv[1]))
PY
}

SCANNER_TOOL_DIR=$(canonical_path "$(dirname "${BASH_SOURCE[0]}")")
ARCHIVE_IDENTITY_VERIFIER="$SCANNER_TOOL_DIR/verify-image-archive-identity.py"
if [ ! -f "$ARCHIVE_IDENTITY_VERIFIER" ] || [ -L "$ARCHIVE_IDENTITY_VERIFIER" ] || \
   [ "$(canonical_path "$ARCHIVE_IDENTITY_VERIFIER")" != "$ARCHIVE_IDENTITY_VERIFIER" ]; then
  echo "Archive identity verifier is missing or linked." >&2
  exit 1
fi

if [ ! -d "$WORK_ROOT" ] || [ -L "$WORK_ROOT" ] || \
   [ "$(canonical_path "$WORK_ROOT")" != "$WORK_ROOT" ] || \
   [ "$(stat -Lc '%u:%g:%a:%F' "$WORK_ROOT")" != "0:0:700:directory" ]; then
  echo "Scanner work root must be a canonical root-owned mode-0700 directory." >&2
  exit 1
fi
EVIDENCE_ROOT=$(dirname "$METADATA")
if [ ! -d "$EVIDENCE_ROOT" ] || [ -L "$EVIDENCE_ROOT" ] || \
   [ "$(canonical_path "$EVIDENCE_ROOT")" != "$EVIDENCE_ROOT" ] || \
   [ "$(stat -Lc '%u:%g:%a:%F' "$EVIDENCE_ROOT")" != "0:0:700:directory" ] || \
   [ "$WORK_ROOT" != "$EVIDENCE_ROOT/scanner-work" ]; then
  echo "Scanner evidence root must be canonical, root-private, and contain the work root." >&2
  exit 1
fi
if [ ! -f "$METADATA" ] || [ -L "$METADATA" ] || \
   [ "$(canonical_path "$METADATA")" != "$METADATA" ] || \
   [ "$(stat -Lc '%u:%g:%a:%F' "$METADATA")" != "0:0:600:regular file" ]; then
  echo "Scanner metadata must be an existing root-owned mode-0600 regular file." >&2
  exit 1
fi
WORK_ROOT_ID=$(stat -Lc '%d:%i:%u:%g:%a:%F' "$WORK_ROOT")

declare -a SERVICES=()
declare -a IMAGE_IDS=()
declare -a ARCHIVES=()
declare -a SYFT_REPORTS=()
declare -a GRYPE_REPORTS=()
declare -a CONFIG_IMAGE_IDS=()
SERVICE_KEYS='|'
OUTPUT_KEYS='|'
archive_total_bytes=0
while [ "$#" -gt 0 ]; do
  service=$1
  image_id=$2
  archive=$3
  syft_report=$4
  grype_report=$5
  shift 5
  if ! [[ "$service" =~ ^[a-z][a-z0-9-]{0,31}$ ]] || \
     [[ "$SERVICE_KEYS" == *"|$service|"* ]]; then
    echo "Scanner service names must be unique and safe: $service" >&2
    exit 1
  fi
  if ! [[ "$image_id" =~ ^sha256:[0-9a-f]{64}$ ]]; then
    echo "Scanner image ID is invalid for $service." >&2
    exit 1
  fi
  if [ ! -f "$archive" ] || [ ! -s "$archive" ] || [ -L "$archive" ] || \
     [ "$(canonical_path "$archive")" != "$archive" ] || \
     [ "$(dirname "$archive")" != "$EVIDENCE_ROOT" ] || \
     [ "$(stat -Lc '%u:%g:%a:%F' "$archive")" != "0:0:444:regular file" ] || \
     [[ "$archive" == *','* ]]; then
    echo "Immutable image archive is invalid for $service." >&2
    exit 1
  fi
  for output in "$syft_report" "$grype_report"; do
    if [ -e "$output" ] || [ -L "$output" ] || \
       [ "$(dirname "$output")" != "$EVIDENCE_ROOT" ] || \
       [ ! -d "$(dirname "$output")" ] || \
       [ "$(canonical_path "$(dirname "$output")")/$(basename "$output")" != "$output" ] || \
       [[ "$output" == *','* ]] || [[ "$OUTPUT_KEYS" == *"|$output|"* ]]; then
      echo "Scanner output path is invalid or already exists for $service." >&2
      exit 1
    fi
    OUTPUT_KEYS+="$output|"
  done
  SERVICES+=("$service")
  SERVICE_KEYS+="$service|"
  IMAGE_IDS+=("$image_id")
  ARCHIVES+=("$archive")
  SYFT_REPORTS+=("$syft_report")
  GRYPE_REPORTS+=("$grype_report")
  archive_total_bytes=$((archive_total_bytes + $(stat -Lc '%s' "$archive")))
done

for index in "${!SERVICES[@]}"; do
  service=${SERVICES[$index]}
  image_id=${IMAGE_IDS[$index]}
  archive=${ARCHIVES[$index]}
  identity_validation=$(timeout --foreground --signal TERM --kill-after=15s 300s \
    python3 "$ARCHIVE_IDENTITY_VERIFIER" \
    "$archive" "$image_id" "$service") || {
      echo "Archive identity verification failed for $service." >&2
      exit 1
    }
  config_key=${service//-/_}_scanner_config_image_id
  scanner_config_id=""
  while IFS='=' read -r key value; do
    if [ "$key" = "$config_key" ]; then
      if [ -n "$scanner_config_id" ]; then
        echo "Archive identity verifier returned duplicate config identity for $service." >&2
        exit 1
      fi
      scanner_config_id=$value
    fi
  done <<< "$identity_validation"
  if ! [[ "$scanner_config_id" =~ ^sha256:[0-9a-f]{64}$ ]]; then
    echo "Archive identity verifier returned invalid config identity for $service." >&2
    exit 1
  fi
  CONFIG_IMAGE_IDS+=("$scanner_config_id")
  printf '%s\n' "$identity_validation" >> "$METADATA"
done

available_bytes=$(df -PB1 "$WORK_ROOT" | awk 'END {print $4}')
required_bytes=$((archive_total_bytes + DISK_WORKSPACE_BYTES))
if ! [[ "$available_bytes" =~ ^[0-9]+$ ]] || [ "$available_bytes" -lt "$required_bytes" ]; then
  echo "Scanner disk preflight requires the archive bytes plus 3 GiB free workspace." >&2
  exit 1
fi
printf 'scanner_archive_total_bytes=%s\nscanner_disk_available_before_bytes=%s\nscanner_disk_required_before_bytes=%s\nscanner_disk_min_remaining_bytes=%s\nscanner_disk_preflight_is_quota=false\nscanner_tmpfs_bytes=536870912\nscanner_memory_bytes=805306368\nscanner_memory_plus_swap_bytes=1610612736\n' \
  "$archive_total_bytes" "$available_bytes" "$required_bytes" "$MIN_REMAINING_BYTES" \
  >> "$METADATA"

RUN_DIR=$(mktemp -d "$WORK_ROOT/scanner.XXXXXX")
chmod 700 "$RUN_DIR"
RUN_DIR_ID=$(stat -Lc '%d:%i:%u:%g:%a:%F' "$RUN_DIR")
CACHE_MOUNT="$RUN_DIR/grype-cache"
mkdir "$CACHE_MOUNT"
chmod 0711 "$CACHE_MOUNT"
CACHE_MOUNT_ID=$(stat -Lc '%d:%i:%u:%g:%a:%F' "$CACHE_MOUNT")
ACTIVE_CONTAINER=""
ACTIVE_CONTAINER_NAME=""
CACHE_MOUNTED=false
MONITOR_PID=""
MONITOR_UNRESOLVED=false
CACHE_PEAK_FILE="$RUN_DIR/cache-peak-bytes"
printf '0\n' > "$CACHE_PEAK_FILE"

finish_owned_container() {
  local running ownership
  if [ -z "$ACTIVE_CONTAINER" ] && [ -z "$ACTIVE_CONTAINER_NAME" ]; then
    return 0
  fi
  if [ -z "$ACTIVE_CONTAINER" ]; then
    if ! ownership=$(timeout --foreground --signal TERM --kill-after=5s 15s \
      docker inspect --format '{{.Id}}|{{index .Config.Labels "cloud.dcompany.erp.scanner-invocation"}}|{{.Name}}' \
      "$ACTIVE_CONTAINER_NAME"); then
      echo "Cannot resolve the ambiguously created scanner container." >&2
      return 1
    fi
    ACTIVE_CONTAINER=${ownership%%|*}
  fi
  if ! [[ "$ACTIVE_CONTAINER" =~ ^[0-9a-f]{64}$ ]]; then
    echo "Owned scanner container ID is invalid; refusing broad cleanup." >&2
    return 1
  fi
  if ! ownership=$(timeout --foreground --signal TERM --kill-after=5s 15s \
    docker inspect --format '{{.Id}}|{{index .Config.Labels "cloud.dcompany.erp.scanner-invocation"}}|{{.Name}}' \
    "$ACTIVE_CONTAINER"); then
    echo "Cannot verify the owned scanner container identity." >&2
    return 1
  fi
  if [ "$ownership" != "$ACTIVE_CONTAINER|$RUN_ID|/$ACTIVE_CONTAINER_NAME" ]; then
    echo "Scanner container ownership label or name mismatch; refusing cleanup." >&2
    return 1
  fi
  if ! running=$(timeout --foreground --signal TERM --kill-after=5s 15s \
    docker inspect --format '{{.State.Running}}' "$ACTIVE_CONTAINER"); then
    echo "Cannot prove the owned scanner container state." >&2
    return 1
  fi
  if [ "$running" = true ]; then
    if ! timeout --foreground --signal TERM --kill-after=5s 30s \
      docker stop --time 10 "$ACTIVE_CONTAINER" >/dev/null; then
      echo "Cannot stop the owned scanner container." >&2
      return 1
    fi
    if ! timeout --foreground --signal TERM --kill-after=5s 15s \
      docker wait "$ACTIVE_CONTAINER" >/dev/null; then
      echo "Cannot confirm the owned scanner container stopped." >&2
      return 1
    fi
  fi
  if ! timeout --foreground --signal TERM --kill-after=5s 15s \
    docker rm "$ACTIVE_CONTAINER" >/dev/null; then
    echo "Cannot remove the owned scanner container." >&2
    return 1
  fi
  ACTIVE_CONTAINER=""
  ACTIVE_CONTAINER_NAME=""
  return 0
}

stop_cache_monitor() {
  local monitor_status
  if [ -n "$MONITOR_PID" ]; then
    rm -f "$RUN_DIR/cache-monitor-active"
    if ! timeout --foreground --signal TERM --kill-after=1s 5s \
      tail --pid="$MONITOR_PID" -f /dev/null; then
      echo "Scanner cache measurement did not stop within five seconds." >&2
      MONITOR_UNRESOLVED=true
      return 1
    fi
    monitor_status=0
    wait "$MONITOR_PID" || monitor_status=$?
    if [ "$monitor_status" -ne 0 ]; then
      echo "Scanner cache measurement failed." >&2
      MONITOR_PID=""
      return 1
    fi
    MONITOR_PID=""
  fi
  return 0
}

cleanup_scanner_runtime() {
  local original_status=$? cleanup_status=0
  trap - EXIT HUP INT TERM
  set +e
  stop_cache_monitor || cleanup_status=1
  finish_owned_container || cleanup_status=1
  if [ -n "$ACTIVE_CONTAINER" ] || [ -n "$ACTIVE_CONTAINER_NAME" ] || \
     [ "$MONITOR_UNRESOLVED" = true ]; then
    echo "Owned scanner process remains unresolved; retaining its cache mount." >&2
    cleanup_status=1
  elif [ "$CACHE_MOUNTED" = true ]; then
    if [ "$(stat -Lc '%d:%i:%u:%g:%a:%F' "$WORK_ROOT")" != "$WORK_ROOT_ID" ] || \
       [ "$(stat -Lc '%d:%i:%u:%g:%a:%F' "$RUN_DIR")" != "$RUN_DIR_ID" ] || \
       [ "$(stat -Lc '%d:%i:%u:%g:%a:%F' "$CACHE_MOUNT")" != "$CACHE_MOUNT_ID" ]; then
      echo "Private scanner path identity changed; retaining the cache mount." >&2
      cleanup_status=1
    elif ! mountpoint -q "$CACHE_MOUNT"; then
      echo "Private scanner cache mount disappeared before cleanup." >&2
      cleanup_status=1
    else
      umount "$CACHE_MOUNT" || cleanup_status=1
      if mountpoint -q "$CACHE_MOUNT"; then
        echo "Private scanner cache remains mounted; refusing deletion." >&2
        cleanup_status=1
      else
        CACHE_MOUNTED=false
      fi
    fi
  fi
  if [ "$CACHE_MOUNTED" = false ]; then
    if [ "$(stat -Lc '%d:%i:%u:%g:%a:%F' "$WORK_ROOT")" != "$WORK_ROOT_ID" ] || \
       [ "$(stat -Lc '%d:%i:%u:%g:%a:%F' "$RUN_DIR")" != "$RUN_DIR_ID" ] || \
       [ -L "$RUN_DIR" ] || [ "$(canonical_path "$RUN_DIR")" != "$RUN_DIR" ] || \
       [ "$(dirname "$RUN_DIR")" != "$WORK_ROOT" ]; then
      echo "Scanner runtime path changed; refusing deletion." >&2
      cleanup_status=1
    else
      rm -rf "$RUN_DIR" || cleanup_status=1
    fi
  fi
  if [ "$original_status" -ne 0 ]; then
    exit "$original_status"
  fi
  exit "$cleanup_status"
}
trap cleanup_scanner_runtime EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM

mount --bind "$CACHE_MOUNT" "$CACHE_MOUNT"
CACHE_MOUNTED=true
mount -o remount,bind,rw,noexec,nosuid,nodev "$CACHE_MOUNT"
mounted_target=$(findmnt -rn -M "$CACHE_MOUNT" -o TARGET)
mounted_options=$(findmnt -rn -M "$CACHE_MOUNT" -o OPTIONS)
if [ "$mounted_target" != "$CACHE_MOUNT" ] || ! mountpoint -q "$CACHE_MOUNT"; then
  echo "Private scanner cache is not the exact requested mountpoint." >&2
  exit 1
fi
for required_option in rw noexec nosuid nodev; do
  if [[ ",$mounted_options," != *",$required_option,"* ]]; then
    echo "Private scanner cache is missing mount option: $required_option" >&2
    exit 1
  fi
done
mkdir "$CACHE_MOUNT/db"
chown "$SCANNER_UID:$SCANNER_GID" "$CACHE_MOUNT/db"
chmod 0700 "$CACHE_MOUNT/db"
if [ "$(stat -Lc '%u:%g:%a:%F' "$CACHE_MOUNT/db")" \
  != "$SCANNER_UID:$SCANNER_GID:700:directory" ]; then
  echo "Private scanner DB directory has the wrong ownership or mode." >&2
  exit 1
fi

RUN_ID=$(python3 - <<'PY'
import secrets
print(secrets.token_hex(16))
PY
)
CONTAINER_SEQUENCE=0

offline_container_args=(
  --network none --read-only --cap-drop ALL
  --security-opt no-new-privileges
  --user "$SCANNER_UID:$SCANNER_GID"
  --cpus 1
  --pids-limit 256
  --memory "$MEMORY_LIMIT"
  --memory-swap "$MEMORY_SWAP_LIMIT"
)
online_container_args=(
  --read-only --cap-drop ALL
  --security-opt no-new-privileges
  --user "$SCANNER_UID:$SCANNER_GID"
  --cpus 1
  --pids-limit 256
  --memory "$MEMORY_LIMIT"
  --memory-swap "$MEMORY_SWAP_LIMIT"
)

run_owned_container() {
  local wallclock=$1 output_path=$2 network_profile=$3
  shift 3
  local create_output create_status=0 run_status=0 state
  local -a container_args
  case "$network_profile" in
    offline) container_args=("${offline_container_args[@]}") ;;
    online) container_args=("${online_container_args[@]}") ;;
    *) echo "Invalid scanner network profile." >&2; return 1 ;;
  esac
  CONTAINER_SEQUENCE=$((CONTAINER_SEQUENCE + 1))
  ACTIVE_CONTAINER_NAME="dcompany-scanner-$RUN_ID-$CONTAINER_SEQUENCE"
  ACTIVE_CONTAINER=""
  create_output=$(timeout --foreground --signal TERM --kill-after=5s 30s \
    docker create --name "$ACTIVE_CONTAINER_NAME" \
    --label "cloud.dcompany.erp.scanner-invocation=$RUN_ID" \
    "${container_args[@]}" "$@") || create_status=$?
  if [[ "$create_output" =~ ^[0-9a-f]{64}$ ]]; then
    ACTIVE_CONTAINER=$create_output
  fi
  if [ "$create_status" -ne 0 ] || [ -z "$ACTIVE_CONTAINER" ]; then
    [ "$create_status" -ne 0 ] || create_status=1
    if ! finish_owned_container; then
      echo "Docker create outcome is ambiguous; retaining the private scanner runtime." >&2
    fi
    return "$create_status"
  fi
  if timeout --foreground --signal TERM --kill-after=30s "$wallclock" \
    docker start -a "$ACTIVE_CONTAINER" > "$output_path"; then
    run_status=0
  else
    run_status=$?
  fi
  state=$(timeout --foreground --signal TERM --kill-after=5s 15s \
    docker inspect --format '{{.State.OOMKilled}}:{{.State.ExitCode}}' \
    "$ACTIVE_CONTAINER") || run_status=1
  if ! finish_owned_container; then
    echo "Owned scanner container cleanup failed." >&2
    exit 1
  fi
  if [ "$run_status" -ne 0 ]; then
    return "$run_status"
  fi
  if [ "$state" != false:0 ]; then
    echo "Scanner container did not exit cleanly: $state" >&2
    return 1
  fi
}

PROBE_OUTPUT="$RUN_DIR/cache-mount-probe.txt"
probe_program='from pathlib import Path
p=Path("/scanner-cache/db")
line=next((v for v in Path("/proc/self/mountinfo").read_text().splitlines() if v.split()[4]=="/scanner-cache"), None)
assert line is not None
left,right=line.split(" - ",1)
options=set(left.split()[5].split(",")) | set(right.split()[2].split(","))
assert {"rw","noexec","nosuid","nodev"} <= options
assert p.stat().st_uid == 65532 and (p.stat().st_mode & 0o777) == 0o700
w=p/"write-probe"; w.write_bytes(b"verified"); w.unlink()
print("scanner_cache_mount_verified=true")'
probe_status=0
run_owned_container 60s "$PROBE_OUTPUT" offline \
  --mount "type=bind,src=$CACHE_MOUNT,dst=/scanner-cache" \
  --entrypoint /usr/local/bin/python \
  "$PROBE_IMAGE_ID" -c "$probe_program" || probe_status=$?
if [ "$probe_status" -ne 0 ]; then
  echo "Scanner cache mount inheritance probe failed." >&2
  exit "$probe_status"
fi
if [ "$(cat "$PROBE_OUTPUT")" != scanner_cache_mount_verified=true ]; then
  echo "Scanner cache mount inheritance probe returned invalid evidence." >&2
  exit 1
fi
printf 'scanner_cache_host_mount_options=%s\nscanner_cache_container_mount_verified=true\n' \
  "$mounted_options" >> "$METADATA"

cache_usage_bytes() {
  python3 - "$CACHE_MOUNT" <<'PY'
import os
import sys

total = 0
for root, _directories, files in os.walk(sys.argv[1]):
    for name in files:
        try:
            total += os.stat(os.path.join(root, name), follow_symlinks=False).st_size
        except FileNotFoundError:
            pass
print(total)
PY
}

sample_cache_peak() {
  local current peak
  current=$(cache_usage_bytes)
  peak=$(cat "$CACHE_PEAK_FILE")
  if [ "$current" -gt "$peak" ]; then
    printf '%s\n' "$current" > "$CACHE_PEAK_FILE"
  fi
}

touch "$RUN_DIR/cache-monitor-active"
(
  while [ -e "$RUN_DIR/cache-monitor-active" ]; do
    sample_cache_peak
    sleep 1
  done
  sample_cache_peak
) &
MONITOR_PID=$!

for index in "${!SERVICES[@]}"; do
  service=${SERVICES[$index]}
  config_image_id=${CONFIG_IMAGE_IDS[$index]}
  archive=${ARCHIVES[$index]}
  syft_report=${SYFT_REPORTS[$index]}
  grype_report=${GRYPE_REPORTS[$index]}
  scanner_status=0
  run_owned_container 900s "$syft_report" offline \
    --tmpfs "$TMPFS_SPEC" \
    -e "GOMEMLIMIT=$GOMEMLIMIT" \
    -e "GOMAXPROCS=$GOMAXPROCS" \
    -e SYFT_CHECK_FOR_APP_UPDATE=false \
    -e SYFT_CACHE_DIR=/tmp/syft-cache \
    --mount "type=bind,src=$archive,dst=/scan/image.tar,readonly" \
    "$SYFT_IMAGE" "/scan/image.tar" --from docker-archive --output syft-json \
    || scanner_status=$?
  if [ "$scanner_status" -ne 0 ]; then
    echo "Syft failed for $service." >&2
    exit "$scanner_status"
  fi
  if [ ! -s "$syft_report" ]; then
    echo "Syft produced no evidence for $service." >&2
    exit 1
  fi
  syft_validation=$(python3 - "$syft_report" "$service" "$config_image_id" <<'PY'
import json
import sys
from pathlib import Path

report_path = Path(sys.argv[1])
service = sys.argv[2]
expected_image_id = sys.argv[3]
try:
    report = json.loads(report_path.read_text(encoding="utf-8"))
except (OSError, UnicodeDecodeError, json.JSONDecodeError) as exc:
    raise SystemExit(f"Invalid Syft JSON report for {service}: {exc}") from exc

descriptor = report.get("descriptor")
source = report.get("source")
metadata = source.get("metadata") if isinstance(source, dict) else None
actual_image_id = metadata.get("imageID") if isinstance(metadata, dict) else None
if not isinstance(descriptor, dict) or descriptor.get("name") != "syft" or descriptor.get("version") != "1.42.3":
    raise SystemExit(f"Syft scanner identity mismatch for {service}")
if not isinstance(source, dict) or source.get("type") != "image":
    raise SystemExit(f"Syft source type mismatch for {service}")
if actual_image_id != expected_image_id:
    raise SystemExit(f"Syft source image ID mismatch for {service}")
if not isinstance(report.get("artifacts"), list):
    raise SystemExit(f"Syft report for {service} has no artifact catalogue")
print(f"{service}_syft_version=1.42.3")
print(f"{service}_syft_source_image_id={actual_image_id}")
PY
  )
  printf '%s\n' "$syft_validation" >> "$METADATA"
  chmod 0444 "$syft_report"

  scanner_status=0
  run_owned_container 1200s "$grype_report" online \
    --tmpfs "$TMPFS_SPEC" \
    -e "GOMEMLIMIT=$GOMEMLIMIT" \
    -e "GOMAXPROCS=$GOMAXPROCS" \
    -e GRYPE_CHECK_FOR_APP_UPDATE=false \
    -e GRYPE_DB_CACHE_DIR=/scanner-cache/db \
    --mount "type=bind,src=$CACHE_MOUNT,dst=/scanner-cache" \
    --mount "type=bind,src=$archive,dst=/scan/image.tar,readonly" \
    "$GRYPE_IMAGE" "docker-archive:/scan/image.tar" --fail-on high --output json \
    || scanner_status=$?
  if [ "$scanner_status" -ne 0 ]; then
    echo "Grype failed for $service." >&2
    exit "$scanner_status"
  fi
  if [ ! -s "$grype_report" ]; then
    echo "Grype produced no evidence for $service." >&2
    exit 1
  fi
  validation=$(python3 - "$grype_report" "$service" "$config_image_id" <<'PY'
import json
import sys
from pathlib import Path

report_path = Path(sys.argv[1])
service = sys.argv[2]
expected_image_id = sys.argv[3]
try:
    report = json.loads(report_path.read_text(encoding="utf-8"))
except (OSError, UnicodeDecodeError, json.JSONDecodeError) as exc:
    raise SystemExit(f"Invalid Grype JSON report for {service}: {exc}") from exc

descriptor = report.get("descriptor")
if not isinstance(descriptor, dict) or descriptor.get("name") != "grype" or descriptor.get("version") != "0.118.0":
    raise SystemExit(f"Grype scanner identity mismatch for {service}")
version = descriptor.get("version")
database = descriptor.get("db")
status = database.get("status") if isinstance(database, dict) else None
built = status.get("built") if isinstance(status, dict) else None
schema = status.get("schemaVersion") if isinstance(status, dict) else None
valid = status.get("valid") if isinstance(status, dict) else None
source = report.get("source")
target = source.get("target") if isinstance(source, dict) else None
actual_image_id = target.get("imageID") if isinstance(target, dict) else None
if not isinstance(source, dict) or source.get("type") != "image":
    raise SystemExit(f"Grype source type mismatch for {service}")
if not isinstance(built, str) or not built.strip():
    raise SystemExit(f"Grype report for {service} has no vulnerability DB build identity")
if not isinstance(schema, str) or not schema.strip():
    raise SystemExit(f"Grype report for {service} has no vulnerability DB schema identity")
if valid is not True:
    raise SystemExit(f"Grype vulnerability DB for {service} is not valid")
if actual_image_id != expected_image_id:
    raise SystemExit(f"Grype source image ID mismatch for {service}")
print(f"{service}_grype_version={version}")
print(f"{service}_grype_db_built={built}")
print(f"{service}_grype_db_schema={schema}")
print(f"{service}_grype_db_valid=true")
print(f"{service}_source_image_id={actual_image_id}")
PY
  )
  printf '%s\n' "$validation" >> "$METADATA"
  chmod 0444 "$grype_report"
  remaining_bytes=$(df -PB1 "$WORK_ROOT" | awk 'END {print $4}')
  if ! [[ "$remaining_bytes" =~ ^[0-9]+$ ]] || [ "$remaining_bytes" -lt "$MIN_REMAINING_BYTES" ]; then
    echo "Scanner consumed the required 1 GiB operational disk reserve." >&2
    exit 1
  fi
  printf '%s_disk_available_after_scan_bytes=%s\n' "$service" "$remaining_bytes" \
    >> "$METADATA"
done

stop_cache_monitor
printf 'scanner_cache_sampled_max_bytes=%s\nscanner_cache_final_bytes=%s\n' \
  "$(cat "$CACHE_PEAK_FILE")" "$(cache_usage_bytes)" >> "$METADATA"
