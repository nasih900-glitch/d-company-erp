import ast
import hashlib
import shutil
from pathlib import Path
import subprocess

import pytest

from scripts.verify_code26_regression_freeze import (
    CODE25_BASE,
    CODE30_1_BASE,
    CODE30_2_BASE,
    CODE30_2_FREEZE_CONTROL_PATHS,
    CODE30_3_BASE,
    CODE30_3_FREEZE_CONTROL_PATHS,
    CODE30_4_BASE,
    CODE30_4_BUILD40_BASE,
    CODE30_4_BUILD41_BASE,
    CODE30_4_BUILD42_BASE,
    CODE30_4_BUILD43_BASE,
    CODE30_4_BUILD44_BASE,
    CODE30_4_BUILD45_BASE,
    CODE30_4_BUILD46_BASE,
    CODE30_4_BUILD47_BASE,
    CODE30_4_BUILD48_BASE,
    CODE30_4_BUILD49_BASE,
    CODE30_4_BUILD50_BASE,
    CODE30_4_FREEZE_CONTROL_PATHS,
    RegressionFreezeError,
    REVIEWED_CODE29_2_PRODUCTION_PATHS,
    REVIEWED_CODE29_2_TEST_SHA256,
    REVIEWED_CODE30_1_BUILD36_RELEASE_TEST_SHA256,
    REVIEWED_CODE30_1_DELETION_REPLAY_PRODUCTION_PATHS,
    REVIEWED_CODE30_1_DELETION_REPLAY_SHA256,
    REVIEWED_CODE30_1_FEATURE_SHA256,
    REVIEWED_CODE30_1_RELEASE_TEST_SHA256,
    REVIEWED_CODE30_1_PRODUCTION_PATHS,
    REVIEWED_CODE30_2_PRODUCTION_PATHS,
    REVIEWED_CODE30_2_SHA256,
    REVIEWED_CODE30_3_PRODUCTION_PATHS,
    REVIEWED_CODE30_3_SHA256,
    REVIEWED_CODE30_4_PRODUCTION_PATHS,
    REVIEWED_CODE30_4_HOTFIX_SHA256,
    REVIEWED_CODE30_4_BUILD41_SHA256,
    REVIEWED_CODE30_4_BUILD42_SHA256,
    REVIEWED_CODE30_4_BUILD43_SHA256,
    REVIEWED_CODE30_4_BUILD44_SHA256,
    REVIEWED_CODE30_4_BUILD45_SHA256,
    REVIEWED_CODE30_4_BUILD46_SHA256,
    REVIEWED_CODE30_4_BUILD47_SHA256,
    REVIEWED_CODE30_4_BUILD48_SHA256,
    REVIEWED_CODE30_4_BUILD49_SHA256,
    REVIEWED_CODE30_4_BUILD50_SHA256,
    REVIEWED_CODE30_4_BUILD51_SHA256,
    REVIEWED_CODE30_4_SHA256,
    REVIEWED_POST_CODE30_3_MAINTENANCE_SHA256,
    REVIEWED_CODE30_PRODUCTION_PATHS,
    REVIEWED_CODE30_TEST_SHA256,
    REVIEWED_PACKAGING_TEST_SHA256,
    REVIEWED_PACKAGING_UI_PATHS,
    REVIEWED_PRICING_TEST_SHA256,
    REVIEWED_WEB_AUTH_PATHS,
    _disable_counts,
    _missing_ordered_lines,
    _normalise_release_identity,
    _normalise_pos_notice_dynamic_state_host,
    _normalise_audit_reader_locator,
    _normalise_android_release_pipeline,
    _normalise_realtime_api_mock,
    _verify_post_code30_3_maintenance_bytes,
    _verify_code30_4_exact_delta,
    verify_repository,
)


ROOT = Path(__file__).resolve().parents[1]


def _git_object_bytes(ref: str, path: str) -> bytes:
    return subprocess.run(
        ["git", "show", f"{ref}:{path}"],
        cwd=ROOT,
        check=True,
        capture_output=True,
    ).stdout


def _reviewed_code30_point4_maps(source: bytes) -> dict[str, dict[str, str]]:
    tree = ast.parse(source.decode("utf-8"))
    maps: dict[str, dict[str, str]] = {}
    for node in tree.body:
        if not isinstance(node, ast.Assign) or len(node.targets) != 1:
            continue
        target = node.targets[0]
        if not isinstance(target, ast.Name):
            continue
        if not target.id.startswith("REVIEWED_CODE30_4") or not isinstance(node.value, ast.Dict):
            continue
        value = ast.literal_eval(node.value)
        if isinstance(value, dict):
            maps[target.id] = value
    return maps


def _current_reviewed_sha256(path: str, fallback: str) -> str:
    return REVIEWED_CODE30_4_BUILD51_SHA256.get(path) or REVIEWED_CODE30_4_BUILD50_SHA256.get(path) or REVIEWED_CODE30_4_BUILD49_SHA256.get(path) or REVIEWED_CODE30_4_BUILD48_SHA256.get(path) or REVIEWED_CODE30_4_BUILD47_SHA256.get(path) or REVIEWED_CODE30_4_BUILD46_SHA256.get(path) or REVIEWED_CODE30_4_BUILD45_SHA256.get(path) or REVIEWED_CODE30_4_BUILD44_SHA256.get(path) or REVIEWED_CODE30_4_BUILD43_SHA256.get(path) or REVIEWED_CODE30_4_BUILD42_SHA256.get(
        path,
        REVIEWED_CODE30_4_BUILD41_SHA256.get(
            path,
            REVIEWED_CODE30_4_HOTFIX_SHA256.get(
                path,
                REVIEWED_CODE30_4_SHA256.get(
                    path,
                    REVIEWED_CODE30_3_SHA256.get(
                        path, REVIEWED_CODE30_2_SHA256.get(path, fallback)
                    ),
                ),
            ),
        ),
    )


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


def test_current_release_identity_normalises_directly_to_code25_baseline() -> None:
    path = "android-native/app/src/test/java/cloud/dcompany/erp/AndroidReleaseIdentityTest.kt"
    current = 'assertEquals(26, BuildConfig.VERSION_CODE)\n"3.1.16"\ncode 26 artifact\n'
    assert _normalise_release_identity(path, current) == (
        'assertEquals(25, BuildConfig.VERSION_CODE)\n"3.1.14"\ncode 25 artifact\n'
    )


def test_code27_identity_normalises_directly_to_inherited_code25_baseline() -> None:
    path = "android-native/app/src/test/java/cloud/dcompany/erp/AndroidReleaseIdentityTest.kt"
    current = 'assertEquals(27, BuildConfig.VERSION_CODE)\n"3.1.17"\ncode 27 artifact\n'
    assert _normalise_release_identity(path, current) == (
        'assertEquals(25, BuildConfig.VERSION_CODE)\n"3.1.14"\ncode 25 artifact\n'
    )


def test_code28_identity_normalises_directly_to_inherited_code25_baseline() -> None:
    path = "android-native/app/src/test/java/cloud/dcompany/erp/AndroidReleaseIdentityTest.kt"
    current = 'assertEquals(28, BuildConfig.VERSION_CODE)\n"3.1.18"\ncode 28 artifact\n'
    assert _normalise_release_identity(path, current) == (
        'assertEquals(25, BuildConfig.VERSION_CODE)\n"3.1.14"\ncode 25 artifact\n'
    )


def test_code29_identity_normalises_directly_to_inherited_code25_baseline() -> None:
    path = "android-native/app/src/test/java/cloud/dcompany/erp/AndroidReleaseIdentityTest.kt"
    current = 'assertEquals(29, BuildConfig.VERSION_CODE)\n"3.1.20"\ncode 29 artifact\n'
    assert _normalise_release_identity(path, current) == (
        'assertEquals(25, BuildConfig.VERSION_CODE)\n"3.1.14"\ncode 25 artifact\n'
    )


def test_current_code29_patch_identity_normalises_directly_to_inherited_code25_baseline() -> None:
    path = "android-native/app/src/test/java/cloud/dcompany/erp/AndroidReleaseIdentityTest.kt"
    current = 'assertEquals(29, BuildConfig.VERSION_CODE)\n"3.1.21"\ncode 29 artifact\n'
    assert _normalise_release_identity(path, current) == (
        'assertEquals(25, BuildConfig.VERSION_CODE)\n"3.1.14"\ncode 25 artifact\n'
    )


def test_code29_point1_build30_identity_normalises_to_inherited_code25_baseline() -> None:
    path = "android-native/app/src/test/java/cloud/dcompany/erp/AndroidReleaseIdentityTest.kt"
    current = 'assertEquals(30, BuildConfig.VERSION_CODE)\n"3.1.22"\ncode 29 artifact\n'
    assert _normalise_release_identity(path, current) == (
        'assertEquals(25, BuildConfig.VERSION_CODE)\n"3.1.14"\ncode 25 artifact\n'
    )


def test_code29_point2_build31_identity_normalises_to_inherited_code25_baseline() -> None:
    path = "android-native/app/src/test/java/cloud/dcompany/erp/AndroidReleaseIdentityTest.kt"
    current = 'assertEquals(31, BuildConfig.VERSION_CODE)\n"3.1.23"\ncode 29 artifact\n'
    assert _normalise_release_identity(path, current) == (
        'assertEquals(25, BuildConfig.VERSION_CODE)\n"3.1.14"\ncode 25 artifact\n'
    )


def test_code30_build33_identity_normalises_to_inherited_code25_baseline() -> None:
    path = "android-native/app/src/test/java/cloud/dcompany/erp/AndroidReleaseIdentityTest.kt"
    current = 'assertEquals(33, BuildConfig.VERSION_CODE)\n"3.1.25"\ncode 30 artifact\n'
    assert _normalise_release_identity(path, current) == (
        'assertEquals(25, BuildConfig.VERSION_CODE)\n"3.1.14"\ncode 25 artifact\n'
    )


def test_code30_point1_build34_identity_normalises_to_inherited_code25_baseline() -> None:
    path = "android-native/app/src/test/java/cloud/dcompany/erp/AndroidReleaseIdentityTest.kt"
    current = 'assertEquals(34, BuildConfig.VERSION_CODE)\n"3.1.26"\ncode 30.1 artifact\n'
    assert _normalise_release_identity(path, current) == (
        'assertEquals(25, BuildConfig.VERSION_CODE)\n"3.1.14"\ncode 25 artifact\n'
    )


def test_code30_point1_retry_build35_identity_normalises_to_inherited_code25_baseline() -> None:
    path = "android-native/app/src/test/java/cloud/dcompany/erp/AndroidReleaseIdentityTest.kt"
    current = 'assertEquals(35, BuildConfig.VERSION_CODE)\n"3.1.27"\ncode 30.1 artifact\n'
    assert _normalise_release_identity(path, current) == (
        'assertEquals(25, BuildConfig.VERSION_CODE)\n"3.1.14"\ncode 25 artifact\n'
    )


def test_code30_point1_replay_build36_identity_normalises_to_inherited_code25_baseline() -> None:
    path = "android-native/app/src/test/java/cloud/dcompany/erp/AndroidReleaseIdentityTest.kt"
    current = 'assertEquals(36, BuildConfig.VERSION_CODE)\n"3.1.28"\ncode 30.1 artifact\n'
    assert _normalise_release_identity(path, current) == (
        'assertEquals(25, BuildConfig.VERSION_CODE)\n"3.1.14"\ncode 25 artifact\n'
    )


def test_code30_point2_build37_identity_normalises_to_inherited_code25_baseline() -> None:
    path = "android-native/app/src/test/java/cloud/dcompany/erp/AndroidReleaseIdentityTest.kt"
    current = 'assertEquals(37, BuildConfig.VERSION_CODE)\n"3.1.29"\ncode 30.2 artifact\n'
    assert _normalise_release_identity(path, current) == (
        'assertEquals(25, BuildConfig.VERSION_CODE)\n"3.1.14"\ncode 25 artifact\n'
    )


def test_code30_point3_build38_identity_normalises_to_inherited_code25_baseline() -> None:
    path = "android-native/app/src/test/java/cloud/dcompany/erp/AndroidReleaseIdentityTest.kt"
    current = 'assertEquals(38, BuildConfig.VERSION_CODE)\n"3.1.30"\ncode 30.3 artifact\n'
    assert _normalise_release_identity(path, current) == (
        'assertEquals(25, BuildConfig.VERSION_CODE)\n"3.1.14"\ncode 25 artifact\n'
    )


def test_code30_point4_build39_identity_normalises_to_inherited_code25_baseline() -> None:
    path = "android-native/app/src/test/java/cloud/dcompany/erp/AndroidReleaseIdentityTest.kt"
    current = 'assertEquals(39, BuildConfig.VERSION_CODE)\n"3.1.31"\ncode 30.4 artifact\n'
    assert _normalise_release_identity(path, current) == (
        'assertEquals(25, BuildConfig.VERSION_CODE)\n"3.1.14"\ncode 25 artifact\n'
    )


def test_code30_point4_corrective_build40_identity_normalises_to_inherited_code25_baseline() -> None:
    path = "android-native/app/src/test/java/cloud/dcompany/erp/AndroidReleaseIdentityTest.kt"
    current = 'assertEquals(40, BuildConfig.VERSION_CODE)\n"3.1.32"\ncode 30.4 artifact\n'
    assert _normalise_release_identity(path, current) == (
        'assertEquals(25, BuildConfig.VERSION_CODE)\n"3.1.14"\ncode 25 artifact\n'
    )


def test_code30_point4_control_deck_build41_identity_normalises_to_inherited_code25_baseline() -> None:
    path = "android-native/app/src/test/java/cloud/dcompany/erp/AndroidReleaseIdentityTest.kt"
    current = 'assertEquals(41, BuildConfig.VERSION_CODE)\n"3.1.33"\ncode 30.4 artifact\n'
    assert _normalise_release_identity(path, current) == (
        'assertEquals(25, BuildConfig.VERSION_CODE)\n"3.1.14"\ncode 25 artifact\n'
    )


def test_code30_point4_polish_build42_identity_normalises_to_inherited_code25_baseline() -> None:
    path = "android-native/app/src/test/java/cloud/dcompany/erp/AndroidReleaseIdentityTest.kt"
    current = 'assertEquals(42, BuildConfig.VERSION_CODE)\n"3.1.34"\ncode 30.4 artifact\n'
    assert _normalise_release_identity(path, current) == (
        'assertEquals(25, BuildConfig.VERSION_CODE)\n"3.1.14"\ncode 25 artifact\n'
    )


def test_code30_point4_security_build43_identity_normalises_to_inherited_code25_baseline() -> None:
    path = "android-native/app/src/test/java/cloud/dcompany/erp/AndroidReleaseIdentityTest.kt"
    current = 'assertEquals(43, BuildConfig.VERSION_CODE)\n"3.1.35"\ncode 30.4 artifact\n'
    assert _normalise_release_identity(path, current) == (
        'assertEquals(25, BuildConfig.VERSION_CODE)\n"3.1.14"\ncode 25 artifact\n'
    )
    assert _normalise_release_identity("backend/tests/unit/test_money.py", current) == current


def test_code30_point4_security_build44_identity_normalises_to_inherited_code25_baseline() -> None:
    path = "android-native/app/src/test/java/cloud/dcompany/erp/AndroidReleaseIdentityTest.kt"
    current = 'assertEquals(44, BuildConfig.VERSION_CODE)\n"3.1.36"\ncode 30.4 artifact\n'
    assert _normalise_release_identity(path, current) == (
        'assertEquals(25, BuildConfig.VERSION_CODE)\n"3.1.14"\ncode 25 artifact\n'
    )
    assert _normalise_release_identity("backend/tests/unit/test_money.py", current) == current


def test_code30_point4_quality_build45_identity_normalises_to_inherited_code25_baseline() -> None:
    path = "android-native/app/src/test/java/cloud/dcompany/erp/AndroidReleaseIdentityTest.kt"
    current = 'assertEquals(45, BuildConfig.VERSION_CODE)\n"3.1.37"\ncode 30.4 artifact\n'
    assert _normalise_release_identity(path, current) == (
        'assertEquals(25, BuildConfig.VERSION_CODE)\n"3.1.14"\ncode 25 artifact\n'
    )
    assert _normalise_release_identity("backend/tests/unit/test_money.py", current) == current


def test_code30_point4_operational_build46_identity_normalises_to_inherited_code25_baseline() -> None:
    path = "android-native/app/src/test/java/cloud/dcompany/erp/AndroidReleaseIdentityTest.kt"
    current = 'assertEquals(46, BuildConfig.VERSION_CODE)\n"3.1.38"\ncode 30.4 artifact\n'
    assert _normalise_release_identity(path, current) == (
        'assertEquals(25, BuildConfig.VERSION_CODE)\n"3.1.14"\ncode 25 artifact\n'
    )
    assert _normalise_release_identity("backend/tests/unit/test_money.py", current) == current


def test_code30_point4_release_build47_identity_normalises_to_inherited_code25_baseline() -> None:
    path = "android-native/app/src/test/java/cloud/dcompany/erp/AndroidReleaseIdentityTest.kt"
    current = 'assertEquals(47, BuildConfig.VERSION_CODE)\n"3.1.39"\ncode 30.4 artifact\n'
    assert _normalise_release_identity(path, current) == (
        'assertEquals(25, BuildConfig.VERSION_CODE)\n"3.1.14"\ncode 25 artifact\n'
    )
    assert _normalise_release_identity("backend/tests/unit/test_money.py", current) == current


def test_code30_point4_release_build48_identity_normalises_to_inherited_code25_baseline() -> None:
    path = "android-native/app/src/test/java/cloud/dcompany/erp/AndroidReleaseIdentityTest.kt"
    current = 'assertEquals(48, BuildConfig.VERSION_CODE)\n"3.1.40"\ncode 30.4 artifact\n'
    assert _normalise_release_identity(path, current) == (
        'assertEquals(25, BuildConfig.VERSION_CODE)\n"3.1.14"\ncode 25 artifact\n'
    )
    assert _normalise_release_identity("backend/tests/unit/test_money.py", current) == current


def test_code30_point4_release_build49_identity_normalises_to_inherited_code25_baseline() -> None:
    path = "android-native/app/src/test/java/cloud/dcompany/erp/AndroidReleaseIdentityTest.kt"
    current = 'assertEquals(49, BuildConfig.VERSION_CODE)\n"3.1.41"\ncode 30.4 artifact\n'
    assert _normalise_release_identity(path, current) == (
        'assertEquals(25, BuildConfig.VERSION_CODE)\n"3.1.14"\ncode 25 artifact\n'
    )
    assert _normalise_release_identity("backend/tests/unit/test_money.py", current) == current


def test_code30_point4_release_build50_identity_normalises_to_inherited_code25_baseline() -> None:
    path = "android-native/app/src/test/java/cloud/dcompany/erp/AndroidReleaseIdentityTest.kt"
    current = 'assertEquals(50, BuildConfig.VERSION_CODE)\n"3.1.42"\ncode 30.4 artifact\n'
    assert _normalise_release_identity(path, current) == (
        'assertEquals(25, BuildConfig.VERSION_CODE)\n"3.1.14"\ncode 25 artifact\n'
    )
    assert _normalise_release_identity("backend/tests/unit/test_money.py", current) == current


def test_pos_notice_dynamic_state_host_normalises_only_the_approved_bytes() -> None:
    path = (
        "android-native/app/src/androidTest/java/cloud/dcompany/erp/ui/screens/"
        "PosEmptyCatalogueUiTest.kt"
    )
    current = _git_object_bytes(CODE30_2_BASE, path).decode("utf-8")
    baseline = subprocess.run(
        ["git", "show", f"{CODE25_BASE}:{path}"],
        cwd=ROOT,
        check=True,
        capture_output=True,
        text=True,
    ).stdout
    normalised = _normalise_pos_notice_dynamic_state_host(path, current)

    assert normalised != current
    assert not _missing_ordered_lines(baseline, normalised)
    assert _normalise_pos_notice_dynamic_state_host("tests/other.kt", current) == current

    for mutated in (
        current + "\n",
        current.replace("                        state = state.value,", "", 1),
        current.replace(
            "                        state = state.value,",
            "                        state = state.value,\n                        state = state.value,",
            1,
        ),
    ):
        assert _normalise_pos_notice_dynamic_state_host(path, mutated) == mutated

    original_assertion = (
        '        compose.onNodeWithText("CONTINUE TO PAYMENT").assertDoesNotExist()'
    )
    assertion_mutation = current.replace(original_assertion, "", 1)
    assert _normalise_pos_notice_dynamic_state_host(path, assertion_mutation) == assertion_mutation
    assert _missing_ordered_lines(baseline, assertion_mutation) == [original_assertion]
    assert _normalise_pos_notice_dynamic_state_host(path, baseline) == baseline


def test_pipeline_normalisation_requires_the_exact_counted_transform() -> None:
    path = "tests/test_android_release_pipeline.py"
    current = (ROOT / path).read_text(encoding="utf-8")
    normalised = _normalise_android_release_pipeline(path, current)

    assert normalised != current
    assert (
        "        coordinated_job = workflow[coordinated_start:instrumentation_start]"
        in normalised
    )
    assert normalised.count(
        '            "needs: [coordinated-release-gates, android-instrumentation]",'
    ) == 2

    unexpected = current.replace(
        "class AndroidReleasePipelineTest",
        '            "needs: [coordinated-release-gates, production-image-gates, android-instrumentation]",\n\n'
        "class AndroidReleasePipelineTest",
        1,
    )
    assert _normalise_android_release_pipeline(path, unexpected) == unexpected


def test_disable_marker_counter_detects_new_skip_paths() -> None:
    baseline = "def test_money():\n    assert True\n"
    candidate = "@pytest.mark.skip\ndef test_money():\n    assert True\n"
    assert sum(_disable_counts(candidate)) > sum(_disable_counts(baseline))


@pytest.mark.parametrize(("path", "expected_sha256"), REVIEWED_PRICING_TEST_SHA256.items())
def test_pricing_test_rewrites_require_exact_reviewed_bytes(
    path: str,
    expected_sha256: str,
) -> None:
    current = (ROOT / path).read_bytes()
    current_expected = _current_reviewed_sha256(
        path,
        REVIEWED_CODE30_1_DELETION_REPLAY_SHA256.get(
            path,
            REVIEWED_CODE30_1_FEATURE_SHA256.get(
                path,
                REVIEWED_CODE30_TEST_SHA256.get(
                    path, REVIEWED_CODE29_2_TEST_SHA256.get(path, expected_sha256)
                ),
            ),
        ),
    )
    assert hashlib.sha256(current).hexdigest() == current_expected
    assert hashlib.sha256(current + b"\n").hexdigest() != current_expected


@pytest.mark.parametrize(("path", "expected_sha256"), REVIEWED_PACKAGING_TEST_SHA256.items())
def test_packaging_label_test_rewrite_requires_exact_reviewed_bytes(
    path: str,
    expected_sha256: str,
) -> None:
    current = (ROOT / path).read_bytes()
    current_expected = _current_reviewed_sha256(path, expected_sha256)
    assert hashlib.sha256(current).hexdigest() == current_expected
    assert hashlib.sha256(current + b"\n").hexdigest() != current_expected


@pytest.mark.parametrize(("path", "expected_sha256"), REVIEWED_CODE30_TEST_SHA256.items())
def test_code30_test_rewrites_require_exact_reviewed_bytes(
    path: str,
    expected_sha256: str,
) -> None:
    current = (ROOT / path).read_bytes()
    current_expected = _current_reviewed_sha256(
        path, REVIEWED_CODE30_1_DELETION_REPLAY_SHA256.get(path, expected_sha256)
    )
    assert hashlib.sha256(current).hexdigest() == current_expected
    assert hashlib.sha256(current + b"\n").hexdigest() != current_expected


@pytest.mark.parametrize(("path", "expected_sha256"), REVIEWED_CODE30_1_FEATURE_SHA256.items())
def test_code30_point1_reviewed_files_require_exact_bytes(
    path: str,
    expected_sha256: str,
) -> None:
    current = (ROOT / path).read_bytes()
    current_expected = _current_reviewed_sha256(
        path, REVIEWED_CODE30_1_DELETION_REPLAY_SHA256.get(path, expected_sha256)
    )
    assert hashlib.sha256(current).hexdigest() == current_expected
    assert hashlib.sha256(current + b"\n").hexdigest() != current_expected


@pytest.mark.parametrize(
    ("path", "expected_sha256"), REVIEWED_CODE30_1_DELETION_REPLAY_SHA256.items()
)
def test_code30_point1_deletion_replay_files_require_exact_bytes(
    path: str,
    expected_sha256: str,
) -> None:
    current = (ROOT / path).read_bytes()
    current_expected = _current_reviewed_sha256(path, expected_sha256)
    assert hashlib.sha256(current).hexdigest() == current_expected
    assert hashlib.sha256(current + b"\n").hexdigest() != current_expected


@pytest.mark.parametrize(
    ("path", "expected_sha256"), REVIEWED_CODE30_1_RELEASE_TEST_SHA256.items()
)
def test_code30_point1_release_tests_require_exact_reviewed_bytes(
    path: str,
    expected_sha256: str,
) -> None:
    current = (ROOT / path).read_bytes()
    current_expected = _current_reviewed_sha256(
        path,
        REVIEWED_CODE30_1_BUILD36_RELEASE_TEST_SHA256.get(path, expected_sha256),
    )
    assert hashlib.sha256(current).hexdigest() == current_expected
    assert hashlib.sha256(current + b"\n").hexdigest() != current_expected


@pytest.mark.parametrize(
    ("path", "expected_sha256"),
    REVIEWED_CODE30_1_BUILD36_RELEASE_TEST_SHA256.items(),
)
def test_code30_point1_build36_release_tests_require_exact_reviewed_bytes(
    path: str,
    expected_sha256: str,
) -> None:
    current = (ROOT / path).read_bytes()
    current_expected = _current_reviewed_sha256(path, expected_sha256)
    assert hashlib.sha256(current).hexdigest() == current_expected
    assert hashlib.sha256(current + b"\n").hexdigest() != current_expected


@pytest.mark.parametrize(("path", "expected_sha256"), REVIEWED_CODE30_2_SHA256.items())
def test_code30_point2_reviewed_delta_requires_exact_bytes(
    path: str,
    expected_sha256: str,
) -> None:
    historical = _git_object_bytes(CODE30_2_BASE, path)
    assert hashlib.sha256(historical).hexdigest() == expected_sha256
    assert hashlib.sha256(historical + b"\n").hexdigest() != expected_sha256


def test_code30_point2_delta_inventory_is_exact() -> None:
    changed = set(
        subprocess.run(
            ["git", "diff", "--name-only", CODE30_1_BASE, CODE30_2_BASE],
            cwd=ROOT,
            check=True,
            capture_output=True,
            text=True,
        ).stdout.splitlines()
    )
    assert {path for path in changed if path} == (
        set(REVIEWED_CODE30_2_SHA256) | set(CODE30_2_FREEZE_CONTROL_PATHS)
    )


@pytest.mark.parametrize(("path", "expected_sha256"), REVIEWED_CODE30_3_SHA256.items())
def test_code30_point3_reviewed_delta_requires_exact_bytes(
    path: str,
    expected_sha256: str,
) -> None:
    historical = _git_object_bytes(CODE30_3_BASE, path)
    assert hashlib.sha256(historical).hexdigest() == expected_sha256
    assert hashlib.sha256(historical + b"\n").hexdigest() != expected_sha256


def test_code30_point3_delta_inventory_is_exact() -> None:
    changed = set(
        subprocess.run(
            ["git", "diff", "--name-only", CODE30_2_BASE, CODE30_3_BASE],
            cwd=ROOT,
            check=True,
            capture_output=True,
            text=True,
        ).stdout.splitlines()
    )
    assert {path for path in changed if path} == (
        set(REVIEWED_CODE30_3_SHA256) | set(CODE30_3_FREEZE_CONTROL_PATHS)
    )


@pytest.mark.parametrize(("path", "expected_sha256"), REVIEWED_CODE30_4_SHA256.items())
def test_code30_point4_signed_base_retains_exact_reviewed_bytes(
    path: str,
    expected_sha256: str,
) -> None:
    signed = _git_object_bytes(CODE30_4_BASE, path)
    assert hashlib.sha256(signed).hexdigest() == expected_sha256
    assert hashlib.sha256(signed + b"\n").hexdigest() != expected_sha256


@pytest.mark.parametrize(
    ("path", "expected_sha256"), REVIEWED_CODE30_4_HOTFIX_SHA256.items()
)
def test_code30_point4_signed_build40_retains_exact_reviewed_bytes(
    path: str,
    expected_sha256: str,
) -> None:
    current = _git_object_bytes(CODE30_4_BUILD40_BASE, path)
    assert hashlib.sha256(current).hexdigest() == expected_sha256
    assert hashlib.sha256(current + b"\n").hexdigest() != expected_sha256


@pytest.mark.parametrize(
    ("path", "expected_sha256"), REVIEWED_CODE30_4_BUILD41_SHA256.items()
)
def test_code30_point4_build41_overlay_requires_exact_bytes(
    path: str,
    expected_sha256: str,
) -> None:
    current = _git_object_bytes(CODE30_4_BUILD41_BASE, path)
    assert hashlib.sha256(current).hexdigest() == expected_sha256
    assert hashlib.sha256(current + b"\n").hexdigest() != expected_sha256


@pytest.mark.parametrize(
    ("path", "expected_sha256"), REVIEWED_CODE30_4_BUILD42_SHA256.items()
)
def test_code30_point4_build42_overlay_requires_exact_bytes(
    path: str,
    expected_sha256: str,
) -> None:
    current = _git_object_bytes(CODE30_4_BUILD42_BASE, path)
    assert hashlib.sha256(current).hexdigest() == expected_sha256
    assert hashlib.sha256(current + b"\n").hexdigest() != expected_sha256


@pytest.mark.parametrize(
    ("path", "expected_sha256"), REVIEWED_CODE30_4_BUILD43_SHA256.items()
)
def test_code30_point4_build43_overlay_requires_exact_bytes(path: str, expected_sha256: str) -> None:
    current = _git_object_bytes(CODE30_4_BUILD43_BASE, path)
    assert hashlib.sha256(current).hexdigest() == expected_sha256
    assert hashlib.sha256(current + b"\n").hexdigest() != expected_sha256


@pytest.mark.parametrize(
    ("path", "expected_sha256"), REVIEWED_CODE30_4_BUILD44_SHA256.items()
)
def test_code30_point4_build44_overlay_requires_exact_bytes(path: str, expected_sha256: str) -> None:
    current = _git_object_bytes(CODE30_4_BUILD44_BASE, path)
    assert hashlib.sha256(current).hexdigest() == expected_sha256
    assert hashlib.sha256(current + b"\n").hexdigest() != expected_sha256


@pytest.mark.parametrize(
    ("path", "expected_sha256"), REVIEWED_CODE30_4_BUILD45_SHA256.items()
)
def test_code30_point4_build45_overlay_requires_exact_current_bytes(
    path: str, expected_sha256: str
) -> None:
    current = _git_object_bytes(CODE30_4_BUILD45_BASE, path)
    assert hashlib.sha256(current).hexdigest() == expected_sha256
    assert hashlib.sha256(current + b"\n").hexdigest() != expected_sha256


@pytest.mark.parametrize(
    ("path", "expected_sha256"), REVIEWED_CODE30_4_BUILD46_SHA256.items()
)
def test_code30_point4_build46_overlay_requires_exact_current_bytes(
    path: str, expected_sha256: str
) -> None:
    current = _git_object_bytes(CODE30_4_BUILD46_BASE, path)
    assert hashlib.sha256(current).hexdigest() == expected_sha256
    assert hashlib.sha256(current + b"\n").hexdigest() != expected_sha256


@pytest.mark.parametrize(
    ("path", "expected_sha256"), REVIEWED_CODE30_4_BUILD47_SHA256.items()
)
def test_code30_point4_build47_overlay_requires_exact_current_bytes(
    path: str, expected_sha256: str
) -> None:
    current = _git_object_bytes(CODE30_4_BUILD47_BASE, path)
    assert hashlib.sha256(current).hexdigest() == expected_sha256
    assert hashlib.sha256(current + b"\n").hexdigest() != expected_sha256


@pytest.mark.parametrize(
    ("path", "expected_sha256"), REVIEWED_CODE30_4_BUILD48_SHA256.items()
)
def test_code30_point4_build48_overlay_requires_exact_current_bytes(
    path: str, expected_sha256: str
) -> None:
    current = _git_object_bytes(CODE30_4_BUILD48_BASE, path)
    assert hashlib.sha256(current).hexdigest() == expected_sha256
    assert hashlib.sha256(current + b"\n").hexdigest() != expected_sha256


def test_code30_point4_maps_through_build50_are_immutable() -> None:
    historical = _reviewed_code30_point4_maps(
        _git_object_bytes(CODE30_4_BUILD50_BASE, "scripts/verify_code26_regression_freeze.py")
    )
    current = _reviewed_code30_point4_maps(
        (ROOT / "scripts/verify_code26_regression_freeze.py").read_bytes()
    )
    assert historical
    for name, reviewed_map in historical.items():
        assert current[name] == reviewed_map


@pytest.mark.parametrize(
    ("path", "expected_sha256"), REVIEWED_CODE30_4_BUILD49_SHA256.items()
)
def test_code30_point4_build49_overlay_requires_exact_current_bytes(
    path: str, expected_sha256: str
) -> None:
    current = _git_object_bytes(CODE30_4_BUILD49_BASE, path)
    assert hashlib.sha256(current).hexdigest() == expected_sha256
    assert hashlib.sha256(current + b"\n").hexdigest() != expected_sha256


@pytest.mark.parametrize(
    ("path", "expected_sha256"), REVIEWED_CODE30_4_BUILD50_SHA256.items()
)
def test_code30_point4_build50_overlay_requires_exact_current_bytes(
    path: str, expected_sha256: str
) -> None:
    current = _git_object_bytes(CODE30_4_BUILD50_BASE, path)
    assert hashlib.sha256(current).hexdigest() == expected_sha256
    assert hashlib.sha256(current + b"\n").hexdigest() != expected_sha256


def test_code30_point4_delta_inventory_is_exact() -> None:
    changed = set(
        subprocess.run(
            ["git", "diff", "--name-only", CODE30_3_BASE],
            cwd=ROOT,
            check=True,
            capture_output=True,
            text=True,
        ).stdout.splitlines()
    )
    changed.update(
        subprocess.run(
            ["git", "ls-files", "--others", "--exclude-standard"],
            cwd=ROOT,
            check=True,
            capture_output=True,
            text=True,
        ).stdout.splitlines()
    )
    assert {path for path in changed if path} == (
        set(REVIEWED_CODE30_4_SHA256)
        | set(CODE30_4_FREEZE_CONTROL_PATHS)
        | set(REVIEWED_POST_CODE30_3_MAINTENANCE_SHA256)
        | set(REVIEWED_CODE30_4_HOTFIX_SHA256)
        | set(REVIEWED_CODE30_4_BUILD41_SHA256)
        | set(REVIEWED_CODE30_4_BUILD42_SHA256)
        | set(REVIEWED_CODE30_4_BUILD43_SHA256)
        | set(REVIEWED_CODE30_4_BUILD44_SHA256)
        | set(REVIEWED_CODE30_4_BUILD45_SHA256)
        | set(REVIEWED_CODE30_4_BUILD46_SHA256)
        | set(REVIEWED_CODE30_4_BUILD47_SHA256)
        | set(REVIEWED_CODE30_4_BUILD48_SHA256)
        | set(REVIEWED_CODE30_4_BUILD49_SHA256)
        | set(REVIEWED_CODE30_4_BUILD50_SHA256)
        | set(REVIEWED_CODE30_4_BUILD51_SHA256)
    )


def test_code30_point4_build42_delta_inventory_is_exact() -> None:
    changed = set(
        subprocess.run(
            ["git", "diff", "--name-only", CODE30_4_BUILD41_BASE, CODE30_4_BUILD42_BASE],
            cwd=ROOT,
            check=True,
            capture_output=True,
            text=True,
        ).stdout.splitlines()
    )
    assert {path for path in changed if path} == (
        set(REVIEWED_CODE30_4_BUILD42_SHA256) | set(CODE30_4_FREEZE_CONTROL_PATHS)
    )


def test_code30_point4_build43_delta_inventory_is_exact() -> None:
    changed = set(
        subprocess.run(
            ["git", "diff", "--name-only", CODE30_4_BUILD42_BASE, CODE30_4_BUILD43_BASE],
            cwd=ROOT,
            check=True,
            capture_output=True,
            text=True,
        ).stdout.splitlines()
    )
    assert {path for path in changed if path} == (
        set(REVIEWED_CODE30_4_BUILD43_SHA256) | set(CODE30_4_FREEZE_CONTROL_PATHS)
    )


def test_code30_point4_build44_delta_inventory_is_exact() -> None:
    changed = set(
        subprocess.run(
            ["git", "diff", "--name-only", CODE30_4_BUILD43_BASE, CODE30_4_BUILD44_BASE],
            cwd=ROOT,
            check=True,
            capture_output=True,
            text=True,
        ).stdout.splitlines()
    )
    exact = {path for path in changed if path}
    assert len(exact) == 24
    assert exact == (
        set(REVIEWED_CODE30_4_BUILD44_SHA256) | set(CODE30_4_FREEZE_CONTROL_PATHS)
    )


def test_code30_point4_build45_delta_inventory_is_exact() -> None:
    changed = set(
        subprocess.run(
            ["git", "diff", "--name-only", CODE30_4_BUILD44_BASE, CODE30_4_BUILD45_BASE],
            cwd=ROOT,
            check=True,
            capture_output=True,
            text=True,
        ).stdout.splitlines()
    )
    assert {path for path in changed if path} == (
        set(REVIEWED_CODE30_4_BUILD45_SHA256) | set(CODE30_4_FREEZE_CONTROL_PATHS)
    )


def test_code30_point4_build46_delta_inventory_is_exact() -> None:
    changed = set(
        subprocess.run(
            ["git", "diff", "--name-only", CODE30_4_BUILD45_BASE, CODE30_4_BUILD46_BASE],
            cwd=ROOT,
            check=True,
            capture_output=True,
            text=True,
        ).stdout.splitlines()
    )
    assert {path for path in changed if path} == (
        set(REVIEWED_CODE30_4_BUILD46_SHA256) | set(CODE30_4_FREEZE_CONTROL_PATHS)
    )


def test_code30_point4_build47_delta_inventory_is_exact() -> None:
    changed = set(
        subprocess.run(
            ["git", "diff", "--name-only", CODE30_4_BUILD46_BASE, CODE30_4_BUILD47_BASE],
            cwd=ROOT,
            check=True,
            capture_output=True,
            text=True,
        ).stdout.splitlines()
    )
    assert {path for path in changed if path} == (
        set(REVIEWED_CODE30_4_BUILD47_SHA256) | set(CODE30_4_FREEZE_CONTROL_PATHS)
    )


def test_code30_point4_build48_delta_inventory_is_exact() -> None:
    changed = set(
        subprocess.run(
            ["git", "diff", "--name-only", CODE30_4_BUILD47_BASE, CODE30_4_BUILD48_BASE],
            cwd=ROOT,
            check=True,
            capture_output=True,
            text=True,
        ).stdout.splitlines()
    )
    assert {path for path in changed if path} == (
        set(REVIEWED_CODE30_4_BUILD48_SHA256) | set(CODE30_4_FREEZE_CONTROL_PATHS)
    )


def test_code30_point4_build49_delta_inventory_is_exact() -> None:
    changed = set(
        subprocess.run(
            ["git", "diff", "--name-only", CODE30_4_BUILD48_BASE, CODE30_4_BUILD49_BASE],
            cwd=ROOT,
            check=True,
            capture_output=True,
            text=True,
        ).stdout.splitlines()
    )
    assert {path for path in changed if path} == (
        set(REVIEWED_CODE30_4_BUILD49_SHA256) | set(CODE30_4_FREEZE_CONTROL_PATHS)
    )


def test_code30_point4_build50_delta_inventory_is_exact() -> None:
    changed = set(
        subprocess.run(
            ["git", "diff", "--name-only", CODE30_4_BUILD49_BASE, CODE30_4_BUILD50_BASE],
            cwd=ROOT, check=True, capture_output=True, text=True,
        ).stdout.splitlines()
    )
    assert {path for path in changed if path} == (
        set(REVIEWED_CODE30_4_BUILD50_SHA256) | set(CODE30_4_FREEZE_CONTROL_PATHS)
    )


def test_code30_point4_build51_delta_inventory_is_exact() -> None:
    changed = set(
        subprocess.run(
            ["git", "diff", "--name-only", CODE30_4_BUILD50_BASE],
            cwd=ROOT, check=True, capture_output=True, text=True,
        ).stdout.splitlines()
    )
    changed.update(
        subprocess.run(
            ["git", "ls-files", "--others", "--exclude-standard"],
            cwd=ROOT, check=True, capture_output=True, text=True,
        ).stdout.splitlines()
    )
    assert {path for path in changed if path} == (
        set(REVIEWED_CODE30_4_BUILD51_SHA256) | set(CODE30_4_FREEZE_CONTROL_PATHS)
    )


def test_post_code30_point3_maintenance_bytes_reject_a_mutation(tmp_path: Path) -> None:
    for path in REVIEWED_POST_CODE30_3_MAINTENANCE_SHA256:
        destination = tmp_path / path
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_bytes((ROOT / path).read_bytes())

    errors: list[str] = []
    _verify_post_code30_3_maintenance_bytes(tmp_path, errors)
    assert not errors

    changed_path = "infra/scripts/apply-code30-3-combined-cleanup.sql"
    changed_file = tmp_path / changed_path
    changed_file.write_bytes(changed_file.read_bytes() + b"\n")
    _verify_post_code30_3_maintenance_bytes(tmp_path, errors)
    assert errors == [
        "reviewed post-Code30.3 maintenance file differs from its approved bytes: "
        + changed_path
    ]


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


def test_realtime_fixture_normalisation_only_adds_required_auth_exports() -> None:
    path = "frontend/src/lib/realtime-lifecycle.test.ts"
    previous = "vi.mock('./api', () => ({ BASE_URL: '/api/v1', readAccessToken: () => 'test-token' }));"
    current = "vi.mock('./api', () => ({ BASE_URL: '/api/v1', readAccessToken: () => 'test-token', readSessionGeneration: () => 0, renewSessionAccessToken: async () => 'test-token' }));"
    assertion = "expect(TestSocket.instances).toHaveLength(2);"
    assert _normalise_realtime_api_mock(path, current) == previous
    assert _normalise_realtime_api_mock("frontend/src/lib/other.test.ts", current) == current
    assert not _missing_ordered_lines(
        previous + "\n" + assertion,
        _normalise_realtime_api_mock(path, current + "\n" + assertion),
    )


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
    assert {path for path in report.changed_production_files if path.startswith("frontend/src/")} == REVIEWED_WEB_AUTH_PATHS | {
        "frontend/src/modules/gaming/GamingScreen.tsx",
        "frontend/src/modules/gaming/gaming-tariff.test.ts",
    } | REVIEWED_PACKAGING_UI_PATHS | {
        path for path in REVIEWED_CODE29_2_PRODUCTION_PATHS if path.startswith("frontend/src/")
    } | {
        path for path in REVIEWED_CODE30_PRODUCTION_PATHS if path.startswith("frontend/src/")
    } | {
        path for path in REVIEWED_CODE30_1_PRODUCTION_PATHS if path.startswith("frontend/src/")
    } | {
        path
        for path in REVIEWED_CODE30_1_DELETION_REPLAY_PRODUCTION_PATHS
        if path.startswith("frontend/src/")
    } | {
        path
        for path in REVIEWED_CODE30_2_PRODUCTION_PATHS
        if path.startswith("frontend/src/")
    } | {
        path
        for path in REVIEWED_CODE30_3_PRODUCTION_PATHS
        if path.startswith("frontend/src/")
    } | {
        path
        for path in REVIEWED_CODE30_4_PRODUCTION_PATHS
        if path.startswith("frontend/src/")
    }


def test_build51_release_identity_normalisation_does_not_touch_business_tests() -> None:
    identity = "android-native/app/src/test/java/cloud/dcompany/erp/AndroidReleaseIdentityTest.kt"
    current = 'assertEquals(51, BuildConfig.VERSION_CODE)\n"3.1.43"\nversion_code = 51\n'
    expected = 'assertEquals(25, BuildConfig.VERSION_CODE)\n"3.1.14"\nversion_code=25\n'
    assert _normalise_release_identity(identity, current) == expected
    for business_test in (
        "backend/tests/unit/test_money.py",
        "backend/tests/integration/test_pos_refund_settlement.py",
        "android-native/app/src/test/java/cloud/dcompany/erp/ui/screens/gaming/GamingParticipantAmendmentPolicyTest.kt",
    ):
        assert _normalise_release_identity(business_test, current) == current


def test_all_historical_reviewed_literal_maps_remain_unchanged_at_build50() -> None:
    def literal_maps(source: bytes) -> dict[str, object]:
        return {
            node.targets[0].id: ast.literal_eval(node.value)
            for node in ast.parse(source.decode("utf-8")).body
            if isinstance(node, ast.Assign)
            and len(node.targets) == 1
            and isinstance(node.targets[0], ast.Name)
            and node.targets[0].id.startswith("REVIEWED_")
            and isinstance(node.value, ast.Dict)
        }

    historical = literal_maps(
        _git_object_bytes(CODE30_4_BUILD50_BASE, "scripts/verify_code26_regression_freeze.py")
    )
    current = literal_maps((ROOT / "scripts/verify_code26_regression_freeze.py").read_bytes())
    assert historical
    assert "REVIEWED_CODE30_4_BUILD50_SHA256" in historical
    for name, reviewed_map in historical.items():
        assert current[name] == reviewed_map, name
    assert current.keys() - historical.keys() == {"REVIEWED_CODE30_4_BUILD51_SHA256"}


@pytest.mark.parametrize(
    ("path", "expected_sha256"), REVIEWED_CODE30_4_BUILD51_SHA256.items()
)
def test_build51_reviewed_overlay_rejects_any_byte_change(
    path: str, expected_sha256: str
) -> None:
    current = (ROOT / path).read_bytes()
    assert hashlib.sha256(current).hexdigest() == expected_sha256
    assert hashlib.sha256(current + b"\n").hexdigest() != expected_sha256


@pytest.mark.parametrize("mutation", ("bytes", "removed", "linked", "untracked"))
def test_build51_exact_guard_refuses_changed_removed_linked_or_extra_source(
    tmp_path: Path, mutation: str
) -> None:
    candidate = tmp_path / "candidate"
    subprocess.run(
        ["git", "clone", "--shared", "--quiet", "--no-hardlinks", str(ROOT), str(candidate)],
        check=True, capture_output=True,
    )
    # Include the separately reviewed controls when this test runs before their commit.
    for path in CODE30_4_FREEZE_CONTROL_PATHS | {
        "tests/test_code26_physical_audit_lane.py",
        "docs/V3_1_43_RELEASE_FREEZE_REVIEW.md",
    }:
        destination = candidate / path
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(ROOT / path, destination)
    path = "backend/app/services/finance/tender_corrections.py"
    target = candidate / path
    if mutation == "bytes":
        target.write_bytes(target.read_bytes() + b"\n")
        expected = f"reviewed Code30.4 file differs from its approved bytes: {path}"
    elif mutation == "removed":
        target.unlink()
        expected = f"reviewed Code30.4 file was removed, linked, or non-regular: {path}"
    elif mutation == "linked":
        retained_bytes = target.read_bytes()
        target.unlink()
        external = tmp_path / "identical-external-source.py"
        external.write_bytes(retained_bytes)
        target.symlink_to(external)
        expected = f"reviewed Code30.4 file was removed, linked, or non-regular: {path}"
    else:
        path = "backend/app/services/finance/unreviewed_build51_source.py"
        (candidate / path).write_text("UNREVIEWED = True\n", encoding="utf-8")
        expected = f"unreviewed path entered the Code30.4 build-51 delta: {path}"
    errors: list[str] = []
    _verify_code30_4_exact_delta(candidate, errors)
    assert expected in errors
