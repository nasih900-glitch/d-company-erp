#!/usr/bin/env python3
"""Production runtime, crash, capacity, and backup-freshness monitor.

This host-side check complements the public health endpoints. It detects
container exits/restarts/OOM kills, backend exception logs, slow public
responses, low disk or memory, and stale local backups. A systemd timer runs
it every five minutes and alerts only when the set of failing checks changes.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.request
from dataclasses import asdict, dataclass
from datetime import datetime, timezone
from email.mime.text import MIMEText
from pathlib import Path
from typing import Any

try:
    from ops.android_update_channel import (
        AndroidUpdateChannelError,
        fetch_channel_matrix,
        verify_local_artifact,
        verify_public_artifact,
    )
except ModuleNotFoundError:  # direct execution from /opt/d-company-erp/ops
    from android_update_channel import (  # type: ignore[no-redef]
        AndroidUpdateChannelError,
        fetch_channel_matrix,
        verify_local_artifact,
        verify_public_artifact,
    )

try:
    from ops.smtp_client import alert_recipients, authenticated_smtp
except ModuleNotFoundError:  # direct execution: python /opt/.../ops/runtime_monitor.py
    from smtp_client import alert_recipients, authenticated_smtp


ROOT = Path(os.getenv("DCOMPANY_ROOT", "/opt/d-company-erp"))
ENV_FILE = ROOT / ".env"
COMPOSE_FILE = ROOT / "docker-compose.prod.yml"
STATE_FILE = ROOT / "ops" / ".runtime_monitor_state.json"
LOCAL_BACKUP_DIR = Path("/var/lib/dcompany-erp/backups/auto")
ANDROID_RELEASE_DIR = ROOT / "releases/android"
DEFAULT_BASE_URL = "https://dcompany.duckdns.org"
EXPECTED_SERVICES = frozenset(
    {"caddy", "postgres", "redis", "minio", "backend", "frontend"}
)
ERROR_LINE_RE = re.compile(
    r'"level"\s*:\s*"(?:error|critical|fatal)"|Traceback \(most recent call last\)|'
    r"unhandled exception",
    re.IGNORECASE,
)


@dataclass(frozen=True)
class Issue:
    code: str
    detail: str


@dataclass(frozen=True)
class ContainerState:
    service: str
    container_id: str
    status: str
    health: str | None
    restart_count: int
    oom_killed: bool


@dataclass
class MonitorResult:
    issues: list[Issue]
    metrics: dict[str, float | int | str]
    restart_counts: dict[str, int]

    @property
    def ok(self) -> bool:
        return not self.issues


def load_env(path: Path = ENV_FILE) -> dict[str, str]:
    env: dict[str, str] = {}
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        env[key.strip()] = value.strip().strip('"').strip("'")
    return env


def load_previous_state(path: Path = STATE_FILE) -> dict[str, Any]:
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
    except FileNotFoundError:
        return {}
    except (OSError, json.JSONDecodeError) as exc:
        return {"state_error": str(exc)}
    return (
        payload
        if isinstance(payload, dict)
        else {"state_error": "state is not an object"}
    )


def save_state(result: MonitorResult, path: Path = STATE_FILE) -> None:
    payload = {
        "checked_at": datetime.now(timezone.utc).isoformat(),
        "ok": result.ok,
        "issues": [asdict(issue) for issue in result.issues],
        "metrics": result.metrics,
        "restart_counts": result.restart_counts,
    }
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(".tmp")
    temporary.write_text(json.dumps(payload, sort_keys=True) + "\n", encoding="utf-8")
    temporary.replace(path)


def _positive_float(env: dict[str, str], key: str, default: float) -> float:
    try:
        value = float(env.get(key, str(default)))
    except ValueError:
        return default
    return value if value > 0 else default


def check_public_endpoints(
    base_url: str, *, timeout_seconds: float, max_response_ms: float
) -> tuple[list[Issue], dict[str, float]]:
    issues: list[Issue] = []
    metrics: dict[str, float] = {}
    for endpoint in ("healthz", "readyz"):
        started = time.monotonic()
        try:
            with urllib.request.urlopen(
                f"{base_url.rstrip('/')}/{endpoint}", timeout=timeout_seconds
            ) as response:
                body = response.read().decode("utf-8", errors="replace")
                elapsed_ms = (time.monotonic() - started) * 1000
                metrics[f"{endpoint}_latency_ms"] = round(elapsed_ms, 1)
                if response.status != 200:
                    issues.append(Issue(f"http_{endpoint}", f"HTTP {response.status}"))
                    continue
                if endpoint == "readyz":
                    try:
                        readiness = json.loads(body)
                    except json.JSONDecodeError:
                        issues.append(
                            Issue("readyz_invalid", "response is not valid JSON")
                        )
                        continue
                    if readiness.get("status") not in {"ok", "ready"}:
                        issues.append(
                            Issue(
                                "readyz_degraded",
                                f"reported status {readiness.get('status')!r}",
                            )
                        )
                if elapsed_ms > max_response_ms:
                    issues.append(
                        Issue(
                            f"slow_{endpoint}",
                            f"{elapsed_ms:.0f} ms exceeds {max_response_ms:.0f} ms",
                        )
                    )
        except (urllib.error.URLError, TimeoutError, OSError) as exc:
            elapsed_ms = (time.monotonic() - started) * 1000
            metrics[f"{endpoint}_latency_ms"] = round(elapsed_ms, 1)
            issues.append(
                Issue(f"http_{endpoint}", f"unreachable: {type(exc).__name__}")
            )
    return issues, metrics


def check_android_update_channel(
    base_url: str,
    *,
    release_dir: Path = ANDROID_RELEASE_DIR,
    timeout_seconds: float = 15,
) -> tuple[list[Issue], dict[str, float | int | str]]:
    """Validate the active registry record, local bytes, and public headers.

    Hashing the local read-only APK every five minutes is inexpensive and
    proves the bytes Caddy can serve have not drifted.  The external monitor
    performs a periodic full HTTPS hash; this host check uses HEAD publicly to
    avoid transferring the same APK from the VPS back to itself every run.
    """
    try:
        matrix = fetch_channel_matrix(
            base_url,
            baseline_version_code=14,
            timeout_seconds=timeout_seconds,
        )
        probe = matrix.canonical
        metrics: dict[str, float | int | str] = {
            "android_minimum_version_code": probe.minimum_supported_version_code,
            "android_latest_version_code": probe.latest_version_code,
            "android_policy_revision": probe.policy_revision,
            "android_release_advertised": 1 if probe.release else 0,
            "android_probed_version_codes": ",".join(
                str(value) for value in matrix.probed_version_codes
            ),
        }
        if probe.release is None:
            return [], metrics
        local_path = release_dir / probe.release.filename
        verify_local_artifact(local_path, probe.release)
        verify_public_artifact(
            probe.release,
            timeout_seconds=timeout_seconds,
            download_body=False,
        )
        metrics.update(
            {
                "android_release_version_name": probe.release.version_name,
                "android_release_size_bytes": probe.release.size_bytes,
                "android_release_sha256": probe.release.sha256,
            }
        )
        return [], metrics
    except AndroidUpdateChannelError as exc:
        return [Issue("android_update_channel", str(exc))], {
            "android_release_advertised": "unknown"
        }


def inspect_containers() -> list[ContainerState]:
    compose_command = [
        "docker",
        "compose",
        "-f",
        str(COMPOSE_FILE),
        "--env-file",
        str(ENV_FILE),
        "ps",
        "-q",
    ]
    listed = subprocess.run(
        compose_command,
        cwd=ROOT,
        check=True,
        capture_output=True,
        text=True,
        timeout=30,
    )
    container_ids = listed.stdout.split()
    if not container_ids:
        return []
    inspected = subprocess.run(
        ["docker", "inspect", *container_ids],
        check=True,
        capture_output=True,
        text=True,
        timeout=30,
    )
    payload = json.loads(inspected.stdout)
    states: list[ContainerState] = []
    for item in payload:
        labels = item.get("Config", {}).get("Labels") or {}
        state = item.get("State") or {}
        health_payload = state.get("Health") or {}
        service = labels.get("com.docker.compose.service")
        if not service:
            continue
        states.append(
            ContainerState(
                service=service,
                container_id=str(item.get("Id", "")),
                status=str(state.get("Status", "unknown")),
                health=health_payload.get("Status"),
                restart_count=int(item.get("RestartCount", 0)),
                oom_killed=bool(state.get("OOMKilled", False)),
            )
        )
    return states


def evaluate_containers(
    states: list[ContainerState], previous_restart_counts: dict[str, int]
) -> tuple[list[Issue], dict[str, int]]:
    issues: list[Issue] = []
    by_service = {state.service: state for state in states}
    missing = sorted(EXPECTED_SERVICES - by_service.keys())
    if missing:
        issues.append(Issue("containers_missing", ", ".join(missing)))
    restart_counts: dict[str, int] = {}
    for service, state in sorted(by_service.items()):
        restart_counts[service] = state.restart_count
        if state.status != "running":
            issues.append(Issue(f"container_{service}", f"status is {state.status}"))
        if state.health not in {None, "healthy"}:
            issues.append(Issue(f"health_{service}", f"health is {state.health}"))
        if state.oom_killed:
            issues.append(Issue(f"oom_{service}", "container was OOM-killed"))
        previous = previous_restart_counts.get(service)
        if previous is not None and state.restart_count > previous:
            delta = state.restart_count - previous
            issues.append(
                Issue(
                    f"restart_{service}",
                    f"restart count increased by {delta} to {state.restart_count}",
                )
            )
    return issues, restart_counts


def check_backend_errors(
    states: list[ContainerState], *, since_minutes: int = 6
) -> tuple[list[Issue], int]:
    backend = next((state for state in states if state.service == "backend"), None)
    if backend is None or not backend.container_id:
        return [], 0
    result = subprocess.run(
        [
            "docker",
            "logs",
            "--since",
            f"{since_minutes}m",
            "--tail",
            "2000",
            backend.container_id,
        ],
        check=True,
        capture_output=True,
        text=True,
        timeout=30,
    )
    count = sum(
        1
        for line in (result.stdout + result.stderr).splitlines()
        if ERROR_LINE_RE.search(line)
    )
    issues = (
        [
            Issue(
                "backend_errors",
                f"{count} error/exception log lines in {since_minutes} minutes",
            )
        ]
        if count
        else []
    )
    return issues, count


def check_capacity(
    *, disk_warning_percent: float, memory_warning_percent: float
) -> tuple[list[Issue], dict[str, float]]:
    issues: list[Issue] = []
    disk = shutil.disk_usage(ROOT)
    disk_used_percent = 100.0 * (disk.total - disk.free) / disk.total
    if disk_used_percent >= disk_warning_percent:
        issues.append(
            Issue(
                "disk_capacity",
                (
                    f"disk usage {disk_used_percent:.1f}% is at or above "
                    f"{disk_warning_percent:.1f}%"
                ),
            )
        )

    memory: dict[str, int] = {}
    with Path("/proc/meminfo").open(encoding="utf-8") as meminfo:
        for line in meminfo:
            key, raw_value = line.split(":", 1)
            if key in {"MemTotal", "MemAvailable"}:
                memory[key] = int(raw_value.strip().split()[0])
    total = memory.get("MemTotal", 0)
    available = memory.get("MemAvailable", 0)
    memory_available_percent = 100.0 * available / total if total else 0.0
    if not total:
        issues.append(Issue("memory_metrics", "could not read total memory"))
    elif memory_available_percent <= memory_warning_percent:
        issues.append(
            Issue(
                "memory_capacity",
                f"available memory {memory_available_percent:.1f}% is at or below "
                f"{memory_warning_percent:.1f}%",
            )
        )
    return issues, {
        "disk_used_percent": round(disk_used_percent, 1),
        "memory_available_percent": round(memory_available_percent, 1),
    }


def check_backup_freshness(max_age_hours: float) -> tuple[list[Issue], float | None]:
    backups = list(LOCAL_BACKUP_DIR.glob("*.dump")) if LOCAL_BACKUP_DIR.exists() else []
    if not backups:
        return [Issue("backup_missing", "no local database backup exists")], None
    latest = max(backups, key=lambda path: path.stat().st_mtime)
    age_hours = (time.time() - latest.stat().st_mtime) / 3600
    issues = []
    if latest.stat().st_size <= 0:
        issues.append(Issue("backup_empty", f"latest backup {latest.name} is empty"))
    if age_hours > max_age_hours:
        issues.append(
            Issue(
                "backup_stale",
                (
                    f"latest backup is {age_hours:.1f} hours old; "
                    f"limit is {max_age_hours:.1f}"
                ),
            )
        )
    return issues, round(age_hours, 1)


def run_monitor(env: dict[str, str], previous: dict[str, Any]) -> MonitorResult:
    issues: list[Issue] = []
    metrics: dict[str, float | int | str] = {}
    base_url = env.get("PUBLIC_URL", DEFAULT_BASE_URL).rstrip("/")
    public_issues, public_metrics = check_public_endpoints(
        base_url,
        timeout_seconds=_positive_float(env, "MONITOR_HTTP_TIMEOUT_SECONDS", 10),
        max_response_ms=_positive_float(env, "MONITOR_MAX_RESPONSE_MS", 2500),
    )
    issues.extend(public_issues)
    metrics.update(public_metrics)

    update_issues, update_metrics = check_android_update_channel(
        base_url,
        timeout_seconds=_positive_float(env, "MONITOR_HTTP_TIMEOUT_SECONDS", 10),
    )
    issues.extend(update_issues)
    metrics.update(update_metrics)

    states: list[ContainerState] = []
    restart_counts: dict[str, int] = {}
    try:
        states = inspect_containers()
        raw_previous_counts = previous.get("restart_counts", {})
        previous_counts = (
            {str(key): int(value) for key, value in raw_previous_counts.items()}
            if isinstance(raw_previous_counts, dict)
            else {}
        )
        container_issues, restart_counts = evaluate_containers(states, previous_counts)
        issues.extend(container_issues)
        log_issues, error_count = check_backend_errors(states)
        issues.extend(log_issues)
        metrics["backend_error_lines"] = error_count
    except (
        OSError,
        subprocess.SubprocessError,
        json.JSONDecodeError,
        ValueError,
    ) as exc:
        issues.append(
            Issue(
                "docker_monitor", f"container inspection failed: {type(exc).__name__}"
            )
        )

    try:
        capacity_issues, capacity_metrics = check_capacity(
            disk_warning_percent=_positive_float(
                env, "MONITOR_DISK_WARNING_PERCENT", 85
            ),
            memory_warning_percent=_positive_float(
                env, "MONITOR_MEMORY_WARNING_PERCENT", 10
            ),
        )
        issues.extend(capacity_issues)
        metrics.update(capacity_metrics)
    except OSError as exc:
        issues.append(
            Issue(
                "capacity_monitor", f"capacity inspection failed: {type(exc).__name__}"
            )
        )

    try:
        backup_issues, age_hours = check_backup_freshness(
            _positive_float(env, "BACKUP_MAX_AGE_HOURS", 30)
        )
        issues.extend(backup_issues)
        if age_hours is not None:
            metrics["backup_age_hours"] = age_hours
    except OSError as exc:
        issues.append(
            Issue("backup_monitor", f"backup inspection failed: {type(exc).__name__}")
        )

    if previous.get("state_error"):
        issues.append(
            Issue(
                "monitor_state",
                "previous state file was unreadable and has been replaced",
            )
        )
    return MonitorResult(issues=issues, metrics=metrics, restart_counts=restart_counts)


def send_notice(env: dict[str, str], subject: str, body: str) -> None:
    recipients = alert_recipients(env)
    if not recipients:
        raise RuntimeError(
            "ALERT_RECIPIENT_EMAILS or ACCOUNT_SECURITY_EMAIL is required"
        )
    from_email = env.get("FROM_EMAIL") or env.get("SMTP_USER")
    if not from_email:
        raise RuntimeError("FROM_EMAIL or SMTP_USER is required")
    message = MIMEText(body)
    message["Subject"] = subject
    message["From"] = f"{env.get('FROM_NAME', 'D Company ERP')} <{from_email}>"
    message["To"] = ", ".join(recipients)
    with authenticated_smtp(env, timeout_seconds=30) as session:
        session.send_message(message)


def notification_for(
    result: MonitorResult, previous: dict[str, Any]
) -> tuple[str, str] | None:
    previous_issues = previous.get("issues", [])
    previous_codes = {
        item.get("code")
        for item in previous_issues
        if isinstance(item, dict) and item.get("code")
    }
    current_codes = {issue.code for issue in result.issues}
    if current_codes == previous_codes:
        return None
    if result.ok:
        return (
            "D Company ERP runtime recovered",
            "All production runtime checks are healthy again.\n\n"
            + json.dumps(result.metrics, indent=2, sort_keys=True),
        )
    lines = "\n".join(f"- {issue.code}: {issue.detail}" for issue in result.issues)
    return (
        "D Company ERP runtime alert",
        (
            f"Production monitoring detected the following issue(s):\n\n{lines}\n\n"
            f"Metrics:\n{json.dumps(result.metrics, indent=2, sort_keys=True)}"
        ),
    )


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--test-email",
        action="store_true",
        help="send one explicit SMTP verification message and exit",
    )
    parser.add_argument(
        "--no-email",
        action="store_true",
        help="run checks without sending state-change email",
    )
    args = parser.parse_args()
    try:
        env = load_env()
    except OSError as exc:
        print(f"Runtime monitor could not read {ENV_FILE}: {exc}", file=sys.stderr)
        return 2

    if args.test_email:
        try:
            send_notice(
                env,
                "D Company ERP SMTP verification",
                (
                    "SMTP authentication, TLS negotiation, and delivery were "
                    "requested by the production release verification process."
                ),
            )
        except Exception as exc:  # noqa: BLE001 - CLI must report transport failures
            print(
                f"SMTP verification FAILED: {type(exc).__name__}: {exc}",
                file=sys.stderr,
            )
            return 1
        print("SMTP verification message accepted by the configured server.")
        return 0

    previous = load_previous_state()
    result = run_monitor(env, previous)
    notice = notification_for(result, previous)
    if notice and not args.no_email:
        try:
            send_notice(env, *notice)
        except Exception as exc:  # noqa: BLE001 - health result must still be persisted
            print(
                f"Runtime alert delivery FAILED: {type(exc).__name__}: {exc}",
                file=sys.stderr,
            )
    save_state(result)
    print(
        json.dumps(
            {
                "ok": result.ok,
                "issues": [asdict(issue) for issue in result.issues],
                "metrics": result.metrics,
            },
            sort_keys=True,
        )
    )
    return 0 if result.ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
