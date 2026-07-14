#!/usr/bin/env python3
"""Nightly D Company ERP backup: pg_dump -> Google Drive.

Runs on the VPS host (not inside a container — it needs the Docker CLI) via
a systemd timer (dcompany-backup.timer). Postgres itself stays the source of
truth; this only ships an off-site copy so a disk/droplet failure doesn't
mean total data loss.

On any failure, sends an alert email using the same SMTP config the app
already uses for OTP mail — a silent backup failure is worse than no backup
at all, since it creates false confidence.

Requires:
  - /opt/d-company-erp/secrets/gdrive-service-account.json (Service Account
    key; the target Drive folder must be shared with its client_email)
  - /opt/d-company-erp/ops/venv (google-api-python-client, google-auth)
  - /opt/d-company-erp/.env (reused for SMTP + alert recipient)
"""

from __future__ import annotations

import smtplib
import subprocess
import sys
from datetime import datetime, timedelta, timezone
from email.mime.text import MIMEText
from pathlib import Path

from google.oauth2 import service_account
from googleapiclient.discovery import build
from googleapiclient.errors import HttpError
from googleapiclient.http import MediaFileUpload

ROOT = Path("/opt/d-company-erp")
ENV_FILE = ROOT / ".env"
COMPOSE_FILE = ROOT / "docker-compose.prod.yml"
SERVICE_ACCOUNT_FILE = ROOT / "secrets" / "gdrive-service-account.json"
LOCAL_BACKUP_DIR = ROOT / "backups" / "auto"
DRIVE_FOLDER_NAME = "D Company ERP Backups"
RETENTION_DAYS = 30


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
                "docker", "compose", "-f", str(COMPOSE_FILE), "--env-file", str(ENV_FILE),
                "exec", "-T", "postgres", "pg_dump", "-U", "erp", "-d", "erp", "-F", "c",
            ],
            cwd=ROOT, stdout=f, stderr=subprocess.PIPE, check=True,
        )
    if dest.stat().st_size == 0:
        raise RuntimeError("pg_dump produced an empty file")


def drive_service():
    creds = service_account.Credentials.from_service_account_file(
        str(SERVICE_ACCOUNT_FILE), scopes=["https://www.googleapis.com/auth/drive"],
    )
    return build("drive", "v3", credentials=creds, cache_discovery=False)


def find_folder_id(service) -> str:
    resp = service.files().list(
        q=(
            f"name = '{DRIVE_FOLDER_NAME}' "
            "and mimeType = 'application/vnd.google-apps.folder' and trashed = false"
        ),
        fields="files(id, name)",
        spaces="drive",
    ).execute()
    files = resp.get("files", [])
    if not files:
        raise RuntimeError(
            f"No Drive folder named {DRIVE_FOLDER_NAME!r} is shared with the service account "
            "— check the folder is shared with its client_email as Editor."
        )
    return files[0]["id"]


def upload(service, folder_id: str, local_path: Path) -> None:
    metadata = {"name": local_path.name, "parents": [folder_id]}
    media = MediaFileUpload(str(local_path), mimetype="application/octet-stream", resumable=False)
    service.files().create(body=metadata, media_body=media, fields="id").execute()


def cleanup_drive(service, folder_id: str) -> None:
    cutoff = (datetime.now(timezone.utc) - timedelta(days=RETENTION_DAYS)).strftime("%Y-%m-%dT%H:%M:%S")
    resp = service.files().list(
        q=f"'{folder_id}' in parents and trashed = false and createdTime < '{cutoff}'",
        fields="files(id, name)",
    ).execute()
    for f in resp.get("files", []):
        service.files().delete(fileId=f["id"]).execute()


def cleanup_local() -> None:
    if not LOCAL_BACKUP_DIR.exists():
        return
    cutoff = datetime.now(timezone.utc) - timedelta(days=RETENTION_DAYS)
    for f in LOCAL_BACKUP_DIR.iterdir():
        if f.is_file() and datetime.fromtimestamp(f.stat().st_mtime, tz=timezone.utc) < cutoff:
            f.unlink()


def send_alert(env: dict[str, str], subject: str, body: str) -> None:
    to_addr = env.get("ACCOUNT_SECURITY_EMAIL")
    if not to_addr or not env.get("SMTP_HOST"):
        print("No alert email configured — cannot send failure notice.", file=sys.stderr)
        return
    msg = MIMEText(body)
    msg["Subject"] = subject
    msg["From"] = f'{env.get("FROM_NAME", "D Company ERP")} <{env.get("FROM_EMAIL") or env["SMTP_USER"]}>'
    msg["To"] = to_addr
    try:
        with smtplib.SMTP_SSL(env["SMTP_HOST"], int(env.get("SMTP_PORT", "465"))) as s:
            s.login(env["SMTP_USER"], env["SMTP_PASSWORD"])
            s.send_message(msg)
    except Exception as exc:  # noqa: BLE001
        print(f"Alert email also failed to send: {exc}", file=sys.stderr)


def main() -> int:
    env = load_env()
    stamp = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H%M%SZ")
    local_path = LOCAL_BACKUP_DIR / f"dcompany-erp-{stamp}.dump"
    try:
        run_pg_dump(local_path)
        service = drive_service()
        folder_id = find_folder_id(service)
        upload(service, folder_id, local_path)
        cleanup_drive(service, folder_id)
        cleanup_local()
        print(f"Backup OK: {local_path.name} ({local_path.stat().st_size} bytes)")
        return 0
    except (subprocess.CalledProcessError, HttpError, RuntimeError, OSError) as exc:
        detail = exc.stderr.decode(errors="replace") if isinstance(exc, subprocess.CalledProcessError) and exc.stderr else str(exc)
        print(f"Backup FAILED: {detail}", file=sys.stderr)
        send_alert(
            env,
            subject="D Company ERP backup failed",
            body=(
                f"The nightly database backup failed at {datetime.now(timezone.utc).isoformat()}.\n\n"
                f"Error: {detail}\n\n"
                "The ERP itself is unaffected — this only means last night's off-site backup "
                "didn't happen. Check `systemctl status dcompany-backup.service` on the server, "
                "or ask Claude to look into it."
            ),
        )
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
