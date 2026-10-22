#!/bin/bash
# Start the exact built production images in an isolated Compose project and
# prove migrations, readiness, health and immutable release identity.

set -euo pipefail

TAG_SUFFIX="${1:-}"
APP_VERSION="${2:-}"
APP_REVISION="${3:-}"
if ! [[ "$TAG_SUFFIX" =~ ^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$ ]] || \
   ! [[ "$APP_VERSION" =~ ^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$ ]] || \
   ! [[ "$APP_REVISION" =~ ^[0-9a-f]{40}$ ]]; then
  echo "Usage: $0 BUILT_TAG_SUFFIX X.Y.Z FULL_GIT_SHA" >&2
  exit 2
fi

cd "$(dirname "$0")/../.."
ROOT=$(pwd)
PROJECT_NAME="code25-runtime-${GITHUB_RUN_ID:-$$}-${GITHUB_RUN_ATTEMPT:-0}"
PROJECT_NAME=$(printf '%s' "$PROJECT_NAME" | tr '[:upper:]' '[:lower:]')
REDIS_REF="d-company-erp-redis:${APP_REVISION}"
CANDIDATE_ENV=$(mktemp)
ROUTED_READY=$(mktemp)
DIAGNOSTIC_LOG=$(mktemp)
MIGRATION_OVERRIDE=$(mktemp)
MIGRATION_BASE_CONFIG=$(mktemp)
MIGRATION_PINNED_CONFIG=$(mktemp)
rm -f "$CANDIDATE_ENV"

compose=(
  docker compose -p "$PROJECT_NAME" --project-directory "$ROOT"
  -f "$ROOT/docker-compose.prod.yml" --env-file "$CANDIDATE_ENV"
)
emit_failure_diagnostics() {
  local failure_code=$1

  echo "Runtime image verification failed with exit code $failure_code." >&2
  echo "Compose service state:" >&2
  "${compose[@]}" ps --all >&2 || true

  "${compose[@]}" logs --no-color --timestamps --tail 500 \
    postgres redis backend frontend caddy >"$DIAGNOSTIC_LOG" 2>&1 || true
  if ! python3 - "$CANDIDATE_ENV" "$DIAGNOSTIC_LOG" <<'PY'
import re
import sys
from pathlib import Path

env_path = Path(sys.argv[1])
log_path = Path(sys.argv[2])
logs = log_path.read_text(encoding="utf-8", errors="replace")

if env_path.is_file():
    for raw_line in env_path.read_text(encoding="utf-8", errors="replace").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        key = key.strip().upper()
        value = value.strip()
        if len(value) >= 2 and value[0] == value[-1] and value[0] in {"'", '"'}:
            value = value[1:-1]
        sensitive = (
            key in {"DATABASE_URL", "REDIS_URL", "S3_ACCESS_KEY", "SMTP_USER", "B2_KEY_ID"}
            or any(marker in key for marker in ("PASSWORD", "SECRET", "TOKEN", "PRIVATE_KEY"))
            or key.endswith(("_ACCESS_KEY", "_APPLICATION_KEY"))
        )
        if sensitive and value:
            logs = logs.replace(value, "[REDACTED]")

# Also mask credentials embedded in a DSN assembled or normalised at runtime.
dsn_credentials = re.compile(
    r"(?i)((?:postgres(?:ql)?(?:\+[a-z0-9_]+)?|rediss?)://[^\s:/@]+:)[^\s@]+(@)"
)
logs = dsn_credentials.sub(r"\1[REDACTED]\2", logs)

sys.stderr.write("Service logs (secret values redacted):\n")
sys.stderr.write(logs)
if logs and not logs.endswith("\n"):
    sys.stderr.write("\n")
PY
  then
    echo "Unable to redact service logs safely; raw logs were withheld." >&2
  fi
}
cleanup() {
  failure_code=$?
  trap - EXIT
  if [ "$failure_code" -ne 0 ]; then
    emit_failure_diagnostics "$failure_code"
  fi
  "${compose[@]}" down --volumes --remove-orphans >/dev/null 2>&1 || true
  rm -f "$CANDIDATE_ENV" "$ROUTED_READY" "$DIAGNOSTIC_LOG" \
    "$MIGRATION_OVERRIDE" "$MIGRATION_BASE_CONFIG" \
    "$MIGRATION_PINNED_CONFIG"
  exit "$failure_code"
}
trap cleanup EXIT

for service in caddy postgres redis backend frontend; do
  source_ref="erp-${service}:${TAG_SUFFIX}"
  target_ref="d-company-erp-${service}:${APP_REVISION}"
  source_id=$(docker image inspect --format '{{.Id}}' "$source_ref")
  docker image tag "$source_id" "$target_ref"
  test "$(docker image inspect --format '{{.Id}}' "$target_ref")" = "$source_id"
done
REDIS_EXPECTED_ID=$(docker image inspect --format '{{.Id}}' "$REDIS_REF")
if ! [[ "$REDIS_EXPECTED_ID" =~ ^sha256:[0-9a-f]{64}$ ]]; then
  echo "Pinned Redis reference did not resolve to one immutable local image ID." >&2
  exit 1
fi

DCE_SKIP_BACKUP=true bash infra/scripts/prepare-production-env.sh \
  - "$CANDIDATE_ENV" ci-runtime.example.org "$APP_REVISION"
test "$(grep '^APP_VERSION=' "$CANDIDATE_ENV" | cut -d= -f2-)" = "$APP_VERSION"
"${compose[@]}" config --quiet

candidate_images=$(python3 ops/runtime_release_parity.py candidate \
  --root "$ROOT" --env-file "$CANDIDATE_ENV" \
  --version-name "$APP_VERSION" --source-git-sha "$APP_REVISION" \
  --project-name "$PROJECT_NAME")

# Build55 proves the actual final-image trust stores and image encoders offline.
# Bind the helper from this exact source checkout read-only; never install or
# download while checking a runtime image, and never run a mutable image tag.
if [ "$APP_VERSION" = 3.1.47 ]; then
  TRUST_BACKEND_IMAGE_ID=$(python3 -c \
    'import json,sys; print(json.loads(sys.argv[1])["services"]["backend"]["image_id"])' \
    "$candidate_images")
  if ! [[ "$TRUST_BACKEND_IMAGE_ID" =~ ^sha256:[0-9a-f]{64}$ ]] || \
     [ "$(docker image inspect --format '{{.Id}}' "$TRUST_BACKEND_IMAGE_ID")" != "$TRUST_BACKEND_IMAGE_ID" ]; then
    echo "Build55 trust verification requires the attested backend image ID." >&2
    exit 1
  fi
  docker run --rm --pull never --network none --read-only --cap-drop ALL \
    --security-opt no-new-privileges \
    --mount "type=bind,src=$ROOT/infra/scripts/bootstrap-verified-pip.py,dst=/tmp/verify-runtime-trust.py,readonly" \
    --entrypoint python "$TRUST_BACKEND_IMAGE_ID" \
    -I -B /tmp/verify-runtime-trust.py --verify-runtime
fi

# Build52/53/54 exact pending-data transitions run Alembic from the attested backend
# image before its normal entrypoint or any other application runtime starts.
# Exercise that same Compose/image boundary here; the later full runtime check
# remains the proof for the ordinary fresh-install startup path.
EXACT_MIGRATION_BUILD=""
case "$APP_VERSION" in
  3.1.44) EXACT_MIGRATION_BUILD=Build52 ;;
  3.1.45) EXACT_MIGRATION_BUILD=Build53 ;;
  3.1.46) EXACT_MIGRATION_BUILD=Build54 ;;
esac
if [ -n "$EXACT_MIGRATION_BUILD" ]; then
  CANDIDATE_BACKEND_IMAGE_ID=$(python3 -c \
    'import json,sys; print(json.loads(sys.argv[1])["services"]["backend"]["image_id"])' \
    "$candidate_images")
  if ! [[ "$CANDIDATE_BACKEND_IMAGE_ID" =~ ^sha256:[0-9a-f]{64}$ ]] || \
     [ "$(docker image inspect --format '{{.Id}}' \
       "$CANDIDATE_BACKEND_IMAGE_ID" 2>/dev/null)" \
       != "$CANDIDATE_BACKEND_IMAGE_ID" ]; then
    echo "Attested $EXACT_MIGRATION_BUILD backend image is unavailable for migration proof." >&2
    exit 1
  fi
  (umask 077
   cat > "$MIGRATION_OVERRIDE" <<YAML
services:
  backend:
    image: "$CANDIDATE_BACKEND_IMAGE_ID"
YAML
  )
  migration_compose=(
    docker compose -p "$PROJECT_NAME" --project-directory "$ROOT"
    -f "$ROOT/docker-compose.prod.yml" -f "$MIGRATION_OVERRIDE"
    --env-file "$CANDIDATE_ENV"
  )
  "${compose[@]}" config --format json > "$MIGRATION_BASE_CONFIG"
  "${migration_compose[@]}" config --format json > "$MIGRATION_PINNED_CONFIG"
  chmod 600 "$MIGRATION_OVERRIDE" "$MIGRATION_BASE_CONFIG" \
    "$MIGRATION_PINNED_CONFIG"
  python3 - "$MIGRATION_BASE_CONFIG" "$MIGRATION_PINNED_CONFIG" \
      "$CANDIDATE_BACKEND_IMAGE_ID" <<'PY'
import copy
import json
from pathlib import Path
import sys

base = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
pinned = json.loads(Path(sys.argv[2]).read_text(encoding="utf-8"))
expected = copy.deepcopy(base)
expected["services"]["backend"]["image"] = sys.argv[3]
if pinned != expected:
    raise SystemExit("exact migration override changed more than the backend image")
PY
  assert_no_normal_application_runtime() {
    local service
    local containers
    for service in redis backend frontend; do
      containers=$("${compose[@]}" ps --all -q "$service")
      if [ -n "$containers" ]; then
        echo "$EXACT_MIGRATION_BUILD migration proof found an unexpected $service runtime." >&2
        return 1
      fi
    done
  }
  assert_no_normal_application_runtime
  "${compose[@]}" up -d --no-build --pull never --wait --wait-timeout 180 \
    postgres
  assert_no_normal_application_runtime
  mapfile -t MIGRATION_POSTGRES_CONTAINERS \
    < <("${compose[@]}" ps --all --status running -q postgres)
  if [ "${#MIGRATION_POSTGRES_CONTAINERS[@]}" -ne 1 ] || \
     ! [[ "${MIGRATION_POSTGRES_CONTAINERS[0]}" =~ ^[0-9a-f]{64}$ ]]; then
    echo "$EXACT_MIGRATION_BUILD migration proof requires exactly one running PostgreSQL container." >&2
    exit 1
  fi
  "${migration_compose[@]}" run --pull never --rm --no-deps \
    --entrypoint python backend -m alembic upgrade 0089
  assert_no_normal_application_runtime
  PRIOR_MIGRATION_HEAD=$(docker exec "${MIGRATION_POSTGRES_CONTAINERS[0]}" \
    psql -U erp -d erp -Atc 'SELECT version_num FROM alembic_version')
  if [ "$PRIOR_MIGRATION_HEAD" != 0089 ]; then
    echo "$EXACT_MIGRATION_BUILD image migration proof did not establish prior head 0089." >&2
    exit 1
  fi
  "${migration_compose[@]}" run --pull never --rm --no-deps \
    --entrypoint python backend -m alembic upgrade head
  assert_no_normal_application_runtime
  MIGRATED_DATABASE_HEAD=$(docker exec "${MIGRATION_POSTGRES_CONTAINERS[0]}" \
    psql -U erp -d erp -Atc 'SELECT version_num FROM alembic_version')
  if [ "$MIGRATED_DATABASE_HEAD" != 0091 ]; then
    echo "$EXACT_MIGRATION_BUILD one-off image migration did not produce database head 0091." >&2
    exit 1
  fi
fi

"${compose[@]}" up -d --no-build --wait --wait-timeout 180 \
  postgres redis backend frontend
mapfile -t REDIS_CONTAINERS < <("${compose[@]}" ps --all --status running -q redis)
if [ "${#REDIS_CONTAINERS[@]}" -ne 1 ] || \
   ! [[ "${REDIS_CONTAINERS[0]}" =~ ^[0-9a-f]{64}$ ]]; then
  echo "Runtime verification requires exactly one running Redis container." >&2
  exit 1
fi
REDIS_RUNNING_ID=$(docker inspect --format '{{.Image}}' "${REDIS_CONTAINERS[0]}")
REDIS_HEALTH=$(docker inspect --format '{{.State.Health.Status}}' "${REDIS_CONTAINERS[0]}")
if [ "$REDIS_RUNNING_ID" != "$REDIS_EXPECTED_ID" ] || [ "$REDIS_HEALTH" != healthy ]; then
  echo "Running Redis identity or health differs from the pinned candidate." >&2
  exit 1
fi
if [ "$(docker image inspect --format '{{.Id}}' "$REDIS_REF")" != "$REDIS_EXPECTED_ID" ]; then
  echo "Pinned Redis reference changed during runtime verification." >&2
  exit 1
fi
printf 'redis_reference=%s\nredis_image_id=%s\nredis_health=%s\n' \
  "$REDIS_REF" "$REDIS_RUNNING_ID" "$REDIS_HEALTH"
"${compose[@]}" exec -T backend python -c \
  "import urllib.request; urllib.request.urlopen('http://localhost:8000/readyz', timeout=5).read()"
"${compose[@]}" exec -T backend alembic current
python3 ops/runtime_release_parity.py running \
  --root "$ROOT" --env-file "$CANDIDATE_ENV" \
  --version-name "$APP_VERSION" --source-git-sha "$APP_REVISION" \
  --project-name "$PROJECT_NAME" \
  --services postgres redis backend frontend \
  --expected-images-json "$candidate_images" >/dev/null

# Exercise the actual reverse-proxy boundary without asking an external ACME
# service for a disposable CI hostname. The shell override applies only while
# creating Caddy; the already-running application containers keep the reviewed
# candidate environment.
DOMAIN='http://ci-runtime.local' "${compose[@]}" up -d --no-build caddy
attempts=0
until curl -fsS -H 'Host: ci-runtime.local' \
  'http://127.0.0.1/readyz' >"$ROUTED_READY" 2>/dev/null; do
  attempts=$((attempts + 1))
  if [ "$attempts" -ge 30 ]; then
    "${compose[@]}" logs caddy >&2 || true
    echo "Caddy did not route the backend readiness endpoint." >&2
    exit 1
  fi
  sleep 1
done
grep -Eq '"status"[[:space:]]*:[[:space:]]*"ready"' \
  "$ROUTED_READY"
curl -fsS -H 'Host: ci-runtime.local' 'http://127.0.0.1/' \
  | grep -F '<div id="root"></div>' >/dev/null
python3 ops/verify_web_public_assets.py --base-url http://127.0.0.1 --host ci-runtime.local

python3 ops/runtime_release_parity.py running \
  --root "$ROOT" --env-file "$CANDIDATE_ENV" \
  --version-name "$APP_VERSION" --source-git-sha "$APP_REVISION" \
  --project-name "$PROJECT_NAME" \
  --expected-images-json "$candidate_images" >/dev/null

echo "Production Caddy/PostgreSQL/Redis/backend/frontend routing, health and identity passed."
