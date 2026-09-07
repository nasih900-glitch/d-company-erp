#!/usr/bin/env bash
# Run the Code 26 business acceptance lane against a disposable local backend.
#
# This script is deliberately explicit: it never defaults to a cloud device,
# never accepts a non-loopback database, never uses production credentials and
# never publishes or activates an Android release.

set -Eeuo pipefail

usage() {
  cat <<'EOF'
Usage: scripts/run_code26_physical_business_audit.sh --device emulator|firebase

Optional environment variables:
  CODE26_EVIDENCE_ROOT  Parent evidence directory.
  CODE26_PYTHON        Python from an isolated environment with backend deps.
  CODE26_ALEMBIC       Optional alembic executable (defaults to python -m).
  CODE26_DEVICE_MODEL   Firebase physical model (default: TB370FU).
  CODE26_DEVICE_API     Firebase API level (default: 35).
  CODE26_GCP_PROJECT    Firebase project (default: erp-15f1617a).

The firebase mode is synchronous and may consume paid Test Lab quota. It must
only be invoked after the Code 26 source-settled release gate has been given.
EOF
}

DEVICE=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --device)
      [[ $# -ge 2 ]] || { usage >&2; exit 64; }
      DEVICE="$2"
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      printf 'Unknown argument: %s\n' "$1" >&2
      usage >&2
      exit 64
      ;;
  esac
done
if [[ "$DEVICE" != "emulator" && "$DEVICE" != "firebase" ]]; then
  printf '%s\n' 'An explicit --device emulator|firebase is required.' >&2
  exit 64
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
BACKEND_DIR="$REPO_ROOT/backend"
ANDROID_DIR="$REPO_ROOT/android-native"
PLAN="$ANDROID_DIR/audit-driver/plans/code26-gaming-finance-physical.json"
PYTHON="${CODE26_PYTHON:-}"
if [[ -z "$PYTHON" ]]; then
  for candidate in "$BACKEND_DIR/.venv/bin/python" "$REPO_ROOT/.venv/bin/python"; do
    if [[ -x "$candidate" ]]; then
      PYTHON="$candidate"
      break
    fi
  done
fi
ALEMBIC="${CODE26_ALEMBIC:-}"
EVIDENCE_ROOT="${CODE26_EVIDENCE_ROOT:-${TMPDIR:-/tmp}/dcompany-code26-physical-evidence}"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
RUN_ID="code26-full-route-${STAMP}-$$"
EVIDENCE_DIR="$EVIDENCE_ROOT/$RUN_ID"
RUNTIME_DIR="$EVIDENCE_DIR/runtime"
ARTIFACT_DIR="$EVIDENCE_DIR/artifacts"

require_command() {
  command -v "$1" >/dev/null 2>&1 || {
    printf 'Required command is unavailable: %s\n' "$1" >&2
    exit 69
  }
}

find_android_build_tool() {
  local tool="$1"
  local sdk_root="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}"
  { find "$sdk_root/build-tools" -type f -name "$tool" 2>/dev/null || true; } |
    sort | tail -1
}

for command_name in git createdb dropdb pg_isready redis-server cloudflared curl jq openssl rg shasum; do
  require_command "$command_name"
done
[[ -n "$PYTHON" && -x "$PYTHON" ]] || {
  printf '%s\n' 'No project Python environment was found. Set CODE26_PYTHON explicitly.' >&2
  exit 69
}
if [[ -n "$ALEMBIC" && ! -x "$ALEMBIC" ]]; then
  printf 'Alembic unavailable: %s\n' "$ALEMBIC" >&2
  exit 69
fi

APKSIGNER="${CODE26_APKSIGNER:-$(find_android_build_tool apksigner)}"
AAPT="${CODE26_AAPT:-$(find_android_build_tool aapt)}"
[[ -x "$APKSIGNER" ]] || { printf 'Android apksigner unavailable: %s\n' "$APKSIGNER" >&2; exit 69; }
[[ -x "$AAPT" ]] || { printf 'Android aapt unavailable: %s\n' "$AAPT" >&2; exit 69; }
"$PYTHON" -c 'import alembic, fastapi, redis, sqlalchemy, uvicorn' >/dev/null || {
  printf 'Python environment does not contain all backend audit dependencies: %s\n' "$PYTHON" >&2
  exit 69
}
[[ -f "$PLAN" ]] || { printf 'Audit plan unavailable: %s\n' "$PLAN" >&2; exit 66; }
EVIDENCE_ROOT="$("$PYTHON" -c 'import pathlib,sys; print(pathlib.Path(sys.argv[1]).expanduser().resolve())' "$EVIDENCE_ROOT")"
case "$EVIDENCE_ROOT/" in
  "$REPO_ROOT"/*)
    printf '%s\n' 'Evidence root must be outside the candidate source tree.' >&2
    exit 65
    ;;
esac
EVIDENCE_DIR="$EVIDENCE_ROOT/$RUN_ID"
RUNTIME_DIR="$EVIDENCE_DIR/runtime"
ARTIFACT_DIR="$EVIDENCE_DIR/artifacts"

# Physical evidence may only name an immutable, clean commit. A dirty source
# tree can otherwise build bytes that are absent from source_commit and make a
# Code 25 HEAD look like a Code 26 result.
SOURCE_COMMIT="$(git -C "$REPO_ROOT" rev-parse --verify 'HEAD^{commit}')"
SOURCE_TREE="$(git -C "$REPO_ROOT" rev-parse --verify 'HEAD^{tree}')"
SOURCE_BRANCH="$(git -C "$REPO_ROOT" symbolic-ref --quiet --short HEAD || printf 'DETACHED')"
SOURCE_DIRTY="$(git -C "$REPO_ROOT" status --porcelain=v1 --untracked-files=all)"
if [[ -n "$SOURCE_DIRTY" ]]; then
  printf '%s\n' 'Refusing physical acceptance from dirty source. Commit the exact candidate first.' >&2
  printf '%s\n' "$SOURCE_DIRTY" >&2
  exit 65
fi
VERSION_CODE="$(sed -nE 's/^[[:space:]]*versionCode[[:space:]]*=[[:space:]]*([0-9]+).*/\1/p' "$ANDROID_DIR/app/build.gradle.kts" | head -1)"
VERSION_NAME="$(sed -nE 's/^[[:space:]]*versionName[[:space:]]*=[[:space:]]*"([^"]+)".*/\1/p' "$ANDROID_DIR/app/build.gradle.kts" | head -1)"
if [[ "$VERSION_CODE" != "26" || "$VERSION_NAME" != "3.1.15" ]]; then
  printf 'Refusing non-Code-26 source identity: versionCode=%s versionName=%s\n' \
    "$VERSION_CODE" "$VERSION_NAME" >&2
  exit 65
fi

mkdir -p "$RUNTIME_DIR" "$ARTIFACT_DIR"
jq -n \
  --arg commit "$SOURCE_COMMIT" \
  --arg tree "$SOURCE_TREE" \
  --arg branch "$SOURCE_BRANCH" \
  --arg version_name "$VERSION_NAME" \
  --argjson version_code "$VERSION_CODE" \
  --arg captured_at "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
  '{commit:$commit,tree:$tree,branch:$branch,clean:true,version_code:$version_code,
    version_name:$version_name,captured_at:$captured_at}' \
  > "$ARTIFACT_DIR/source-identity.json"
git -C "$REPO_ROOT" cat-file commit "$SOURCE_COMMIT" | shasum -a 256 \
  > "$ARTIFACT_DIR/source-commit-object-sha256.txt"
git -C "$REPO_ROOT" status --short --branch > "$RUNTIME_DIR/git-status-before.txt"
cp "$PLAN" "$ARTIFACT_DIR/audit-plan.json"
shasum -a 256 "$ARTIFACT_DIR/audit-plan.json" > "$ARTIFACT_DIR/audit-plan.sha256"
jq -n '{required_external:[
  "redmi_reboot_alarm_delivery",
  "redmi_lock_screen_alarm_delivery",
  "redmi_notification_denial_recovery",
  "redmi_oem_battery_optimisation",
  "signed_in_place_upgrade"
],reason:"A same-process Firebase instrumentation run cannot survive a real reboot, reproduce HyperOS OEM battery policy, prove actual lock-screen/permission-denial alarm delivery, or prove a production-signed upgrade."}' \
  > "$ARTIFACT_DIR/external-device-gates.json"

if [[ "$DEVICE" == "firebase" ]]; then
  require_command gcloud
  require_command gsutil
else
  require_command adb
fi

free_port() {
  "$PYTHON" - <<'PY'
import socket
with socket.socket() as sock:
    sock.bind(("127.0.0.1", 0))
    print(sock.getsockname()[1])
PY
}

run_alembic() {
  if [[ -n "$ALEMBIC" ]]; then
    "$ALEMBIC" "$@"
  else
    "$PYTHON" -m alembic "$@"
  fi
}

fetch_firebase_matrix_json() {
  local project="$1"
  local matrix_id="$2"
  local output="$3"
  local token project_uri matrix_uri response http_status request_rc detail
  [[ "$project" =~ ^[a-z][a-z0-9-]{4,28}[a-z0-9]$ ]] || {
    printf 'Invalid Google Cloud project id: %s\n' "$project" >&2
    return 64
  }
  [[ "$matrix_id" =~ ^matrix-[A-Za-z0-9_-]+$ ]] || {
    printf 'Invalid Firebase matrix id: %s\n' "$matrix_id" >&2
    return 64
  }
  response="$RUNTIME_DIR/firebase-matrix-api-response.json"
  project_uri="$(jq -rn --arg value "$project" '$value | @uri')"
  matrix_uri="$(jq -rn --arg value "$matrix_id" '$value | @uri')"
  if ! token="$(
    gcloud auth print-access-token --project="$project" \
      2> "$RUNTIME_DIR/firebase-auth.log"
  )"; then
    printf '%s\n' 'Could not obtain Google Cloud access token for Test Lab matrix lookup.' >&2
    return 70
  fi
  if [[ -z "$token" || ${#token} -gt 8192 || ! "$token" =~ ^[A-Za-z0-9._~+/-]+$ ]]; then
    unset token
    printf '%s\n' 'Google Cloud returned an invalid access token.' >&2
    return 70
  fi
  : > "$response"
  chmod 600 "$response"
  set +e
  http_status="$({
      printf 'header = "Authorization: Bearer %s"\n' "$token"
      printf 'header = "X-Goog-User-Project: %s"\n' "$project"
    } | curl --config - --silent --show-error --fail-with-body \
      --retry 4 --retry-connrefused --retry-delay 2 --retry-max-time 90 \
      --connect-timeout 15 --max-time 120 --request GET \
      --header 'Accept: application/json' --output "$response" \
      --write-out '%{http_code}' \
      "https://testing.googleapis.com/v1/projects/$project_uri/testMatrices/$matrix_uri")"
  request_rc=$?
  set -e
  unset token
  if [[ $request_rc -ne 0 ]]; then
    detail='no JSON error body'
    if [[ -s "$response" ]] && jq -e 'type == "object"' "$response" >/dev/null 2>&1; then
      detail="$(
        jq -r '(.error.status // "UNKNOWN") + ": " +
          (.error.message // "Testing API request failed")' "$response"
      )"
    fi
    printf 'Cloud Testing API matrix lookup failed (curl=%s HTTP=%s): %s\n' \
      "$request_rc" "${http_status:-000}" "$detail" >&2
    return 70
  fi
  if ! jq -e --arg project "$project" --arg matrix "$matrix_id" '
      type == "object" and .projectId == $project and .testMatrixId == $matrix and
      (.state == "FINISHED" or .state == "ERROR" or .state == "INVALID") and
      (.resultStorage.googleCloudStorage.gcsPath |
        type == "string" and startswith("gs://"))
    ' "$response" >/dev/null; then
    printf '%s\n' \
      'Cloud Testing API returned a mismatched, non-terminal, or incomplete matrix.' >&2
    return 70
  fi
  mv "$response" "$output"
}

write_source_recheck() {
  local final_commit final_tree final_dirty
  final_commit="$(git -C "$REPO_ROOT" rev-parse --verify 'HEAD^{commit}' 2>/dev/null)"
  final_tree="$(git -C "$REPO_ROOT" rev-parse --verify 'HEAD^{tree}' 2>/dev/null)"
  final_dirty="$(git -C "$REPO_ROOT" status --porcelain=v1 --untracked-files=all 2>/dev/null)"
  jq -n \
    --arg before_commit "$SOURCE_COMMIT" \
    --arg after_commit "$final_commit" \
    --arg before_tree "$SOURCE_TREE" \
    --arg after_tree "$final_tree" \
    --arg dirty "$final_dirty" \
    '{before_commit:$before_commit,after_commit:$after_commit,
      before_tree:$before_tree,after_tree:$after_tree,
      clean_after:($dirty == ""),unchanged:(
        $before_commit == $after_commit and $before_tree == $after_tree and $dirty == ""
      )}' > "$ARTIFACT_DIR/source-recheck.json"
  [[ "$final_commit" == "$SOURCE_COMMIT" && "$final_tree" == "$SOURCE_TREE" && \
     -z "$final_dirty" ]]
}

PLAN_STEPS="$(jq -er '.steps | length' "$PLAN")"
PLAN_STARTS="$(jq '[.steps[] | select(
  .action == "click" and (
    ((.text // "") | startswith("Start ·")) or
    (.name | endswith("start open-ended"))
  )
)] | length' "$PLAN")"
PLAN_PAYMENTS="$(jq '[.steps[] | select(
  .action == "click" and (
    ((.text // "") | startswith("CONFIRM ")) or
    ((.textRegex // "") | startswith("^CONFIRM "))
  )
)] | length' "$PLAN")"
PLAN_FRAME_WINDOWS="$(jq '[.steps[] | select(.action == "idleFrames")] | length' "$PLAN")"
PLAN_STABILITY_WINDOWS="$(jq '[.steps[] | select(
  .action == "idleFrames" or .action == "idleStability" or
  .action == "idleSemanticStability"
)] | length' "$PLAN")"
PLAN_ALARM_CONSTRAINTS="$(jq '[.steps[] | select(.action == "alarmConstraints")] | length' "$PLAN")"
PLAN_PAUSE_SEQUENCE="$(jq '[.steps[] | select(
  (.name == "Standard Single: open pause reason" and .action == "click" and .text == "Pause") or
  (.name == "Standard Single: enter physical pause reason" and .action == "fill" and
    .value == "Physical audit pause stability") or
  (.name == "Standard Single: pause with reason" and .action == "click" and
    .text == "Pause session") or
  (.name == "Measure paused session layout stability" and
    .action == "idleSemanticStability") or
  (.name == "Standard Single: resume after stable pause" and .action == "click" and
    .text == "Resume")
)] | length' "$PLAN")"
PLAN_SEMANTIC_STABILITY="$(jq '[.steps[] | select(
  .name == "Measure paused session layout stability" and
  .action == "idleSemanticStability" and .durationMs == 10000 and
  .exactStableSemantics == [{
    id:"ps5_station_1_paused_timer",attribute:"text",
    fullmatch:"[0-9]{2}:[0-9]{2}:[0-9]{2}",expectedCount:1,
    ancestor:{attribute:"content-desc",fullmatch:"PS5 Station 1\\. Paused\\. Standard · Single · ₹80\\.00 fixed total\\."}
  }]
)] | length' "$PLAN")"
PLAN_PACKAGE_STARTS="$(jq '[.steps[] | select(
  .action == "click" and ((.text // "") | startswith("Start ·")) and
  ((.packageCode // "") | contains("-session-"))
)] | length' "$PLAN")"
PLAN_EXTENSION_SUBMITS="$(jq '[.steps[] | select(
  .action == "click" and ((.text // "") | startswith("Add ·")) and
  ((.packageCode // "") | contains("-extension-"))
)] | length' "$PLAN")"
PLAN_BASE_CODES_COMPLETE="$(jq '(
  [.steps[] | select(
    .action == "click" and ((.text // "") | startswith("Start ·")) and
    ((.packageCode // "") | contains("-session-"))
  ) | .packageCode] | unique
) == [
  "premium-dual-session-60m",
  "premium-single-session-60m",
  "standard-dual-session-30m",
  "standard-dual-session-60m",
  "standard-simdrive-session-15m",
  "standard-simdrive-session-30m",
  "standard-simdrive-session-60m",
  "standard-single-session-30m",
  "standard-single-session-60m"
]' "$PLAN")"
PLAN_EXTENSION_CODES_COMPLETE="$(jq '(
  [.steps[] | select(
    .action == "click" and ((.text // "") | startswith("Add ·")) and
    ((.packageCode // "") | contains("-extension-"))
  ) | .packageCode] | unique
) == [
  "premium-dual-extension-30m",
  "premium-dual-extension-60m",
  "premium-single-extension-30m",
  "premium-single-extension-60m",
  "standard-dual-extension-30m",
  "standard-dual-extension-60m",
  "standard-single-extension-30m",
  "standard-single-extension-60m"
]' "$PLAN")"
if [[ "$PLAN_STEPS" -ne 411 || "$PLAN_STARTS" -ne 16 || "$PLAN_PAYMENTS" -ne 16 || \
      "$PLAN_FRAME_WINDOWS" -ne 4 || "$PLAN_STABILITY_WINDOWS" -ne 9 || \
      "$PLAN_ALARM_CONSTRAINTS" -ne 1 || "$PLAN_PAUSE_SEQUENCE" -ne 5 || \
      "$PLAN_SEMANTIC_STABILITY" -ne 1 || "$PLAN_PACKAGE_STARTS" -ne 13 || \
      "$PLAN_EXTENSION_SUBMITS" -ne 9 || "$PLAN_BASE_CODES_COMPLETE" != true || \
      "$PLAN_EXTENSION_CODES_COMPLETE" != true ]]; then
  printf 'Plan safety gate failed: steps=%s starts=%s payments=%s frame_windows=%s stability_windows=%s alarm_constraints=%s pause_sequence=%s semantic_stability=%s package_starts=%s extension_submits=%s base_codes_complete=%s extension_codes_complete=%s\n' \
    "$PLAN_STEPS" "$PLAN_STARTS" "$PLAN_PAYMENTS" "$PLAN_FRAME_WINDOWS" \
    "$PLAN_STABILITY_WINDOWS" "$PLAN_ALARM_CONSTRAINTS" "$PLAN_PAUSE_SEQUENCE" \
    "$PLAN_SEMANTIC_STABILITY" "$PLAN_PACKAGE_STARTS" "$PLAN_EXTENSION_SUBMITS" \
    "$PLAN_BASE_CODES_COMPLETE" "$PLAN_EXTENSION_CODES_COMPLETE" >&2
  exit 65
fi

PGHOST="127.0.0.1"
PGPORT="5432"
PGUSER="erp"
PGPASSWORD="erp"
export PGHOST PGPORT PGUSER PGPASSWORD
pg_isready -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" >/dev/null

DB_NAME="dcompany_physical_audit_${STAMP//[^0-9]/}_$$"
DB_NAME="${DB_NAME:0:62}"
REDIS_PORT="$(free_port)"
BACKEND_PORT="$(free_port)"
TEST_EMAIL="audit-${STAMP}-$$@physical-audit.test"
TEST_PASSWORD="$(openssl rand -hex 24)"
JWT_SECRET="$(openssl rand -hex 32)"
DATABASE_URL="postgresql+asyncpg://erp:erp@127.0.0.1:${PGPORT}/${DB_NAME}"
REDIS_URL="redis://127.0.0.1:${REDIS_PORT}/0"
SEED_MANIFEST="$ARTIFACT_DIR/fixture-seed.json"
VERIFY_MANIFEST="$ARTIFACT_DIR/fixture-verify.json"
FINANCE_RECONCILIATION="$ARTIFACT_DIR/financial-api-reconciliation.json"
CREDENTIAL_FILE="$RUNTIME_DIR/credentials.json"
BUSINESS_DATE_BEFORE="$(TZ=Asia/Kolkata date +%F)"
jq -n --arg email "$TEST_EMAIL" --arg password "$TEST_PASSWORD" \
  '{fixture:"main",users:[{email:$email,password:$password}]}' > "$CREDENTIAL_FILE"
chmod 600 "$CREDENTIAL_FILE"

REDIS_PID=""
BACKEND_PID=""
TUNNEL_PID=""
DB_CREATED=0
cleanup() {
  local status=$?
  set +e
  [[ -n "$TUNNEL_PID" ]] && kill "$TUNNEL_PID" >/dev/null 2>&1
  [[ -n "$BACKEND_PID" ]] && kill "$BACKEND_PID" >/dev/null 2>&1
  [[ -n "$REDIS_PID" ]] && kill "$REDIS_PID" >/dev/null 2>&1
  [[ -n "$TUNNEL_PID" ]] && wait "$TUNNEL_PID" >/dev/null 2>&1
  [[ -n "$BACKEND_PID" ]] && wait "$BACKEND_PID" >/dev/null 2>&1
  [[ -n "$REDIS_PID" ]] && wait "$REDIS_PID" >/dev/null 2>&1
  rm -f "$CREDENTIAL_FILE"
  if [[ "$DB_CREATED" -eq 1 ]]; then
    dropdb --force --if-exists "$DB_NAME" > "$RUNTIME_DIR/dropdb.log" 2>&1
  fi
  git -C "$REPO_ROOT" status --short --branch > "$RUNTIME_DIR/git-status-after.txt" 2>&1
  if ! write_source_recheck 2>/dev/null; then
    printf '%s\n' 'ERROR: source commit/tree changed or became dirty during acceptance.' >&2
    git -C "$REPO_ROOT" status --porcelain=v1 --untracked-files=all \
      > "$RUNTIME_DIR/source-drift.txt" 2>&1
    if [[ -f "$EVIDENCE_DIR/run-summary.json" ]]; then
      jq '.passed=false | .source_recheck_after_cleanup=false' \
        "$EVIDENCE_DIR/run-summary.json" > "$RUNTIME_DIR/run-summary-drift.json" 2>/dev/null &&
        mv "$RUNTIME_DIR/run-summary-drift.json" "$EVIDENCE_DIR/run-summary.json"
    fi
    status=1
  fi
  exit "$status"
}
trap cleanup EXIT INT TERM

createdb "$DB_NAME"
DB_CREATED=1
redis-server --bind 127.0.0.1 --port "$REDIS_PORT" --save '' --appendonly no \
  --dir "$RUNTIME_DIR" > "$RUNTIME_DIR/redis.log" 2>&1 &
REDIS_PID=$!
for _ in $(seq 1 60); do
  if "$PYTHON" -c \
    "import redis; assert redis.Redis(host='127.0.0.1', port=$REDIS_PORT).ping()" \
    >/dev/null 2>&1; then
    break
  fi
  sleep 0.25
done
kill -0 "$REDIS_PID" 2>/dev/null || { printf '%s\n' 'Redis failed to start.' >&2; exit 70; }

export ENV=test DATABASE_URL REDIS_URL JWT_SECRET
export LOG_FORMAT=console LOG_LEVEL=INFO EXPOSE_DOCS=false
export PHYSICAL_AUDIT_CONFIRMATION=I_UNDERSTAND_THIS_IS_A_DISPOSABLE_LOCAL_DATABASE
export PHYSICAL_AUDIT_EXPECTED_DATABASE="$DB_NAME"
export PHYSICAL_AUDIT_USER_EMAIL="$TEST_EMAIL"
export PHYSICAL_AUDIT_USER_PASSWORD="$TEST_PASSWORD"

(
  cd "$BACKEND_DIR"
  run_alembic upgrade head
) > "$RUNTIME_DIR/alembic.log" 2>&1
(
  cd "$BACKEND_DIR"
  PHYSICAL_AUDIT_MANIFEST_PATH="$SEED_MANIFEST" \
    "$PYTHON" -m scripts.physical_audit_fixture seed
) > "$RUNTIME_DIR/fixture-seed.log" 2>&1

(
  cd "$BACKEND_DIR"
  # Shared pause stays disabled by default and in production. Enable it only
  # for this disposable, loopback-database backend process so the physical
  # workflow can prove the real pause/resume contract end to end.
  exec env GAMING_PAUSE_ENABLED=true \
    "$PYTHON" -m uvicorn app.main:app --host 127.0.0.1 --port "$BACKEND_PORT"
) > "$RUNTIME_DIR/backend.log" 2>&1 &
BACKEND_PID=$!
for _ in $(seq 1 120); do
  if curl --fail --silent --show-error "http://127.0.0.1:${BACKEND_PORT}/readyz" \
    > "$RUNTIME_DIR/local-ready.json" 2>/dev/null; then
    break
  fi
  sleep 0.25
done
curl --fail --silent --show-error "http://127.0.0.1:${BACKEND_PORT}/readyz" \
  > "$RUNTIME_DIR/local-ready.json"

cloudflared tunnel --no-autoupdate --url "http://127.0.0.1:${BACKEND_PORT}" \
  > "$RUNTIME_DIR/cloudflared.log" 2>&1 &
TUNNEL_PID=$!
TUNNEL_URL=""
for _ in $(seq 1 120); do
  TUNNEL_URL="$(sed -nE 's#.*(https://[A-Za-z0-9-]+\.trycloudflare\.com).*#\1#p' \
    "$RUNTIME_DIR/cloudflared.log" | head -1)"
  [[ -n "$TUNNEL_URL" ]] && break
  kill -0 "$TUNNEL_PID" 2>/dev/null || break
  sleep 0.5
done
if [[ ! "$TUNNEL_URL" =~ ^https://[A-Za-z0-9-]+\.trycloudflare\.com$ ]]; then
  printf '%s\n' 'Temporary HTTPS tunnel did not become available.' >&2
  exit 70
fi
for _ in $(seq 1 120); do
  if curl --fail --silent --show-error "$TUNNEL_URL/readyz" \
    > "$RUNTIME_DIR/tunnel-ready.json" 2>/dev/null; then
    break
  fi
  sleep 0.5
done
curl --fail --silent --show-error "$TUNNEL_URL/readyz" \
  > "$RUNTIME_DIR/tunnel-ready.json"

(
  cd "$ANDROID_DIR"
  ./gradlew --no-daemon \
    :app:assemblePhysicalAudit \
    :audit-driver:assembleDebug \
    :audit-driver:assembleDebugAndroidTest \
    -Pdcompany.physicalAuditApiBaseUrl="$TUNNEL_URL/api/v1/"
) > "$RUNTIME_DIR/gradle-build.log" 2>&1

ERP_APK="$ANDROID_DIR/app/build/outputs/apk/physicalAudit/app-physicalAudit.apk"
DRIVER_APK="$ANDROID_DIR/audit-driver/build/outputs/apk/debug/audit-driver-debug.apk"
DRIVER_TEST_APK="$ANDROID_DIR/audit-driver/build/outputs/apk/androidTest/debug/audit-driver-debug-androidTest.apk"
for apk in "$ERP_APK" "$DRIVER_APK" "$DRIVER_TEST_APK"; do
  [[ -s "$apk" ]] || { printf 'Expected APK is missing: %s\n' "$apk" >&2; exit 66; }
done

APK_IDENTITIES_NDJSON="$RUNTIME_DIR/apk-identities.ndjson"
: > "$APK_IDENTITIES_NDJSON"
record_apk_identity() {
  local apk="$1"
  local expected_package="${2:-}"
  local expected_version_code="${3:-}"
  local expected_version_name="${4:-}"
  local name sha copied_sha package_name version_code version_name cert_sha
  name="$(basename "$apk")"
  sha="$(shasum -a 256 "$apk" | awk '{print $1}')"
  "$APKSIGNER" verify --verbose --print-certs "$apk" \
    > "$RUNTIME_DIR/apksigner-$name.txt"
  "$AAPT" dump badging "$apk" > "$RUNTIME_DIR/aapt-$name.txt"
  cert_sha="$(sed -nE 's/^Signer #[0-9]+ certificate SHA-256 digest: (.*)$/\1/p' \
    "$RUNTIME_DIR/apksigner-$name.txt" | head -1)"
  package_name="$(sed -nE "s/^package: name='([^']+)'.*/\\1/p" \
    "$RUNTIME_DIR/aapt-$name.txt" | head -1)"
  version_code="$(sed -nE "s/^package: .*versionCode='([^']+)'.*/\\1/p" \
    "$RUNTIME_DIR/aapt-$name.txt" | head -1)"
  version_name="$(sed -nE "s/^package: .*versionName='([^']+)'.*/\\1/p" \
    "$RUNTIME_DIR/aapt-$name.txt" | head -1)"
  # Android's instrumentation APK has an application id and signer but no
  # independent versionCode/versionName. Require version identity only for the
  # ERP-under-test, whose expected values are supplied below.
  [[ -n "$cert_sha" && -n "$package_name" ]] || {
    printf 'Could not resolve APK signing/package identity: %s\n' "$apk" >&2
    exit 65
  }
  if [[ -n "$expected_package" ]] && \
     [[ "$package_name" != "$expected_package" || "$version_code" != "$expected_version_code" || \
        "$version_name" != "$expected_version_name" ]]; then
    printf 'Unexpected APK identity for %s: %s %s %s\n' \
      "$name" "$package_name" "$version_code" "$version_name" >&2
    exit 65
  fi
  cp "$apk" "$ARTIFACT_DIR/$name"
  copied_sha="$(shasum -a 256 "$ARTIFACT_DIR/$name" | awk '{print $1}')"
  [[ "$sha" == "$copied_sha" ]] || { printf 'Copied APK hash mismatch: %s\n' "$name" >&2; exit 65; }
  printf '%s  %s\n' "$sha" "$name" >> "$ARTIFACT_DIR/apk-sha256.txt"
  jq -n \
    --arg file "$name" --arg sha256 "$sha" --arg signer_sha256 "$cert_sha" \
    --arg package_name "$package_name" --arg version_code "$version_code" \
    --arg version_name "$version_name" --arg source_commit "$SOURCE_COMMIT" \
    '{file:$file,sha256:$sha256,signer_certificate_sha256:$signer_sha256,
      package_name:$package_name,version_code:$version_code,version_name:$version_name,
      source_commit:$source_commit,signature_verified:true,copied_hash_verified:true}' \
    >> "$APK_IDENTITIES_NDJSON"
}
record_apk_identity "$ERP_APK" cloud.dcompany.erp.physicalaudit 26 3.1.15-physical-audit
record_apk_identity "$DRIVER_APK"
record_apk_identity "$DRIVER_TEST_APK"
jq -s --arg commit "$SOURCE_COMMIT" --arg tree "$SOURCE_TREE" \
  '{source_commit:$commit,source_tree:$tree,apks:.}' \
  "$APK_IDENTITIES_NDJSON" > "$ARTIFACT_DIR/apk-identities.json"

TEST_RC=0
MATRIX_ID=""
if [[ "$DEVICE" == "emulator" ]]; then
  adb get-state >/dev/null
  adb logcat -c
  adb uninstall cloud.dcompany.erp.physicalaudit >/dev/null 2>&1 || true
  adb uninstall cloud.dcompany.erp.auditdriver >/dev/null 2>&1 || true
  adb install "$ERP_APK" > "$RUNTIME_DIR/adb-install-erp.log"
  adb install "$DRIVER_APK" > "$RUNTIME_DIR/adb-install-driver.log"
  adb install "$DRIVER_TEST_APK" > "$RUNTIME_DIR/adb-install-test.log"
  adb push "$PLAN" /sdcard/Download/dcompany-code26-plan.json \
    > "$RUNTIME_DIR/adb-push-plan.log"
  adb push "$CREDENTIAL_FILE" /sdcard/Download/dcompany-code26-credentials.json \
    > "$RUNTIME_DIR/adb-push-credentials.log"
  set +e
  adb shell am instrument -w -r \
    -e auditRunId "$RUN_ID" \
    -e auditPlan /sdcard/Download/dcompany-code26-plan.json \
    -e auditCredentials /sdcard/Download/dcompany-code26-credentials.json \
    -e class cloud.dcompany.erp.auditdriver.BusinessWorkflowDeviceTest#completeBusinessWorkflow \
    cloud.dcompany.erp.auditdriver.test/androidx.test.runner.AndroidJUnitRunner \
    2>&1 | tee "$RUNTIME_DIR/instrumentation.txt"
  TEST_RC=${PIPESTATUS[0]}
  set -e
  adb logcat -d -v threadtime > "$ARTIFACT_DIR/logcat.txt"
  mkdir -p "$ARTIFACT_DIR/device-pull"
  adb pull "/sdcard/Android/data/cloud.dcompany.erp.auditdriver/files/business-audit/$RUN_ID" \
    "$ARTIFACT_DIR/device-pull/" > "$RUNTIME_DIR/adb-pull.log" 2>&1 || true
else
  PROJECT="${CODE26_GCP_PROJECT:-erp-15f1617a}"
  MODEL="${CODE26_DEVICE_MODEL:-TB370FU}"
  API="${CODE26_DEVICE_API:-35}"
  RESULTS_DIR="code26-business/$RUN_ID"
  gcloud firebase test android models describe "$MODEL" --project "$PROJECT" --format=json \
    > "$ARTIFACT_DIR/firebase-device.json"
  if [[ "$(jq -r '.form' "$ARTIFACT_DIR/firebase-device.json")" != "PHYSICAL" ]] || \
     ! jq -e --arg api "$API" '.supportedVersionIds | index($api) != null' \
       "$ARTIFACT_DIR/firebase-device.json" >/dev/null; then
    printf 'Requested Firebase target is not a supported physical device: %s API %s\n' \
      "$MODEL" "$API" >&2
    exit 65
  fi
  set +e
  gcloud firebase test android run \
    --project "$PROJECT" \
    --type instrumentation \
    --app "$DRIVER_APK" \
    --test "$DRIVER_TEST_APK" \
    --additional-apks "$ERP_APK" \
    --device "model=$MODEL,version=$API,locale=en,orientation=landscape" \
    --test-targets \
      'class cloud.dcompany.erp.auditdriver.BusinessWorkflowDeviceTest#completeBusinessWorkflow' \
    --environment-variables \
      "auditRunId=$RUN_ID,auditPlan=/sdcard/Download/dcompany-code26-plan.json,auditCredentials=/sdcard/Download/dcompany-code26-credentials.json" \
    --other-files \
      "/sdcard/Download/dcompany-code26-plan.json=$PLAN,/sdcard/Download/dcompany-code26-credentials.json=$CREDENTIAL_FILE" \
    --directories-to-pull \
      /sdcard/Android/data/cloud.dcompany.erp.auditdriver/files/business-audit \
    --performance-metrics \
    --record-video \
    --timeout 45m \
    --results-dir "$RESULTS_DIR" \
    --results-history-name 'Code26 physical business acceptance' \
    2>&1 | tee "$RUNTIME_DIR/firebase-run.txt"
  TEST_RC=${PIPESTATUS[0]}
  set -e
  MATRIX_ID="$(sed -nE 's/.*(matrix-[A-Za-z0-9_-]+).*/\1/p' \
    "$RUNTIME_DIR/firebase-run.txt" | tail -1)"
  if [[ -z "$MATRIX_ID" ]]; then
    printf '%s\n' 'Firebase did not return a matrix id.' >&2
    exit 70
  fi
  printf '%s\n' "$MATRIX_ID" > "$ARTIFACT_DIR/firebase-matrix-id.txt"
  fetch_firebase_matrix_json \
    "$PROJECT" "$MATRIX_ID" "$ARTIFACT_DIR/firebase-matrix.json"
  GCS_PATH="$(
    jq -er '.resultStorage.googleCloudStorage.gcsPath' \
      "$ARTIFACT_DIR/firebase-matrix.json"
  )"
  GCS_WITHOUT_SCHEME="${GCS_PATH#gs://}"
  GCS_OBJECT_PATH="${GCS_WITHOUT_SCHEME#*/}"
  GCS_OBJECT_PATH="${GCS_OBJECT_PATH%/}"
  if [[ "$GCS_WITHOUT_SCHEME" == "$GCS_OBJECT_PATH" || \
        "$GCS_OBJECT_PATH" != "$RESULTS_DIR" ]]; then
    printf 'Matrix result path does not match this run: %s\n' "$GCS_PATH" >&2
    exit 65
  fi
  mkdir -p "$ARTIFACT_DIR/firebase-results"
  GCS_SOURCE="${GCS_PATH%/}"
  if ! gsutil -m cp -r "$GCS_SOURCE" "$ARTIFACT_DIR/firebase-results/" \
      > "$RUNTIME_DIR/firebase-download.log" 2>&1; then
    printf '%s\n' \
      'Firebase evidence download failed; physical acceptance cannot continue.' >&2
    exit 70
  fi
  find "$ARTIFACT_DIR/firebase-results" -type f -print | LC_ALL=C sort \
    > "$ARTIFACT_DIR/firebase-results-files.txt"
  [[ -s "$ARTIFACT_DIR/firebase-results-files.txt" ]] || {
    printf '%s\n' 'Firebase returned no downloadable evidence files.' >&2
    exit 70
  }
  : > "$ARTIFACT_DIR/firebase-result-apk-sha256.txt"
  for expected_apk in "$ERP_APK" "$DRIVER_APK" "$DRIVER_TEST_APK"; do
    expected_name="$(basename "$expected_apk")"
    match_count="$(
      find "$ARTIFACT_DIR/firebase-results" -type f -name "$expected_name" -print |
        wc -l | tr -d '[:space:]'
    )"
    [[ "$match_count" == "1" ]] || {
      printf 'Expected exactly one downloaded %s, found %s.\n' \
        "$expected_name" "$match_count" >&2
      exit 70
    }
    downloaded_apk="$(
      find "$ARTIFACT_DIR/firebase-results" -type f -name "$expected_name" -print |
        head -1
    )"
    immutable_apk="$ARTIFACT_DIR/$expected_name"
    [[ -s "$immutable_apk" ]] || {
      printf 'Immutable submitted APK copy is missing: %s\n' "$expected_name" >&2
      exit 70
    }
    expected_sha="$(shasum -a 256 "$immutable_apk" | awk '{print $1}')"
    recorded_sha="$(
      jq -er --arg file "$expected_name" \
        '.apks[] | select(.file == $file) | .sha256' \
        "$ARTIFACT_DIR/apk-identities.json"
    )"
    [[ "$expected_sha" == "$recorded_sha" ]] || {
      printf 'Immutable APK copy no longer matches recorded identity: %s\n' \
        "$expected_name" >&2
      exit 70
    }
    downloaded_sha="$(shasum -a 256 "$downloaded_apk" | awk '{print $1}')"
    [[ "$expected_sha" == "$downloaded_sha" ]] || {
      printf 'Downloaded Firebase APK hash mismatch: %s\n' "$expected_name" >&2
      exit 70
    }
    printf '%s  %s\n' "$downloaded_sha" "$expected_name" \
      >> "$ARTIFACT_DIR/firebase-result-apk-sha256.txt"
  done
fi

set +e
(
  cd "$BACKEND_DIR"
  PHYSICAL_AUDIT_MANIFEST_PATH="$VERIFY_MANIFEST" \
    "$PYTHON" -m scripts.physical_audit_fixture verify
) > "$RUNTIME_DIR/fixture-verify.log" 2>&1
VERIFY_RC=$?
set -e

# Reconcile the exact figures rendered by native Finance and Reports against
# the independently queried disposable database totals. A visually present
# heading is not financial acceptance evidence.
FINANCE_RC=1
jq -n '{passed:false,failures:["fixture verification did not complete"]}' \
  > "$FINANCE_RECONCILIATION"
if [[ "$VERIFY_RC" -eq 0 ]]; then
  set +e
  (
    set -Eeuo pipefail
    business_date_after="$(TZ=Asia/Kolkata date +%F)"
    [[ "$business_date_after" == "$BUSINESS_DATE_BEFORE" ]] || {
      printf '%s\n' 'The physical run crossed the shop business-date boundary.' >&2
      exit 1
    }
    login_response="$(
      jq -nc --arg email "$TEST_EMAIL" --arg password "$TEST_PASSWORD" \
        '{email:$email,password:$password}' |
        curl --fail --silent --show-error \
          -H 'Content-Type: application/json' \
          --data-binary @- \
          "http://127.0.0.1:${BACKEND_PORT}/api/v1/auth/login"
    )"
    access_token="$(jq -er '.access_token' <<<"$login_response")"
    auth_header="Authorization: Bearer $access_token"
    curl --fail --silent --show-error -H "$auth_header" \
      "http://127.0.0.1:${BACKEND_PORT}/api/v1/finance/pnl" \
      > "$ARTIFACT_DIR/finance-pnl-response.json"
    curl --fail --silent --show-error -H "$auth_header" \
      "http://127.0.0.1:${BACKEND_PORT}/api/v1/reports/daily?on_date=$BUSINESS_DATE_BEFORE" \
      > "$ARTIFACT_DIR/reports-daily-response.json"
    curl --fail --silent --show-error -H "$auth_header" \
      "http://127.0.0.1:${BACKEND_PORT}/api/v1/reports/monthly?yyyy_mm=${BUSINESS_DATE_BEFORE:0:7}" \
      > "$ARTIFACT_DIR/reports-monthly-response.json"
    jq -n \
      --slurpfile fixture "$VERIFY_MANIFEST" \
      --slurpfile finance "$ARTIFACT_DIR/finance-pnl-response.json" \
      --slurpfile daily "$ARTIFACT_DIR/reports-daily-response.json" \
      --slurpfile monthly "$ARTIFACT_DIR/reports-monthly-response.json" \
      --arg business_date "$BUSINESS_DATE_BEFORE" '
      ($fixture[0]) as $f |
      ($finance[0]) as $p |
      ($daily[0]) as $d |
      ($monthly[0]) as $m |
      ($f.gross_order_minor) as $revenue |
      ($f.inventory.cogs_minor) as $cogs |
      [
        (if $p.accounting_basis == "operational_receipt" then empty else "Finance basis" end),
        (if $p.revenue_minor == $revenue then empty else "Finance revenue" end),
        (if $p.cogs_minor == $cogs then empty else "Finance COGS" end),
        (if $p.gross_profit_minor == ($revenue - $cogs) then empty else "Finance gross profit" end),
        (if $p.expenses_minor == 0 then empty else "Finance expenses" end),
        (if $p.depreciation_minor == 0 then empty else "Finance depreciation" end),
        (if $p.net_profit_minor == ($revenue - $cogs) then empty else "Finance net profit" end),
        (if $d.branch_id == $f.branch_id then empty else "Daily report branch" end),
        (if $d.orders_count == 16 then empty else "Daily order count" end),
        (if $d.gross_revenue_minor == $revenue and $d.net_revenue_minor == $revenue
          then empty else "Daily revenue" end),
        (if $d.payments_received.cash_minor == $f.cash_collected_minor
          then empty else "Daily cash" end),
        (if $d.payments_received.upi_minor == $f.upi_collected_minor
          then empty else "Daily UPI" end),
        (if $d.payments_received.total_minor == $revenue and
            $d.net_payments_received_minor == $revenue
          then empty else "Daily payment total" end),
        (if $d.revenue.discounts_and_points_redeemed_minor == $f.manual_discount_minor
          then empty else "Daily discount" end),
        (if $d.cogs_minor == $cogs then empty else "Daily COGS" end),
        (if $d.gross_profit_minor == ($revenue - $cogs) and
            $d.net_profit_minor == ($revenue - $cogs)
          then empty else "Daily profit" end),
        (if $m.branch_id == $f.branch_id then empty else "Monthly report branch" end),
        (if $m.orders_count == 16 then empty else "Monthly order count" end),
        (if $m.gross_revenue_minor == $revenue and $m.net_revenue_minor == $revenue
          then empty else "Monthly revenue" end),
        (if $m.payments_received.cash_minor == $f.cash_collected_minor
          then empty else "Monthly cash" end),
        (if $m.payments_received.upi_minor == $f.upi_collected_minor
          then empty else "Monthly UPI" end),
        (if $m.payments_received.total_minor == $revenue and
            $m.net_payments_received_minor == $revenue
          then empty else "Monthly payment total" end),
        (if $m.revenue.discounts_and_points_redeemed_minor == $f.manual_discount_minor
          then empty else "Monthly discount" end),
        (if $m.cogs_minor == $cogs then empty else "Monthly COGS" end),
        (if $m.gross_profit_minor == ($revenue - $cogs) and
            $m.net_profit_minor == ($revenue - $cogs)
          then empty else "Monthly profit" end)
      ] as $failures |
      {
        business_date:$business_date,
        expected:{revenue_minor:$revenue,cogs_minor:$cogs,
          cash_minor:$f.cash_collected_minor,upi_minor:$f.upi_collected_minor,
          discount_minor:$f.manual_discount_minor,orders:16},
        observed:{finance:$p,daily:$d,monthly:$m},
        failures:$failures,
        passed:($failures | length == 0)
      }' > "$FINANCE_RECONCILIATION"
    jq -e '.passed == true' "$FINANCE_RECONCILIATION" >/dev/null
  ) > "$RUNTIME_DIR/financial-api-reconciliation.log" 2>&1
  FINANCE_RC=$?
  set -e
fi

SOURCE_RECHECK_RC=0
if ! write_source_recheck; then
  SOURCE_RECHECK_RC=1
fi

if rg -n -i \
  'FATAL EXCEPTION.*cloud\.dcompany\.erp|ANR in cloud\.dcompany\.erp|cloud\.dcompany\.erp[^ ]* has died' \
  "$ARTIFACT_DIR" "$RUNTIME_DIR" > "$ARTIFACT_DIR/runtime-failure-scan.txt"; then
  RUNTIME_SCAN_CLEAN=false
else
  RUNTIME_SCAN_CLEAN=true
  : > "$ARTIFACT_DIR/runtime-failure-scan.txt"
fi

set +e
"$PYTHON" "$SCRIPT_DIR/analyze_code26_physical_evidence.py" \
  "$EVIDENCE_DIR" \
  --expected-steps "$PLAN_STEPS" \
  --expected-frame-windows "$PLAN_FRAME_WINDOWS" \
  --lane "$DEVICE" \
  --output "$ARTIFACT_DIR/evidence-analysis.json" \
  > "$RUNTIME_DIR/evidence-analysis.log" 2>&1
ANALYSIS_RC=$?
set -e

FIXTURE_PASSED=false
if [[ "$VERIFY_RC" -eq 0 ]] && \
   jq -e '.passed == true and (.failures | length == 0)' "$VERIFY_MANIFEST" >/dev/null 2>&1; then
  FIXTURE_PASSED=true
fi

jq -n \
  --arg run_id "$RUN_ID" \
  --arg device "$DEVICE" \
  --arg matrix_id "$MATRIX_ID" \
  --arg commit "$SOURCE_COMMIT" \
  --arg tree "$SOURCE_TREE" \
  --arg version_name "$VERSION_NAME" \
  --argjson version_code "$VERSION_CODE" \
  --arg tunnel_host "${TUNNEL_URL#https://}" \
  --argjson test_exit_code "$TEST_RC" \
  --argjson verify_exit_code "$VERIFY_RC" \
  --argjson finance_reconciliation_exit_code "$FINANCE_RC" \
  --argjson source_recheck_exit_code "$SOURCE_RECHECK_RC" \
  --argjson analysis_exit_code "$ANALYSIS_RC" \
  --argjson fixture_verified "$FIXTURE_PASSED" \
  --argjson runtime_scan_clean "$RUNTIME_SCAN_CLEAN" \
  --argjson plan_steps "$PLAN_STEPS" \
  --argjson planned_sessions "$PLAN_STARTS" \
  --argjson planned_payments "$PLAN_PAYMENTS" \
  --argjson planned_frame_windows "$PLAN_FRAME_WINDOWS" \
  --argjson planned_stability_windows "$PLAN_STABILITY_WINDOWS" \
  '{
    run_id:$run_id,
    device_lane:$device,
    matrix_id:(if ($matrix_id | length) > 0 then $matrix_id else null end),
    source_commit:$commit,
    source_tree:$tree,
    version_code:$version_code,
    version_name:$version_name,
    source_was_clean:true,
    disposable_https_host:$tunnel_host,
    plan_steps:$plan_steps,
    planned_sessions:$planned_sessions,
    planned_payments:$planned_payments,
    planned_frame_windows:$planned_frame_windows,
    planned_layout_stability_windows:$planned_stability_windows,
    device_test_exit_code:$test_exit_code,
    fixture_verify_exit_code:$verify_exit_code,
    finance_reconciliation_exit_code:$finance_reconciliation_exit_code,
    source_recheck_exit_code:$source_recheck_exit_code,
    evidence_analysis_exit_code:$analysis_exit_code,
    runtime_scan_clean:$runtime_scan_clean,
    production_credentials_used:false,
    production_data_mutated:false,
    release_activated:false,
    release_acceptance_complete:false,
    external_target_device_gates:[
      "redmi_reboot_alarm_delivery",
      "redmi_lock_screen_alarm_delivery",
      "redmi_notification_denial_recovery",
      "redmi_oem_battery_optimisation",
      "signed_in_place_upgrade"
    ],
    fixture_verified:$fixture_verified,
    passed:(
      $test_exit_code == 0 and
      $verify_exit_code == 0 and
      $finance_reconciliation_exit_code == 0 and
      $source_recheck_exit_code == 0 and
      $analysis_exit_code == 0 and
      $runtime_scan_clean and
      $fixture_verified
    )
  }' > "$EVIDENCE_DIR/run-summary.json"

if [[ "$TEST_RC" -ne 0 || "$VERIFY_RC" -ne 0 || "$FINANCE_RC" -ne 0 || \
      "$SOURCE_RECHECK_RC" -ne 0 || \
      "$ANALYSIS_RC" -ne 0 || \
      "$RUNTIME_SCAN_CLEAN" != true || "$FIXTURE_PASSED" != true ]]; then
  printf 'FAIL: %s (device=%s fixture=%s finance=%s analysis=%s runtime_scan=%s)\n' \
    "$RUN_ID" "$TEST_RC" "$VERIFY_RC" "$FINANCE_RC" "$ANALYSIS_RC" \
    "$RUNTIME_SCAN_CLEAN" >&2
  printf 'Evidence: %s\n' "$EVIDENCE_DIR" >&2
  exit 1
fi

printf 'PASS: %s\nEvidence: %s\n' "$RUN_ID" "$EVIDENCE_DIR"
