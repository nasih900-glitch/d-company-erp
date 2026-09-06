from __future__ import annotations

import hashlib
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from ops.android_update_channel import (
    AdvertisedAndroidRelease,
    AndroidUpdateChannelError,
    ChannelProbe,
    fetch_channel_matrix,
    fetch_channel_probe,
    parse_compatibility_payload,
    verify_local_artifact,
)

BASE_URL = "https://dcompany.duckdns.org"
SHA = "ab" * 32
SIGNER = "cd" * 32


def compatibility_payload(**overrides):
    payload = {
        "platform": "android",
        "current_version_code": 1,
        "minimum_supported_version_code": 8,
        "latest_version_code": 8,
        "policy_revision": 1,
        "status": "update_required",
        "update_url": None,
        "latest_version_name": None,
        "release_notes": None,
        "apk_sha256": None,
        "apk_size_bytes": None,
        "apk_signing_cert_sha256": None,
        "message": "Update required",
    }
    payload.update(overrides)
    return payload


class AndroidUpdateChannelTest(unittest.TestCase):
    @staticmethod
    def response(payload: dict, *, cache_control: str = "no-store"):
        class FakeResponse:
            status = 200

            def __init__(self) -> None:
                self.headers = {
                    "Cache-Control": cache_control,
                    "Content-Type": "application/json",
                    "X-Client-Compatibility-Policy-Revision": str(
                        payload.get("policy_revision", "")
                    ),
                }

            def __enter__(self):
                return self

            def __exit__(self, *_args):
                return False

            def read(self, _limit: int) -> bytes:
                return json.dumps(payload).encode()

            def geturl(self) -> str:
                return (
                    f"{BASE_URL}/api/v1/public/client-compatibility"
                    "?platform=android&version_code=1"
                )

        return FakeResponse()

    def test_dormant_channel_requires_all_release_metadata_to_be_blank(self) -> None:
        probe = parse_compatibility_payload(
            compatibility_payload(),
            base_url=BASE_URL,
            requested_version_code=1,
        )

        self.assertIsNone(probe.release)
        self.assertEqual(8, probe.minimum_supported_version_code)
        self.assertEqual(8, probe.latest_version_code)
        self.assertEqual(1, probe.policy_revision)

    def test_active_channel_is_parsed_as_one_complete_contract(self) -> None:
        probe = parse_compatibility_payload(
            compatibility_payload(
                latest_version_code=15,
                update_url=f"{BASE_URL}/downloads/android/d-company-erp-v3.1.4-direct.apk",
                latest_version_name="3.1.4",
                release_notes="Gaming Centre update",
                apk_sha256=SHA,
                apk_size_bytes=123,
                apk_signing_cert_sha256=SIGNER,
            ),
            base_url=BASE_URL,
            requested_version_code=1,
        )

        assert probe.release is not None
        self.assertEqual(15, probe.release.version_code)
        self.assertEqual("d-company-erp-v3.1.4-direct.apk", probe.release.filename)
        self.assertEqual(SIGNER, probe.release.signing_certificate_sha256)

    def test_partial_release_metadata_fails_closed(self) -> None:
        with self.assertRaisesRegex(AndroidUpdateChannelError, "partially"):
            parse_compatibility_payload(
                compatibility_payload(
                    latest_version_code=15,
                    update_url=f"{BASE_URL}/downloads/android/release.apk",
                ),
                base_url=BASE_URL,
                requested_version_code=1,
            )

    def test_active_metadata_is_never_accepted_from_a_cacheable_response(self) -> None:
        payload = compatibility_payload(
            latest_version_code=15,
            update_url=f"{BASE_URL}/downloads/android/release.apk",
            latest_version_name="3.1.4",
            release_notes="Gaming Centre update",
            apk_sha256=SHA,
            apk_size_bytes=123,
            apk_signing_cert_sha256=SIGNER,
        )
        with (
            patch(
                "ops.android_update_channel.urllib.request.urlopen",
                return_value=self.response(payload, cache_control="max-age=60"),
            ),
            self.assertRaisesRegex(AndroidUpdateChannelError, "no-store"),
        ):
            fetch_channel_probe(BASE_URL, version_code=1)

    def test_dormant_policy_is_non_cacheable_even_without_an_active_release(
        self,
    ) -> None:
        with patch(
            "ops.android_update_channel.urllib.request.urlopen",
            return_value=self.response(compatibility_payload()),
        ):
            probe = fetch_channel_probe(BASE_URL, version_code=1)

        self.assertIsNone(probe.release)

    def test_wrong_origin_and_directory_are_rejected(self) -> None:
        for url in (
            "https://example.test/downloads/android/release.apk",
            f"{BASE_URL}/uploads/release.apk",
            f"{BASE_URL}/downloads/android/nested/release.apk",
            f"{BASE_URL}/downloads/android/release.apk?changed=1",
        ):
            with self.subTest(url=url), self.assertRaises(AndroidUpdateChannelError):
                parse_compatibility_payload(
                    compatibility_payload(
                        latest_version_code=15,
                        update_url=url,
                        latest_version_name="3.1.4",
                        release_notes="Gaming Centre update",
                        apk_sha256=SHA,
                        apk_size_bytes=123,
                        apk_signing_cert_sha256=SIGNER,
                    ),
                    base_url=BASE_URL,
                    requested_version_code=1,
                )

    def test_status_must_match_requested_minimum_and_latest(self) -> None:
        with self.assertRaisesRegex(AndroidUpdateChannelError, "status"):
            parse_compatibility_payload(
                compatibility_payload(status="supported"),
                base_url=BASE_URL,
                requested_version_code=1,
            )

    def test_matrix_probes_old_floor_manual_baseline_previous_and_latest(self) -> None:
        release = AdvertisedAndroidRelease(
            version_code=15,
            version_name="3.1.4",
            url=f"{BASE_URL}/downloads/android/release.apk",
            sha256=SHA,
            size_bytes=123,
            signing_certificate_sha256=SIGNER,
            release_notes="Gaming Centre update",
        )
        probed: list[int] = []

        def fake_probe(_base_url: str, *, version_code: int, timeout_seconds: float):
            probed.append(version_code)
            return ChannelProbe(8, 15, release, {}, 3)

        with patch(
            "ops.android_update_channel.fetch_channel_probe",
            side_effect=fake_probe,
        ):
            matrix = fetch_channel_matrix(BASE_URL, baseline_version_code=14)

        self.assertEqual((1, 8, 14, 15), matrix.probed_version_codes)
        self.assertEqual([1, 8, 14, 15], probed)

    def test_local_hosted_bytes_match_advertised_size_and_hash(self) -> None:
        payload = b"signed-apk-bytes"
        with tempfile.TemporaryDirectory() as temporary:
            apk = Path(temporary) / "release.apk"
            apk.write_bytes(payload)
            release = AdvertisedAndroidRelease(
                version_code=15,
                version_name="3.1.4",
                url=f"{BASE_URL}/downloads/android/release.apk",
                sha256=hashlib.sha256(payload).hexdigest(),
                size_bytes=len(payload),
                signing_certificate_sha256=SIGNER,
            )

            evidence = verify_local_artifact(apk, release)

        self.assertEqual(len(payload), evidence.size_bytes)
        self.assertEqual(hashlib.sha256(payload).hexdigest(), evidence.sha256)

    def test_local_hash_mismatch_is_reported(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            apk = Path(temporary) / "release.apk"
            apk.write_bytes(b"different")
            release = AdvertisedAndroidRelease(
                version_code=15,
                version_name="3.1.4",
                url=f"{BASE_URL}/downloads/android/release.apk",
                sha256=SHA,
                size_bytes=len(b"different"),
                signing_certificate_sha256=SIGNER,
            )
            with self.assertRaisesRegex(AndroidUpdateChannelError, "SHA-256"):
                verify_local_artifact(apk, release)


if __name__ == "__main__":
    unittest.main()
