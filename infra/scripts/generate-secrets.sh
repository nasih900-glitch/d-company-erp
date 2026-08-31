#!/bin/bash
# Generate or backfill strong production secrets without replacing real values.
#
# Existing installations are upgraded in place: missing, empty, or exact
# CHANGE_ME values are filled, while every non-placeholder value is retained.
# A changed existing file gets a mode-0600 backup before the atomic rename.

set -euo pipefail
cd "$(dirname "$0")/../.."

ENV_FILE="${1:-.env}"
ENV_DIR=$(dirname "$ENV_FILE")
ENV_NAME=$(basename "$ENV_FILE")
mkdir -p "$ENV_DIR"

ENV_EXISTED=false
if [ -f "$ENV_FILE" ]; then
  ENV_EXISTED=true
fi

TMP_FILE=$(mktemp "$ENV_DIR/.${ENV_NAME}.tmp.XXXXXX")
NEXT_FILE=""
cleanup() {
  if [ -n "$TMP_FILE" ]; then
    rm -f "$TMP_FILE"
  fi
  if [ -n "$NEXT_FILE" ]; then
    rm -f "$NEXT_FILE"
  fi
}
trap cleanup EXIT
chmod 600 "$TMP_FILE"

if [ "$ENV_EXISTED" = true ]; then
  cp "$ENV_FILE" "$TMP_FILE"
else
  echo "Creating $ENV_FILE from .env.production.example…"
  cp .env.production.example "$TMP_FILE"
fi

rand_b64() { openssl rand -base64 "$1" | tr -d '\n=' | tr '+/' '_-'; }
rand_std_b64() { openssl rand -base64 "$1" | tr -d '\n'; }
rand_hex() { openssl rand -hex "$1" | tr -d '\n'; }
rand_pw()  { openssl rand -hex 24 | tr -d '\n'; }

JWT_SECRET=$(rand_b64 48)
PAIRING_SECRET=$(rand_b64 48)
RELAY_SECRET=$(rand_std_b64 32)
REDIS_PW=$(rand_hex 32)
POSTGRES_PW=$(rand_pw)
MINIO_PW=$(rand_pw)
OWNER_PW=$(rand_pw)

managed_keys=(
  POSTGRES_PASSWORD DATABASE_URL JWT_SECRET REDIS_PASSWORD
  REMOTE_ASSISTANCE_PAIRING_SECRET REMOTE_ASSISTANCE_RELAY_SECRET
  S3_SECRET_KEY SEED_OWNER_PASSWORD
)
for key in "${managed_keys[@]}"; do
  count=$(grep -c "^${key}=" "$TMP_FILE" || true)
  if [ "$count" -gt 1 ]; then
    echo "Refusing to update $ENV_FILE: duplicate $key entries are ambiguous." >&2
    exit 1
  fi
done

replace_or_append() {
  key=$1
  placeholder=$2
  generated=$3
  existing=""
  if grep -q "^${key}=" "$TMP_FILE"; then
    existing=$(grep "^${key}=" "$TMP_FILE" | cut -d= -f2-)
  fi

  if [ -n "$existing" ] && \
     [ "$existing" != "$placeholder" ] && \
     [[ "$existing" != CHANGE_ME* ]]; then
    return 1
  fi

  NEXT_FILE=$(mktemp "$ENV_DIR/.${ENV_NAME}.next.XXXXXX")
  chmod 600 "$NEXT_FILE"
  awk -v key="$key" -v value="$generated" '
    BEGIN { replaced = 0 }
    index($0, key "=") == 1 {
      if (!replaced) {
        print key "=" value
        replaced = 1
      }
      next
    }
    { print }
    END {
      if (!replaced) print key "=" value
    }
  ' "$TMP_FILE" > "$NEXT_FILE"
  mv "$NEXT_FILE" "$TMP_FILE"
  NEXT_FILE=""
  return 0
}

replace_or_append POSTGRES_PASSWORD CHANGE_ME_strong_random_password "$POSTGRES_PW" || true
POSTGRES_EFFECTIVE=$(grep '^POSTGRES_PASSWORD=' "$TMP_FILE" | cut -d= -f2-)
replace_or_append DATABASE_URL \
  postgresql+psycopg://erp:CHANGE_ME_strong_random_password@postgres:5432/erp \
  "postgresql+psycopg://erp:${POSTGRES_EFFECTIVE}@postgres:5432/erp" || true
replace_or_append JWT_SECRET CHANGE_ME_48_char_base64_secret "$JWT_SECRET" || true
replace_or_append REDIS_PASSWORD CHANGE_ME_64_hex_redis_password "$REDIS_PW" || true
replace_or_append REMOTE_ASSISTANCE_PAIRING_SECRET \
  CHANGE_ME_48_char_dedicated_pairing_secret "$PAIRING_SECRET" || true
replace_or_append REMOTE_ASSISTANCE_RELAY_SECRET \
  CHANGE_ME_32_byte_base64_relay_key "$RELAY_SECRET" || true
replace_or_append S3_SECRET_KEY CHANGE_ME_minio_password "$MINIO_PW" || true

OWNER_PASSWORD_GENERATED=false
if replace_or_append SEED_OWNER_PASSWORD CHANGE_ME_strong_owner_password "$OWNER_PW"; then
  OWNER_PASSWORD_GENERATED=true
fi

BACKUP_FILE=""
if [ "$ENV_EXISTED" = true ] && cmp -s "$ENV_FILE" "$TMP_FILE"; then
  chmod 600 "$ENV_FILE"
else
  if [ "$ENV_EXISTED" = true ] && [ "${DCE_SKIP_BACKUP:-false}" != true ]; then
    BACKUP_FILE=$(mktemp "$ENV_DIR/${ENV_NAME}.pre-code17.XXXXXX")
    cp -p "$ENV_FILE" "$BACKUP_FILE"
    chmod 600 "$BACKUP_FILE"
  fi
  chmod 600 "$TMP_FILE"
  mv "$TMP_FILE" "$ENV_FILE"
  TMP_FILE=""
fi

echo
echo "============================================================"
echo "Production secrets are present in $ENV_FILE (mode 0600)."
if [ -n "$BACKUP_FILE" ]; then
  echo "Pre-change backup: $BACKUP_FILE"
fi
grep "^SEED_OWNER_EMAIL=" "$ENV_FILE" || true
if [ "$OWNER_PASSWORD_GENERATED" = true ]; then
  echo "Owner password generated and stored in $ENV_FILE; it is not displayed."
else
  echo "Existing owner password retained and not displayed."
fi
echo
echo "Now review $ENV_FILE and fill in any remaining operational values:"
echo "  DOMAIN=          (the domain you bought)"
echo "  CORS_ORIGINS=    (replace CHANGE_ME with your domain)"
echo "  SEED_OWNER_EMAIL= (the real first-login email)"
echo "============================================================"
