from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys


ROOT = Path(__file__).resolve().parents[1]
VEX = ROOT / "infra/security/vex/zlib-cve-2026-85091.openvex.json"
VEX_GENERATOR = ROOT / "infra/scripts/generate-zlib-vex.py"
VEX_VERIFIER = ROOT / "infra/scripts/verify-zlib-vex.py"
IMAGE_PRODUCT = "erp-backend:test"
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


def _render_vex(tmp_path: Path) -> Path:
    tmp_path.mkdir(parents=True, exist_ok=True)
    path = tmp_path / "backend-zlib.openvex.json"
    if path.exists():
        return path
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
    )
    assert completed.returncode == 0, completed.stderr
    assert path.stat().st_mode & 0o777 == 0o444
    rendered = path.read_text(encoding="utf-8")
    assert "__D_COMPANY_" not in rendered
    assert IMAGE_PRODUCT in rendered
    assert IMAGE_ID in rendered
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
