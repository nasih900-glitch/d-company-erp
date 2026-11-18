#!/usr/bin/env python3
"""Validate the narrowly scoped zlib VEX and any Grype evidence it filtered."""

from __future__ import annotations

import json
import hashlib
from datetime import datetime, timezone
from pathlib import Path
import re
import sys
from typing import Any
from urllib.parse import parse_qs


CVE = "CVE-2026-85091"
PACKAGE_NAME = "zlib"
PACKAGE_VERSION = "1.3.2-r0"
PACKAGE_PURL = f"pkg:apk/alpine/{PACKAGE_NAME}@{PACKAGE_VERSION}"
CADDY_PACKAGE_VERSION = "1.4-r0"
BACKEND_PACKAGE_VERSION = "1.4-r0"
CADDY_PORT_COMMIT = "0436c6d655a04cbfd7987202a970f5aa7870a901"
CADDY_RUNTIME_PATH = "/usr/lib/libz.so.1.4"
OFFICIAL_PROFILES = {
    "backend": ("3.24.3", "bdf501c870a92a05c097857401f69c48c4810384"),
    "frontend": ("3.24.1", "bdf501c870a92a05c097857401f69c48c4810384"),
    "postgres": ("3.24.1", "bdf501c870a92a05c097857401f69c48c4810384"),
    "redis": ("3.21.8", "d3332621d44c2c4cf52e52217b16674db190212c"),
    "caddy": ("3.23.5", CADDY_PORT_COMMIT),
}


def official_profile(service: str) -> tuple[str, str]:
    if service not in OFFICIAL_PROFILES:
        raise SystemExit("Unknown official zlib service profile")
    return OFFICIAL_PROFILES[service]

CADDY_PACKAGE_PURL = f"pkg:apk/alpine/{PACKAGE_NAME}@{CADDY_PACKAGE_VERSION}"
SYFT_VERSION = "1.42.3"
SYFT_SCHEMA = {
    "version": "16.1.3",
    "url": "https://raw.githubusercontent.com/anchore/syft/main/schema/json/schema-16.1.3.json",
}
GRYPE_VERSION = "0.118.0"
HASHLIB_CVE = "CVE-2026-119170"
HASHLIB_PURL = "pkg:generic/python@3.14.10"
HASHLIB_PROOF = {
    "schema": 1, "cpython": "3.14.10", "openssl": "3.5.9",
    "source_sha256": "de414a57119abef52784d8993e341d53aed635fc2b05c2607d9f98a2c5b72032",
    "patch_sha256": "346c4b31e1cdd87d08cd2e79dbbcc00e6e8c6d47bdb045346be7bbf40c283fed",
    "patched_source_sha256": "d907bf6b22ddd8a55e271d19f8e1e5eda07f931d3e5143698171904f0970e549",
    "module_path": "/usr/local/lib/python3.14/lib-dynload/_hashlib.cpython-314-x86_64-linux-musl.so",
    "module_sha256": "3ea80f99a8d7ba7011b20eeef31ecb120c0914c8241dc209c38515e994a01671",
    "unpatched_control": True, "named_both_cache_orders": True, "generic_both_cache_orders": True,
}
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


def load_json(path: Path, *, maximum_bytes: int | None = None) -> dict[str, Any]:
    try:
        if maximum_bytes is None:
            content = path.read_text(encoding="utf-8")
        else:
            if path.is_symlink() or path.stat().st_size > maximum_bytes:
                raise ValueError("diagnostic evidence is linked or oversized")
            with path.open("rb") as stream:
                data = stream.read(maximum_bytes + 1)
            if len(data) > maximum_bytes:
                raise ValueError("diagnostic evidence is oversized")
            content = data.decode("utf-8")
        value = json.loads(
            content,
            object_pairs_hook=reject_duplicate_keys,
        )
    except (OSError, UnicodeDecodeError, ValueError) as exc:
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


def validate_fixed_sbom(
    service: str, path: Path, expected_image_id: str, *, archive_platform_binding: str | None = None,
) -> str:
    if archive_platform_binding is not None and archive_platform_binding != f"linux/amd64@{expected_image_id}":
        raise SystemExit(f"{service.capitalize()} archive platform/config identity mismatch")
    report = load_json(path)
    distro_version, port_commit = official_profile(service)
    package_version = CADDY_PACKAGE_VERSION
    package_purl = f"pkg:apk/alpine/zlib@{package_version}"
    runtime_path = CADDY_RUNTIME_PATH
    runtime_version = "1.4"
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
        raise SystemExit(f"{service.capitalize()} fixed-package SBOM has an unexpected Syft identity")
    if not isinstance(used_cataloguers, list) or "apk-db-cataloger" not in used_cataloguers:
        raise SystemExit(f"{service.capitalize()} fixed-package SBOM did not use the APK database cataloguer")
    if report.get("schema") != SYFT_SCHEMA:
        raise SystemExit(f"{service.capitalize()} fixed-package SBOM has an unexpected Syft schema")
    if (
        not isinstance(source, dict)
        or source.get("type") != "image"
        or not isinstance(metadata, dict)
        or metadata.get("imageID") != expected_image_id
    ):
        raise SystemExit(f"{service.capitalize()} fixed-package SBOM image ID mismatch")

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
        raise SystemExit(f"{service.capitalize()} fixed-package SBOM has an empty or incomplete catalogue")

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
        raise SystemExit(f"{service.capitalize()} fixed-package SBOM must contain exactly one zlib package")

    package = candidates[0]
    package_metadata = package.get("metadata")
    locations = package.get("locations")
    purl = package.get("purl")
    if (
        not isinstance(package.get("id"), str)
        or not package["id"]
        or package.get("name") != PACKAGE_NAME
        or package.get("version") != package_version
        or package.get("type") != "apk"
        or package.get("foundBy") != "apk-db-cataloger"
        or package.get("metadataType") != "apk-db-entry"
        or not isinstance(package_metadata, dict)
        or package_metadata.get("package") != PACKAGE_NAME
        or package_metadata.get("originPackage") != PACKAGE_NAME
        or package_metadata.get("version") != package_version
        or not isinstance(purl, str)
        or re.fullmatch(re.escape(package_purl) + r"\?.+", purl) is None
    ):
        raise SystemExit(f"{service.capitalize()} fixed-package SBOM zlib identity is inconsistent")

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
        raise SystemExit(f"{service.capitalize()} fixed-package SBOM zlib PURL metadata is inconsistent")

    if not isinstance(locations, list) or len(locations) != 1:
        raise SystemExit(f"{service.capitalize()} fixed-package SBOM zlib location is ambiguous")
    location = locations[0]
    if (
        not isinstance(location, dict)
        or location.get("path") != "/lib/apk/db/installed"
        or location.get("accessPath") != "/lib/apk/db/installed"
        or IMAGE_ID.fullmatch(str(location.get("layerID", ""))) is None
        or location.get("annotations") != {"evidence": "primary"}
    ):
        raise SystemExit(f"{service.capitalize()} fixed-package SBOM zlib location is inconsistent")

    syft_platform = (metadata.get("os"), metadata.get("architecture"))
    # Pinned Syft leaves both fields explicitly empty on some Docker archives.
    # The hardened runner supplies the platform from the same verified config.
    archive_platform_used = syft_platform == ("", "") and archive_platform_binding is not None
    if ((syft_platform != ("linux", "amd64") and not archive_platform_used)
            or distro["versionID"] != distro_version or architecture != "x86_64"
            or apk_port_commit != port_commit):
        raise SystemExit(f"{service.capitalize()} fixed-package SBOM does not match the reviewed Alpine port")

    provides = package_metadata.get("provides")
    package_files = package_metadata.get("files")
    if (
        not isinstance(provides, list)
        or f"so:libz.so.1={runtime_version}" not in provides
        or not isinstance(package_files, list)
        or not any(
            isinstance(item, dict) and item.get("path") == runtime_path
            for item in package_files
        )
    ):
        raise SystemExit(f"{service.capitalize()} fixed-package SBOM lacks the installed zlib runtime metadata")

    digest = hashlib.sha256(path.read_bytes()).hexdigest()
    print(f"{service}_fixed_sbom_sha256={digest}")
    print(f"{service}_fixed_sbom_image_id={expected_image_id}")
    print(f"{service}_fixed_zlib_version={package_version}")
    if archive_platform_used:
        print(f"{service}_fixed_sbom_platform_source=verified-docker-archive")
    return digest


def validate_fixed_report(
    service: str,
    path: Path,
    expected_product: str,
    expected_image_id: str,
    *, hashlib_exception: str | None = None, openssl_ignored: list[dict[str, Any]] | None = None,
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
        raise SystemExit(f"{service.capitalize()} Grype report has an unexpected scanner identity")
    if not isinstance(configuration, dict) or configuration.get("fail-on-severity") != "high":
        raise SystemExit(f"{service.capitalize()} Grype report does not preserve the high severity fail gate")
    built = status.get("built") if isinstance(status, dict) else None
    schema = status.get("schemaVersion") if isinstance(status, dict) else None
    valid = status.get("valid") if isinstance(status, dict) else None
    if not isinstance(built, str) or not built.strip():
        raise SystemExit(f"{service.capitalize()} Grype report has no vulnerability DB build identity")
    if not isinstance(schema, str) or not schema.strip():
        raise SystemExit(f"{service.capitalize()} Grype report has no vulnerability DB schema identity")
    if valid is not True:
        raise SystemExit(f"{service.capitalize()} Grype vulnerability DB is not valid")
    validate_scanner_source(
        service,
        report.get("source"),
        expected_product,
        expected_image_id,
        "SBOM",
    )

    matches = report.get("matches")
    ignored = report.get("ignoredMatches", [])
    if not isinstance(matches, list) or not isinstance(ignored, list):
        raise SystemExit(f"{service.capitalize()} Grype report match arrays are invalid")
    if openssl_ignored is not None:
        # These exact records were independently bound to raw evidence, the
        # current image, native/source/configuration closure and VEX status.
        ignored = ignored.copy()
        for entry in openssl_ignored:
            if entry not in ignored:
                raise SystemExit("Missing verified OpenSSL ignored record")
            ignored.remove(entry)
    if ignored and hashlib_exception != "fixed":
        raise SystemExit(f"{service.capitalize()} fixed zlib report must not contain ignored findings")
    if hashlib_exception == "fixed" and (
        len(ignored) != 1 or not exact_hashlib_match(ignored[0])
        or ignored[0].get("appliedIgnoreRules") != [{"namespace": "vex", "vex-status": "fixed"}]
    ):
        raise SystemExit("Filtered backend report did not apply exactly the verified hashlib fix")
    fixed_raw_matches = 0
    for match in matches:
        if not isinstance(match, dict):
            raise SystemExit(f"{service.capitalize()} Grype report contains an invalid match")
        vulnerability = match.get("vulnerability")
        if not isinstance(vulnerability, dict):
            raise SystemExit(f"{service.capitalize()} Grype report contains a match without a vulnerability")
        if vulnerability.get("id") in (CVE, "CVE-2026-4738"):
            raise SystemExit(f"Residual {vulnerability.get('id')} match remains for {service}")
        severity = vulnerability.get("severity")
        if hashlib_exception is not None and severity not in {"Unknown", "Negligible", "Low", "Medium", "High", "Critical"}:
            raise SystemExit("Unexpected Grype severity in the verified hashlib assessment")
        if vulnerability.get("id") == HASHLIB_CVE and hashlib_exception is not None:
            if hashlib_exception != "raw" or not exact_hashlib_match(match):
                raise SystemExit("Unexpected residual or inconsistent hashlib finding")
            fixed_raw_matches += 1
            continue
        if isinstance(severity, str) and severity.lower() in {"high", "critical"}:
            raise SystemExit(f"{service.capitalize()} Grype report contains a high or critical finding")
    if hashlib_exception == "raw" and fixed_raw_matches != 1:
        raise SystemExit("Raw backend report requires exactly one reviewed hashlib finding")
    if hashlib_exception == "raw":
        return  # Only the subsequent filtered scan emits final scanner metadata.
    if hashlib_exception == "fixed":
        print("backend_hashlib_grype_fixed_ignored_matches=1")
    print(f"{service}_fixed_zlib_ignored_matches=0")
    print(f"{service}_grype_version={GRYPE_VERSION}")
    print(f"{service}_grype_db_built={built}")
    print(f"{service}_grype_db_schema={schema}")


def exact_hashlib_match(match: Any) -> bool:
    if not isinstance(match, dict):
        return False
    package, vulnerability = match.get("artifact"), match.get("vulnerability")
    if not isinstance(package, dict) or not isinstance(vulnerability, dict):
        return False
    cpes = package.get("cpes")
    expected_cpes = [f"cpe:2.3:a:{vendor}:python:3.14.10:*:*:*:*:*:*:*"
                     for vendor in ("python_software_foundation", "python")]
    if (vulnerability.get("id"), vulnerability.get("namespace"), vulnerability.get("severity")) != (HASHLIB_CVE, "nvd:cpe", "High"):
        return False
    fix = vulnerability.get("fix")
    if not isinstance(fix, dict) or fix.get("versions") != ["3.14.11"] or fix.get("state") != "fixed":
        return False
    if (package.get("name"), package.get("version"), package.get("type"), package.get("purl")) != (
        "python", "3.14.10", "binary", HASHLIB_PURL,
    ) or cpes not in (expected_cpes, expected_cpes[::-1]) or match.get("relatedVulnerabilities") != []:
        return False
    details = match.get("matchDetails")
    reviewed_details = [{
        "type": "cpe-match", "matcher": "stock-matcher",
        "searchedBy": {"namespace": "nvd:cpe", "cpes": [cpe], "package": {"name": "python", "version": "3.14.10"}},
        "found": {"vulnerabilityID": HASHLIB_CVE, "versionConstraint": ">= 3.14.10, < 3.14.11 (unknown)",
                  "cpes": [cpe.replace(":3.14.10:", ":*:")]},
        "fix": {"suggestedVersion": "3.14.11"},
    } for cpe in expected_cpes]
    if details not in (reviewed_details, reviewed_details[::-1]):
        return False
    locations = package.get("locations")
    expected_locations = {"/usr/local/bin/python3.14": "primary", "/usr/local/lib/libpython3.14.so.1.0": "supporting"}
    if not isinstance(locations, list) or len(locations) != len(expected_locations):
        return False
    for location in locations:
        if not isinstance(location, dict):
            return False
        name = location.get("path")
        if (name not in expected_locations or location.get("accessPath") != name
                or location.get("annotations") != {"evidence": expected_locations.pop(name)}
                or not IMAGE_ID.fullmatch(str(location.get("layerID", "")))):
            return False
    return not expected_locations


def validate_hashlib_proof(path: Path) -> dict[str, Any]:
    if path.is_symlink() or path.resolve(strict=True) != path.absolute():
        raise SystemExit("Hashlib proof is linked")
    proof = load_json(path, maximum_bytes=8192)
    if (set(proof) != set(HASHLIB_PROOF)
            or any(type(proof[key]) is not type(value) or proof[key] != value for key, value in HASHLIB_PROOF.items())):
        raise SystemExit("Hashlib proof lacks the reviewed source, module identity and OpenSSL behavior")
    return proof


def hashlib_statement(product: str, image_id: str, proof: dict[str, Any]) -> dict[str, Any]:
    return {
        "vulnerability": {"name": HASHLIB_CVE}, "products": [{"@id": product, "subcomponents": [{"@id": HASHLIB_PURL}]}],
        "status": "fixed", "status_notes": json.dumps({"image_id": image_id, "proof": proof}, sort_keys=True),
    }


def validate_hashlib_vex(path: Path, product: str, image_id: str) -> None:
    proof = validate_hashlib_proof(path.with_name("backend-hashlib-runtime.json"))
    if path.is_symlink() or path.resolve(strict=True) != path.absolute():
        raise SystemExit("Hashlib VEX is linked")
    value = load_json(path, maximum_bytes=8192)
    if (value.get("@context") != "https://openvex.dev/ns/v0.2.0"
            or value.get("@id") != f"urn:dcompany:openvex:cve-2026-119170:{image_id.removeprefix('sha256:')}"
            or value.get("version") != 1 or value.get("statements") != [hashlib_statement(product, image_id, proof)]):
        raise SystemExit("Hashlib VEX does not bind the reviewed fix and image")


def prove_fixed_hashlib(arguments: list[str]) -> int:
    if len(arguments) not in (7, 8):
        raise SystemExit("Hashlib remediation requires raw Grype report, image tag, config ID, runtime proof and VEX output")
    raw, product, image_id, proof_path, output = arguments[2:7]
    openssl_ignored = None
    if len(arguments) == 8:
        flag = "--openssl-assessment="
        if not arguments[7].startswith(flag):
            raise SystemExit("Unexpected hashlib assessment argument")
        from runpy import run_path
        helper = run_path(str(Path(__file__).with_name("backend-openssl-assessment.py")))
        openssl_ignored = helper["verify_report"](Path(raw).parent, Path(arguments[7].removeprefix(flag)),
                                                  product, image_id, Path(raw))
    validate_expected_identity(product, image_id)
    proof = validate_hashlib_proof(Path(proof_path))
    # Only the unfiltered threshold failure for this exact, proved-fixed
    # runtime component can produce a one-CVE VEX statement.
    validate_fixed_report("backend", Path(raw), product, image_id, hashlib_exception="raw", openssl_ignored=openssl_ignored)
    document = {
        "@context": "https://openvex.dev/ns/v0.2.0",
        "@id": f"urn:dcompany:openvex:cve-2026-119170:{image_id.removeprefix('sha256:')}",
        "author": "D Company ERP", "timestamp": datetime.now(timezone.utc).isoformat(), "version": 1,
        "statements": [hashlib_statement(product, image_id, proof)],
    }
    destination = Path(output)
    with destination.open("x", encoding="utf-8") as stream:
        json.dump(document, stream, sort_keys=True)
        stream.write("\n")
    destination.chmod(0o444)
    metadata_raw = str(Path(raw).with_name("backend-grype-unfiltered.json")) if openssl_ignored is not None else raw
    if openssl_ignored is not None:
        print(f"backend_hashlib_assessed_grype_sha256={hashlib.sha256(Path(raw).read_bytes()).hexdigest()}")
    for name, path in (("raw_grype", metadata_raw), ("vex", output)):
        print(f"backend_hashlib_{name}_sha256={hashlib.sha256(Path(path).read_bytes()).hexdigest()}")
    return 0


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


def validate_official_runtime(value: Any, service: str) -> None:
    distro_version, port_commit = official_profile(service)
    fixed = {
        "service": service,
        "package": "zlib", "package_version": CADDY_PACKAGE_VERSION,
        "port_commit": port_commit, "distro": "alpine",
        "distro_version": distro_version, "architecture": "x86_64",
        "apk_signature_verified": True, "runtime_path": CADDY_RUNTIME_PATH,
        "runtime_version": "1.4", "soname": "libz.so.1", "proc_map": CADDY_RUNTIME_PATH,
    }
    variable = {"apk_sha256", "runtime_sha256", "apk_runtime_sha256", "manifest_algorithm", "manifest_digest"}
    if not isinstance(value, dict) or set(value) != set(fixed) | variable:
        raise SystemExit("Official runtime proof has missing or unknown keys")
    if any(type(value[key]) is not type(expected) or value[key] != expected
           for key, expected in fixed.items()):
        raise SystemExit("Official runtime proof does not match its authenticated package/runtime profile")
    for key in ("apk_sha256", "runtime_sha256", "apk_runtime_sha256"):
        if not isinstance(value[key], str) or re.fullmatch(r"[0-9a-f]{64}", value[key]) is None:
            raise SystemExit("Official runtime proof has an invalid SHA256")
    if value["apk_runtime_sha256"] != value["runtime_sha256"]:
        raise SystemExit("Official runtime bytes differ from the authenticated APK payload")
    algorithm = value["manifest_algorithm"]
    length = {"sha1": 40, "sha256": 64}.get(algorithm) if isinstance(algorithm, str) else None
    if length is None or not isinstance(value["manifest_digest"], str) or re.fullmatch(
        "[0-9a-f]{" + str(length) + "}", value["manifest_digest"]
    ) is None:
        raise SystemExit("Official runtime proof has an invalid authenticated manifest digest")
    if value["manifest_algorithm"] == "sha256" and value["manifest_digest"] != value["runtime_sha256"]:
        raise SystemExit("Official runtime bytes differ from the authenticated APK manifest")


def bind_official_runtime(arguments: list[str]) -> int:
    if len(arguments) != 7:
        raise SystemExit("Usage: --bind-runtime-proof SERVICE RAW_PROOF IMAGE_PRODUCT CONFIG_IMAGE_ID OUTPUT")
    service = arguments[2]
    official_profile(service)
    raw, product, image_id, output = Path(arguments[3]), arguments[4], arguments[5], Path(arguments[6])
    validate_expected_identity(product, image_id)
    if raw.is_symlink() or raw.resolve(strict=True) != raw.absolute():
        raise SystemExit("Official runtime proof input is linked")
    proof = load_json(raw)
    validate_official_runtime(proof, service)
    with output.open("x", encoding="utf-8") as stream:
        json.dump({"image_id": image_id, "image_product": product, "proof": proof}, stream, sort_keys=True)
        stream.write("\n")
    output.chmod(0o444)
    return 0


def validate_fixed_mode(arguments: list[str], fixed_service: str) -> int:
    usage = (f"Usage: {arguments[0]} --{fixed_service}-fixed-sbom SYFT_REPORT "
             f"IMAGE_PRODUCT IMAGE_ID {fixed_service}=GRYPE_REPORT" +
             " --runtime-proof=PROOF [--archive-platform=PLATFORM@CONFIG_IMAGE_ID] [--hashlib-vex=VEX] [--openssl-assessment=VEX]")
    if len(arguments) < 6:
        raise SystemExit(usage)
    sbom_path = Path(arguments[2]).resolve(strict=True)
    expected_product, expected_image_id = arguments[3:5]
    validate_expected_identity(expected_product, expected_image_id)
    service, separator, raw_report_path = arguments[5].partition("=")
    if service != fixed_service or not separator or not raw_report_path:
        raise SystemExit(f"--{fixed_service}-fixed-sbom requires exactly one {fixed_service} Grype report binding")
    archive_platform_binding = None
    hashlib_vex = None
    openssl_assessment = None
    if fixed_service in OFFICIAL_PROFILES:
        if len(arguments) < 7 or not arguments[6].startswith("--runtime-proof="):
            raise SystemExit(usage)
        for argument in arguments[7:]:
            flag, separator, value = argument.partition("=")
            if not separator or not value:
                raise SystemExit(usage)
            if flag == "--archive-platform" and archive_platform_binding is None:
                archive_platform_binding = value
            elif flag == "--hashlib-vex" and fixed_service == "backend" and hashlib_vex is None:
                hashlib_vex = Path(value)
            elif flag == "--openssl-assessment" and fixed_service in {"backend", "frontend", "caddy", "postgres"} and openssl_assessment is None:
                openssl_assessment = Path(value)
            else:
                raise SystemExit(usage)
        proof_path = Path(arguments[6].removeprefix("--runtime-proof="))
        if proof_path.is_symlink() or proof_path.resolve(strict=True) != proof_path.absolute():
            raise SystemExit("Official runtime proof is linked")
        bound = load_json(proof_path)
        if (set(bound) != {"image_id", "image_product", "proof"}
                or bound["image_id"] != expected_image_id or bound["image_product"] != expected_product):
            raise SystemExit("Official runtime proof image/config identity mismatch")
        validate_official_runtime(bound["proof"], fixed_service)
        print(f"{fixed_service}_runtime_proof_sha256={hashlib.sha256(proof_path.read_bytes()).hexdigest()}")
        print(f"{fixed_service}_runtime_sha256={bound['proof']['runtime_sha256']}")
    elif len(arguments) != 6:
        raise SystemExit(usage)
    report_path = Path(raw_report_path).resolve(strict=True)
    validate_fixed_sbom(fixed_service, sbom_path, expected_image_id, archive_platform_binding=archive_platform_binding)
    if hashlib_vex is not None:
        validate_hashlib_vex(hashlib_vex, expected_product, expected_image_id)
    openssl_ignored = None
    if openssl_assessment is not None:
        from runpy import run_path
        if fixed_service == "backend":
            if hashlib_vex is None or openssl_assessment != hashlib_vex.with_name("backend-reviewed.openvex.json"):
                raise SystemExit("OpenSSL assessment requires the same-directory verified Python fix")
            helper = run_path(str(Path(__file__).with_name("backend-openssl-assessment.py")))
            openssl_ignored = helper["verify_report"](openssl_assessment.parent, sbom_path, expected_product,
                                                      expected_image_id, report_path, final=True)
        else:
            if openssl_assessment.name != f"{fixed_service}-openssl-reviewed.openvex.json":
                raise SystemExit("Service OpenSSL assessment path differs")
            helper = run_path(str(Path(__file__).with_name("service-openssl-assessments.py")))
            openssl_ignored = helper["verify_report"](fixed_service, openssl_assessment.parent, sbom_path,
                                                      expected_product, expected_image_id, report_path)
    validate_fixed_report(fixed_service, report_path, expected_product, expected_image_id,
                          hashlib_exception="fixed" if hashlib_vex else None, openssl_ignored=openssl_ignored)
    return 0


_PUBLIC_ADVISORY = re.compile(r"(?:CVE-\d{4}-\d{4,10}|GHSA-[a-z0-9]{4}-[a-z0-9]{4}-[a-z0-9]{4}|GO-\d{4}-\d{4,10})")
_PUBLIC_PACKAGE = re.compile(r"[A-Za-z0-9][A-Za-z0-9+_.@/-]{0,159}")
_PUBLIC_VERSION = re.compile(r"[A-Za-z0-9][A-Za-z0-9+_.:~!-]{0,119}")


def scan_failure_summary(path: Path, product: str, image_id: str) -> str:
    """Public, bounded failure evidence only; never a scan acceptance decision."""
    validate_expected_identity(product, image_id)
    report = load_json(path, maximum_bytes=64 * 1024 * 1024)
    validate_scanner_source("failure-summary", report.get("source"), product, image_id)
    descriptor = report.get("descriptor")
    if (not isinstance(descriptor, dict) or descriptor.get("name") != "grype"
            or descriptor.get("version") != GRYPE_VERSION):
        raise SystemExit("Unexpected failure-summary scanner identity")
    configuration = descriptor.get("configuration")
    if not isinstance(configuration, dict) or configuration.get("fail-on-severity") != "high":
        raise SystemExit("Unexpected failure-summary severity policy")
    matches = report.get("matches")
    if not isinstance(matches, list) or len(matches) > 100_000:
        raise SystemExit("Invalid failure-summary matches")
    findings = []
    omitted = 0
    for match in matches:
        if not isinstance(match, dict):
            omitted += 1
            continue
        vulnerability = match.get("vulnerability")
        if not isinstance(vulnerability, dict):
            omitted += 1
            continue
        severity = vulnerability.get("severity")
        if severity not in ("High", "Critical"):
            continue
        package = artifact(match)
        advisory = vulnerability_id(match)
        name, version = package.get("name"), package.get("version")
        fix = vulnerability.get("fix", {})
        fixes = fix.get("versions", []) if isinstance(fix, dict) else None
        if (not isinstance(advisory, str) or not _PUBLIC_ADVISORY.fullmatch(advisory)
                or not isinstance(name, str) or not _PUBLIC_PACKAGE.fullmatch(name)
                or not isinstance(version, str) or not _PUBLIC_VERSION.fullmatch(version)
                or not isinstance(fixes, list) or len(fixes) > 8
                or any(not isinstance(value, str) or not _PUBLIC_VERSION.fullmatch(value) for value in fixes)):
            omitted += 1
            continue
        if len(findings) < 40:
            findings.append({"id": advisory, "package": name, "version": version,
                             "severity": severity, "fix_versions": fixes})
        else:
            omitted += 1
    # Only fixed public fields are serialized; no report messages, paths or descriptions.
    return json.dumps({"diagnostic": "grype-failure-summary-v1", "image_id": image_id,
                       "image_product": product, "findings": findings, "omitted": omitted},
                      separators=(",", ":")) + "\n"


def main(arguments: list[str]) -> int:
    if len(arguments) > 1 and arguments[1] == "--validate-hashlib-runtime":
        if len(arguments) != 5:
            raise SystemExit("Hashlib validation requires proof, image tag and config ID")
        path = Path(arguments[2])
        validate_expected_identity(arguments[3], arguments[4])
        validate_hashlib_proof(path)
        print(f"backend_hashlib_runtime_proof_sha256={hashlib.sha256(path.read_bytes()).hexdigest()}")
        print(f"backend_hashlib_runtime_config_image_id={arguments[4]}")
        return 0
    if len(arguments) > 1 and arguments[1] == "--prove-fixed-hashlib":
        return prove_fixed_hashlib(arguments)
    if len(arguments) > 1 and arguments[1] == "--scan-failure-summary":
        if len(arguments) != 5:
            raise SystemExit("Invalid scan failure summary arguments")
        sys.stdout.write(scan_failure_summary(Path(arguments[2]), arguments[3], arguments[4]))
        return 0
    if len(arguments) > 1 and arguments[1] == "--bind-runtime-proof":
        return bind_official_runtime(arguments)
    if len(arguments) > 1 and arguments[1] == "--bind-caddy-runtime-proof":
        return bind_official_runtime([arguments[0], "--bind-runtime-proof", "caddy", *arguments[2:]])
    fixed_modes = {f"--{service}-fixed-sbom": service for service in OFFICIAL_PROFILES}
    if len(arguments) > 1 and arguments[1] in fixed_modes:
        return validate_fixed_mode(arguments, fixed_modes[arguments[1]])
    if any(mode in arguments[2:] for mode in fixed_modes):
        raise SystemExit("Fixed-package flags must use a separate fixed-SBOM verifier mode")
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
