#!/usr/bin/env bash
# Shared fail-closed shell helpers for Code30.3 combined cleanup runners.

C3C_TAGGED_APP_SHA=ad5adfb93c3488f1f931ca27da53824aa57d3dc5
C3C_CANONICAL_CHECKOUT=/opt/d-company-erp
C3C_CANONICAL_PROJECT=d-company-erp
C3C_CANONICAL_ENV=/opt/d-company-erp/.env
C3C_SNAPSHOT_ROOT=/var/lib/dcompany-erp/build-snapshots

c3c_die() { printf 'REFUSED: %s\n' "$*" >&2; exit 2; }
c3c_hash_file() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum -- "$1" | awk '{print $1}'
  else shasum -a 256 -- "$1" | awk '{print $1}'; fi
}
c3c_regular_file() { [[ "$1" == /* && -f "$1" && -r "$1" && ! -L "$1" ]]; }
c3c_safe_name() { [[ "$1" =~ ^[a-z0-9][a-z0-9_.-]*$ ]]; }

c3c_require_install_lock() {
  local lock_file=/run/d-company-erp/production-install.lock
  local lock_id=${DCOMPANY_PRODUCTION_INSTALL_LOCK_ID:-}
  local fd_metadata path_metadata
  [[ "$(id -u)" == 0 && "${DCOMPANY_PRODUCTION_INSTALL_LOCK_FD:-}" == 9 &&
     "$lock_id" =~ ^[0-9]+:[0-9]+$ ]] ||
    c3c_die "root-owned production installer lock descriptor is required"
  [[ -d /run/d-company-erp && ! -L /run/d-company-erp &&
     -f "$lock_file" && ! -L "$lock_file" ]] ||
    c3c_die "canonical production installer lock path is absent or linked"
  fd_metadata=$(stat -Lc '%u:%g:%a:%h:%f:%d:%i' "/proc/$$/fd/9" 2>/dev/null) ||
    c3c_die "inherited production installer lock descriptor is unavailable"
  path_metadata=$(stat -Lc '%u:%g:%a:%h:%f:%d:%i' "$lock_file" 2>/dev/null) ||
    c3c_die "canonical production installer lock is unavailable"
  [[ "$fd_metadata" == "0:0:600:1:8180:$lock_id" &&
     "$path_metadata" == "$fd_metadata" ]] ||
    c3c_die "inherited production installer lock failed identity, mode, or path validation"
  flock -n 9 || c3c_die "another production installer or maintenance run holds the lock"
}

c3c_require_no_existing_receipt() {
  local cleanup_id=$1 receipt_count
  c3c_require_install_lock
  receipt_count=$(docker exec "$C3C_POSTGRES_CONTAINER" sh -eu -c '
    : "${POSTGRES_USER:?}"
    printf "%s\n" "SELECT count(*) FROM audit_log WHERE action = '\''verified_trial_cleanup'\'' AND entity_type = '\''TrialCleanupReceipt'\'' AND entity_id = :'\''cleanup_id'\'';" | \
      PGOPTIONS="-c default_transaction_read_only=on" \
      psql -X --no-psqlrc --set=ON_ERROR_STOP=1 --quiet --tuples-only --no-align \
        --username "$POSTGRES_USER" --dbname "$1" --set="cleanup_id=$2" \
        --file=-
  ' preflight "$C3C_DATABASE" "$cleanup_id") ||
    c3c_die "read-only cleanup receipt preflight failed; no cleanup SQL started"
  [[ "$receipt_count" =~ ^[0-9]+$ ]] ||
    c3c_die "read-only cleanup receipt preflight returned an invalid count; no cleanup SQL started"
  [[ "$receipt_count" == 0 ]] ||
    c3c_die "cleanup receipt already exists for this ID; no cleanup SQL started and this cleanup must not be retried"
}

c3c_require_clean_ops_checkout() {
  local script_dir=$1 expected_source=$2
  local path relative actual_blob expected_blob
  C3C_OPS_ROOT=$(git -C "$script_dir" rev-parse --show-toplevel 2>/dev/null) ||
    c3c_die "maintenance scripts are not in Git"
  [[ "$(git -C "$C3C_OPS_ROOT" rev-parse HEAD)" == "$expected_source" ]] ||
    c3c_die "maintenance checkout HEAD differs from reviewed manifest"
  [[ -z "$(git -C "$C3C_OPS_ROOT" status --porcelain=v1 --untracked-files=all)" ]] ||
    c3c_die "maintenance checkout must be completely clean"
  for path in \
    "$script_dir/apply-code30-3-combined-cleanup.sh" \
    "$script_dir/apply-code30-3-combined-cleanup.sql" \
    "$script_dir/code30-3-combined-cleanup-body.sql" \
    "$script_dir/code30-3-combined-cleanup-runtime.sh" \
    "$script_dir/code30-3-combined-cleanup-lock.py" \
    "$script_dir/postcheck-code30-3-combined-cleanup.sh" \
    "$script_dir/postcheck-code30-3-combined-cleanup.sql" \
    "$script_dir/prepare-code30-3-combined-cleanup.py" \
    "$script_dir/rehearse-code30-3-combined-cleanup.sh" \
    "$script_dir/rehearse-code30-3-combined-cleanup.sql"; do
    [[ -f "$path" && ! -L "$path" ]] || c3c_die "protected ops file is absent or linked: $path"
    relative=$(git -C "$C3C_OPS_ROOT" ls-files --full-name "$path")
    [[ -n "$relative" ]] || c3c_die "protected ops file is not tracked: $path"
    actual_blob=$(git hash-object "$path")
    expected_blob=$(git -C "$C3C_OPS_ROOT" rev-parse "HEAD:$relative" 2>/dev/null) ||
      c3c_die "protected ops file is absent from the reviewed commit: $relative"
    [[ "$actual_blob" == "$expected_blob" ]] ||
      c3c_die "protected ops file differs from the reviewed commit: $relative"
  done
}

c3c_resolve_stopped_runtime() {
  [[ -d "$C3C_CANONICAL_CHECKOUT" && ! -L "$C3C_CANONICAL_CHECKOUT" ]] ||
    c3c_die "canonical application checkout is absent or linked"
  c3c_regular_file "$C3C_CANONICAL_ENV" || c3c_die "canonical production env file is absent or linked"
  [[ "$(git -C "$C3C_CANONICAL_CHECKOUT" rev-parse HEAD 2>/dev/null)" == "$C3C_TAGGED_APP_SHA" ]] ||
    c3c_die "canonical application checkout is not the immutable v3.1.30 merge"
  [[ -z "$(git -C "$C3C_CANONICAL_CHECKOUT" status --porcelain=v1 --untracked-files=no)" ]] ||
    c3c_die "canonical application checkout has tracked changes"

  local service ids id label_project label_service working_dir snapshot_dir=
  for service in postgres backend caddy; do
    ids=$(docker ps -aq \
      --filter "label=com.docker.compose.project=$C3C_CANONICAL_PROJECT" \
      --filter "label=com.docker.compose.service=$service") ||
      c3c_die "cannot enumerate canonical $service containers"
    C3C_IDS=()
    while IFS= read -r id; do
      [[ -n "$id" ]] && C3C_IDS+=("$id")
    done <<< "$ids"
    [[ "${#C3C_IDS[@]}" -eq 1 && -n "${C3C_IDS[0]}" ]] ||
      c3c_die "canonical project must have exactly one $service container"
    id=${C3C_IDS[0]}
    label_project=$(docker inspect --format '{{index .Config.Labels "com.docker.compose.project"}}' "$id")
    label_service=$(docker inspect --format '{{index .Config.Labels "com.docker.compose.service"}}' "$id")
    working_dir=$(docker inspect --format '{{index .Config.Labels "com.docker.compose.project.working_dir"}}' "$id")
    [[ "$label_project" == "$C3C_CANONICAL_PROJECT" && "$label_service" == "$service" ]] ||
      c3c_die "$service container labels do not identify the canonical project"
    if [[ -z "$snapshot_dir" ]]; then
      snapshot_dir=$working_dir
    elif [[ "$working_dir" != "$snapshot_dir" ]]; then
      c3c_die "canonical service containers disagree on the frozen snapshot directory"
    fi
    case "$service" in
      postgres) C3C_POSTGRES_CONTAINER=$id ;;
      backend) C3C_BACKEND_CONTAINER=$id ;;
      caddy) C3C_CADDY_CONTAINER=$id ;;
    esac
  done
  [[ "$snapshot_dir" =~ ^${C3C_SNAPSHOT_ROOT}/${C3C_TAGGED_APP_SHA}\.[A-Za-z0-9]+$ ]] ||
    c3c_die "canonical containers do not use the tagged frozen release snapshot"
  [[ -d "$snapshot_dir" && ! -L "$snapshot_dir" && ! -e "$snapshot_dir/.git" ]] ||
    c3c_die "frozen release snapshot is absent, linked, or contains Git metadata"
  [[ "$(stat -Lc '%u:%g:%a:%F' "$snapshot_dir")" == "0:0:700:directory" ]] ||
    c3c_die "frozen release snapshot is not root-owned mode 0700"
  C3C_PROJECT_DIR=$snapshot_dir
  C3C_COMPOSE_FILE="$C3C_PROJECT_DIR/docker-compose.prod.yml"
  c3c_regular_file "$C3C_COMPOSE_FILE" || c3c_die "frozen production Compose file is absent or linked"
  local compose_actual compose_expected
  compose_actual=$(git hash-object "$C3C_COMPOSE_FILE")
  compose_expected=$(git -C "$C3C_CANONICAL_CHECKOUT" rev-parse "HEAD:docker-compose.prod.yml") ||
    c3c_die "tagged checkout has no production Compose file"
  [[ "$compose_actual" == "$compose_expected" ]] ||
    c3c_die "frozen production Compose bytes differ from the tagged checkout"
  C3C_COMPOSE=(docker compose -p "$C3C_CANONICAL_PROJECT" --project-directory "$C3C_PROJECT_DIR"
               -f "$C3C_COMPOSE_FILE" --env-file "$C3C_CANONICAL_ENV")
  [[ "$(docker inspect --format '{{.State.Running}}' "$C3C_POSTGRES_CONTAINER")" == true ]] ||
    c3c_die "canonical PostgreSQL must be running"
  [[ "$(docker inspect --format '{{.State.Running}}' "$C3C_BACKEND_CONTAINER")" == false ]] ||
    c3c_die "backend must be stopped"
  [[ "$(docker inspect --format '{{.State.Running}}' "$C3C_CADDY_CONTAINER")" == false ]] ||
    c3c_die "Caddy ingress must be stopped"
  [[ -z "$(docker ps -q --filter "label=com.docker.compose.project=$C3C_CANONICAL_PROJECT" \
       --filter 'label=com.docker.compose.service=backend')" &&
     -z "$(docker ps -q --filter "label=com.docker.compose.project=$C3C_CANONICAL_PROJECT" \
       --filter 'label=com.docker.compose.service=caddy')" ]] ||
    c3c_die "a canonical backend or Caddy container is still running"
  local backend_image revision
  backend_image=$(docker inspect --format '{{.Image}}' "$C3C_BACKEND_CONTAINER")
  revision=$(docker image inspect --format '{{index .Config.Labels "org.opencontainers.image.revision"}}' "$backend_image")
  [[ "$revision" == "$C3C_TAGGED_APP_SHA" ]] ||
    c3c_die "backend image revision is not the immutable v3.1.30 merge"
  C3C_DATABASE=$(docker inspect --format '{{range .Config.Env}}{{println .}}{{end}}'     "$C3C_POSTGRES_CONTAINER" | sed -n 's/^POSTGRES_DB=//p')
  [[ "$C3C_DATABASE" == erp ]] || c3c_die "canonical production database must be erp"
}
