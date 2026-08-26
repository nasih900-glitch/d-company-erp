from __future__ import annotations

import os
import tempfile
import time
import unittest
from pathlib import Path
from unittest.mock import MagicMock, patch

from ops.runtime_monitor import (
    ContainerState,
    Issue,
    MonitorResult,
    check_backup_freshness,
    evaluate_containers,
    notification_for,
)
from ops.smtp_client import (
    SMTPConfigurationError,
    alert_recipients,
    authenticated_smtp,
)


class SMTPClientTest(unittest.TestCase):
    def test_alert_recipients_prefers_explicit_list_and_deduplicates(self) -> None:
        self.assertEqual(
            ["ops@example.test", "owner@example.test"],
            alert_recipients(
                {
                    "ALERT_RECIPIENT_EMAILS": (
                        "ops@example.test; owner@example.test,ops@example.test"
                    ),
                    "ACCOUNT_SECURITY_EMAIL": "fallback@example.test",
                }
            ),
        )

    def test_alert_recipients_falls_back_to_security_mailbox(self) -> None:
        self.assertEqual(
            ["owner@example.test"],
            alert_recipients({"ACCOUNT_SECURITY_EMAIL": "owner@example.test"}),
        )

    @patch("ops.smtp_client.smtplib.SMTP_SSL")
    def test_port_465_uses_implicit_tls(self, smtp_ssl: MagicMock) -> None:
        session = smtp_ssl.return_value.__enter__.return_value
        env = {
            "SMTP_HOST": "smtp.example.test",
            "SMTP_PORT": "465",
            "SMTP_USER": "user",
            "SMTP_PASSWORD": "secret",
        }

        with authenticated_smtp(env) as opened:
            self.assertIs(opened, session)

        session.login.assert_called_once_with("user", "secret")

    @patch("ops.smtp_client.smtplib.SMTP")
    def test_port_587_negotiates_starttls_before_login(self, smtp: MagicMock) -> None:
        session = smtp.return_value.__enter__.return_value
        env = {
            "SMTP_HOST": "smtp.example.test",
            "SMTP_PORT": "587",
            "SMTP_USER": "user",
            "SMTP_PASSWORD": "secret",
        }

        with authenticated_smtp(env) as opened:
            self.assertIs(opened, session)

        session.starttls.assert_called_once()
        session.login.assert_called_once_with("user", "secret")
        self.assertEqual(2, session.ehlo.call_count)

    def test_credentials_are_required(self) -> None:
        with self.assertRaises(SMTPConfigurationError):
            with authenticated_smtp({"SMTP_HOST": "smtp.example.test"}):
                pass


class RuntimeMonitorTest(unittest.TestCase):
    def healthy_states(self) -> list[ContainerState]:
        return [
            ContainerState(
                service=service,
                container_id=f"id-{service}",
                status="running",
                health="healthy"
                if service in {"backend", "frontend", "postgres", "redis"}
                else None,
                restart_count=0,
                oom_killed=False,
            )
            for service in (
                "caddy",
                "postgres",
                "redis",
                "minio",
                "backend",
                "frontend",
            )
        ]

    def test_healthy_containers_have_no_issues(self) -> None:
        issues, counts = evaluate_containers(self.healthy_states(), {})

        self.assertEqual([], issues)
        self.assertEqual(0, counts["backend"])

    def test_missing_unhealthy_and_new_restart_are_reported(self) -> None:
        states = self.healthy_states()
        states = [state for state in states if state.service != "minio"]
        states = [
            ContainerState(
                service=state.service,
                container_id=state.container_id,
                status="exited" if state.service == "backend" else state.status,
                health="unhealthy" if state.service == "backend" else state.health,
                restart_count=2 if state.service == "backend" else state.restart_count,
                oom_killed=state.service == "backend",
            )
            for state in states
        ]

        issues, _ = evaluate_containers(states, {"backend": 1})

        self.assertEqual(
            {
                "containers_missing",
                "container_backend",
                "health_backend",
                "oom_backend",
                "restart_backend",
            },
            {issue.code for issue in issues},
        )

    def test_alerts_only_when_issue_codes_change_and_on_recovery(self) -> None:
        failed = MonitorResult(
            issues=[Issue("disk_capacity", "disk is full")],
            metrics={},
            restart_counts={},
        )
        previous_failed = {
            "issues": [{"code": "disk_capacity", "detail": "older detail"}]
        }

        self.assertIsNone(notification_for(failed, previous_failed))
        recovered = MonitorResult(issues=[], metrics={}, restart_counts={})
        notice = notification_for(recovered, previous_failed)
        self.assertIsNotNone(notice)
        assert notice is not None
        self.assertIn("recovered", notice[0].lower())

    def test_backup_freshness_detects_stale_and_empty_files(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            backup_dir = Path(temporary)
            backup = backup_dir / "backup.dump"
            backup.write_bytes(b"")
            stale_time = time.time() - 48 * 3600
            os.utime(backup, (stale_time, stale_time))
            with patch("ops.runtime_monitor.LOCAL_BACKUP_DIR", backup_dir):
                issues, age = check_backup_freshness(30)

        self.assertIsNotNone(age)
        self.assertEqual(
            {"backup_empty", "backup_stale"}, {issue.code for issue in issues}
        )


if __name__ == "__main__":
    unittest.main()
