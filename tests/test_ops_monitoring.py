from __future__ import annotations

import os
import subprocess
import tempfile
import time
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path
from unittest.mock import MagicMock, patch

from ops import backup_to_b2, runtime_monitor
from ops.android_update_channel import (
    AdvertisedAndroidRelease,
    ChannelMatrix,
    ChannelProbe,
)
from ops.runtime_monitor import (
    LOCAL_BACKUP_DIR,
    NOTIFICATION_RETRY_MAX_SECONDS,
    ContainerState,
    Issue,
    MonitorResult,
    check_android_update_channel,
    check_backup_freshness,
    evaluate_containers,
    notification_delivery_failed,
    notification_delivery_succeeded,
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
        with (
            self.assertRaises(SMTPConfigurationError),
            authenticated_smtp({"SMTP_HOST": "smtp.example.test"}),
        ):
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

    def test_failed_delivery_is_retried_after_persisted_backoff(self) -> None:
        failed = MonitorResult(
            issues=[Issue("disk_capacity", "disk is full")],
            metrics={},
            restart_counts={},
        )
        previous = {
            "issues": [],
            "notification_delivery": {
                "delivered_transition_key": "healthy",
                "pending_transition_key": None,
                "failed_attempts": 0,
                "next_retry_at": None,
            },
        }
        now = datetime(2026, 8, 30, 12, 0, tzinfo=timezone.utc)

        self.assertIsNotNone(notification_for(failed, previous, now=now))
        delivery = notification_delivery_failed(
            failed, previous, now=now, error=TimeoutError("smtp timeout")
        )
        persisted = {
            "issues": [{"code": "disk_capacity", "detail": "disk is full"}],
            "notification_delivery": delivery,
        }

        self.assertEqual("healthy", delivery["delivered_transition_key"])
        self.assertEqual("issues:disk_capacity", delivery["pending_transition_key"])
        self.assertEqual(1, delivery["failed_attempts"])
        self.assertIsNone(
            notification_for(failed, persisted, now=now + timedelta(minutes=4))
        )
        self.assertIsNotNone(
            notification_for(failed, persisted, now=now + timedelta(minutes=5))
        )

    def test_delivery_retry_backoff_is_bounded_and_success_suppresses_retry(
        self,
    ) -> None:
        failed = MonitorResult(
            issues=[Issue("http_readyz", "unreachable")],
            metrics={},
            restart_counts={},
        )
        previous: dict[str, object] = {
            "issues": [],
            "notification_delivery": {
                "delivered_transition_key": "healthy",
                "pending_transition_key": None,
                "failed_attempts": 0,
                "next_retry_at": None,
            },
        }
        now = datetime(2026, 8, 30, 12, 0, tzinfo=timezone.utc)
        delivery: dict[str, object] = {}
        for attempt in range(1, 16):
            delivery = notification_delivery_failed(
                failed,
                previous,
                now=now,
                error=ConnectionError("smtp unavailable"),
            )
            self.assertEqual(min(attempt, 11), delivery["failed_attempts"])
            previous = {
                "issues": [{"code": "http_readyz"}],
                "notification_delivery": delivery,
            }

        retry_at = datetime.fromisoformat(str(delivery["next_retry_at"]))
        self.assertLessEqual(
            (retry_at - now).total_seconds(), NOTIFICATION_RETRY_MAX_SECONDS
        )

        succeeded = notification_delivery_succeeded(failed, previous, now=retry_at)
        delivered_state = {
            "issues": [{"code": "http_readyz"}],
            "notification_delivery": succeeded,
        }
        self.assertEqual("issues:http_readyz", succeeded["delivered_transition_key"])
        self.assertIsNone(notification_for(failed, delivered_state, now=retry_at))

    def test_main_persists_pending_delivery_when_smtp_fails(self) -> None:
        failed = MonitorResult(
            issues=[Issue("backend_errors", "one error")],
            metrics={},
            restart_counts={},
        )
        previous = {
            "issues": [],
            "notification_delivery": {
                "delivered_transition_key": "healthy",
                "pending_transition_key": None,
                "failed_attempts": 0,
                "next_retry_at": None,
            },
        }
        with (
            patch("ops.runtime_monitor.sys.argv", ["runtime_monitor.py"]),
            patch("ops.runtime_monitor.load_env", return_value={}),
            patch("ops.runtime_monitor.load_previous_state", return_value=previous),
            patch("ops.runtime_monitor.run_monitor", return_value=failed),
            patch(
                "ops.runtime_monitor.send_notice",
                side_effect=TimeoutError("smtp timeout"),
            ),
            patch("ops.runtime_monitor.save_state") as save_state,
        ):
            exit_code = runtime_monitor.main()

        self.assertEqual(1, exit_code)
        delivery = save_state.call_args.kwargs["notification_delivery"]
        self.assertEqual("healthy", delivery["delivered_transition_key"])
        self.assertEqual("issues:backend_errors", delivery["pending_transition_key"])
        self.assertEqual(1, delivery["failed_attempts"])

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

    @patch("ops.runtime_monitor.verify_public_artifact")
    @patch("ops.runtime_monitor.verify_local_artifact")
    @patch("ops.runtime_monitor.fetch_channel_matrix")
    def test_active_android_release_checks_local_bytes_and_public_headers(
        self,
        fetch_matrix: MagicMock,
        verify_local: MagicMock,
        verify_public: MagicMock,
    ) -> None:
        release = AdvertisedAndroidRelease(
            version_code=15,
            version_name="3.1.4",
            url=(
                "https://dcompany.duckdns.org/downloads/android/"
                "d-company-erp-v3.1.4-direct.apk"
            ),
            sha256="ab" * 32,
            size_bytes=123,
            signing_certificate_sha256="cd" * 32,
        )
        fetch_matrix.return_value = ChannelMatrix(
            ChannelProbe(8, 15, release, {}, 3),
            (1, 8, 14, 15),
        )

        issues, metrics = check_android_update_channel(
            "https://dcompany.duckdns.org",
            release_dir=Path("/safe/releases"),
        )

        self.assertEqual([], issues)
        verify_local.assert_called_once_with(
            Path("/safe/releases/d-company-erp-v3.1.4-direct.apk"),
            release,
        )
        verify_public.assert_called_once_with(
            release,
            timeout_seconds=15,
            download_body=False,
        )
        self.assertEqual(15, metrics["android_latest_version_code"])
        self.assertEqual(3, metrics["android_policy_revision"])

    @patch("ops.runtime_monitor.fetch_channel_matrix")
    def test_dormant_android_channel_is_safe_and_does_not_fetch_an_apk(
        self,
        fetch_matrix: MagicMock,
    ) -> None:
        fetch_matrix.return_value = ChannelMatrix(
            ChannelProbe(8, 8, None, {}, 3),
            (1, 8, 14),
        )

        issues, metrics = check_android_update_channel("https://dcompany.duckdns.org")

        self.assertEqual([], issues)
        self.assertEqual(0, metrics["android_release_advertised"])


class BackupRuntimeTest(unittest.TestCase):
    def test_dump_is_validated_then_atomically_published(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            destination = Path(temporary) / "nightly.dump"
            commands: list[list[str]] = []

            def run(command, **kwargs):
                commands.append(command)
                if "pg_dump" in command:
                    kwargs["stdout"].write(b"valid-custom-archive")
                else:
                    self.assertIn("pg_restore", command)
                    self.assertEqual(b"valid-custom-archive", kwargs["stdin"].read())
                return subprocess.CompletedProcess(command, 0)

            with patch("ops.backup_to_b2.subprocess.run", side_effect=run):
                backup_to_b2.run_pg_dump(destination)

            self.assertEqual(b"valid-custom-archive", destination.read_bytes())
            self.assertFalse(destination.with_name("nightly.dump.part").exists())
            self.assertIn("pg_dump", commands[0])
            self.assertIn("pg_restore", commands[1])
            self.assertIn("--list", commands[1])

    def test_invalid_dump_removes_partial_and_never_publishes_final(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            destination = Path(temporary) / "nightly.dump"

            def run(command, **kwargs):
                if "pg_dump" in command:
                    kwargs["stdout"].write(b"truncated-custom-archive")
                    return subprocess.CompletedProcess(command, 0)
                raise subprocess.CalledProcessError(
                    1, command, stderr=b"archive is corrupt"
                )

            with (
                patch("ops.backup_to_b2.subprocess.run", side_effect=run),
                self.assertRaises(subprocess.CalledProcessError),
            ):
                backup_to_b2.run_pg_dump(destination)

            self.assertFalse(destination.exists())
            self.assertFalse(destination.with_name("nightly.dump.part").exists())

    def test_upload_requires_remote_size_to_match(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            local_path = Path(temporary) / "nightly.dump"
            local_path.write_bytes(b"verified")
            client = MagicMock()
            client.head_object.return_value = {"ContentLength": 7}

            with self.assertRaisesRegex(RuntimeError, "size mismatch"):
                backup_to_b2.upload(client, local_path)

            client.upload_file.assert_called_once_with(
                str(local_path), backup_to_b2.B2_BUCKET, local_path.name
            )

    def test_local_cleanup_preserves_verified_recovery_point(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            backup_dir = Path(temporary)
            preserved = backup_dir / "verified.dump"
            expired = backup_dir / "expired.dump"
            preserved.write_bytes(b"verified")
            expired.write_bytes(b"old")
            stale_time = time.time() - (backup_to_b2.RETENTION_DAYS + 1) * 86400
            os.utime(preserved, (stale_time, stale_time))
            os.utime(expired, (stale_time, stale_time))

            with patch("ops.backup_to_b2.LOCAL_BACKUP_DIR", backup_dir):
                backup_to_b2.cleanup_local(preserve_verified=preserved)

            self.assertTrue(preserved.exists())
            self.assertFalse(expired.exists())

    def test_failed_upload_prunes_only_expired_superseded_local_dumps(self) -> None:
        def write_verified(path: Path) -> None:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(b"verified")

        with tempfile.TemporaryDirectory() as temporary:
            with (
                patch("ops.backup_to_b2.LOCAL_BACKUP_DIR", Path(temporary)),
                patch("ops.backup_to_b2.load_env", return_value={}),
                patch("ops.backup_to_b2.run_pg_dump", side_effect=write_verified),
                patch("ops.backup_to_b2.b2_client", return_value=object()),
                patch("ops.backup_to_b2.upload", side_effect=RuntimeError("offline")),
                patch("ops.backup_to_b2.cleanup_remote") as cleanup_remote,
                patch("ops.backup_to_b2.cleanup_local") as cleanup_local,
                patch("ops.backup_to_b2.send_alert"),
            ):
                result = backup_to_b2.main()

            self.assertEqual(1, result)
            cleanup_remote.assert_not_called()
            cleanup_local.assert_called_once()
            self.assertTrue(
                cleanup_local.call_args.kwargs["preserve_verified"].is_file()
            )

    def test_cleanup_after_failed_dump_preserves_newest_prior_recovery_point(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            backup_dir = Path(temporary)
            newest = backup_dir / "newest.dump"
            expired = backup_dir / "expired.dump"
            newest.write_bytes(b"known-good")
            expired.write_bytes(b"old")
            stale_time = time.time() - (backup_to_b2.RETENTION_DAYS + 1) * 86400
            os.utime(expired, (stale_time, stale_time))

            with patch("ops.backup_to_b2.LOCAL_BACKUP_DIR", backup_dir):
                backup_to_b2.cleanup_local()

            self.assertTrue(newest.exists())
            self.assertFalse(expired.exists())

    def test_local_cleanup_removes_abandoned_partial_without_any_final_dump(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            backup_dir = Path(temporary)
            abandoned = backup_dir / "interrupted.dump.part"
            recent = backup_dir / "running.dump.part"
            abandoned.write_bytes(b"partial")
            recent.write_bytes(b"partial")
            stale_time = time.time() - (
                backup_to_b2.PARTIAL_RETENTION_HOURS + 1
            ) * 3600
            os.utime(abandoned, (stale_time, stale_time))

            with patch("ops.backup_to_b2.LOCAL_BACKUP_DIR", backup_dir):
                backup_to_b2.cleanup_local()

            self.assertFalse(abandoned.exists())
            self.assertTrue(recent.exists())


class BackupServiceInstallationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.repo = Path(__file__).resolve().parents[1]

    def test_backup_and_monitor_use_snapshot_independent_storage(self) -> None:
        backup_source = (self.repo / "ops/backup_to_b2.py").read_text()

        self.assertEqual(Path("/var/lib/dcompany-erp/backups/auto"), LOCAL_BACKUP_DIR)
        self.assertIn(
            'LOCAL_BACKUP_DIR = Path("/var/lib/dcompany-erp/backups/auto")',
            backup_source,
        )

    def test_backup_unit_uses_persistent_runtime_and_hardening(self) -> None:
        service = (self.repo / "infra/systemd/dcompany-backup.service").read_text()

        self.assertIn(
            "ExecStart=/var/lib/dcompany-erp/backup-runtime/current/bin/python ",
            service,
        )
        self.assertNotIn("/opt/d-company-erp/ops/venv", service)
        for directive in (
            "NoNewPrivileges=true",
            "ProtectSystem=strict",
            "PrivateTmp=true",
            "CapabilityBoundingSet=",
            "ReadWritePaths=/var/lib/dcompany-erp/backups",
            "UMask=0077",
        ):
            self.assertIn(directive, service)

    def test_backup_timer_preserves_live_schedule(self) -> None:
        timer = (self.repo / "infra/systemd/dcompany-backup.timer").read_text()

        self.assertIn("OnCalendar=*-*-* 22:00:00 UTC", timer)
        self.assertIn("Persistent=true", timer)

    def test_installer_bootstraps_locked_runtime_and_both_timers(self) -> None:
        installer = (
            self.repo / "infra/scripts/install-operations-monitor.sh"
        ).read_text()

        for contract in (
            "ops/backup-requirements.lock",
            "STATE_ROOT=/var/lib/dcompany-erp",
            "--require-hashes",
            "--only-binary=:all:",
            "cp -p -n",
            "dcompany-backup.service",
            "systemctl enable --now dcompany-backup.timer",
        ):
            self.assertIn(contract, installer)

        self.assertLess(
            installer.index("systemctl enable --now dcompany-backup.timer"),
            installer.index("systemctl start dcompany-runtime-monitor.service"),
        )

    def test_installer_seeds_only_missing_or_previously_failed_backup(self) -> None:
        installer = (
            self.repo / "infra/scripts/install-operations-monitor.sh"
        ).read_text()

        self.assertIn("has_usable_backup()", installer)
        self.assertIn("-name '*.dump' -size +0c", installer)
        self.assertIn(
            'if ! has_usable_backup || [ "$backup_service_was_failed" -eq 1 ]; then',
            installer,
        )
        self.assertEqual(1, installer.count("systemctl start dcompany-backup.service"))
        self.assertLess(
            installer.index("systemctl start dcompany-backup.service"),
            installer.index("systemctl enable --now dcompany-backup.timer"),
        )
        self.assertLess(
            installer.index("systemctl start dcompany-backup.service"),
            installer.index("systemctl start dcompany-runtime-monitor.service"),
        )

    def test_backup_dependency_graph_is_fully_pinned_and_hashed(self) -> None:
        requirements = (
            (self.repo / "ops/backup-requirements.lock").read_text().splitlines()
        )
        package_lines = [
            line for line in requirements if line and not line.startswith(("#", " "))
        ]
        hash_lines = [line.strip() for line in requirements if "--hash=" in line]

        self.assertEqual(7, len(package_lines))
        self.assertEqual(7, len(hash_lines))
        self.assertTrue(
            all("==" in line and line.endswith("\\") for line in package_lines)
        )
        self.assertTrue(
            all(
                line.startswith("--hash=sha256:") and len(line.split(":", 1)[1]) == 64
                for line in hash_lines
            )
        )


if __name__ == "__main__":
    unittest.main()
