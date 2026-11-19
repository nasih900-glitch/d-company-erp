from __future__ import annotations

import base64
import copy
import hashlib
import json
from pathlib import Path
from runpy import run_path

import pytest

ROOT = Path(__file__).resolve().parents[1]
HELPER = ROOT / "infra/scripts/openssl-vendor-fixed.py"


def write(path, value):
    if path.exists():
        path.chmod(0o600)
    path.write_text(json.dumps(value, sort_keys=True))


def make_evidence(service, tmp_path):
    h = run_path(str(HELPER))
    review = h["reviewed"]()
    fixture = json.loads((ROOT / "tests/fixtures/openssl_alpine324_r1_vendor_matches.json").read_text())
    config = json.dumps({"os": "linux", "architecture": "amd64", "config": {"Env": []}}).encode()
    image = "sha256:" + hashlib.sha256(config).hexdigest()
    product = "erp-" + service + ":vendor-test"
    if service == "backend":
        for artifact in fixture["artifacts"]:
            artifact["purl"] = artifact["purl"].replace("alpine-3.24.1", "alpine-3.24.3")
        for match in fixture["matches"]:
            match["artifact"]["purl"] = match["artifact"]["purl"].replace("alpine-3.24.1", "alpine-3.24.3")
    sbom = {"schema": h["COMMON"]["base"]()["SYFT_SCHEMA"], "descriptor": {"name": "syft", "version": "1.42.3"},
            "source": {"type": "image", "metadata": {"imageID": image, "config": base64.b64encode(config).decode()}},
            "artifacts": fixture["artifacts"], "files": [{"location": {"path": path}, "executable": {"format": "elf"},
                "digests": digests} for path, digests in review["runtime_digests"].items()]}
    raw = {"descriptor": {"name": "grype", "version": "0.118.0", "configuration": {"fail-on-severity": "high"},
                          "db": {"status": {"valid": True, "built": "2026-11-18T18:19:44Z", "schemaVersion": "6.1.10"}}},
           "source": {"type": "image", "target": {"imageID": image, "tags": [product]}},
           "matches": fixture["matches"] + [{"vulnerability": {"id": "UNRELATED-MEDIUM", "severity": "Medium"}}],
           "ignoredMatches": []}
    runtime = h["expected_runtime"](service, review)
    write(tmp_path / "runtime.raw.json", runtime)
    h["bind_runtime"](service, tmp_path / "runtime.raw.json", tmp_path / f"{service}-openssl-vendor-runtime.json", product, image)
    write(tmp_path / f"{service}.syft.json", sbom)
    write(tmp_path / f"{service}-grype-vendor-unfiltered.json", raw)
    return h, service, tmp_path, product, image, sbom, raw, runtime


@pytest.fixture(params=("backend", "frontend", "postgres"))
def evidence(request, tmp_path):
    return make_evidence(request.param, tmp_path)


@pytest.fixture
def backend_evidence(tmp_path):
    return make_evidence("backend", tmp_path)


@pytest.mark.parametrize("defect", [None, "unknown-high", "extra-ignore", "missing-match", "duplicate", "package", "r0",
                                    "location", "fix-state", "purl", "syft-metadata", "syft-payload", "syft-config",
                                    "runtime-archive", "runtime-library", "runtime-signature", "runtime-source", "image"])
def test_vendor_fixed_proof_requires_exact_signed_image_package_and_eight_matches(evidence, defect):
    h, service, directory, product, image, sbom, raw, runtime = evidence
    bound = json.loads((directory / f"{service}-openssl-vendor-runtime.json").read_text())
    first = raw["matches"][0]
    if defect == "unknown-high": raw["matches"].append({"vulnerability": {"id": "UNREVIEWED", "severity": "High"}})
    elif defect == "extra-ignore": raw["ignoredMatches"] = [copy.deepcopy(first)]
    elif defect == "missing-match": raw["matches"].pop(0)
    elif defect == "duplicate": raw["matches"].append(copy.deepcopy(first))
    elif defect == "package": first["artifact"]["name"] = "openssl"
    elif defect == "r0": first["artifact"]["version"] = "3.5.9-r0"
    elif defect == "location": first["artifact"]["locations"][0]["path"] = "/unreviewed"
    elif defect == "fix-state": first["vulnerability"]["fix"]["state"] = "fixed"
    elif defect == "purl": first["artifact"]["purl"] = first["artifact"]["purl"].replace("x86_64", "aarch64")
    elif defect == "syft-metadata": sbom["artifacts"][0]["metadata"]["architecture"] = "aarch64"
    elif defect == "syft-payload": sbom["files"][0]["digests"][1]["value"] = "0" * 64
    elif defect == "syft-config": sbom["source"]["metadata"]["config"] = base64.b64encode(b'{}').decode()
    elif defect == "runtime-archive": bound["proof"]["archives_sha256"]["libcrypto3"] = "0" * 64
    elif defect == "runtime-library": bound["proof"]["runtime_sha256"]["/usr/lib/libssl.so.3"] = "0" * 64
    elif defect == "runtime-signature": bound["proof"]["signature_verified"] = False
    elif defect == "runtime-source": bound["proof"]["port_commit"] = "0" * 40
    elif defect == "image": bound["image_id"] = "sha256:" + "0" * 64
    write(directory / f"{service}.syft.json", sbom)
    write(directory / f"{service}-grype-vendor-unfiltered.json", raw)
    write(directory / f"{service}-openssl-vendor-runtime.json", bound)
    args = [str(HELPER), "--prove", service, str(directory), str(directory / f"{service}.syft.json"), product, image]
    output = directory / f"{service}-openssl-vendor-fixed.openvex.json"
    if defect is None:
        h["main"](args)
        statements = json.loads(output.read_text())["statements"]
        assert len(statements) == 4
        assert all(statement["status"] == "fixed" and "justification" not in statement for statement in statements)
    else:
        with pytest.raises((SystemExit, KeyError)):
            h["main"](args)
        assert not output.exists()


@pytest.mark.parametrize("defect", [None, "drop-medium", "extra-ignore", "missing-ignore", "not-affected", "extra-rule", "changed-db", "changed-vex"])
def test_vendor_filtered_scan_preserves_raw_records_and_exact_fixed_rules(evidence, defect):
    h, service, directory, product, image, sbom, raw, runtime = evidence
    h["main"]([str(HELPER), "--prove", service, str(directory), str(directory / f"{service}.syft.json"), product, image])
    filtered = copy.deepcopy(raw)
    approved = raw["matches"][:-1]
    filtered["matches"] = [raw["matches"][-1]]
    filtered["ignoredMatches"] = [{**match, "appliedIgnoreRules": copy.deepcopy(h["STATUS"])} for match in approved]
    if defect == "drop-medium": filtered["matches"] = []
    elif defect == "extra-ignore": filtered["ignoredMatches"].append(copy.deepcopy(filtered["ignoredMatches"][0]))
    elif defect == "missing-ignore": filtered["ignoredMatches"].pop()
    elif defect == "not-affected": filtered["ignoredMatches"][0]["appliedIgnoreRules"][0]["vex-status"] = "not_affected"
    elif defect == "extra-rule": filtered["ignoredMatches"][0]["appliedIgnoreRules"].append({"namespace": "generic"})
    elif defect == "changed-db": filtered["descriptor"]["db"]["status"]["built"] = "future"
    elif defect == "changed-vex":
        path = directory / f"{service}-openssl-vendor-fixed.openvex.json"
        value = json.loads(path.read_text()); value["statements"][0]["status"] = "not_affected"; write(path, value)
    output = directory / "filtered.json"; write(output, filtered)
    args = (service, directory, directory / f"{service}.syft.json", product, image, output)
    if defect is None:
        approved = h["verify_report"](*args)
        h["COMMON"]["base"]()["validate_fixed_report"](service, output, product, image, openssl_ignored=approved)
    else:
        with pytest.raises(SystemExit): h["verify_report"](*args)


def test_caddy_cannot_enter_fixed_vendor_profile():
    h = run_path(str(HELPER))
    with pytest.raises(SystemExit, match="no vendor-fixed profile"):
        h["expected_runtime"]("caddy", h["reviewed"]())


@pytest.mark.parametrize("defect", [None, "missing-proof", "malformed-proof", "cross-image-python", "extra-statement", "missing-python-ignore", "extra-ignore"])
def test_backend_final_requires_independent_genuine_hashlib_proof_and_exact_eight_plus_one(backend_evidence, defect):
    h, service, directory, product, image, sbom, raw, runtime = backend_evidence
    fixture = json.loads((ROOT / "tests/fixtures/openssl_alpine324_r1_vendor_matches.json").read_text())
    python = fixture["backend_hashlib_match"]
    raw["matches"].insert(0, python)
    write(directory / "backend-grype-vendor-unfiltered.json", raw)
    h["main"]([str(HELPER), "--prove", service, str(directory), str(directory / "backend.syft.json"), product, image])
    intermediate = copy.deepcopy(raw)
    intermediate["matches"] = [python, raw["matches"][-1]]
    intermediate["ignoredMatches"] = [{**match, "appliedIgnoreRules": copy.deepcopy(h["STATUS"])} for match in raw["matches"][1:-1]]
    raw_path = directory / "backend-grype-unfiltered.json"; write(raw_path, intermediate)
    proof_path = directory / "backend-hashlib-runtime.json"
    write(proof_path, fixture["backend_hashlib_proof"])
    if defect == "missing-proof": proof_path.unlink()
    elif defect == "malformed-proof":
        proof = fixture["backend_hashlib_proof"].copy(); proof["module_sha256"] = "0" * 64; write(proof_path, proof)
    b = h["COMMON"]["base"]()
    args = ["verifier", "--prove-fixed-hashlib", str(raw_path), product, image, str(proof_path),
            str(directory / "backend-hashlib-fixed.openvex.json"), "--openssl-vendor-fixed=" + str(directory / "backend.syft.json")]
    if defect in {"missing-proof", "malformed-proof"}:
        with pytest.raises((SystemExit, FileNotFoundError)): b["prove_fixed_hashlib"](args)
        assert not (directory / "backend-hashlib-fixed.openvex.json").exists()
        return
    b["prove_fixed_hashlib"](args)
    if defect == "cross-image-python":
        path = directory / "backend-hashlib-fixed.openvex.json"; vex = json.loads(path.read_text())
        vex["statements"][0]["products"][0]["@id"] = "unrelated:other-image"; write(path, vex)
        with pytest.raises(SystemExit):
            h["main"]([str(HELPER), "--combine", str(directory), str(directory / "backend.syft.json"), product, image, str(raw_path)])
        return
    h["main"]([str(HELPER), "--combine", str(directory), str(directory / "backend.syft.json"), product, image, str(raw_path)])
    filtered = copy.deepcopy(intermediate)
    filtered["matches"] = [raw["matches"][-1]]
    filtered["ignoredMatches"].append({**python, "appliedIgnoreRules": copy.deepcopy(h["STATUS"])})
    if defect == "extra-statement":
        path = directory / "backend-vendor-reviewed.openvex.json"; value = json.loads(path.read_text())
        value["statements"].append(copy.deepcopy(value["statements"][0])); write(path, value)
    elif defect == "missing-python-ignore": filtered["ignoredMatches"].pop()
    elif defect == "extra-ignore": filtered["ignoredMatches"].append(copy.deepcopy(filtered["ignoredMatches"][0]))
    report = directory / "backend-final.json"; write(report, filtered)
    args = (service, directory, directory / "backend.syft.json", product, image, report)
    if defect is None:
        approved = h["verify_report"](*args, final=True)
        assert len(approved) == 8 and len(filtered["ignoredMatches"]) == 9
        b["validate_fixed_report"](service, report, product, image, hashlib_exception="fixed", openssl_ignored=approved)
    else:
        with pytest.raises(SystemExit): h["verify_report"](*args, final=True)


@pytest.mark.parametrize("defect", [None, "rotated", "existing-directory"])
def test_vendor_archives_use_exact_https_content_and_never_overwrite_existing_paths(tmp_path, monkeypatch, defect):
    import io

    h = run_path(str(HELPER)); scope = h["prepare_archives"].__globals__
    payload = b"synthetic download boundary only; not signed runtime proof"
    expected = hashlib.sha256(payload).hexdigest()
    monkeypatch.setitem(scope, "reviewed", lambda: {"archives_sha256": {"libcrypto3": expected, "libssl3": expected}})
    urls = []

    def response(url, timeout):
        urls.append(url)
        assert timeout == 30
        return io.BytesIO(payload if defect != "rotated" else b"different vendor archive")

    monkeypatch.setattr(scope["urllib"].request, "urlopen", response)
    directory = tmp_path / "vendor-apks"
    if defect == "existing-directory":
        directory.mkdir(); (directory / "user-sentinel").write_text("preserve")
    if defect is None:
        h["prepare_archives"](directory)
        assert urls == ["https://dl-cdn.alpinelinux.org/alpine/v3.24/main/x86_64/" + name + "-3.5.9-r1.apk"
                        for name in ("libcrypto3", "libssl3")]
        assert all(path.read_bytes() == payload and path.stat().st_mode & 0o777 == 0o444 for path in directory.iterdir())
    else:
        with pytest.raises(SystemExit): h["prepare_archives"](directory)
        if defect == "existing-directory":
            assert urls == [] and (directory / "user-sentinel").read_text() == "preserve"
        else:
            assert not list(directory.iterdir())


@pytest.mark.parametrize("service,flags", [
    ("backend", ["--openssl-assessment=old.json", "--openssl-vendor-fixed=new.json"]),
    ("backend", ["--openssl-vendor-fixed=new.json", "--openssl-assessment=old.json"]),
    ("backend", ["--openssl-vendor-fixed=first.json", "--openssl-vendor-fixed=second.json"]),
    ("caddy", ["--openssl-vendor-fixed=new.json"]),
])
def test_final_admission_refuses_mixed_duplicate_or_caddy_vendor_flags_before_loading_proofs(service, flags, tmp_path):
    h = run_path(str(HELPER)); b = h["COMMON"]["base"]()
    sbom = tmp_path / "unread.syft.json"; sbom.write_text("{}")
    with pytest.raises(SystemExit):
        b["main"](["verifier", f"--{service}-fixed-sbom", str(sbom), "erp:test", "sha256:" + "a" * 64,
                   service + "=absent-grype.json", "--runtime-proof=absent-runtime.json", *flags])
