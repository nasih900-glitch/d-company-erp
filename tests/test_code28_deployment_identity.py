from __future__ import annotations

import json
import subprocess
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
CODE27_SIGNED_BASE = "dd0a1626a4a0107b104a1098f4b36596e38b752c"
PROTECTED_APPLICATION_PREFIXES = (
    "backend/app/",
    "backend/alembic/",
    "frontend/src/",
    "android-native/app/src/main/",
    "android-native/app/schemas/",
    "android-native/audit-driver/src/",
)
REVIEWED_RELEASE_HARDENING_PATHS = {
    ".github/actions/scan-production-images/action.yml",
    ".github/workflows/ci.yml",
    ".github/workflows/release.yml",
    "infra/docker/caddy-build/go.mod",
    "infra/docker/caddy-build/go.sum",
    "infra/docker/caddy.Dockerfile",
    "infra/scripts/install-on-vm.sh",
    "infra/scripts/run-hardened-image-scanners.sh",
}


def _git(*arguments: str) -> str:
    return subprocess.run(
        ["git", *arguments],
        cwd=ROOT,
        check=True,
        capture_output=True,
        text=True,
    ).stdout


def _code27_file(path: str) -> str:
    return _git("show", f"{CODE27_SIGNED_BASE}:{path}")


def _replace_exact(source: str, replacements: tuple[tuple[str, str, int], ...]) -> str:
    for old, new, expected_count in replacements:
        assert source.count(old) == expected_count
        source = source.replace(old, new)
    return source


def _changed_files(*prefixes: str) -> set[str]:
    changed = set(
        _git("diff", "--name-only", CODE27_SIGNED_BASE, "--", *prefixes).splitlines()
    )
    changed.update(
        path
        for path in _git("ls-files", "--others", "--exclude-standard").splitlines()
        if path.startswith(prefixes)
    )
    return {path for path in changed if path}


def test_code28_preserves_signed_code27_application_behavior() -> None:
    assert _changed_files(*PROTECTED_APPLICATION_PREFIXES) == {
        "backend/app/__init__.py"
    }

    version_path = "backend/app/__init__.py"
    expected = _replace_exact(
        _code27_file(version_path),
        (('__version__ = "3.1.17"', '__version__ = "3.1.18"', 1),),
    )
    assert (ROOT / version_path).read_text(encoding="utf-8") == expected


def test_code28_release_identity_files_change_only_expected_values() -> None:
    replacements = {
        "android-native/app/build.gradle.kts": (
            ("versionCode = 27", "versionCode = 28", 1),
            ('versionName = "3.1.17"', 'versionName = "3.1.18"', 1),
        ),
        "backend/pyproject.toml": (("3.1.17", "3.1.18", 1),),
        "backend/app/__init__.py": (("3.1.17", "3.1.18", 1),),
        "frontend/package.json": (("3.1.17", "3.1.18", 1),),
        "frontend/package-lock.json": (("3.1.17", "3.1.18", 2),),
        "frontend/.env.example": (("3.1.17", "3.1.18", 1),),
        "docker-compose.prod.yml": (("3.1.17", "3.1.18", 6),),
        ".env.production.example": (
            ("APP_VERSION=3.1.17", "APP_VERSION=3.1.18", 1),
            (
                "Code 27 (3.1.17) is the current deployment-only corrective\n"
                "# candidate; it is not advertised until docs/CODE27_RELEASE_CANDIDATE.md passes.",
                "Code 28 (3.1.18) is the current scanner-hardening corrective\n"
                "# candidate; it is not advertised until docs/CODE28_RELEASE_CANDIDATE.md passes.",
                1,
            ),
        ),
    }
    for path, path_replacements in replacements.items():
        expected = _replace_exact(_code27_file(path), path_replacements)
        assert (ROOT / path).read_text(encoding="utf-8") == expected


def test_code28_scanner_deployment_scope_is_exact() -> None:
    assert _changed_files(
        ".github/actions/", ".github/workflows/", "infra/docker/", "infra/scripts/"
    ) == REVIEWED_RELEASE_HARDENING_PATHS


def test_code28_physical_lane_changes_identity_only() -> None:
    plan_path = "android-native/audit-driver/plans/code26-gaming-finance-physical.json"
    code27_plan = json.loads(_code27_file(plan_path))
    code28_plan = json.loads((ROOT / plan_path).read_text(encoding="utf-8"))
    assert len(code28_plan["steps"]) == 413
    assert code28_plan["expected_sessions"] == 16
    assert code28_plan["name"] == "Code 28 full-route Gaming and Finance physical acceptance"
    code28_plan["name"] = code27_plan["name"]
    assert code28_plan == code27_plan

    runner_path = "scripts/run_code26_physical_business_audit.sh"
    expected_runner = _replace_exact(
        _code27_file(runner_path),
        (
            ("Run the Code 27 business", "Run the Code 28 business", 1),
            ("after the Code 27 source-settled", "after the Code 28 source-settled", 1),
            ("a Code 27 result", "a Code 28 result", 1),
            (
                '"27" || "$VERSION_NAME" != "3.1.17"',
                '"28" || "$VERSION_NAME" != "3.1.18"',
                1,
            ),
            ("non-Code-27 source identity", "non-Code-28 source identity", 1),
            (
                "cloud.dcompany.erp.physicalaudit 27 3.1.17-physical-audit",
                "cloud.dcompany.erp.physicalaudit 28 3.1.18-physical-audit",
                1,
            ),
        ),
    )
    assert (ROOT / runner_path).read_text(encoding="utf-8") == expected_runner

    analyzer_path = "scripts/analyze_code26_physical_evidence.py"
    expected_analyzer = _replace_exact(
        _code27_file(analyzer_path),
        (
            ("Code 27 tablet business", "Code 28 tablet business", 1),
            (
                'payload.get("version_code") != 27 or payload.get("version_name") != "3.1.17"',
                'payload.get("version_code") != 28 or payload.get("version_name") != "3.1.18"',
                1,
            ),
            ("source is not Code 27 / 3.1.17", "source is not Code 28 / 3.1.18", 1),
            ('get("version_code") != "27"', 'get("version_code") != "28"', 1),
            (
                'get("version_name") != "3.1.17-physical-audit"',
                'get("version_name") != "3.1.18-physical-audit"',
                1,
            ),
            (
                "physicalAudit APK is not Code 27 / 3.1.17-physical-audit",
                "physicalAudit APK is not Code 28 / 3.1.18-physical-audit",
                1,
            ),
        ),
    )
    assert (ROOT / analyzer_path).read_text(encoding="utf-8") == expected_analyzer
