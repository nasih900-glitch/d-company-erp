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

cd "$(dirname "$0")/../.."
REPO_DIR="$(pwd)"
LOCK_FILE=/var/lock/d-company-erp-production-install.lock
umask 077
exec 9>"$LOCK_FILE"
chmod 600 "$LOCK_FILE"
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

# Prove and protect the currently deployed release before any environment or
# running-service change. A real upgrade must have one running Postgres and
# backend, an exact database head, a source-verified immutable backend image,
# the prior Compose file, and durable rollback tags for every service image.
UPGRADE_SNAPSHOT=""
EXISTING_POSTGRES_CONTAINER=""
EXISTING_BACKEND_CONTAINER=""
PRIOR_PROJECT_NAME=""
PRIOR_DB_HEAD=""
CONTAINER_IDS=()
RUNNING_CONTAINER_IDS=()
if [ -f .env ]; then
  if ! container_ids_output=$(docker compose -f docker-compose.prod.yml --env-file .env ps -aq 2>/dev/null); then
    echo "Cannot enumerate the existing Compose project; refusing upgrade." >&2
    exit 1
  fi
  if ! running_ids_output=$(docker compose -f docker-compose.prod.yml --env-file .env ps -q 2>/dev/null); then
    echo "Cannot enumerate running Compose services; refusing upgrade." >&2
    exit 1
  fi
  while IFS= read -r container_id; do
    [ -n "$container_id" ] && CONTAINER_IDS+=("$container_id")
  done <<< "$container_ids_output"
  while IFS= read -r container_id; do
    [ -n "$container_id" ] && RUNNING_CONTAINER_IDS+=("$container_id")
  done <<< "$running_ids_output"
  if [ "${#CONTAINER_IDS[@]}" -eq 0 ]; then
    echo "Existing .env has no Compose containers; refusing to treat it as a fresh install." >&2
    exit 1
  fi

  if ! postgres_ids_output=$(docker compose -f docker-compose.prod.yml --env-file .env ps -aq postgres 2>/dev/null); then
    echo "Cannot locate the existing Postgres service; refusing upgrade." >&2
    exit 1
  fi
  POSTGRES_IDS=()
  while IFS= read -r container_id; do
    [ -n "$container_id" ] && POSTGRES_IDS+=("$container_id")
  done <<< "$postgres_ids_output"
  if [ "${#POSTGRES_IDS[@]}" -ne 1 ]; then
    echo "Expected exactly one existing Postgres container; found ${#POSTGRES_IDS[@]}. Refusing upgrade." >&2
    exit 1
  fi
  EXISTING_POSTGRES_CONTAINER=${POSTGRES_IDS[0]}
  if [ "$(docker inspect --format '{{.State.Running}}' "$EXISTING_POSTGRES_CONTAINER")" != true ]; then
    echo "Existing Postgres is not running; recover it and back it up before upgrade." >&2
    exit 1
  fi

  if ! backend_ids_output=$(docker compose -f docker-compose.prod.yml --env-file .env ps -q backend 2>/dev/null); then
    echo "Cannot locate the running backend service; refusing upgrade." >&2
    exit 1
  fi
  BACKEND_IDS=()
  while IFS= read -r container_id; do
    [ -n "$container_id" ] && BACKEND_IDS+=("$container_id")
  done <<< "$backend_ids_output"
  if [ "${#BACKEND_IDS[@]}" -ne 1 ]; then
    echo "Expected exactly one running backend container; found ${#BACKEND_IDS[@]}. Refusing upgrade." >&2
    exit 1
  fi
  EXISTING_BACKEND_CONTAINER=${BACKEND_IDS[0]}
  PRIOR_PROJECT_NAME=$(docker inspect \
    --format '{{index .Config.Labels "com.docker.compose.project"}}' \
    "$EXISTING_BACKEND_CONTAINER")
  if ! [[ "$PRIOR_PROJECT_NAME" =~ ^[a-z0-9][a-z0-9_.-]*$ ]]; then
    echo "Running backend has no safe Compose project identity; refusing upgrade." >&2
    exit 1
  fi

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
  install -m 0700 -d "$REPO_DIR/.deployment-rollbacks"
  UPGRADE_SNAPSHOT=$(mktemp -d \
    "$REPO_DIR/.deployment-rollbacks/pre-code17-${SNAPSHOT_STAMP}.XXXXXX")
  chmod 700 "$UPGRADE_SNAPSHOT"
  install -m 0600 .env "$UPGRADE_SNAPSHOT/.env"
  if ! git show "$PRIOR_REVISION:docker-compose.prod.yml" \
    > "$UPGRADE_SNAPSHOT/docker-compose.prior.yml"; then
    echo "Cannot recover docker-compose.prod.yml from the proven prior revision." >&2
    exit 1
  fi
  chmod 600 "$UPGRADE_SNAPSHOT/docker-compose.prior.yml"

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
    printf 'installer_checkout=%s\n' "$CURRENT_REVISION"
  } > "$UPGRADE_SNAPSHOT/revision.txt"
  : > "$UPGRADE_SNAPSHOT/container-images.txt"
  {
    echo '#!/bin/bash'
    echo 'set -euo pipefail'
  } > "$UPGRADE_SNAPSHOT/restore-images.sh"
  for container_id in "${CONTAINER_IDS[@]}"; do
    service=$(docker inspect --format '{{index .Config.Labels "com.docker.compose.service"}}' "$container_id")
    original_ref=$(docker inspect --format '{{.Config.Image}}' "$container_id")
    image_id=$(docker inspect --format '{{.Image}}' "$container_id")
    image_revision=$(docker image inspect --format '{{index .Config.Labels "org.opencontainers.image.revision"}}' "$image_id")
    if ! [[ "$service" =~ ^[A-Za-z0-9_.-]+$ ]] || [ -z "$original_ref" ] || [ -z "$image_id" ]; then
      echo "Cannot establish rollback identity for container $container_id." >&2
      exit 1
    fi
    rollback_ref="dcompany-rollback:${SNAPSHOT_STAMP}-${service}"
    docker image tag "$image_id" "$rollback_ref"
    printf '%s|%s|%s|%s|%s\n' \
      "$service" "$original_ref" "$image_id" "$image_revision" "$rollback_ref" \
      >> "$UPGRADE_SNAPSHOT/container-images.txt"
    printf 'docker image tag %q %q\n' "$rollback_ref" "$original_ref" \
      >> "$UPGRADE_SNAPSHOT/restore-images.sh"
  done
  chmod 600 "$UPGRADE_SNAPSHOT/revision.txt" "$UPGRADE_SNAPSHOT/container-images.txt"
  chmod 700 "$UPGRADE_SNAPSHOT/restore-images.sh"
  echo "==> Protected and source-verified prior deployment: $UPGRADE_SNAPSHOT"
else
  inferred_project_name=$(basename "$REPO_DIR" \
    | tr '[:upper:]' '[:lower:]' \
    | sed -E 's/[^a-z0-9_.-]//g; s/^[^a-z0-9]+//')
  existing_project_containers=$(docker ps -aq \
    --filter "label=com.docker.compose.project=$inferred_project_name")
  retained_volumes=$(docker volume ls -q \
    --filter "label=com.docker.compose.project=$inferred_project_name")
  retained_networks=$(docker network ls -q \
    --filter "label=com.docker.compose.project=$inferred_project_name")
  rollback_evidence=$(find .deployment-rollbacks -mindepth 1 -maxdepth 1 -print -quit \
    2>/dev/null || true)
  env_backup_evidence=$(find . -maxdepth 1 -type f -name '.env.pre-code17.*' -print -quit)
  if [ -n "$existing_project_containers" ] || [ -n "$retained_volumes" ] || \
     [ -n "$retained_networks" ] || [ -n "$rollback_evidence" ] || \
     [ -n "$env_backup_evidence" ]; then
    echo "Persistent deployment evidence exists but .env is missing." >&2
    echo "Refusing a fresh secret reset; recover the prior .env through the incident runbook." >&2
    exit 1
  fi
fi

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
  echo "==> Checking existing production .env for required Code17 secrets…"
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
    echo "==> Code17 acceptance failed; restoring the quiesced prior release." >&2
    docker compose -f docker-compose.prod.yml --env-file .env \
      stop -t 30 caddy backend frontend redis minio >/dev/null 2>&1 || true
    if [ -f .env ]; then
      install -m 0600 .env "$UPGRADE_SNAPSHOT/.env.failed-code17"
    fi
    if ! current_postgres_output=$(docker compose -f docker-compose.prod.yml \
      --env-file .env ps -q postgres 2>/dev/null); then
      current_postgres_output=""
    fi
    CURRENT_POSTGRES_IDS=()
    while IFS= read -r container_id; do
      [ -n "$container_id" ] && CURRENT_POSTGRES_IDS+=("$container_id")
    done <<< "$current_postgres_output"
    rollback_succeeded=true
    if [ "${#CURRENT_POSTGRES_IDS[@]}" -ne 1 ] || \
       [ ! -s "$UPGRADE_SNAPSHOT/database.dump" ] || \
       ! sha256sum -c "$UPGRADE_SNAPSHOT/database.dump.sha256" >/dev/null 2>&1; then
      rollback_succeeded=false
    else
      rollback_postgres=${CURRENT_POSTGRES_IDS[0]}
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
      install -m 0600 "$UPGRADE_SNAPSHOT/.env" "$REPO_DIR/.env.rollback.$$"
      mv "$REPO_DIR/.env.rollback.$$" .env
      "$UPGRADE_SNAPSHOT/restore-images.sh" || rollback_succeeded=false
    fi
    if [ "$rollback_succeeded" = true ]; then
      prior_compose=(
        docker compose -p "$PRIOR_PROJECT_NAME" --project-directory "$REPO_DIR"
        -f "$UPGRADE_SNAPSHOT/docker-compose.prior.yml" --env-file .env
      )
      "${prior_compose[@]}" up -d --no-build --force-recreate \
        postgres redis minio backend frontend || rollback_succeeded=false
    fi
    if [ "$rollback_succeeded" = true ]; then
      rollback_attempts=0
      until "${prior_compose[@]}" exec -T backend \
        curl -fsS http://localhost:8000/readyz >/dev/null 2>&1; do
        rollback_attempts=$((rollback_attempts + 1))
        if [ "$rollback_attempts" -gt 120 ]; then
          rollback_succeeded=false
          break
        fi
        sleep 1
      done
    fi
    if [ "$rollback_succeeded" = true ]; then
      "${prior_compose[@]}" up -d --no-build --force-recreate caddy \
        || rollback_succeeded=false
    fi
    if [ "$rollback_succeeded" = true ]; then
      echo "==> Prior release and final quiesced database backup restored." >&2
      echo "    Investigate Code17 before another scheduled upgrade." >&2
    else
      docker compose -f docker-compose.prod.yml --env-file .env stop caddy \
        >/dev/null 2>&1 || true
      echo "AUTOMATIC ROLLBACK FAILED; public ingress remains closed." >&2
      echo "Use the protected evidence at $UPGRADE_SNAPSHOT and the incident runbook." >&2
    fi
  elif [ "$PROMOTION_COMPLETE" = true ]; then
    docker compose -f docker-compose.prod.yml --env-file .env stop caddy \
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
  docker compose -f docker-compose.prod.yml --env-file .env stop -t 30 caddy \
    >/dev/null 2>&1 || true
  echo "Code17 public-ingress acceptance failed; Caddy is stopped." >&2
  echo "The current database is preserved and the quiesced dump was NOT restored." >&2
  echo "Use the recovery runbook and protected evidence at $UPGRADE_SNAPSHOT." >&2
  exit "$failure_code"
}
trap handle_install_failure EXIT
MAINTENANCE_ACTIVE=false
if [ -f .env ]; then
  SOURCE_ENV=.env
else
  SOURCE_ENV=-
fi
bash infra/scripts/prepare-production-env.sh \
  "$SOURCE_ENV" "$ENV_CANDIDATE" "$DOMAIN" "$CURRENT_REVISION"

echo
echo "==> Candidate environment ready. Non-secret values:"
grep -E "^(DOMAIN|SEED_OWNER_EMAIL|ENV)=" "$ENV_CANDIDATE"

echo
echo "==> Running fail-closed environment and Compose preflight…"
docker compose -f docker-compose.prod.yml --env-file "$ENV_CANDIDATE" config --quiet
echo "==> Production config preflight passed; no Compose service has been recreated."
if [ -n "$UPGRADE_SNAPSHOT" ]; then
  echo "    Rollback evidence remains at: $UPGRADE_SNAPSHOT"
  echo "==> Proving existing DNS/TLS/readiness before the maintenance window…"
  curl -fsS "https://$DOMAIN/readyz" >/dev/null
fi

echo
echo "==> Building candidate images while the existing release remains live…"
docker compose -f docker-compose.prod.yml --env-file "$ENV_CANDIDATE" build

# Compose build arguments are release identity, not decoration. Prove the
# resulting images carry the exact candidate version and checkout revision
# before stopping writers or promoting the environment.
CANDIDATE_APP_VERSION=$(grep '^APP_VERSION=' "$ENV_CANDIDATE" | cut -d= -f2-)
for candidate_service in backend frontend; do
  candidate_image=$(docker compose -f docker-compose.prod.yml \
    --env-file "$ENV_CANDIDATE" images -q "$candidate_service")
  if [ -z "$candidate_image" ] || [ "$(printf '%s\n' "$candidate_image" | wc -l | tr -d ' ')" -ne 1 ]; then
    echo "Expected exactly one built image for $candidate_service." >&2
    exit 1
  fi
  candidate_version=$(docker image inspect \
    --format '{{index .Config.Labels "org.opencontainers.image.version"}}' \
    "$candidate_image")
  candidate_revision=$(docker image inspect \
    --format '{{index .Config.Labels "org.opencontainers.image.revision"}}' \
    "$candidate_image")
  if [ "$candidate_version" != "$CANDIDATE_APP_VERSION" ] || \
     [ "$candidate_revision" != "$CURRENT_REVISION" ]; then
    echo "Candidate $candidate_service image release labels do not match the clean checkout." >&2
    exit 1
  fi
done
echo "==> Candidate backend/frontend image version and revision labels verified."

if [ -n "$EXISTING_POSTGRES_CONTAINER" ]; then
  database_size_bytes=$(docker exec "$EXISTING_POSTGRES_CONTAINER" \
    psql -U erp -d erp -Atc "SELECT pg_database_size('erp')")
  snapshot_free_kib=$(df -Pk "$UPGRADE_SNAPSHOT" | awk 'END {print $4}')
  postgres_free_kib=$(docker exec "$EXISTING_POSTGRES_CONTAINER" \
    sh -c "df -Pk /var/lib/postgresql/data | awk 'END {print \$4}'")
  bash infra/scripts/check-upgrade-capacity.sh \
    "$database_size_bytes" "$snapshot_free_kib" "$postgres_free_kib"
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
  docker compose -f docker-compose.prod.yml --env-file .env stop -t 30 caddy
  docker compose -f docker-compose.prod.yml --env-file .env stop -t 60 backend
  docker compose -f docker-compose.prod.yml --env-file .env stop -t 30 frontend
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

# ----- 4. Bring up the stack -----
echo
echo "==> Starting internal services while public ingress remains closed…"
docker compose -f docker-compose.prod.yml --env-file .env \
  up -d postgres redis minio backend frontend

# ----- 5. Wait for backend healthy -----
echo
echo "==> Waiting for backend readiness (migrations, seeds, DB, and Redis)…"
ATTEMPTS=0
until docker compose -f docker-compose.prod.yml exec -T backend curl -fsS http://localhost:8000/readyz >/dev/null 2>&1; do
  ATTEMPTS=$((ATTEMPTS+1))
  if [ $ATTEMPTS -gt 120 ]; then
    echo "Backend did not come up in 2 minutes. Check logs:"
    echo "  docker compose -f docker-compose.prod.yml logs backend"
    exit 1
  fi
  printf "."
  sleep 1
done
echo
echo "==> Backend is ready."

if [ "$FRESH_INSTALL" = true ]; then
  OWNER_EMAIL=$(grep '^SEED_OWNER_EMAIL=' .env | cut -d= -f2-)
  OWNER_PASSWORD=$(grep '^SEED_OWNER_PASSWORD=' .env | cut -d= -f2-)
  echo "==> Proving the generated owner can sign in with protected admin.system access…"
  {
    printf '%s\n' "$OWNER_EMAIL"
    printf '%s\n' "$OWNER_PASSWORD"
  } | docker compose -f docker-compose.prod.yml --env-file .env exec -T backend \
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
        raise SystemExit("seeded owner cannot access an admin.system endpoint")
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
docker compose -f docker-compose.prod.yml --env-file .env up -d caddy

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
    echo "Check Caddy logs:  docker compose -f docker-compose.prod.yml logs caddy"
    exit 1
  fi
  printf "."
  sleep 1
done
echo
echo "==> HTTPS is live."
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
    docker compose -f docker-compose.prod.yml exec backend \\
      python -m scripts.create_user \\
        --email friend@example.com --name "Friend Name" \\
        --role auditor
  The command prompts twice without terminal echo. Transfer that temporary
  password through an approved secret channel; ERP never prints it.

  Useful commands:
    docker compose -f docker-compose.prod.yml ps                 # status
    docker compose -f docker-compose.prod.yml logs -f backend    # live logs
    docker compose -f docker-compose.prod.yml restart backend    # restart
    docker compose -f docker-compose.prod.yml stop               # stop existing containers

  Backups (run weekly via cron):
    docker compose -f docker-compose.prod.yml exec postgres \\
      pg_dump -U erp erp | gzip > /root/backups/erp-\$(date +%F).sql.gz

============================================================
EOF
