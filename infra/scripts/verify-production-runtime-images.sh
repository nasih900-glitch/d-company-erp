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
CANDIDATE_ENV=$(mktemp)
ROUTED_READY=$(mktemp)
rm -f "$CANDIDATE_ENV"

compose=(
  docker compose -p "$PROJECT_NAME" --project-directory "$ROOT"
  -f "$ROOT/docker-compose.prod.yml" --env-file "$CANDIDATE_ENV"
)
cleanup() {
  failure_code=$?
  trap - EXIT
  "${compose[@]}" down --volumes --remove-orphans >/dev/null 2>&1 || true
  rm -f "$CANDIDATE_ENV" "$ROUTED_READY"
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
docker pull 'redis:7-alpine@sha256:ff02b58f971e7d7d156a1267e283fcbbeee91773b6aa36c49dac28ecfe28eadf' \
  >/dev/null

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
