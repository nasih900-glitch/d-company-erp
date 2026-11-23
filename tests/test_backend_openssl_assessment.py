from __future__ import annotations

import base64
from collections import Counter
import copy
import hashlib
import json
from pathlib import Path
from runpy import run_path

import pytest

from openssl_historical_fixtures import historical_backend_hashes, historical_source_root

ROOT = Path(__file__).resolve().parents[1]
HELPER = ROOT / "infra/scripts/backend-openssl-assessment.py"
PRODUCT = "erp-backend:test"


def write(path, value):
    path.write_text(json.dumps(value, sort_keys=True))


@pytest.fixture
def evidence(tmp_path, monkeypatch):
    h = run_path(str(HELPER))
    globals_ = h["validate_context"].__globals__
    review = h["reviewed"]()
    historical = historical_source_root(tmp_path, review)
    validator = h["base"]()
    monkeypatch.setitem(globals_, "base", lambda: validator)
    h["base"] = globals_["base"]
    monkeypatch.setitem(globals_, "HERE", historical / "infra/scripts")
    raw_matches = json.loads((ROOT / "tests/fixtures/backend_openssl_reviewed_matches.json").read_text())["matches"]
    config = {"architecture": "amd64", "os": "linux", "config": {
        "Entrypoint": ["/entrypoint.sh"], "WorkingDir": "/app", "Env": ["PATH=/usr/local/bin:/usr/bin:/bin"]}}
    config_bytes = json.dumps(config).encode()
    image_id = "sha256:" + hashlib.sha256(config_bytes).hexdigest()
    elfs = {"/usr/lib/libcrypto.so.3": {"sha256": "a" * 64, "dt_needed": []},
            "/private/libssl.so.3": {"sha256": "b" * 64, "dt_needed": ["libcrypto.so.3"]}}
    review["runtime"]["elf_sha256"] = {p: v["sha256"] for p, v in elfs.items()}
    review["elf_files_sha256"] = h["digest"](elfs)
    monkeypatch.setitem(globals_, "reviewed", lambda: copy.deepcopy(review))
    raw = {"descriptor": {"name": "grype", "version": "0.118.0", "configuration": {"fail-on-severity": "high"},
                          "db": {"status": {"valid": True, "built": "2026-11-18T00:00:00Z", "schemaVersion": "6.1.3"}}},
           "source": {"type": "image", "target": {"imageID": image_id, "tags": [PRODUCT]}},
           "matches": raw_matches + [{"vulnerability": {"id": "UNRELATED-MEDIUM", "severity": "Medium"}}], "ignoredMatches": []}
    artifacts = json.loads((ROOT / "tests/fixtures/backend_openssl_syft_artifacts.json").read_text())["artifacts"]
    sbom = {"schema": h["base"]()["SYFT_SCHEMA"], "descriptor": {"name": "syft", "version": "1.42.3"},
            "source": {"type": "image", "metadata": {"imageID": image_id, "config": base64.b64encode(config_bytes).decode()}},
            "artifacts": artifacts, "files": [
                {"location": {"path": p}, "digests": [{"algorithm": "sha256", "value": v["sha256"]}],
                 "executable": {"format": "elf", "importedLibraries": v["dt_needed"]}} for p, v in elfs.items()]}
    runtime = {"image_id": image_id, "image_product": PRODUCT, "proof": copy.deepcopy(review["runtime"])}
    write(tmp_path / "backend-grype-unfiltered.json", raw)
    write(tmp_path / "backend.syft.json", sbom)
    write(tmp_path / "backend-openssl-runtime.json", runtime)
    return h, tmp_path, image_id, raw, sbom, runtime, review


def prepare_filtered(evidence, final=False):
    h, directory, image_id, raw, sbom, runtime, review = evidence
    h["main"]([str(HELPER), "--prove", str(directory), str(directory / "backend.syft.json"), PRODUCT, image_id])
    approved = [m for m in raw["matches"] if m["vulnerability"]["id"] in h["CVES"]]
    report = copy.deepcopy(raw)
    report["matches"] = [m for m in raw["matches"] if m not in approved]
    report["ignoredMatches"] = [{**m, "appliedIgnoreRules": copy.deepcopy(h["STATUS"])} for m in approved]
    path = directory / "backend-grype-openssl-filtered.json"
    write(path, report)
    if final:
        b = h["base"]()
        write(directory / "backend-hashlib-runtime.json", b["HASHLIB_PROOF"])
        python_vex = {"@context": "https://openvex.dev/ns/v0.2.0",
                      "@id": f"urn:dcompany:openvex:cve-2026-119170:{image_id[7:]}", "version": 1,
                      "statements": [b["hashlib_statement"](PRODUCT, image_id, b["HASHLIB_PROOF"])]}
        write(directory / "backend-hashlib-fixed.openvex.json", python_vex)
        h["main"]([str(HELPER), "--combine", str(directory), str(directory / "backend.syft.json"), PRODUCT, image_id])
        python = next(m for m in report["matches"] if b["exact_hashlib_match"](m))
        report["matches"].remove(python)
        report["ignoredMatches"].append({**python, "appliedIgnoreRules": [{"namespace": "vex", "vex-status": "fixed"}]})
        path = directory / "backend-filtered.json"
        write(path, report)
    return path, report


def test_reviewed_contract_covers_complete_inventory_and_independent_scanner_records():
    h = run_path(str(HELPER))
    review = h["reviewed"]()
    assert len(review["runtime"]["elf_sha256"]) == 181
    assert review["runtime"]["cryptography_openssl"] == "OpenSSL 4.0.1 9 Jun 2026"
    assert review["runtime"]["system_openssl"] == "OpenSSL 3.5.9 29 Sep 2026"
    assert review["runtime"]["crypto_environment"] == {}
    matches = json.loads((ROOT / "tests/fixtures/backend_openssl_reviewed_matches.json").read_text())["matches"]
    assert {m["vulnerability"]["id"] + "/" + m["artifact"]["name"]: h["match_signature"](m)
            for m in matches if m["vulnerability"]["id"] in h["CVES"]} == review["matches"]
    app = historical_backend_hashes()
    assert len(app) == 686
    assert h["digest"](app) == review["runtime"]["application_sha256"]


def test_eight_openssl_assessments_preserve_python_high_and_medium(evidence):
    h, directory, image_id, *_ = evidence
    path, report = prepare_filtered(evidence)
    approved = h["verify_report"](directory, directory / "backend.syft.json", PRODUCT, image_id, path)
    assert len(approved) == 8
    assert Counter(m["vulnerability"]["severity"] for m in report["matches"]) == {"High": 1, "Medium": 1}
    # The existing Python gate still rejects an unrelated High after removing
    # only the independently verified eight OpenSSL records.
    b = h["base"]()
    b["validate_fixed_report"]("backend", path, PRODUCT, image_id, hashlib_exception="raw", openssl_ignored=approved)


@pytest.mark.parametrize("defect", ["extra-high", "missing-match", "duplicate-match", "cve", "package", "version", "location",
                                    "matcher", "raw-ignore", "image", "app-tree", "library", "config", "provider", "crypto-env",
                                    "api", "syft-library", "syft-extra-elf", "syft-missing-elf", "syft-dependency", "syft-package", "syft-cpe", "syft-metadata-type", "syft-apk-digest", "syft-apk-arch", "syft-file-path"])
def test_no_vex_can_be_produced_after_unreviewed_evidence_drift(evidence, defect):
    h, directory, image_id, raw, sbom, runtime, _ = evidence
    match = next(m for m in raw["matches"] if m["vulnerability"]["id"] in h["CVES"])
    if defect == "extra-high": raw["matches"].append({"vulnerability": {"id": "UNREVIEWED", "severity": "High"}})
    elif defect == "missing-match": raw["matches"].remove(match)
    elif defect == "duplicate-match": raw["matches"].append(copy.deepcopy(match))
    elif defect == "cve": match["vulnerability"]["id"] = "CVE-2099-12345"
    elif defect == "package": match["artifact"]["name"] = "openssl"
    elif defect == "version": match["artifact"]["version"] = "3.5.10-r0"
    elif defect == "location": match["artifact"]["locations"][0]["path"] = "/unreviewed"
    elif defect == "matcher": match["matchDetails"][0]["matcher"] = "unreviewed"
    elif defect == "raw-ignore": raw["ignoredMatches"] = [copy.deepcopy(match)]
    elif defect == "image": runtime["image_id"] = "sha256:" + "0" * 64
    elif defect == "app-tree": runtime["proof"]["application_sha256"] = "0" * 64
    elif defect == "library": runtime["proof"]["elf_sha256"]["/usr/lib/libcrypto.so.3"] = "0" * 64
    elif defect == "config": runtime["proof"]["configuration_sha256"]["/entrypoint.sh"] = "0" * 64
    elif defect == "provider": runtime["proof"]["system_provider_available"]["legacy"] = 1
    elif defect == "crypto-env": runtime["proof"]["crypto_environment"]["OPENSSL_CONF"] = "/unreviewed"
    elif defect == "api": runtime["proof"]["api_tests"]["aesgcm_12_byte_nonce"] = False
    elif defect == "syft-library": sbom["files"][0]["digests"][0]["value"] = "0" * 64
    elif defect == "syft-extra-elf": sbom["files"].append({**sbom["files"][0], "location": {"path": "/unreviewed.so"}})
    elif defect == "syft-missing-elf": sbom["files"].pop()
    elif defect == "syft-dependency": sbom["files"][0]["executable"]["importedLibraries"] = ["unreviewed.so"]
    elif defect == "syft-package": sbom["artifacts"][0]["id"] = "unreviewed"
    elif defect == "syft-cpe": sbom["artifacts"][0]["cpes"][0]["cpe"] = "unreviewed"
    elif defect == "syft-metadata-type": sbom["artifacts"][0]["metadataType"] = "ApkMetadata"
    elif defect == "syft-apk-digest": sbom["artifacts"][0]["metadata"]["pullChecksum"] = "unreviewed"
    elif defect == "syft-apk-arch": sbom["artifacts"][0]["metadata"]["architecture"] = "aarch64"
    elif defect == "syft-file-path": sbom["artifacts"][0]["metadata"]["files"][0]["path"] = "/unreviewed"
    write(directory / "backend-grype-unfiltered.json", raw)
    write(directory / "backend.syft.json", sbom)
    write(directory / "backend-openssl-runtime.json", runtime)
    with pytest.raises(SystemExit):
        h["main"]([str(HELPER), "--prove", str(directory), str(directory / "backend.syft.json"), PRODUCT, image_id])
    assert not (directory / "backend-openssl-reviewed.openvex.json").exists()


@pytest.mark.parametrize("defect", [None, "missing-medium", "added-match", "missing-ignore", "extra-ignore", "changed-status", "changed-rule", "changed-db", "changed-vex", "missing-python-proof"])
def test_final_scan_requires_exact_nine_exclusions_and_full_raw_equivalence(evidence, defect):
    h, directory, image_id, *_ = evidence
    path, report = prepare_filtered(evidence, final=True)
    if defect == "missing-medium": report["matches"] = []
    elif defect == "added-match": report["matches"].append({"vulnerability": {"id": "NEW", "severity": "Low"}})
    elif defect == "missing-ignore": report["ignoredMatches"].pop()
    elif defect == "extra-ignore": report["ignoredMatches"].append(copy.deepcopy(report["ignoredMatches"][0]))
    elif defect == "changed-status": report["ignoredMatches"][0]["appliedIgnoreRules"][0]["vex-status"] = "fixed"
    elif defect == "changed-rule": report["ignoredMatches"][0]["appliedIgnoreRules"].append({"namespace": "global-ignore"})
    elif defect == "changed-db": report["descriptor"]["db"]["status"]["built"] = "future"
    elif defect == "changed-vex":
        combined_path = directory / "backend-reviewed.openvex.json"
        combined = json.loads(combined_path.read_text()); combined["statements"].pop(); combined_path.chmod(0o600); write(combined_path, combined)
    elif defect == "missing-python-proof":
        proof_path = directory / "backend-hashlib-runtime.json"
        proof = json.loads(proof_path.read_text()); proof["unpatched_control"] = False; write(proof_path, proof)
    write(path, report)
    if defect is None:
        approved = h["verify_report"](directory, directory / "backend.syft.json", PRODUCT, image_id, path, final=True)
        h["base"]()["validate_fixed_report"]("backend", path, PRODUCT, image_id, hashlib_exception="fixed", openssl_ignored=approved)
        assert len(report["ignoredMatches"]) == 9
    else:
        with pytest.raises(SystemExit):
            h["verify_report"](directory, directory / "backend.syft.json", PRODUCT, image_id, path, final=True)


@pytest.mark.parametrize("defect", ["duplicate-key", "linked-proof", "output-exists", "wrong-service"])
def test_malformed_or_cross_service_assessment_cannot_be_accepted(evidence, defect):
    h, directory, image_id, *_ = evidence
    if defect == "duplicate-key":
        p = directory / "backend-openssl-runtime.json"; p.write_text('{"image_id":"one","image_id":"two"}')
    elif defect == "linked-proof":
        p = directory / "backend-openssl-runtime.json"; p.rename(directory / "proof-target.json"); p.symlink_to("proof-target.json")
    elif defect == "output-exists":
        (directory / "backend-openssl-reviewed.openvex.json").write_text("unreviewed existing output")
    product = "erp-frontend:test" if defect == "wrong-service" else PRODUCT
    with pytest.raises(SystemExit):
        h["main"]([str(HELPER), "--prove", str(directory), str(directory / "backend.syft.json"), product, image_id])


def test_historical_r0_backend_proof_cannot_admit_current_r1_source(evidence, monkeypatch):
    h, directory, image_id, *_ = evidence
    monkeypatch.setitem(h["validate_context"].__globals__, "HERE", ROOT / "infra/scripts")
    with pytest.raises(SystemExit, match="startup/deployment source changed"):
        h["main"]([str(HELPER), "--prove", str(directory), str(directory / "backend.syft.json"), PRODUCT, image_id])
    assert not (directory / "backend-openssl-reviewed.openvex.json").exists()
