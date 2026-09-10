#!/bin/bash
# Build the Redis executable required by the isolated CI ACL contract.

set -euo pipefail

REDIS_VERSION=7.4.11
REDIS_ARCHIVE="redis-${REDIS_VERSION}.tar.gz"
REDIS_URL="https://download.redis.io/releases/${REDIS_ARCHIVE}"
REDIS_SHA256="3c266ece0abd54ed3b1c912c6eb86b7508cf382cb690ee6649d3843f018f6357"
MAX_ARCHIVE_BYTES=8388608
BUILD_JOBS=2

fail() {
  echo "CI Redis prerequisite: $*" >&2
  exit 1
}

if [ "${CI:-}" != true ] || [ "${GITHUB_ACTIONS:-}" != true ]; then
  fail "this helper is restricted to GitHub Actions CI"
fi
if [ -z "${RUNNER_TEMP:-}" ] || [ -z "${GITHUB_PATH:-}" ]; then
  fail "RUNNER_TEMP and GITHUB_PATH are required"
fi
case "$RUNNER_TEMP" in
  /*) ;;
  *) fail "RUNNER_TEMP must be an absolute path" ;;
esac
case "$GITHUB_PATH" in
  /*) ;;
  *) fail "GITHUB_PATH must be an absolute path" ;;
esac
case "$RUNNER_TEMP$GITHUB_PATH" in
  *$'\n'*|*$'\r'*) fail "CI paths must not contain line breaks" ;;
esac
if [ ! -d "$RUNNER_TEMP" ] || [ -L "$RUNNER_TEMP" ] || [ ! -O "$RUNNER_TEMP" ] || [ ! -w "$RUNNER_TEMP" ]; then
  fail "RUNNER_TEMP must be a private runner-owned writable directory"
fi
GITHUB_PATH_PARENT=$(dirname -- "$GITHUB_PATH")
if [ ! -d "$GITHUB_PATH_PARENT" ] || [ -L "$GITHUB_PATH_PARENT" ] || [ ! -O "$GITHUB_PATH_PARENT" ] || [ ! -w "$GITHUB_PATH_PARENT" ]; then
  fail "GITHUB_PATH must be inside a runner-owned writable directory"
fi
if [ -e "$GITHUB_PATH" ] && { [ ! -f "$GITHUB_PATH" ] || [ -L "$GITHUB_PATH" ] || [ ! -O "$GITHUB_PATH" ] || [ ! -w "$GITHUB_PATH" ]; }; then
  fail "GITHUB_PATH must be a runner-owned writable regular file"
fi

for command_name in curl sha256sum tar make install mktemp; do
  command -v "$command_name" >/dev/null 2>&1 || fail "required command is unavailable: $command_name"
done

umask 077
RUNNER_TEMP_REAL=$(CDPATH='' cd -- "$RUNNER_TEMP" && pwd -P)
WORK_DIR=""
BIN_DIR=""
PUBLISHED=false

cleanup() {
  status=$?
  if [ -n "$WORK_DIR" ] && [ -d "$WORK_DIR" ]; then
    rm -rf -- "$WORK_DIR"
  fi
  if [ "$PUBLISHED" != true ] && [ -n "$BIN_DIR" ] && [ -d "$BIN_DIR" ]; then
    rm -rf -- "$BIN_DIR"
  fi
  return "$status"
}
trap cleanup EXIT
trap 'exit 130' HUP INT TERM

WORK_DIR=$(mktemp -d "$RUNNER_TEMP_REAL/dcompany-ci-redis-build.XXXXXX")
BIN_DIR=$(mktemp -d "$RUNNER_TEMP_REAL/dcompany-ci-redis-bin.XXXXXX")
ARCHIVE_PATH="$WORK_DIR/$REDIS_ARCHIVE"
SOURCE_DIR="$WORK_DIR/redis-$REDIS_VERSION"

if ! curl \
  --fail \
  --silent \
  --show-error \
  --location \
  --proto '=https' \
  --proto-redir '=https' \
  --connect-timeout 15 \
  --max-time 120 \
  --max-filesize "$MAX_ARCHIVE_BYTES" \
  --output "$ARCHIVE_PATH" \
  "$REDIS_URL"; then
  fail "Redis source download failed"
fi

if [ ! -f "$ARCHIVE_PATH" ] || [ -L "$ARCHIVE_PATH" ]; then
  fail "download did not produce a regular archive"
fi
ARCHIVE_BYTES=$(wc -c <"$ARCHIVE_PATH")
ARCHIVE_BYTES=${ARCHIVE_BYTES//[[:space:]]/}
case "$ARCHIVE_BYTES" in
  ''|*[!0-9]*) fail "downloaded archive size is invalid" ;;
esac
if [ "$ARCHIVE_BYTES" -eq 0 ] || [ "$ARCHIVE_BYTES" -gt "$MAX_ARCHIVE_BYTES" ]; then
  fail "downloaded archive is outside the allowed size"
fi

SHA_OUTPUT=$(sha256sum "$ARCHIVE_PATH") || fail "could not hash downloaded archive"
ACTUAL_SHA256=${SHA_OUTPUT%%[[:space:]]*}
if [ "$ACTUAL_SHA256" != "$REDIS_SHA256" ]; then
  fail "downloaded Redis archive checksum mismatch"
fi

tar --extract --gzip --file "$ARCHIVE_PATH" --directory "$WORK_DIR" \
  --no-same-owner --no-same-permissions
if [ ! -d "$SOURCE_DIR" ] || [ -L "$SOURCE_DIR" ] || [ ! -f "$SOURCE_DIR/Makefile" ]; then
  fail "verified Redis archive has an unexpected layout"
fi

if ! make --directory "$SOURCE_DIR" --jobs "$BUILD_JOBS" \
  MALLOC=libc BUILD_TLS=no redis-server; then
  fail "Redis build failed"
fi
BUILT_SERVER="$SOURCE_DIR/src/redis-server"
if [ ! -f "$BUILT_SERVER" ] || [ -L "$BUILT_SERVER" ] || [ ! -x "$BUILT_SERVER" ]; then
  fail "Redis build did not produce an executable redis-server"
fi
install -m 0755 "$BUILT_SERVER" "$BIN_DIR/redis-server"

VERSION_OUTPUT=$("$BIN_DIR/redis-server" --version) || fail "built redis-server version check failed"
case "$VERSION_OUTPUT" in
  *$'\n'*|*$'\r'*) fail "built redis-server returned malformed version output" ;;
esac
case "$VERSION_OUTPUT" in
  "Redis server v=$REDIS_VERSION "*) ;;
  *) fail "built redis-server is not Redis $REDIS_VERSION" ;;
esac
case "$VERSION_OUTPUT" in
  *" malloc=libc "*) ;;
  *) fail "built redis-server did not use the required libc allocator" ;;
esac

printf '%s\n' "$BIN_DIR" >>"$GITHUB_PATH" || fail "could not publish the verified Redis path"
PUBLISHED=true
echo "Verified Redis $REDIS_VERSION CI prerequisite at $BIN_DIR/redis-server"
