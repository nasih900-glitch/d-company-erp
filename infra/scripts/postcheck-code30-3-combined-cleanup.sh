#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR=$(unset CDPATH; cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)
LOCK_BOOTSTRAP="$SCRIPT_DIR/code30-3-combined-cleanup-lock.py"
if [[ "${DCOMPANY_PRODUCTION_INSTALL_LOCK_FD:-}" != 9 ]]; then
  [[ -f "$LOCK_BOOTSTRAP" && -r "$LOCK_BOOTSTRAP" && ! -L "$LOCK_BOOTSTRAP" ]] || {
    printf 'REFUSED: tracked installer-lock bootstrap is missing, unreadable, or linked\n' >&2; exit 2;
  }
  bootstrap_rel=$(git -C "$SCRIPT_DIR" ls-files --full-name "$LOCK_BOOTSTRAP")
  bootstrap_actual=$(git hash-object "$LOCK_BOOTSTRAP")
  bootstrap_expected=$(git -C "$SCRIPT_DIR" rev-parse "HEAD:$bootstrap_rel" 2>/dev/null || true)
  [[ -n "$bootstrap_rel" && "$bootstrap_actual" == "$bootstrap_expected" ]] || {
    printf 'REFUSED: installer-lock bootstrap is not the tracked HEAD version\n' >&2; exit 2;
  }
  exec python3 "$LOCK_BOOTSTRAP" "$SCRIPT_DIR/postcheck-code30-3-combined-cleanup.sh" "$@"
fi
RUNTIME_HELPER="$SCRIPT_DIR/code30-3-combined-cleanup-runtime.sh"
[[ -f "$RUNTIME_HELPER" && -r "$RUNTIME_HELPER" && ! -L "$RUNTIME_HELPER" ]] || {
  printf 'REFUSED: tracked runtime helper is missing, unreadable, or linked\n' >&2; exit 2;
}
runtime_rel=$(git -C "$SCRIPT_DIR" ls-files --full-name "$RUNTIME_HELPER")
runtime_actual=$(git hash-object "$RUNTIME_HELPER")
runtime_expected=$(git -C "$SCRIPT_DIR" rev-parse "HEAD:$runtime_rel" 2>/dev/null || true)
[[ -n "$runtime_rel" && "$runtime_actual" == "$runtime_expected" ]] || {
    printf 'REFUSED: runtime helper is not the tracked HEAD version\n' >&2; exit 2;
  }
# shellcheck source=code30-3-combined-cleanup-runtime.sh
source "$RUNTIME_HELPER"
c3c_require_install_lock
VALIDATOR="$SCRIPT_DIR/prepare-code30-3-combined-cleanup.py"
BODY="$SCRIPT_DIR/code30-3-combined-cleanup-body.sql"
POSTCHECK="$SCRIPT_DIR/postcheck-code30-3-combined-cleanup.sql"

manifest_file= classification_file= review_file= tablet_file=
while (($#)); do
  case "$1" in
    --operator-reviewed-manifest) manifest_file=${2-}; shift 2 ;;
    --classification-evidence) classification_file=${2-}; shift 2 ;;
    --operator-review-evidence) review_file=${2-}; shift 2 ;;
    --tablet-replay-evidence) tablet_file=${2-}; shift 2 ;;
    *) c3c_die "unknown or incomplete argument: $1" ;;
  esac
done
for path in "$manifest_file" "$classification_file" "$review_file" "$tablet_file"             "$VALIDATOR" "$BODY" "$POSTCHECK"; do
  c3c_regular_file "$path" || c3c_die "path must be an absolute readable regular non-symlink: $path"
done
command -v docker >/dev/null 2>&1 || c3c_die "docker is required"
command -v python3 >/dev/null 2>&1 || c3c_die "python3 is required"

python3 "$VALIDATOR" --quiet --classification-evidence "$classification_file"   --operator-review-evidence "$review_file" --tablet-replay-evidence "$tablet_file"   "$manifest_file" || c3c_die "reviewed manifest evidence validation failed"
expected_body_sha=$(python3 "$VALIDATOR" --quiet --print-field maintenance_sql_sha256 "$manifest_file")
expected_source_sha=$(python3 "$VALIDATOR" --quiet --print-field maintenance_source_git_sha "$manifest_file")
expected_fingerprint=$(python3 "$VALIDATOR" --quiet --print-state-fingerprint "$manifest_file")
manifest_sha=$(c3c_hash_file "$manifest_file")
postcheck_sha=$(c3c_hash_file "$POSTCHECK")
[[ "$(c3c_hash_file "$BODY")" == "$expected_body_sha" ]] ||
  c3c_die "shared transaction body hash differs from manifest"
c3c_require_clean_ops_checkout "$SCRIPT_DIR" "$expected_source_sha"
c3c_resolve_stopped_runtime

container_dir_candidate="/tmp/code30-combined-postcheck-${$}-$(date -u +%s)"
container_dir=
cleanup() {
  local status=$?
  trap - EXIT
  if [[ -n "$container_dir" ]]; then
    docker exec --user 0 "$C3C_POSTGRES_CONTAINER" rm -rf -- "$container_dir" >/dev/null 2>&1 ||
      status=1
  fi
  exit "$status"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

docker exec "$C3C_POSTGRES_CONTAINER" test ! -e "$container_dir_candidate" ||
  c3c_die "temporary container path already exists"
docker exec --user 0 "$C3C_POSTGRES_CONTAINER" mkdir -m 0700 -- "$container_dir_candidate"
container_dir=$container_dir_candidate
docker cp "$POSTCHECK" "$C3C_POSTGRES_CONTAINER:$container_dir/postcheck-code30-3-combined-cleanup.sql" >/dev/null
docker cp "$manifest_file" "$C3C_POSTGRES_CONTAINER:$container_dir/manifest.json" >/dev/null
docker exec --user 0 "$C3C_POSTGRES_CONTAINER" sh -eu -c '
  chown -R postgres:postgres "$1"; chmod 0500 "$1"; chmod 0400 "$1"/*
' permissions "$container_dir"
[[ "$(c3c_hash_file "$POSTCHECK")" == "$postcheck_sha" &&
   "$(c3c_hash_file "$manifest_file")" == "$manifest_sha" ]] ||
  c3c_die "a host input changed while staging"
for pair in \
  "$postcheck_sha:$container_dir/postcheck-code30-3-combined-cleanup.sql" \
  "$manifest_sha:$container_dir/manifest.json"; do
  expected=${pair%%:*}; staged_path=${pair#*:}
  actual=$(docker exec "$C3C_POSTGRES_CONTAINER" sha256sum "$staged_path" | awk '{print $1}')
  [[ "$actual" == "$expected" ]] || c3c_die "staged container file hash differs: $staged_path"
done

c3c_require_install_lock
c3c_resolve_stopped_runtime
output=$(docker exec "$C3C_POSTGRES_CONTAINER" sh -eu -c '
  : "${POSTGRES_USER:?}"
  exec psql -X --no-psqlrc --set=ON_ERROR_STOP=1 --quiet --tuples-only --no-align \
    --username "$POSTGRES_USER" --dbname "$1" \
    --set="expected_database_name=$1" --set="manifest_container_path=$2" \
    --set="maintenance_sql_sha256=$3" --set="expected_state_fingerprint=$4" \
    --file="$5"
' postcheck "$C3C_DATABASE" "$container_dir/manifest.json" \
  "$expected_body_sha" "$expected_fingerprint" \
  "$container_dir/postcheck-code30-3-combined-cleanup.sql")
printf '%s\n' "$output"
[[ "$output" == *'"status": "accepted"'* ]] ||
  c3c_die "read-only postcheck returned no accepted result"
printf 'Read-only combined cleanup postcheck passed while Caddy and backend remain stopped.\n'
