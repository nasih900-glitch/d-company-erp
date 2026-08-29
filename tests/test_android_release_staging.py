from __future__ import annotations

import hashlib
import json
import os
import tempfile
import unittest
from argparse import Namespace
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

from ops import stage_android_release as staging

BASE_URL = "https://dcompany.duckdns.org"
SIGNER = "55" * 32


class AndroidReleaseStagingTest(unittest.TestCase):
    def make_package(self, root: Path) -> tuple[Path, Path, dict]:
        apk = root / "d-company-erp-v3.1.4-direct.apk"
        apk.write_bytes(b"immutable-signed-apk")
        payload = {
            "api_base_url": f"{BASE_URL}/api/v1/",
            "application_id": "cloud.dcompany.erp",
            "variant": "directRelease",
            "version_code": 15,
            "version_name": "3.1.4",
            "apk_filename": apk.name,
            "apk_download_url": f"{BASE_URL}/downloads/android/{apk.name}",
            "apk_sha256": hashlib.sha256(apk.read_bytes()).hexdigest(),
            "apk_size_bytes": apk.stat().st_size,
            "signing_certificate_sha256": SIGNER,
            "git_sha": "a" * 40,
            "release_ref": "v3.1.4",
            "workflow_run_id": "12345",
            "workflow_run_attempt": "1",
        }
        manifest = root / "release-manifest.json"
        manifest.write_text(json.dumps(payload), encoding="utf-8")
        return manifest, apk, payload

    def test_exact_ci_manifest_and_bytes_are_required(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            manifest_path, apk, _ = self.make_package(Path(temporary))
            manifest = staging.load_ci_manifest(manifest_path, base_url=BASE_URL)

            staging.verify_ci_apk_bytes(apk, manifest)
            apk.write_bytes(b"substituted")
            with self.assertRaisesRegex(staging.AndroidReleaseStagingError, "size|SHA"):
                staging.verify_ci_apk_bytes(apk, manifest)

    def test_registry_manifest_is_strict_and_contains_no_activation_authority(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            manifest_path, _, _ = self.make_package(Path(temporary))
            ci = staging.load_ci_manifest(manifest_path, base_url=BASE_URL)

        payload = staging.registry_manifest(ci, release_notes="Verified gaming update")

        self.assertEqual(
            {
                "version_code",
                "version_name",
                "channel",
                "update_url",
                "release_notes",
                "apk_sha256",
                "apk_size_bytes",
                "apk_signing_cert_sha256",
                "source_git_sha",
                "source_release_ref",
                "source_workflow_run_id",
                "source_workflow_run_attempt",
            },
            set(payload),
        )
        self.assertEqual("a" * 40, payload["source_git_sha"])
        self.assertEqual("v3.1.4", payload["source_release_ref"])
        self.assertEqual(12345, payload["source_workflow_run_id"])
        self.assertEqual(1, payload["source_workflow_run_attempt"])
        self.assertNotIn("status", payload)
        self.assertNotIn("minimum_supported_version_code", payload)

    def test_android_tools_must_agree_with_manifest_identity(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            manifest_path, apk, _ = self.make_package(Path(temporary))
            ci = staging.load_ci_manifest(manifest_path, base_url=BASE_URL)
            outputs = iter(
                [
                    "cloud.dcompany.erp",
                    "15",
                    "3.1.4",
                    (
                        "Verified using v2 scheme (APK Signature Scheme v2): true\n"
                        f"Signer #1 certificate SHA-256 digest: {SIGNER}"
                    ),
                ]
            )
            with (
                patch.object(
                    staging,
                    "_discover_android_tool",
                    side_effect=["apkanalyzer", "apksigner"],
                ),
                patch.object(
                    staging,
                    "_run_checked",
                    side_effect=lambda _command: next(outputs),
                ),
            ):
                evidence = staging.verify_apk_identity_and_signer(apk, ci)

        self.assertEqual(15, evidence.version_code)
        self.assertEqual(SIGNER, evidence.signing_certificate_sha256)
        self.assertTrue(evidence.verified_v2_or_newer)

    def test_remote_publish_is_atomic_and_only_reuses_identical_bytes(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            release_dir = root / "releases/android"
            release_dir.mkdir(parents=True)
            filename = "d-company-erp-v3.1.4-direct.apk"
            temporary_apk = release_dir / f".{filename}.{'a' * 32}.part"
            final_apk = release_dir / filename
            body = b"verified"
            payload = {
                "remote_root": str(root),
                "remote_temp": str(temporary_apk),
                "remote_final": str(final_apk),
                "apk_filename": filename,
                "apk_sha256": hashlib.sha256(body).hexdigest(),
                "apk_size_bytes": len(body),
            }
            with patch.object(
                staging,
                "_atomic_rename_noreplace",
                side_effect=lambda source, destination: os.rename(source, destination),
            ):
                prepared = staging._remote_action("prepare", payload)
                temporary_apk.write_bytes(body)
                published = staging._remote_action("publish", payload)

            self.assertTrue(prepared["ok"])
            self.assertTrue(published["ok"])
            self.assertEqual(body, final_apk.read_bytes())
            resumed = staging._remote_action("prepare", payload)
            self.assertTrue(resumed["already_published"])

            final_apk.write_bytes(b"substituted")
            with self.assertRaisesRegex(
                staging.AndroidReleaseStagingError, "different bytes"
            ):
                staging._remote_action("prepare", payload)

    def test_ci_manifest_schema_does_not_accept_operator_added_fields(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            manifest_path, _, payload = self.make_package(Path(temporary))
            payload["minimum_supported_version_code"] = 1
            manifest_path.write_text(json.dumps(payload), encoding="utf-8")

            with self.assertRaisesRegex(
                staging.AndroidReleaseStagingError, "unexpected"
            ):
                staging.load_ci_manifest(manifest_path, base_url=BASE_URL)

    def test_ci_manifest_rejects_duplicate_json_keys(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            manifest_path, _, _ = self.make_package(Path(temporary))
            manifest_path.write_text('{"version_code":15,"version_code":14}')

            with self.assertRaisesRegex(
                staging.AndroidReleaseStagingError, "repeats key"
            ):
                staging.load_ci_manifest(manifest_path, base_url=BASE_URL)

    def test_manual_code_14_baseline_cannot_be_staged_as_its_own_update(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            manifest_path, _, payload = self.make_package(Path(temporary))
            payload["version_code"] = 14
            manifest_path.write_text(json.dumps(payload), encoding="utf-8")

            with self.assertRaisesRegex(
                staging.AndroidReleaseStagingError, "begins at version code 15"
            ):
                staging.load_ci_manifest(manifest_path, base_url=BASE_URL)

    def test_ci_workflow_provenance_must_be_canonical_and_bounded(self) -> None:
        invalid = {
            "workflow_run_id": ("001", "workflow_run_id"),
            "workflow_run_attempt": ("0", "workflow_run_attempt"),
        }
        for field, (value, expected) in invalid.items():
            with tempfile.TemporaryDirectory() as temporary:
                manifest_path, _, payload = self.make_package(Path(temporary))
                payload[field] = value
                manifest_path.write_text(json.dumps(payload), encoding="utf-8")
                with self.assertRaisesRegex(
                    staging.AndroidReleaseStagingError,
                    expected,
                ):
                    staging.load_ci_manifest(manifest_path, base_url=BASE_URL)

    def test_release_notes_cannot_inject_multiline_configuration(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            manifest_path, _, _ = self.make_package(Path(temporary))
            ci = staging.load_ci_manifest(manifest_path, base_url=BASE_URL)
        with self.assertRaisesRegex(staging.AndroidReleaseStagingError, "printable"):
            staging.registry_manifest(ci, release_notes="good\nANDROID_MIN=1")

    def test_backend_receipt_must_echo_the_exact_manifest_hash(self) -> None:
        manifest = {
            "version_code": 15,
            "version_name": "3.1.4",
            "channel": "direct",
            "update_url": (
                f"{BASE_URL}/downloads/android/d-company-erp-v3.1.4-direct.apk"
            ),
            "release_notes": "Verified gaming update",
            "apk_sha256": "ab" * 32,
            "apk_size_bytes": 123,
            "apk_signing_cert_sha256": SIGNER,
            "source_git_sha": "a" * 40,
            "source_release_ref": "v3.1.4",
            "source_workflow_run_id": 12345,
            "source_workflow_run_attempt": 1,
        }
        target = staging.RemoteTarget(
            host="root@example.test",
            key=Path("/safe/key"),
            port=22,
            root="/opt/d-company-erp",
        )
        with (
            patch.object(
                staging.subprocess,
                "run",
                return_value=SimpleNamespace(
                    stdout=json.dumps(
                        {
                            "id": "11111111-1111-1111-1111-111111111111",
                            "status": "staged",
                            "manifest_sha256": "00" * 32,
                        }
                    ).encode()
                ),
            ),
            self.assertRaisesRegex(staging.AndroidReleaseStagingError, "receipt"),
        ):
            staging._register_staged_release(target, manifest)

    def test_remote_command_fields_reject_shell_metacharacters(self) -> None:
        with self.assertRaisesRegex(staging.AndroidReleaseStagingError, "SSH host"):
            staging._validated_remote_target(
                staging.RemoteTarget(
                    host="root@example.test;touch /tmp/unsafe",
                    key=Path("/safe/key"),
                    port=22,
                    root="/opt/d-company-erp",
                )
            )

    def test_verification_only_does_not_require_vps_credentials(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            manifest_path, apk, _ = self.make_package(Path(temporary))
            args = Namespace(
                manifest=manifest_path,
                apk=apk,
                release_notes="Verified gaming update",
                expected_signer_sha256=SIGNER,
                base_url=BASE_URL,
                ssh_host="root@example.test",
                ssh_key=Path(temporary) / "missing-key",
                ssh_port=22,
                remote_root="/opt/d-company-erp",
                apkanalyzer=None,
                apksigner=None,
                apply=False,
            )
            evidence = staging.AndroidToolEvidence(
                apkanalyzer="apkanalyzer",
                apksigner="apksigner",
                application_id="cloud.dcompany.erp",
                version_code=15,
                version_name="3.1.4",
                signing_certificate_sha256=SIGNER,
                verified_v2_or_newer=True,
            )
            with patch.object(
                staging, "verify_apk_identity_and_signer", return_value=evidence
            ):
                result = staging.stage_release(args)

        self.assertTrue(result["ok"])
        self.assertFalse(result["applied"])

    def test_staging_requires_an_independently_trusted_signer(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            manifest_path, apk, _ = self.make_package(Path(temporary))
            args = Namespace(
                manifest=manifest_path,
                apk=apk,
                release_notes="Verified gaming update",
                expected_signer_sha256="66" * 32,
                base_url=BASE_URL,
                ssh_host="root@example.test",
                ssh_key=Path(temporary) / "missing-key",
                ssh_port=22,
                remote_root="/opt/d-company-erp",
                apkanalyzer=None,
                apksigner=None,
                apply=False,
            )

            with self.assertRaisesRegex(
                staging.AndroidReleaseStagingError, "independently trusted signer"
            ):
                staging.stage_release(args)

    def test_external_monitor_checks_headers_frequently_and_bytes_periodically(
        self,
    ) -> None:
        workflow = (
            Path(__file__).resolve().parents[1]
            / ".github/workflows/production-monitor.yml"
        ).read_text(encoding="utf-8")

        self.assertIn("ops/android_update_channel.py", workflow)
        self.assertIn("--headers-only", workflow)
        self.assertIn('cron: "7 */6 * * *"', workflow)
        self.assertIn("FULL_APK_CHECK", workflow)


if __name__ == "__main__":
    unittest.main()
