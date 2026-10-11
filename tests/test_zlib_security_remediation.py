from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys

import pytest


ROOT = Path(__file__).resolve().parents[1]
VEX = ROOT / "infra/security/vex/zlib-cve-2026-85091.openvex.json"
VEX_GENERATOR = ROOT / "infra/scripts/generate-zlib-vex.py"
VEX_VERIFIER = ROOT / "infra/scripts/verify-zlib-vex.py"
IMAGE_PRODUCT = "erp-backend:test"
CADDY_PRODUCT = "erp-caddy:test"
IMAGE_ID = "sha256:" + "a" * 64
DOCKERFILES = (
    "backend.Dockerfile",
    "frontend.Dockerfile",
    "caddy.Dockerfile",
    "postgres.Dockerfile",
    "redis.Dockerfile",
)


def test_official_zlib_source_and_complete_upstream_patch_chain_are_hash_bound() -> None:
    null_guard = ROOT / "infra/docker/zlib/e3dc0a85-null-guard.patch"
    printf_return = ROOT / "infra/docker/zlib/bbc2ccf3-gzvprintf-return.patch"
    primary = ROOT / "infra/docker/zlib/cve-2026-85091.patch"
    followup = ROOT / "infra/docker/zlib/cve-2026-85091-followup.patch"
    assert hashlib.sha256(null_guard.read_bytes()).hexdigest() == (
        "183bc8b9dd078a41a62de5c2d905d9b0196b45bc46f100d7e4147ec228207c74"
    )
    assert hashlib.sha256(printf_return.read_bytes()).hexdigest() == (
        "7d00ee29be5e636d30da2890961e83e35cee0b152333ecd97a46ae2e71eb5d47"
    )
    assert hashlib.sha256(primary.read_bytes()).hexdigest() == (
        "110ff14375733173d8aa54574473424fbd7dfe4b81f1ca34a759c6fe14b15b14"
    )
    assert hashlib.sha256(followup.read_bytes()).hexdigest() == (
        "96040ee84d0d187905283912dbd3f7b66ac2033976a2ceefe9b8cca63143d9c2"
    )

    build = (ROOT / "infra/docker/zlib/build-patched-zlib.sh").read_text()
    for value in (
        "bb329a0a2cd0274d05519d61c667c062e06990d72e125ee2dfa8de64f0119d16",
        "e3dc0a85b7032e98380dec011bc8f2c2ee0d8fca",
        "bbc2ccf3d0de267576b524b875c769a724a513b0",
        "183bc8b9dd078a41a62de5c2d905d9b0196b45bc46f100d7e4147ec228207c74",
        "7d00ee29be5e636d30da2890961e83e35cee0b152333ecd97a46ae2e71eb5d47",
        "df84af25dc1942490e1d1c899a07619152a46148",
        "7235b0a581227c56a79a43ff828f8ef6794194c8",
        "110ff14375733173d8aa54574473424fbd7dfe4b81f1ca34a759c6fe14b15b14",
        "96040ee84d0d187905283912dbd3f7b66ac2033976a2ceefe9b8cca63143d9c2",
    ):
        assert value in build
    assert "make test" in build
    assert "grep -Fc" in build
    assert "-eq 2" in build
    assert "adjacent++" in build
    assert "-eq 2" in build
    assert "cc6687863cc2560ca866e9fd480802d0a90fe09b65bea37f1b1a59e5a02f9e2b" in build


def test_zlib_source_fetch_retries_failures_and_rejects_wrong_bytes(tmp_path: Path) -> None:
    source = (ROOT / "infra/docker/zlib/build-patched-zlib.sh").read_text()
    function = re.search(r"(?ms)^fetch_zlib_source\(\) \{\n.*?^\}\n", source)
    assert function is not None

    bin_dir = tmp_path / "bin"
    bin_dir.mkdir()
    wget = bin_dir / "wget"
    wget.write_text(
        "#!/bin/sh\n"
        "set -eu\n"
        "count=$(cat \"$FAKE_WGET_COUNT\" 2>/dev/null || printf 0)\n"
        "count=$((count + 1))\n"
        "printf '%s\\n' \"$count\" > \"$FAKE_WGET_COUNT\"\n"
        "if [ \"$count\" -lt \"$FAKE_WGET_SUCCEED_ON\" ]; then\n"
        "    printf 'simulated download failure\\n' >&2\n"
        "    exit 1\n"
        "fi\n"
        "while [ \"$#\" -gt 0 ]; do\n"
        "    if [ \"$1\" = -O ]; then output=$2; break; fi\n"
        "    shift\n"
        "done\n"
        "cp \"$FAKE_WGET_PAYLOAD\" \"$output\"\n"
    )
    wget.chmod(0o755)
    sleep = bin_dir / "sleep"
    sleep.write_text("#!/bin/sh\nexit 0\n")
    sleep.chmod(0o755)

    payload = tmp_path / "payload"
    payload.write_bytes(b"verified source archive fixture")
    archive = tmp_path / "archive.tar.gz"
    count_file = tmp_path / "attempts"
    env = os.environ | {
        "PATH": f"{bin_dir}:{os.environ['PATH']}",
        "SOURCE_URL": "https://example.test/zlib-1.3.2.tar.gz",
        "SOURCE_ARCHIVE": str(archive),
        "SOURCE_SHA256": hashlib.sha256(payload.read_bytes()).hexdigest(),
        "FAKE_WGET_COUNT": str(count_file),
        "FAKE_WGET_PAYLOAD": str(payload),
    }
    harness = "set -eu\n" + function.group() + "\nfetch_zlib_source\n"

    env["FAKE_WGET_SUCCEED_ON"] = "3"
    recovered = subprocess.run(["sh", "-c", harness], env=env, capture_output=True, text=True)
    assert recovered.returncode == 0, recovered.stderr
    assert count_file.read_text().strip() == "3"
    assert archive.read_bytes() == payload.read_bytes()

    count_file.unlink()
    env["SOURCE_SHA256"] = "0" * 64
    env["FAKE_WGET_SUCCEED_ON"] = "1"
    mismatch = subprocess.run(["sh", "-c", harness], env=env, capture_output=True, text=True)
    assert mismatch.returncode != 0
    assert "zlib source SHA256 mismatch" in mismatch.stderr
    assert count_file.read_text().strip() == "1"

    count_file.unlink()
    env["FAKE_WGET_SUCCEED_ON"] = "4"
    failed = subprocess.run(["sh", "-c", harness], env=env, capture_output=True, text=True)
    assert failed.returncode != 0
    assert "zlib source download failed after 3 attempts" in failed.stderr
    assert count_file.read_text().strip() == "3"


def test_every_alpine_runtime_installs_and_proves_the_patched_library() -> None:
    for name in DOCKERFILES:
        source = (ROOT / "infra/docker" / name).read_text()
        assert " AS zlib-builder" in source
        assert "sh /tmp/dcompany-zlib/build-patched-zlib.sh" in source
        assert (
            "COPY --from=zlib-builder /out/usr/lib/libz.so.1.3.2 "
            "/usr/lib/libz.so.1.3.2"
        ) in source
        assert "sh /tmp/verify-patched-zlib.sh" in source
        assert 'com.dcompany.zlib.null-guard.commit="e3dc0a85' in source
        assert 'com.dcompany.zlib.printf-return.commit="bbc2ccf3' in source
        assert 'com.dcompany.zlib.patch.commit="df84af25' in source
        assert 'com.dcompany.zlib.followup.commit="7235b0a5' in source
        for line in source.splitlines():
            if line.startswith("FROM "):
                assert "@sha256:" in line

    backend = (ROOT / "infra/docker/backend.Dockerfile").read_text()
    assert backend.count(
        "python:3.14.7-alpine3.24@sha256:"
        "016508ba505da24f7139765bc4bb669df4e88eb2f12eeadd571bf2f88d7533df"
    ) == 3
    redis = (ROOT / "infra/docker/redis.Dockerfile").read_text()
    assert redis.count(
        "redis:7.4.11-alpine3.21@sha256:"
        "520775a41a63e77e06c73e35d2fd9cc15921a609516818796b4ecbb813078bc7"
    ) == 2


def _report(
    ignored: list[dict[str, object]],
    matches: list[dict[str, object]] | None = None,
    *,
    image_id: str = IMAGE_ID,
    tags: list[str] | None = None,
) -> dict[str, object]:
    return {
        "source": {
            "type": "image",
            "target": {"imageID": image_id, "tags": tags or [IMAGE_PRODUCT]},
        },
        "matches": matches or [],
        "ignoredMatches": ignored,
    }


def _zlib_match(*, cve: str = "CVE-2026-85091", status: str = "fixed") -> dict[str, object]:
    return {
        "vulnerability": {"id": cve},
        "artifact": {
            "name": "zlib",
            "version": "1.3.2-r0",
            "type": "apk",
            "purl": "pkg:apk/alpine/zlib@1.3.2-r0?arch=x86_64&distro=alpine-3.24.0",
        },
        "appliedIgnoreRules": [
            {
                "namespace": "vex",
                "vex-status": status,
            }
        ],
    }


def _render_vex(
    tmp_path: Path,
    image_product: str = IMAGE_PRODUCT,
    image_id: str = IMAGE_ID,
) -> Path:
    tmp_path.mkdir(parents=True, exist_ok=True)
    path = tmp_path / "backend-zlib.openvex.json"
    if path.exists():
        return path
    completed = subprocess.run(
        [
            sys.executable,
            str(VEX_GENERATOR),
            str(VEX),
            image_product,
            image_id,
            str(path),
        ],
        text=True,
        capture_output=True,
        check=False,
    )
    assert completed.returncode == 0, completed.stderr
    assert path.stat().st_mode & 0o777 == 0o444
    rendered = path.read_text(encoding="utf-8")
    assert "__D_COMPANY_" not in rendered
    assert image_product in rendered
    assert image_id in rendered
    return path


def test_vex_generator_sets_scanner_read_mode_under_installer_umask(
    tmp_path: Path,
) -> None:
    path = tmp_path / "restrictive-umask.openvex.json"
    completed = subprocess.run(
        [
            sys.executable,
            str(VEX_GENERATOR),
            str(VEX),
            IMAGE_PRODUCT,
            IMAGE_ID,
            str(path),
        ],
        text=True,
        capture_output=True,
        check=False,
        preexec_fn=lambda: os.umask(0o077),
    )

    assert completed.returncode == 0, completed.stderr
    assert path.stat().st_mode & 0o777 == 0o444


def _verify_report(tmp_path: Path, report: dict[str, object]) -> subprocess.CompletedProcess[str]:
    tmp_path.mkdir(parents=True, exist_ok=True)
    path = tmp_path / "grype.json"
    path.write_text(json.dumps(report), encoding="utf-8")
    vex = _render_vex(tmp_path)
    return subprocess.run(
        [
            sys.executable,
            str(VEX_VERIFIER),
            str(vex),
            IMAGE_PRODUCT,
            IMAGE_ID,
            f"backend={path}",
        ],
        text=True,
        capture_output=True,
        check=False,
    )


def test_vex_accepts_only_the_exact_fixed_zlib_disposition(tmp_path: Path) -> None:
    completed = _verify_report(tmp_path, _report([_zlib_match()]))
    assert completed.returncode == 0, completed.stderr
    assert "backend_zlib_vex_fixed_ignored_matches=1" in completed.stdout


def test_vex_rejects_unrelated_or_nonfixed_ignored_matches(tmp_path: Path) -> None:
    unrelated = _verify_report(
        tmp_path,
        _report([_zlib_match(cve="CVE-2026-00001")]),
    )
    assert unrelated.returncode != 0
    assert "Unexpected ignored Grype match" in unrelated.stderr

    nonfixed = _verify_report(tmp_path, _report([_zlib_match(status="not_affected")]))
    assert nonfixed.returncode != 0
    assert "not the reviewed fixed rule" in nonfixed.stderr


def test_vex_rejects_extra_or_multiple_ignore_rule_evidence(tmp_path: Path) -> None:
    extra_field = _zlib_match()
    extra_field["appliedIgnoreRules"] = [
        {
            "namespace": "vex",
            "vulnerability": "CVE-2026-85091",
            "vex-status": "fixed",
        }
    ]
    extra = _verify_report(tmp_path / "extra", _report([extra_field]))
    assert extra.returncode != 0
    assert "not the reviewed fixed rule" in extra.stderr

    multiple_rules = _zlib_match()
    multiple_rules["appliedIgnoreRules"] = [
        {"namespace": "vex", "vex-status": "fixed"},
        {"namespace": "vex", "vex-status": "fixed"},
    ]
    multiple = _verify_report(tmp_path / "multiple", _report([multiple_rules]))
    assert multiple.returncode != 0
    assert "disposition is ambiguous" in multiple.stderr


def test_vex_rejects_an_unfiltered_zlib_finding(tmp_path: Path) -> None:
    finding = _zlib_match()
    finding.pop("appliedIgnoreRules")
    completed = _verify_report(tmp_path, _report([], [finding]))
    assert completed.returncode != 0
    assert "Unfiltered CVE-2026-85091 zlib match remains" in completed.stderr


def test_vex_rejects_zero_or_multiple_ignored_matches(tmp_path: Path) -> None:
    none = _verify_report(tmp_path / "none", _report([]))
    assert none.returncode != 0
    assert "exactly one reviewed zlib finding" in none.stderr

    multiple = _verify_report(
        tmp_path / "multiple", _report([_zlib_match(), _zlib_match()])
    )
    assert multiple.returncode != 0
    assert "exactly one reviewed zlib finding" in multiple.stderr


def test_vex_rejects_wrong_report_image_id_or_tag(tmp_path: Path) -> None:
    wrong_id = _verify_report(
        tmp_path / "wrong-id",
        _report([_zlib_match()], image_id="sha256:" + "b" * 64),
    )
    assert wrong_id.returncode != 0
    assert "image ID does not match VEX" in wrong_id.stderr

    wrong_tag = _verify_report(
        tmp_path / "wrong-tag",
        _report([_zlib_match()], tags=["erp-backend:other"]),
    )
    assert wrong_tag.returncode != 0
    assert "image tag does not match VEX" in wrong_tag.stderr


def _caddy_sbom(*, image_id: str = IMAGE_ID) -> dict[str, object]:
    layer_id = "sha256:" + "b" * 64
    return {
        "artifacts": [
            {
                "id": "zlib-r1",
                "name": "zlib",
                "version": "1.3.2-r1",
                "type": "apk",
                "foundBy": "apk-db-cataloger",
                "locations": [
                    {
                        "path": "/lib/apk/db/installed",
                        "accessPath": "/lib/apk/db/installed",
                        "layerID": layer_id,
                        "annotations": {"evidence": "primary"},
                    }
                ],
                "purl": (
                    "pkg:apk/alpine/zlib@1.3.2-r1"
                    "?arch=x86_64&distro=alpine-3.23.5"
                ),
                "metadataType": "apk-db-entry",
                "metadata": {
                    "package": "zlib",
                    "originPackage": "zlib",
                    "version": "1.3.2-r1",
                    "architecture": "x86_64",
                    "gitCommitOfApkPort": "0afa2da0e8c8051c6f8f64a7a388e5a259904245",
                    "provides": ["so:libz.so.1=1.3.2"],
                    "files": [{"path": "/usr/lib/libz.so.1.3.2"}],
                },
            },
            {
                "id": "musl",
                "name": "musl",
                "version": "1.2.5-r21",
                "type": "apk",
                "foundBy": "apk-db-cataloger",
            },
        ],
        "artifactRelationships": [
            {"parent": "zlib-r1", "child": "musl", "type": "dependency-of"}
        ],
        "files": [{"id": "runtime", "location": {"path": "/usr/lib/libz.so.1.3.2"}}],
        "descriptor": {
            "name": "syft",
            "version": "1.42.3",
            "configuration": {
                "catalogers": {"used": ["apk-db-cataloger", "file-metadata-cataloger"]}
            },
        },
        "schema": {
            "version": "16.1.3",
            "url": (
                "https://raw.githubusercontent.com/anchore/syft/main/"
                "schema/json/schema-16.1.3.json"
            ),
        },
        "source": {"type": "image", "metadata": {"imageID": image_id}},
        "distro": {"id": "alpine", "versionID": "3.23.5"},
    }


def _caddy_grype(
    *,
    image_id: str = IMAGE_ID,
    matches: list[dict[str, object]] | None = None,
    ignored: list[dict[str, object]] | None = None,
) -> dict[str, object]:
    return {
        "descriptor": {
            "name": "grype",
            "version": "0.118.0",
            "configuration": {"fail-on-severity": "high"},
            "db": {
                "status": {
                    "built": "2026-10-11T00:00:00Z",
                    "schemaVersion": "v6.1.9",
                    "valid": True,
                }
            },
        },
        "source": {
            "type": "image",
            "target": {"imageID": image_id, "tags": [CADDY_PRODUCT]},
        },
        "matches": [] if matches is None else matches,
        "ignoredMatches": [] if ignored is None else ignored,
    }


def _verify_caddy(
    tmp_path: Path,
    sbom: dict[str, object],
    report: dict[str, object],
) -> subprocess.CompletedProcess[str]:
    tmp_path.mkdir(parents=True, exist_ok=True)
    sbom_path = tmp_path / "caddy.syft.json"
    report_path = tmp_path / "caddy-grype.json"
    sbom_path.write_text(json.dumps(sbom), encoding="utf-8")
    report_path.write_text(json.dumps(report), encoding="utf-8")
    return subprocess.run(
        [
            sys.executable,
            str(VEX_VERIFIER),
            "--caddy-fixed-sbom",
            str(sbom_path),
            CADDY_PRODUCT,
            IMAGE_ID,
            f"caddy={report_path}",
        ],
        text=True,
        capture_output=True,
        check=False,
    )


def test_caddy_r1_requires_actual_syft_and_zero_suppressed_findings(
    tmp_path: Path,
) -> None:
    sbom = _caddy_sbom()
    report = _caddy_grype()
    report.pop("ignoredMatches")
    completed = _verify_caddy(tmp_path, sbom, report)

    assert completed.returncode == 0, completed.stderr
    sbom_path = tmp_path / "caddy.syft.json"
    assert (
        f"caddy_fixed_sbom_sha256={hashlib.sha256(sbom_path.read_bytes()).hexdigest()}"
        in completed.stdout
    )
    assert "caddy_fixed_zlib_version=1.3.2-r1" in completed.stdout
    assert "caddy_fixed_zlib_ignored_matches=0" in completed.stdout
    assert not list(tmp_path.glob("*.openvex.json"))


@pytest.mark.parametrize(
    ("case", "error"),
    [
        ("empty", "empty or incomplete catalogue"),
        ("duplicate", "exactly one zlib package"),
        ("r0", "identity is inconsistent"),
        ("version", "identity is inconsistent"),
        ("type", "identity is inconsistent"),
        ("purl", "PURL metadata is inconsistent"),
        ("cataloguer", "identity is inconsistent"),
        ("location", "location is inconsistent"),
        ("foreign-id", "SBOM image ID mismatch"),
        ("missing-id", "SBOM image ID mismatch"),
        ("scanner", "unexpected Syft identity"),
    ],
)
def test_caddy_r1_rejects_incomplete_or_inconsistent_syft_evidence(
    tmp_path: Path, case: str, error: str
) -> None:
    sbom = _caddy_sbom()
    zlib = sbom["artifacts"][0]  # type: ignore[index]
    if case == "empty":
        sbom["artifacts"] = []
    elif case == "duplicate":
        sbom["artifacts"].append(dict(zlib))  # type: ignore[union-attr]
    elif case == "r0":
        zlib["version"] = "1.3.2-r0"  # type: ignore[index]
    elif case == "version":
        zlib["version"] = "1.3.2-r2"  # type: ignore[index]
    elif case == "type":
        zlib["type"] = "deb"  # type: ignore[index]
    elif case == "purl":
        zlib["purl"] = "pkg:apk/alpine/zlib@1.3.2-r1?arch=aarch64&distro=alpine-3.23.5"  # type: ignore[index]
    elif case == "cataloguer":
        zlib["foundBy"] = "binary-classifier-cataloger"  # type: ignore[index]
    elif case == "location":
        zlib["locations"][0]["path"] = "/fake/installed"  # type: ignore[index]
    elif case == "foreign-id":
        sbom["source"] = {
            "type": "image",
            "metadata": {"imageID": "sha256:" + "c" * 64},
        }
    elif case == "missing-id":
        sbom["source"] = {"type": "image", "metadata": {}}
    elif case == "scanner":
        sbom["descriptor"]["version"] = "1.42.2"  # type: ignore[index]

    completed = _verify_caddy(tmp_path, sbom, _caddy_grype())
    assert completed.returncode != 0
    assert error in completed.stderr


@pytest.mark.parametrize(
    ("case", "error"),
    [
        ("scanner", "unexpected scanner identity"),
        ("db", "vulnerability DB is not valid"),
        ("db-built", "no vulnerability DB build identity"),
        ("db-schema", "no vulnerability DB schema identity"),
        ("foreign-id", "image ID does not match SBOM"),
        ("missing-id", "image ID does not match SBOM"),
        ("wrong-tag", "image tag does not match SBOM"),
        ("ignored", "must not contain ignored findings"),
        ("residual", "Residual CVE-2026-85091"),
        ("high", "high or critical finding"),
        ("arrays", "match arrays are invalid"),
        ("fail-gate", "high severity fail gate"),
    ],
)
def test_caddy_r1_rejects_untrusted_or_residual_grype_evidence(
    tmp_path: Path, case: str, error: str
) -> None:
    report = _caddy_grype()
    if case == "scanner":
        report["descriptor"]["version"] = "0.117.0"  # type: ignore[index]
    elif case == "db":
        report["descriptor"]["db"]["status"]["valid"] = False  # type: ignore[index]
    elif case == "db-built":
        report["descriptor"]["db"]["status"]["built"] = ""  # type: ignore[index]
    elif case == "db-schema":
        report["descriptor"]["db"]["status"]["schemaVersion"] = ""  # type: ignore[index]
    elif case == "foreign-id":
        report["source"]["target"]["imageID"] = "sha256:" + "c" * 64  # type: ignore[index]
    elif case == "missing-id":
        report["source"]["target"].pop("imageID")  # type: ignore[index]
    elif case == "wrong-tag":
        report["source"]["target"]["tags"] = ["erp-caddy:other"]  # type: ignore[index]
    elif case == "ignored":
        report["ignoredMatches"] = [_zlib_match()]
    elif case == "residual":
        report["matches"] = [{"vulnerability": {"id": "CVE-2026-85091"}}]
    elif case == "high":
        report["matches"] = [
            {"vulnerability": {"id": "CVE-2026-00001", "severity": "High"}}
        ]
    elif case == "arrays":
        report["ignoredMatches"] = {}
    elif case == "fail-gate":
        report["descriptor"]["configuration"]["fail-on-severity"] = "critical"  # type: ignore[index]

    completed = _verify_caddy(tmp_path, _caddy_sbom(), report)
    assert completed.returncode != 0
    assert error in completed.stderr


def test_caddy_fixed_sbom_argument_is_narrowly_bound(tmp_path: Path) -> None:
    sbom_path = tmp_path / "caddy.syft.json"
    report_path = tmp_path / "grype.json"
    sbom_path.write_text(json.dumps(_caddy_sbom()), encoding="utf-8")
    report_path.write_text(json.dumps(_caddy_grype()), encoding="utf-8")
    vex = _render_vex(tmp_path, CADDY_PRODUCT)
    cases = (
        (
            [
                sys.executable,
                str(VEX_VERIFIER),
                str(vex),
                CADDY_PRODUCT,
                IMAGE_ID,
                f"caddy={report_path}",
            ],
            "Invalid Grype report binding",
        ),
        (
            [
                sys.executable,
                str(VEX_VERIFIER),
                "--caddy-fixed-sbom",
                str(sbom_path),
                CADDY_PRODUCT,
                IMAGE_ID,
                f"backend={report_path}",
            ],
            "requires exactly one caddy",
        ),
        (
            [
                sys.executable,
                str(VEX_VERIFIER),
                "--caddy-fixed-sbom",
                str(sbom_path),
                CADDY_PRODUCT,
                IMAGE_ID,
                f"caddy={report_path}",
                f"caddy={report_path}",
            ],
            "Usage:",
        ),
        (
            [
                sys.executable,
                str(VEX_VERIFIER),
                str(vex),
                CADDY_PRODUCT,
                IMAGE_ID,
                "--caddy-fixed-sbom",
                str(sbom_path),
            ],
            "separate Caddy verifier mode",
        ),
    )
    for command, error in cases:
        completed = subprocess.run(
            command,
            text=True,
            capture_output=True,
            check=False,
        )
        assert completed.returncode != 0
        assert error in completed.stderr


def test_scanner_evidence_rejects_duplicate_json_keys(tmp_path: Path) -> None:
    sbom_path = tmp_path / "caddy.syft.json"
    report_path = tmp_path / "grype.json"
    sbom_path.write_text(json.dumps(_caddy_sbom()), encoding="utf-8")
    report = json.dumps(_caddy_grype())
    report_path.write_text(
        report.replace('"matches": []', '"matches": [], "matches": []', 1),
        encoding="utf-8",
    )
    completed = subprocess.run(
        [
            sys.executable,
            str(VEX_VERIFIER),
            "--caddy-fixed-sbom",
            str(sbom_path),
            CADDY_PRODUCT,
            IMAGE_ID,
            f"caddy={report_path}",
        ],
        text=True,
        capture_output=True,
        check=False,
    )

    assert completed.returncode != 0
    assert "duplicate object key: matches" in completed.stderr


def test_unrendered_template_is_rejected(tmp_path: Path) -> None:
    report = tmp_path / "grype.json"
    report.write_text(json.dumps(_report([_zlib_match()])), encoding="utf-8")
    completed = subprocess.run(
        [
            sys.executable,
            str(VEX_VERIFIER),
            str(VEX),
            IMAGE_PRODUCT,
            IMAGE_ID,
            f"backend={report}",
        ],
        text=True,
        capture_output=True,
        check=False,
    )
    assert completed.returncode != 0
    assert "unresolved template placeholder" in completed.stderr


def test_redis_is_a_locally_built_release_image() -> None:
    compose = (ROOT / "docker-compose.prod.yml").read_text()
    redis = compose.split("\n  redis:\n", 1)[1].split("\n  backend:\n", 1)[0]
    assert "image: d-company-erp-redis:${APP_REVISION:-unknown}" in redis
    assert "dockerfile: infra/docker/redis.Dockerfile" in redis

    parity = (ROOT / "ops/runtime_release_parity.py").read_text()
    assert '("caddy", "postgres", "redis", "backend", "frontend")' in parity
    assert 'frozenset({"postgres", "redis", "backend", "frontend"})' in parity
