#!/usr/bin/env python3
"""D Company ERP self-hosted uptime check.

Runs on the VPS host via a systemd timer (dcompany-uptime.timer) every
5 minutes. Hits the live public URL (not localhost) so the check exercises
the whole real path a customer/staff request takes: DNS, TLS, Caddy, and
the backend's own DB/Redis dependency checks — not just "is the process
alive". Uses the same SMTP config the app already uses for OTP mail (see
backup_to_b2.py, which this deliberately mirrors) to alert on failure, and
sends a follow-up email when it recovers so a transient blip doesn't need
manual confirmation that it actually cleared.

Only alerts on a STATE CHANGE (up->down or down->up), tracked in a small
state file, so a prolonged outage sends one alert and one recovery email —
not a fresh email every 5 minutes for as long as it's down.

Requires:
  - /opt/d-company-erp/.env (reused for SMTP + alert recipient)
"""

from __future__ import annotations

import sys
import urllib.error
import urllib.request
from email.mime.text import MIMEText
from pathlib import Path

try:
    from ops.smtp_client import alert_recipients, authenticated_smtp
except ModuleNotFoundError:  # direct execution: python /opt/.../ops/uptime_check.py
    from smtp_client import alert_recipients, authenticated_smtp

ROOT = Path("/opt/d-company-erp")
ENV_FILE = ROOT / ".env"
STATE_FILE = ROOT / "ops" / ".uptime_state"
BASE_URL = "https://dcompany.duckdns.org"
REQUEST_TIMEOUT_SECONDS = 10
SMTP_TIMEOUT_SECONDS = 30


def load_env() -> dict[str, str]:
    env: dict[str, str] = {}
    for raw in ENV_FILE.read_text().splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        env[key.strip()] = value.strip()
    return env


def check() -> tuple[bool, str]:
    """Returns (is_up, detail). is_up is True only if both /healthz returns
    200 and /readyz reports every dependency ok — a degraded-but-serving
    backend (e.g. Redis down) should still page someone, not be silently
    treated as healthy."""
    try:
        with urllib.request.urlopen(
            f"{BASE_URL}/healthz", timeout=REQUEST_TIMEOUT_SECONDS
        ) as resp:
            if resp.status != 200:
                return False, f"/healthz returned HTTP {resp.status}"
    except (urllib.error.URLError, TimeoutError) as exc:
        return False, f"/healthz unreachable: {exc}"

    try:
        with urllib.request.urlopen(
            f"{BASE_URL}/readyz", timeout=REQUEST_TIMEOUT_SECONDS
        ) as resp:
            body = resp.read().decode("utf-8", errors="replace")
            if resp.status != 200:
                return False, f"/readyz returned HTTP {resp.status}: {body}"
            if '"down"' in body:
                return False, f"/readyz reports a dependency down: {body}"
    except (urllib.error.URLError, TimeoutError) as exc:
        return False, f"/readyz unreachable: {exc}"

    return True, "ok"


def send_alert(env: dict[str, str], subject: str, body: str) -> None:
    recipients = alert_recipients(env)
    if not recipients or not env.get("SMTP_HOST"):
        print("No alert email configured — cannot send notice.", file=sys.stderr)
        return
    from_email = env.get("FROM_EMAIL") or env.get("SMTP_USER")
    if not from_email:
        print(
            "No FROM_EMAIL or SMTP_USER configured — cannot send notice.",
            file=sys.stderr,
        )
        return
    msg = MIMEText(body)
    msg["Subject"] = subject
    msg["From"] = f"{env.get('FROM_NAME', 'D Company ERP')} <{from_email}>"
    msg["To"] = ", ".join(recipients)
    try:
        with authenticated_smtp(env, timeout_seconds=SMTP_TIMEOUT_SECONDS) as s:
            s.send_message(msg)
    except Exception as exc:  # noqa: BLE001
        print(f"Alert email also failed to send: {exc}", file=sys.stderr)


def main() -> int:
    try:
        env = load_env()
    except OSError as exc:
        print(
            f"Uptime check FAILED to start: could not read {ENV_FILE}: {exc}",
            file=sys.stderr,
        )
        return 1

    is_up, detail = check()
    previous_state = STATE_FILE.read_text().strip() if STATE_FILE.is_file() else "up"
    current_state = "up" if is_up else "down"

    if current_state != previous_state:
        if is_up:
            send_alert(
                env,
                subject="D Company ERP is back up",
                body=f"dcompany.duckdns.org is responding normally again.\n\n{detail}",
            )
            print(f"RECOVERED: {detail}")
        else:
            send_alert(
                env,
                subject="D Company ERP is DOWN",
                body=(
                    f"dcompany.duckdns.org failed its health check.\n\n{detail}\n\n"
                    "This is the live production system — check the VPS."
                ),
            )
            print(f"DOWN: {detail}", file=sys.stderr)
    else:
        print(f"{current_state.upper()} (unchanged): {detail}")

    STATE_FILE.parent.mkdir(parents=True, exist_ok=True)
    STATE_FILE.write_text(current_state)
    return 0 if is_up else 1


if __name__ == "__main__":
    sys.exit(main())
