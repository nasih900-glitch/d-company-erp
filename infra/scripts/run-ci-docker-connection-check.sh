#!/bin/bash
# Compare runner and root against one explicit disposable GitHub CI daemon.

set -euo pipefail

if [ "$#" -ne 4 ]; then
  echo "Usage: $0 UNIX_DOCKER_HOST classic|containerd EXPECTED_DOCKER_ROOT EVIDENCE_JSON" >&2
  exit 2
fi

DOCKER_HOST_EXPECTED=$1
EXPECTED_STORE=$2
EXPECTED_ROOT=$3
EVIDENCE_JSON=$4
if [ "$EXPECTED_STORE" != classic ] && [ "$EXPECTED_STORE" != containerd ]; then
  echo "Expected Docker store must be classic or containerd." >&2
  exit 2
fi
if [ -z "${RUNNER_TOOL_CACHE:-}" ]; then
  echo "RUNNER_TOOL_CACHE is required to bind the setup-Docker CLI." >&2
  exit 1
fi

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)
PROBE="$SCRIPT_DIR/verify-ci-docker-connection.py"
DOCKER_CLI=$(command -v docker)
DOCKER_CLI=$(realpath "$DOCKER_CLI")
DOCKER_CLI_DIR=$(dirname "$DOCKER_CLI")
EVIDENCE_DIR=$(dirname "$EVIDENCE_JSON")
mkdir -p "$EVIDENCE_DIR"
RUNNER_JSON="$EVIDENCE_JSON.runner"
ROOT_JSON="$EVIDENCE_JSON.root"
umask 077

/usr/bin/python3 "$PROBE" capture \
  --docker-cli "$DOCKER_CLI" \
  --docker-host "$DOCKER_HOST_EXPECTED" \
  --expected-store "$EXPECTED_STORE" \
  --expected-root "$EXPECTED_ROOT" \
  --tool-cache-root "$RUNNER_TOOL_CACHE" \
  --role runner >"$RUNNER_JSON"

/usr/bin/sudo /usr/bin/env -i \
  "PATH=$DOCKER_CLI_DIR:/usr/sbin:/usr/bin:/sbin:/bin" \
  "DOCKER_HOST=$DOCKER_HOST_EXPECTED" \
  HOME=/root LC_ALL=C \
  /usr/bin/python3 "$PROBE" capture \
    --docker-cli "$DOCKER_CLI" \
    --docker-host "$DOCKER_HOST_EXPECTED" \
    --expected-store "$EXPECTED_STORE" \
    --expected-root "$EXPECTED_ROOT" \
    --tool-cache-root "$RUNNER_TOOL_CACHE" \
    --role root >"$ROOT_JSON"

/usr/bin/python3 "$PROBE" compare "$RUNNER_JSON" "$ROOT_JSON" >"$EVIDENCE_JSON"
rm -f "$RUNNER_JSON" "$ROOT_JSON"
