#!/bin/bash
# D Company ERP — one-command production installer for an Ubuntu 22.04 VM
# (Oracle Cloud Always Free, AWS, GCP, DigitalOcean, anywhere with Docker support).
#
# Run this AFTER you've:
#   1. SSH'd into your fresh Ubuntu VM
#   2. Cloned (or scp'd) this repo to /opt/d-company-erp/
#   3. Pointed your domain's A record at this VM's public IP
#
# Then:
#   cd /opt/d-company-erp
#   sudo bash infra/scripts/install-on-vm.sh yourdomain.com
# Existing installs additionally require: --maintenance-confirmed. Historical
# Code14 and Code16 installs whose image labels predate full-SHA provenance also
# require their exact, one-time legacy revision flag documented below.
#
# What it does:
#   1. Installs Docker + docker compose plugin
#   2. Opens ports 80 and 443 in Ubuntu's iptables (Oracle gotcha)
#   3. Generates or safely backfills strong secrets via generate-secrets.sh
#   4. Sets the DOMAIN in .env
#   5. Brings up the prod stack (Caddy auto-issues Let's Encrypt cert)
#   6. Runs migrations + seed scripts
#   7. Prints the URL and a credential-location handoff without exposing secrets
#
# Total time: ~5 minutes after DNS has propagated.

set -euo pipefail

INSTALLER_ARGS=("$@")

if [ "$EUID" -ne 0 ]; then
  echo "Run with sudo: sudo bash $0 yourdomain.com"
  exit 1
fi

DOMAIN="${1:-}"
if [ -z "$DOMAIN" ]; then
  echo "Usage: sudo bash $0 yourdomain.com [--maintenance-confirmed]" >&2
  echo "(the domain you bought and pointed at this VM's IP)"
  exit 1
fi
shift

MAINTENANCE_CONFIRMED=false
LEGACY_CODE14_REVISION=""
LEGACY_CODE16_REVISION=""
KNOWN_CODE14_REVISION="e5e90df5781e93681b8e9dcdd1ae9a6a5fb6a0b9"
KNOWN_CODE14_SHORT_REVISION="e5e90df"
KNOWN_CODE16_REVISION="2ac3fc88e4ce14d0f05d049b443a6a09c387a78a"
while [ "$#" -gt 0 ]; do
  case "$1" in
    --maintenance-confirmed)
      MAINTENANCE_CONFIRMED=true
      shift
      ;;
    --legacy-code16-revision)
      if [ "$#" -lt 2 ]; then
        echo "--legacy-code16-revision requires the exact 40-character revision." >&2
        exit 1
      fi
      LEGACY_CODE16_REVISION=$2
      shift 2
      ;;
    --legacy-code14-revision)
      if [ "$#" -lt 2 ]; then
        echo "--legacy-code14-revision requires the exact 40-character revision." >&2
        exit 1
      fi
      LEGACY_CODE14_REVISION=$2
      shift 2
      ;;
    *)
      echo "Unknown installer option: $1" >&2
      exit 1
      ;;
  esac
done
if [ -n "$LEGACY_CODE14_REVISION" ] && [ -n "$LEGACY_CODE16_REVISION" ]; then
  echo "Only one legacy revision bridge may be selected." >&2
  exit 1
fi
if [ "${#DOMAIN}" -gt 253 ] || \
   ! [[ "$DOMAIN" =~ ^([A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?\.)+[A-Za-z]{2,63}$ ]]; then
  echo "Domain must be a plain DNS hostname (for example erp.example.com)." >&2
  exit 1
fi
DOMAIN=$(printf '%s' "$DOMAIN" | tr '[:upper:]' '[:lower:]')

SCRIPT_SOURCE_ROOT=$(cd "$(dirname "$0")/../.." && pwd -P)
if [ -n "${DCOMPANY_PRODUCTION_REPO_DIR:-}" ]; then
  REPO_DIR=$(cd "$DCOMPANY_PRODUCTION_REPO_DIR" && pwd -P)
else
  REPO_DIR=$SCRIPT_SOURCE_ROOT
fi
cd "$REPO_DIR"
ROLLBACK_ROOT=/var/lib/dcompany-erp/deployment-rollbacks
BUILD_SNAPSHOT_ROOT=/var/lib/dcompany-erp/build-snapshots
SECURITY_EVIDENCE_ROOT=/var/lib/dcompany-erp/container-security
SYFT_IMAGE='anchore/syft:v1.42.3@sha256:5999d209a342e55e9edf70bf8930fb5b86d8f2a783fa401178372c50e21b1d36'
GRYPE_IMAGE='anchore/grype:v0.118.0@sha256:8a93fc48da96bd6ec5981279d099b69de11541dc68fdf222fb9161f8ff284af7'
umask 077
# Bash redirections cannot request O_NOFOLLOW. A small reviewed bootstrap opens
# /run and the lock dir relative to verified descriptors, opens the file with
# O_NOFOLLOW and no truncation, locks it, then re-execs this installer with fd 9.
if [ "${DCOMPANY_PRODUCTION_INSTALL_LOCK_FD:-}" != 9 ]; then
  if ! command -v python3 >/dev/null 2>&1; then
    echo "python3 is required for secure production installer locking." >&2
    exit 1
  fi
  exec python3 "$SCRIPT_SOURCE_ROOT/infra/scripts/production_install_lock.py" \
    "$SCRIPT_SOURCE_ROOT/infra/scripts/install-on-vm.sh" "${INSTALLER_ARGS[@]}"
fi
if ! [[ "${DCOMPANY_PRODUCTION_INSTALL_LOCK_ID:-}" =~ ^[0-9]+:[0-9]+$ ]]; then
  echo "Production lock descriptor identity is missing." >&2
  exit 1
fi
# GNU stat's raw mode is independent of file contents and localized type names;
# 8180 is the exact Linux mode for a regular file with permissions 0600.
lock_fd_metadata=$(stat -Lc '%u:%g:%a:%h:%f:%d:%i' "/proc/$$/fd/9")
expected_lock_fd_metadata="0:0:600:1:8180:${DCOMPANY_PRODUCTION_INSTALL_LOCK_ID}"
if [ "$lock_fd_metadata" != "$expected_lock_fd_metadata" ]; then
  echo "Production lock descriptor failed ownership/type validation." >&2
  exit 1
fi
if ! flock -n 9; then
  echo "Another D Company production install or upgrade is already running." >&2
  exit 1
fi
CURRENT_REVISION=$(git rev-parse HEAD 2>/dev/null || true)
if ! [[ "$CURRENT_REVISION" =~ ^[0-9a-f]{40}$ ]] || \
   [ -n "$(git status --porcelain --untracked-files=normal)" ]; then
  echo "Production install requires a clean Git checkout at an exact release commit." >&2
  exit 1
fi

# Freeze every privileged release input before Docker, Compose, environment
# preparation, cutover, or parity verification runs. Bash can read a script
# lazily, so merely pointing selected commands at a git archive is not enough:
# re-exec the installer itself from the root-private snapshot as well.
CANDIDATE_BUILD_ROOT=${DCOMPANY_PRODUCTION_RELEASE_SNAPSHOT:-}
CANDIDATE_SOURCE_ARCHIVE_SHA256=${DCOMPANY_PRODUCTION_SOURCE_ARCHIVE_SHA256:-}
if [ -z "$CANDIDATE_BUILD_ROOT" ]; then
  CANDIDATE_BUILD_ROOT=$(mktemp -d \
    "$BUILD_SNAPSHOT_ROOT/${CURRENT_REVISION}.XXXXXX")
  chmod 700 "$CANDIDATE_BUILD_ROOT"
  CANDIDATE_SOURCE_ARCHIVE="$CANDIDATE_BUILD_ROOT/source.tar"
  git archive --format=tar "$CURRENT_REVISION" > "$CANDIDATE_SOURCE_ARCHIVE"
  if [ ! -s "$CANDIDATE_SOURCE_ARCHIVE" ]; then
    rm -rf "$CANDIDATE_BUILD_ROOT"
    echo "Immutable candidate source archive is empty." >&2
    exit 1
  fi
  CANDIDATE_SOURCE_ARCHIVE_SHA256=$(sha256sum "$CANDIDATE_SOURCE_ARCHIVE" \
    | awk '{print $1}')
  tar -tf "$CANDIDATE_SOURCE_ARCHIVE" >/dev/null
  tar -xf "$CANDIDATE_SOURCE_ARCHIVE" -C "$CANDIDATE_BUILD_ROOT"
  rm -f "$CANDIDATE_SOURCE_ARCHIVE"
  test ! -e "$CANDIDATE_BUILD_ROOT/.git"
  test -f "$CANDIDATE_BUILD_ROOT/infra/scripts/install-on-vm.sh"
  export DCOMPANY_PRODUCTION_REPO_DIR="$REPO_DIR"
  export DCOMPANY_PRODUCTION_RELEASE_SNAPSHOT="$CANDIDATE_BUILD_ROOT"
  export DCOMPANY_PRODUCTION_RELEASE_REVISION="$CURRENT_REVISION"
  export DCOMPANY_PRODUCTION_SOURCE_ARCHIVE_SHA256="$CANDIDATE_SOURCE_ARCHIVE_SHA256"
  exec /bin/bash "$CANDIDATE_BUILD_ROOT/infra/scripts/install-on-vm.sh" \
    "${INSTALLER_ARGS[@]}"
fi
if [ "$SCRIPT_SOURCE_ROOT" != "$CANDIDATE_BUILD_ROOT" ] || \
   [ "${DCOMPANY_PRODUCTION_RELEASE_REVISION:-}" != "$CURRENT_REVISION" ] || \
   ! [[ "$CANDIDATE_SOURCE_ARCHIVE_SHA256" =~ ^[0-9a-f]{64}$ ]] || \
   [ "$(stat -Lc '%u:%g:%a:%F' "$CANDIDATE_BUILD_ROOT")" \
     != "0:0:700:directory" ]; then
  echo "Frozen production release snapshot failed identity validation." >&2
  exit 1
fi
RELEASE_COMPOSE_FILE="$CANDIDATE_BUILD_ROOT/docker-compose.prod.yml"
CANDIDATE_PARITY_TOOL="$CANDIDATE_BUILD_ROOT/ops/runtime_release_parity.py"
PREPARE_ENV_TOOL="$CANDIDATE_BUILD_ROOT/infra/scripts/prepare-production-env.sh"
CAPACITY_CHECK_TOOL="$CANDIDATE_BUILD_ROOT/infra/scripts/check-upgrade-capacity.sh"
HARDENED_SCANNER_TOOL="$CANDIDATE_BUILD_ROOT/infra/scripts/run-hardened-image-scanners.sh"
for release_input in \
  "$RELEASE_COMPOSE_FILE" "$CANDIDATE_PARITY_TOOL" \
  "$PREPARE_ENV_TOOL" "$CAPACITY_CHECK_TOOL" "$HARDENED_SCANNER_TOOL"; do
  if [ ! -f "$release_input" ] || [ -L "$release_input" ]; then
    echo "Frozen production release snapshot is incomplete or linked." >&2
    exit 1
  fi
done
if [ -f .env ] && [ "$MAINTENANCE_CONFIRMED" != true ]; then
  echo "Upgrade requires a scheduled write outage." >&2
  echo "Stop staff activity, sync every tablet outbox, then rerun with --maintenance-confirmed." >&2
  exit 1
fi
if [ ! -f .env ] && { [ "$MAINTENANCE_CONFIRMED" = true ] || \
   [ -n "$LEGACY_CODE14_REVISION" ] || [ -n "$LEGACY_CODE16_REVISION" ]; }; then
  echo "Maintenance and legacy-upgrade flags are valid only for an existing installation." >&2
  exit 1
fi
if { [ -n "$LEGACY_CODE14_REVISION" ] || [ -n "$LEGACY_CODE16_REVISION" ]; } && \
   [ "$MAINTENANCE_CONFIRMED" != true ]; then
  echo "Legacy deployment verification requires --maintenance-confirmed." >&2
  exit 1
fi
echo "=== D Company ERP — production install ==="
echo "Domain:    $DOMAIN"
echo "Repo dir:  $REPO_DIR"
echo

# ----- 1. Docker -----
if ! command -v docker >/dev/null 2>&1; then
  echo "==> Installing Docker…"
  apt-get update -qq
  apt-get install -y -qq ca-certificates curl gnupg lsb-release
  install -m 0755 -d /etc/apt/keyrings
  curl -fsSL https://download.docker.com/linux/ubuntu/gpg | gpg --dearmor -o /etc/apt/keyrings/docker.gpg
  chmod a+r /etc/apt/keyrings/docker.gpg
  echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] https://download.docker.com/linux/ubuntu $(lsb_release -cs) stable" \
    > /etc/apt/sources.list.d/docker.list
  apt-get update -qq
  apt-get install -y -qq docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
  systemctl enable --now docker
fi
echo "==> Docker version: $(docker --version)"

# Read-only discovery uses the frozen Compose contract while retaining the
# historical checkout-derived project identity. No service is created by this
# array. Candidate runtime commands below use an explicit attested project name
# and the immutable snapshot as their project directory.
inspection_compose=(
  docker compose --project-directory "$REPO_DIR"
  -f "$RELEASE_COMPOSE_FILE"
)

# Prove and protect the currently deployed release before any environment or
# running-service change. A real upgrade must have one running Postgres and
# backend, an exact database head, a source-verified immutable backend image,
# the prior Compose file, and durable rollback tags for every service image.
UPGRADE_SNAPSHOT=""
PRIOR_SOURCE_ROOT=""
EXISTING_POSTGRES_CONTAINER=""
EXISTING_BACKEND_CONTAINER=""
PRIOR_PROJECT_NAME=""
PRIOR_DB_HEAD=""
ROLLBACK_STATE_MANIFEST_DIGEST=""
DATABASE_BACKUP_SHA256=""
CONTAINER_IDS=()
RUNNING_CONTAINER_IDS=()
EXPECTED_SERVICES=()
PRIOR_INTERNAL_SERVICES=()
LEGACY_MINIO_CONTAINER=""
declare -A EXISTING_CONTAINER_BY_SERVICE=()
declare -A PRIOR_SERVICE_IMAGE_IDS=()
declare -A PRIOR_SERVICE_ORIGINAL_REFS=()
declare -A PRIOR_SERVICE_ROLLBACK_REFS=()
declare -A PRIOR_IMAGE_BY_ORIGINAL_REF=()
if [ -f .env ]; then
  if ! services_output=$("${inspection_compose[@]}" --env-file .env \
    config --services 2>/dev/null); then
    echo "Cannot enumerate existing Compose services; refusing upgrade." >&2
    exit 1
  fi
  while IFS= read -r service; do
    [ -z "$service" ] && continue
    if ! [[ "$service" =~ ^[A-Za-z0-9_.-]+$ ]] || \
       [ -n "${EXISTING_CONTAINER_BY_SERVICE[$service]+present}" ]; then
      echo "Compose returned an unsafe or duplicate service identity." >&2
      exit 1
    fi
    if ! service_ids_output=$("${inspection_compose[@]}" \
      --env-file .env ps -aq "$service" 2>/dev/null); then
      echo "Cannot enumerate existing $service containers; refusing upgrade." >&2
      exit 1
    fi
    SERVICE_IDS=()
    while IFS= read -r container_id; do
      [ -n "$container_id" ] && SERVICE_IDS+=("$container_id")
    done <<< "$service_ids_output"
    if [ "${#SERVICE_IDS[@]}" -ne 1 ]; then
      echo "Expected exactly one existing $service container; found ${#SERVICE_IDS[@]}. Refusing upgrade." >&2
      exit 1
    fi
    container_id=${SERVICE_IDS[0]}
    EXPECTED_SERVICES+=("$service")
    CONTAINER_IDS+=("$container_id")
    EXISTING_CONTAINER_BY_SERVICE[$service]=$container_id
    if [ "$(docker inspect --format '{{.State.Running}}' "$container_id")" = true ]; then
      RUNNING_CONTAINER_IDS+=("$container_id")
    fi
  done <<< "$services_output"
  if [ "${#EXPECTED_SERVICES[@]}" -eq 0 ]; then
    echo "Existing .env has no Compose services; refusing upgrade." >&2
    exit 1
  fi
  if [ -z "${EXISTING_CONTAINER_BY_SERVICE[postgres]+present}" ]; then
    echo "Existing Compose project has no Postgres service; refusing upgrade." >&2
    exit 1
  fi
  EXISTING_POSTGRES_CONTAINER=${EXISTING_CONTAINER_BY_SERVICE[postgres]}
  if [ "$(docker inspect --format '{{.State.Running}}' "$EXISTING_POSTGRES_CONTAINER")" != true ]; then
    echo "Existing Postgres is not running; recover it and back it up before upgrade." >&2
    exit 1
  fi

  if [ -z "${EXISTING_CONTAINER_BY_SERVICE[backend]+present}" ]; then
    echo "Existing Compose project has no backend service; refusing upgrade." >&2
    exit 1
  fi
  EXISTING_BACKEND_CONTAINER=${EXISTING_CONTAINER_BY_SERVICE[backend]}
  if [ "$(docker inspect --format '{{.State.Running}}' "$EXISTING_BACKEND_CONTAINER")" != true ]; then
    echo "Existing backend is not running; recover it before upgrade." >&2
    exit 1
  fi
  PRIOR_PROJECT_NAME=$(docker inspect \
    --format '{{index .Config.Labels "com.docker.compose.project"}}' \
    "$EXISTING_BACKEND_CONTAINER")
  if ! [[ "$PRIOR_PROJECT_NAME" =~ ^[a-z0-9][a-z0-9_.-]*$ ]]; then
    echo "Running backend has no safe Compose project identity; refusing upgrade." >&2
    exit 1
  fi

  # Code25 retires the unused MinIO runtime, but an upgrade from an older
  # release may still have exactly one MinIO container in the same Compose
  # project. Capture it explicitly for rollback before removing it later; the
  # Code25 Compose file intentionally no longer enumerates this service.
  legacy_minio_output=$(docker ps -aq \
    --filter "label=com.docker.compose.project=$PRIOR_PROJECT_NAME" \
    --filter "label=com.docker.compose.service=minio")
  LEGACY_MINIO_IDS=()
  while IFS= read -r container_id; do
    [ -n "$container_id" ] && LEGACY_MINIO_IDS+=("$container_id")
  done <<< "$legacy_minio_output"
  if [ "${#LEGACY_MINIO_IDS[@]}" -gt 1 ]; then
    echo "Expected at most one legacy MinIO container; found ${#LEGACY_MINIO_IDS[@]}. Refusing upgrade." >&2
    exit 1
  fi
  if [ "${#LEGACY_MINIO_IDS[@]}" -eq 1 ]; then
    LEGACY_MINIO_CONTAINER=${LEGACY_MINIO_IDS[0]}
    if [ -n "${EXISTING_CONTAINER_BY_SERVICE[minio]+present}" ]; then
      echo "Legacy MinIO was already enumerated by the Code25 Compose contract." >&2
      exit 1
    fi
    EXPECTED_SERVICES+=(minio)
    CONTAINER_IDS+=("$LEGACY_MINIO_CONTAINER")
    EXISTING_CONTAINER_BY_SERVICE[minio]=$LEGACY_MINIO_CONTAINER
    if [ "$(docker inspect --format '{{.State.Running}}' "$LEGACY_MINIO_CONTAINER")" = true ]; then
      RUNNING_CONTAINER_IDS+=("$LEGACY_MINIO_CONTAINER")
    fi
  fi
  for service in postgres redis minio backend frontend; do
    if [ -n "${EXISTING_CONTAINER_BY_SERVICE[$service]+present}" ]; then
      PRIOR_INTERNAL_SERVICES+=("$service")
    fi
  done

  PRIOR_DB_HEAD=$(docker exec "$EXISTING_POSTGRES_CONTAINER" \
    psql -U erp -d erp -Atc "SELECT version_num FROM alembic_version")
  if ! [[ "$PRIOR_DB_HEAD" =~ ^[0-9]{4}$ ]]; then
    echo "Existing database must have exactly one four-digit Alembic head." >&2
    exit 1
  fi

  PRIOR_BACKEND_IMAGE=$(docker inspect --format '{{.Image}}' "$EXISTING_BACKEND_CONTAINER")
  IMAGE_REVISION=$(docker image inspect \
    --format '{{index .Config.Labels "org.opencontainers.image.revision"}}' \
    "$PRIOR_BACKEND_IMAGE")
  if [[ "$IMAGE_REVISION" =~ ^[0-9a-f]{40}$ ]]; then
    if [ -n "$LEGACY_CODE14_REVISION" ] || [ -n "$LEGACY_CODE16_REVISION" ]; then
      echo "A legacy override is not allowed for an image with an exact revision label." >&2
      exit 1
    fi
    PRIOR_REVISION=$IMAGE_REVISION
  elif [ -n "$LEGACY_CODE14_REVISION" ]; then
    if [ "$LEGACY_CODE14_REVISION" != "$KNOWN_CODE14_REVISION" ] || \
       [ "$IMAGE_REVISION" != "$KNOWN_CODE14_SHORT_REVISION" ] || \
       [ "$PRIOR_DB_HEAD" != 0058 ]; then
      echo "The historical Code14 short-label bridge requires:" >&2
      echo "  --legacy-code14-revision $KNOWN_CODE14_REVISION" >&2
      echo "the exact image label $KNOWN_CODE14_SHORT_REVISION and database head 0058." >&2
      exit 1
    fi
    image_version=$(docker image inspect \
      --format '{{index .Config.Labels "org.opencontainers.image.version"}}' \
      "$PRIOR_BACKEND_IMAGE")
    if [ "$image_version" != 3.1.3 ]; then
      echo "Legacy Code14 bridge requires the immutable backend image version 3.1.3." >&2
      exit 1
    fi
    PRIOR_REVISION=$LEGACY_CODE14_REVISION
  else
    if [ "$LEGACY_CODE16_REVISION" != "$KNOWN_CODE16_REVISION" ] || \
       [ "$PRIOR_DB_HEAD" != 0060 ]; then
      echo "The historical Code16 placeholder-label bridge requires:" >&2
      echo "  --legacy-code16-revision $KNOWN_CODE16_REVISION" >&2
      echo "and an existing database at exact head 0060." >&2
      exit 1
    fi
    case "$IMAGE_REVISION" in
      CHANGE_ME_git_commit_sha|unknown|"") ;;
      *)
        echo "Unexpected malformed image revision label; refusing legacy override." >&2
        exit 1
        ;;
    esac
    image_version=$(docker image inspect \
      --format '{{index .Config.Labels "org.opencontainers.image.version"}}' \
      "$PRIOR_BACKEND_IMAGE")
    if [ "$image_version" != 3.1.5 ]; then
      echo "Legacy bridge requires the immutable Code16 backend image version 3.1.5." >&2
      exit 1
    fi
    PRIOR_REVISION=$LEGACY_CODE16_REVISION
  fi
  if ! git cat-file -e "$PRIOR_REVISION^{commit}" 2>/dev/null; then
    echo "Prior release commit is not locally available; fetch it before retrying." >&2
    exit 1
  fi

  SNAPSHOT_STAMP=$(date -u +%Y%m%dt%H%M%Sz)
  UPGRADE_SNAPSHOT=$(mktemp -d \
    "$ROLLBACK_ROOT/pre-code25-${SNAPSHOT_STAMP}.XXXXXX")
  chmod 700 "$UPGRADE_SNAPSHOT"
  SNAPSHOT_ID=$(basename "$UPGRADE_SNAPSHOT")
  install -m 0600 .env "$UPGRADE_SNAPSHOT/.env"
  PRIOR_SOURCE_ROOT="$UPGRADE_SNAPSHOT/prior-source"
  mkdir -m 0700 "$PRIOR_SOURCE_ROOT"
  PRIOR_SOURCE_ARCHIVE="$UPGRADE_SNAPSHOT/prior-source.tar"
  if ! git archive --format=tar "$PRIOR_REVISION" > "$PRIOR_SOURCE_ARCHIVE" || \
     [ ! -s "$PRIOR_SOURCE_ARCHIVE" ]; then
    echo "Cannot freeze the proven prior release source." >&2
    exit 1
  fi
  PRIOR_SOURCE_ARCHIVE_SHA256=$(sha256sum "$PRIOR_SOURCE_ARCHIVE" | awk '{print $1}')
  tar -tf "$PRIOR_SOURCE_ARCHIVE" >/dev/null
  tar -xf "$PRIOR_SOURCE_ARCHIVE" -C "$PRIOR_SOURCE_ROOT"
  chmod 400 "$PRIOR_SOURCE_ARCHIVE"
  if [ ! -f "$PRIOR_SOURCE_ROOT/docker-compose.prod.yml" ] || \
     [ -L "$PRIOR_SOURCE_ROOT/docker-compose.prod.yml" ]; then
    echo "Frozen prior release has no safe production Compose contract." >&2
    exit 1
  fi
  install -m 0600 "$PRIOR_SOURCE_ROOT/docker-compose.prod.yml" \
    "$UPGRADE_SNAPSHOT/docker-compose.prior.yml"
  chmod 600 "$UPGRADE_SNAPSHOT/docker-compose.prior.yml"
  # Code14/Code16 used a relative ./releases/android Caddy bind. Rollback now
  # executes their Compose file from a protected source snapshot, so that old
  # relative bind would otherwise serve the archive's README directory rather
  # than the live immutable APK store. This checksummed override replaces the
  # volume by its unique container target while leaving every executable prior
  # release input frozen.
  cat > "$UPGRADE_SNAPSHOT/docker-compose.rollback-live-data.yml" <<'YAML'
services:
  caddy:
    volumes:
      - type: bind
        source: "${ANDROID_RELEASE_ROOT:?ANDROID_RELEASE_ROOT is required}"
        target: /srv/releases/android
        read_only: true
YAML
  chmod 600 "$UPGRADE_SNAPSHOT/docker-compose.rollback-live-data.yml"

  # The normal Code16 installer wrote a placeholder revision label. For both
  # that bridge and correctly labelled releases, attest a stopped container
  # created from the immutable image. Never execute code from the writable
  # running container being attested.
  SOURCE_MANIFEST="$UPGRADE_SNAPSHOT/backend-source.sha256"
  EXPECTED_SOURCE_PATHS="$UPGRADE_SNAPSHOT/backend-source.paths"
  while IFS= read -r source_path; do
    source_hash=$(git show "$PRIOR_REVISION:$source_path" | sha256sum | awk '{print $1}')
    relative_path=${source_path#backend/}
    printf '%s\t%s\n' "$source_hash" "$relative_path" \
      >> "$SOURCE_MANIFEST"
    printf '%s\n' "$relative_path" >> "$EXPECTED_SOURCE_PATHS"
  done < <(git ls-tree -r --name-only "$PRIOR_REVISION" backend)
  entrypoint_hash=$(git show "$PRIOR_REVISION:infra/docker/backend-entrypoint.sh" \
    | sha256sum | awk '{print $1}')
  chmod 600 "$SOURCE_MANIFEST" "$EXPECTED_SOURCE_PATHS"
  IMAGE_VERIFY_ROOT=$(mktemp -d "$UPGRADE_SNAPSHOT/.image-verify.XXXXXX")
  chmod 700 "$IMAGE_VERIFY_ROOT"
  IMAGE_VERIFY_CONTAINER=$(docker create "$PRIOR_BACKEND_IMAGE")
  if ! docker cp "$IMAGE_VERIFY_CONTAINER:/app/." "$IMAGE_VERIFY_ROOT/app" || \
     ! docker cp "$IMAGE_VERIFY_CONTAINER:/entrypoint.sh" \
       "$IMAGE_VERIFY_ROOT/entrypoint.sh"; then
    docker rm "$IMAGE_VERIFY_CONTAINER" >/dev/null 2>&1 || true
    rm -rf "$IMAGE_VERIFY_ROOT"
    echo "Cannot export the immutable prior backend image for verification." >&2
    exit 1
  fi
  docker rm "$IMAGE_VERIFY_CONTAINER" >/dev/null
  ACTUAL_SOURCE_PATHS="$IMAGE_VERIFY_ROOT/actual.paths"
  find "$IMAGE_VERIFY_ROOT/app" -type f -printf '%P\n' | LC_ALL=C sort \
    > "$ACTUAL_SOURCE_PATHS"
  LC_ALL=C sort -o "$EXPECTED_SOURCE_PATHS" "$EXPECTED_SOURCE_PATHS"
  if [ -n "$(find "$IMAGE_VERIFY_ROOT/app" -type l -print -quit)" ] || \
     ! cmp -s "$EXPECTED_SOURCE_PATHS" "$ACTUAL_SOURCE_PATHS"; then
    rm -rf "$IMAGE_VERIFY_ROOT"
    echo "Immutable backend image contains missing, extra, or linked source files." >&2
    exit 1
  fi
  source_mismatch=false
  while IFS=$'\t' read -r expected_hash relative_path; do
    actual_hash=$(sha256sum "$IMAGE_VERIFY_ROOT/app/$relative_path" | awk '{print $1}')
    if [ "$actual_hash" != "$expected_hash" ]; then
      source_mismatch=true
      break
    fi
  done < "$SOURCE_MANIFEST"
  actual_entrypoint_hash=$(sha256sum "$IMAGE_VERIFY_ROOT/entrypoint.sh" | awk '{print $1}')
  rm -rf "$IMAGE_VERIFY_ROOT"
  if [ "$source_mismatch" = true ] || [ "$actual_entrypoint_hash" != "$entrypoint_hash" ]; then
    echo "Immutable backend image contents do not match the selected prior commit." >&2
    exit 1
  fi
  SOURCE_MANIFEST_DIGEST=$(sha256sum "$SOURCE_MANIFEST" | awk '{print $1}')

  {
    printf 'prior_revision=%s\n' "$PRIOR_REVISION"
    printf 'prior_database_head=%s\n' "$PRIOR_DB_HEAD"
    printf 'prior_compose_project=%s\n' "$PRIOR_PROJECT_NAME"
    printf 'prior_backend_image_id=%s\n' "$PRIOR_BACKEND_IMAGE"
    printf 'backend_source_manifest_sha256=%s\n' "$SOURCE_MANIFEST_DIGEST"
    printf 'prior_source_archive_sha256=%s\n' "$PRIOR_SOURCE_ARCHIVE_SHA256"
    printf 'installer_checkout=%s\n' "$CURRENT_REVISION"
  } > "$UPGRADE_SNAPSHOT/revision.txt"
  : > "$UPGRADE_SNAPSHOT/container-images.txt"
  for container_id in "${CONTAINER_IDS[@]}"; do
    service=$(docker inspect --format '{{index .Config.Labels "com.docker.compose.service"}}' "$container_id")
    original_ref=$(docker inspect --format '{{.Config.Image}}' "$container_id")
    image_id=$(docker inspect --format '{{.Image}}' "$container_id")
    image_revision=$(docker image inspect --format '{{index .Config.Labels "org.opencontainers.image.revision"}}' "$image_id")
    if ! [[ "$service" =~ ^[A-Za-z0-9_.-]+$ ]] || \
       ! [[ "$original_ref" =~ ^[A-Za-z0-9][A-Za-z0-9._/:@-]{0,254}$ ]] || \
       ! [[ "$image_id" =~ ^sha256:[0-9a-f]{64}$ ]]; then
      echo "Cannot establish rollback identity for container $container_id." >&2
      exit 1
    fi
    if [ "${EXISTING_CONTAINER_BY_SERVICE[$service]:-}" != "$container_id" ]; then
      echo "Rollback container identity does not match the unique Compose service." >&2
      exit 1
    fi
    if [ -n "${PRIOR_IMAGE_BY_ORIGINAL_REF[$original_ref]+present}" ] && \
       [ "${PRIOR_IMAGE_BY_ORIGINAL_REF[$original_ref]}" != "$image_id" ]; then
      echo "Rollback image reference $original_ref resolves to multiple prior images." >&2
      exit 1
    fi
    PRIOR_IMAGE_BY_ORIGINAL_REF[$original_ref]=$image_id
    PRIOR_SERVICE_IMAGE_IDS[$service]=$image_id
    PRIOR_SERVICE_ORIGINAL_REFS[$service]=$original_ref
    image_short=${image_id#sha256:}
    image_short=${image_short:0:12}
    rollback_ref="dcompany-rollback:${SNAPSHOT_ID}-${service}-${image_short}"
    if docker image inspect "$rollback_ref" >/dev/null 2>&1; then
      echo "Unique rollback snapshot tag already exists: $rollback_ref" >&2
      exit 1
    fi
    docker image tag "$image_id" "$rollback_ref"
    tagged_image_id=$(docker image inspect --format '{{.Id}}' "$rollback_ref")
    if [ "$tagged_image_id" != "$image_id" ]; then
      echo "Rollback tag does not resolve to its recorded immutable image." >&2
      exit 1
    fi
    PRIOR_SERVICE_ROLLBACK_REFS[$service]=$rollback_ref
    printf '%s|%s|%s|%s|%s\n' \
      "$service" "$original_ref" "$image_id" "$image_revision" "$rollback_ref" \
      >> "$UPGRADE_SNAPSHOT/container-images.txt"
  done
  chmod 600 "$UPGRADE_SNAPSHOT/revision.txt" "$UPGRADE_SNAPSHOT/container-images.txt"
  ROLLBACK_STATE_MANIFEST="$UPGRADE_SNAPSHOT/rollback-state.sha256"
  (
    cd "$UPGRADE_SNAPSHOT"
    sha256sum .env docker-compose.prior.yml \
      docker-compose.rollback-live-data.yml revision.txt container-images.txt \
      backend-source.sha256 backend-source.paths prior-source.tar
  ) > "$ROLLBACK_STATE_MANIFEST"
  chmod 400 "$ROLLBACK_STATE_MANIFEST"
  ROLLBACK_STATE_MANIFEST_DIGEST=$(sha256sum "$ROLLBACK_STATE_MANIFEST" \
    | awk '{print $1}')
  echo "==> Protected and source-verified prior deployment: $UPGRADE_SNAPSHOT"
else
  inferred_project_name=$(basename "$REPO_DIR" \
    | tr '[:upper:]' '[:lower:]' \
    | sed -E 's/[^a-z0-9_.-]//g; s/^[^a-z0-9]+//')
  if ! [[ "$inferred_project_name" =~ ^[a-z0-9][a-z0-9_.-]*$ ]]; then
    echo "Cannot derive a safe Compose project identity from the repository path." >&2
    exit 1
  fi
  existing_project_containers=$(docker ps -aq \
    --filter "label=com.docker.compose.project=$inferred_project_name")
  retained_volumes=$(docker volume ls -q \
    --filter "label=com.docker.compose.project=$inferred_project_name")
  retained_networks=$(docker network ls -q \
    --filter "label=com.docker.compose.project=$inferred_project_name")
  rollback_evidence=$(find "$ROLLBACK_ROOT" "$REPO_DIR/.deployment-rollbacks" \
    -mindepth 1 -maxdepth 1 -print -quit 2>/dev/null || true)
  env_backup_evidence=$(find . -maxdepth 1 -type f -name '.env.pre-code17.*' -print -quit)
  if [ -n "$existing_project_containers" ] || [ -n "$retained_volumes" ] || \
     [ -n "$retained_networks" ] || [ -n "$rollback_evidence" ] || \
     [ -n "$env_backup_evidence" ]; then
    echo "Persistent deployment evidence exists but .env is missing." >&2
    echo "Refusing a fresh secret reset; recover the prior .env through the incident runbook." >&2
    exit 1
  fi
fi

if [ -n "$PRIOR_PROJECT_NAME" ]; then
  DEPLOY_PROJECT_NAME=$PRIOR_PROJECT_NAME
else
  DEPLOY_PROJECT_NAME=$inferred_project_name
fi
# This is the one intentionally mutable bind: signed APKs are operational
# release data staged by the independently locked publisher. Code, Compose,
# Caddy configuration, Dockerfiles, and verification tools stay in the frozen
# snapshot for the entire lifetime of the deployed containers.
ANDROID_RELEASE_ROOT="$REPO_DIR/releases/android"
export ANDROID_RELEASE_ROOT
candidate_compose=(
  docker compose -p "$DEPLOY_PROJECT_NAME"
  --project-directory "$CANDIDATE_BUILD_ROOT"
  -f "$RELEASE_COMPOSE_FILE"
)
printf -v OPERATIONS_COMPOSE_COMMAND \
  'docker compose -p %q --project-directory %q -f %q --env-file %q' \
  "$DEPLOY_PROJECT_NAME" "$CANDIDATE_BUILD_ROOT" \
  "$RELEASE_COMPOSE_FILE" "$REPO_DIR/.env"

# ----- 2. Firewall (Oracle Cloud's gotcha) -----
# Oracle Linux/Ubuntu images ship with an iptables INPUT REJECT rule that
# blocks ports the Oracle Security List opens. Fix:
echo "==> Opening ports 80 + 443 in iptables (Oracle Cloud fix)…"
iptables -I INPUT 6 -p tcp -m state --state NEW -m tcp --dport 80  -j ACCEPT  || true
iptables -I INPUT 6 -p tcp -m state --state NEW -m tcp --dport 443 -j ACCEPT  || true
iptables -I INPUT 6 -p udp -m udp --dport 443 -j ACCEPT                       || true
# Persist iptables rules across reboots.
apt-get install -y -qq iptables-persistent
netfilter-persistent save || true

# Also UFW if it's active (Ubuntu default).
if command -v ufw >/dev/null && ufw status | grep -q "Status: active"; then
  ufw allow 80/tcp
  ufw allow 443/tcp
  ufw allow 22/tcp
fi

# ----- 3. Generate/backfill and validate a candidate environment -----
FRESH_INSTALL=false
if [ ! -f .env ]; then
  FRESH_INSTALL=true
  echo "==> Generating production .env with strong secrets…"
else
  echo "==> Checking existing production .env for required release secrets…"
fi
# Always run this idempotent step. It fills only missing/empty/placeholders,
# never overwrites a real value. The live .env is not touched until every
# security/Compose preflight and required database backup succeeds.
ENV_CANDIDATE="$REPO_DIR/.env.code17.candidate.$$"
VERIFY_DATABASE=""
if [ -e "$ENV_CANDIDATE" ]; then
  echo "Refusing to overwrite unexpected candidate file: $ENV_CANDIDATE" >&2
  exit 1
fi
PROMOTION_COMPLETE=false
handle_install_failure() {
  failure_code=$?
  trap - EXIT
  set +e
  if [ -n "$ENV_CANDIDATE" ]; then
    rm -f "$ENV_CANDIDATE"
  fi
  if [ -n "$VERIFY_DATABASE" ] && [ -n "$EXISTING_POSTGRES_CONTAINER" ]; then
    docker exec "$EXISTING_POSTGRES_CONTAINER" \
      dropdb -U erp --if-exists "$VERIFY_DATABASE" >/dev/null 2>&1 || true
  fi
  if [ "$PROMOTION_COMPLETE" = true ] && [ -n "$UPGRADE_SNAPSHOT" ]; then
    echo "==> Release acceptance failed; restoring the quiesced prior release." >&2
    # Stop every candidate writer/runtime, including a partially started
    # PostgreSQL container. Rollback below recreates PostgreSQL from the
    # attested prior image before it touches the logical backup.
    "${candidate_compose[@]}" --env-file .env \
      stop -t 30 caddy backend frontend redis postgres >/dev/null 2>&1 || true
    if [ -f .env ]; then
      install -m 0600 .env "$UPGRADE_SNAPSHOT/.env.failed-code17"
    fi
    rollback_succeeded=true
    if ! [[ "$ROLLBACK_STATE_MANIFEST_DIGEST" =~ ^[0-9a-f]{64}$ ]] || \
       [ "$(sha256sum "$UPGRADE_SNAPSHOT/rollback-state.sha256" 2>/dev/null \
          | awk '{print $1}')" != "$ROLLBACK_STATE_MANIFEST_DIGEST" ] || \
       ! (cd "$UPGRADE_SNAPSHOT" && \
          sha256sum -c rollback-state.sha256 >/dev/null 2>&1); then
      rollback_succeeded=false
    fi
    if [ "$rollback_succeeded" = true ] && \
       { ! [[ "$DATABASE_BACKUP_SHA256" =~ ^[0-9a-f]{64}$ ]] || \
         [ ! -s "$UPGRADE_SNAPSHOT/database.dump" ] || \
         [ "$(sha256sum "$UPGRADE_SNAPSHOT/database.dump" 2>/dev/null \
            | awk '{print $1}')" != "$DATABASE_BACKUP_SHA256" ] || \
         ! sha256sum -c "$UPGRADE_SNAPSHOT/database.dump.sha256" \
           >/dev/null 2>&1; }; then
      rollback_succeeded=false
    fi

    # Reconstruct rollback code/config from the checksummed prior commit tar,
    # rather than trusting either the current checkout or a long-lived
    # extracted tree at the time of the incident.
    if [ "$rollback_succeeded" = true ]; then
      PRIOR_RUNTIME_SOURCE_ROOT="$UPGRADE_SNAPSHOT/prior-runtime-source.$$"
      if [ -e "$PRIOR_RUNTIME_SOURCE_ROOT" ] || \
         ! mkdir -m 0700 "$PRIOR_RUNTIME_SOURCE_ROOT" || \
         ! tar -tf "$UPGRADE_SNAPSHOT/prior-source.tar" >/dev/null || \
         ! tar -xf "$UPGRADE_SNAPSHOT/prior-source.tar" \
           -C "$PRIOR_RUNTIME_SOURCE_ROOT" || \
         [ ! -f "$PRIOR_RUNTIME_SOURCE_ROOT/docker-compose.prod.yml" ] || \
         [ -L "$PRIOR_RUNTIME_SOURCE_ROOT/docker-compose.prod.yml" ]; then
        rollback_succeeded=false
      else
        PRIOR_SOURCE_ROOT=$PRIOR_RUNTIME_SOURCE_ROOT
      fi
    fi

    # Restore the prior environment only from in-memory-attested rollback
    # state. Image restoration is performed from validated arrays captured by
    # this process; no script from the filesystem is executed during failure
    # handling. A candidate PostgreSQL failure therefore cannot substitute the
    # known-good rollback runtime or its Compose contract.
    if [ "$rollback_succeeded" = true ]; then
      install -m 0600 "$UPGRADE_SNAPSHOT/.env" "$REPO_DIR/.env.rollback.$$"
      mv "$REPO_DIR/.env.rollback.$$" .env
      for service in "${EXPECTED_SERVICES[@]}"; do
        prior_image_id=${PRIOR_SERVICE_IMAGE_IDS[$service]:-}
        prior_original_ref=${PRIOR_SERVICE_ORIGINAL_REFS[$service]:-}
        prior_rollback_ref=${PRIOR_SERVICE_ROLLBACK_REFS[$service]:-}
        if ! [[ "$prior_image_id" =~ ^sha256:[0-9a-f]{64}$ ]] || \
           ! [[ "$prior_original_ref" =~ ^[A-Za-z0-9][A-Za-z0-9._/:@-]{0,254}$ ]] || \
           ! [[ "$prior_rollback_ref" =~ ^dcompany-rollback:[A-Za-z0-9_.-]+$ ]]; then
          rollback_succeeded=false
          break
        fi
        if ! rollback_image_id=$(docker image inspect --format '{{.Id}}' \
          "$prior_rollback_ref" 2>/dev/null) || \
           [ "$rollback_image_id" != "$prior_image_id" ]; then
          rollback_succeeded=false
          break
        fi
        # Docker cannot create a mutable tag whose target itself contains an
        # @sha256 digest. Digest references are already immutable; mutable
        # application names are restored directly from the exact image ID.
        if [[ "$prior_original_ref" != *@sha256:* ]] && \
           ! docker image tag "$prior_image_id" "$prior_original_ref"; then
          rollback_succeeded=false
          break
        fi
        if ! restored_original_id=$(docker image inspect --format '{{.Id}}' \
          "$prior_original_ref" 2>/dev/null) || \
           [ "$restored_original_id" != "$prior_image_id" ]; then
          rollback_succeeded=false
          break
        fi
      done
    fi
    if [ "$rollback_succeeded" = true ]; then
      prior_compose=(
        docker compose -p "$PRIOR_PROJECT_NAME" --project-directory "$PRIOR_SOURCE_ROOT"
        -f "$UPGRADE_SNAPSHOT/docker-compose.prior.yml"
        -f "$UPGRADE_SNAPSHOT/docker-compose.rollback-live-data.yml"
        --env-file .env
      )
      "${prior_compose[@]}" up -d --no-build --pull never \
        --force-recreate postgres \
        || rollback_succeeded=false
    fi
    if [ "$rollback_succeeded" = true ]; then
      if ! rollback_postgres_output=$("${prior_compose[@]}" ps -q postgres 2>/dev/null); then
        rollback_postgres_output=""
      fi
      ROLLBACK_POSTGRES_IDS=()
      while IFS= read -r container_id; do
        [ -n "$container_id" ] && ROLLBACK_POSTGRES_IDS+=("$container_id")
      done <<< "$rollback_postgres_output"
      if [ "${#ROLLBACK_POSTGRES_IDS[@]}" -ne 1 ]; then
        rollback_succeeded=false
      else
        rollback_postgres=${ROLLBACK_POSTGRES_IDS[0]}
        restored_postgres_image_id=$(docker inspect --format '{{.Image}}' \
          "$rollback_postgres")
        if [ "$restored_postgres_image_id" != "${PRIOR_SERVICE_IMAGE_IDS[postgres]}" ]; then
          rollback_succeeded=false
        fi
      fi
    fi
    if [ "$rollback_succeeded" = true ]; then
      rollback_postgres_attempts=0
      until docker exec "$rollback_postgres" pg_isready -U erp -d postgres \
        >/dev/null 2>&1; do
        rollback_postgres_attempts=$((rollback_postgres_attempts + 1))
        if [ "$rollback_postgres_attempts" -gt 120 ]; then
          rollback_succeeded=false
          break
        fi
        sleep 1
      done
    fi
    if [ "$rollback_succeeded" = true ]; then
      docker exec "$rollback_postgres" psql -U erp -d postgres \
        -v ON_ERROR_STOP=1 -c \
        "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = 'erp' AND pid <> pg_backend_pid()" \
        >/dev/null || rollback_succeeded=false
      if [ "$rollback_succeeded" = true ]; then
        docker exec "$rollback_postgres" dropdb -U erp --if-exists erp \
          >/dev/null || rollback_succeeded=false
        docker exec "$rollback_postgres" createdb -U erp -T template0 erp \
          >/dev/null || rollback_succeeded=false
        docker exec -i "$rollback_postgres" pg_restore -U erp -d erp \
          --exit-on-error --no-owner < "$UPGRADE_SNAPSHOT/database.dump" \
          >/dev/null || rollback_succeeded=false
        restored_head=$(docker exec "$rollback_postgres" \
          psql -U erp -d erp -Atc "SELECT version_num FROM alembic_version" \
          2>/dev/null || true)
        if [ "$restored_head" != "$PRIOR_DB_HEAD" ]; then
          rollback_succeeded=false
        fi
      fi
    fi
    if [ "$rollback_succeeded" = true ]; then
      PRIOR_NON_POSTGRES_SERVICES=()
      for service in "${PRIOR_INTERNAL_SERVICES[@]}"; do
        case "$service" in
          postgres|caddy) continue ;;
          *) PRIOR_NON_POSTGRES_SERVICES+=("$service") ;;
        esac
      done
      if [ "${#PRIOR_NON_POSTGRES_SERVICES[@]}" -gt 0 ]; then
        "${prior_compose[@]}" up -d --no-build --pull never --force-recreate \
          "${PRIOR_NON_POSTGRES_SERVICES[@]}" || rollback_succeeded=false
      fi
    fi
    if [ "$rollback_succeeded" = true ]; then
      for service in "${EXPECTED_SERVICES[@]}"; do
        # Keep public ingress closed until the restored backend passes its
        # readiness probe. Caddy is started and attested separately below.
        [ "$service" = caddy ] && continue
        if ! restored_ids_output=$("${prior_compose[@]}" ps -q "$service" 2>/dev/null); then
          rollback_succeeded=false
          break
        fi
        RESTORED_IDS=()
        while IFS= read -r container_id; do
          [ -n "$container_id" ] && RESTORED_IDS+=("$container_id")
        done <<< "$restored_ids_output"
        if [ "${#RESTORED_IDS[@]}" -ne 1 ]; then
          rollback_succeeded=false
          break
        fi
        restored_image_id=$(docker inspect --format '{{.Image}}' "${RESTORED_IDS[0]}")
        if [ "$restored_image_id" != "${PRIOR_SERVICE_IMAGE_IDS[$service]}" ]; then
          rollback_succeeded=false
          break
        fi
      done
    fi
    if [ "$rollback_succeeded" = true ]; then
      rollback_attempts=0
      until "${prior_compose[@]}" exec -T backend python -c \
        "import urllib.request; urllib.request.urlopen('http://localhost:8000/readyz', timeout=2).read()" \
        >/dev/null 2>&1; do
        rollback_attempts=$((rollback_attempts + 1))
        if [ "$rollback_attempts" -gt 120 ]; then
          rollback_succeeded=false
          break
        fi
        sleep 1
      done
    fi
    if [ "$rollback_succeeded" = true ]; then
      "${prior_compose[@]}" up -d --no-build --pull never \
        --force-recreate caddy \
        || rollback_succeeded=false
    fi
    if [ "$rollback_succeeded" = true ]; then
      if ! restored_caddy_output=$("${prior_compose[@]}" ps -q caddy 2>/dev/null); then
        rollback_succeeded=false
      else
        RESTORED_CADDY_IDS=()
        while IFS= read -r container_id; do
          [ -n "$container_id" ] && RESTORED_CADDY_IDS+=("$container_id")
        done <<< "$restored_caddy_output"
        if [ "${#RESTORED_CADDY_IDS[@]}" -ne 1 ]; then
          rollback_succeeded=false
        else
          restored_caddy_image_id=$(docker inspect --format '{{.Image}}' \
            "${RESTORED_CADDY_IDS[0]}")
          if [ "$restored_caddy_image_id" != "${PRIOR_SERVICE_IMAGE_IDS[caddy]:-}" ]; then
            rollback_succeeded=false
          fi
        fi
      fi
    fi
    if [ "$rollback_succeeded" = true ]; then
      echo "==> Prior release and final quiesced database backup restored." >&2
      echo "    Investigate the failed release before another scheduled upgrade." >&2
    else
      "${candidate_compose[@]}" --env-file .env stop caddy \
        >/dev/null 2>&1 || true
      echo "AUTOMATIC ROLLBACK FAILED; public ingress remains closed." >&2
      echo "Use the protected evidence at $UPGRADE_SNAPSHOT and the incident runbook." >&2
    fi
  elif [ "$PROMOTION_COMPLETE" = true ]; then
    "${candidate_compose[@]}" --env-file .env stop caddy \
      >/dev/null 2>&1 || true
    echo "Fresh-install acceptance failed; public ingress remains closed." >&2
  elif [ "${MAINTENANCE_ACTIVE:-false}" = true ]; then
    echo "==> Pre-promotion failure: restarting the unchanged prior containers." >&2
    for prior_container in "${RUNNING_CONTAINER_IDS[@]}"; do
      docker start "$prior_container" >/dev/null || true
    done
  fi
  exit "$failure_code"
}
handle_post_ingress_failure() {
  failure_code=$?
  trap - EXIT
  set +e
  "${candidate_compose[@]}" --env-file .env stop -t 30 caddy \
    >/dev/null 2>&1 || true
  echo "Release public-ingress acceptance failed; Caddy is stopped." >&2
  echo "The current database is preserved and the quiesced dump was NOT restored." >&2
  echo "Use the recovery runbook and protected evidence at $UPGRADE_SNAPSHOT." >&2
  exit "$failure_code"
}
trap handle_install_failure EXIT
MAINTENANCE_ACTIVE=false
if [ -f .env ]; then
  SOURCE_ENV="$REPO_DIR/.env"
else
  SOURCE_ENV=-
fi
bash "$PREPARE_ENV_TOOL" \
  "$SOURCE_ENV" "$ENV_CANDIDATE" "$DOMAIN" "$CURRENT_REVISION"

echo
echo "==> Candidate environment ready. Non-secret values:"
grep -E "^(DOMAIN|SEED_OWNER_EMAIL|ENV)=" "$ENV_CANDIDATE"

echo
echo "==> Running fail-closed environment and Compose preflight…"
"${candidate_compose[@]}" --env-file "$ENV_CANDIDATE" config --quiet
echo "==> Production config preflight passed; no Compose service has been recreated."

# Redis is the only production service supplied directly by an upstream image
# rather than built from this release snapshot. Resolve its exact reference
# from the frozen Compose contract, require a digest pin, and fetch/record the
# platform-specific immutable image ID before the maintenance window. `--pull
# never` at cutover then cannot depend on an incidental image left by the prior
# deployment (which is especially important for first installs).
CANDIDATE_REDIS_IMAGE_REF=$(
  "${candidate_compose[@]}" --env-file "$ENV_CANDIDATE" config --format json \
    | python3 -c \
      'import json,sys; print(json.load(sys.stdin)["services"]["redis"]["image"])'
)
if ! [[ "$CANDIDATE_REDIS_IMAGE_REF" =~ ^redis:7-alpine@sha256:[0-9a-f]{64}$ ]]; then
  echo "Candidate Redis image must be the reviewed digest-pinned Redis 7 Alpine image." >&2
  exit 1
fi
docker pull "$CANDIDATE_REDIS_IMAGE_REF" >/dev/null
CANDIDATE_REDIS_IMAGE_ID=$(docker image inspect --format '{{.Id}}' \
  "$CANDIDATE_REDIS_IMAGE_REF")
if ! [[ "$CANDIDATE_REDIS_IMAGE_ID" =~ ^sha256:[0-9a-f]{64}$ ]]; then
  echo "Candidate Redis pull returned no immutable image ID." >&2
  exit 1
fi
echo "==> Exact digest-pinned Redis image is present before maintenance."
if [ -n "$UPGRADE_SNAPSHOT" ]; then
  echo "    Rollback evidence remains at: $UPGRADE_SNAPSHOT"
  echo "==> Proving existing DNS/TLS/readiness before the maintenance window…"
  curl -fsS "https://$DOMAIN/readyz" >/dev/null
fi

echo
echo "==> Building candidate images while the existing release remains live…"
# The process, Compose contract, Docker contexts, Caddy configuration, and
# verification tools all come from the same snapshot created before step 1.
# This production host has limited memory. Build one image at a time; the Go
# Dockerfiles also constrain compiler workers and memory internally.
for candidate_service in caddy postgres backend frontend; do
  COMPOSE_PARALLEL_LIMIT=1 "${candidate_compose[@]}" \
    --env-file "$ENV_CANDIDATE" build "$candidate_service"
done

# Compose build arguments are release identity, not decoration. Prove the
# resulting images carry the exact candidate version and checkout revision
# before stopping writers or promoting the environment.
CANDIDATE_APP_VERSION=$(grep '^APP_VERSION=' "$ENV_CANDIDATE" | cut -d= -f2-)
CANDIDATE_IMAGE_ATTESTATION=$(python3 "$CANDIDATE_PARITY_TOOL" candidate \
  --root "$CANDIDATE_BUILD_ROOT" --env-file "$ENV_CANDIDATE" \
  --version-name "$CANDIDATE_APP_VERSION" --source-git-sha "$CURRENT_REVISION" \
  --project-name "$DEPLOY_PROJECT_NAME")
echo "==> Candidate Caddy/PostgreSQL/backend/frontend identities verified."
echo "==> Immutable candidate source archive: $CANDIDATE_SOURCE_ARCHIVE_SHA256"

# Fetch the exact scanner images before entering the helper's bounded Docker
# create lifecycle. A cold registry transfer must not consume that deadline.
timeout --foreground --signal TERM --kill-after=30s 600s \
  docker pull "$SYFT_IMAGE" >/dev/null
timeout --foreground --signal TERM --kill-after=30s 600s \
  docker pull "$GRYPE_IMAGE" >/dev/null

# Scan the exact locally built image IDs before maintenance begins. CI scans
# the same reviewed source and every digest-pinned infrastructure image, while
# this gate closes the remaining build-host parity boundary for the four images
# that the production VM itself will run.
SECURITY_EVIDENCE_DIR=$(mktemp -d \
  "$SECURITY_EVIDENCE_ROOT/${CURRENT_REVISION}.XXXXXX")
chmod 700 "$SECURITY_EVIDENCE_DIR"
printf '%s\n' "$CANDIDATE_IMAGE_ATTESTATION" \
  > "$SECURITY_EVIDENCE_DIR/candidate-images.json"
printf 'source_git_sha=%s\ncandidate_source_archive_sha256=%s\napp_version=%s\nsyft_image=%s\ngrype_image=%s\nscanned_at_utc=%s\n' \
  "$CURRENT_REVISION" "$CANDIDATE_SOURCE_ARCHIVE_SHA256" \
  "$CANDIDATE_APP_VERSION" "$SYFT_IMAGE" "$GRYPE_IMAGE" \
  "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
  > "$SECURITY_EVIDENCE_DIR/scan-metadata.txt"
printf 'redis_image_ref=%s\nredis_image_id=%s\n' \
  "$CANDIDATE_REDIS_IMAGE_REF" "$CANDIDATE_REDIS_IMAGE_ID" \
  >> "$SECURITY_EVIDENCE_DIR/scan-metadata.txt"
SCANNER_WORK_ROOT="$SECURITY_EVIDENCE_DIR/scanner-work"
mkdir "$SCANNER_WORK_ROOT"
chmod 700 "$SCANNER_WORK_ROOT"
SCANNER_ARGUMENTS=()
CANDIDATE_SCAN_SERVICES=()
CANDIDATE_SCAN_IMAGE_REFS=()
CANDIDATE_SCAN_IMAGE_IDS=()
CANDIDATE_SCAN_ARCHIVES=()
CANDIDATE_SCANNER_PROBE_IMAGE_ID=""
for candidate_service in caddy postgres backend frontend; do
  candidate_image_ref=$(python3 -c \
    'import json,sys; print(json.loads(sys.argv[1])["services"][sys.argv[2]]["image_ref"])' \
    "$CANDIDATE_IMAGE_ATTESTATION" "$candidate_service")
  candidate_image_id=$(python3 -c \
    'import json,sys; print(json.loads(sys.argv[1])["services"][sys.argv[2]]["image_id"])' \
    "$CANDIDATE_IMAGE_ATTESTATION" "$candidate_service")
  if ! [[ "$candidate_image_ref" =~ ^[A-Za-z0-9][A-Za-z0-9._/:@-]{0,254}$ ]] || \
     ! [[ "$candidate_image_id" =~ ^sha256:[0-9a-f]{64}$ ]] || \
     [ "$(docker image inspect --format '{{.Id}}' "$candidate_image_ref")" != "$candidate_image_id" ]; then
    echo "Candidate $candidate_service image changed before security scanning." >&2
    exit 1
  fi
  image_archive="$SECURITY_EVIDENCE_DIR/$candidate_service-image.tar"
  docker image save "$candidate_image_id" --output "$image_archive"
  test -s "$image_archive"
  chmod 0444 "$image_archive"
  image_archive_sha256=$(sha256sum "$image_archive" | awk '{print $1}')
  printf '%s_image_id=%s\n%s_archive_sha256=%s\n' \
    "$candidate_service" "$candidate_image_id" \
    "$candidate_service" "$image_archive_sha256" \
    >> "$SECURITY_EVIDENCE_DIR/scan-metadata.txt"
  CANDIDATE_SCAN_SERVICES+=("$candidate_service")
  CANDIDATE_SCAN_IMAGE_REFS+=("$candidate_image_ref")
  CANDIDATE_SCAN_IMAGE_IDS+=("$candidate_image_id")
  CANDIDATE_SCAN_ARCHIVES+=("$image_archive")
  SCANNER_ARGUMENTS+=(
    "$candidate_service" "$candidate_image_id" "$image_archive"
    "$SECURITY_EVIDENCE_DIR/$candidate_service.syft.json"
    "$SECURITY_EVIDENCE_DIR/$candidate_service-grype.json"
  )
  if [ "$candidate_service" = backend ]; then
    CANDIDATE_SCANNER_PROBE_IMAGE_ID=$candidate_image_id
  fi
done
if [ -z "$CANDIDATE_SCANNER_PROBE_IMAGE_ID" ]; then
  echo "Candidate backend image is unavailable for scanner mount verification." >&2
  exit 1
fi
bash "$HARDENED_SCANNER_TOOL" \
  "$SCANNER_WORK_ROOT" "$SECURITY_EVIDENCE_DIR/scan-metadata.txt" \
  "$SYFT_IMAGE" "$GRYPE_IMAGE" "$CANDIDATE_SCANNER_PROBE_IMAGE_ID" \
  "${SCANNER_ARGUMENTS[@]}"
for index in "${!CANDIDATE_SCAN_SERVICES[@]}"; do
  candidate_service=${CANDIDATE_SCAN_SERVICES[$index]}
  candidate_image_ref=${CANDIDATE_SCAN_IMAGE_REFS[$index]}
  candidate_image_id=${CANDIDATE_SCAN_IMAGE_IDS[$index]}
  image_archive=${CANDIDATE_SCAN_ARCHIVES[$index]}
  if [ "$(docker image inspect --format '{{.Id}}' "$candidate_image_ref")" != "$candidate_image_id" ]; then
    echo "Candidate $candidate_service tag changed during security scanning." >&2
    exit 1
  fi
  rm -f "$image_archive"
done
if [ -n "$(find "$SCANNER_WORK_ROOT" -mindepth 1 -print -quit)" ]; then
  echo "Scanner runtime cleanup left unexpected private state." >&2
  exit 1
fi
rmdir "$SCANNER_WORK_ROOT"
chmod 600 "$SECURITY_EVIDENCE_DIR"/*
echo "==> Exact candidate image SBOM/CVE gate passed: $SECURITY_EVIDENCE_DIR"
if [ -n "$EXISTING_POSTGRES_CONTAINER" ]; then
  database_size_bytes=$(docker exec "$EXISTING_POSTGRES_CONTAINER" \
    psql -U erp -d erp -Atc "SELECT pg_database_size('erp')")
  snapshot_free_kib=$(df -Pk "$UPGRADE_SNAPSHOT" | awk 'END {print $4}')
  postgres_free_kib=$(docker exec "$EXISTING_POSTGRES_CONTAINER" \
    sh -c "df -Pk /var/lib/postgresql/data | awk 'END {print \$4}'")
  bash "$CAPACITY_CHECK_TOOL" \
    "$database_size_bytes" "$snapshot_free_kib" "$postgres_free_kib"
fi

# Fail before stopping any writer if the exact Redis image resolved above is
# no longer available under the frozen digest reference. CI scans this same
# digest-pinned upstream image; locally built release images are scanned above.
if [ "$(docker image inspect --format '{{.Id}}' \
  "$CANDIDATE_REDIS_IMAGE_REF")" != "$CANDIDATE_REDIS_IMAGE_ID" ]; then
  echo "Candidate Redis image changed or disappeared before maintenance." >&2
  exit 1
fi

# A deployed database gets one final verified snapshot only after ingress and
# writers are stopped. This prevents post-backup payments/shifts from being
# silently lost if the dump becomes the rollback source.
if [ -n "$EXISTING_POSTGRES_CONTAINER" ]; then
  if [ "$(docker inspect --format '{{.State.Running}}' "$EXISTING_POSTGRES_CONTAINER")" != true ]; then
    echo "Existing Postgres is not running; recover it and take a backup before upgrade." >&2
    exit 1
  fi
  echo "==> Entering scheduled maintenance and draining application writers…"
  MAINTENANCE_ACTIVE=true
  "${candidate_compose[@]}" --env-file .env stop -t 30 caddy
  "${candidate_compose[@]}" --env-file .env stop -t 60 backend
  "${candidate_compose[@]}" --env-file .env stop -t 30 frontend
  pending_outbox_count=$(docker exec "$EXISTING_POSTGRES_CONTAINER" \
    psql -U erp -d erp -Atc \
    "SELECT COALESCE(SUM(pending_outbox_count), 0) FROM client_installations")
  if ! [[ "$pending_outbox_count" =~ ^[0-9]+$ ]] || [ "$pending_outbox_count" -ne 0 ]; then
    echo "Reported tablet outboxes are not fully drained; refusing migration." >&2
    exit 1
  fi
  echo "==> Creating final quiesced pre-upgrade PostgreSQL backup…"
  DATABASE_BACKUP="$UPGRADE_SNAPSHOT/database.dump"
  docker exec "$EXISTING_POSTGRES_CONTAINER" \
    pg_dump -U erp --format=custom erp > "$DATABASE_BACKUP"
  chmod 600 "$DATABASE_BACKUP"
  if [ ! -s "$DATABASE_BACKUP" ]; then
    echo "Pre-upgrade PostgreSQL backup is empty; refusing to change the stack." >&2
    exit 1
  fi
  docker exec -i "$EXISTING_POSTGRES_CONTAINER" \
    pg_restore --list < "$DATABASE_BACKUP" >/dev/null
  DATABASE_BACKUP_SHA256=$(sha256sum "$DATABASE_BACKUP" | awk '{print $1}')
  sha256sum "$DATABASE_BACKUP" > "$UPGRADE_SNAPSHOT/database.dump.sha256"
  chmod 600 "$UPGRADE_SNAPSHOT/database.dump.sha256"
  VERIFY_DATABASE="code17_restore_verify_${SNAPSHOT_STAMP//[^a-zA-Z0-9_]/_}"
  docker exec "$EXISTING_POSTGRES_CONTAINER" \
    createdb -U erp -T template0 "$VERIFY_DATABASE"
  if ! docker exec -i "$EXISTING_POSTGRES_CONTAINER" \
    pg_restore -U erp -d "$VERIFY_DATABASE" --exit-on-error --no-owner \
    < "$DATABASE_BACKUP" >/dev/null; then
    docker exec "$EXISTING_POSTGRES_CONTAINER" \
      dropdb -U erp --if-exists "$VERIFY_DATABASE" >/dev/null 2>&1 || true
    echo "Pre-upgrade PostgreSQL backup failed a full disposable restore." >&2
    exit 1
  fi
  verified_head=$(docker exec "$EXISTING_POSTGRES_CONTAINER" \
    psql -U erp -d "$VERIFY_DATABASE" -Atc "SELECT version_num FROM alembic_version")
  docker exec "$EXISTING_POSTGRES_CONTAINER" \
    dropdb -U erp --if-exists "$VERIFY_DATABASE" >/dev/null
  VERIFY_DATABASE=""
  if [ "$verified_head" != "$PRIOR_DB_HEAD" ]; then
    echo "Restored backup Alembic head does not match the prior deployment." >&2
    exit 1
  fi
  echo "==> Verified database backup: $DATABASE_BACKUP"
fi

# Atomic promotion happens only after all fail-closed checks and the database
# backup. The pre-upgrade .env remains in the mode-0700 snapshot directory.
chmod 600 "$ENV_CANDIDATE"
mv "$ENV_CANDIDATE" .env
ENV_CANDIDATE=""
MAINTENANCE_ACTIVE=false
PROMOTION_COMPLETE=true
echo "==> Candidate environment promoted atomically."

# The old release may have run MinIO even though no application code uses S3.
# Retire that one proven legacy container only after candidate promotion; its
# image, Compose file, environment and volume remain available to rollback.
if [ -n "$LEGACY_MINIO_CONTAINER" ]; then
  docker stop -t 30 "$LEGACY_MINIO_CONTAINER" >/dev/null
  docker rm "$LEGACY_MINIO_CONTAINER" >/dev/null
  echo "==> Retired unused legacy MinIO runtime; preserved its named volume."
fi

# ----- 4. Bring up the stack -----
echo
echo "==> Starting internal services while public ingress remains closed…"
"${candidate_compose[@]}" --env-file .env \
  up -d --no-build --pull never postgres redis backend frontend

CANDIDATE_REDIS_CONTAINER_IDS=()
while IFS= read -r container_id; do
  [ -n "$container_id" ] && CANDIDATE_REDIS_CONTAINER_IDS+=("$container_id")
done < <("${candidate_compose[@]}" --env-file .env ps -q redis)
if [ "${#CANDIDATE_REDIS_CONTAINER_IDS[@]}" -ne 1 ] || \
   [ "$(docker inspect --format '{{.Image}}' \
     "${CANDIDATE_REDIS_CONTAINER_IDS[0]:-missing}")" \
     != "$CANDIDATE_REDIS_IMAGE_ID" ]; then
  echo "Running Redis does not use the verified digest-pinned candidate image." >&2
  exit 1
fi
echo "==> Running Redis image identity verified."

# ----- 5. Wait for backend healthy -----
echo
echo "==> Waiting for backend readiness (migrations, seeds, DB, and Redis)…"
ATTEMPTS=0
until "${candidate_compose[@]}" --env-file .env exec -T backend python -c \
  "import urllib.request; urllib.request.urlopen('http://localhost:8000/readyz', timeout=2).read()" \
  >/dev/null 2>&1; do
  ATTEMPTS=$((ATTEMPTS+1))
  if [ $ATTEMPTS -gt 120 ]; then
    echo "Backend did not come up in 2 minutes. Check logs:"
    echo "  $OPERATIONS_COMPOSE_COMMAND logs backend"
    exit 1
  fi
  printf "."
  sleep 1
done
echo
echo "==> Backend is ready."

# Docker health checks can lag readiness. Keep ingress closed until every
# internal release-built image equals the pre-cutover candidate.
echo "==> Verifying healthy PostgreSQL/backend/frontend release identities…"
ATTEMPTS=0
until python3 "$CANDIDATE_PARITY_TOOL" running \
  --root "$CANDIDATE_BUILD_ROOT" --env-file "$REPO_DIR/.env" \
  --version-name "$CANDIDATE_APP_VERSION" --source-git-sha "$CURRENT_REVISION" \
  --project-name "$DEPLOY_PROJECT_NAME" \
  --expected-images-json "$CANDIDATE_IMAGE_ATTESTATION" \
  --services postgres backend frontend; do
  ATTEMPTS=$((ATTEMPTS+1))
  if [ "$ATTEMPTS" -ge 30 ]; then
    echo "Running release parity was not proven; public ingress remains closed." >&2
    exit 1
  fi
  sleep 2
done

# Business prices must never change merely because a container restarted.
# Apply the reviewed Code 22 tariff exactly once in the guarded maintenance
# transaction window, after migrations and before public ingress reopens. Any
# unexpected active legacy package aborts the installer and follows the same
# verified rollback path as a migration or readiness failure.
echo "==> Auditing the owner-approved Gaming Centre tariff…"
"${candidate_compose[@]}" --env-file .env exec -T backend \
  python -m scripts.ensure_gaming_tariff --dry-run
echo "==> Applying the owner-approved Gaming Centre tariff…"
"${candidate_compose[@]}" --env-file .env exec -T backend \
  python -m scripts.ensure_gaming_tariff
echo "==> Gaming Centre tariff accepted."

if [ "$FRESH_INSTALL" = true ]; then
  OWNER_EMAIL=$(grep '^SEED_OWNER_EMAIL=' .env | cut -d= -f2-)
  OWNER_PASSWORD=$(grep '^SEED_OWNER_PASSWORD=' .env | cut -d= -f2-)
  echo "==> Proving the generated owner can sign in with protected admin.system access…"
  {
    printf '%s\n' "$OWNER_EMAIL"
    printf '%s\n' "$OWNER_PASSWORD"
  } | "${candidate_compose[@]}" --env-file .env exec -T backend \
    python -c '
import json
import sys
import urllib.request

email = sys.stdin.readline().rstrip("\n")
password = sys.stdin.readline().rstrip("\n")
payload = json.dumps({"email": email, "password": password}).encode()
request = urllib.request.Request(
    "http://localhost:8000/api/v1/auth/login",
    data=payload,
    headers={"Content-Type": "application/json"},
)
with urllib.request.urlopen(request, timeout=10) as response:
    access_token = json.load(response)["access_token"]
me_request = urllib.request.Request(
    "http://localhost:8000/api/v1/auth/me",
    headers={"Authorization": f"Bearer {access_token}"},
)
with urllib.request.urlopen(me_request, timeout=10) as response:
    me = json.load(response)
if me.get("email") != email:
    raise SystemExit("seeded owner identity does not match SEED_OWNER_EMAIL")
if "owner" not in me.get("roles", []):
    raise SystemExit("seeded owner lacks the public owner role")
if me.get("audit_access") is not True:
    raise SystemExit("seeded owner lacks protected audit authority")
if "admin.system" not in me.get("effective_permissions", []):
    raise SystemExit("seeded owner lacks protected admin.system access")
protected_request = urllib.request.Request(
    "http://localhost:8000/api/v1/remote-assistance/devices",
    headers={"Authorization": f"Bearer {access_token}"},
)
with urllib.request.urlopen(protected_request, timeout=10) as response:
    if response.status != 200:
        raise SystemExit("seeded owner cannot access the owner support endpoint")
'
  unset OWNER_PASSWORD
  echo "==> Fresh owner authentication and protected access accepted."
fi

echo "==> Backend acceptance passed; reopening public ingress…"
if [ -n "$UPGRADE_SNAPSHOT" ]; then
  # The quiesced dump is a safe automatic rollback point only while ingress is
  # closed. Once Caddy may accept writes, never restore that older snapshot.
  # DNS/TLS was already proven against the unchanged domain before maintenance.
  trap handle_post_ingress_failure EXIT
fi
"${candidate_compose[@]}" --env-file .env \
  up -d --no-build --pull never caddy

# ----- 6. Wait for Caddy to issue the certificate -----
echo
echo "==> Waiting for Caddy to issue Let's Encrypt cert for $DOMAIN…"
echo "    (This can take up to 60 seconds after first DNS-correct request.)"
ATTEMPTS=0
until curl -fsS "https://$DOMAIN/readyz" >/dev/null 2>&1; do
  ATTEMPTS=$((ATTEMPTS+1))
  if [ $ATTEMPTS -gt 120 ]; then
    echo
    echo "HTTPS not ready after 2 minutes. Common causes:"
    echo "  • DNS hasn't propagated yet — try again in 5–10 minutes."
    echo "  • Domain A record points to the wrong IP."
    echo "  • Port 80/443 isn't reachable from the internet (check Oracle Security List)."
    echo "Check Caddy logs:  $OPERATIONS_COMPOSE_COMMAND logs caddy"
    exit 1
  fi
  printf "."
  sleep 1
done
echo
echo "==> HTTPS is live."
echo "==> Verifying complete running release identity, including Caddy…"
python3 "$CANDIDATE_PARITY_TOOL" running \
  --root "$CANDIDATE_BUILD_ROOT" --env-file "$REPO_DIR/.env" \
  --version-name "$CANDIDATE_APP_VERSION" --source-git-sha "$CURRENT_REVISION" \
  --project-name "$DEPLOY_PROJECT_NAME" \
  --expected-images-json "$CANDIDATE_IMAGE_ATTESTATION"
trap - EXIT

# ----- 7. Done -----
OWNER_EMAIL=$(grep '^SEED_OWNER_EMAIL=' .env | cut -d= -f2-)

if [ "$FRESH_INSTALL" = true ]; then
  LOGIN_HANDOFF="  First-login email: $OWNER_EMAIL\n  The generated password is stored only in the mode-0600 .env file.\n  Retrieve it through your approved host-secret workflow, then change it on first login."
else
  LOGIN_HANDOFF="  Existing owner credentials were retained and are not displayed."
fi

cat <<EOF

============================================================
  ✓ D Company ERP is live at:  https://$DOMAIN
============================================================

$(printf '%b' "$LOGIN_HANDOFF")

  To create your friend's view-only login, run:
    $OPERATIONS_COMPOSE_COMMAND exec backend \\
      python -m scripts.create_user \\
        --email friend@example.com --name "Friend Name" \\
        --role auditor
  The command prompts twice without terminal echo. Transfer that temporary
  password through an approved secret channel; ERP never prints it.

  Useful commands:
    $OPERATIONS_COMPOSE_COMMAND ps                 # status
    $OPERATIONS_COMPOSE_COMMAND logs -f backend    # live logs
    $OPERATIONS_COMPOSE_COMMAND restart backend    # restart
    $OPERATIONS_COMPOSE_COMMAND stop               # stop existing containers

  Backups (run weekly via cron):
    $OPERATIONS_COMPOSE_COMMAND exec postgres \\
      pg_dump -U erp erp | gzip > /root/backups/erp-\$(date +%F).sql.gz

============================================================
EOF
