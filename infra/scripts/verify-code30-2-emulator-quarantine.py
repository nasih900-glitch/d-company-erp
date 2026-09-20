#!/usr/bin/env python3
"""Verify the immutable one-time Code30.2 emulator quarantine evidence."""

from __future__ import annotations

import hashlib
import json
import stat
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


EXPECTED_TOP_LEVEL_KEYS = {
    "schema_version",
    "captured_at_utc",
    "package_name",
    "archive_sha256",
    "archived_room_unresolved_outbox_count",
    "retired_installation_ids",
    "avds",
}
EXPECTED_AVD_KEYS = {
    "name",
    "factory_reset_or_previously_clean",
    "package_absent",
    "data_absent",
    "network_disabled_during_verification",
    "verified_at_utc",
}
EXPECTED_ARCHIVE_SHA256 = (
    "7a44d49f12fbae4a14dca1352284542c96ab0f6fe3c3c6fc53f172cfeb0e9dbe"
)
EXPECTED_EVIDENCE_SHA256 = (
    "379c6368936d03223e19482cc840c2a9d2483dc9a96909fba22cd9f59911eec8"
)
EXPECTED_INSTALLATION_IDS = [
    "63a3bdac-1e66-4656-86ba-7ffedefc11d0",
    "d664b4a5-d293-48a7-a96c-8c3050ed5e76",
]
EXPECTED_AVD_NAMES = [
    "code29_patch_trial_20260912",
    "code29_pos_notice_regression_20260912",
    "code29_release_20260912",
    "code29_signed_upgrade_20260912",
    "dcompany_code26_auth_regression",
    "dcompany_code26_release_ime_audit",
    "dcompany_code26_shop_day",
    "dcompany_code26_shop_day_fixed",
    "dcompany_code26_signed_upgrade",
    "dcompany_code27_signed_upgrade_20260909_0901",
    "dcompany_code28_signed_upgrade_20260909_2038",
    "dcompany_code29_business",
    "dcompany_code29_pricing_card",
    "dcompany_code30_ci_touch_triage_20260913",
    "dcompany_code30_customer_lookup_20260913",
    "dcompany_code30_full_trial_20260914",
    "dcompany_customer_playtime_20260913",
    "dcompany_tablet",
]


class EvidenceError(ValueError):
    """Raised when the quarantine evidence does not match its frozen contract."""


def _reject_duplicate_keys(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise EvidenceError(f"duplicate JSON key: {key}")
        result[key] = value
    return result


def _parse_utc(value: Any, field: str) -> datetime:
    if not isinstance(value, str) or not value.endswith("Z"):
        raise EvidenceError(f"{field} must be an RFC3339 UTC timestamp")
    try:
        parsed = datetime.fromisoformat(value[:-1] + "+00:00")
    except ValueError as exc:
        raise EvidenceError(f"{field} is not a valid RFC3339 timestamp") from exc
    if parsed.tzinfo != timezone.utc:
        raise EvidenceError(f"{field} must use UTC")
    return parsed


def verify_evidence(path: Path) -> tuple[str, int]:
    metadata = path.lstat()
    if (
        not stat.S_ISREG(metadata.st_mode)
        or path.is_symlink()
        or path.resolve(strict=True) != path.absolute()
    ):
        raise EvidenceError("evidence path must be a regular non-symlink file")
    if metadata.st_size <= 0 or metadata.st_size > 64 * 1024:
        raise EvidenceError("evidence file size is outside the reviewed limit")

    raw = path.read_bytes()
    try:
        document = json.loads(
            raw,
            object_pairs_hook=_reject_duplicate_keys,
            parse_constant=lambda value: (_ for _ in ()).throw(
                EvidenceError(f"invalid JSON constant: {value}")
            ),
        )
    except (json.JSONDecodeError, UnicodeDecodeError) as exc:
        raise EvidenceError("evidence is not valid UTF-8 JSON") from exc

    if not isinstance(document, dict) or set(document) != EXPECTED_TOP_LEVEL_KEYS:
        raise EvidenceError("evidence has an unexpected top-level schema")
    if type(document["schema_version"]) is not int or document["schema_version"] != 1:
        raise EvidenceError("unsupported evidence schema version")
    if document["package_name"] != "cloud.dcompany.erp":
        raise EvidenceError("evidence names an unexpected Android package")
    if document["archive_sha256"] != EXPECTED_ARCHIVE_SHA256:
        raise EvidenceError("evidence archive hash does not match the reviewed quarantine")
    if (
        type(document["archived_room_unresolved_outbox_count"]) is not int
        or document["archived_room_unresolved_outbox_count"] != 0
    ):
        raise EvidenceError("archived Room database still has unresolved outbox work")
    if document["retired_installation_ids"] != EXPECTED_INSTALLATION_IDS:
        raise EvidenceError("retired installation identities do not match the reviewed set")

    captured_at = _parse_utc(document["captured_at_utc"], "captured_at_utc")
    avds = document["avds"]
    if not isinstance(avds, list) or len(avds) != len(EXPECTED_AVD_NAMES):
        raise EvidenceError("evidence does not contain the complete reviewed AVD inventory")
    names: list[str] = []
    for index, avd in enumerate(avds):
        if not isinstance(avd, dict) or set(avd) != EXPECTED_AVD_KEYS:
            raise EvidenceError(f"avds[{index}] has an unexpected schema")
        if not isinstance(avd["name"], str):
            raise EvidenceError(f"avds[{index}].name must be a string")
        names.append(avd["name"])
        for field in (
            "factory_reset_or_previously_clean",
            "package_absent",
            "data_absent",
            "network_disabled_during_verification",
        ):
            if avd[field] is not True:
                raise EvidenceError(f"avds[{index}].{field} must be true")
        verified_at = _parse_utc(
            avd["verified_at_utc"], f"avds[{index}].verified_at_utc"
        )
        if verified_at > captured_at:
            raise EvidenceError("an AVD verification timestamp is later than capture time")
    if names != EXPECTED_AVD_NAMES or len(names) != len(set(names)):
        raise EvidenceError("AVD names do not match the exact reviewed inventory")

    digest = hashlib.sha256(raw).hexdigest()
    if digest != EXPECTED_EVIDENCE_SHA256:
        raise EvidenceError("evidence bytes do not match the reviewed SHA-256")
    return digest, len(avds)


def main() -> int:
    if len(sys.argv) != 2:
        print(f"Usage: {Path(sys.argv[0]).name} EVIDENCE_JSON", file=sys.stderr)
        return 2
    try:
        digest, avd_count = verify_evidence(Path(sys.argv[1]))
    except (EvidenceError, OSError) as exc:
        print(f"Invalid Code30.2 emulator quarantine evidence: {exc}", file=sys.stderr)
        return 1
    print(f"sha256={digest} avds={avd_count}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
