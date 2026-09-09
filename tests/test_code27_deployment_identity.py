from __future__ import annotations

import json
import subprocess
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
CODE26_SIGNED_BASE = "6fa5544d30958453e7c70d3883d6ccac4bcabed8"
REVIEWED_WEB_AUTH_PATHS = {
    "frontend/src/lib/api.ts",
    "frontend/src/lib/realtime.ts",
    "frontend/src/lib/api-cookie-session-renewal.test.ts",
    "frontend/src/lib/api-session-renewal.test.ts",
    "frontend/src/lib/realtime-auth-renewal.test.ts",
    "frontend/src/lib/realtime-lifecycle.test.ts",
}
PROTECTED_APPLICATION_PREFIXES = (
    "backend/app/",
    "frontend/src/",
    "android-native/app/src/main/",
)


def _git(*arguments: str) -> str:
    return subprocess.run(
        ["git", *arguments],
        cwd=ROOT,
        check=True,
        capture_output=True,
        text=True,
    ).stdout


def _baseline_file(path: str) -> str:
    return _git("show", f"{CODE26_SIGNED_BASE}:{path}")


def _replace_once(source: str, old: str, new: str) -> str:
    assert source.count(old) == 1
    return source.replace(old, new)


def test_code27_preserves_signed_code26_application_behavior() -> None:
    changed_application_files = set(
        _git(
            "diff",
            "--name-only",
            CODE26_SIGNED_BASE,
            "--",
            *PROTECTED_APPLICATION_PREFIXES,
        ).splitlines()
    )
    changed_application_files.update(
        path
        for path in _git("ls-files", "--others", "--exclude-standard").splitlines()
        if path.startswith(PROTECTED_APPLICATION_PREFIXES)
    )
    assert changed_application_files == {
        "backend/app/__init__.py",
        *REVIEWED_WEB_AUTH_PATHS,
    }

    version_path = "backend/app/__init__.py"
    code26_version_source = _baseline_file(version_path)
    assert code26_version_source.count('__version__ = "3.1.16"') == 1
    expected_code27_source = code26_version_source.replace(
        '__version__ = "3.1.16"', '__version__ = "3.1.17"'
    )
    assert (ROOT / version_path).read_text(encoding="utf-8") == expected_code27_source


def test_code27_physical_lane_changes_identity_only() -> None:
    plan_path = "android-native/audit-driver/plans/code26-gaming-finance-physical.json"
    code26_plan = json.loads(_baseline_file(plan_path))
    code27_plan = json.loads((ROOT / plan_path).read_text(encoding="utf-8"))
    assert len(code27_plan["steps"]) == 413
    assert code27_plan["expected_sessions"] == 16
    assert code27_plan["name"] == "Code 27 full-route Gaming and Finance physical acceptance"
    code27_plan["name"] = code26_plan["name"]
    assert code27_plan == code26_plan

    runner_path = "scripts/run_code26_physical_business_audit.sh"
    expected_runner = _baseline_file(runner_path)
    for old, new in (
        ("Run the Code 26 business", "Run the Code 27 business"),
        ("after the Code 26 source-settled", "after the Code 27 source-settled"),
        ("a Code 26 result", "a Code 27 result"),
        ('"26" || "$VERSION_NAME" != "3.1.16"', '"27" || "$VERSION_NAME" != "3.1.17"'),
        ("non-Code-26 source identity", "non-Code-27 source identity"),
        (
            "cloud.dcompany.erp.physicalaudit 26 3.1.16-physical-audit",
            "cloud.dcompany.erp.physicalaudit 27 3.1.17-physical-audit",
        ),
    ):
        expected_runner = _replace_once(expected_runner, old, new)
    assert (ROOT / runner_path).read_text(encoding="utf-8") == expected_runner

    analyzer_path = "scripts/analyze_code26_physical_evidence.py"
    expected_analyzer = _baseline_file(analyzer_path)
    for old, new in (
        ("Code 26 tablet business", "Code 27 tablet business"),
        (
            'payload.get("version_code") != 26 or payload.get("version_name") != "3.1.16"',
            'payload.get("version_code") != 27 or payload.get("version_name") != "3.1.17"',
        ),
        ("source is not Code 26 / 3.1.16", "source is not Code 27 / 3.1.17"),
        ('get("version_code") != "26"', 'get("version_code") != "27"'),
        (
            'get("version_name") != "3.1.16-physical-audit"',
            'get("version_name") != "3.1.17-physical-audit"',
        ),
        (
            "physicalAudit APK is not Code 26 / 3.1.16-physical-audit",
            "physicalAudit APK is not Code 27 / 3.1.17-physical-audit",
        ),
    ):
        expected_analyzer = _replace_once(expected_analyzer, old, new)
    assert (ROOT / analyzer_path).read_text(encoding="utf-8") == expected_analyzer
