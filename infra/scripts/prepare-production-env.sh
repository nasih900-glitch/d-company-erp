#!/bin/bash
# Build and validate a production candidate without changing the live .env.

set -euo pipefail
cd "$(dirname "$0")/../.."

SOURCE_ENV=${1:--}
CANDIDATE_ENV=${2:?candidate environment path is required}
DOMAIN=${3:?domain is required}
APP_REVISION=${4:?release revision is required}
VERSION_SOURCE=.env.production.example

if [ "${#DOMAIN}" -gt 253 ] || \
   ! [[ "$DOMAIN" =~ ^([A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?\.)+[A-Za-z]{2,63}$ ]]; then
  echo "Domain must be a plain DNS hostname (for example erp.example.com)." >&2
  exit 1
fi
DOMAIN=$(printf '%s' "$DOMAIN" | tr '[:upper:]' '[:lower:]')
if ! [[ "$APP_REVISION" =~ ^[0-9a-f]{40}$ ]]; then
  echo "APP_REVISION must be an exact 40-character Git commit." >&2
  exit 1
fi
version_count=$(grep -c '^APP_VERSION=' "$VERSION_SOURCE" || true)
if [ "$version_count" -ne 1 ]; then
  echo "Expected exactly one APP_VERSION entry in $VERSION_SOURCE; found $version_count." >&2
  exit 1
fi
APP_VERSION=$(grep '^APP_VERSION=' "$VERSION_SOURCE" | cut -d= -f2-)
if ! [[ "$APP_VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo "APP_VERSION in $VERSION_SOURCE must be a release version such as 3.1.11." >&2
  exit 1
fi
if [ -e "$CANDIDATE_ENV" ]; then
  echo "Refusing to overwrite candidate environment: $CANDIDATE_ENV" >&2
  exit 1
fi
if [ "$SOURCE_ENV" != - ]; then
  if [ ! -f "$SOURCE_ENV" ]; then
    echo "Source environment does not exist: $SOURCE_ENV" >&2
    exit 1
  fi
  source_domain_count=$(grep -c '^DOMAIN=' "$SOURCE_ENV" || true)
  if [ "$source_domain_count" -ne 1 ]; then
    echo "Existing production environment must contain exactly one DOMAIN entry." >&2
    exit 1
  fi
  source_domain=$(grep '^DOMAIN=' "$SOURCE_ENV" | cut -d= -f2-)
  if [ "$source_domain" != "$DOMAIN" ]; then
    echo "Normal upgrades must use the existing DOMAIN exactly ($source_domain)." >&2
    echo "Domain migration requires a separate DNS/TLS/origin migration runbook." >&2
    exit 1
  fi
  install -m 0600 "$SOURCE_ENV" "$CANDIDATE_ENV"
fi

DCE_SKIP_BACKUP=true bash infra/scripts/generate-secrets.sh "$CANDIDATE_ENV"

NEXT_ENV=$(mktemp "$(dirname "$CANDIDATE_ENV")/.env.prepared.XXXXXX")
cleanup() {
  if [ -n "$NEXT_ENV" ]; then
    rm -f "$NEXT_ENV"
  fi
}
trap cleanup EXIT
chmod 600 "$NEXT_ENV"
awk -v domain="$DOMAIN" -v revision="$APP_REVISION" -v version="$APP_VERSION" '
  BEGIN { saw_domain = 0; saw_revision = 0; saw_version = 0 }
  /^DOMAIN=/ {
    if (saw_domain) { print "duplicate DOMAIN entry" > "/dev/stderr"; exit 2 }
    print "DOMAIN=" domain
    saw_domain = 1
    next
  }
  /^APP_REVISION=/ {
    if (saw_revision) { print "duplicate APP_REVISION entry" > "/dev/stderr"; exit 2 }
    print "APP_REVISION=" revision
    saw_revision = 1
    next
  }
  /^APP_VERSION=/ {
    if (saw_version) { print "duplicate APP_VERSION entry" > "/dev/stderr"; exit 2 }
    print "APP_VERSION=" version
    saw_version = 1
    next
  }
  {
    gsub(/CHANGE_ME\.com/, domain)
    print
  }
  END {
    if (!saw_domain) print "DOMAIN=" domain
    if (!saw_revision) print "APP_REVISION=" revision
    if (!saw_version) print "APP_VERSION=" version
  }
' "$CANDIDATE_ENV" > "$NEXT_ENV"
mv "$NEXT_ENV" "$CANDIDATE_ENV"
NEXT_ENV=""
chmod 600 "$CANDIDATE_ENV"
bash infra/scripts/validate-production-env.sh "$CANDIDATE_ENV" "$APP_VERSION"
