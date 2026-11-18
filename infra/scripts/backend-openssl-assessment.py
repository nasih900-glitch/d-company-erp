#!/usr/bin/env python3
"""Image-bound assessment of four OpenSSL API paths, never a severity override."""
from __future__ import annotations

from collections import Counter
from datetime import datetime, timezone
import hashlib
import base64
import ctypes
import os
import json
from pathlib import Path
from runpy import run_path
import sys
from typing import Any

HERE = Path(__file__).resolve().parent
REVIEW = HERE.parent / "security" / "vex" / "backend-openssl-reviewed.json"
CVES = ("CVE-2026-95692", "CVE-2026-88823", "CVE-2026-95691", "CVE-2026-107496")
PACKAGES = ("libcrypto3", "libssl3")
STATUS = [{"namespace": "vex", "vex-status": "not_affected"}]


def require(value: bool, message: str) -> None:
    if not value:
        raise SystemExit("Backend OpenSSL assessment: " + message)


def canonical(value: Any) -> str:
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=True)


def digest(value: Any) -> str:
    return hashlib.sha256(canonical(value).encode()).hexdigest()


def base() -> dict[str, Any]:
    return run_path(str(HERE / "verify-zlib-vex.py"))


def load(path: Path, maximum: int = 64 * 1024 * 1024) -> dict[str, Any]:
    require(not path.is_symlink() and path.resolve(strict=True) == path.absolute(), "linked evidence")
    return base()["load_json"](path, maximum_bytes=maximum)


def reviewed() -> dict[str, Any]:
    value = load(REVIEW, 256 * 1024)
    require(set(value) == {"schema", "runtime", "elf_files_sha256", "matches", "rationale", "deployment_sources", "apk_metadata_sha256"}
            and value["schema"] == 1 and set(value["rationale"]) == set(CVES), "invalid reviewed contract")
    require(set(value["matches"]) == {f"{cve}/{package}" for cve in CVES for package in PACKAGES},
            "review must cover exactly eight package findings")
    return value


def match_signature(match: dict[str, Any]) -> str:
    # DB scores/descriptions may change; the actual package, match mechanism,
    # version constraint and fix state must still be the reviewed ones.
    artifact = match.get("artifact", {})
    return digest({"vulnerability": {key: match.get("vulnerability", {}).get(key)
                                     for key in ("id", "namespace", "severity", "fix")},
                   "artifact": {key: artifact.get(key) for key in ("name", "version", "type", "purl", "cpes", "metadataType", "metadata")},
                   "locations": [{key: item.get(key) for key in ("path", "accessPath", "annotations")}
                                 for item in artifact.get("locations", [])],
                   "matchDetails": match.get("matchDetails"),
                   "relatedVulnerabilities": match.get("relatedVulnerabilities")})


def validate_sbom(sbom: dict[str, Any], image_id: str, review: dict[str, Any]) -> None:
    b = base()
    require(sbom.get("schema") == b["SYFT_SCHEMA"]
            and sbom.get("descriptor", {}).get("name") == "syft"
            and sbom.get("descriptor", {}).get("version") == b["SYFT_VERSION"]
            and sbom.get("source", {}).get("type") == "image"
            and sbom.get("source", {}).get("metadata", {}).get("imageID") == image_id,
            "Syft identity or schema changed")
    metadata = sbom["source"]["metadata"]
    try:
        config_bytes = base64.b64decode(metadata["config"], validate=True)
        configuration = json.loads(config_bytes)
        require(configuration.get("os") == "linux" and configuration.get("architecture") == "amd64", "unreviewed image platform")
        config = configuration["config"]
    except (KeyError, TypeError, ValueError) as exc:
        raise SystemExit("Backend OpenSSL assessment: invalid image configuration") from exc
    require("sha256:" + hashlib.sha256(config_bytes).hexdigest() == image_id
            and config.get("Entrypoint") == ["/entrypoint.sh"] and config.get("Cmd") is None
            and config.get("WorkingDir") == "/app"
            and not any(item.split("=", 1)[0].startswith(("OPENSSL_", "LD_", "CRYPTOGRAPHY_"))
                        for item in config.get("Env", [])), "image startup or crypto environment changed")
    validate_elf_inventory(sbom, review)


def validate_elf_inventory(sbom: dict[str, Any], review: dict[str, Any]) -> None:
    """Use the pinned Syft inventory and independently collected image hashes."""
    files = sbom.get("files")
    require(isinstance(files, list), "missing Syft file inventory")
    elfs: dict[str, Any] = {}
    for item in files:
        require(isinstance(item, dict), "invalid Syft file")
        executable = item.get("executable", {})
        if executable.get("format") != "elf":
            continue
        path = item.get("location", {}).get("path")
        hashes = [entry.get("value") for entry in item.get("digests", []) if entry.get("algorithm") == "sha256"]
        require(isinstance(path, str) and path.startswith("/") and path not in elfs
                and len(hashes) <= 1 and path in review["runtime"]["elf_sha256"],
                "ambiguous Syft ELF inventory")
        expected_hash = review["runtime"]["elf_sha256"][path]
        require(not hashes or hashes == [expected_hash], "Syft native library digest changed")
        elfs[path] = {"sha256": expected_hash, "dt_needed": executable.get("importedLibraries")}
    require(digest(elfs) == review["elf_files_sha256"], "complete ELF/library closure changed")
    require({path: entry["sha256"] for path, entry in elfs.items()} == review["runtime"]["elf_sha256"],
            "runtime and Syft ELF bytes disagree")


def project_apk(package: dict[str, Any], review: dict[str, Any]) -> dict[str, Any]:
    """Pinned Syft 1.42.3 → Grype 0.118.0 APK serialization boundary."""
    metadata, cpes = package.get("metadata"), package.get("cpes")
    require(package.get("metadataType") == "apk-db-entry" and isinstance(metadata, dict)
            and digest(metadata) == review["apk_metadata_sha256"].get(package.get("name")),
            "unreviewed Syft APK metadata")
    require(isinstance(cpes, list) and all(isinstance(item, dict) and set(item) == {"cpe", "source"}
            and item["source"] == "syft-generated" and isinstance(item["cpe"], str) for item in cpes),
            "unexpected Syft CPE serialization")
    # Grype deliberately drops APK file digests/owners and converts CPE
    # objects to strings. Full source metadata is independently hash-bound.
    projected = {field: package.get(field) for field in ("id", "name", "version", "type", "purl", "locations")}
    projected.update({"cpes": [item["cpe"] for item in cpes], "metadataType": "ApkMetadata",
                      "metadata": {"files": [{"path": item["path"]} for item in metadata["files"]],
                                   "Arch": metadata["architecture"]}})
    return projected


def validate_context(directory: Path, sbom_path: Path, product: str, image_id: str) -> tuple[dict[str, Any], dict[str, Any], list[dict[str, Any]]]:
    b = base()
    b["validate_expected_identity"](product, image_id)
    review = reviewed()
    root = HERE.parents[1]
    require(all(hashlib.sha256((root / name).read_bytes()).hexdigest() == expected
                for name, expected in review["deployment_sources"].items()), "reviewed startup/deployment source changed")
    runtime = load(directory / "backend-openssl-runtime.json")
    require(set(runtime) == {"image_id", "image_product", "proof"}
            and runtime["image_id"] == image_id and runtime["image_product"] == product
            and canonical(runtime["proof"]) == canonical(review["runtime"]), "runtime, application or configuration closure changed")
    sbom = load(sbom_path)
    validate_sbom(sbom, image_id, review)
    raw = load(directory / "backend-grype-unfiltered.json")
    b["validate_scanner_source"]("backend", raw.get("source"), product, image_id, "OpenSSL assessment")
    descriptor = raw.get("descriptor", {})
    require(descriptor.get("name") == "grype" and descriptor.get("version") == b["GRYPE_VERSION"]
            and descriptor.get("configuration", {}).get("fail-on-severity") == "high"
            and descriptor.get("db", {}).get("status", {}).get("valid") is True, "Grype identity or high gate changed")
    require(raw.get("ignoredMatches", []) == [] and isinstance(raw.get("matches"), list), "raw scan contains ignores")
    artifacts = sbom.get("artifacts", [])
    approved = []
    keys: set[str] = set()
    python = 0
    for match in raw["matches"]:
        require(isinstance(match, dict), "invalid raw match")
        vulnerability, artifact = match.get("vulnerability", {}), match.get("artifact", {})
        cve = vulnerability.get("id")
        if cve in CVES:
            key = f"{cve}/{artifact.get('name')}"
            require(key in review["matches"] and key not in keys and match_signature(match) == review["matches"][key],
                    "unreviewed or duplicate OpenSSL finding")
            candidates = [item for item in artifacts if item.get("id") == artifact.get("id")]
            require(len(candidates) == 1 and project_apk(candidates[0], review) == {
                field: artifact.get(field) for field in ("id", "name", "version", "type", "purl", "locations", "cpes", "metadataType", "metadata")},
                    "OpenSSL finding does not match exact Syft artifact")
            keys.add(key)
            approved.append(match)
        elif cve == b["HASHLIB_CVE"]:
            require(b["exact_hashlib_match"](match), "unreviewed Python finding")
            python += 1
        else:
            require(vulnerability.get("severity") in {"Unknown", "Negligible", "Low", "Medium"}, "unrelated finding remains blocking")
    require(keys == set(review["matches"]) and python == 1, "expected exactly eight OpenSSL and one Python findings")
    # Duplicate scanner records must never make a set-based comparison pass.
    require(len(set(map(canonical, raw["matches"]))) == len(raw["matches"]), "duplicate raw match")
    return review, raw, approved


def statements(product: str, image_id: str, review: dict[str, Any], raw: dict[str, Any]) -> list[dict[str, Any]]:
    purls = {match["artifact"]["name"]: match["artifact"]["purl"] for match in raw["matches"]
             if match["vulnerability"]["id"] in CVES}
    require(set(purls) == set(PACKAGES), "reviewed package product identities changed")
    products = [{"@id": product, "subcomponents": [{"@id": purls[name]} for name in PACKAGES]}]
    notes = canonical({"image_id": image_id, "review_sha256": digest(review), "raw_sha256": digest(raw)})
    return [{"vulnerability": {"name": cve}, "products": products, "status": "not_affected",
             "justification": "vulnerable_code_not_in_execute_path", "impact_statement": review["rationale"][cve],
             "status_notes": notes} for cve in CVES]


def validate_vex(path: Path, product: str, image_id: str, review: dict[str, Any], raw: dict[str, Any]) -> dict[str, Any]:
    value = load(path, 32768)
    require(set(value) == {"@context", "@id", "author", "timestamp", "version", "statements"}
            and value["@context"] == "https://openvex.dev/ns/v0.2.0"
            and value["@id"] == f"urn:dcompany:openvex:backend-openssl:{image_id.removeprefix('sha256:')}"
            and value["author"] == "D Company ERP" and value["version"] == 1
            and value["statements"] == statements(product, image_id, review, raw), "unbound OpenSSL VEX")
    return value


def verify_report(directory: Path, sbom_path: Path, product: str, image_id: str, report_path: Path,
                  *, final: bool = False) -> list[dict[str, Any]]:
    review, raw, approved = validate_context(directory, sbom_path, product, image_id)
    validate_vex(directory / "backend-openssl-reviewed.openvex.json", product, image_id, review, raw)
    report = load(report_path)
    def database_identity(value: dict[str, Any]) -> dict[str, Any]:
        database = dict(value.get("descriptor", {}).get("db", {}))
        if isinstance(database.get("status"), dict):
            database["status"] = {key: item for key, item in database["status"].items() if key != "path"}
        return database
    # Archive and daemon scans have different cache paths and source userInput;
    # retain exact image identity, DB build/checksum/providers and all matches.
    require(database_identity(report) == database_identity(raw), "rescan vulnerability DB changed")
    b = base()
    b["validate_scanner_source"]("backend", report.get("source"), product, image_id, "reviewed rescan")
    matches, ignored = report.get("matches"), report.get("ignoredMatches")
    require(isinstance(matches, list) and isinstance(ignored, list), "missing rescan records")
    expected = [{**item, "appliedIgnoreRules": STATUS} for item in approved]
    if final:
        b["validate_hashlib_vex"](directory / "backend-hashlib-fixed.openvex.json", product, image_id)
        python = next(item for item in raw["matches"] if b["exact_hashlib_match"](item))
        expected.append({**python, "appliedIgnoreRules": [{"namespace": "vex", "vex-status": "fixed"}]})
    require(Counter(map(canonical, ignored)) == Counter(map(canonical, expected)), "unexplained or missing ignored finding")
    reconstructed = matches + [{key: value for key, value in item.items() if key != "appliedIgnoreRules"} for item in ignored]
    require(Counter(map(canonical, reconstructed)) == Counter(map(canonical, raw["matches"])), "raw-to-filtered match inventory changed")
    if final:
        combined = load(directory / "backend-reviewed.openvex.json", 65536)
        python_vex = load(directory / "backend-hashlib-fixed.openvex.json", 8192)
        require(set(combined) == {"@context", "@id", "author", "timestamp", "version", "statements"}
                and combined.get("version") == 1 and combined.get("author") == "D Company ERP"
                and combined.get("statements") == statements(product, image_id, review, raw) + python_vex["statements"]
                and combined.get("@context") == "https://openvex.dev/ns/v0.2.0"
                and combined.get("@id") == f"urn:dcompany:openvex:backend-reviewed:{image_id.removeprefix('sha256:')}", "combined assessment changed")
    return expected[:8]


def write_new(path: Path, value: dict[str, Any]) -> None:
    require(not path.exists() and not path.is_symlink(), "assessment output already exists")
    with path.open("x", encoding="utf-8") as stream:
        stream.write(canonical(value) + "\n")
    path.chmod(0o444)


def collect_runtime(manifest_path: Path, product: str, image_id: str) -> None:
    # The readonly mounted reviewed path list is cross-checked against Syft's
    # COMPLETE ELF inventory by the host. No independent ELF parser is needed.
    manifest = json.loads(manifest_path.read_bytes())
    expected = manifest["runtime"]
    import ssl
    import cryptography
    from cryptography.hazmat.backends.openssl.backend import backend
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import ec
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM
    import psycopg
    from psycopg import pq

    app_files = {}
    for path in sorted(Path("/app").rglob("*")):
        require(not path.is_symlink(), "linked application source")
        if path.is_file():
            app_files[str(path.relative_to("/app"))] = hashlib.sha256(path.read_bytes()).hexdigest()
    paths = {Path("/entrypoint.sh"), Path("/app/requirements.lock")}
    for directory in (Path("/etc/ssl"), Path("/usr/lib/ssl")):
        if directory.is_dir():
            paths.update(path.resolve(strict=True) for path in directory.rglob("*.cnf") if path.is_file())
    configurations = {str(path): hashlib.sha256(path.read_bytes()).hexdigest() for path in sorted(paths)}
    library = ctypes.CDLL("/usr/lib/libcrypto.so.3")
    library.OSSL_PROVIDER_available.argtypes = [ctypes.c_void_p, ctypes.c_char_p]
    library.OSSL_PROVIDER_available.restype = ctypes.c_int
    nonce, cleartext, aad = b"0" * 12, b"ERP crypto path control", b"erp-assessment"
    aes = AESGCM(b"1" * 32)
    aes_ok = aes.decrypt(nonce, aes.encrypt(nonce, cleartext, aad), aad) == cleartext
    key = ec.generate_private_key(ec.SECP256R1())
    public = key.public_key().public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
    loaded = serialization.load_der_public_key(public)
    loaded.verify(key.sign(cleartext, ec.ECDSA(hashes.SHA256())), cleartext, ec.ECDSA(hashes.SHA256()))
    p256_ok = isinstance(loaded, ec.EllipticCurvePublicKey) and isinstance(loaded.curve, ec.SECP256R1) and loaded.public_bytes(
        serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo) == public
    elf_hashes = {}
    for name in expected["elf_sha256"]:
        path = Path(name)
        require(path.is_file() and not path.is_symlink(), "linked or absent native library")
        elf_hashes[name] = hashlib.sha256(path.read_bytes()).hexdigest()
    proof = {
        "schema": 1, "python": ".".join(map(str, sys.version_info[:3])), "system_openssl": ssl.OPENSSL_VERSION,
        "cryptography": cryptography.__version__, "cryptography_openssl": backend.openssl_version_text(),
        "psycopg": psycopg.__version__, "libpq_version": pq.version(), "elf_sha256": elf_hashes,
        "application_sha256": digest(app_files), "configuration_sha256": configurations,
        "crypto_environment": {key: value for key, value in os.environ.items() if key.startswith(("OPENSSL_", "LD_", "CRYPTOGRAPHY_"))},
        "system_provider_available": {name: library.OSSL_PROVIDER_available(None, name.encode()) for name in ("default", "legacy", "fips")},
        "api_tests": {"aesgcm_12_byte_nonce": aes_ok, "p256_canonical_der_and_signature": p256_ok},
    }
    print(canonical({"image_id": image_id, "image_product": product, "proof": proof}))


def main(arguments: list[str]) -> None:
    if len(arguments) == 5 and arguments[1] == "--has-findings":
        report = load(Path(arguments[2]))
        base()["validate_scanner_source"]("backend", report.get("source"), arguments[3], arguments[4], "OpenSSL assessment")
        raise SystemExit(0 if any(item.get("vulnerability", {}).get("id") in CVES for item in report.get("matches", [])) else 1)
    if len(arguments) == 5 and arguments[1] == "--collect":
        collect_runtime(Path(arguments[2]), arguments[3], arguments[4])
        return
    require(len(arguments) == 6 and arguments[1] in {"--prove", "--combine"}, "invalid assessment arguments")
    mode, directory_name, sbom_name, product, image_id = arguments[1:]
    directory, sbom = Path(directory_name), Path(sbom_name)
    review, raw, _ = validate_context(directory, sbom, product, image_id)
    document = {"@context": "https://openvex.dev/ns/v0.2.0",
                "@id": f"urn:dcompany:openvex:backend-openssl:{image_id.removeprefix('sha256:')}",
                "author": "D Company ERP", "timestamp": datetime.now(timezone.utc).isoformat(), "version": 1,
                "statements": statements(product, image_id, review, raw)}
    if mode == "--combine":
        verify_report(directory, sbom, product, image_id, directory / "backend-grype-openssl-filtered.json")
        base()["validate_hashlib_vex"](directory / "backend-hashlib-fixed.openvex.json", product, image_id)
        document["@id"] = f"urn:dcompany:openvex:backend-reviewed:{image_id.removeprefix('sha256:')}"
        document["statements"] += load(directory / "backend-hashlib-fixed.openvex.json", 8192)["statements"]
    name = "backend-reviewed.openvex.json" if mode == "--combine" else "backend-openssl-reviewed.openvex.json"
    write_new(directory / name, document)
    evidence = [("combined_vex", directory / name)] if mode == "--combine" else [
        ("runtime_proof", directory / "backend-openssl-runtime.json"),
        ("raw_grype", directory / "backend-grype-unfiltered.json"), ("vex", directory / name)]
    for key, path in evidence:
        print(f"backend_openssl_{key}_sha256={hashlib.sha256(path.read_bytes()).hexdigest()}")


if __name__ == "__main__":
    main(sys.argv)
