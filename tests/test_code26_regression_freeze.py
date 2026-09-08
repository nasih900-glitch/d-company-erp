from pathlib import Path

import pytest

from scripts.verify_code26_regression_freeze import (
    CODE25_BASE,
    RegressionFreezeError,
    _disable_counts,
    _missing_ordered_lines,
    _normalise_release_identity,
    _normalise_audit_reader_locator,
    verify_repository,
)


ROOT = Path(__file__).resolve().parents[1]


def test_ordered_baseline_lines_allow_additions_but_not_rewrites() -> None:
    baseline = "first\nsecond\nthird\n"
    assert not _missing_ordered_lines(baseline, "new\nfirst\nsecond\nextra\nthird\n")
    assert _missing_ordered_lines(baseline, "first\nchanged\nthird\n") == ["second"]


def test_release_identity_normalisation_is_limited_to_identity_tests() -> None:
    path = "android-native/app/src/test/java/cloud/dcompany/erp/AndroidReleaseIdentityTest.kt"
    current = 'assertEquals(26, BuildConfig.VERSION_CODE)\n"3.1.15"\ncode 26 artifact\n'
    assert _normalise_release_identity(path, current) == (
        'assertEquals(25, BuildConfig.VERSION_CODE)\n"3.1.14"\ncode 25 artifact\n'
    )
    assert _normalise_release_identity("backend/tests/unit/test_money.py", current) == current


def test_disable_marker_counter_detects_new_skip_paths() -> None:
    baseline = "def test_money():\n    assert True\n"
    candidate = "@pytest.mark.skip\ndef test_money():\n    assert True\n"
    assert sum(_disable_counts(candidate)) > sum(_disable_counts(baseline))


def test_audit_reader_migration_only_normalises_one_locator() -> None:
    path = "tests/test_android_audit_isolation.py"
    previous = '        plan_read = source.index(\'JSONObject(device.executeShellCommand("cat $planPath"))\', outer_try)'
    current = "        plan_read = source.index('JSONObject(readInstructionFile(planPath))', outer_try)"
    assertion = "        self.assertLess(outer_finally, cleanup)"
    assert _normalise_audit_reader_locator(path, current) == previous
    assert _normalise_audit_reader_locator("tests/other.py", current) == current
    assert not _missing_ordered_lines(
        previous + "\n" + assertion,
        _normalise_audit_reader_locator(path, current + "\n" + assertion),
    )
    assert _missing_ordered_lines(
        previous + "\n" + assertion,
        _normalise_audit_reader_locator(path, current),
    ) == [assertion]


def test_code26_preserves_the_complete_code25_test_surface() -> None:
    try:
        report = verify_repository(ROOT, CODE25_BASE)
    except RegressionFreezeError as exc:
        pytest.fail(str(exc))
    # Immutable Code 25 currently contains exactly 491 tracked test/support
    # sources across backend, web, Android JVM/instrumentation and root gates.
    # A matcher change must be reviewed explicitly rather than silently
    # shrinking the frozen baseline.
    assert report.baseline_test_files == 491
    assert report.preserved_test_files == report.baseline_test_files
    assert "frontend/src" not in "\n".join(report.changed_production_files)
