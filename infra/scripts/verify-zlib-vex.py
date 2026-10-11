#!/usr/bin/env python3
"""Validate the narrowly scoped zlib VEX and any Grype evidence it filtered."""

from __future__ import annotations

import json
import hashlib
from pathlib import Path
import re
import sys
from typing import Any
from urllib.parse import parse_qs


CVE = "CVE-2026-85091"
PACKAGE_NAME = "zlib"
PACKAGE_VERSION = "1.3.2-r0"
PACKAGE_PURL = f"pkg:apk/alpine/{PACKAGE_NAME}@{PACKAGE_VERSION}"
CADDY_PACKAGE_VERSION = "1.3.2-r1"
CADDY_PACKAGE_PURL = f"pkg:apk/alpine/{PACKAGE_NAME}@{CADDY_PACKAGE_VERSION}"
SYFT_VERSION = "1.42.3"
SYFT_SCHEMA = {
    "version": "16.1.3",
    "url": "https://raw.githubusercontent.com/anchore/syft/main/schema/json/schema-16.1.3.json",
}
GRYPE_VERSION = "0.118.0"
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


class DuplicateJSONKey(ValueError):
    """Raised when scanner evidence contains an ambiguous object key."""


def reject_duplicate_keys(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    value: dict[str, Any] = {}
    for key, item in pairs:
        if key in value:
            raise DuplicateJSONKey(f"duplicate object key: {key}")
        value[key] = item
    return value


def load_json(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(
            path.read_text(encoding="utf-8"),
            object_pairs_hook=reject_duplicate_keys,
        )
    except (OSError, UnicodeDecodeError, json.JSONDecodeError, DuplicateJSONKey) as exc:
        raise SystemExit(f"Invalid JSON evidence {path}: {exc}") from exc
    if not isinstance(value, dict):
        raise SystemExit(f"JSON evidence must be an object: {path}")
    return value


IMAGE_PRODUCT = re.compile(r"[A-Za-z0-9][A-Za-z0-9._/:@-]{0,254}")
IMAGE_ID = re.compile(r"sha256:[0-9a-f]{64}")


def validate_expected_identity(expected_product: str, expected_image_id: str) -> None:
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


def validate_document(path: Path, expected_product: str, expected_image_id: str) -> None:
    document = load_json(path)
    raw_document = path.read_text(encoding="utf-8")
    if "__D_COMPANY_" in raw_document:
        raise SystemExit("Rendered zlib VEX contains an unresolved template placeholder")
    validate_expected_identity(expected_product, expected_image_id)
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


def validate_scanner_source(
    service: str,
    source: Any,
    expected_product: str,
    expected_image_id: str,
    identity_evidence: str = "VEX",
) -> None:
    target = source.get("target") if isinstance(source, dict) else None
    tags = target.get("tags") if isinstance(target, dict) else None
    if (
        not isinstance(source, dict)
        or source.get("type") != "image"
        or not isinstance(target, dict)
        or target.get("imageID") != expected_image_id
    ):
        raise SystemExit(
            f"Grype report image ID mismatch; image ID does not match "
            f"{identity_evidence} for {service}"
        )
    if (
        not isinstance(tags, list)
        or not tags
        or any(not isinstance(tag, str) for tag in tags)
        or expected_product not in tags
    ):
        raise SystemExit(
            f"Grype report image tag does not match {identity_evidence} for {service}"
        )


def validate_caddy_sbom(path: Path, expected_image_id: str) -> str:
    report = load_json(path)
    descriptor = report.get("descriptor")
    configuration = descriptor.get("configuration") if isinstance(descriptor, dict) else None
    cataloguers = configuration.get("catalogers") if isinstance(configuration, dict) else None
    used_cataloguers = cataloguers.get("used") if isinstance(cataloguers, dict) else None
    source = report.get("source")
    metadata = source.get("metadata") if isinstance(source, dict) else None
    if (
        not isinstance(descriptor, dict)
        or descriptor.get("name") != "syft"
        or descriptor.get("version") != SYFT_VERSION
    ):
        raise SystemExit("Caddy fixed-package SBOM has an unexpected Syft identity")
    if not isinstance(used_cataloguers, list) or "apk-db-cataloger" not in used_cataloguers:
        raise SystemExit("Caddy fixed-package SBOM did not use the APK database cataloguer")
    if report.get("schema") != SYFT_SCHEMA:
        raise SystemExit("Caddy fixed-package SBOM has an unexpected Syft schema")
    if (
        not isinstance(source, dict)
        or source.get("type") != "image"
        or not isinstance(metadata, dict)
        or metadata.get("imageID") != expected_image_id
    ):
        raise SystemExit("Caddy fixed-package SBOM image ID mismatch")

    artifacts = report.get("artifacts")
    relationships = report.get("artifactRelationships")
    files = report.get("files")
    distro = report.get("distro")
    if (
        not isinstance(artifacts, list)
        or len(artifacts) < 2
        or not all(isinstance(item, dict) for item in artifacts)
        or not isinstance(relationships, list)
        or not relationships
        or not isinstance(files, list)
        or not files
        or not isinstance(distro, dict)
        or distro.get("id") != "alpine"
        or not isinstance(distro.get("versionID"), str)
        or not distro["versionID"].strip()
    ):
        raise SystemExit("Caddy fixed-package SBOM has an empty or incomplete catalogue")

    candidates = []
    for item in artifacts:
        item_metadata = item.get("metadata")
        item_purl = item.get("purl")
        if (
            item.get("name") == PACKAGE_NAME
            or (isinstance(item_metadata, dict) and item_metadata.get("package") == PACKAGE_NAME)
            or (isinstance(item_purl, str) and item_purl.startswith("pkg:apk/alpine/zlib@"))
        ):
            candidates.append(item)
    if len(candidates) != 1:
        raise SystemExit("Caddy fixed-package SBOM must contain exactly one zlib package")

    package = candidates[0]
    package_metadata = package.get("metadata")
    locations = package.get("locations")
    purl = package.get("purl")
    if (
        not isinstance(package.get("id"), str)
        or not package["id"]
        or package.get("name") != PACKAGE_NAME
        or package.get("version") != CADDY_PACKAGE_VERSION
        or package.get("type") != "apk"
        or package.get("foundBy") != "apk-db-cataloger"
        or package.get("metadataType") != "apk-db-entry"
        or not isinstance(package_metadata, dict)
        or package_metadata.get("package") != PACKAGE_NAME
        or package_metadata.get("originPackage") != PACKAGE_NAME
        or package_metadata.get("version") != CADDY_PACKAGE_VERSION
        or not isinstance(purl, str)
        or re.fullmatch(re.escape(CADDY_PACKAGE_PURL) + r"\?.+", purl) is None
    ):
        raise SystemExit("Caddy fixed-package SBOM zlib identity is inconsistent")

    architecture = package_metadata.get("architecture")
    apk_port_commit = package_metadata.get("gitCommitOfApkPort")
    query = parse_qs(purl.partition("?")[2], keep_blank_values=True)
    if (
        not isinstance(architecture, str)
        or not architecture
        or not isinstance(apk_port_commit, str)
        or re.fullmatch(r"[0-9a-f]{40}", apk_port_commit) is None
        or query.get("arch") != [architecture]
        or query.get("distro") != [f"alpine-{distro['versionID']}"]
    ):
        raise SystemExit("Caddy fixed-package SBOM zlib PURL metadata is inconsistent")

    if not isinstance(locations, list) or len(locations) != 1:
        raise SystemExit("Caddy fixed-package SBOM zlib location is ambiguous")
    location = locations[0]
    if (
        not isinstance(location, dict)
        or location.get("path") != "/lib/apk/db/installed"
        or location.get("accessPath") != "/lib/apk/db/installed"
        or IMAGE_ID.fullmatch(str(location.get("layerID", ""))) is None
        or location.get("annotations") != {"evidence": "primary"}
    ):
        raise SystemExit("Caddy fixed-package SBOM zlib location is inconsistent")

    provides = package_metadata.get("provides")
    package_files = package_metadata.get("files")
    if (
        not isinstance(provides, list)
        or "so:libz.so.1=1.3.2" not in provides
        or not isinstance(package_files, list)
        or not any(
            isinstance(item, dict) and item.get("path") == RUNTIME_PATH
            for item in package_files
        )
    ):
        raise SystemExit("Caddy fixed-package SBOM lacks the installed zlib runtime metadata")

    digest = hashlib.sha256(path.read_bytes()).hexdigest()
    print(f"caddy_fixed_sbom_sha256={digest}")
    print(f"caddy_fixed_sbom_image_id={expected_image_id}")
    print(f"caddy_fixed_zlib_version={CADDY_PACKAGE_VERSION}")
    return digest


def validate_caddy_report(
    path: Path,
    expected_product: str,
    expected_image_id: str,
) -> None:
    report = load_json(path)
    descriptor = report.get("descriptor")
    configuration = descriptor.get("configuration") if isinstance(descriptor, dict) else None
    database = descriptor.get("db") if isinstance(descriptor, dict) else None
    status = database.get("status") if isinstance(database, dict) else None
    if (
        not isinstance(descriptor, dict)
        or descriptor.get("name") != "grype"
        or descriptor.get("version") != GRYPE_VERSION
    ):
        raise SystemExit("Caddy Grype report has an unexpected scanner identity")
    if not isinstance(configuration, dict) or configuration.get("fail-on-severity") != "high":
        raise SystemExit("Caddy Grype report does not preserve the high severity fail gate")
    built = status.get("built") if isinstance(status, dict) else None
    schema = status.get("schemaVersion") if isinstance(status, dict) else None
    valid = status.get("valid") if isinstance(status, dict) else None
    if not isinstance(built, str) or not built.strip():
        raise SystemExit("Caddy Grype report has no vulnerability DB build identity")
    if not isinstance(schema, str) or not schema.strip():
        raise SystemExit("Caddy Grype report has no vulnerability DB schema identity")
    if valid is not True:
        raise SystemExit("Caddy Grype vulnerability DB is not valid")
    validate_scanner_source(
        "caddy",
        report.get("source"),
        expected_product,
        expected_image_id,
        "SBOM",
    )

    matches = report.get("matches")
    ignored = report.get("ignoredMatches", [])
    if not isinstance(matches, list) or not isinstance(ignored, list):
        raise SystemExit("Caddy Grype report match arrays are invalid")
    if ignored:
        raise SystemExit("Caddy fixed zlib report must not contain ignored findings")
    for match in matches:
        if not isinstance(match, dict):
            raise SystemExit("Caddy Grype report contains an invalid match")
        vulnerability = match.get("vulnerability")
        if not isinstance(vulnerability, dict):
            raise SystemExit("Caddy Grype report contains a match without a vulnerability")
        if vulnerability.get("id") == CVE:
            raise SystemExit(f"Residual {CVE} match remains for caddy")
        severity = vulnerability.get("severity")
        if isinstance(severity, str) and severity.lower() in {"high", "critical"}:
            raise SystemExit("Caddy Grype report contains a high or critical finding")
    print("caddy_fixed_zlib_ignored_matches=0")
    print(f"caddy_grype_version={GRYPE_VERSION}")
    print(f"caddy_grype_db_built={built}")
    print(f"caddy_grype_db_schema={schema}")


def validate_report(
    service: str,
    path: Path,
    expected_product: str,
    expected_image_id: str,
) -> None:
    report = load_json(path)
    validate_scanner_source(service, report.get("source"), expected_product, expected_image_id)
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


def validate_caddy_mode(arguments: list[str]) -> int:
    if len(arguments) != 6:
        raise SystemExit(
            f"Usage: {arguments[0]} --caddy-fixed-sbom SYFT_REPORT "
            "IMAGE_PRODUCT IMAGE_ID caddy=GRYPE_REPORT"
        )
    sbom_path = Path(arguments[2]).resolve(strict=True)
    expected_product = arguments[3]
    expected_image_id = arguments[4]
    validate_expected_identity(expected_product, expected_image_id)
    service, separator, raw_report_path = arguments[5].partition("=")
    if service != "caddy" or not separator or not raw_report_path:
        raise SystemExit(
            "--caddy-fixed-sbom requires exactly one caddy Grype report binding"
        )
    report_path = Path(raw_report_path).resolve(strict=True)
    validate_caddy_sbom(sbom_path, expected_image_id)
    validate_caddy_report(report_path, expected_product, expected_image_id)
    return 0


def main(arguments: list[str]) -> int:
    if len(arguments) > 1 and arguments[1] == "--caddy-fixed-sbom":
        return validate_caddy_mode(arguments)
    if "--caddy-fixed-sbom" in arguments[2:]:
        raise SystemExit("--caddy-fixed-sbom must use the separate Caddy verifier mode")
    if len(arguments) < 4:
        raise SystemExit(
            f"Usage: {arguments[0]} VEX_DOCUMENT IMAGE_PRODUCT IMAGE_ID "
            "[SERVICE=GRYPE_REPORT ...]"
        )
    vex_path = Path(arguments[1]).resolve(strict=True)
    expected_product = arguments[2]
    expected_image_id = arguments[3]
    validate_document(vex_path, expected_product, expected_image_id)
    report_specs = arguments[4:]
    if not report_specs:
        print(f"zlib_vex_sha256={hashlib.sha256(vex_path.read_bytes()).hexdigest()}")
        return 0

    bindings: list[tuple[str, Path]] = []
    services: set[str] = set()
    for spec in report_specs:
        service, separator, raw_path = spec.partition("=")
        if (
            not separator
            or not raw_path
            or re.fullmatch(r"[a-z][a-z0-9-]{0,31}", service) is None
            or service == "caddy"
            or service in services
        ):
            raise SystemExit(f"Invalid Grype report binding: {spec}")
        services.add(service)
        bindings.append((service, Path(raw_path).resolve(strict=True)))

    for service, report_path in bindings:
        validate_report(
            service,
            report_path,
            expected_product,
            expected_image_id,
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
