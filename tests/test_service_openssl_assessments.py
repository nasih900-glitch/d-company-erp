from __future__ import annotations

import base64
import copy
import hashlib
import json
from pathlib import Path
from runpy import run_path

import pytest

from postgres16_security_fixtures import reviewed_postgres1616_recipe

from openssl_historical_fixtures import BUILD77, historical_source_root

ROOT = Path(__file__).resolve().parents[1]
HELPER = ROOT / "infra/scripts/service-openssl-assessments.py"


def write(path, value):
    path.write_text(json.dumps(value, sort_keys=True))


@pytest.fixture(params=("caddy", "frontend", "postgres"))
def evidence(request, tmp_path, monkeypatch):
    service = request.param
    h = run_path(str(HELPER))
    review = h["reviewed"](service)
    if service != "caddy":
        historical = historical_source_root(tmp_path, review)
        monkeypatch.setitem(h["validate_context"].__globals__, "HERE", historical / "infra/scripts")
    fixture = json.loads((ROOT / f"tests/fixtures/{service}_openssl_reviewed_matches.json").read_text())
    config_bytes = json.dumps({"os": "linux", "architecture": "amd64", "config": review["image_startup"]}).encode()
    image_id = "sha256:" + hashlib.sha256(config_bytes).hexdigest()
    product = f"erp-{service}:test"
    elfs = {"/usr/lib/libcrypto.so.3": {"sha256": "a" * 64, "dt_needed": []},
            "/usr/lib/libssl.so.3": {"sha256": "b" * 64, "dt_needed": ["libcrypto.so.3"]}}
    review["runtime"]["elf_sha256"] = {p: v["sha256"] for p, v in elfs.items()}
    review["elf_files_sha256"] = h["digest"](elfs)
    monkeypatch.setitem(h["validate_context"].__globals__, "reviewed", lambda selected: copy.deepcopy(review)
                        if selected == service else pytest.fail("cross-service assessment"))
    sbom = {"schema": h["COMMON"]["base"]()["SYFT_SCHEMA"], "descriptor": {"name": "syft", "version": "1.42.3"},
            "source": {"type": "image", "metadata": {"imageID": image_id, "config": base64.b64encode(config_bytes).decode()}},
            "artifacts": fixture["artifacts"], "files": [
                {"location": {"path": p}, "digests": [{"algorithm": "sha256", "value": v["sha256"]}],
                 "executable": {"format": "elf", "importedLibraries": v["dt_needed"]}} for p, v in elfs.items()]}
    raw = {"descriptor": {"name": "grype", "version": "0.118.0", "configuration": {"fail-on-severity": "high"},
                          "db": {"status": {"valid": True, "built": "2026-11-18T00:00:00Z", "schemaVersion": "6.1.3"}}},
           "source": {"type": "image", "target": {"imageID": image_id, "tags": [product]}},
           "matches": fixture["matches"] + [{"vulnerability": {"id": "UNRELATED-MEDIUM", "severity": "Medium"}}],
           "ignoredMatches": []}
    runtime = {"image_id": image_id, "image_product": product, "proof": copy.deepcopy(review["runtime"])}
    write(tmp_path / f"{service}.syft.json", sbom)
    write(tmp_path / f"{service}-grype-unfiltered.json", raw)
    write(tmp_path / f"{service}-openssl-runtime.json", runtime)
    return h, service, tmp_path, product, image_id, sbom, raw, runtime


@pytest.mark.parametrize("defect", [None, "extra-high", "missing-match", "duplicate-match", "wrong-cve", "wrong-package",
                                    "wrong-purl", "source-config", "elf-dependency", "extra-elf", "runtime-native",
                                    "init-script", "entrypoint", "template", "environment", "syft-apk"])
def test_each_named_closure_requires_exact_source_native_config_and_eight_findings(evidence, defect):
    h, service, directory, product, image_id, sbom, raw, runtime = evidence
    first = raw["matches"][0]
    if defect == "extra-high": raw["matches"].append({"vulnerability": {"id": "FUTURE", "severity": "High"}})
    elif defect == "missing-match": raw["matches"].pop(0)
    elif defect == "duplicate-match": raw["matches"].append(copy.deepcopy(first))
    elif defect == "wrong-cve": first["vulnerability"]["id"] = "FUTURE"
    elif defect == "wrong-package": first["artifact"]["name"] = "openssl"
    elif defect == "wrong-purl": first["artifact"]["purl"] = "pkg:apk/alpine/libcrypto3@3.5.10-r0"
    elif defect == "source-config": sbom["source"]["metadata"]["config"] = base64.b64encode(b'{}').decode()
    elif defect == "elf-dependency": sbom["files"][0]["executable"]["importedLibraries"] = ["unreviewed.so"]
    elif defect == "extra-elf": sbom["files"].append({**sbom["files"][0], "location": {"path": "/unreviewed.so"}})
    elif defect == "runtime-native": runtime["proof"]["elf_sha256"]["/usr/lib/libcrypto.so.3"] = "0" * 64
    elif defect == "init-script": runtime["proof"]["configuration_sha256"]["/docker-entrypoint-initdb.d/unreviewed.sql"] = "0" * 64
    elif defect == "entrypoint": runtime["proof"]["entrypoint_sha256"]["/unreviewed.sh"] = "0" * 64
    elif defect == "template": runtime["proof"]["directories"]["/etc/nginx/templates"] = "present"
    elif defect == "environment": runtime["proof"]["crypto_environment"]["OPENSSL_CONF"] = "/unreviewed"
    elif defect == "syft-apk": sbom["artifacts"][0]["metadata"]["pullChecksum"] = "unreviewed"
    write(directory / f"{service}.syft.json", sbom); write(directory / f"{service}-grype-unfiltered.json", raw)
    write(directory / f"{service}-openssl-runtime.json", runtime)
    args = [str(HELPER), "--prove", service, str(directory), str(directory / f"{service}.syft.json"), product, image_id]
    if defect is None:
        h["main"](args)
        document = json.loads((directory / f"{service}-openssl-reviewed.openvex.json").read_text())
        assert len(document["statements"]) == 4
        assert all(s["status"] == "not_affected" and s["justification"] == "vulnerable_code_not_in_execute_path"
                   for s in document["statements"])
    else:
        with pytest.raises((SystemExit, KeyError)):
            h["main"](args)
        assert not (directory / f"{service}-openssl-reviewed.openvex.json").exists()


@pytest.mark.parametrize("defect", [None, "drop-medium", "missing-ignore", "extra-ignore", "wrong-status", "global-ignore", "changed-db"])
def test_each_service_final_scan_preserves_every_raw_record(evidence, defect):
    h, service, directory, product, image_id, _, raw, _ = evidence
    h["main"]([str(HELPER), "--prove", service, str(directory), str(directory / f"{service}.syft.json"), product, image_id])
    report = copy.deepcopy(raw)
    report["matches"] = [raw["matches"][-1]]
    report["ignoredMatches"] = [{**m, "appliedIgnoreRules": copy.deepcopy(h["COMMON"]["STATUS"])} for m in raw["matches"][:-1]]
    if defect == "drop-medium": report["matches"] = []
    elif defect == "missing-ignore": report["ignoredMatches"].pop()
    elif defect == "extra-ignore": report["ignoredMatches"].append(copy.deepcopy(report["ignoredMatches"][0]))
    elif defect == "wrong-status": report["ignoredMatches"][0]["appliedIgnoreRules"][0]["vex-status"] = "fixed"
    elif defect == "global-ignore": report["ignoredMatches"][0]["appliedIgnoreRules"].append({"namespace": "generic"})
    elif defect == "changed-db": report["descriptor"]["db"]["status"]["built"] = "future"
    path = directory / "filtered.json"; write(path, report)
    args = (service, directory, directory / f"{service}.syft.json", product, image_id, path)
    if defect is None:
        approved = h["verify_report"](*args)
        h["COMMON"]["base"]()["validate_fixed_report"](service, path, product, image_id, openssl_ignored=approved)
    else:
        with pytest.raises(SystemExit): h["verify_report"](*args)


@pytest.mark.parametrize("defect", [None, "duplicate", "unknown", "missing-marker", "bad-hash"])
def test_runtime_text_parser_preserves_observed_unicode_links_and_empty_directories(tmp_path, defect):
    h = run_path(str(HELPER))
    text = ("ELF\t" + "a" * 64 + "  /bin/busybox\nRESOLVE\t/bin/busybox\t/bin/busybox\n"
            "DIR\t/etc/nginx/templates\tabsent\n"
            "LINK\t/etc/ssl/certs/988a38cb.0\tca-cert-NetLock_Arany_=Class_Gold=_Főtanúsítvány.pem\nCRYPTOENV\n")
    if defect == "duplicate": text = text.replace("CRYPTOENV", "DIR\t/etc/nginx/templates\tpresent\nCRYPTOENV")
    elif defect == "unknown": text = text.replace("ELF\t", "UNREVIEWED\t")
    elif defect == "missing-marker": text = text.replace("CRYPTOENV\n", "")
    elif defect == "bad-hash": text = text.replace("a" * 64, "not-a-hash")
    path = tmp_path / "runtime.txt"; path.write_text(text)
    if defect is None:
        proof = h["parse_runtime"](path)
        assert proof["directories"] == {"/etc/nginx/templates": "absent"}
        assert proof["links"]["/etc/ssl/certs/988a38cb.0"].endswith("Főtanúsítvány.pem")
        assert proof["elf_sha256"] == {"/bin/busybox": "a" * 64}
    else:
        with pytest.raises(SystemExit): h["parse_runtime"](path)


@pytest.mark.parametrize("defect", [None, "ssl", "shared-preload", "local-preload", "session-preload", "search-path", "extension", "native-library", "role-override"])
def test_postgres_live_state_must_match_closed_review_without_silently_skipping_unavailable_data(defect):
    h = run_path(str(HELPER)); state = copy.deepcopy(h["reviewed"]("postgres")["database_state"])
    if defect == "ssl": state["settings"]["ssl"] = "on"
    elif defect in {"shared-preload", "local-preload", "session-preload"}: state["settings"][defect.replace("-preload", "_preload_libraries")] = "unreviewed"
    elif defect == "search-path": state["settings"]["dynamic_library_path"] = "/tmp"
    elif defect == "extension": state["extensions"].append({"name": "unreviewed", "version": "1"})
    elif defect == "native-library": state["C_libraries"].append("/tmp/unreviewed.so")
    elif defect == "role-override": state["role_database_library_overrides"] = [{"settings": ["dynamic_library_path=/tmp"]}]
    if defect is None: h["verify_postgres_database_state"](state)
    else:
        with pytest.raises(ValueError): h["verify_postgres_database_state"](state)
    with pytest.raises(ValueError): h["verify_postgres_database_state"](None)


def test_unassessed_redis_or_arbitrary_service_is_refused():
    h = run_path(str(HELPER))
    for service in ("redis", "backend", "arbitrary"):
        with pytest.raises(SystemExit): h["reviewed"](service)


def test_collector_cannot_hide_configuration_enumeration_failure(tmp_path, monkeypatch):
    import os
    import subprocess

    h = run_path(str(HELPER))
    config = tmp_path / "configuration"; config.mkdir()
    monkeypatch.setitem(h["prepare_runtime"].__globals__, "reviewed", lambda service: {
        "runtime": {"elf_sha256": {}, "directories": {str(config): "present"}}})
    script = tmp_path / "collector.sh"; h["prepare_runtime"]("frontend", script)
    tools = tmp_path / "tools"; tools.mkdir()
    denied = tools / "find"; denied.write_text("#!/bin/sh\nprintf 'enumeration denied\\n' >&2\nexit 23\n"); denied.chmod(0o755)
    result = subprocess.run(["sh", str(script)], text=True, capture_output=True,
                            env={**os.environ, "PATH": str(tools) + os.pathsep + os.environ["PATH"]})
    assert result.returncode == 23
    assert "enumeration denied" in result.stderr
    assert "CRYPTOENV" not in result.stdout


def test_collector_cannot_report_empty_crypto_environment_when_env_fails(tmp_path, monkeypatch):
    import os
    import subprocess

    h = run_path(str(HELPER))
    monkeypatch.setitem(h["prepare_runtime"].__globals__, "reviewed", lambda service: {
        "runtime": {"elf_sha256": {}, "directories": {}}})
    script = tmp_path / "collector.sh"; h["prepare_runtime"]("frontend", script)
    tools = tmp_path / "tools"; tools.mkdir()
    denied = tools / "env"; denied.write_text("#!/bin/sh\nprintf 'environment unavailable\\n' >&2\nexit 24\n"); denied.chmod(0o755)
    result = subprocess.run(["sh", str(script)], text=True, capture_output=True,
                            env={**os.environ, "PATH": str(tools) + os.pathsep + os.environ["PATH"]})
    assert result.returncode == 24
    assert "environment unavailable" in result.stderr
    assert "CRYPTOENV" not in result.stdout


def test_historical_r0_service_proof_cannot_admit_current_r1_source(evidence, monkeypatch):
    h, service, directory, product, image_id, *_ = evidence
    if service == "caddy":
        # Its unchanged Alpine3.23 runtime remains a current reviewed r0 path.
        h["main"]([str(HELPER), "--prove", service, str(directory), str(directory / f"{service}.syft.json"), product, image_id])
    else:
        monkeypatch.setitem(h["validate_context"].__globals__, "HERE", ROOT / "infra/scripts")
        with pytest.raises(SystemExit, match="deployment source changed"):
            h["main"]([str(HELPER), "--prove", service, str(directory), str(directory / f"{service}.syft.json"), product, image_id])
        assert not (directory / f"{service}-openssl-reviewed.openvex.json").exists()


def test_vendor_r1_refresh_changes_only_three_exact_openssl_recipe_pins():
    import subprocess

    for service in ("backend", "frontend", "postgres", "caddy"):
        name = f"infra/docker/{service}.Dockerfile"
        old = subprocess.check_output(["git", "show", f"{BUILD77}:{name}"], cwd=ROOT, text=True)
        current = (ROOT / name).read_text()
        expected = old if service == "caddy" else old.replace("3.5.9-r0", "3.5.9-r1")
        if service == "postgres":
            expected = reviewed_postgres1616_recipe(expected)
        assert current == expected, service
        if service != "caddy":
            assert "'libcrypto3=3.5.9-r1'" in current
            assert "'libssl3=3.5.9-r1'" in current
            assert "'libcrypto3-3.5.9-r1'" in current
            assert "'libssl3-3.5.9-r1'" in current
            assert "3.5.9-r0" not in current
        if service == "backend":
            assert "'openssl-dev=3.5.9-r1'" in current
