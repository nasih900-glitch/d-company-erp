#!/usr/bin/env python3
"""Fail closed when Code 26 weakens the proven Code 25 regression surface.

This is deliberately release-specific.  Code 25 is the behavioural baseline;
Code 26 may add tests and narrowly change the allow-listed Android failure
paths, but it may not delete, disable, reorder, or rewrite an existing test.
The sole reviewed audit-reader locator migration below preserves every
credential-cleanup assertion while following the corrected UTF-8 reader.
"""

from __future__ import annotations

import argparse
import json
import re
import subprocess
from dataclasses import asdict, dataclass
from pathlib import Path


CODE25_BASE = "715ba8c2671c7fbceb362ab59052a8a128b67668"

RELEASE_IDENTITY_TESTS = {
    "android-native/app/src/test/java/cloud/dcompany/erp/AndroidReleaseIdentityTest.kt",
    "backend/tests/unit/test_client_compatibility.py",
    "backend/tests/unit/test_release_audit_fixes.py",
    "backend/tests/unit/test_release_contracts.py",
    "backend/tests/unit/test_remote_assistance_contract.py",
    "backend/tests/unit/test_runtime_release_parity.py",
    "tests/test_android_runtime_parity.py",
}

ALLOWED_PRODUCTION_PATHS = {
    "backend/app/__init__.py",
    "android-native/app/src/main/java/cloud/dcompany/erp/DCompanyApp.kt",
    "android-native/app/src/main/java/cloud/dcompany/erp/PersistedStartupState.kt",
    "android-native/app/src/main/java/cloud/dcompany/erp/core/alarm/AlarmReceiver.kt",
    "android-native/app/src/main/java/cloud/dcompany/erp/core/alarm/AlarmRescheduleWorker.kt",
    "android-native/app/src/main/java/cloud/dcompany/erp/core/alarm/OperationalAlarmRuntime.kt",
    "android-native/app/src/main/java/cloud/dcompany/erp/core/auth/CacheScope.kt",
    "android-native/app/src/main/java/cloud/dcompany/erp/core/diagnostics/DiagnosticOutbox.kt",
    "android-native/app/src/main/java/cloud/dcompany/erp/core/money/MoneyInput.kt",
    "android-native/app/src/main/java/cloud/dcompany/erp/core/net/ApiClient.kt",
    "android-native/app/src/main/java/cloud/dcompany/erp/core/sync/BackgroundSyncWorker.kt",
    "android-native/app/src/main/java/cloud/dcompany/erp/ui/SessionViewModel.kt",
    "android-native/app/src/main/java/cloud/dcompany/erp/ui/components/Primitives.kt",
    "android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/PosScreen.kt",
    "android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/PosViewModel.kt",
    "android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/finance/FinanceScreen.kt",
    "android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/gaming/GamingScreen.kt",
    "android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/gaming/GamingViewModel.kt",
    "android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/settings/BugReportOutbox.kt",
}

PRODUCTION_PREFIXES = (
    "backend/app/",
    "frontend/src/",
    "android-native/app/src/main/",
)

DISABLING_PATTERNS = (
    re.compile(r"@pytest\.mark\.(?:skip|skipif|xfail)\b"),
    re.compile(r"@unittest\.(?:skip|skipIf|skipUnless)\b"),
    re.compile(r"\bpytest\.skip\s*\("),
    re.compile(r"@Ignore\b"),
    re.compile(r"\bAssume\.assume\w*\s*\("),
    re.compile(r"\b(?:describe|it|test)\.skip\s*\("),
)


@dataclass(frozen=True)
class RegressionFreezeReport:
    baseline_commit: str
    baseline_test_files: int
    preserved_test_files: int
    changed_production_files: tuple[str, ...]
    allowed_production_files: tuple[str, ...]


class RegressionFreezeError(RuntimeError):
    """Raised when the release no longer preserves its baseline."""


def _git(root: Path, *args: str) -> str:
    completed = subprocess.run(
        ["git", *args],
        cwd=root,
        check=True,
        capture_output=True,
        text=True,
    )
    return completed.stdout


def _is_baseline_test(path: str) -> bool:
    if path.startswith(("backend/tests/", "tests/")):
        return path.endswith(".py")
    if path.startswith(
        (
            "android-native/app/src/test/",
            "android-native/app/src/androidTest/",
        )
    ):
        return path.endswith((".kt", ".java"))
    if path.startswith("frontend/src/"):
        name = Path(path).name
        return any(marker in name for marker in (".test.", ".spec."))
    return False


def _normalise_release_identity(path: str, text: str) -> str:
    if path not in RELEASE_IDENTITY_TESTS:
        return text
    normalised = text
    for current, baseline in (
        ("3.1.16", "3.1.14"),
        ("3.1.15", "3.1.14"),
        ("Code 26", "Code 25"),
        ("code 26", "code 25"),
        ("CODE26", "CODE25"),
        ("code26", "code25"),
    ):
        normalised = normalised.replace(current, baseline)
    normalised = re.sub(r"version_code\s*=\s*26\b", "version_code=25", normalised)
    normalised = re.sub(
        r"assertEquals\(26,\s*BuildConfig\.VERSION_CODE\)",
        "assertEquals(25, BuildConfig.VERSION_CODE)",
        normalised,
    )
    return normalised


def _missing_ordered_lines(baseline: str, candidate: str) -> list[str]:
    candidate_iter = iter(candidate.splitlines())
    missing: list[str] = []
    for line in baseline.splitlines():
        if any(current == line for current in candidate_iter):
            continue
        missing.append(line)
        break
    return missing


def _normalise_audit_reader_locator(path: str, text: str) -> str:
    if path != "tests/test_android_audit_isolation.py":
        return text
    # Only this implementation locator changes. All cleanup assertions and
    # their ordering remain subject to the full baseline comparison.
    return text.replace(
        "        plan_read = source.index('JSONObject(readInstructionFile(planPath))', outer_try)",
        '        plan_read = source.index(\'JSONObject(device.executeShellCommand("cat $planPath"))\', outer_try)',
    )


def _disable_counts(text: str) -> tuple[int, ...]:
    return tuple(len(pattern.findall(text)) for pattern in DISABLING_PATTERNS)


def verify_repository(root: Path, baseline: str = CODE25_BASE) -> RegressionFreezeReport:
    root = root.resolve()
    _git(root, "cat-file", "-e", f"{baseline}^{{commit}}")
    baseline_paths = _git(root, "ls-tree", "-r", "--name-only", baseline).splitlines()
    test_paths = sorted(path for path in baseline_paths if _is_baseline_test(path))
    if not test_paths:
        raise RegressionFreezeError("Code 25 baseline contains no test files")

    errors: list[str] = []
    for path in test_paths:
        candidate_path = root / path
        if not candidate_path.is_file():
            errors.append(f"baseline test file was removed: {path}")
            continue
        baseline_text = _git(root, "show", f"{baseline}:{path}")
        candidate_text = candidate_path.read_text(encoding="utf-8")
        baseline_normalised = _normalise_release_identity(path, baseline_text)
        candidate_normalised = _normalise_release_identity(path, candidate_text)
        candidate_normalised = _normalise_audit_reader_locator(path, candidate_normalised)
        if _missing_ordered_lines(baseline_normalised, candidate_normalised):
            errors.append(f"baseline test was rewritten or reordered: {path}")
        baseline_disables = _disable_counts(baseline_text)
        candidate_disables = _disable_counts(candidate_text)
        if any(after > before for before, after in zip(baseline_disables, candidate_disables)):
            errors.append(f"baseline test gained a skip/xfail/ignore path: {path}")

    changed = set(
        _git(
            root,
            "diff",
            "--name-only",
            baseline,
            "--",
            *PRODUCTION_PREFIXES,
        ).splitlines()
    )
    changed.update(
        path
        for path in _git(root, "ls-files", "--others", "--exclude-standard").splitlines()
        if path.startswith(PRODUCTION_PREFIXES)
    )
    changed = {path for path in changed if path}
    deleted = {
        fields[-1]
        for line in _git(
            root,
            "diff",
            "--name-status",
            baseline,
            "--",
            *PRODUCTION_PREFIXES,
        ).splitlines()
        if (fields := line.split("\t")) and fields[0].startswith("D")
    }
    if deleted:
        errors.extend(f"production source was deleted: {path}" for path in sorted(deleted))
    unexpected = changed - ALLOWED_PRODUCTION_PATHS
    if unexpected:
        errors.extend(
            f"production source is outside the frozen Code 26 scope: {path}"
            for path in sorted(unexpected)
        )

    if errors:
        raise RegressionFreezeError("\n".join(errors))

    return RegressionFreezeReport(
        baseline_commit=baseline,
        baseline_test_files=len(test_paths),
        preserved_test_files=len(test_paths),
        changed_production_files=tuple(sorted(changed)),
        allowed_production_files=tuple(sorted(ALLOWED_PRODUCTION_PATHS)),
    )


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--baseline", default=CODE25_BASE)
    args = parser.parse_args()
    try:
        report = verify_repository(args.root, args.baseline)
    except (RegressionFreezeError, subprocess.CalledProcessError) as exc:
        print(json.dumps({"passed": False, "error": str(exc)}, indent=2))
        return 1
    print(json.dumps({"passed": True, **asdict(report)}, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
