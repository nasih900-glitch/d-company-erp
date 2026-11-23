#!/usr/bin/env python3
"""Prove only the exact signed Alpine3.24 OpenSSL r1 vendor backports fixed."""
from __future__ import annotations

import base64
from collections import Counter
import copy
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
from runpy import run_path
import sys
import urllib.request
import zlib

HERE = Path(__file__).resolve().parent
COMMON = run_path(str(HERE / "backend-openssl-assessment.py"))
require, load, canonical, digest = (COMMON[key] for key in ("require", "load", "canonical", "digest"))
CVES, PACKAGES = COMMON["CVES"], COMMON["PACKAGES"]
STATUS = [{"namespace": "vex", "vex-status": "fixed"}]
REVIEW = HERE.parent / "security/vex/openssl-alpine324-r1-fixed.json"
CUSTOM_REVIEW = HERE.parent / "security/vex/openssl-dcompany-backport-fixed.json"
CUSTOM_PROFILE = "dcompany-backport"


def reviewed():
    value = load(REVIEW, 32768)
    require(set(value) == {"schema", "vendor_commit", "vendor_source", "vendor_secdb", "services", "package_version",
                           "signature_key", "signature_key_sha256", "archives_sha256", "runtime_sha256", "runtime_digests", "apk_metadata_sha256", "matches",
                           "deployment_sources"} and value["schema"] == 1
            and value["package_version"] == "3.5.9-r1"
            and value["vendor_commit"] == "0f4c7e8d0f5f7cebbdc2156eca4391080b732bf2"
            and value["services"] == {"backend": "3.24.3", "frontend": "3.24.1", "postgres": "3.24.4"}
            and set(value["matches"]) == {cve + "/" + package for cve in CVES for package in PACKAGES},
            "invalid fixed-only vendor review")
    return value


def expected_runtime(service, review):
    require(service in review["services"], "service has no vendor-fixed profile")
    return {"schema": 1, "service": service, "distro_version": review["services"][service], "architecture": "x86_64",
            "port_commit": review["vendor_commit"], "signature_key": review["signature_key"], "signature_verified": True, "signature_key_sha256": review["signature_key_sha256"],
            "package_version": review["package_version"], "archives_sha256": review["archives_sha256"],
            "runtime_sha256": review["runtime_sha256"]}


def bind_runtime(service, raw_path, output, product, image_id):
    COMMON["base"]()["validate_expected_identity"](product, image_id)
    proof = load(raw_path, 8192)
    require(canonical(proof) == canonical(expected_runtime(service, reviewed())), "signed vendor runtime proof changed")
    COMMON["write_new"](output, {"image_id": image_id, "image_product": product, "proof": proof})


def signature(match, distro):
    normalized = copy.deepcopy(match)
    artifact = normalized["artifact"]
    package = artifact["name"]
    expected = f"pkg:apk/alpine/{package}@3.5.9-r1?arch=x86_64&distro=alpine-{distro}&upstream=openssl"
    require(artifact.get("purl") == expected, "unexpected vendor package product identity")
    # Only the independently checked base distro differs between the three
    # services. Package metadata, matcher, CVE, version and fix state stay exact.
    artifact["purl"] = expected.replace("alpine-" + distro, "alpine-3.24.1")
    return COMMON["match_signature"](normalized)


def validate_scanner(service, report, product, image_id):
    b = COMMON["base"]()
    b["validate_scanner_source"](service, report.get("source"), product, image_id, "fixed vendor assessment")
    descriptor = report.get("descriptor", {})
    status = descriptor.get("db", {}).get("status", {})
    require(descriptor.get("name") == "grype" and descriptor.get("version") == b["GRYPE_VERSION"]
            and descriptor.get("configuration", {}).get("fail-on-severity") == "high" and status.get("valid") is True
            and isinstance(status.get("built"), str) and bool(status["built"].strip())
            and isinstance(status.get("schemaVersion"), str) and bool(status["schemaVersion"].strip()),
            "invalid vendor scanner identity, High gate or database")


def context(service, directory, sbom_path, product, image_id):
    b, review = COMMON["base"](), reviewed()
    b["validate_expected_identity"](product, image_id)
    require(service in review["services"] and all(
        hashlib.sha256((HERE.parents[1] / name).read_bytes()).hexdigest() == value
        for name, value in review["deployment_sources"].items()), "vendor recipe or verifier source changed")
    runtime = load(directory / f"{service}-openssl-vendor-runtime.json", 8192)
    require(set(runtime) == {"image_id", "image_product", "proof"} and runtime["image_id"] == image_id
            and runtime["image_product"] == product
            and canonical(runtime["proof"]) == canonical(expected_runtime(service, review)), "unbound signed vendor runtime proof")
    sbom = load(sbom_path)
    metadata = sbom.get("source", {}).get("metadata", {})
    require(sbom.get("schema") == b["SYFT_SCHEMA"] and sbom.get("descriptor", {}).get("name") == "syft"
            and sbom.get("descriptor", {}).get("version") == b["SYFT_VERSION"]
            and sbom.get("source", {}).get("type") == "image" and metadata.get("imageID") == image_id,
            "vendor Syft image identity changed")
    config_bytes = base64.b64decode(metadata["config"], validate=True)
    config = json.loads(config_bytes)
    require("sha256:" + hashlib.sha256(config_bytes).hexdigest() == image_id
            and config.get("os") == "linux" and config.get("architecture") == "amd64", "vendor image configuration changed")
    files = sbom.get("files", [])
    for path, expected in review["runtime_sha256"].items():
        observed = [item for item in files if item.get("location", {}).get("path") == path]
        require(len(observed) == 1 and observed[0].get("executable", {}).get("format") == "elf"
                and observed[0].get("digests") == review["runtime_digests"][path],
                "Syft runtime does not match authenticated vendor payload")
    raw = load(directory / f"{service}-grype-vendor-unfiltered.json")
    validate_scanner(service, raw, product, image_id)
    require(raw.get("ignoredMatches", []) == [] and isinstance(raw.get("matches"), list), "invalid original vendor scan")
    approved, seen, python = [], set(), 0
    for match in raw["matches"]:
        vulnerability, artifact = match.get("vulnerability", {}), match.get("artifact", {})
        if vulnerability.get("id") in CVES:
            key = vulnerability["id"] + "/" + str(artifact.get("name"))
            require(key in review["matches"] and key not in seen
                    and signature(match, review["services"][service]) == review["matches"][key], "unreviewed vendor finding")
            candidates = [item for item in sbom["artifacts"] if item.get("id") == artifact.get("id")]
            require(len(candidates) == 1 and COMMON["project_apk"](candidates[0], review) == {
                field: artifact.get(field) for field in ("id", "name", "version", "type", "purl", "locations", "cpes", "metadataType", "metadata")},
                "vendor finding does not match exact signed-package Syft artifact")
            approved.append(match); seen.add(key)
        elif service == "backend" and b["exact_hashlib_match"](match):
            python += 1
        else:
            require(vulnerability.get("severity") in {"Unknown", "Negligible", "Low", "Medium"}, "unrelated vendor High remains blocking")
    require(seen == set(review["matches"]) and python <= 1
            and len(set(map(canonical, raw["matches"]))) == len(raw["matches"]), "missing or duplicate fixed vendor findings")
    return review, raw, approved


def document(service, review, raw, product, image_id, runtime):
    purls = {match["artifact"]["name"]: match["artifact"]["purl"] for match in raw["matches"]
             if match["vulnerability"]["id"] in CVES}
    notes = canonical({"image_id": image_id, "review_sha256": digest(review), "raw_sha256": digest(raw),
                       "runtime_sha256": digest(runtime)})
    return {"@context": "https://openvex.dev/ns/v0.2.0", "@id": f"urn:dcompany:openvex:{service}-openssl-vendor-fixed:{image_id[7:]}",
            "author": "D Company ERP", "version": 1,
            "statements": [{"vulnerability": {"name": cve}, "products": [{"@id": product, "subcomponents": [
                {"@id": purls[name]} for name in PACKAGES]}], "status": "fixed", "status_notes": notes,
                "action_statement": "Authenticated Alpine3.24 OpenSSL3.5.9-r1 vendor backport: " + review["vendor_source"]}
                for cve in CVES]}


def validate_statement(vex, expected):
    require(set(vex) == set(expected) | {"timestamp"}
            and {key: value for key, value in vex.items() if key != "timestamp"} == expected,
            "unbound vendor fixed statement")
    stamp = vex.get("timestamp")
    try:
        parsed = datetime.fromisoformat(stamp)
        require(parsed.tzinfo is not None and parsed.utcoffset().total_seconds() == 0 and parsed.isoformat() == stamp,
                "invalid vendor statement timestamp")
    except (TypeError, ValueError) as exc:
        raise SystemExit("Invalid vendor statement timestamp") from exc


def combined_document(review, raw, runtime, product, image_id, python_vex):
    value = document("backend", review, raw, product, image_id, runtime)
    value["@id"] = f"urn:dcompany:openvex:backend-vendor-reviewed:{image_id[7:]}"
    value["statements"] += python_vex["statements"]
    return value


def r1_verify_report(service, directory, sbom, product, image_id, report_path, *, final=False):
    review, raw, approved = context(service, directory, sbom, product, image_id)
    runtime = load(directory / f"{service}-openssl-vendor-runtime.json", 8192)
    expected_vex = document(service, review, raw, product, image_id, runtime)
    vex = load(directory / f"{service}-openssl-vendor-fixed.openvex.json", 32768)
    validate_statement(vex, expected_vex)
    report = load(report_path)
    validate_scanner(service, report, product, image_id)
    def db_identity(scan):
        db = copy.deepcopy(scan.get("descriptor", {}).get("db", {}))
        if isinstance(db.get("status"), dict): db["status"].pop("path", None)
        return db
    require(db_identity(report) == db_identity(raw), "vendor rescan database changed")
    expected_ignored = [{**item, "appliedIgnoreRules": STATUS} for item in approved]
    expected_active = [item for item in raw["matches"] if item not in approved]
    if final:
        b = COMMON["base"]()
        b["validate_hashlib_vex"](directory / "backend-hashlib-fixed.openvex.json", product, image_id)
        python = [item for item in expected_active if b["exact_hashlib_match"](item)]
        require(service == "backend" and len(python) == 1, "missing independent hashlib finding")
        expected_active.remove(python[0]); expected_ignored.append({**python[0], "appliedIgnoreRules": STATUS})
        python_vex = load(directory / "backend-hashlib-fixed.openvex.json", 8192)
        combined = load(directory / "backend-vendor-reviewed.openvex.json", 32768)
        validate_statement(combined, combined_document(review, raw, runtime, product, image_id, python_vex))
    require(Counter(map(canonical, report.get("matches", []))) == Counter(map(canonical, expected_active))
            and Counter(map(canonical, report.get("ignoredMatches", []))) == Counter(map(canonical, expected_ignored)),
            "vendor raw/final records or fixed ignore rules changed")
    return expected_ignored[:len(approved)]


def custom_reviewed():
    value = load(CUSTOM_REVIEW, 32768)
    cves = tuple(value.get("cves", []))
    core_cves = ["CVE-2026-107493", "CVE-2026-107494", "CVE-2026-121482",
                 "CVE-2026-124709", "CVE-2026-84786"]
    require(set(value) == {"schema", "profile", "package_version", "services", "source",
                           "alpine_aports_commit", "source_evidence_sha256", "runtime_sha256",
                           "package_payload_sha256",
                           "cves", "service_cves", "matches"}
            and value["schema"] == 1 and value["profile"] == CUSTOM_PROFILE
            and value["package_version"] == "3.5.9_p20261123-r0"
            and value["services"] == {"backend": "3.24.3", "frontend": "3.24.1",
                                       "caddy": "3.23.5", "postgres": "3.24.4"}
            and len(cves) == 9 and len(set(cves)) == 9
            and value["service_cves"] == {"backend": core_cves, "frontend": core_cves,
                                           "caddy": list(cves), "postgres": core_cves}
            and value["package_payload_sha256"] == {
                "libcrypto3": "afa8ded7c167acdf000b3479d60bc078ec8f4b88bd0196c922c39a6fedc2f956",
                "libssl3": "96514a7dadc049413d146e93797107db096f6d0547f3f56a46ba0e40a5182254"}
            and set(value["matches"]) == {f"{cve}/{package}" for cve in cves for package in PACKAGES},
            "invalid D Company backport review")
    return value


def custom_apk_payload_sha256(path, expected=None):
    require(path.stat().st_size <= 32 * 1024 * 1024, "backport archive is unexpectedly large")
    try:
        decompressor = zlib.decompressobj(31)
        signature = decompressor.decompress(path.read_bytes(), 4096)
    except zlib.error as exc:
        raise SystemExit("Backport APK signature member is invalid") from exc
    require(bool(signature) and len(signature) <= 4096 and decompressor.eof
            and not decompressor.unconsumed_tail and bool(decompressor.unused_data),
            "backport APK signature member boundary changed")
    observed = hashlib.sha256(decompressor.unused_data).hexdigest()
    if expected is not None:
        require(observed == expected, "backport reproducible package payload changed")
    return observed


def custom_export(path):
    review = custom_reviewed()
    require(path.is_dir() and not path.is_symlink() and path.resolve(strict=True) == path.absolute(),
            "backport export is missing or linked")
    provenance_path = path / "provenance.json"
    patch_path = path / "patches.sha256"
    signature_path = path / "openssl-signature.status"
    require(all(item.is_file() and not item.is_symlink()
                and item.resolve(strict=True) == item.absolute()
                for item in (provenance_path, patch_path, signature_path)),
            "backport producer evidence is missing or linked")
    provenance = load(provenance_path, 16384)
    require(set(provenance) == {"schema", "status", "distro", "architecture", "source",
                                "alpine_aports_commit", "dane_series", "package_version",
                                "targeted_tests", "toolchain_manifest_sha256", "source_evidence_sha256",
                                "archives_sha256", "runtime_payload_sha256"}
            and provenance["schema"] == 1 and provenance["status"] == "isolated-proof-only"
            and provenance["distro"] == "alpine-3.24.4" and provenance["architecture"] == "x86_64"
            and provenance["package_version"] == review["package_version"]
            and provenance["alpine_aports_commit"] == review["alpine_aports_commit"]
            and provenance["source"] == review["source"]
            and provenance["source_evidence_sha256"] == review["source_evidence_sha256"]
            and provenance["runtime_payload_sha256"] == review["runtime_sha256"],
            "backport producer provenance changed")
    require(hashlib.sha256(patch_path.read_bytes()).hexdigest() == review["source_evidence_sha256"]["patch_inventory"]
            and hashlib.sha256(signature_path.read_bytes()).hexdigest() == review["source_evidence_sha256"]["signature_status"],
            "backport source evidence changed")
    archives, package_payloads = {}, {}
    for package in PACKAGES:
        archive = path / "repository/x86_64" / f"{package}-{review['package_version']}.apk"
        require(archive.is_file() and not archive.is_symlink() and archive.resolve(strict=True) == archive.absolute(),
                "backport signed archive is missing or linked")
        archives[package] = hashlib.sha256(archive.read_bytes()).hexdigest()
        package_payloads[package] = custom_apk_payload_sha256(
            archive, review["package_payload_sha256"][package])
    require(archives == {name: provenance["archives_sha256"][name] for name in PACKAGES},
            "backport signed archive digest changed")
    require(package_payloads == review["package_payload_sha256"],
            "backport reproducible package payload changed")
    key_directory = path / "keys"
    keys = list(key_directory.glob("*.rsa.pub"))
    require(key_directory.is_dir() and not key_directory.is_symlink()
            and len(keys) == 1 and list(key_directory.iterdir()) == keys
            and keys[0].is_file() and not keys[0].is_symlink()
            and keys[0].resolve(strict=True) == keys[0].absolute(),
            "ambiguous or linked backport signing key")
    return {"export_path": str(path), "provenance_sha256": hashlib.sha256(provenance_path.read_bytes()).hexdigest(),
            "patch_inventory_sha256": hashlib.sha256(patch_path.read_bytes()).hexdigest(),
            "signature_status_sha256": hashlib.sha256(signature_path.read_bytes()).hexdigest(),
            "signing_key_sha256": hashlib.sha256(keys[0].read_bytes()).hexdigest(),
            "archives_sha256": archives, "package_payload_sha256": package_payloads,
            "runtime_sha256": provenance["runtime_payload_sha256"]}


def expected_custom_runtime(service, producer):
    review = custom_reviewed()
    return {"schema": 1, "service": service, "distro": "alpine",
            "distro_version": review["services"][service], "architecture": "x86_64",
            "source_version": review["source"]["version"], "source_sha256": review["source"]["archive_sha256"],
            "source_signing_fingerprint": review["source"]["signing_fingerprint"],
            "package_version": review["package_version"], "producer_provenance_sha256": producer["provenance_sha256"],
            "patch_inventory_sha256": producer["patch_inventory_sha256"], "apk_signature_verified": True,
            "signing_key_sha256": producer["signing_key_sha256"], "archives_sha256": producer["archives_sha256"],
            "runtime_sha256": review["runtime_sha256"]}


def bind_custom_runtime(service, raw_path, output, export_path, product, image_id):
    COMMON["base"]()["validate_expected_identity"](product, image_id)
    review, producer = custom_reviewed(), custom_export(export_path)
    require(service in review["services"], "service has no D Company backport profile")
    proof = load(raw_path, 16384)
    require(canonical(proof) == canonical(expected_custom_runtime(service, producer)),
            "signed backport runtime proof changed")
    COMMON["write_new"](output, {"profile": CUSTOM_PROFILE, "image_id": image_id,
                                  "image_product": product, "proof": proof, "producer": producer})


def custom_signature(match, distro):
    normalized = copy.deepcopy(match)
    artifact = normalized.get("artifact", {})
    package = artifact.get("name")
    expected = f"pkg:apk/alpine/{package}@3.5.9_p20261123-r0?arch=x86_64&distro=alpine-{distro}&upstream=openssl"
    require(artifact.get("purl") == expected, "unexpected backport package product identity")
    artifact["purl"] = expected.replace("alpine-" + distro, "alpine-3.23.5")
    return COMMON["match_signature"](normalized)


def project_custom_apk(package, review):
    metadata, cpes = package.get("metadata"), package.get("cpes")
    name = package.get("name")
    expected_paths = {
        "libcrypto3": ["/etc", "/etc/ssl", "/etc/ssl/ct_log_list.cnf", "/etc/ssl/ct_log_list.cnf.dist",
                       "/etc/ssl/openssl.cnf", "/etc/ssl/openssl.cnf.dist", "/usr", "/usr/lib",
                       "/usr/lib/libcrypto.so.3", "/usr/lib/engines-3", "/usr/lib/engines-3/afalg.so",
                       "/usr/lib/engines-3/capi.so", "/usr/lib/engines-3/loader_attic.so",
                       "/usr/lib/engines-3/padlock.so", "/usr/lib/ossl-modules",
                       "/usr/lib/ossl-modules/legacy.so"],
        "libssl3": ["/usr", "/usr/lib", "/usr/lib/libssl.so.3"],
    }
    require(package.get("metadataType") == "apk-db-entry" and isinstance(metadata, dict)
            and name in expected_paths and metadata.get("package") == name
            and metadata.get("originPackage") == "openssl"
            and metadata.get("version") == review["package_version"]
            and metadata.get("architecture") == "x86_64"
            and [item.get("path") for item in metadata.get("files", [])] == expected_paths[name],
            "unreviewed Syft backport APK metadata")
    require(isinstance(cpes, list) and all(isinstance(item, dict) and set(item) == {"cpe", "source"}
            and item["source"] == "syft-generated" and isinstance(item["cpe"], str) for item in cpes),
            "unexpected Syft backport CPE serialization")
    projected = {field: package.get(field) for field in ("id", "name", "version", "type", "purl", "locations")}
    projected.update({"cpes": [item["cpe"] for item in cpes], "metadataType": "ApkMetadata",
                      "metadata": {"files": [{"path": item["path"]} for item in metadata["files"]],
                                   "Arch": metadata["architecture"]}})
    return projected


def custom_context(service, directory, sbom_path, product, image_id):
    b, review = COMMON["base"](), custom_reviewed()
    b["validate_expected_identity"](product, image_id)
    runtime = load(directory / f"{service}-openssl-vendor-runtime.json", 32768)
    require(set(runtime) == {"profile", "image_id", "image_product", "proof", "producer"}
            and runtime["profile"] == CUSTOM_PROFILE and runtime["image_id"] == image_id
            and runtime["image_product"] == product and service in review["services"]
            and runtime["producer"].get("package_payload_sha256") == review["package_payload_sha256"]
            and canonical(runtime["proof"]) == canonical(expected_custom_runtime(service, runtime["producer"])),
            "unbound D Company backport runtime proof")
    sbom = load(sbom_path)
    metadata = sbom.get("source", {}).get("metadata", {})
    require(sbom.get("schema") == b["SYFT_SCHEMA"] and sbom.get("descriptor", {}).get("name") == "syft"
            and sbom.get("descriptor", {}).get("version") == b["SYFT_VERSION"]
            and sbom.get("source", {}).get("type") == "image" and metadata.get("imageID") == image_id,
            "backport Syft image identity changed")
    config_bytes = base64.b64decode(metadata["config"], validate=True)
    config = json.loads(config_bytes)
    require("sha256:" + hashlib.sha256(config_bytes).hexdigest() == image_id
            and config.get("os") == "linux" and config.get("architecture") == "amd64",
            "backport image configuration changed")
    files = sbom.get("files", [])
    for path, expected in review["runtime_sha256"].items():
        observed = [item for item in files if item.get("location", {}).get("path") == path]
        sha256 = [entry.get("value") for entry in observed[0].get("digests", [])
                  if entry.get("algorithm") == "sha256"] if len(observed) == 1 else []
        require(len(observed) == 1 and observed[0].get("executable", {}).get("format") == "elf"
                and sha256 == [expected] and runtime["proof"]["runtime_sha256"][path] == expected,
                "Syft runtime does not match produced backport payload")
    raw = load(directory / f"{service}-grype-vendor-unfiltered.json")
    validate_scanner(service, raw, product, image_id)
    require(raw.get("ignoredMatches", []) == [] and isinstance(raw.get("matches"), list),
            "invalid original backport scan")
    cves, approved, seen, python = set(review["cves"]), [], set(), 0
    for match in raw["matches"]:
        vulnerability, artifact = match.get("vulnerability", {}), match.get("artifact", {})
        cve, package = vulnerability.get("id"), artifact.get("name")
        if cve in cves:
            key = f"{cve}/{package}"
            require(key in review["matches"] and key not in seen
                    and vulnerability.get("namespace") == "nvd:cpe"
                    and vulnerability.get("fix") == {"versions": [], "state": "unknown"}
                    and len(match.get("matchDetails", [])) == 1
                    and match["matchDetails"][0].get("type") == "cpe-match"
                    and match["matchDetails"][0].get("matcher") == "apk-matcher"
                    and custom_signature(match, review["services"][service]) == review["matches"][key],
                    "unreviewed D Company backport finding")
            candidates = [item for item in sbom.get("artifacts", []) if item.get("id") == artifact.get("id")]
            require(len(candidates) == 1 and project_custom_apk(candidates[0], review) == {
                field: artifact.get(field) for field in ("id", "name", "version", "type", "purl", "locations", "cpes", "metadataType", "metadata")},
                "backport finding does not match exact Syft package projection")
            approved.append(match); seen.add(key)
        elif service == "backend" and b["exact_hashlib_match"](match):
            python += 1
        else:
            require(vulnerability.get("severity") in {"Unknown", "Negligible", "Low", "Medium"},
                    "unknown or unrelated High backport finding remains blocking")
    expected_seen = {f"{cve}/{package}" for cve in review["service_cves"][service] for package in PACKAGES}
    require(seen == expected_seen and python <= 1
            and len(set(map(canonical, raw["matches"]))) == len(raw["matches"]),
            "missing, additional, or duplicate D Company backport findings for service")
    return review, raw, approved, runtime


def custom_document(service, review, raw, product, image_id, runtime):
    reviewed_cves = tuple(review["cves"])
    observed = {cve: {} for cve in reviewed_cves}
    for match in raw["matches"]:
        cve = match.get("vulnerability", {}).get("id")
        package = match.get("artifact", {}).get("name")
        if cve in observed and package in PACKAGES:
            observed[cve][package] = match["artifact"]["purl"]
    observed = {cve: packages for cve, packages in observed.items() if packages}
    require(list(observed) == review["service_cves"][service]
            and all(set(packages) == set(PACKAGES) for packages in observed.values()),
            "backport service CVE package pairs changed")
    notes = canonical({"image_id": image_id, "profile": CUSTOM_PROFILE, "review_sha256": digest(review),
                       "raw_sha256": digest(raw), "runtime_sha256": digest(runtime),
                       "producer_provenance_sha256": runtime["producer"]["provenance_sha256"],
                       "archives_sha256": runtime["producer"]["archives_sha256"],
                       "package_payload_sha256": runtime["producer"]["package_payload_sha256"]})
    return {"@context": "https://openvex.dev/ns/v0.2.0",
            "@id": f"urn:dcompany:openvex:{service}-openssl-backport-fixed:{image_id[7:]}",
            "author": "D Company ERP", "version": 1,
            "statements": [{"vulnerability": {"name": cve}, "products": [{"@id": product,
                "subcomponents": [{"@id": observed[cve][name]} for name in PACKAGES]}], "status": "fixed",
                "status_notes": notes,
                "action_statement": "Authenticated OpenSSL 3.5.9 D Company backport packages and exact runtime payload"}
                for cve in observed]}


def custom_combined_document(review, raw, runtime, product, image_id, python_vex):
    value = custom_document("backend", review, raw, product, image_id, runtime)
    value["@id"] = f"urn:dcompany:openvex:backend-vendor-reviewed:{image_id[7:]}"
    value["statements"] += python_vex["statements"]
    return value


def custom_verify_report(service, directory, sbom, product, image_id, report_path, *, final=False):
    review, raw, approved, runtime = custom_context(service, directory, sbom, product, image_id)
    expected_vex = custom_document(service, review, raw, product, image_id, runtime)
    vex = load(directory / f"{service}-openssl-vendor-fixed.openvex.json", 65536)
    validate_statement(vex, expected_vex)
    report = load(report_path)
    validate_scanner(service, report, product, image_id)
    def db_identity(scan):
        database = copy.deepcopy(scan.get("descriptor", {}).get("db", {}))
        if isinstance(database.get("status"), dict): database["status"].pop("path", None)
        return database
    require(db_identity(report) == db_identity(raw), "backport rescan database changed")
    expected_ignored = [{**item, "appliedIgnoreRules": STATUS} for item in approved]
    expected_active = [item for item in raw["matches"] if item not in approved]
    if final:
        b = COMMON["base"]()
        b["validate_hashlib_vex"](directory / "backend-hashlib-fixed.openvex.json", product, image_id)
        python = [item for item in expected_active if b["exact_hashlib_match"](item)]
        require(service == "backend" and len(python) == 1, "missing independent hashlib finding")
        expected_active.remove(python[0]); expected_ignored.append({**python[0], "appliedIgnoreRules": STATUS})
        python_vex = load(directory / "backend-hashlib-fixed.openvex.json", 8192)
        combined = load(directory / "backend-vendor-reviewed.openvex.json", 65536)
        validate_statement(combined, custom_combined_document(review, raw, runtime, product, image_id, python_vex))
    require(Counter(map(canonical, report.get("matches", []))) == Counter(map(canonical, expected_active))
            and Counter(map(canonical, report.get("ignoredMatches", []))) == Counter(map(canonical, expected_ignored)),
            "backport raw/final records or fixed ignore rules changed")
    return expected_ignored[:len(approved)]


def verify_report(service, directory, sbom, product, image_id, report_path, *, final=False):
    bound = load(directory / f"{service}-openssl-vendor-runtime.json", 32768)
    if bound.get("profile") == CUSTOM_PROFILE:
        return custom_verify_report(service, directory, sbom, product, image_id, report_path, final=final)
    return r1_verify_report(service, directory, sbom, product, image_id, report_path, final=final)


def prepare_archives(directory):
    review = reviewed()
    require(not directory.exists() and not directory.is_symlink()
            and directory.parent.resolve(strict=True) == directory.parent.absolute(), "vendor archive directory exists or is linked")
    directory.mkdir(mode=0o700)
    for package, expected in review["archives_sha256"].items():
        require(package in PACKAGES, "unknown vendor archive")
        filename = package + "-3.5.9-r1.apk"
        url = "https://dl-cdn.alpinelinux.org/alpine/v3.24/main/x86_64/" + filename
        target = directory / filename
        # Exact digest admission precedes publication. A rotated CDN archive
        # is rejected; it is never silently substituted into the review.
        with urllib.request.urlopen(url, timeout=30) as response:
            data = response.read(16 * 1024 * 1024 + 1)
        require(len(data) <= 16 * 1024 * 1024 and hashlib.sha256(data).hexdigest() == expected,
                "downloaded vendor archive size or digest changed")
        with target.open("xb") as output: output.write(data)
        target.chmod(0o444)
        print(f"openssl_vendor_{package}_apk_sha256={expected}")


def main(args):
    mode = args[1] if len(args) > 1 else None
    if mode == "--validate-custom-export" and len(args) == 3:
        producer = custom_export(Path(args[2]))
        print(f"openssl_backport_export_path={producer['export_path']}")
        print(f"openssl_backport_provenance_sha256={producer['provenance_sha256']}")
        return
    if mode == "--custom-bind-runtime" and len(args) == 8:
        bind_custom_runtime(args[2], Path(args[3]), Path(args[4]), Path(args[5]), args[6], args[7]); return
    if mode == "--custom-has-findings" and len(args) == 6:
        review = custom_reviewed()
        require(args[2] in review["services"], "service has no D Company backport profile")
        raw = load(Path(args[3])); validate_scanner(args[2], raw, args[4], args[5])
        cves = set(review["cves"])
        raise SystemExit(0 if any(item.get("vulnerability", {}).get("id") in cves
                                  and item.get("artifact", {}).get("name") in PACKAGES
                                  for item in raw.get("matches", [])) else 1)
    if mode == "--prepare-archives" and len(args) == 3:
        prepare_archives(Path(args[2])); return
    if mode == "--bind-runtime" and len(args) == 7:
        bind_runtime(args[2], Path(args[3]), Path(args[4]), args[5], args[6]); return
    if mode == "--has-findings" and len(args) == 6:
        require(args[2] in reviewed()["services"], "service has no vendor-fixed profile")
        raw = load(Path(args[3])); validate_scanner(args[2], raw, args[4], args[5])
        raise SystemExit(0 if any(item.get("vulnerability", {}).get("id") in CVES for item in raw.get("matches", [])) else 1)
    if mode in {"--verify", "--verify-final"} and len(args) in {8, 9}:
        ignored = verify_report(args[2], Path(args[3]), Path(args[4]), args[5], args[6], Path(args[7]), final=mode == "--verify-final")
        if len(args) == 9:
            report = load(Path(args[7]))
            high = any(item.get("vulnerability", {}).get("severity") in {"High", "Critical"} for item in report["matches"])
            require(args[8] == f"--scanner-exit={2 if high else 0}", "vendor scanner exit disagrees with verified records")
        print(f"{args[2]}_openssl_vendor_fixed_ignored_matches={len(ignored)}")
        return
    if mode == "--combine" and len(args) == 7:
        directory, sbom, product, image_id, filtered = Path(args[2]), Path(args[3]), args[4], args[5], Path(args[6])
        verify_report("backend", directory, sbom, product, image_id, filtered)
        COMMON["base"]()["validate_hashlib_vex"](directory / "backend-hashlib-fixed.openvex.json", product, image_id)
        bound = load(directory / "backend-openssl-vendor-runtime.json", 32768)
        if bound.get("profile") == CUSTOM_PROFILE:
            review, raw, _, runtime = custom_context("backend", directory, sbom, product, image_id)
            value = custom_combined_document(review, raw, runtime, product, image_id,
                                             load(directory / "backend-hashlib-fixed.openvex.json", 8192))
        else:
            review, raw, _ = context("backend", directory, sbom, product, image_id)
            value = combined_document(review, raw, bound, product, image_id,
                                      load(directory / "backend-hashlib-fixed.openvex.json", 8192))
        output = directory / "backend-vendor-reviewed.openvex.json"
    else:
        require(mode in {"--prove", "--custom-prove"} and len(args) == 7, "invalid vendor-fixed proof arguments")
        service, directory, sbom, product, image_id = args[2:]
        directory = Path(directory)
        runtime = load(directory / f"{service}-openssl-vendor-runtime.json", 32768)
        if mode == "--custom-prove":
            review, raw, _, runtime = custom_context(service, directory, Path(sbom), product, image_id)
            value = custom_document(service, review, raw, product, image_id, runtime)
            review_path = CUSTOM_REVIEW
        else:
            review, raw, _ = context(service, directory, Path(sbom), product, image_id)
            value = document(service, review, raw, product, image_id, runtime)
            review_path = REVIEW
        output = directory / f"{service}-openssl-vendor-fixed.openvex.json"
        for field, path in (("runtime", directory / f"{service}-openssl-vendor-runtime.json"),
                            ("original", directory / f"{service}-grype-vendor-unfiltered.json"), ("review", review_path)):
            print(f"{service}_openssl_vendor_{field}_sha256={hashlib.sha256(path.read_bytes()).hexdigest()}")
    value["timestamp"] = datetime.now(timezone.utc).isoformat()
    COMMON["write_new"](output, value)
    metadata_key = "backend_openssl_vendor_combined_vex" if mode == "--combine" else service + "_openssl_vendor_vex"
    print(f"{metadata_key}_sha256={hashlib.sha256(output.read_bytes()).hexdigest()}")


if __name__ == "__main__":
    main(sys.argv)
