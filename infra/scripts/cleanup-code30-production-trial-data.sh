#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR=$(unset CDPATH; cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)
SQL_FILE="$SCRIPT_DIR/cleanup-code30-production-trial-data.sql"
RUNNER_FILE="$SCRIPT_DIR/$(basename -- "${BASH_SOURCE[0]}")"
QUARANTINE_EVIDENCE_FILE="$SCRIPT_DIR/../../releases/evidence/code30-2-emulator-quarantine.json"
CONFIRMATION=APPLY_CODE30_1_VERIFIED_TRIAL_CLEANUP
EXPECTED_QUARANTINE_EVIDENCE_SHA256=379c6368936d03223e19482cc840c2a9d2483dc9a96909fba22cd9f59911eec8
ZERO_SHA256=$(printf '0%.0s' {1..64})
ZERO_GIT_SHA=$(printf '0%.0s' {1..40})

usage() {
  cat <<'USAGE'
Usage:
  cleanup-code30-production-trial-data.sh --postgres-container NAME

  cleanup-code30-production-trial-data.sh --postgres-container NAME \
    --backend-container NAME \
    --apply \
    --confirm APPLY_CODE30_1_VERIFIED_TRIAL_CLEANUP \
    --expected-state-fingerprint SHA256 \
    --backup-file /absolute/path/to/fresh-custom-format.dump \
    --source-git-sha GIT_SHA \
    --executor NAME

The default is a full transactional dry run that always rolls back without
inserting an audit row or consuming an audit sequence value.

Apply computes the backup hash itself, restores that exact file to a disposable
database inside the same PostgreSQL container, verifies migration 0078, and
runs the cleanup dry run on the restore.  Its complete database fingerprint
must equal the fresh production fingerprint supplied by the operator.  The
backend container's immutable image must also carry the same source revision.
The PostgreSQL and stopped backend containers must be the canonical services
from the same Docker Compose project, and no backend service container from
that project may still be running.
The reviewed quarantine evidence is hashed from its canonical tracked file in
that exact clean checkout.  The disposable restore database is dropped on every
exit.
USAGE
}

die() {
  printf 'REFUSED: %s\n' "$*" >&2
  exit 2
}

is_sha256() {
  [[ "$1" =~ ^[0-9a-f]{64}$ ]]
}

is_git_sha() {
  [[ "$1" =~ ^[0-9a-f]{40}$ ]]
}

safe_container_name() {
  [[ "$1" =~ ^[A-Za-z0-9][A-Za-z0-9_.-]*$ ]]
}

hash_file() {
  local path=$1
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum -- "$path" | awk '{print $1}'
  elif command -v shasum >/dev/null 2>&1; then
    shasum -a 256 -- "$path" | awk '{print $1}'
  else
    openssl dgst -sha256 "$path" | awk '{print $NF}'
  fi
}

postgres_container=
backend_container=
apply=false
confirm=
expected_state_fingerprint=
backup_file=
quarantine_evidence_sha256=$EXPECTED_QUARANTINE_EVIDENCE_SHA256
source_git_sha=
executor_name=

while (($#)); do
  case "$1" in
    --postgres-container)
      (($# >= 2)) || die "--postgres-container requires a value"
      postgres_container=$2
      shift 2
      ;;
    --backend-container)
      (($# >= 2)) || die "--backend-container requires a value"
      backend_container=$2
      shift 2
      ;;
    --apply)
      apply=true
      shift
      ;;
    --confirm)
      (($# >= 2)) || die "--confirm requires a value"
      confirm=$2
      shift 2
      ;;
    --expected-state-fingerprint)
      (($# >= 2)) || die "--expected-state-fingerprint requires a value"
      expected_state_fingerprint=$2
      shift 2
      ;;
    --backup-file)
      (($# >= 2)) || die "--backup-file requires a value"
      backup_file=$2
      shift 2
      ;;
    --source-git-sha)
      (($# >= 2)) || die "--source-git-sha requires a value"
      source_git_sha=$2
      shift 2
      ;;
    --executor)
      (($# >= 2)) || die "--executor requires a value"
      executor_name=$2
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      die "unknown argument: $1"
      ;;
  esac
done

[[ -n "$postgres_container" ]] || die "--postgres-container is required"
safe_container_name "$postgres_container" || \
  die "--postgres-container contains unsupported characters"
[[ -r "$SQL_FILE" ]] || die "cleanup SQL is missing: $SQL_FILE"
command -v docker >/dev/null 2>&1 || die "docker is required"

docker inspect "$postgres_container" >/dev/null 2>&1 || \
  die "PostgreSQL container does not exist: $postgres_container"
[[ "$(docker inspect --format '{{.State.Running}}' "$postgres_container")" == true ]] || \
  die "PostgreSQL container is not running: $postgres_container"

backup_sha256=$ZERO_SHA256
backend_image_id="sha256:$ZERO_SHA256"
restore_db=

cleanup_restore() {
  local prior_status=$?
  trap - EXIT
  if [[ -n "$restore_db" ]]; then
    if ! docker exec "$postgres_container" sh -eu -c '
      : "${POSTGRES_USER:?POSTGRES_USER is not set}"
      exec dropdb --username "$POSTGRES_USER" --if-exists --force "$1"
    ' cleanup-code30 "$restore_db" >/dev/null; then
      printf 'REFUSED: could not drop disposable restore database %s\n' "$restore_db" >&2
      prior_status=1
    fi
    restore_db=
  fi
  exit "$prior_status"
}
trap cleanup_restore EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

run_cleanup_sql() {
  local database=$1
  local apply_value=$2
  local expected_fingerprint=$3
  docker exec -i "$postgres_container" sh -eu -c '
    : "${POSTGRES_USER:?POSTGRES_USER is not set in the PostgreSQL container}"
    exec psql -X --no-psqlrc --set=ON_ERROR_STOP=1 --quiet --tuples-only --no-align \
      --username "$POSTGRES_USER" --dbname "$1" \
      --set="cleanup_apply=$2" \
      --set="expected_state_fingerprint=$3" \
      --set="backup_sha256=$4" \
      --set="quarantine_evidence_sha256=$5" \
      --set="source_git_sha=$6" \
      --set="backend_image_id=$7" \
      --set="executor_name=$8"
  ' cleanup-code30 "$database" "$apply_value" "$expected_fingerprint" \
    "$backup_sha256" "$quarantine_evidence_sha256" "$source_git_sha" \
    "$backend_image_id" "$executor_name" < "$SQL_FILE"
}

extract_fingerprint() {
  sed -n 's/.*"state_fingerprint": "\([0-9a-f]\{64\}\)".*/\1/p' | tail -n 1
}

production_db=$(docker exec "$postgres_container" sh -eu -c '
  : "${POSTGRES_DB:?POSTGRES_DB is not set in the PostgreSQL container}"
  printf "%s" "$POSTGRES_DB"
')
[[ -n "$production_db" ]] || die "PostgreSQL container reported an empty database name"

if [[ "$apply" == true ]]; then
  [[ "$confirm" == "$CONFIRMATION" ]] || \
    die "--apply requires --confirm $CONFIRMATION"
  is_sha256 "$expected_state_fingerprint" || \
    die "--apply requires a fresh 64-character lowercase --expected-state-fingerprint"
  [[ "$expected_state_fingerprint" != "$ZERO_SHA256" ]] || \
    die "--expected-state-fingerprint cannot be all zeroes"
  [[ -n "$backup_file" ]] || die "--apply requires --backup-file"
  [[ "$backup_file" == /* ]] || die "--backup-file must be an absolute path"
  [[ -f "$backup_file" && -r "$backup_file" && ! -L "$backup_file" ]] || \
    die "--backup-file must be a readable regular file and not a symlink"
  is_git_sha "$source_git_sha" || \
    die "--apply requires the exact 40-character lowercase --source-git-sha"
  [[ "$executor_name" =~ ^[A-Za-z0-9][A-Za-z0-9._:@/-]{0,99}$ ]] || \
    die "--executor must be 1-100 safe printable characters"
  [[ -n "$backend_container" ]] || die "--apply requires --backend-container"
  safe_container_name "$backend_container" || \
    die "--backend-container contains unsupported characters"

  docker inspect "$backend_container" >/dev/null 2>&1 || \
    die "backend container does not exist: $backend_container"
  [[ "$(docker inspect --format '{{.State.Running}}' "$backend_container")" == false ]] || \
    die "backend container must be stopped before cleanup: $backend_container"

  # Bind the two named containers to the same deployed Compose stack. A
  # stopped container made from the expected image but unrelated to the live
  # PostgreSQL service is not sufficient provenance. Also refuse if a renamed
  # or replacement backend service container in the same stack is still live.
  postgres_compose_project=$(docker inspect --format \
    '{{index .Config.Labels "com.docker.compose.project"}}' \
    "$postgres_container")
  postgres_compose_service=$(docker inspect --format \
    '{{index .Config.Labels "com.docker.compose.service"}}' \
    "$postgres_container")
  backend_compose_project=$(docker inspect --format \
    '{{index .Config.Labels "com.docker.compose.project"}}' \
    "$backend_container")
  backend_compose_service=$(docker inspect --format \
    '{{index .Config.Labels "com.docker.compose.service"}}' \
    "$backend_container")
  [[ "$postgres_compose_project" =~ ^[A-Za-z0-9][A-Za-z0-9_.-]*$ ]] || \
    die "PostgreSQL container has no safe Docker Compose project identity"
  [[ "$postgres_compose_service" == postgres ]] || \
    die "PostgreSQL container is not the canonical Compose postgres service"
  [[ "$backend_compose_project" == "$postgres_compose_project" ]] || \
    die "backend and PostgreSQL containers are not from the same Compose project"
  [[ "$backend_compose_service" == backend ]] || \
    die "backend container is not the canonical Compose backend service"
  running_backend_containers=$(docker ps \
    --filter "label=com.docker.compose.project=$postgres_compose_project" \
    --filter 'label=com.docker.compose.service=backend' \
    --format '{{.ID}}')
  [[ -z "$running_backend_containers" ]] || \
    die "a backend service container from the production Compose project is still running"

  # Bind the executable and SQL bytes to the exact deployed source revision.
  # Image provenance alone is insufficient if an operator runs a modified
  # maintenance script from a dirty or unrelated checkout.
  command -v git >/dev/null 2>&1 || die "git is required for apply source verification"
  [[ -f "$RUNNER_FILE" && ! -L "$RUNNER_FILE" ]] || \
    die "cleanup runner must be a regular non-symlink file"
  [[ -f "$SQL_FILE" && ! -L "$SQL_FILE" ]] || \
    die "cleanup SQL must be a regular non-symlink file"
  [[ -f "$QUARANTINE_EVIDENCE_FILE" && ! -L "$QUARANTINE_EVIDENCE_FILE" ]] || \
    die "quarantine evidence must be a regular non-symlink file"
  repo_root=$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel 2>/dev/null) || \
    die "cleanup runner is not inside a Git checkout"
  repo_root=$(unset CDPATH; cd -- "$repo_root" && pwd -P)
  runner_canonical=$(unset CDPATH; cd -- "$(dirname -- "$RUNNER_FILE")" && \
    printf '%s/%s' "$(pwd -P)" "$(basename -- "$RUNNER_FILE")")
  sql_canonical=$(unset CDPATH; cd -- "$(dirname -- "$SQL_FILE")" && \
    printf '%s/%s' "$(pwd -P)" "$(basename -- "$SQL_FILE")")
  evidence_canonical=$(unset CDPATH; cd -- "$(dirname -- "$QUARANTINE_EVIDENCE_FILE")" && \
    printf '%s/%s' "$(pwd -P)" "$(basename -- "$QUARANTINE_EVIDENCE_FILE")")
  [[ "$runner_canonical" == "$repo_root/infra/scripts/cleanup-code30-production-trial-data.sh" ]] || \
    die "cleanup runner is not the canonical tracked repository script"
  [[ "$sql_canonical" == "$repo_root/infra/scripts/cleanup-code30-production-trial-data.sql" ]] || \
    die "cleanup SQL is not the canonical tracked repository file"
  [[ "$evidence_canonical" == "$repo_root/releases/evidence/code30-2-emulator-quarantine.json" ]] || \
    die "quarantine evidence is not the canonical tracked repository file"
  [[ "$(git -C "$repo_root" rev-parse HEAD)" == "$source_git_sha" ]] || \
    die "cleanup checkout HEAD does not match --source-git-sha"
  [[ -z "$(git -C "$repo_root" status --porcelain=v1 --untracked-files=all)" ]] || \
    die "cleanup checkout must be completely clean before apply"
  for tracked_path in \
    infra/scripts/cleanup-code30-production-trial-data.sh \
    infra/scripts/cleanup-code30-production-trial-data.sql \
    releases/evidence/code30-2-emulator-quarantine.json
  do
    git -C "$repo_root" ls-files --error-unmatch "$tracked_path" >/dev/null 2>&1 || \
      die "cleanup source is not tracked at HEAD: $tracked_path"
    [[ "$(git -C "$repo_root" hash-object "$repo_root/$tracked_path")" == \
       "$(git -C "$repo_root" rev-parse "HEAD:$tracked_path")" ]] || \
      die "cleanup source bytes do not match HEAD: $tracked_path"
  done

  quarantine_evidence_sha256=$(hash_file "$QUARANTINE_EVIDENCE_FILE")
  [[ "$quarantine_evidence_sha256" == "$EXPECTED_QUARANTINE_EVIDENCE_SHA256" ]] || \
    die "canonical quarantine evidence does not match the reviewed SHA-256"

  backend_image_id=$(docker inspect --format '{{.Image}}' "$backend_container")
  [[ "$backend_image_id" =~ ^sha256:[0-9a-f]{64}$ ]] || \
    die "backend container is not bound to an immutable local image ID"
  backend_revision=$(docker image inspect --format \
    '{{index .Config.Labels "org.opencontainers.image.revision"}}' \
    "$backend_image_id")
  [[ "$backend_revision" == "$source_git_sha" ]] || \
    die "backend image revision does not match --source-git-sha"

  backup_sha256=$(hash_file "$backup_file")
  is_sha256 "$backup_sha256" || die "could not compute the backup SHA-256"
  [[ "$backup_sha256" != "$ZERO_SHA256" ]] || die "backup SHA-256 cannot be all zeroes"

  # Validate the archive before creating any disposable database.  pg_restore
  # is the same binary/version that will perform the actual restore.
  docker exec -i "$postgres_container" sh -eu -c \
    'exec pg_restore --list >/dev/null' < "$backup_file" || \
    die "--backup-file is not a valid PostgreSQL custom-format archive"

  # Do not arm the EXIT trap until this process has successfully created the
  # database. If the candidate name already exists, createdb fails and the
  # script must never drop a database it does not own.
  restore_db_candidate="code30_cleanup_restore_${$}_$(date -u +%s)"
  docker exec "$postgres_container" sh -eu -c '
    : "${POSTGRES_USER:?POSTGRES_USER is not set}"
    exec createdb --username "$POSTGRES_USER" --template template0 "$1"
  ' cleanup-code30 "$restore_db_candidate"
  restore_db=$restore_db_candidate

  docker exec -i "$postgres_container" sh -eu -c '
    : "${POSTGRES_USER:?POSTGRES_USER is not set}"
    exec pg_restore --exit-on-error --no-owner --no-privileges \
      --username "$POSTGRES_USER" --dbname "$1"
  ' cleanup-code30 "$restore_db" < "$backup_file"

  [[ "$(hash_file "$backup_file")" == "$backup_sha256" ]] || \
    die "backup file changed while it was being validated and restored"

  restored_revision=$(docker exec "$postgres_container" sh -eu -c '
    : "${POSTGRES_USER:?POSTGRES_USER is not set}"
    exec psql -X --no-psqlrc --tuples-only --no-align --username "$POSTGRES_USER" \
      --dbname "$1" --command "SELECT version_num FROM alembic_version"
  ' cleanup-code30 "$restore_db")
  [[ "$restored_revision" == 0078 ]] || \
    die "restored backup is not at exact database migration 0078"

  restore_result=$(run_cleanup_sql "$restore_db" false '')
  restored_fingerprint=$(printf '%s\n' "$restore_result" | extract_fingerprint)
  is_sha256 "$restored_fingerprint" || \
    die "restored cleanup dry run did not return a valid state fingerprint"
  [[ "$restored_fingerprint" == "$expected_state_fingerprint" ]] || \
    die "restored backup fingerprint does not match the fresh production fingerprint"

  # Drop before touching production.  The EXIT trap remains a fallback for
  # every earlier failure path.
  docker exec "$postgres_container" sh -eu -c '
    : "${POSTGRES_USER:?POSTGRES_USER is not set}"
    exec dropdb --username "$POSTGRES_USER" --if-exists --force "$1"
  ' cleanup-code30 "$restore_db" >/dev/null
  restore_db=

  run_cleanup_sql "$production_db" true "$expected_state_fingerprint"
else
  [[ -z "$confirm$expected_state_fingerprint$backup_file$source_git_sha$executor_name$backend_container" ]] || \
    die "apply evidence and confirmation arguments are accepted only with --apply"
  source_git_sha=$ZERO_GIT_SHA
  executor_name=dry-run
  run_cleanup_sql "$production_db" false ''
fi
