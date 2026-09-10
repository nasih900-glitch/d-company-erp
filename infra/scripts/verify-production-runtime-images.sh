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
REDIS_REF='redis:7-alpine@sha256:ff02b58f971e7d7d156a1267e283fcbbeee91773b6aa36c49dac28ecfe28eadf'
CANDIDATE_ENV=$(mktemp)
ROUTED_READY=$(mktemp)
DIAGNOSTIC_LOG=$(mktemp)
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
  rm -f "$CANDIDATE_ENV" "$ROUTED_READY" "$DIAGNOSTIC_LOG"
  exit "$failure_code"
}
trap cleanup EXIT

for service in caddy postgres backend frontend; do
  source_ref="erp-${service}:${TAG_SUFFIX}"
  target_ref="d-company-erp-${service}:${APP_REVISION}"
  source_id=$(docker image inspect --format '{{.Id}}' "$source_ref")
  docker image tag "$source_id" "$target_ref"
  test "$(docker image inspect --format '{{.Id}}' "$target_ref")" = "$source_id"
done
docker pull "$REDIS_REF" >/dev/null
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
  --services postgres backend frontend \
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

python3 ops/runtime_release_parity.py running \
  --root "$ROOT" --env-file "$CANDIDATE_ENV" \
  --version-name "$APP_VERSION" --source-git-sha "$APP_REVISION" \
  --project-name "$PROJECT_NAME" \
  --expected-images-json "$candidate_images" >/dev/null

echo "Production Caddy/PostgreSQL/backend/frontend routing, health and identity passed."
