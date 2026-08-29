#!/bin/sh
set -eu

if [ "$(id -u)" -ne 0 ]; then
  echo "Run as root: sudo sh infra/scripts/install-operations-monitor.sh" >&2
  exit 1
fi

ROOT=/opt/d-company-erp
STATE_ROOT=/var/lib/dcompany-erp
BACKUP_RUNTIME="$STATE_ROOT/backup-runtime"
BACKUP_REQUIREMENTS="$ROOT/ops/backup-requirements.lock"
BACKUP_DIR="$STATE_ROOT/backups/auto"
LEGACY_BACKUP_DIR="$ROOT/backups/auto"

if [ ! -f "$ROOT/.env" ] || [ ! -f "$ROOT/docker-compose.prod.yml" ]; then
  echo "$ROOT is not a configured D Company ERP production installation." >&2
  exit 1
fi
if [ ! -f "$BACKUP_REQUIREMENTS" ]; then
  echo "Missing pinned backup dependencies: $BACKUP_REQUIREMENTS" >&2
  exit 1
fi
if ! command -v python3 >/dev/null 2>&1; then
  echo "python3 is required to install the backup runtime." >&2
  exit 1
fi

# Pause the scheduler before switching its interpreter. Never replace a
# runtime under a backup already in progress; the timer is restored on exit if
# this installation fails.
backup_timer_was_active=0
if systemctl is-active --quiet dcompany-backup.timer 2>/dev/null; then
  backup_timer_was_active=1
  systemctl stop dcompany-backup.timer
fi

runtime_temp=
restore_backup_timer() {
  if [ -n "$runtime_temp" ] && [ -d "$runtime_temp" ]; then
    rm -rf -- "$runtime_temp"
  fi
  if [ "$backup_timer_was_active" -eq 1 ]; then
    systemctl start dcompany-backup.timer >/dev/null 2>&1 || true
  fi
}
trap restore_backup_timer EXIT HUP INT TERM

if systemctl is-active --quiet dcompany-backup.service 2>/dev/null; then
  echo "dcompany-backup.service is running; retry after the current backup finishes." >&2
  exit 1
fi

install -d -o root -g root -m 0700 \
  "$STATE_ROOT" "$BACKUP_RUNTIME" "$STATE_ROOT/backups" "$BACKUP_DIR"

# Preserve existing local restore points without deleting the legacy source.
# Leaving the source intact makes the migration reversible until an operator
# has verified both the local and B2 copies.
if [ -d "$LEGACY_BACKUP_DIR" ]; then
  find "$LEGACY_BACKUP_DIR" -maxdepth 1 -type f -name '*.dump' \
    -exec cp -p -n -- '{}' "$BACKUP_DIR/" ';'
fi
find "$BACKUP_DIR" -maxdepth 1 -type f -name '*.dump' -exec chmod 0600 '{}' +
find "$BACKUP_DIR" -maxdepth 1 -type f -name '*.dump' \
  -exec chown root:root '{}' +

requirements_sha=$(sha256sum "$BACKUP_REQUIREMENTS" | awk '{print $1}')
runtime_version="$BACKUP_RUNTIME/venv-$requirements_sha"

runtime_is_valid() {
  candidate=$1
  [ -x "$candidate/bin/python" ] || return 1
  "$candidate/bin/python" -m pip check >/dev/null 2>&1 || return 1
  "$candidate/bin/python" - "$BACKUP_REQUIREMENTS" >/dev/null 2>&1 <<'PY'
from importlib.metadata import version
from pathlib import Path
import re
import sys

for line in Path(sys.argv[1]).read_text(encoding="utf-8").splitlines():
    match = re.match(r"^([A-Za-z0-9_.-]+)==([^ \\]+)", line)
    if match and version(match.group(1)) != match.group(2):
        raise SystemExit(1)

import boto3  # noqa: F401, E402
import botocore  # noqa: F401, E402
PY
}

if ! runtime_is_valid "$runtime_version"; then
  if ! python3 -m venv --help >/dev/null 2>&1; then
    echo "Python venv support is missing; install the OS python3-venv package." >&2
    exit 1
  fi

  runtime_temp="$BACKUP_RUNTIME/.venv-new-$$"
  python3 -m venv "$runtime_temp"
  PIP_DISABLE_PIP_VERSION_CHECK=1 PIP_NO_INPUT=1 \
    "$runtime_temp/bin/python" -m pip install \
      --no-cache-dir \
      --only-binary=:all: \
      --require-hashes \
      --requirement "$BACKUP_REQUIREMENTS"
  runtime_is_valid "$runtime_temp"

  if [ -e "$runtime_version" ]; then
    mv "$runtime_version" "$runtime_version.invalid-$$"
  fi
  mv "$runtime_temp" "$runtime_version"
  runtime_temp=
fi

current_link="$BACKUP_RUNTIME/.current-new-$$"
ln -s "$(basename "$runtime_version")" "$current_link"
mv -Tf "$current_link" "$BACKUP_RUNTIME/current"
runtime_is_valid "$BACKUP_RUNTIME/current"

install -m 0644 \
  "$ROOT/infra/systemd/dcompany-runtime-monitor.service" \
  /etc/systemd/system/dcompany-runtime-monitor.service
install -m 0644 \
  "$ROOT/infra/systemd/dcompany-runtime-monitor.timer" \
  /etc/systemd/system/dcompany-runtime-monitor.timer
install -m 0644 \
  "$ROOT/infra/systemd/dcompany-backup.service" \
  /etc/systemd/system/dcompany-backup.service
install -m 0644 \
  "$ROOT/infra/systemd/dcompany-backup.timer" \
  /etc/systemd/system/dcompany-backup.timer
install -m 0644 \
  "$ROOT/infra/cron/dcompany-erp-alerts" \
  /etc/cron.d/dcompany-erp-alerts
install -m 0644 \
  "$ROOT/infra/logrotate/dcompany-erp-alerts" \
  /etc/logrotate.d/dcompany-erp-alerts

systemctl daemon-reload
systemctl reset-failed dcompany-backup.service >/dev/null 2>&1 || true
systemctl enable --now dcompany-backup.timer
# The runtime monitor treats a missing or stale backup as a real failure. Make
# the repaired backup scheduler authoritative before asking the monitor to
# evaluate that state, otherwise the warning could abort this installer while
# leaving the old broken scheduler in place.
backup_timer_was_active=0
trap - EXIT HUP INT TERM
systemctl enable --now dcompany-runtime-monitor.timer
systemctl start dcompany-runtime-monitor.service
systemctl --no-pager status dcompany-runtime-monitor.timer
systemctl --no-pager status dcompany-backup.timer
