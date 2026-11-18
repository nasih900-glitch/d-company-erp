#!/usr/bin/env python3
"""Three closed service assessments, not an arbitrary package/CVE ignore API."""
from __future__ import annotations

import base64
from collections import Counter
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
from runpy import run_path
import shlex
import sys

HERE = Path(__file__).resolve().parent
COMMON = run_path(str(HERE / "backend-openssl-assessment.py"))
require, load, digest, canonical = (COMMON[name] for name in ("require", "load", "digest", "canonical"))
PROFILES = {
    "caddy": "caddy-static-go-openssl-reviewed.json",
    "frontend": "frontend-http-only-openssl-reviewed.json",
    "postgres": "postgres16-pgcrypto-openssl-reviewed.json",
}
PATH = re.compile(r"/[A-Za-z0-9_./+@=-]+")


def safe_observed_path(value):
    # Alpine includes a Unicode-labelled CA certificate; these are data, never
    # shell source. Reject separators/control characters and unsafe punctuation.
    return bool(value) and all(char.isalnum() or char in "/_+@=.-" for char in value)


def reviewed(service):
    require(service in PROFILES, "service has no reviewed execution-path assessment")
    value = load(HERE.parent / "security/vex" / PROFILES[service], 256 * 1024)
    require(set(value) == {"schema", "service", "runtime", "elf_files_sha256", "matches", "rationale",
                           "deployment_sources", "apk_metadata_sha256", "image_startup", "database_state"}
            and value["schema"] == 1 and value["service"] == service
            and set(value["rationale"]) == set(COMMON["CVES"])
            and set(value["matches"]) == {f"{cve}/{package}" for cve in COMMON["CVES"] for package in COMMON["PACKAGES"]},
            "service assessment contract changed")
    return value


def parse_runtime(path):
    require(not path.is_symlink() and path.resolve(strict=True) == path.absolute() and path.stat().st_size <= 1024 * 1024,
            "linked or oversized service runtime evidence")
    result = {name: {} for name in ("elf_sha256", "resolves", "configuration_sha256", "links", "directories", "entrypoint_sha256", "crypto_environment")}
    fields = {"ELF": "elf_sha256", "CONFIGFILE": "configuration_sha256", "ENTRYPOINT": "entrypoint_sha256"}
    saw_environment = False
    for line in path.read_text(encoding="utf-8").splitlines():
        if line == "CRYPTOENV":
            require(not saw_environment, "duplicate runtime environment marker")
            saw_environment = True
            continue
        if saw_environment:
            key, separator, value = line.partition("=")
            require(separator and key.startswith(("OPENSSL_", "LD_", "CRYPTOGRAPHY_"))
                    and key not in result["crypto_environment"], "invalid crypto environment evidence")
            result["crypto_environment"][key] = value
            continue
        kind, separator, body = line.partition("\t")
        require(separator, "malformed runtime closure record")
        if kind in fields:
            match = re.fullmatch(r"([0-9a-f]{64})  (/[^\t\r\n]+)", body)
            require(match is not None, "malformed runtime hash record")
            value, name = match.groups()
            require(safe_observed_path(name), "invalid runtime hash path")
            target = result[fields[kind]]
        else:
            name, separator, value = body.partition("\t")
            require(separator and name.startswith("/") and safe_observed_path(name), "malformed runtime path record")
            require(kind in {"RESOLVE", "LINK", "DIR"}, "unknown runtime closure record")
            target = result[{"RESOLVE": "resolves", "LINK": "links", "DIR": "directories"}[kind]]
            if kind == "DIR":
                require(value in {"present", "absent"}, "invalid directory presence")
            else:
                require(safe_observed_path(value), "invalid resolved/link path")
        require(name not in target, "duplicate runtime closure record")
        target[name] = value
    require(saw_environment, "incomplete runtime environment evidence")
    return result


def prepare_runtime(service, output):
    review = reviewed(service)
    lines = ["set -eu", "observed_paths=$(mktemp)", "trap 'rm -f \"$observed_paths\"' EXIT HUP INT TERM"]
    for path in sorted(review["runtime"]["elf_sha256"]):
        require(PATH.fullmatch(path) is not None, "unsafe reviewed ELF path")
        quoted = shlex.quote(path)
        lines += [f"printf 'ELF\\t'; sha256sum -- {quoted}", f"printf 'RESOLVE\\t%s\\t' {quoted}; readlink -f -- {quoted}"]
    for path in review["runtime"]["directories"]:
        require(PATH.fullmatch(path) is not None, "unsafe reviewed configuration root")
        quoted = shlex.quote(path)
        lines.append(f"if [ -d {quoted} ]; then printf 'DIR\\t%s\\tpresent\\n' {quoted}; "
                     f"find {quoted} -type f > \"$observed_paths\"; "
                     "while IFS= read -r p; do printf 'CONFIGFILE\\t'; sha256sum -- \"$p\"; done < \"$observed_paths\"; "
                     f"find {quoted} -type l > \"$observed_paths\"; "
                     "while IFS= read -r p; do printf 'LINK\\t%s\\t' \"$p\"; readlink \"$p\"; done < \"$observed_paths\"; "
                     f"else printf 'DIR\\t%s\\tabsent\\n' {quoted}; fi")
    for path in ("/docker-entrypoint.sh", "/usr/local/bin/docker-entrypoint.sh"):
        quoted = shlex.quote(path)
        lines.append(f"if [ -f {quoted} ]; then printf 'ENTRYPOINT\\t'; sha256sum -- {quoted}; fi")
    lines.append("env > \"$observed_paths\"; printf 'CRYPTOENV\\n'; sed -n '/^OPENSSL_/p;/^LD_/p;/^CRYPTOGRAPHY_/p' \"$observed_paths\"")
    require(not output.exists() and not output.is_symlink(), "runtime collector output already exists")
    output.write_text("\n".join(lines) + "\n")
    output.chmod(0o444)


def validate_context(service, directory, sbom_path, product, image_id):
    b = COMMON["base"]()
    b["validate_expected_identity"](product, image_id)
    review = reviewed(service)
    require(all(hashlib.sha256((HERE.parents[1] / name).read_bytes()).hexdigest() == expected
                for name, expected in review["deployment_sources"].items()), "reviewed service deployment source changed")
    runtime = load(directory / f"{service}-openssl-runtime.json")
    require(set(runtime) == {"image_id", "image_product", "proof"} and runtime["image_id"] == image_id
            and runtime["image_product"] == product and canonical(runtime["proof"]) == canonical(review["runtime"]),
            "service runtime/configuration/startup closure changed")
    sbom = load(sbom_path)
    metadata = sbom.get("source", {}).get("metadata", {})
    require(sbom.get("schema") == b["SYFT_SCHEMA"] and sbom.get("descriptor", {}).get("name") == "syft"
            and sbom.get("descriptor", {}).get("version") == b["SYFT_VERSION"]
            and sbom.get("source", {}).get("type") == "image" and metadata.get("imageID") == image_id,
            "service Syft identity changed")
    config_bytes = base64.b64decode(metadata["config"], validate=True)
    config = json.loads(config_bytes)
    require("sha256:" + hashlib.sha256(config_bytes).hexdigest() == image_id
            and config.get("os") == "linux" and config.get("architecture") == "amd64"
            and {key: config["config"].get(key) for key in review["image_startup"]} == review["image_startup"],
            "service image configuration changed")
    COMMON["validate_elf_inventory"](sbom, review)
    raw = load(directory / f"{service}-grype-unfiltered.json")
    b["validate_scanner_source"](service, raw.get("source"), product, image_id, "service execution-path assessment")
    descriptor = raw.get("descriptor", {})
    require(descriptor.get("name") == "grype" and descriptor.get("version") == b["GRYPE_VERSION"]
            and descriptor.get("configuration", {}).get("fail-on-severity") == "high"
            and descriptor.get("db", {}).get("status", {}).get("valid") is True
            and raw.get("ignoredMatches", []) == [] and isinstance(raw.get("matches"), list), "service raw scan identity changed")
    approved, seen = [], set()
    for match in raw["matches"]:
        vulnerability, artifact = match.get("vulnerability", {}), match.get("artifact", {})
        if vulnerability.get("id") in COMMON["CVES"]:
            key = f"{vulnerability['id']}/{artifact.get('name')}"
            require(key in review["matches"] and key not in seen and COMMON["match_signature"](match) == review["matches"][key],
                    "unreviewed or duplicate service OpenSSL finding")
            candidates = [item for item in sbom["artifacts"] if item.get("id") == artifact.get("id")]
            require(len(candidates) == 1 and COMMON["project_apk"](candidates[0], review) == {
                field: artifact.get(field) for field in ("id", "name", "version", "type", "purl", "locations", "cpes", "metadataType", "metadata")},
                    "service finding does not match exact Syft artifact")
            approved.append(match); seen.add(key)
        else:
            require(vulnerability.get("severity") in {"Unknown", "Negligible", "Low", "Medium"}, "unreviewed service High finding remains blocking")
    require(seen == set(review["matches"]) and len(set(map(canonical, raw["matches"]))) == len(raw["matches"]),
            "service raw scan must have exactly eight reviewed findings without duplicates")
    return review, raw, approved


def document(service, review, raw, product, image_id):
    value = {"@context": "https://openvex.dev/ns/v0.2.0", "@id": f"urn:dcompany:openvex:{service}-openssl:{image_id[7:]}",
             "author": "D Company ERP", "timestamp": datetime.now(timezone.utc).isoformat(), "version": 1,
             "statements": COMMON["statements"](product, image_id, review, raw)}
    return value


def verify_report(service, directory, sbom_path, product, image_id, report_path):
    review, raw, approved = validate_context(service, directory, sbom_path, product, image_id)
    vex = load(directory / f"{service}-openssl-reviewed.openvex.json", 32768)
    expected_vex = document(service, review, raw, product, image_id)
    expected_vex["timestamp"] = vex.get("timestamp")
    require(vex == expected_vex, "unbound service OpenSSL VEX")
    report = load(report_path)
    descriptor = report.get("descriptor", {})
    require(descriptor.get("name") == "grype" and descriptor.get("version") == "0.118.0"
            and descriptor.get("configuration", {}).get("fail-on-severity") == "high"
            and descriptor.get("db", {}).get("status", {}).get("valid") is True, "service rescan scanner/high gate changed")
    COMMON["base"]()["validate_scanner_source"](service, report.get("source"), product, image_id, "service rescan")
    def db(value):
        result = dict(value.get("descriptor", {}).get("db", {}))
        result["status"] = {key: item for key, item in result.get("status", {}).items() if key != "path"}
        return result
    require(db(report) == db(raw), "service vulnerability DB changed")
    ignored, matches = report.get("ignoredMatches"), report.get("matches")
    expected = [{**match, "appliedIgnoreRules": COMMON["STATUS"]} for match in approved]
    require(isinstance(ignored, list) and isinstance(matches, list)
            and Counter(map(canonical, ignored)) == Counter(map(canonical, expected)), "unexplained service ignored finding")
    restored = matches + [{key: item for key, item in match.items() if key != "appliedIgnoreRules"} for match in ignored]
    require(Counter(map(canonical, restored)) == Counter(map(canonical, raw["matches"])), "service raw-to-filtered inventory changed")
    print(f"{service}_openssl_grype_not_affected_ignored_matches=8")
    return expected


def verify_postgres_database_state(observed):
    if canonical(observed) != canonical(reviewed("postgres")["database_state"]):
        raise ValueError("PostgreSQL crypto/preload/extension state differs from the reviewed execution paths")


def main(args):
    mode = args[1] if len(args) > 1 else ""
    if mode == "--prepare-runtime" and len(args) == 4:
        prepare_runtime(args[2], Path(args[3])); return
    if mode == "--bind-runtime" and len(args) == 7:
        service, source, product, image_id, output = args[2:]
        COMMON["base"]()["validate_expected_identity"](product, image_id)
        proof = parse_runtime(Path(source))
        require(canonical(proof) == canonical(reviewed(service)["runtime"]), "actual service runtime closure changed")
        COMMON["write_new"](Path(output), {"image_id": image_id, "image_product": product, "proof": proof}); return
    require(mode in {"--has-findings", "--prove", "--verify"} and len(args) == (6 if mode == "--has-findings" else 7 if mode == "--prove" else 8),
            "invalid service assessment arguments")
    service = args[2]
    reviewed(service)
    if mode == "--has-findings":
        raw, product, image_id = args[3:]
        report = load(Path(raw))
        COMMON["base"]()["validate_scanner_source"](service, report.get("source"), product, image_id, "service assessment")
        raise SystemExit(0 if any(m.get("vulnerability", {}).get("id") in COMMON["CVES"] for m in report.get("matches", [])) else 1)
    directory, sbom_path, product, image_id = Path(args[3]), Path(args[4]), args[5], args[6]
    if mode == "--verify":
        verify_report(service, directory, sbom_path, product, image_id, Path(args[7])); return
    review, raw, _ = validate_context(service, directory, sbom_path, product, image_id)
    output = directory / f"{service}-openssl-reviewed.openvex.json"
    COMMON["write_new"](output, document(service, review, raw, product, image_id))
    for key, path in (("runtime_proof", directory / f"{service}-openssl-runtime.json"),
                      ("raw_grype", directory / f"{service}-grype-unfiltered.json"), ("vex", output)):
        print(f"{service}_openssl_{key}_sha256={hashlib.sha256(path.read_bytes()).hexdigest()}")


if __name__ == "__main__":
    main(sys.argv)
