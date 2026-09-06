from __future__ import annotations

import hashlib
import io
import json
import os
import sys
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
    def setUp(self) -> None:
        """Keep security-sensitive fixtures below a private trusted ancestor.

        Linux normally places ``TemporaryDirectory`` below mode-1777 ``/tmp``.
        Production correctly rejects a release path reached through such an
        ancestor, so tests that exercise the trusted-path code need their own
        mode-0700 root inside the checkout instead of weakening that check.
        """
        self._previous_tempdir = tempfile.tempdir
        self._private_temp_root = tempfile.TemporaryDirectory(
            prefix=".android-release-staging-",
            dir=Path(__file__).resolve().parent,
        )
        Path(self._private_temp_root.name).chmod(0o700)
        tempfile.tempdir = self._private_temp_root.name

    def tearDown(self) -> None:
        tempfile.tempdir = self._previous_tempdir
        self._private_temp_root.cleanup()

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

    def test_registry_manifest_fingerprint_excludes_transport_newline(self) -> None:
        payload = {"version_code": 25, "release_notes": "Code 25"}
        expected = json.dumps(
            payload,
            sort_keys=True,
            separators=(",", ":"),
            ensure_ascii=True,
        ).encode("utf-8")

        self.assertEqual(expected, staging.canonical_registry_manifest(payload))
        self.assertEqual(expected + b"\n", staging.canonical_json(payload))

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

    @patch.object(staging, "inspect_release_pair", return_value={})
    def test_remote_publish_is_atomic_and_only_reuses_identical_bytes(
        self, _runtime
    ) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            parent = Path(temporary).resolve()
            root = parent / "erp"
            release_dir = root / "releases/android"
            release_dir.mkdir(parents=True)
            filename = "d-company-erp-v3.1.4-direct.apk"
            final_apk = release_dir / filename
            body = b"verified"
            payload = {
                "remote_root": str(root),
                "apk_filename": filename,
                "apk_sha256": hashlib.sha256(body).hexdigest(),
                "apk_size_bytes": len(body),
            }
            with (
                patch.object(staging, "TRUSTED_REMOTE_OWNER_UID", os.geteuid()),
                patch.object(staging, "TRUSTED_REMOTE_OWNER_GID", os.getegid()),
                patch.object(
                    staging,
                    "_atomic_rename_noreplace_at",
                    side_effect=lambda source_fd, source, destination_fd, destination: (
                        os.rename(
                            source,
                            destination,
                            src_dir_fd=source_fd,
                            dst_dir_fd=destination_fd,
                        )
                    ),
                ),
            ):
                prepared = staging._remote_action("prepare", payload)
                published = staging._remote_action(
                    "upload", payload, upload_stream=io.BytesIO(body)
                )
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

    @unittest.skipUnless(
        sys.platform == "linux",
        "requires the Linux renameat2(RENAME_NOREPLACE) syscall",
    )
    def test_real_renameat2_noreplace_moves_once_and_never_overwrites(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source_directory = root / "private-upload"
            destination_directory = root / "public-release"
            source_directory.mkdir()
            destination_directory.mkdir()
            source_fd = os.open(
                source_directory,
                os.O_RDONLY | os.O_DIRECTORY | os.O_CLOEXEC,
            )
            destination_fd = os.open(
                destination_directory,
                os.O_RDONLY | os.O_DIRECTORY | os.O_CLOEXEC,
            )
            try:
                (source_directory / "candidate.apk").write_bytes(b"first")
                staging._atomic_rename_noreplace_at(
                    source_fd,
                    "candidate.apk",
                    destination_fd,
                    "release.apk",
                )
                self.assertFalse((source_directory / "candidate.apk").exists())
                self.assertEqual(
                    b"first",
                    (destination_directory / "release.apk").read_bytes(),
                )

                (source_directory / "second.apk").write_bytes(b"second")
                with self.assertRaises(staging._ImmutableDestinationExists):
                    staging._atomic_rename_noreplace_at(
                        source_fd,
                        "second.apk",
                        destination_fd,
                        "release.apk",
                    )
                self.assertEqual(
                    b"second",
                    (source_directory / "second.apk").read_bytes(),
                )
                self.assertEqual(
                    b"first",
                    (destination_directory / "release.apk").read_bytes(),
                )
            finally:
                os.close(destination_fd)
                os.close(source_fd)

    @patch.object(staging, "inspect_release_pair", return_value={})
    def test_remote_upload_reservation_cannot_overwrite_symlink_victim(
        self, _runtime
    ) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            parent = Path(temporary).resolve()
            root = parent / "erp"
            (root / "releases/android").mkdir(parents=True)
            private = root / staging.PRIVATE_UPLOAD_DIRECTORY_NAME
            private.mkdir(mode=0o700)
            filename = "d-company-erp-v3.1.4-direct.apk"
            body = b"verified"
            victim = parent / "victim"
            victim.write_bytes(b"must-survive")
            reserved = private / f".{filename}.{'a' * 32}.part"
            reserved.symlink_to(victim)
            payload = {
                "remote_root": str(root),
                "apk_filename": filename,
                "apk_sha256": hashlib.sha256(body).hexdigest(),
                "apk_size_bytes": len(body),
            }
            with (
                patch.object(staging, "TRUSTED_REMOTE_OWNER_UID", os.geteuid()),
                patch.object(staging, "TRUSTED_REMOTE_OWNER_GID", os.getegid()),
                patch.object(
                    staging.uuid, "uuid4", return_value=SimpleNamespace(hex="a" * 32)
                ),
                self.assertRaisesRegex(
                    staging.AndroidReleaseStagingError,
                    "reserve",
                ),
            ):
                staging._remote_action(
                    "upload",
                    payload,
                    upload_stream=io.BytesIO(body),
                )

            self.assertEqual(b"must-survive", victim.read_bytes())
            self.assertTrue(reserved.is_symlink())

    @patch.object(staging, "inspect_release_pair", return_value={})
    def test_remote_upload_directory_swap_cannot_redirect_write_to_victim(
        self, _runtime
    ) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            parent = Path(temporary).resolve()
            root = parent / "erp"
            release_dir = root / "releases/android"
            release_dir.mkdir(parents=True)
            filename = "d-company-erp-v3.1.4-direct.apk"
            body = b"verified"
            payload = {
                "remote_root": str(root),
                "apk_filename": filename,
                "apk_sha256": hashlib.sha256(body).hexdigest(),
                "apk_size_bytes": len(body),
            }
            moved = root / "releases/android-before-swap"
            victim: Path | None = None

            def swap_then_rename(
                source_fd, source, destination_fd, destination
            ) -> None:
                nonlocal victim
                release_dir.rename(moved)
                release_dir.mkdir()
                victim = release_dir / filename
                victim.write_bytes(b"must-survive")
                os.rename(
                    source,
                    destination,
                    src_dir_fd=source_fd,
                    dst_dir_fd=destination_fd,
                )

            with (
                patch.object(staging, "TRUSTED_REMOTE_OWNER_UID", os.geteuid()),
                patch.object(staging, "TRUSTED_REMOTE_OWNER_GID", os.getegid()),
                patch.object(
                    staging,
                    "_atomic_rename_noreplace_at",
                    side_effect=swap_then_rename,
                ),
            ):
                result = staging._remote_action(
                    "upload",
                    payload,
                    upload_stream=io.BytesIO(body),
                )

            self.assertTrue(result["ok"])
            self.assertIsNotNone(victim)
            assert victim is not None
            self.assertEqual(b"must-survive", victim.read_bytes())
            self.assertEqual(body, (moved / filename).read_bytes())

    @patch.object(staging, "inspect_release_pair", return_value={})
    def test_private_upload_directory_swap_cannot_redirect_the_held_file(
        self, _runtime
    ) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            parent = Path(temporary).resolve()
            root = parent / "erp"
            release_dir = root / "releases/android"
            release_dir.mkdir(parents=True)
            private = root / staging.PRIVATE_UPLOAD_DIRECTORY_NAME
            private.mkdir(mode=0o700)
            moved_private = root / "private-before-swap"
            filename = "d-company-erp-v3.1.4-direct.apk"
            token = "b" * 32
            temporary_name = f".{filename}.{token}.part"
            body = b"verified"
            victim = parent / "victim"
            victim.write_bytes(b"must-survive")
            original_copy = staging._copy_exact_upload

            def swap_then_copy(source, destination_fd, **kwargs) -> None:
                private.rename(moved_private)
                private.mkdir(mode=0o700)
                (private / temporary_name).symlink_to(victim)
                original_copy(source, destination_fd, **kwargs)

            payload = {
                "remote_root": str(root),
                "apk_filename": filename,
                "apk_sha256": hashlib.sha256(body).hexdigest(),
                "apk_size_bytes": len(body),
            }
            with (
                patch.object(staging, "TRUSTED_REMOTE_OWNER_UID", os.geteuid()),
                patch.object(staging, "TRUSTED_REMOTE_OWNER_GID", os.getegid()),
                patch.object(
                    staging.uuid,
                    "uuid4",
                    return_value=SimpleNamespace(hex=token),
                ),
                patch.object(
                    staging,
                    "_copy_exact_upload",
                    side_effect=swap_then_copy,
                ),
                patch.object(
                    staging,
                    "_atomic_rename_noreplace_at",
                    side_effect=lambda source_fd, source, destination_fd, destination: (
                        os.rename(
                            source,
                            destination,
                            src_dir_fd=source_fd,
                            dst_dir_fd=destination_fd,
                        )
                    ),
                ),
            ):
                result = staging._remote_action(
                    "upload",
                    payload,
                    upload_stream=io.BytesIO(body),
                )

            self.assertTrue(result["ok"])
            self.assertEqual(body, (release_dir / filename).read_bytes())
            self.assertEqual(b"must-survive", victim.read_bytes())
            self.assertTrue((private / temporary_name).is_symlink())

    @patch.object(staging, "inspect_release_pair", return_value={})
    def test_remote_prepare_rejects_symlink_and_hardlink_destinations(
        self, _runtime
    ) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            parent = Path(temporary).resolve()
            root = parent / "erp"
            release_dir = root / "releases/android"
            release_dir.mkdir(parents=True)
            filename = "d-company-erp-v3.1.4-direct.apk"
            victim = parent / "victim"
            victim.write_bytes(b"must-survive")
            final = release_dir / filename
            payload = {
                "remote_root": str(root),
                "apk_filename": filename,
                "apk_sha256": hashlib.sha256(victim.read_bytes()).hexdigest(),
                "apk_size_bytes": victim.stat().st_size,
            }
            with (
                patch.object(staging, "TRUSTED_REMOTE_OWNER_UID", os.geteuid()),
                patch.object(staging, "TRUSTED_REMOTE_OWNER_GID", os.getegid()),
            ):
                final.symlink_to(victim)
                with self.assertRaisesRegex(
                    staging.AndroidReleaseStagingError,
                    "safely open",
                ):
                    staging._remote_action("prepare", payload)
                final.unlink()
                os.link(victim, final)
                with self.assertRaisesRegex(
                    staging.AndroidReleaseStagingError,
                    "link",
                ):
                    staging._remote_action("prepare", payload)

            self.assertEqual(b"must-survive", victim.read_bytes())

    @patch.object(staging, "inspect_release_pair", return_value={})
    def test_remote_prepare_rejects_symlinked_root_components(self, _runtime) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            parent = Path(temporary).resolve()
            real_root = parent / "real-erp"
            (real_root / "releases/android").mkdir(parents=True)
            linked_root = parent / "linked-erp"
            linked_root.symlink_to(real_root, target_is_directory=True)
            payload = {
                "remote_root": str(linked_root),
                "apk_filename": "d-company-erp-v3.1.4-direct.apk",
                "apk_sha256": "ab" * 32,
                "apk_size_bytes": 8,
            }
            with (
                patch.object(staging, "TRUSTED_REMOTE_OWNER_UID", os.geteuid()),
                patch.object(staging, "TRUSTED_REMOTE_OWNER_GID", os.getegid()),
                self.assertRaisesRegex(
                    staging.AndroidReleaseStagingError,
                    "safely traverse",
                ),
            ):
                staging._remote_action("prepare", payload)

    @patch.object(staging, "inspect_release_pair", return_value={})
    def test_remote_upload_rejects_non_private_staging_directory(
        self, _runtime
    ) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            parent = Path(temporary).resolve()
            root = parent / "erp"
            (root / "releases/android").mkdir(parents=True)
            private = root / staging.PRIVATE_UPLOAD_DIRECTORY_NAME
            private.mkdir(mode=0o755)
            payload = {
                "remote_root": str(root),
                "apk_filename": "d-company-erp-v3.1.4-direct.apk",
                "apk_sha256": hashlib.sha256(b"verified").hexdigest(),
                "apk_size_bytes": len(b"verified"),
            }
            with (
                patch.object(staging, "TRUSTED_REMOTE_OWNER_UID", os.geteuid()),
                patch.object(staging, "TRUSTED_REMOTE_OWNER_GID", os.getegid()),
                self.assertRaisesRegex(
                    staging.AndroidReleaseStagingError,
                    "Private Android upload directory",
                ),
            ):
                staging._remote_action(
                    "upload",
                    payload,
                    upload_stream=io.BytesIO(b"verified"),
                )

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
            staging._register_staged_release_on_host(target.root, manifest)

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

    def test_ssh_destination_rejects_all_leading_dash_forms(self) -> None:
        for host in ("-V", "-Jattacker.example", "root@-Jattacker.example"):
            with (
                self.subTest(host=host),
                self.assertRaisesRegex(staging.AndroidReleaseStagingError, "SSH host"),
            ):
                staging._validated_remote_target(
                    staging.RemoteTarget(
                        host=host,
                        key=Path("/safe/key"),
                        port=22,
                        root="/opt/d-company-erp",
                    )
                )

    def test_ssh_helpers_revalidate_option_looking_destinations(self) -> None:
        for host in ("-V", "-Jattacker.example", "root@-Jattacker.example"):
            target = staging.RemoteTarget(
                host=host,
                key=Path("/safe/key"),
                port=22,
                root="/opt/d-company-erp",
            )
            with (
                self.subTest(host=host, helper="json"),
                patch.object(staging.subprocess, "run") as run,
                self.assertRaisesRegex(staging.AndroidReleaseStagingError, "SSH host"),
            ):
                staging._ssh_json(target, "runtime", {})
            run.assert_not_called()

            with tempfile.TemporaryDirectory() as temporary:
                apk = Path(temporary) / "candidate.apk"
                apk.write_bytes(b"candidate")
                with (
                    self.subTest(host=host, helper="upload"),
                    patch.object(staging.subprocess, "run") as run,
                    self.assertRaisesRegex(
                        staging.AndroidReleaseStagingError,
                        "SSH host",
                    ),
                ):
                    staging._ssh_upload_apk(target, apk, {})
                run.assert_not_called()

    def test_ssh_option_terminator_precedes_destination_for_json_and_upload(
        self,
    ) -> None:
        target = staging.RemoteTarget(
            host="root@example.test",
            key=Path("/safe/key"),
            port=22,
            root="/opt/d-company-erp",
        )
        with (
            tempfile.TemporaryDirectory() as temporary,
            patch.object(
                staging.subprocess,
                "run",
                return_value=SimpleNamespace(stdout=b'{"ok":true}'),
            ) as run,
        ):
            apk = Path(temporary) / "candidate.apk"
            apk.write_bytes(b"candidate")
            staging._ssh_json(target, "runtime", {})
            staging._ssh_upload_apk(target, apk, {})

        for invocation in run.call_args_list:
            command = invocation.args[0]
            destination_index = command.index(target.host)
            self.assertEqual("--", command[destination_index - 1])
            self.assertNotIn("--", command[destination_index + 1 :])

    def test_verification_only_does_not_require_vps_credentials(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            manifest_path, apk, _ = self.make_package(Path(temporary))
            ci_manifest = staging.load_ci_manifest(manifest_path, base_url=BASE_URL)
            expected_registry_digest = hashlib.sha256(
                staging.canonical_registry_manifest(
                    staging.registry_manifest(
                        ci_manifest,
                        release_notes="Verified gaming update",
                    )
                )
            ).hexdigest()
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
        self.assertEqual(
            expected_registry_digest,
            result["plan"]["registry_manifest_sha256"],
        )

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

    def test_runtime_mismatch_rejects_apply_before_prepare_upload_or_registration(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            manifest, apk, _ = self.make_package(root)
            key = root / "ssh-key"
            key.touch()
            args = Namespace(
                manifest=manifest,
                apk=apk,
                release_notes="Verified update",
                expected_signer_sha256=SIGNER,
                base_url=BASE_URL,
                ssh_host="root@example.test",
                ssh_key=key,
                ssh_port=22,
                remote_root="/opt/d-company-erp",
                apkanalyzer=None,
                apksigner=None,
                apply=True,
            )
            with (
                patch.object(staging, "verify_apk_identity_and_signer"),
                patch.object(
                    staging,
                    "_ssh_json",
                    side_effect=staging.AndroidReleaseStagingError("runtime mismatch"),
                ) as remote,
                patch.object(staging, "_ssh_upload_apk") as upload,
                patch.object(staging, "_finalize_staged_release") as finalize,
                patch.object(staging, "verify_public_artifact") as public,
            ):
                with self.assertRaisesRegex(
                    staging.AndroidReleaseStagingError, "runtime mismatch"
                ):
                    staging.stage_release(args)
                self.assertEqual(
                    ["runtime"], [call.args[1] for call in remote.call_args_list]
                )
                upload.assert_not_called()
                finalize.assert_not_called()
                public.assert_not_called()

    def test_remote_prepare_and_upload_recheck_runtime_before_file_work(
        self,
    ) -> None:
        for action in ("prepare", "upload"):
            with (
                self.subTest(action=action),
                patch.object(
                    staging,
                    "inspect_release_pair",
                    side_effect=staging.RuntimeParityError("mismatch"),
                ),
                patch.object(staging, "_open_release_directories") as paths,
                patch.object(staging, "_receive_and_publish_apk") as publish,
            ):
                with self.assertRaisesRegex(
                    staging.AndroidReleaseStagingError, "mismatch"
                ):
                    staging._remote_action(
                        action, {"remote_root": "/opt/d-company-erp"}
                    )
                paths.assert_not_called()
                publish.assert_not_called()

    def test_runtime_change_before_locked_finalize_prevents_registry_mutation(
        self,
    ) -> None:
        initial_runtime = {
            "version_name": "3.1.4",
            "source_git_sha": "a" * 40,
            "services": {"backend": {"image_id": "sha256:" + "b" * 64}},
        }
        changed_runtime = {
            **initial_runtime,
            "services": {"backend": {"image_id": "sha256:" + "c" * 64}},
        }
        with tempfile.TemporaryDirectory() as temporary:
            lock_file = Path(temporary) / "production-install.lock"
            payload = {
                "remote_root": "/opt/d-company-erp",
                "version_name": "3.1.4",
                "source_git_sha": "a" * 40,
                "initial_runtime_parity": initial_runtime,
                "manifest": {"version_code": 15},
            }
            with (
                patch.object(staging, "PRODUCTION_INSTALL_LOCK", str(lock_file)),
                patch.object(
                    staging, "_inspect_running_release", return_value=changed_runtime
                ),
                patch.object(staging, "_register_staged_release_on_host") as register,
            ):
                with self.assertRaisesRegex(
                    staging.AndroidReleaseStagingError, "changed during staging"
                ):
                    staging._remote_action("finalize", payload)
                register.assert_not_called()

    def test_locked_finalize_holds_installer_flock_through_registration(
        self,
    ) -> None:
        runtime = {
            "version_name": "3.1.4",
            "source_git_sha": "a" * 40,
            "services": {
                "backend": {"image_id": "sha256:" + "b" * 64},
                "frontend": {"image_id": "sha256:" + "c" * 64},
            },
        }
        manifest = {"version_code": 15}
        receipt = {
            "id": "11111111-1111-1111-1111-111111111111",
            "status": "staged",
            "manifest_sha256": hashlib.sha256(
                staging.canonical_registry_manifest(manifest)
            ).hexdigest(),
        }
        with tempfile.TemporaryDirectory() as temporary:
            lock_file = Path(temporary) / "production-install.lock"

            def assert_lock_held(*_args, **_kwargs) -> None:
                probe = os.open(lock_file, os.O_RDWR | os.O_CREAT, 0o600)
                try:
                    with self.assertRaises(BlockingIOError):
                        staging.fcntl.flock(
                            probe, staging.fcntl.LOCK_EX | staging.fcntl.LOCK_NB
                        )
                finally:
                    os.close(probe)

            events = []

            def inspect(payload: dict) -> dict:
                events.append("inspect")
                assert_lock_held()
                return runtime

            def register(root: str, value: dict) -> dict:
                events.append("register")
                assert_lock_held()
                self.assertEqual("/opt/d-company-erp", root)
                self.assertEqual(manifest, value)
                return receipt

            payload = {
                "remote_root": "/opt/d-company-erp",
                "version_name": "3.1.4",
                "source_git_sha": "a" * 40,
                "initial_runtime_parity": runtime,
                "manifest": manifest,
            }
            with (
                patch.object(staging, "PRODUCTION_INSTALL_LOCK", str(lock_file)),
                patch.object(staging, "_inspect_running_release", side_effect=inspect),
                patch.object(
                    staging,
                    "_register_staged_release_on_host",
                    side_effect=register,
                ),
            ):
                result = staging._remote_action("finalize", payload)

            self.assertEqual(["inspect", "register"], events)
            self.assertEqual(runtime, result["runtime_parity"])
            self.assertEqual(receipt, result["registered"])
            with staging._production_install_lock(lock_file):
                pass

    def test_locked_finalize_fails_fast_when_installer_is_running(self) -> None:
        payload = {
            "remote_root": "/opt/d-company-erp",
            "version_name": "3.1.4",
            "source_git_sha": "a" * 40,
            "initial_runtime_parity": {},
            "manifest": {"version_code": 15},
        }
        with tempfile.TemporaryDirectory() as temporary:
            lock_file = Path(temporary) / "production-install.lock"
            with (
                staging._production_install_lock(lock_file),
                patch.object(staging, "PRODUCTION_INSTALL_LOCK", str(lock_file)),
                patch.object(staging, "_inspect_running_release") as inspect,
                patch.object(staging, "_register_staged_release_on_host") as register,
            ):
                with self.assertRaisesRegex(
                    staging.AndroidReleaseStagingError, "already running"
                ):
                    staging._remote_action("finalize", payload)
                inspect.assert_not_called()
                register.assert_not_called()

    def test_production_lock_rejects_symlink_fifo_and_hardlink_without_victim_damage(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            parent = Path(temporary) / "locks"
            parent.mkdir(mode=0o700)
            lock_file = parent / "production-install.lock"
            victim = Path(temporary) / "victim"
            victim.write_bytes(b"must-survive")
            victim.chmod(0o600)

            lock_file.symlink_to(victim)
            with (
                self.assertRaises(staging.AndroidReleaseStagingError),
                staging._production_install_lock(lock_file),
            ):
                self.fail("symlink lock must not be acquired")
            self.assertEqual(b"must-survive", victim.read_bytes())

            lock_file.unlink()
            os.mkfifo(lock_file, 0o600)
            with (
                self.assertRaisesRegex(
                    staging.AndroidReleaseStagingError,
                    "regular|ownership|mode|link",
                ),
                staging._production_install_lock(lock_file),
            ):
                self.fail("FIFO lock must not be acquired")

            lock_file.unlink()
            os.link(victim, lock_file)
            with (
                self.assertRaisesRegex(
                    staging.AndroidReleaseStagingError,
                    "link",
                ),
                staging._production_install_lock(lock_file),
            ):
                self.fail("hard-linked lock must not be acquired")
            self.assertEqual(b"must-survive", victim.read_bytes())

    def test_production_lock_requires_private_parent_and_exact_file_mode(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            parent = Path(temporary) / "locks"
            parent.mkdir(mode=0o755)
            lock_file = parent / "production-install.lock"
            with (
                self.assertRaisesRegex(
                    staging.AndroidReleaseStagingError,
                    "directory",
                ),
                staging._production_install_lock(lock_file),
            ):
                self.fail("non-private lock parent must not be accepted")

            parent.chmod(0o700)
            lock_file.write_bytes(b"")
            lock_file.chmod(0o644)
            with (
                self.assertRaisesRegex(
                    staging.AndroidReleaseStagingError,
                    "mode",
                ),
                staging._production_install_lock(lock_file),
            ):
                self.fail("weak lock mode must not be accepted")

    def test_staging_uses_one_remote_finalize_after_public_verification(self) -> None:
        runtime = {
            "version_name": "3.1.4",
            "source_git_sha": "a" * 40,
            "services": {
                "backend": {"image_id": "sha256:" + "b" * 64},
                "frontend": {"image_id": "sha256:" + "c" * 64},
            },
        }
        events = []
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            manifest, apk, _ = self.make_package(root)
            key = root / "ssh-key"
            key.touch()
            args = Namespace(
                manifest=manifest,
                apk=apk,
                release_notes="Verified update",
                expected_signer_sha256=SIGNER,
                base_url=BASE_URL,
                ssh_host="root@example.test",
                ssh_key=key,
                ssh_port=22,
                remote_root="/opt/d-company-erp",
                apkanalyzer=None,
                apksigner=None,
                apply=True,
            )
            tools = staging.AndroidToolEvidence(
                apkanalyzer="apkanalyzer",
                apksigner="apksigner",
                application_id="cloud.dcompany.erp",
                version_code=15,
                version_name="3.1.4",
                signing_certificate_sha256=SIGNER,
                verified_v2_or_newer=True,
            )

            def remote(_target, action: str, payload: dict) -> dict:
                events.append(action)
                if action == "runtime":
                    return {"ok": True, "runtime_parity": runtime}
                if action == "prepare":
                    return {"ok": True, "already_published": True}
                if action == "finalize":
                    self.assertEqual(runtime, payload["initial_runtime_parity"])
                    digest = hashlib.sha256(
                        staging.canonical_registry_manifest(payload["manifest"])
                    ).hexdigest()
                    return {
                        "ok": True,
                        "runtime_parity": runtime,
                        "registered": {
                            "id": "11111111-1111-1111-1111-111111111111",
                            "status": "staged",
                            "manifest_sha256": digest,
                        },
                    }
                if action == "attest":
                    return {"ok": True, "attestation": "/safe/attestation.json"}
                self.fail(f"Unexpected remote action {action}")

            def public(*_args, **_kwargs) -> SimpleNamespace:
                events.append("public")
                return SimpleNamespace(sha256="ab" * 32, size_bytes=20, headers={})

            with (
                patch.object(
                    staging, "verify_apk_identity_and_signer", return_value=tools
                ),
                patch.object(staging, "_ssh_json", side_effect=remote),
                patch.object(staging, "verify_public_artifact", side_effect=public),
                patch.object(staging, "_ssh_upload_apk") as upload,
            ):
                result = staging.stage_release(args)

        self.assertEqual(["runtime", "prepare", "public", "finalize", "attest"], events)
        self.assertEqual("staged", result["status"])
        upload.assert_not_called()

    def test_staging_and_installer_share_the_exact_lock_file(self) -> None:
        lock_helper = (
            Path(__file__).resolve().parents[1]
            / "infra/scripts/production_install_lock.py"
        ).read_text(encoding="utf-8")
        self.assertEqual(
            "/run/d-company-erp/production-install.lock",
            staging.PRODUCTION_INSTALL_LOCK,
        )
        self.assertIn('RUNTIME_PARENT = Path("/run")', lock_helper)
        self.assertIn('RUNTIME_DIRECTORY_NAME = "d-company-erp"', lock_helper)
        self.assertIn('LOCK_FILE_NAME = "production-install.lock"', lock_helper)

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
