#!/bin/bash
# Fail-closed validation for production security settings before stack mutation.

set -euo pipefail
cd "$(dirname "$0")/../.."

ENV_FILE="${1:-.env}"
EXPECTED_APP_VERSION="${2:-}"
if [ ! -f "$ENV_FILE" ]; then
  echo "Production environment file not found: $ENV_FILE" >&2
  exit 1
fi

value_for() {
  key=$1
  count=$(grep -c "^${key}=" "$ENV_FILE" || true)
  if [ "$count" -ne 1 ]; then
    echo "Expected exactly one $key entry in $ENV_FILE; found $count." >&2
    exit 1
  fi
  grep "^${key}=" "$ENV_FILE" | cut -d= -f2-
}

require_secret() {
  key=$1
  minimum_length=$2
  value=$(value_for "$key")
  if [ -z "$value" ] || [[ "$value" == CHANGE_ME* ]] || [ "${#value}" -lt "$minimum_length" ]; then
    echo "$key is missing, a placeholder, or shorter than $minimum_length characters." >&2
    exit 1
  fi
  printf '%s' "$value"
}

env_name=$(value_for ENV)
if [ "$env_name" != prod ]; then
  echo "ENV must be prod for docker-compose.prod.yml (found: $env_name)." >&2
  exit 1
fi

if [ -z "$EXPECTED_APP_VERSION" ]; then
  version_source=.env.production.example
  version_count=$(grep -c '^APP_VERSION=' "$version_source" || true)
  if [ "$version_count" -ne 1 ]; then
    echo "Expected exactly one APP_VERSION entry in $version_source; found $version_count." >&2
    exit 1
  fi
  EXPECTED_APP_VERSION=$(grep '^APP_VERSION=' "$version_source" | cut -d= -f2-)
fi
if ! [[ "$EXPECTED_APP_VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo "Expected APP_VERSION must be a release version such as 3.1.9." >&2
  exit 1
fi
app_version=$(value_for APP_VERSION)
if [ "$app_version" != "$EXPECTED_APP_VERSION" ]; then
  echo "APP_VERSION must match the coordinated release ($EXPECTED_APP_VERSION); found: $app_version." >&2
  exit 1
fi

app_revision=$(value_for APP_REVISION)
if ! [[ "$app_revision" =~ ^[0-9a-f]{40}$ ]]; then
  echo "APP_REVISION must be the exact 40-character release commit." >&2
  exit 1
fi

domain=$(value_for DOMAIN)
if [ -z "$domain" ] || [[ "$domain" == *CHANGE_ME* ]] || \
   [ "$(printf '%s' "$domain" | tr '[:upper:]' '[:lower:]')" != "$domain" ] || \
   [ "${#domain}" -gt 253 ] || \
   ! [[ "$domain" =~ ^([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,63}$ ]]; then
  echo "DOMAIN must be a configured, normalized DNS hostname." >&2
  exit 1
fi

cors_origins=$(value_for CORS_ORIGINS)
public_url=$(value_for PUBLIC_URL)
android_update_origin=$(value_for ANDROID_UPDATE_ALLOWED_ORIGIN)
if [[ "$cors_origins" != *"\"https://${domain}\""* ]]; then
  echo "CORS_ORIGINS must include the configured DOMAIN HTTPS origin." >&2
  exit 1
fi
if [ "$public_url" != "https://${domain}" ]; then
  echo "PUBLIC_URL must exactly match the configured DOMAIN HTTPS URL." >&2
  exit 1
fi
if [ "$android_update_origin" != "https://${domain}" ]; then
  echo "ANDROID_UPDATE_ALLOWED_ORIGIN must exactly match the configured DOMAIN." >&2
  exit 1
fi

owner_email=$(value_for SEED_OWNER_EMAIL)
if [ "$(printf '%s' "$owner_email" | tr '[:upper:]' '[:lower:]')" != "$owner_email" ] || \
   [ "${#owner_email}" -gt 254 ] || \
   [[ "$owner_email" == *CHANGE_ME* ]] || \
   [[ "$owner_email" == *.local ]] || [[ "$owner_email" == *.lan ]] || \
   [[ "$owner_email" == *.test ]] || [[ "$owner_email" == *.invalid ]] || \
   [[ "$owner_email" == *.example ]] || \
   ! [[ "$owner_email" =~ ^[^[:space:]@]+@[^[:space:]@]+\.[^[:space:]@]+$ ]]; then
  echo "SEED_OWNER_EMAIL must be a normalized login email." >&2
  exit 1
fi

jwt_secret=$(require_secret JWT_SECRET 32)
pairing_secret=$(require_secret REMOTE_ASSISTANCE_PAIRING_SECRET 32)
relay_secret=$(require_secret REMOTE_ASSISTANCE_RELAY_SECRET 44)
redis_password=$(require_secret REDIS_PASSWORD 64)
postgres_password=$(require_secret POSTGRES_PASSWORD 20)
database_url=$(require_secret DATABASE_URL 40)
s3_secret=$(require_secret S3_SECRET_KEY 20)
owner_password=$(require_secret SEED_OWNER_PASSWORD 10)

if ! [[ "$redis_password" =~ ^[0-9a-f]{64}$ ]]; then
  echo "REDIS_PASSWORD must be exactly 64 lowercase hex characters." >&2
  exit 1
fi

if ! [[ "$relay_secret" =~ ^[A-Za-z0-9+/]{43}=$ ]]; then
  echo "REMOTE_ASSISTANCE_RELAY_SECRET must be canonical standard base64." >&2
  exit 1
fi
if ! relay_bytes=$(printf '%s' "$relay_secret" | openssl base64 -d -A 2>/dev/null | wc -c | tr -d ' '); then
  echo "REMOTE_ASSISTANCE_RELAY_SECRET is not valid standard base64." >&2
  exit 1
fi
if [ "$relay_bytes" != 32 ]; then
  echo "REMOTE_ASSISTANCE_RELAY_SECRET must be standard base64 for exactly 32 bytes." >&2
  exit 1
fi
relay_canonical=$(printf '%s' "$relay_secret" | openssl base64 -d -A | openssl base64 -A)
if [ "$relay_canonical" != "$relay_secret" ]; then
  echo "REMOTE_ASSISTANCE_RELAY_SECRET must use canonical standard base64." >&2
  exit 1
fi

expected_database_url="postgresql+psycopg://erp:${postgres_password}@postgres:5432/erp"
if [ "$database_url" != "$expected_database_url" ]; then
  echo "DATABASE_URL must use the configured POSTGRES_PASSWORD for the Compose postgres service." >&2
  exit 1
fi

secret_names=(JWT pairing relay Redis Postgres MinIO owner)
secret_values=(
  "$jwt_secret" "$pairing_secret" "$relay_secret" "$redis_password"
  "$postgres_password" "$s3_secret" "$owner_password"
)
for ((left = 0; left < ${#secret_values[@]}; left++)); do
  for ((right = left + 1; right < ${#secret_values[@]}; right++)); do
    if [ "${secret_values[$left]}" = "${secret_values[$right]}" ]; then
      echo "${secret_names[$left]} and ${secret_names[$right]} secrets must be independent." >&2
      exit 1
    fi
  done
done

chmod 600 "$ENV_FILE"
echo "Production environment security preflight passed."
