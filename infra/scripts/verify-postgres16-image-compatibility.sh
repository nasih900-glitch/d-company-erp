#!/bin/bash
# Prove the hardened PostgreSQL 16 image supports fresh initialization,
# same-volume upgrade from the prior exact image, and same-volume rollback.

set -euo pipefail

HARDENED_IMAGE="${1:-}"
EXPECTED_VERSION="${2:-}"
EXPECTED_REVISION="${3:-}"
ORIGINAL_IMAGE='postgres:16.15-alpine3.24@sha256:cf78e76683b9ca8c5733cbbdce6c9262b45b6767934dd0a95e671f9a0fc20685'

if [ -z "$HARDENED_IMAGE" ] || \
   ! [[ "$EXPECTED_VERSION" =~ ^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$ ]] || \
   ! [[ "$EXPECTED_REVISION" =~ ^[0-9a-f]{40}$ ]]; then
  echo "Usage: $0 HARDENED_IMAGE X.Y.Z FULL_GIT_SHA" >&2
  exit 2
fi

token="code25-pgcompat-$$-${RANDOM}"
compat_volume="$token-existing"
restore_volume="$token-restore"
original_container="$token-original"
hardened_container="$token-hardened"
rollback_container="$token-rollback"
restore_container="$token-restored"
dump_dir=$(mktemp -d)
dump_file="$dump_dir/compatibility.dump"

cleanup() {
  failure_code=$?
  trap - EXIT
  docker rm -f \
    "$original_container" "$hardened_container" "$rollback_container" \
    "$restore_container" >/dev/null 2>&1 || true
  docker volume rm "$compat_volume" "$restore_volume" >/dev/null 2>&1 || true
  rm -rf "$dump_dir"
  exit "$failure_code"
}
trap cleanup EXIT

wait_ready() {
  local container=$1
  local attempts=0
  until docker exec "$container" pg_isready -U erp -d erp >/dev/null 2>&1; do
    attempts=$((attempts + 1))
    if [ "$attempts" -ge 60 ]; then
      docker logs "$container" >&2 || true
      echo "PostgreSQL compatibility container $container did not become ready." >&2
      return 1
    fi
    sleep 1
  done
}

start_postgres() {
  local container=$1
  local image=$2
  local volume=$3
  docker run -d --name "$container" \
    -e POSTGRES_USER=erp \
    -e POSTGRES_PASSWORD=test-only-not-a-secret \
    -e POSTGRES_DB=erp \
    -v "$volume:/var/lib/postgresql/data" \
    "$image" >/dev/null
  wait_ready "$container"
}

docker image inspect "$HARDENED_IMAGE" >/dev/null
test "$(docker image inspect --format '{{index .Config.Labels "org.opencontainers.image.version"}}' "$HARDENED_IMAGE")" = "$EXPECTED_VERSION"
test "$(docker image inspect --format '{{index .Config.Labels "org.opencontainers.image.revision"}}' "$HARDENED_IMAGE")" = "$EXPECTED_REVISION"
test "$(docker run --rm "$HARDENED_IMAGE" postgres --version)" = 'postgres (PostgreSQL) 16.15'
docker run --rm "$HARDENED_IMAGE" gosu --version | grep -F '1.19 (go1.26.8 on linux/'
docker run --rm --entrypoint sh "$HARDENED_IMAGE" -ceu \
  'test "$(sha256sum /usr/local/bin/gosu | cut -d" " -f1)" = cdcdfbe2a74dc15d62f6f73da877a372641b80104ff04ca5bee7bc36ee9936ab; gosu nobody true; su-exec nobody true'

docker pull "$ORIGINAL_IMAGE" >/dev/null
docker volume create "$compat_volume" >/dev/null
start_postgres "$original_container" "$ORIGINAL_IMAGE" "$compat_volume"
docker exec "$original_container" psql -U erp -d erp -v ON_ERROR_STOP=1 -c \
  "CREATE TABLE compatibility_probe(id integer PRIMARY KEY, note text NOT NULL); INSERT INTO compatibility_probe VALUES (1, 'original');" \
  >/dev/null
docker rm -f "$original_container" >/dev/null

start_postgres "$hardened_container" "$HARDENED_IMAGE" "$compat_volume"
test "$(docker exec "$hardened_container" psql -U erp -d erp -Atc \
  "SELECT id || '|' || note FROM compatibility_probe ORDER BY id")" = '1|original'
docker exec "$hardened_container" psql -U erp -d erp -v ON_ERROR_STOP=1 -c \
  "INSERT INTO compatibility_probe VALUES (2, 'hardened');" >/dev/null
docker exec "$hardened_container" pg_dump -U erp -d erp --format=custom > "$dump_file"
test -s "$dump_file"
docker rm -f "$hardened_container" >/dev/null

# An immediate rollback to the exact prior 16.15 image must retain both rows.
start_postgres "$rollback_container" "$ORIGINAL_IMAGE" "$compat_volume"
test "$(docker exec "$rollback_container" psql -U erp -d erp -Atc \
  "SELECT id || '|' || note FROM compatibility_probe ORDER BY id")" = $'1|original\n2|hardened'
docker rm -f "$rollback_container" >/dev/null

# A new hardened container must initialize an empty volume and accept a full
# logical restore of data written before and after the image transition.
docker volume create "$restore_volume" >/dev/null
start_postgres "$restore_container" "$HARDENED_IMAGE" "$restore_volume"
docker exec "$restore_container" dropdb -U erp --if-exists erp >/dev/null
docker exec "$restore_container" createdb -U erp -T template0 erp >/dev/null
docker exec -i "$restore_container" pg_restore -U erp -d erp \
  --exit-on-error --no-owner < "$dump_file" >/dev/null
test "$(docker exec "$restore_container" psql -U erp -d erp -Atc \
  "SELECT id || '|' || note FROM compatibility_probe ORDER BY id")" = $'1|original\n2|hardened'

echo "PostgreSQL 16 fresh-volume, existing-volume, backup/restore and rollback compatibility passed."
