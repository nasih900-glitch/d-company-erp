from __future__ import annotations

import hashlib
import json
import tempfile
import unittest
from pathlib import Path

from scripts.build_android_release_manifest import (
    MAX_APK_BYTES,
    ReleaseManifestError,
    build_release_manifest,
    normalize_signer_sha256,
    validate_apk_filename,
    validate_download_base_url,
    write_release_manifest,
)


class AndroidReleaseManifestTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp_dir = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp_dir.cleanup)
        self.root = Path(self.temp_dir.name)
        self.apk = self.root / "app-directRelease.apk"
        self.apk.write_bytes(b"signed-direct-apk-fixture")
        self.metadata = self.root / "output-metadata.json"
        self.metadata.write_text(
            json.dumps(
                {
                    "applicationId": "cloud.dcompany.erp",
                    "variantName": "directRelease",
                    "elements": [{"versionCode": 11, "versionName": "3.1.0"}],
                }
            ),
            encoding="utf-8",
        )

    def build(self, **overrides: object) -> dict[str, object]:
        arguments: dict[str, object] = {
            "apk_path": self.apk,
            "metadata_path": self.metadata,
            "apk_filename": "d-company-erp-v3.1.0-direct.apk",
            "signer_sha256": "ab" * 32,
            "release_ref": "v3.1.0",
            "git_sha": "1" * 40,
            "workflow_run_id": "123",
            "workflow_run_attempt": "1",
        }
        arguments.update(overrides)
        return build_release_manifest(**arguments)  # type: ignore[arg-type]

    def test_builds_update_metadata_from_the_direct_apk(self) -> None:
        manifest = self.build()

        self.assertEqual("cloud.dcompany.erp", manifest["application_id"])
        self.assertEqual("directRelease", manifest["variant"])
        self.assertEqual(11, manifest["version_code"])
        self.assertEqual("3.1.0", manifest["version_name"])
        self.assertEqual(self.apk.stat().st_size, manifest["apk_size_bytes"])
        self.assertEqual(
            hashlib.sha256(self.apk.read_bytes()).hexdigest(),
            manifest["apk_sha256"],
        )
        self.assertEqual("ab" * 32, manifest["signing_certificate_sha256"])
        self.assertEqual(
            "https://dcompany.duckdns.org/downloads/android/"
            "d-company-erp-v3.1.0-direct.apk",
            manifest["apk_download_url"],
        )

    def test_writes_complete_json_atomically(self) -> None:
        output = self.root / "package" / "release-manifest.json"
        manifest = self.build()

        write_release_manifest(output, manifest)

        self.assertEqual(manifest, json.loads(output.read_text(encoding="utf-8")))
        self.assertFalse(output.with_suffix(".json.tmp").exists())

    def test_rejects_path_or_url_shaped_apk_filenames(self) -> None:
        for value in (
            "../app.apk",
            "/app.apk",
            "https://example.test/app.apk",
            "app apk.apk",
            "app.aab",
        ):
            with self.subTest(value=value):
                with self.assertRaisesRegex(ReleaseManifestError, "path-free ASCII"):
                    validate_apk_filename(value)

    def test_rejects_non_https_or_credentialed_download_roots(self) -> None:
        for value in (
            "http://updates.example.test/android/",
            "https://owner@updates.example.test/android/",
            "https://updates.example.test/android/?token=x",
            "https://updates.example.test/android",
        ):
            with self.subTest(value=value):
                with self.assertRaisesRegex(ReleaseManifestError, "HTTPS directory"):
                    validate_download_base_url(value)

    def test_rejects_an_ordinary_release_apk_as_the_downloadable_update(self) -> None:
        metadata = json.loads(self.metadata.read_text(encoding="utf-8"))
        metadata["variantName"] = "release"
        self.metadata.write_text(json.dumps(metadata), encoding="utf-8")

        with self.assertRaisesRegex(ReleaseManifestError, "expected 'directRelease'"):
            self.build()

    def test_rejects_wrong_package_or_unsafe_version_metadata(self) -> None:
        metadata = json.loads(self.metadata.read_text(encoding="utf-8"))
        metadata["applicationId"] = "example.impostor"
        self.metadata.write_text(json.dumps(metadata), encoding="utf-8")
        with self.assertRaisesRegex(ReleaseManifestError, "unexpected application id"):
            self.build()

        metadata["applicationId"] = "cloud.dcompany.erp"
        metadata["elements"][0]["versionCode"] = "10"
        self.metadata.write_text(json.dumps(metadata), encoding="utf-8")
        with self.assertRaisesRegex(ReleaseManifestError, "positive integer"):
            self.build()

    def test_normalizes_but_does_not_weaken_signer_digest_validation(self) -> None:
        colon_digest = ":".join(["AB"] * 32)
        self.assertEqual("ab" * 32, normalize_signer_sha256(colon_digest))
        with self.assertRaisesRegex(ReleaseManifestError, "64 hex digits"):
            normalize_signer_sha256("not-a-certificate")

    def test_rejects_apk_larger_than_the_android_download_limit(self) -> None:
        # truncate creates a sparse fixture, so this proves the byte boundary
        # without allocating or hashing a 512 MiB test payload.
        self.apk.write_bytes(b"")
        with self.apk.open("r+b") as oversized_apk:
            oversized_apk.truncate(MAX_APK_BYTES + 1)

        with self.assertRaisesRegex(ReleaseManifestError, "must not exceed"):
            self.build()


if __name__ == "__main__":
    unittest.main()
