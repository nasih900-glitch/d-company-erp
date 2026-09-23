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
BODY="$SCRIPT_DIR/code30-3-combined-cleanup-body.sql"
APPLY_SQL="$SCRIPT_DIR/apply-code30-3-combined-cleanup.sql"
POSTCHECK_RUNNER="$SCRIPT_DIR/postcheck-code30-3-combined-cleanup.sh"

backup_file= manifest_file= classification_file=
review_file= tablet_file= rehearsal_file= rehearsal_sha_arg= fingerprint_arg= confirmation=
while (($#)); do
  case "$1" in
    --backup-file) backup_file=${2-}; shift 2 ;;
    --operator-reviewed-manifest) manifest_file=${2-}; shift 2 ;;
    --classification-evidence) classification_file=${2-}; shift 2 ;;
    --operator-review-evidence) review_file=${2-}; shift 2 ;;
    --tablet-replay-evidence) tablet_file=${2-}; shift 2 ;;
    --rehearsal-evidence) rehearsal_file=${2-}; shift 2 ;;
    --rehearsal-evidence-sha256) rehearsal_sha_arg=${2-}; shift 2 ;;
    --expected-state-fingerprint) fingerprint_arg=${2-}; shift 2 ;;
    --confirm) confirmation=${2-}; shift 2 ;;
    *) c3c_die "unknown or incomplete argument: $1" ;;
  esac
done
for path in "$backup_file" "$manifest_file" "$classification_file" "$review_file"             "$tablet_file" "$rehearsal_file" "$VALIDATOR" "$BODY" "$APPLY_SQL"             "$POSTCHECK_RUNNER"; do
  c3c_regular_file "$path" || c3c_die "path must be an absolute readable regular non-symlink: $path"
done
[[ "$rehearsal_sha_arg" =~ ^[0-9a-f]{64}$ ]] || c3c_die "rehearsal evidence SHA-256 is invalid"
[[ "$fingerprint_arg" =~ ^[0-9a-f]{64}$ ]] || c3c_die "expected state fingerprint is invalid"
command -v docker >/dev/null 2>&1 || c3c_die "docker is required"
command -v python3 >/dev/null 2>&1 || c3c_die "python3 is required"

python3 "$VALIDATOR" --quiet --classification-evidence "$classification_file"   --operator-review-evidence "$review_file" --tablet-replay-evidence "$tablet_file"   "$manifest_file" || c3c_die "reviewed manifest evidence validation failed"
expected_backup_sha=$(python3 "$VALIDATOR" --quiet --print-field backup_sha256 "$manifest_file")
expected_body_sha=$(python3 "$VALIDATOR" --quiet --print-field maintenance_sql_sha256 "$manifest_file")
expected_source_sha=$(python3 "$VALIDATOR" --quiet --print-field maintenance_source_git_sha "$manifest_file")
expected_fingerprint=$(python3 "$VALIDATOR" --quiet --print-state-fingerprint "$manifest_file")
manifest_sha=$(c3c_hash_file "$manifest_file")
tablet_sha=$(c3c_hash_file "$tablet_file")
apply_sql_sha=$(c3c_hash_file "$APPLY_SQL")
actual_rehearsal_sha=$(c3c_hash_file "$rehearsal_file")
[[ "$(c3c_hash_file "$backup_file")" == "$expected_backup_sha" ]] ||
  c3c_die "fresh backup hash differs from manifest"
[[ "$(c3c_hash_file "$BODY")" == "$expected_body_sha" ]] ||
  c3c_die "shared transaction body hash differs from manifest"
[[ "$actual_rehearsal_sha" == "$rehearsal_sha_arg" ]] ||
  c3c_die "rehearsal evidence file differs from explicitly confirmed hash"
[[ "$expected_fingerprint" == "$fingerprint_arg" ]] ||
  c3c_die "explicit fingerprint differs from reviewed manifest"
expected_confirmation="APPLY_CODE30_3_COMBINED_CLEANUP:$manifest_sha:$actual_rehearsal_sha"
[[ "$confirmation" == "$expected_confirmation" ]] ||
  c3c_die "one-use confirmation does not bind this manifest and rehearsal"

python3 - "$rehearsal_file" "$expected_backup_sha" "$manifest_sha"   "$(c3c_hash_file "$classification_file")" "$(c3c_hash_file "$review_file")"   "$(c3c_hash_file "$tablet_file")" "$expected_body_sha" "$expected_source_sha"   "$expected_fingerprint" "$C3C_TAGGED_APP_SHA" <<'PY'
import json, sys
(path, backup, manifest, classification, review, tablet, body, source, fingerprint, tagged) = sys.argv[1:]
with open(path, encoding="utf-8") as stream:
    evidence = json.load(stream)
expected = {
    "backup_sha256": backup,
    "manifest_sha256": manifest,
    "classification_evidence_sha256": classification,
    "operator_review_evidence_sha256": review,
    "tablet_replay_evidence_sha256": tablet,
    "maintenance_body_sha256": body,
    "maintenance_source_git_sha": source,
    "tagged_app_source_git_sha": tagged,
    "state_fingerprint": fingerprint,
}
if evidence.get("schema_version") != 1 or any(evidence.get(k) != v for k, v in expected.items()):
    raise SystemExit("REFUSED: rehearsal evidence does not bind the apply inputs")
if not isinstance(evidence.get("completed_at"), str) or not evidence.get("cleanup_id"):
    raise SystemExit("REFUSED: rehearsal evidence is incomplete")
PY

c3c_require_clean_ops_checkout "$SCRIPT_DIR" "$expected_source_sha"
c3c_resolve_stopped_runtime

container_dir_candidate="/tmp/code30-combined-apply-${$}-$(date -u +%s)"
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
docker cp "$APPLY_SQL" "$C3C_POSTGRES_CONTAINER:$container_dir/apply-code30-3-combined-cleanup.sql" >/dev/null
docker cp "$BODY" "$C3C_POSTGRES_CONTAINER:$container_dir/code30-3-combined-cleanup-body.sql" >/dev/null
docker cp "$manifest_file" "$C3C_POSTGRES_CONTAINER:$container_dir/manifest.json" >/dev/null
docker cp "$tablet_file" "$C3C_POSTGRES_CONTAINER:$container_dir/tablet-replay.json" >/dev/null
docker exec --user 0 "$C3C_POSTGRES_CONTAINER" sh -eu -c '
  chown -R postgres:postgres "$1"; chmod 0500 "$1"; chmod 0400 "$1"/*
' permissions "$container_dir"
[[ "$(c3c_hash_file "$APPLY_SQL")" == "$apply_sql_sha" &&
   "$(c3c_hash_file "$BODY")" == "$expected_body_sha" &&
   "$(c3c_hash_file "$manifest_file")" == "$manifest_sha" &&
   "$(c3c_hash_file "$tablet_file")" == "$tablet_sha" ]] ||
  c3c_die "a host input changed while staging"
for pair in \
  "$apply_sql_sha:$container_dir/apply-code30-3-combined-cleanup.sql" \
  "$expected_body_sha:$container_dir/code30-3-combined-cleanup-body.sql" \
  "$manifest_sha:$container_dir/manifest.json" \
  "$tablet_sha:$container_dir/tablet-replay.json"; do
  expected=${pair%%:*}; staged_path=${pair#*:}
  actual=$(docker exec "$C3C_POSTGRES_CONTAINER" sha256sum "$staged_path" | awk '{print $1}')
  [[ "$actual" == "$expected" ]] || c3c_die "staged container file hash differs: $staged_path"
done

set +e
apply_output=$(docker exec "$C3C_POSTGRES_CONTAINER" sh -eu -c '
  : "${POSTGRES_USER:?}"
  exec psql -X --no-psqlrc --set=ON_ERROR_STOP=1 --quiet --tuples-only --no-align \
    --username "$POSTGRES_USER" --dbname "$1" \
    --set="expected_database_name=$1" --set="manifest_container_path=$2" \
    --set="tablet_replay_evidence_container_path=$3" --set="backup_sha256=$4" \
    --set="maintenance_sql_sha256=$5" --set="expected_state_fingerprint=$6" \
    --file="$7"
' apply "$C3C_DATABASE" "$container_dir/manifest.json" \
  "$container_dir/tablet-replay.json" "$expected_backup_sha" "$expected_body_sha" \
  "$expected_fingerprint" "$container_dir/apply-code30-3-combined-cleanup.sql" 2>&1)
apply_status=$?
set -e
printf '%s\n' "$apply_output"
if ((apply_status != 0)); then
  cleanup_id=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["cleanup_id"])' "$manifest_file")
  set +e
  receipt_count=$(docker exec "$C3C_POSTGRES_CONTAINER" sh -eu -c '
    : "${POSTGRES_USER:?}"
    printf "%s\n" "SELECT count(*) FROM audit_log WHERE action = '\''verified_trial_cleanup'\'' AND entity_type = '\''TrialCleanupReceipt'\'' AND entity_id = :'\''cleanup_id'\'';" | \
    psql -X --no-psqlrc --set=ON_ERROR_STOP=1 --quiet --tuples-only --no-align \
      --username "$POSTGRES_USER" --dbname "$1" --set="cleanup_id=$2" \
      --file=-
  ' probe "$C3C_DATABASE" "$cleanup_id" 2>/dev/null)
  probe_status=$?
  set -e
  if ((probe_status == 0)) && [[ "$receipt_count" == 0 ]]; then
    c3c_die "cleanup did not commit; the independent receipt probe found no receipt and the atomic transaction is absent"
  fi
  if ((probe_status == 0)) && [[ "$receipt_count" == 1 ]]; then
    c3c_die "cleanup COMMITTED but psql verification failed; keep ingress/backend stopped, restore the fresh backup, and never retry this cleanup"
  fi
  c3c_die "cleanup commit state is UNKNOWN; keep ingress/backend stopped, inspect read-only receipt state, and never retry"
fi
[[ "$apply_output" == *CODE30_3_COMBINED_CLEANUP_COMMITTED* &&
   "$apply_output" == *'"mode": "apply committed"'* ]] ||
  c3c_die "cleanup commit state is UNKNOWN because explicit proof is missing; keep ingress/backend stopped, inspect the receipt read-only, and never retry"

if ! "$POSTCHECK_RUNNER" --operator-reviewed-manifest "$manifest_file" \
    --classification-evidence "$classification_file" --operator-review-evidence "$review_file" \
    --tablet-replay-evidence "$tablet_file"; then
  c3c_die "cleanup COMMITTED but independent postcheck failed; keep ingress/backend stopped, restore the fresh backup, and never retry this cleanup"
fi
printf 'Combined cleanup committed and passed the independent read-only postcheck. Caddy and backend remain stopped.\n'
