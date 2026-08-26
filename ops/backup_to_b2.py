#!/usr/bin/env python3
"""Nightly D Company ERP backup: pg_dump -> Backblaze B2.

Runs on the VPS host (not inside a container — it needs the Docker CLI) via
a systemd timer (dcompany-backup.timer). Postgres itself stays the source of
truth; this only ships an off-site copy so a disk/droplet failure doesn't
mean total data loss.

On any failure, sends an alert email using the same SMTP config the app
already uses for OTP mail — a silent backup failure is worse than no backup
at all, since it creates false confidence.

Requires:
  - boto3 installed in /opt/d-company-erp/ops/venv (S3-compatible client
    used to talk to Backblaze B2; no more Google service-account key)
  - /opt/d-company-erp/.env (reused for SMTP + alert recipient), with
    B2_KEY_ID and B2_APPLICATION_KEY set to a Backblaze B2 application key
    that has read/write/delete access to the dcompany-erp-backups bucket
"""

from __future__ import annotations

import subprocess
import sys
from datetime import datetime, timedelta, timezone
from email.mime.text import MIMEText
from pathlib import Path
from urllib.parse import urlparse

import boto3
from botocore.config import Config

try:
    from ops.smtp_client import alert_recipients, authenticated_smtp
except ModuleNotFoundError:  # direct execution: python /opt/.../ops/backup_to_b2.py
    from smtp_client import alert_recipients, authenticated_smtp

ROOT = Path("/opt/d-company-erp")
ENV_FILE = ROOT / ".env"
COMPOSE_FILE = ROOT / "docker-compose.prod.yml"
LOCAL_BACKUP_DIR = ROOT / "backups" / "auto"
B2_BUCKET = "dcompany-erp-backups"
B2_ENDPOINT = "https://s3.us-east-005.backblazeb2.com"
RETENTION_DAYS = 30
# Safety cap so a wedged docker/postgres can't hang the oneshot unit forever
# and silently block every future nightly run behind it.
PG_DUMP_TIMEOUT_SECONDS = 3600
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


def run_pg_dump(dest: Path) -> None:
    dest.parent.mkdir(parents=True, exist_ok=True)
    with open(dest, "wb") as f:
        subprocess.run(
            [
                "docker",
                "compose",
                "-f",
                str(COMPOSE_FILE),
                "--env-file",
                str(ENV_FILE),
                "exec",
                "-T",
                "postgres",
                "pg_dump",
                "-U",
                "erp",
                "-d",
                "erp",
                "-F",
                "c",
            ],
            cwd=ROOT,
            stdout=f,
            stderr=subprocess.PIPE,
            check=True,
            timeout=PG_DUMP_TIMEOUT_SECONDS,
        )
    if dest.stat().st_size == 0:
        raise RuntimeError("pg_dump produced an empty file")


def b2_client(env: dict[str, str]):
    key_id = env.get("B2_KEY_ID")
    application_key = env.get("B2_APPLICATION_KEY")
    if not key_id or not application_key:
        raise RuntimeError(
            "B2_KEY_ID and/or B2_APPLICATION_KEY missing from .env — cannot "
            "authenticate to Backblaze B2."
        )
    # Derive the B2 region (e.g. "us-east-005") from the endpoint hostname
    # instead of hardcoding it a second time.
    region = urlparse(B2_ENDPOINT).hostname.split(".")[1]
    return boto3.client(
        "s3",
        endpoint_url=B2_ENDPOINT,
        aws_access_key_id=key_id,
        aws_secret_access_key=application_key,
        config=Config(signature_version="s3v4"),
        region_name=region,
    )


def upload(client, local_path: Path) -> None:
    client.upload_file(str(local_path), B2_BUCKET, local_path.name)


def cleanup_remote(client) -> None:
    cutoff = datetime.now(timezone.utc) - timedelta(days=RETENTION_DAYS)
    paginator = client.get_paginator("list_objects_v2")
    stale_keys: list[str] = []
    for page in paginator.paginate(Bucket=B2_BUCKET):
        for obj in page.get("Contents", []):
            if obj["LastModified"] < cutoff:
                stale_keys.append(obj["Key"])

    # Batch deletes up to 1000 keys per call (the S3-API limit).
    for i in range(0, len(stale_keys), 1000):
        batch = stale_keys[i : i + 1000]
        response = client.delete_objects(
            Bucket=B2_BUCKET,
            Delete={"Objects": [{"Key": key} for key in batch]},
        )
        # delete_objects() returns HTTP 200 even when individual keys fail to
        # delete (permission edge case, throttling, etc.) — surface those
        # instead of silently leaving objects past RETENTION_DAYS.
        for err in response.get("Errors") or []:
            print(
                f"Warning: failed to delete stale backup {err.get('Key')}: "
                f"{err.get('Code')} {err.get('Message')}",
                file=sys.stderr,
            )


def cleanup_local() -> None:
    if not LOCAL_BACKUP_DIR.exists():
        return
    cutoff = datetime.now(timezone.utc) - timedelta(days=RETENTION_DAYS)
    for f in LOCAL_BACKUP_DIR.iterdir():
        if (
            f.is_file()
            and datetime.fromtimestamp(f.stat().st_mtime, tz=timezone.utc) < cutoff
        ):
            f.unlink()


def send_alert(env: dict[str, str], subject: str, body: str) -> None:
    recipients = alert_recipients(env)
    if not recipients or not env.get("SMTP_HOST"):
        print(
            "No alert email configured — cannot send failure notice.", file=sys.stderr
        )
        return
    from_email = env.get("FROM_EMAIL") or env.get("SMTP_USER")
    if not from_email:
        print(
            "No FROM_EMAIL or SMTP_USER configured — cannot send failure notice.",
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
        # Can't read .env, so there's no SMTP config to alert with either —
        # this is the one failure mode that can't route through send_alert().
        print(f"Backup FAILED: could not read {ENV_FILE}: {exc}", file=sys.stderr)
        return 1

    stamp = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H%M%SZ")
    local_path = LOCAL_BACKUP_DIR / f"dcompany-erp-{stamp}.dump"

    error = None
    try:
        run_pg_dump(local_path)
        client = b2_client(env)
        upload(client, local_path)
        cleanup_remote(client)
    except Exception as exc:  # noqa: BLE001 - any failure here must reach send_alert()
        error = exc

    # Always prune old local backups, independent of whether the run above
    # succeeded — otherwise a chronic remote failure (bad key, network) means
    # local disk fills up unbounded and can eventually take pg_dump itself
    # down with ENOSPC.
    try:
        cleanup_local()
    except OSError as exc:
        print(f"Local cleanup also failed: {exc}", file=sys.stderr)

    if error is None:
        print(f"Backup OK: {local_path.name} ({local_path.stat().st_size} bytes)")
        return 0

    stderr_bytes = getattr(error, "stderr", None)
    detail = (
        stderr_bytes.decode(errors="replace")
        if isinstance(stderr_bytes, bytes) and stderr_bytes
        else str(error)
    )
    print(f"Backup FAILED: {detail}", file=sys.stderr)
    send_alert(
        env,
        subject="D Company ERP backup failed",
        body=(
            f"The nightly database backup failed at {datetime.now(timezone.utc).isoformat()}.\n\n"
            f"Error: {detail}\n\n"
            "The ERP itself is unaffected — this only means last night's off-site backup "
            "didn't happen. Check systemctl status dcompany-backup.service on the server, "
            "or ask Claude to look into it."
        ),
    )
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
