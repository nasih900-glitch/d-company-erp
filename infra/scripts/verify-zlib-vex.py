#!/usr/bin/env python3
"""Validate the narrowly scoped zlib VEX and any Grype evidence it filtered."""

from __future__ import annotations

import json
import hashlib
from pathlib import Path
import re
import sys
from typing import Any


CVE = "CVE-2026-85091"
PACKAGE_NAME = "zlib"
PACKAGE_VERSION = "1.3.2-r0"
PACKAGE_PURL = f"pkg:apk/alpine/{PACKAGE_NAME}@{PACKAGE_VERSION}"
SOURCE_SHA256 = "bb329a0a2cd0274d05519d61c667c062e06990d72e125ee2dfa8de64f0119d16"
NULL_GUARD_COMMIT = "e3dc0a85b7032e98380dec011bc8f2c2ee0d8fca"
NULL_GUARD_SHA256 = "183bc8b9dd078a41a62de5c2d905d9b0196b45bc46f100d7e4147ec228207c74"
PRINTF_RETURN_COMMIT = "bbc2ccf3d0de267576b524b875c769a724a513b0"
PRINTF_RETURN_SHA256 = "7d00ee29be5e636d30da2890961e83e35cee0b152333ecd97a46ae2e71eb5d47"
PRIMARY_COMMIT = "df84af25dc1942490e1d1c899a07619152a46148"
PRIMARY_SHA256 = "110ff14375733173d8aa54574473424fbd7dfe4b81f1ca34a759c6fe14b15b14"
FOLLOWUP_COMMIT = "7235b0a581227c56a79a43ff828f8ef6794194c8"
FOLLOWUP_SHA256 = "96040ee84d0d187905283912dbd3f7b66ac2033976a2ceefe9b8cca63143d9c2"
RUNTIME_PATH = "/usr/lib/libz.so.1.3.2"


def load_json(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise SystemExit(f"Invalid JSON evidence {path}: {exc}") from exc
    if not isinstance(value, dict):
        raise SystemExit(f"JSON evidence must be an object: {path}")
    return value


IMAGE_PRODUCT = re.compile(r"[A-Za-z0-9][A-Za-z0-9._/:@-]{0,254}")
IMAGE_ID = re.compile(r"sha256:[0-9a-f]{64}")


def validate_document(path: Path, expected_product: str, expected_image_id: str) -> None:
    document = load_json(path)
    raw_document = path.read_text(encoding="utf-8")
    if "__D_COMPANY_" in raw_document:
        raise SystemExit("Rendered zlib VEX contains an unresolved template placeholder")
    image_name = expected_product.rsplit("/", 1)[-1]
    if (
        IMAGE_PRODUCT.fullmatch(expected_product) is None
        or "@" in expected_product
        or ":" not in image_name
        or image_name.endswith(":")
    ):
        raise SystemExit("Expected VEX image product is not an exact tagged image reference")
    if IMAGE_ID.fullmatch(expected_image_id) is None:
        raise SystemExit("Expected VEX image ID is not an immutable sha256 identity")
    if document.get("@context") != "https://openvex.dev/ns/v0.2.0":
        raise SystemExit("zlib VEX uses an unexpected OpenVEX context")
    expected_document_id = (
        "urn:dcompany:openvex:cve-2026-85091:"
        + expected_image_id.removeprefix("sha256:")
    )
    if document.get("@id") != expected_document_id:
        raise SystemExit("zlib VEX document ID is not bound to the exact image ID")
    if document.get("version") != 1:
        raise SystemExit("zlib VEX version must be exactly 1")
    statements = document.get("statements")
    if not isinstance(statements, list) or len(statements) != 1:
        raise SystemExit("zlib VEX must contain exactly one statement")
    statement = statements[0]
    if not isinstance(statement, dict):
        raise SystemExit("zlib VEX statement must be an object")
    vulnerability = statement.get("vulnerability")
    products = statement.get("products")
    if vulnerability != {"name": CVE}:
        raise SystemExit("zlib VEX is not bound to the exact CVE")
    expected_products = [
        {
            "@id": expected_product,
            "subcomponents": [{"@id": PACKAGE_PURL}],
        }
    ]
    if products != expected_products:
        raise SystemExit("zlib VEX is not bound to the exact image and package PURL")
    if statement.get("status") != "fixed":
        raise SystemExit("zlib VEX status must be fixed")
    if "justification" in statement:
        raise SystemExit("fixed zlib VEX must use evidence notes, not not_affected justification")
    notes = statement.get("status_notes")
    required_evidence = (
        SOURCE_SHA256,
        NULL_GUARD_COMMIT,
        NULL_GUARD_SHA256,
        PRINTF_RETURN_COMMIT,
        PRINTF_RETURN_SHA256,
        PRIMARY_COMMIT,
        PRIMARY_SHA256,
        FOLLOWUP_COMMIT,
        FOLLOWUP_SHA256,
        RUNTIME_PATH,
        expected_image_id,
    )
    if not isinstance(notes, str) or any(value not in notes for value in required_evidence):
        raise SystemExit("zlib VEX is missing the reviewed runtime remediation evidence")


def artifact(match: dict[str, Any]) -> dict[str, Any]:
    value = match.get("artifact")
    if not isinstance(value, dict):
        raise SystemExit("Grype ignored match has no artifact object")
    return value


def vulnerability_id(match: dict[str, Any]) -> str:
    value = match.get("vulnerability")
    if not isinstance(value, dict) or not isinstance(value.get("id"), str):
        raise SystemExit("Grype match has no vulnerability ID")
    return value["id"]


def exact_zlib_match(match: dict[str, Any]) -> bool:
    pkg = artifact(match)
    purl = pkg.get("purl")
    return (
        vulnerability_id(match) == CVE
        and pkg.get("name") == PACKAGE_NAME
        and pkg.get("version") == PACKAGE_VERSION
        and pkg.get("type") == "apk"
        and isinstance(purl, str)
        and re.fullmatch(re.escape(PACKAGE_PURL) + r"(?:\?.+)?", purl) is not None
    )


def validate_report(
    service: str,
    path: Path,
    expected_product: str,
    expected_image_id: str,
) -> None:
    report = load_json(path)
    source = report.get("source")
    target = source.get("target") if isinstance(source, dict) else None
    tags = target.get("tags") if isinstance(target, dict) else None
    if (
        not isinstance(source, dict)
        or source.get("type") != "image"
        or not isinstance(target, dict)
        or target.get("imageID") != expected_image_id
    ):
        raise SystemExit(
            f"Grype report image ID mismatch; image ID does not match VEX for {service}"
        )
    if not isinstance(tags, list) or expected_product not in tags:
        raise SystemExit(f"Grype report image tag does not match VEX for {service}")
    matches = report.get("matches")
    ignored = report.get("ignoredMatches")
    if not isinstance(matches, list) or not isinstance(ignored, list):
        raise SystemExit(f"Grype report shape is invalid for {service}")
    for match in matches:
        if not isinstance(match, dict):
            raise SystemExit(f"Grype match is invalid for {service}")
        if vulnerability_id(match) == CVE and artifact(match).get("name") == PACKAGE_NAME:
            raise SystemExit(f"Unfiltered {CVE} zlib match remains for {service}")
    if len(ignored) != 1:
        raise SystemExit(
            f"Grype must ignore exactly one reviewed zlib finding for {service}"
        )
    for match in ignored:
        if not isinstance(match, dict) or not exact_zlib_match(match):
            raise SystemExit(f"Unexpected ignored Grype match for {service}")
        rules = match.get("appliedIgnoreRules")
        if not isinstance(rules, list) or len(rules) != 1:
            raise SystemExit(f"zlib VEX disposition is ambiguous for {service}")
        rule = rules[0]
        if rule != {"namespace": "vex", "vex-status": "fixed"}:
            raise SystemExit(f"zlib VEX disposition is not the reviewed fixed rule for {service}")
    print(f"{service}_zlib_vex_fixed_ignored_matches={len(ignored)}")


def main(arguments: list[str]) -> int:
    if len(arguments) < 4:
        raise SystemExit(
            f"Usage: {arguments[0]} VEX_DOCUMENT IMAGE_PRODUCT IMAGE_ID "
            "[SERVICE=GRYPE_REPORT ...]"
        )
    vex_path = Path(arguments[1]).resolve(strict=True)
    expected_product = arguments[2]
    expected_image_id = arguments[3]
    validate_document(vex_path, expected_product, expected_image_id)
    if len(arguments) == 4:
        print(f"zlib_vex_sha256={hashlib.sha256(vex_path.read_bytes()).hexdigest()}")
    for spec in arguments[4:]:
        service, separator, raw_path = spec.partition("=")
        if not separator or re.fullmatch(r"[a-z][a-z0-9-]{0,31}", service) is None:
            raise SystemExit(f"Invalid Grype report binding: {spec}")
        validate_report(
            service,
            Path(raw_path).resolve(strict=True),
            expected_product,
            expected_image_id,
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
