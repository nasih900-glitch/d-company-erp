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
    assert "SOURCE_URL='https://github.com/madler/zlib/releases/download/v1.3.2/zlib-1.3.2.tar.gz'" in build
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
        service = name.removesuffix(".Dockerfile")
        source = (ROOT / "infra/docker" / name).read_text()
        assert " AS zlib-probe-builder" in source
        assert "build-patched-zlib.sh" not in source
        assert "COPY --from=zlib-builder" not in source
        assert 'com.dcompany.zlib.version="1.4-r0"' in source
        if service == "redis":
            update = 'apk --cache-dir "$zlib_download_dir/cache" update'
            download = 'apk --cache-dir "$zlib_download_dir/cache" cache download \'zlib=1.4-r0\''
            assert source.index(update) < source.index(download)
            assert "apk fetch" not in source
        else:
            assert "apk fetch --no-cache --recursive" in source
        assert "'zlib=1.4-r0'" in source
        assert f"RUN /usr/local/bin/verify-caddy-zlib {service}" in source
        for line in source.splitlines():
            if line.startswith("FROM "): assert "@sha256:" in line

    backend = (ROOT / "infra/docker/backend.Dockerfile").read_text()
    assert backend.count(
        "python:3.14.10-alpine3.24@sha256:"
        "eb2c62658b8d51bff0acc408504b76521d02030de56a8a3c1e830083e89b89d2"
    ) == 4
    assert "apk info -v zlib" not in backend
    assert 'printf \'%s\\n\' "$installed_packages" | grep -Fx \'zlib-1.4-r0\'' in backend
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
                "version": "1.4-r0",
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
                    "pkg:apk/alpine/zlib@1.4-r0"
                    "?arch=x86_64&distro=alpine-3.23.5"
                ),
                "metadataType": "apk-db-entry",
                "metadata": {
                    "package": "zlib",
                    "originPackage": "zlib",
                    "version": "1.4-r0",
                    "architecture": "x86_64",
                    "gitCommitOfApkPort": "0436c6d655a04cbfd7987202a970f5aa7870a901",
                    "provides": ["so:libz.so.1=1.4"],
                    "files": [{"path": "/usr/lib/libz.so.1.4"}],
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
        "files": [{"id": "runtime", "location": {"path": "/usr/lib/libz.so.1.4"}}],
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
        "source": {"type": "image", "metadata": {"imageID": image_id, "architecture": "amd64", "os": "linux"}},
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


def _caddy_runtime_proof() -> dict:
    # Synthetic test data for binding/negative tests, never actual runtime evidence.
    return {"image_id": IMAGE_ID, "image_product": CADDY_PRODUCT, "proof": {
        "service": "caddy", "package": "zlib", "package_version": "1.4-r0",
        "port_commit": "0436c6d655a04cbfd7987202a970f5aa7870a901",
        "distro": "alpine", "distro_version": "3.23.5", "architecture": "x86_64",
        "apk_signature_verified": True, "apk_sha256": "c" * 64,
        "manifest_algorithm": "sha1", "manifest_digest": "d" * 40,
        "runtime_path": "/usr/lib/libz.so.1.4", "runtime_sha256": "e" * 64,
        "apk_runtime_sha256": "e" * 64, "runtime_version": "1.4",
        "soname": "libz.so.1", "proc_map": "/usr/lib/libz.so.1.4",
    }}


def _caddy_proof_file(tmp_path: Path, value: dict | None = None) -> Path:
    path = tmp_path / "caddy-runtime.json"
    path.write_text(json.dumps(_caddy_runtime_proof() if value is None else value))
    return path


def _verify_caddy(
    tmp_path: Path,
    sbom: dict[str, object],
    report: dict[str, object],
    runtime: dict | None = None,
    *, extra_arguments: tuple[str, ...] = (),
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
            f"--runtime-proof={_caddy_proof_file(tmp_path, runtime)}",
            *extra_arguments,
        ],
        text=True,
        capture_output=True,
        check=False,
    )


def test_caddy_official_14_requires_actual_syft_and_zero_suppressed_findings(
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
    assert "caddy_fixed_zlib_version=1.4-r0" in completed.stdout
    assert "caddy_fixed_zlib_ignored_matches=0" in completed.stdout
    assert not list(tmp_path.glob("*.openvex.json"))


@pytest.mark.parametrize(
    ("os_value", "architecture", "binding", "error"),
    (
        ("", "", f"linux/amd64@{IMAGE_ID}", None),
        ("", "", None, "reviewed Alpine port"),
        ("", "amd64", f"linux/amd64@{IMAGE_ID}", "reviewed Alpine port"),
        ("linux", "", f"linux/amd64@{IMAGE_ID}", "reviewed Alpine port"),
        (None, "", f"linux/amd64@{IMAGE_ID}", "reviewed Alpine port"),
        ("", None, f"linux/amd64@{IMAGE_ID}", "reviewed Alpine port"),
        ("windows", "amd64", f"linux/amd64@{IMAGE_ID}", "reviewed Alpine port"),
        ("linux", "arm64", f"linux/amd64@{IMAGE_ID}", "reviewed Alpine port"),
        ("", "", f"linux/arm64@{IMAGE_ID}", "archive platform/config identity mismatch"),
        ("", "", "linux/amd64@sha256:" + "f" * 64, "archive platform/config identity mismatch"),
    ),
)
def test_only_explicit_empty_syft_platform_can_use_matching_archive_binding(
    tmp_path: Path, os_value: str | None, architecture: str | None, binding: str | None, error: str | None,
) -> None:
    sbom = _caddy_sbom()
    metadata = sbom["source"]["metadata"]
    for field, value in (("os", os_value), ("architecture", architecture)):
        if value is None:
            del metadata[field]
        else:
            metadata[field] = value
    arguments = () if binding is None else (f"--archive-platform={binding}",)
    result = _verify_caddy(tmp_path, sbom, _caddy_grype(), extra_arguments=arguments)
    assert result.returncode == (0 if error is None else 1), result.stderr
    if error is None:
        assert "caddy_fixed_sbom_platform_source=verified-docker-archive" in result.stdout
    else:
        assert error in result.stderr


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
def test_caddy_official_14_rejects_incomplete_or_inconsistent_syft_evidence(
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
        zlib["purl"] = "pkg:apk/alpine/zlib@1.4-r0?arch=aarch64&distro=alpine-3.23.5"  # type: ignore[index]
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
def test_caddy_official_14_rejects_untrusted_or_residual_grype_evidence(
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
            "separate fixed-SBOM verifier mode",
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
            f"--runtime-proof={_caddy_proof_file(tmp_path)}",
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


def _backend_r1_evidence(architecture: str = "x86_64") -> tuple[dict, dict]:
    sbom = _caddy_sbom()
    sbom["distro"]["versionID"] = "3.24.3"
    package = sbom["artifacts"][0]
    package["metadata"]["gitCommitOfApkPort"] = "bdf501c870a92a05c097857401f69c48c4810384"
    package["metadata"]["architecture"] = architecture
    package["purl"] = f"pkg:apk/alpine/zlib@1.4-r0?arch={architecture}&distro=alpine-3.24.3"
    report = _caddy_grype()
    report["source"]["target"]["tags"] = [IMAGE_PRODUCT]
    return sbom, report


def _backend_proof_file(tmp_path):
    value = _caddy_runtime_proof()
    value["image_product"] = IMAGE_PRODUCT
    value["proof"].update({"service": "backend", "distro_version": "3.24.3",
                           "port_commit": "bdf501c870a92a05c097857401f69c48c4810384"})
    path = tmp_path / "backend-runtime.json"
    path.write_text(json.dumps(value))
    return path


def _verify_backend_fixed(tmp_path: Path, sbom: dict, report: dict) -> subprocess.CompletedProcess[str]:
    tmp_path.mkdir(parents=True, exist_ok=True)
    sbom_path, report_path = tmp_path / "backend.syft.json", tmp_path / "backend-grype.json"
    sbom_path.write_text(json.dumps(sbom), encoding="utf-8")
    report_path.write_text(json.dumps(report), encoding="utf-8")
    return subprocess.run(
        [sys.executable, str(VEX_VERIFIER), "--backend-fixed-sbom", str(sbom_path),
         IMAGE_PRODUCT, IMAGE_ID, f"backend={report_path}",
         f"--runtime-proof={_backend_proof_file(tmp_path)}"],
        text=True, capture_output=True, check=False,
    )


@pytest.mark.parametrize("architecture", ["x86_64"])
@pytest.mark.parametrize("omitted_ignored", [False, True])
def test_backend_fixed_r1_requires_bound_sbom_and_zero_suppressions(
    tmp_path: Path, architecture: str, omitted_ignored: bool,
) -> None:
    sbom, report = _backend_r1_evidence(architecture)
    if omitted_ignored:
        del report["ignoredMatches"]
    result = _verify_backend_fixed(tmp_path, sbom, report)
    assert result.returncode == 0, result.stderr
    assert "backend_fixed_zlib_ignored_matches=0" in result.stdout
    assert "backend_fixed_zlib_version=1.4-r0" in result.stdout


@pytest.mark.parametrize("defect", [
    "r0", "duplicate_package", "wrong_distro", "wrong_port", "wrong_arch", "aarch64",
    "wrong_purl_arch", "wrong_purl_distro", "metadata_version", "missing_runtime",
    "wrong_sbom_id", "wrong_report_id", "wrong_tag", "null_ignored", "object_ignored",
    "suppression", "residual_cve", "high", "critical", "wrong_scanner", "invalid_database",
])
def test_backend_fixed_r1_rejects_untrusted_or_suppressed_evidence(tmp_path: Path, defect: str) -> None:
    sbom, report = _backend_r1_evidence()
    package = sbom["artifacts"][0]
    if defect == "r0": package["version"] = "1.3.2-r0"
    elif defect == "duplicate_package": sbom["artifacts"].append(dict(package))
    elif defect == "wrong_distro": sbom["distro"]["versionID"] = "3.23.5"
    elif defect == "wrong_port": package["metadata"]["gitCommitOfApkPort"] = "c" * 40
    elif defect == "wrong_arch": package["metadata"]["architecture"] = "unknown"
    elif defect == "aarch64": package["metadata"]["architecture"] = "aarch64"
    elif defect == "wrong_purl_arch": package["purl"] = package["purl"].replace("x86_64", "aarch64")
    elif defect == "wrong_purl_distro": package["purl"] = package["purl"].replace("3.24.3", "3.24.2")
    elif defect == "metadata_version": package["metadata"]["version"] = "1.3.2-r0"
    elif defect == "missing_runtime": package["metadata"]["files"] = []
    elif defect == "wrong_sbom_id": sbom["source"]["metadata"]["imageID"] = "sha256:" + "c" * 64
    elif defect == "wrong_report_id": report["source"]["target"]["imageID"] = "sha256:" + "c" * 64
    elif defect == "wrong_tag": report["source"]["target"]["tags"] = [CADDY_PRODUCT]
    elif defect == "null_ignored": report["ignoredMatches"] = None
    elif defect == "object_ignored": report["ignoredMatches"] = {}
    elif defect == "suppression": report["ignoredMatches"] = [{"vulnerability": {"id": "CVE-other"}}]
    elif defect == "residual_cve": report["matches"] = [{"vulnerability": {"id": "CVE-2026-85091", "severity": "Medium"}}]
    elif defect in {"high", "critical"}: report["matches"] = [{"vulnerability": {"id": "CVE-other", "severity": defect}}]
    elif defect == "wrong_scanner": report["descriptor"]["version"] = "unreviewed"
    elif defect == "invalid_database": report["descriptor"]["db"]["status"]["valid"] = False
    else: raise AssertionError(defect)
    result = _verify_backend_fixed(tmp_path, sbom, report)
    assert result.returncode != 0, (defect, result.stdout)


def test_backend_fixed_mode_rejects_wrong_or_duplicate_service_binding(tmp_path: Path) -> None:
    sbom, report = _backend_r1_evidence()
    assert _verify_backend_fixed(tmp_path, sbom, report).returncode == 0
    base = [sys.executable, str(VEX_VERIFIER), "--backend-fixed-sbom", str(tmp_path / "backend.syft.json"), IMAGE_PRODUCT, IMAGE_ID]
    for reports in ([f"caddy={tmp_path / 'backend-grype.json'}"],
                    [f"backend={tmp_path / 'backend-grype.json'}"] * 2):
        result = subprocess.run(base + reports, text=True, capture_output=True, check=False)
        assert result.returncode != 0


@pytest.mark.parametrize("field,value", [
    ("package_version", "1.3.2-r1"), ("port_commit", "0" * 40),
    ("architecture", "aarch64"), ("distro_version", "3.24.3"),
    ("apk_signature_verified", False), ("runtime_version", "1.3.2"),
    ("runtime_path", "/usr/local/lib/libz.so.1.4"),
    ("proc_map", "/usr/lib/libz.so.1.3.2"), ("soname", "libz.so.2"),
    ("runtime_sha256", "f" * 64), ("apk_runtime_sha256", "f" * 64),
    ("apk_sha256", "not-a-hash"), ("manifest_algorithm", "md5"),
    ("manifest_digest", "d" * 39), ("unknown", True),
])
def test_caddy_rejects_stale_or_unreviewed_runtime_proof(tmp_path, field, value):
    runtime = _caddy_runtime_proof()
    runtime["proof"][field] = value
    result = _verify_caddy(tmp_path, _caddy_sbom(), _caddy_grype(), runtime)
    assert result.returncode != 0


@pytest.mark.parametrize("field,value", [
    ("image_id", "sha256:" + "f" * 64), ("image_product", "erp-caddy:other"),
    ("unknown", "untrusted"),
])
def test_caddy_runtime_proof_cannot_change_config_binding(tmp_path, field, value):
    runtime = _caddy_runtime_proof()
    runtime[field] = value
    assert _verify_caddy(tmp_path, _caddy_sbom(), _caddy_grype(), runtime).returncode != 0


def test_caddy_runtime_proof_rejects_duplicate_keys(tmp_path):
    runtime = _caddy_proof_file(tmp_path)
    runtime.write_text(runtime.read_text().replace('"runtime_version": "1.4"',
                        '"runtime_version": "1.4", "runtime_version": "1.4"'))
    sbom, report = tmp_path / "syft.json", tmp_path / "grype.json"
    sbom.write_text(json.dumps(_caddy_sbom())); report.write_text(json.dumps(_caddy_grype()))
    result = subprocess.run([sys.executable, str(VEX_VERIFIER), "--caddy-fixed-sbom",
        str(sbom), CADDY_PRODUCT, IMAGE_ID, f"caddy={report}", f"--runtime-proof={runtime}"],
        capture_output=True, text=True)
    assert result.returncode != 0 and "duplicate object key" in result.stderr


def test_caddy_cannot_omit_actual_runtime_binding(tmp_path):
    sbom, report = tmp_path / "syft.json", tmp_path / "grype.json"
    sbom.write_text(json.dumps(_caddy_sbom())); report.write_text(json.dumps(_caddy_grype()))
    result = subprocess.run([sys.executable, str(VEX_VERIFIER), "--caddy-fixed-sbom",
        str(sbom), CADDY_PRODUCT, IMAGE_ID, f"caddy={report}"], capture_output=True, text=True)
    assert result.returncode != 0 and "--runtime-proof" in result.stderr


@pytest.mark.parametrize("cve", ["CVE-2026-4738", "CVE-2026-85091"])
def test_caddy_official_package_rejects_residual_target_cve_at_any_severity(tmp_path, cve):
    report = _caddy_grype(matches=[{"vulnerability": {"id": cve, "severity": "Low"}}])
    result = _verify_caddy(tmp_path, _caddy_sbom(), report)
    assert result.returncode != 0 and f"Residual {cve}" in result.stderr


@pytest.mark.parametrize("defect", (None, "image", "tag", "scanner", "duplicate", "oversized", "linked"))
def test_grype_failure_summary_is_public_bounded_and_exactly_bound(tmp_path, defect):
    path = tmp_path / "grype.json"
    report = {
        "descriptor": {"name": "grype", "version": "0.118.0", "configuration": {"fail-on-severity": "high"}},
        "source": {"type": "image", "target": {"imageID": IMAGE_ID, "tags": [IMAGE_PRODUCT]}},
        "matches": [{"vulnerability": {"id": "CVE-2026-12345", "severity": "High",
                                        "description": "PRIVATE_DESCRIPTION", "fix": {"versions": ["2.0-r1"]}},
                     "artifact": {"name": "example-lib", "version": "1.0-r0", "locations": ["PRIVATE_PATH"]},
                     "matchDetails": [{"message": "PRIVATE_MESSAGE"}]}] * 100,
        "PRIVATE_UNKNOWN": "PRIVATE_VALUE",
    }
    if defect == "image": report["source"]["target"]["imageID"] = "sha256:" + "c" * 64
    if defect == "tag": report["source"]["target"]["tags"] = ["erp-backend:other"]
    if defect == "scanner": report["descriptor"]["version"] = "0.0.0"
    path.write_text(json.dumps(report))
    if defect == "duplicate": path.write_text(path.read_text().replace('"matches":', '"matches": [], "matches":', 1))
    if defect == "oversized":
        with path.open("wb") as stream: stream.truncate(64 * 1024 * 1024 + 1)
    if defect == "linked":
        real = tmp_path / "real.json"
        path.rename(real)
        path.symlink_to(real)
    result = subprocess.run([sys.executable, str(VEX_VERIFIER), "--scan-failure-summary", str(path), IMAGE_PRODUCT, IMAGE_ID],
                            text=True, capture_output=True)
    assert "PRIVATE" not in result.stdout
    if defect:
        assert result.returncode != 0
        assert result.stdout == ""
    else:
        assert result.returncode == 0, result.stderr
        output = json.loads(result.stdout)
        assert set(output) == {"diagnostic", "image_id", "image_product", "findings", "omitted"}
        assert len(output["findings"]) == 40 and output["omitted"] == 60
        assert output["image_id"] == IMAGE_ID and output["image_product"] == IMAGE_PRODUCT
        assert output["findings"][0] == {"id": "CVE-2026-12345", "package": "example-lib", "version": "1.0-r0",
                                         "severity": "High", "fix_versions": ["2.0-r1"]}
        assert len(result.stdout) < 64_000


SERVICE_PROFILES = {
    "backend": ("3.24.3", "bdf501c870a92a05c097857401f69c48c4810384"),
    "frontend": ("3.24.1", "bdf501c870a92a05c097857401f69c48c4810384"),
    "postgres": ("3.24.4", "bdf501c870a92a05c097857401f69c48c4810384"),
    "redis": ("3.21.8", "d3332621d44c2c4cf52e52217b16674db190212c"),
    "caddy": ("3.23.5", "0436c6d655a04cbfd7987202a970f5aa7870a901"),
}


@pytest.mark.parametrize("service", SERVICE_PROFILES)
@pytest.mark.parametrize("defect", (None, "service", "distro", "port", "config", "unknown", "duplicate", "missing", "residual"))
def test_all_official_profiles_require_exact_immutable_runtime_binding(tmp_path, service, defect):
    distro, port = SERVICE_PROFILES[service]
    product = f"erp-{service}:test"
    sbom, report, bound = _caddy_sbom(), _caddy_grype(), _caddy_runtime_proof()
    sbom["distro"]["versionID"] = distro
    package = sbom["artifacts"][0]
    package["purl"] = f"pkg:apk/alpine/zlib@1.4-r0?arch=x86_64&distro=alpine-{distro}"
    package["metadata"]["gitCommitOfApkPort"] = port
    report["source"]["target"]["tags"] = [product]
    bound["image_product"] = product
    bound["proof"].update({"service": service, "distro_version": distro, "port_commit": port})
    if defect == "service": bound["proof"]["service"] = "frontend" if service != "frontend" else "postgres"
    elif defect == "distro": bound["proof"]["distro_version"] = "0.0.0"
    elif defect == "port": bound["proof"]["port_commit"] = "0" * 40
    elif defect == "config": bound["image_id"] = "sha256:" + "f" * 64
    elif defect == "unknown": bound["proof"]["unreviewed"] = True
    elif defect == "residual": report["matches"] = [{"vulnerability": {"id": "CVE-2026-4738", "severity": "Low"}}]
    for name, value in (("sbom", sbom), ("report", report), ("proof", bound)):
        (tmp_path / f"{name}.json").write_text(json.dumps(value))
    if defect == "duplicate":
        path = tmp_path / "proof.json"
        path.write_text(path.read_text().replace('"service":', '"service":"unreviewed", "service":', 1))
    args = [sys.executable, str(VEX_VERIFIER), f"--{service}-fixed-sbom", str(tmp_path / "sbom.json"),
            product, IMAGE_ID, f"{service}={tmp_path / 'report.json'}"]
    if defect != "missing": args.append(f"--runtime-proof={tmp_path / 'proof.json'}")
    result = subprocess.run(args, text=True, capture_output=True)
    assert result.returncode == (0 if defect is None else 1), (service, defect, result.stderr)
    if defect is None:
        assert f"{service}_runtime_proof_sha256=" in result.stdout
        assert f"{service}_fixed_zlib_version=1.4-r0" in result.stdout


@pytest.mark.parametrize("service", SERVICE_PROFILES)
def test_official_runtime_binder_rejects_cross_service_raw_proof(tmp_path, service):
    raw = tmp_path / "raw.json"
    raw.write_text(json.dumps(_caddy_runtime_proof()["proof"]))
    output = tmp_path / "bound.json"
    # Caddy default is deliberately mismatched for every other profile; even
    # frontend/postgres's identical distro/port cannot cross service identity.
    requested = service if service != "caddy" else "unreviewed"
    result = subprocess.run([sys.executable, str(VEX_VERIFIER), "--bind-runtime-proof", requested,
                             str(raw), f"erp-{service}:test", IMAGE_ID, str(output)], text=True, capture_output=True)
    assert result.returncode != 0
    assert not output.exists()



def test_python_119170_high_remains_blocking_with_the_genuine_31410_pin(tmp_path):
    sbom, report = _backend_r1_evidence()
    report["matches"] = [{"vulnerability": {"id": "CVE-2026-119170", "severity": "High",
                                            "fix": {"versions": ["3.14.11"]}},
                          "artifact": {"name": "python", "version": "3.14.10"}}]
    result = _verify_backend_fixed(tmp_path, sbom, report)
    assert result.returncode != 0
    assert "high or critical finding" in result.stderr
    source = (ROOT / "infra/docker/backend.Dockerfile").read_text()
    assert 'com.dcompany.python.version="3.14.10"' in source
    assert "python:3.14.10-alpine3.24@sha256:" in source
    assert "3.14.11" not in source


@pytest.mark.parametrize("defect", (None, "wrong-image", "wrong-source", "wrong-module", "wrong-component", "extra-high", "extra-detail", "ignored-raw"))
def test_python_119170_requires_exact_real_backport_and_single_raw_finding(tmp_path, defect):
    from runpy import run_path

    verifier = run_path(str(VEX_VERIFIER))
    sbom, report = _backend_r1_evidence()
    proof = verifier["HASHLIB_PROOF"].copy()
    match = {
        "vulnerability": {"id": "CVE-2026-119170", "namespace": "nvd:cpe", "severity": "High",
                          "fix": {"versions": ["3.14.11"], "state": "fixed"}},
        "artifact": {"name": "python", "version": "3.14.10", "type": "binary", "purl": "pkg:generic/python@3.14.10",
                     "cpes": [f"cpe:2.3:a:{vendor}:python:3.14.10:*:*:*:*:*:*:*"
                              for vendor in ("python_software_foundation", "python")],
                     "locations": [{"path": path, "accessPath": path, "layerID": IMAGE_ID, "annotations": {"evidence": label}}
                                   for path, label in (("/usr/local/bin/python3.14", "primary"),
                                                       ("/usr/local/lib/libpython3.14.so.1.0", "supporting"))]},
        "relatedVulnerabilities": [],
    }
    match["matchDetails"] = [{
        "type": "cpe-match", "matcher": "stock-matcher",
        "searchedBy": {"namespace": "nvd:cpe", "cpes": [cpe], "package": {"name": "python", "version": "3.14.10"}},
        "found": {"vulnerabilityID": "CVE-2026-119170", "versionConstraint": ">= 3.14.10, < 3.14.11 (unknown)",
                  "cpes": [cpe.replace(":3.14.10:", ":*:")]},
        "fix": {"suggestedVersion": "3.14.11"},
    } for cpe in match["artifact"]["cpes"]]
    report["matches"] = [match]
    if defect == "wrong-image":
        report["source"]["target"]["imageID"] = "sha256:" + "b" * 64
    if defect == "wrong-source":
        proof["patched_source_sha256"] = "b" * 64
    if defect == "wrong-module":
        proof["module_sha256"] = "b" * 64
    if defect == "wrong-component":
        match["artifact"]["locations"][0]["path"] = "/unrelated/python"
    if defect == "extra-high":
        report["matches"].append({"vulnerability": {"id": "CVE-2026-155555", "severity": "Critical"}})
    if defect == "extra-detail":
        match["matchDetails"].append({"type": "other", "found": {"vulnerabilityID": "CVE-2026-155555"}})
    if defect == "ignored-raw":
        report["ignoredMatches"] = [match]
    names = {"raw": tmp_path / "backend-grype-unfiltered.json", "proof": tmp_path / "backend-hashlib-runtime.json",
             "vex": tmp_path / "backend-hashlib-fixed.openvex.json"}
    names["raw"].write_text(json.dumps(report))
    names["proof"].write_text(json.dumps(proof))
    raw_before = names["raw"].read_bytes()
    result = subprocess.run([sys.executable, str(VEX_VERIFIER), "--prove-fixed-hashlib", str(names["raw"]),
                             IMAGE_PRODUCT, IMAGE_ID, str(names["proof"]), str(names["vex"])],
                            text=True, capture_output=True, check=False)
    assert names["raw"].read_bytes() == raw_before
    assert result.returncode == (0 if defect is None else 1), result.stderr
    if defect is None:
        assert "backend_hashlib_raw_grype_sha256=" in result.stdout
        assert json.loads(names["vex"].read_text())["statements"][0]["status"] == "fixed"
        report["matches"] = []
        report["ignoredMatches"] = [{**match, "appliedIgnoreRules": [{"namespace": "vex", "vex-status": "fixed"}]}]
        report_path = tmp_path / "backend-filtered.json"
        report_path.write_text(json.dumps(report))
        verifier["validate_hashlib_vex"](names["vex"], IMAGE_PRODUCT, IMAGE_ID)
        verifier["validate_fixed_report"]("backend", report_path, IMAGE_PRODUCT, IMAGE_ID, hashlib_exception="fixed")
        report["ignoredMatches"][0]["appliedIgnoreRules"] = [{"namespace": "vex", "vex-status": "not_affected"}]
        report_path.write_text(json.dumps(report))
        with pytest.raises(SystemExit, match="exactly the verified hashlib fix"):
            verifier["validate_fixed_report"]("backend", report_path, IMAGE_PRODUCT, IMAGE_ID, hashlib_exception="fixed")
    else:
        assert not names["vex"].exists()
