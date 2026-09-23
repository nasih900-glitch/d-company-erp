#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR=$(unset CDPATH; cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)
VALIDATOR="$SCRIPT_DIR/prepare-code30-3-combined-cleanup.py"
SQL_FILE="$SCRIPT_DIR/rehearse-code30-3-combined-cleanup.sql"

die() {
  printf 'REFUSED: %s\n' "$*" >&2
  exit 2
}

usage() {
  cat <<'USAGE'
Usage: rehearse-code30-3-combined-cleanup.sh \
  --postgres-container NAME \
  --backup-file /absolute/path/to/fresh-post-closure.dump \
  --operator-reviewed-manifest /absolute/path/to/combined-cleanup.json \
  --classification-evidence /absolute/path/to/classification-evidence \
  --operator-review-evidence /absolute/path/to/operator-review.json

This command restores the exact backup to a disposable database, runs the
fully pinned combined transaction, and always rolls it back. It has no
production apply mode.
USAGE
}

hash_file() {
  local path=$1
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum -- "$path" | awk '{print $1}'
  else
    shasum -a 256 -- "$path" | awk '{print $1}'
  fi
}

safe_container_name() {
  [[ "$1" =~ ^[A-Za-z0-9][A-Za-z0-9_.-]*$ ]]
}

postgres_container=
backup_file=
manifest_file=
classification_file=
review_file=
while (($#)); do
  case "$1" in
    --postgres-container) (($# >= 2)) || die "--postgres-container requires a value"; postgres_container=$2; shift 2 ;;
    --backup-file) (($# >= 2)) || die "--backup-file requires a value"; backup_file=$2; shift 2 ;;
    --operator-reviewed-manifest) (($# >= 2)) || die "--operator-reviewed-manifest requires a value"; manifest_file=$2; shift 2 ;;
    --classification-evidence) (($# >= 2)) || die "--classification-evidence requires a value"; classification_file=$2; shift 2 ;;
    --operator-review-evidence) (($# >= 2)) || die "--operator-review-evidence requires a value"; review_file=$2; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) die "unknown argument: $1" ;;
  esac
done

[[ -n "$postgres_container" ]] || die "--postgres-container is required"
safe_container_name "$postgres_container" || die "unsafe PostgreSQL container name"
for path in "$backup_file" "$manifest_file" "$classification_file" "$review_file" "$VALIDATOR" "$SQL_FILE"; do
  [[ "$path" == /* ]] || die "all file paths must be absolute: $path"
  [[ -f "$path" && -r "$path" && ! -L "$path" ]] || die "file must be readable, regular, and not a symlink: $path"
done
command -v docker >/dev/null 2>&1 || die "docker is required"
command -v python3 >/dev/null 2>&1 || die "python3 is required"

python3 "$VALIDATOR" --quiet \
  --classification-evidence "$classification_file" \
  --operator-review-evidence "$review_file" \
  "$manifest_file" || die "manifest classification or operator review validation failed"
expected_backup_sha=$(python3 "$VALIDATOR" --quiet --print-field backup_sha256 "$manifest_file")
expected_sql_sha=$(python3 "$VALIDATOR" --quiet --print-field maintenance_sql_sha256 "$manifest_file")
expected_source_sha=$(python3 "$VALIDATOR" --quiet --print-field maintenance_source_git_sha "$manifest_file")
expected_classification_sha=$(python3 "$VALIDATOR" --quiet --print-field classification_evidence_sha256 "$manifest_file")
expected_review_sha=$(python3 "$VALIDATOR" --quiet --print-field operator_review_evidence_sha256 "$manifest_file")
actual_backup_sha=$(hash_file "$backup_file")
actual_sql_sha=$(hash_file "$SQL_FILE")
actual_classification_sha=$(hash_file "$classification_file")
actual_review_sha=$(hash_file "$review_file")
[[ "$actual_backup_sha" == "$expected_backup_sha" ]] || die "backup SHA-256 differs from reviewed manifest"
[[ "$actual_sql_sha" == "$expected_sql_sha" ]] || die "maintenance SQL SHA-256 differs from reviewed manifest"
[[ "$actual_classification_sha" == "$expected_classification_sha" ]] || die "classification evidence SHA-256 differs from reviewed manifest"
[[ "$actual_review_sha" == "$expected_review_sha" ]] || die "operator review evidence SHA-256 differs from reviewed manifest"

repo_root=$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel 2>/dev/null) || die "maintenance scripts are not in a Git checkout"
[[ "$(git -C "$repo_root" rev-parse HEAD)" == "$expected_source_sha" ]] || \
  die "maintenance checkout HEAD differs from reviewed manifest"
[[ -z "$(git -C "$repo_root" status --porcelain=v1 --untracked-files=all)" ]] || \
  die "maintenance checkout must be completely clean"

docker inspect "$postgres_container" >/dev/null 2>&1 || die "PostgreSQL container does not exist"
[[ "$(docker inspect --format '{{.State.Running}}' "$postgres_container")" == true ]] || \
  die "PostgreSQL container is not running"

restore_db=
manifest_container_path=
cleanup() {
  local status=$?
  trap - EXIT
  if [[ -n "$manifest_container_path" ]]; then
    docker exec "$postgres_container" rm -f -- "$manifest_container_path" >/dev/null 2>&1 || status=1
  fi
  if [[ -n "$restore_db" ]]; then
    docker exec "$postgres_container" sh -eu -c '
      : "${POSTGRES_USER:?POSTGRES_USER is not set}"
      exec dropdb --username "$POSTGRES_USER" --if-exists --force "$1"
    ' combined-cleanup "$restore_db" >/dev/null 2>&1 || status=1
  fi
  exit "$status"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

docker exec -i "$postgres_container" pg_restore --list >/dev/null < "$backup_file" || \
  die "backup is not a valid custom-format PostgreSQL archive"

restore_db_candidate="code30_combined_restore_${$}_$(date -u +%s)"
docker exec "$postgres_container" sh -eu -c '
  : "${POSTGRES_USER:?POSTGRES_USER is not set}"
  exec createdb --username "$POSTGRES_USER" --template template0 "$1"
' combined-cleanup "$restore_db_candidate"
restore_db=$restore_db_candidate

docker exec -i "$postgres_container" sh -eu -c '
  : "${POSTGRES_USER:?POSTGRES_USER is not set}"
  exec pg_restore --exit-on-error --no-owner --no-privileges \
    --username "$POSTGRES_USER" --dbname "$1"
' combined-cleanup "$restore_db" < "$backup_file"
[[ "$(hash_file "$backup_file")" == "$actual_backup_sha" ]] || die "backup changed during restore"

manifest_container_path="/tmp/code30-combined-manifest-${$}-$(date -u +%s).json"
docker exec "$postgres_container" test ! -e "$manifest_container_path" || die "temporary manifest path already exists"
docker cp "$manifest_file" "$postgres_container:$manifest_container_path" >/dev/null
docker exec --user 0 "$postgres_container" sh -eu -c '
  chown postgres:postgres "$1"
  chmod 0400 "$1"
' combined-cleanup "$manifest_container_path"

docker exec -i "$postgres_container" sh -eu -c '
  : "${POSTGRES_USER:?POSTGRES_USER is not set}"
  exec psql -X --no-psqlrc --set=ON_ERROR_STOP=1 --quiet --tuples-only --no-align \
    --username "$POSTGRES_USER" --dbname "$1" \
    --set="manifest_container_path=$2" \
    --set="backup_sha256=$3" \
    --set="maintenance_sql_sha256=$4"
' combined-cleanup "$restore_db" "$manifest_container_path" "$actual_backup_sha" "$actual_sql_sha" < "$SQL_FILE"

printf 'Combined cleanup rehearsal passed and rolled back in disposable database %s.\n' "$restore_db"
