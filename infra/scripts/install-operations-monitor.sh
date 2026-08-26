#!/bin/sh
set -eu

if [ "$(id -u)" -ne 0 ]; then
  echo "Run as root: sudo sh infra/scripts/install-operations-monitor.sh" >&2
  exit 1
fi

ROOT=/opt/d-company-erp
if [ ! -f "$ROOT/.env" ] || [ ! -f "$ROOT/docker-compose.prod.yml" ]; then
  echo "$ROOT is not a configured D Company ERP production installation." >&2
  exit 1
fi

install -m 0644 \
  "$ROOT/infra/systemd/dcompany-runtime-monitor.service" \
  /etc/systemd/system/dcompany-runtime-monitor.service
install -m 0644 \
  "$ROOT/infra/systemd/dcompany-runtime-monitor.timer" \
  /etc/systemd/system/dcompany-runtime-monitor.timer
install -m 0644 \
  "$ROOT/infra/cron/dcompany-erp-alerts" \
  /etc/cron.d/dcompany-erp-alerts
install -m 0644 \
  "$ROOT/infra/logrotate/dcompany-erp-alerts" \
  /etc/logrotate.d/dcompany-erp-alerts

systemctl daemon-reload
systemctl enable --now dcompany-runtime-monitor.timer
systemctl start dcompany-runtime-monitor.service
systemctl --no-pager status dcompany-runtime-monitor.timer
