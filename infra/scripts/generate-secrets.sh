#!/bin/bash
# Generates strong random secrets for the production .env file.
# Idempotent — only fills in values that are still "CHANGE_ME...".

set -e
cd "$(dirname "$0")/../.."

ENV_FILE="${1:-.env}"

if [ ! -f "$ENV_FILE" ]; then
  echo "Creating $ENV_FILE from .env.production.example…"
  cp .env.production.example "$ENV_FILE"
fi

rand_b64() { openssl rand -base64 "$1" | tr -d '\n=' | tr '+/' '_-'; }
rand_pw()  { openssl rand -base64 24 | tr -d '\n=+/' | head -c 24; }

JWT_SECRET=$(rand_b64 48)
POSTGRES_PW=$(rand_pw)
MINIO_PW=$(rand_pw)
OWNER_PW=$(rand_pw)

# In-place substitution — only replaces CHANGE_ME_* placeholders.
sed -i.bak \
  -e "s|CHANGE_ME_strong_random_password|$POSTGRES_PW|g" \
  -e "s|CHANGE_ME_48_char_base64_secret|$JWT_SECRET|" \
  -e "s|CHANGE_ME_minio_password|$MINIO_PW|" \
  -e "s|CHANGE_ME_strong_owner_password|$OWNER_PW|" \
  "$ENV_FILE"
rm -f "${ENV_FILE}.bak"

echo
echo "============================================================"
echo "Generated secrets in $ENV_FILE."
echo "Owner login (write this down — shown only once):"
echo
grep "^SEED_OWNER_EMAIL=" "$ENV_FILE"
echo "SEED_OWNER_PASSWORD=$OWNER_PW"
echo
echo "Now edit $ENV_FILE and fill in:"
echo "  DOMAIN=          (the domain you bought)"
echo "  CORS_ORIGINS=    (replace CHANGE_ME with your domain)"
echo "  SEED_*           (your real company info: GSTIN, FSSAI, etc.)"
echo "============================================================"
