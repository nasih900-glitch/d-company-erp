#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR=$(unset CDPATH; cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)
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
VALIDATOR="$SCRIPT_DIR/prepare-code30-3-combined-cleanup.py"
WRAPPER="$SCRIPT_DIR/rehearse-code30-3-combined-cleanup.sql"
BODY="$SCRIPT_DIR/code30-3-combined-cleanup-body.sql"
TAGGED_SHA=ad5adfb93c3488f1f931ca27da53824aa57d3dc5

die() { printf 'REFUSED: %s\n' "$*" >&2; exit 2; }
hash_file() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum -- "$1" | awk '{print $1}'
  else shasum -a 256 -- "$1" | awk '{print $1}'; fi
}
safe_name() { [[ "$1" =~ ^[A-Za-z0-9][A-Za-z0-9_.-]*$ ]]; }
regular_file() { [[ "$1" == /* && -f "$1" && -r "$1" && ! -L "$1" ]]; }

postgres_container= backup_file= manifest_file= classification_file= review_file=
tablet_file= evidence_output=
while (($#)); do
  case "$1" in
    --postgres-container) postgres_container=${2-}; shift 2 ;;
    --backup-file) backup_file=${2-}; shift 2 ;;
    --operator-reviewed-manifest) manifest_file=${2-}; shift 2 ;;
    --classification-evidence) classification_file=${2-}; shift 2 ;;
    --operator-review-evidence) review_file=${2-}; shift 2 ;;
    --tablet-replay-evidence) tablet_file=${2-}; shift 2 ;;
    --rehearsal-evidence-output) evidence_output=${2-}; shift 2 ;;
    *) die "unknown or incomplete argument: $1" ;;
  esac
done
[[ -n "$postgres_container" ]] && safe_name "$postgres_container" || die "safe --postgres-container is required"
for path in "$backup_file" "$manifest_file" "$classification_file" "$review_file" "$tablet_file" "$VALIDATOR" "$WRAPPER" "$BODY"; do
  regular_file "$path" || die "path must be an absolute readable regular non-symlink: $path"
done
[[ "$evidence_output" == /* && ! -e "$evidence_output" && ! -L "$evidence_output" ]] ||
  die "--rehearsal-evidence-output must be a new absolute path"
command -v docker >/dev/null 2>&1 || die "docker is required"
command -v python3 >/dev/null 2>&1 || die "python3 is required"

python3 "$VALIDATOR" --quiet --classification-evidence "$classification_file"   --operator-review-evidence "$review_file" --tablet-replay-evidence "$tablet_file"   "$manifest_file" || die "reviewed manifest evidence validation failed"
expected_backup_sha=$(python3 "$VALIDATOR" --quiet --print-field backup_sha256 "$manifest_file")
expected_body_sha=$(python3 "$VALIDATOR" --quiet --print-field maintenance_sql_sha256 "$manifest_file")
expected_source_sha=$(python3 "$VALIDATOR" --quiet --print-field maintenance_source_git_sha "$manifest_file")
expected_fingerprint=$(python3 "$VALIDATOR" --quiet --print-state-fingerprint "$manifest_file")
actual_backup_sha=$(hash_file "$backup_file")
actual_body_sha=$(hash_file "$BODY")
manifest_sha=$(hash_file "$manifest_file")
classification_sha=$(hash_file "$classification_file")
review_sha=$(hash_file "$review_file")
tablet_sha=$(hash_file "$tablet_file")
wrapper_sha=$(hash_file "$WRAPPER")
[[ "$actual_backup_sha" == "$expected_backup_sha" ]] || die "backup hash differs from manifest"
[[ "$actual_body_sha" == "$expected_body_sha" ]] || die "shared transaction body hash differs from manifest"

c3c_require_clean_ops_checkout "$SCRIPT_DIR" "$expected_source_sha"
docker inspect "$postgres_container" >/dev/null 2>&1 || die "PostgreSQL container is absent"
[[ "$(docker inspect --format '{{.State.Running}}' "$postgres_container")" == true ]] ||
  die "PostgreSQL container is stopped"
docker exec -i "$postgres_container" pg_restore --list >/dev/null < "$backup_file" ||
  die "backup is not a valid custom-format archive"

restore_db_candidate="code30_combined_restore_${$}_$(date -u +%s)"
container_dir_candidate="/tmp/code30-combined-rehearsal-${$}-$(date -u +%s)"
restore_db=
container_dir=
cleanup() {
  local status=$?
  trap - EXIT
  if [[ -n "$container_dir" ]]; then
    docker exec --user 0 "$postgres_container" rm -rf -- "$container_dir" >/dev/null 2>&1 || status=1
  fi
  if [[ -n "$restore_db" ]]; then
    docker exec "$postgres_container" sh -eu -c '
      : "${POSTGRES_USER:?}"; exec dropdb --username "$POSTGRES_USER" --if-exists --force "$1"
    ' cleanup "$restore_db" >/dev/null 2>&1 || status=1
  fi
  exit "$status"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

docker exec "$postgres_container" sh -eu -c '
  : "${POSTGRES_USER:?}"; exec createdb --username "$POSTGRES_USER" --template template0 "$1"
' create "$restore_db_candidate"
restore_db=$restore_db_candidate
docker exec -i "$postgres_container" sh -eu -c '
  : "${POSTGRES_USER:?}"; exec pg_restore --exit-on-error --no-owner --no-privileges \
    --username "$POSTGRES_USER" --dbname "$1"
' restore "$restore_db" < "$backup_file"
[[ "$(hash_file "$backup_file")" == "$actual_backup_sha" ]] || die "backup changed during restore"

docker exec "$postgres_container" test ! -e "$container_dir_candidate" || die "temporary container path already exists"
docker exec --user 0 "$postgres_container" mkdir -m 0700 -- "$container_dir_candidate"
container_dir=$container_dir_candidate
docker cp "$WRAPPER" "$postgres_container:$container_dir/rehearse-code30-3-combined-cleanup.sql" >/dev/null
docker cp "$BODY" "$postgres_container:$container_dir/code30-3-combined-cleanup-body.sql" >/dev/null
docker cp "$manifest_file" "$postgres_container:$container_dir/manifest.json" >/dev/null
docker cp "$tablet_file" "$postgres_container:$container_dir/tablet-replay.json" >/dev/null
docker exec --user 0 "$postgres_container" sh -eu -c '
  chown -R postgres:postgres "$1"; chmod 0500 "$1"
  chmod 0400 "$1"/*
' permissions "$container_dir"
[[ "$(hash_file "$WRAPPER")" == "$wrapper_sha" && "$(hash_file "$BODY")" == "$actual_body_sha" &&
   "$(hash_file "$manifest_file")" == "$manifest_sha" && "$(hash_file "$tablet_file")" == "$tablet_sha" ]] ||
  die "a host input changed while staging"
for pair in \
  "$wrapper_sha:$container_dir/rehearse-code30-3-combined-cleanup.sql" \
  "$actual_body_sha:$container_dir/code30-3-combined-cleanup-body.sql" \
  "$manifest_sha:$container_dir/manifest.json" \
  "$tablet_sha:$container_dir/tablet-replay.json"; do
  expected=${pair%%:*}; staged_path=${pair#*:}
  actual=$(docker exec "$postgres_container" sha256sum "$staged_path" | awk '{print $1}')
  [[ "$actual" == "$expected" ]] || die "staged container file hash differs: $staged_path"
done

sql_output=$(docker exec "$postgres_container" sh -eu -c '
  : "${POSTGRES_USER:?}"
  exec psql -X --no-psqlrc --set=ON_ERROR_STOP=1 --quiet --tuples-only --no-align \
    --username "$POSTGRES_USER" --dbname "$1" \
    --set="expected_database_name=$1" \
    --set="manifest_container_path=$2" \
    --set="tablet_replay_evidence_container_path=$3" \
    --set="backup_sha256=$4" --set="maintenance_sql_sha256=$5" \
    --set="expected_state_fingerprint=$6" --file="$7"
' rehearsal "$restore_db" "$container_dir/manifest.json" \
  "$container_dir/tablet-replay.json" "$actual_backup_sha" "$actual_body_sha" \
  "$expected_fingerprint" "$container_dir/rehearse-code30-3-combined-cleanup.sql")
printf '%s\n' "$sql_output"

python3 - "$sql_output" "$evidence_output" "$actual_backup_sha" "$manifest_sha"   "$classification_sha" "$review_sha" "$tablet_sha" "$actual_body_sha" "$wrapper_sha"   "$expected_source_sha" "$expected_fingerprint" "$TAGGED_SHA" <<'PY'
import datetime, hashlib, json, os, sys
(sql_output, output, backup, manifest, classification, review, tablet, body, wrapper,
 source, fingerprint, tagged) = sys.argv[1:]
lines = [line for line in sql_output.splitlines() if line.strip()]
try:
    result = json.loads(lines[-1])
except (IndexError, json.JSONDecodeError) as exc:
    raise SystemExit(f"REFUSED: rehearsal returned no canonical JSON: {exc}")
if result.get("mode") != "restore rehearsal (rolled back)" or result.get("state_fingerprint") != fingerprint:
    raise SystemExit("REFUSED: rehearsal output fingerprint or mode differs")
evidence = {
    "schema_version": 1,
    "completed_at": datetime.datetime.now(datetime.timezone.utc).isoformat(),
    "backup_sha256": backup,
    "manifest_sha256": manifest,
    "classification_evidence_sha256": classification,
    "operator_review_evidence_sha256": review,
    "tablet_replay_evidence_sha256": tablet,
    "maintenance_body_sha256": body,
    "rehearsal_wrapper_sha256": wrapper,
    "maintenance_source_git_sha": source,
    "tagged_app_source_git_sha": tagged,
    "state_fingerprint": fingerprint,
    "cleanup_id": result["cleanup_id"],
    "sql_output_sha256": hashlib.sha256(sql_output.encode()).hexdigest(),
}
fd = os.open(output, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
with os.fdopen(fd, "w", encoding="utf-8") as stream:
    json.dump(evidence, stream, sort_keys=True, indent=2)
    stream.write("\n")
print(f"Rehearsal evidence SHA-256: {hashlib.sha256(open(output, 'rb').read()).hexdigest()}")
PY
