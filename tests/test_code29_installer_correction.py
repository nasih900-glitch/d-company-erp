from __future__ import annotations

import hashlib
import json
import subprocess
from pathlib import Path

import pytest


ROOT = Path(__file__).resolve().parents[1]
ORIGINAL_CODE29 = "0949620b4632ebd6accdfa62a203be8d85b31a24"
CADDY_SHA256 = "951a0136950bb9edf60ff5cec6ca2df0a041b27ded9e610f0aab49b168739dac"
LOCK_VERIFIER_SHA256 = "338cea91b56c2d85be109d259af1d77aa6d4ceaab4b13b379876c02e4485c029"
HISTORICAL_CODE29_GUARD_SHA256 = (
    "2d761a871369d891849e0170c85533a0269d209f7949b376300eb0994bd5fc15"
)
HISTORICAL_CADDY_GUARD_SHA256 = (
    "2395767f4fc278a45822bc2cc45f476c8a44e9211fc9db7b747196462579c881"
)
OPERATOR_RECORD_SHA256 = {
    "docs/CODE29_RELEASE_CANDIDATE.md": "8f15d3f031daef79ecd1a5680b8bf9f527598d558a004ea198225919898821e4",
    "docs/DISTRIBUTION.md": "601007e7eaca4b700c82e5e70ffd139a6ff9c9921e5c53581284491808710b1e",
    "docs/SERVER_DRIVEN_ANDROID_UPDATES.md": "5808e0f8e5d9eb0829c5e3e570bc3304028d2f828e858eb75c2cce3440afe583",
}
REVIEWED_UI_SHA256 = {
    "android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/PosScreen.kt": (
        "c2473b6b54f430d5cfcad724fcd7f51451ea3a5064839dd91c6e2000cd6a1f95"
    ),
    "android-native/app/src/androidTest/java/cloud/dcompany/erp/ui/screens/PosEmptyCatalogueUiTest.kt": (
        "b56a28fda657fd04490b5dd055e24c524747dd3ba5b1b2e5f34af79f7e907ef5"
    ),
    "android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/inventory/InventoryScreen.kt": (
        "ef0b0be225b11243a918cf503a4ef0df59fd5504150cdbf65f64bad67c03c79e"
    ),
    "android-native/app/src/androidTest/java/cloud/dcompany/erp/ui/screens/inventory/InventoryLoadedWorkspaceUiTest.kt": (
        "69d099d8bfa6df2d6db7a23d4f9ac70dd6a30d8729bd3ec3c5a1e586a5ab91af"
    ),
}
REVIEWED_WEB_SESSION_SHA256 = {
    "frontend/src/lib/api.ts": "9818163fcf287f512da6b7a74dc322fcf6a24ecd4bacb710077d33bfc9163084",
    "frontend/src/lib/api-cookie-session-renewal.test.ts": (
        "a0e8f10b66e0992a4ff0562c901c5f322bc8e3231c56cb9b150d897918cbd6ea"
    ),
    "frontend/src/lib/api-session-renewal.test.ts": (
        "03bde0984b2a6e09657d4f93bd5ea3b1e6be37d7b22345f235f6733b1b856129"
    ),
}

EXPECTED_CORRECTION_PATHS = {
    ".env.production.example",
    ".github/workflows/ci.yml",
    ".github/workflows/release.yml",
    "android-native/app/build.gradle.kts",
    "android-native/app/src/androidTest/java/cloud/dcompany/erp/ui/screens/PosEmptyCatalogueUiTest.kt",
    "android-native/app/src/androidTest/java/cloud/dcompany/erp/ui/screens/inventory/InventoryLoadedWorkspaceUiTest.kt",
    "android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/PosScreen.kt",
    "android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/inventory/InventoryScreen.kt",
    "android-native/app/src/test/java/cloud/dcompany/erp/AndroidReleaseIdentityTest.kt",
    "android-native/audit-driver/plans/code26-gaming-finance-physical.json",
    "backend/app/__init__.py",
    "backend/pyproject.toml",
    "backend/tests/unit/test_client_compatibility.py",
    "backend/tests/unit/test_release_audit_fixes.py",
    "backend/tests/unit/test_release_contracts.py",
    "backend/tests/unit/test_remote_assistance_contract.py",
    "backend/tests/unit/test_runtime_release_parity.py",
    "docker-compose.prod.yml",
    "docs/CODE29_RELEASE_CANDIDATE.md",
    "docs/DISTRIBUTION.md",
    "docs/SERVER_DRIVEN_ANDROID_UPDATES.md",
    "frontend/.env.example",
    "frontend/package-lock.json",
    "frontend/package.json",
    "frontend/src/lib/api-cookie-session-renewal.test.ts",
    "frontend/src/lib/api-session-renewal.test.ts",
    "frontend/src/lib/api.ts",
    "infra/scripts/install-on-vm.sh",
    "scripts/analyze_code26_physical_evidence.py",
    "scripts/run_code26_physical_business_audit.sh",
    "scripts/verify_code26_regression_freeze.py",
    "tests/test_android_release_version.py",
    "tests/test_android_runtime_parity.py",
    "tests/test_caddy_dependency_security.py",
    "tests/test_code26_physical_audit_lane.py",
    "tests/test_code26_regression_freeze.py",
    "tests/test_code29_deployment_identity.py",
    "tests/test_code29_installer_correction.py",
    "tests/verify_production_installer_lock_metadata.py",
}


def _git(*arguments: str) -> str:
    return subprocess.run(
        ["git", *arguments],
        cwd=ROOT,
        check=True,
        capture_output=True,
        text=True,
    ).stdout


def _original(path: str) -> str:
    return _git("show", f"{ORIGINAL_CODE29}:{path}")


def _current(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


def _replace_exact(source: str, replacements: tuple[tuple[str, str, int], ...]) -> str:
    for old, new, expected_count in replacements:
        assert source.count(old) == expected_count
        source = source.replace(old, new)
    return source


def _changed_paths() -> set[str]:
    changed = set(_git("diff", "--name-only", ORIGINAL_CODE29).splitlines())
    changed.update(_git("ls-files", "--others", "--exclude-standard").splitlines())
    return {path for path in changed if path}


def _assert_exact_text(path: str, actual: str, expected: str) -> None:
    assert actual == expected, f"unexpected corrected Code 29 content: {path}"


def _assert_sha256(path: str, content: bytes, expected: str) -> None:
    assert hashlib.sha256(content).hexdigest() == expected, (
        f"unexpected corrected Code 29 content: {path}"
    )


def _identity_expected(path: str) -> str:
    counts = {
        "android-native/app/build.gradle.kts": 1,
        "backend/pyproject.toml": 1,
        "backend/app/__init__.py": 1,
        "frontend/package.json": 1,
        "frontend/package-lock.json": 2,
        "frontend/.env.example": 1,
        "docker-compose.prod.yml": 6,
    }
    return _replace_exact(
        _original(path), (("3.1.19", "3.1.21", counts[path]),)
    )


def _workflow_step() -> str:
    return (
        "      - name: Verify production installer lock metadata\n"
        "        run: |\n"
        "          set -euo pipefail\n"
        '          test -n "$pythonLocation"\n'
        '          configured_python="$pythonLocation/bin/python"\n'
        '          test "${configured_python#/}" != "$configured_python"\n'
        '          test -x "$configured_python"\n'
        '          sudo -n -- "$configured_python" \\\n'
        "            tests/verify_production_installer_lock_metadata.py\n"
    )


def _workflow_expected(path: str) -> str:
    anchors = {
        ".github/workflows/ci.yml": (
            "          pip install --only-binary=:all: --require-hashes "
            "-r ops/backup-requirements.lock\n"
        ),
        ".github/workflows/release.yml": (
            "          python -m pip_audit -r ops/backup-requirements.lock\n"
        ),
    }
    anchor = anchors[path]
    return _replace_exact(_original(path), ((anchor, anchor + _workflow_step(), 1),))


def test_live_delta_is_exactly_the_reviewed_code29_correction() -> None:
    assert _changed_paths() == EXPECTED_CORRECTION_PATHS
    protected_application_changes = {
        path
        for path in _changed_paths()
        if path.startswith(("backend/app/", "frontend/src/", "android-native/app/src/main/"))
    }
    assert protected_application_changes == {
        "backend/app/__init__.py",
        "frontend/src/lib/api-cookie-session-renewal.test.ts",
        "frontend/src/lib/api-session-renewal.test.ts",
        "frontend/src/lib/api.ts",
        "android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/PosScreen.kt",
        "android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/inventory/InventoryScreen.kt",
    }


def test_live_coordinated_identity_is_version_name_3_1_21_with_code_29() -> None:
    for path in (
        "android-native/app/build.gradle.kts",
        "backend/pyproject.toml",
        "backend/app/__init__.py",
        "frontend/package.json",
        "frontend/package-lock.json",
        "frontend/.env.example",
        "docker-compose.prod.yml",
    ):
        _assert_exact_text(path, _current(path), _identity_expected(path))

    build = _current("android-native/app/build.gradle.kts")
    assert build.count("versionCode = 29") == 1
    assert build.count('versionName = "3.1.21"') == 1

    env_replacements = (
        ("APP_VERSION=3.1.19", "APP_VERSION=3.1.21", 1),
        (
            "# immutable history. Signed Code 28 (3.1.18) failed its production image-identity\n"
            "# gate before maintenance or cutover and was never staged or offered. Code 29\n"
            "# (3.1.19) is an unsigned corrective candidate; it is not advertised unless every\n"
            "# gate in docs/CODE29_RELEASE_CANDIDATE.md passes for its exact source and artifacts.",
            "# immutable history. Original signed Code 29 (3.1.19) failed its production\n"
            "# installer lock gate before builds or maintenance and was never staged or offered.\n"
            "# Code 29 (3.1.20) was cancelled before build/signing after the POS notice defect.\n"
            "# Current Code 29 (3.1.21) carries the reviewed incremental UI corrections and is\n"
            "# not advertised unless every gate in docs/CODE29_RELEASE_CANDIDATE.md passes for\n"
            "# its exact source and artifacts.",
            1,
        ),
    )
    expected_env = _replace_exact(_original(".env.production.example"), env_replacements)
    _assert_exact_text(".env.production.example", _current(".env.production.example"), expected_env)
    assert "ANDROID_MIN_SUPPORTED_VERSION_CODE=8" in expected_env
    assert "ANDROID_LATEST_VERSION_CODE=8" in expected_env
    assert "CLIENT_COMPATIBILITY_POLICY_REVISION=1" in expected_env


def test_live_identity_fixtures_are_exact_counted_transformations() -> None:
    replacements = {
        "android-native/app/src/test/java/cloud/dcompany/erp/AndroidReleaseIdentityTest.kt": (
            ("3.1.19", "3.1.21", 2),
        ),
        "backend/tests/unit/test_client_compatibility.py": (("3.1.19", "3.1.21", 1),),
        "backend/tests/unit/test_release_audit_fixes.py": (("3.1.19", "3.1.21", 1),),
        "backend/tests/unit/test_release_contracts.py": (("3.1.19", "3.1.21", 1),),
        "backend/tests/unit/test_remote_assistance_contract.py": (("3.1.19", "3.1.21", 4),),
        "backend/tests/unit/test_runtime_release_parity.py": (("3.1.19", "3.1.21", 6),),
        "tests/test_android_runtime_parity.py": (("3.1.19", "3.1.21", 2),),
        "tests/test_code26_physical_audit_lane.py": (("3.1.19", "3.1.21", 2),),
    }
    for path, path_replacements in replacements.items():
        expected = _replace_exact(_original(path), path_replacements)
        _assert_exact_text(path, _current(path), expected)


def test_original_release_name_tests_remain_and_patch_cases_are_exactly_added() -> None:
    path = "tests/test_android_release_version.py"
    anchor = "    def test_tag_must_match_version_name_exactly(self) -> None:\n"
    addition = (
        "    def test_code29_installer_correction_accepts_v3_1_20(self) -> None:\n"
        "        version = read_gradle_version(\n"
        "            self.write_build_file(version_code=\"29\", version_name='\"3.1.20\"')\n"
        "        )\n\n"
        "        validate_tag(\"v3.1.20\", version)\n"
        "        validate_built_metadata(\n"
        "            self.write_metadata(version_code=29, version_name=\"3.1.20\"), version\n"
        "        )\n\n"
        "        self.assertEqual(AndroidVersion(code=29, name=\"3.1.20\"), version)\n\n"
        "    def test_code29_installer_correction_rejects_original_package_identity(self) -> None:\n"
        "        version = read_gradle_version(\n"
        "            self.write_build_file(version_code=\"29\", version_name='\"3.1.20\"')\n"
        "        )\n\n"
        "        with self.assertRaisesRegex(ReleaseVersionError, \"expected 'v3.1.20'\"):\n"
        "            validate_tag(\"v3.1.19\", version)\n"
        "        with self.assertRaisesRegex(ReleaseVersionError, \"does not match\"):\n"
        "            validate_built_metadata(\n"
        "                self.write_metadata(version_code=29, version_name=\"3.1.19\"),\n"
        "                version,\n"
            "            )\n\n"
    )
    current_addition = (
        "    def test_code29_ui_patch_accepts_v3_1_21(self) -> None:\n"
        "        version = read_gradle_version(\n"
        "            self.write_build_file(version_code=\"29\", version_name='\"3.1.21\"')\n"
        "        )\n\n"
        "        validate_tag(\"v3.1.21\", version)\n"
        "        validate_built_metadata(\n"
        "            self.write_metadata(version_code=29, version_name=\"3.1.21\"), version\n"
        "        )\n\n"
        "        self.assertEqual(AndroidVersion(code=29, name=\"3.1.21\"), version)\n\n"
        "    def test_code29_ui_patch_rejects_prior_package_identity(self) -> None:\n"
        "        version = read_gradle_version(\n"
        "            self.write_build_file(version_code=\"29\", version_name='\"3.1.21\"')\n"
        "        )\n\n"
        "        with self.assertRaisesRegex(ReleaseVersionError, \"expected 'v3.1.21'\"):\n"
        "            validate_tag(\"v3.1.20\", version)\n"
        "        with self.assertRaisesRegex(ReleaseVersionError, \"does not match\"):\n"
        "            validate_built_metadata(\n"
        "                self.write_metadata(version_code=29, version_name=\"3.1.20\"),\n"
        "                version,\n"
        "            )\n\n"
    )
    expected = _replace_exact(
        _original(path), ((anchor, addition + current_addition + anchor, 1),)
    )
    _assert_exact_text(path, _current(path), expected)
    assert "test_code29_image_identity_correction_accepts_v3_1_19" in expected


def test_installer_verifier_and_live_workflows_are_exact() -> None:
    installer_replacements = (
        (
            "lock_fd_metadata=$(stat -Lc '%u:%g:%a:%h:%F:%d:%i' \"/proc/$$/fd/9\")\n"
            'expected_lock_fd_metadata="0:0:600:1:regular file:${DCOMPANY_PRODUCTION_INSTALL_LOCK_ID}"',
            "# GNU stat's raw mode is independent of file contents and localized type names;\n"
            "# 8180 is the exact Linux mode for a regular file with permissions 0600.\n"
            "lock_fd_metadata=$(stat -Lc '%u:%g:%a:%h:%f:%d:%i' \"/proc/$$/fd/9\")\n"
            'expected_lock_fd_metadata="0:0:600:1:8180:${DCOMPANY_PRODUCTION_INSTALL_LOCK_ID}"',
            1,
        ),
    )
    expected_installer = _replace_exact(
        _original("infra/scripts/install-on-vm.sh"), installer_replacements
    )
    _assert_exact_text(
        "infra/scripts/install-on-vm.sh",
        _current("infra/scripts/install-on-vm.sh"),
        expected_installer,
    )
    assert hashlib.sha256(
        (ROOT / "tests/verify_production_installer_lock_metadata.py").read_bytes()
    ).hexdigest() == LOCK_VERIFIER_SHA256

    for path in (".github/workflows/ci.yml", ".github/workflows/release.yml"):
        current = _current(path)
        _assert_exact_text(path, current, _workflow_expected(path))
        assert current.count(_workflow_step()) == 1
        assert current.count(CADDY_SHA256) == 1
        assert "continue-on-error" not in _workflow_step()

    assert _current(".github/actions/scan-production-images/action.yml") == _original(
        ".github/actions/scan-production-images/action.yml"
    )


def test_physical_lane_keeps_413_steps_and_changes_identity_only() -> None:
    plan_path = "android-native/audit-driver/plans/code26-gaming-finance-physical.json"
    original_plan = json.loads(_original(plan_path))
    corrected_plan = json.loads(_current(plan_path))
    assert len(corrected_plan["steps"]) == 413
    assert corrected_plan["expected_sessions"] == 16
    assert corrected_plan["name"] == "Code 29 3.1.21 full-route Gaming and Finance physical acceptance"
    corrected_plan["name"] = original_plan["name"]
    assert corrected_plan == original_plan

    replacements = {
        "scripts/run_code26_physical_business_audit.sh": (("3.1.19", "3.1.21", 2),),
        "scripts/analyze_code26_physical_evidence.py": (("3.1.19", "3.1.21", 4),),
    }
    for path, path_replacements in replacements.items():
        expected = _replace_exact(_original(path), path_replacements)
        _assert_exact_text(path, _current(path), expected)


def test_freeze_extensions_and_historical_guards_are_exact() -> None:
    freeze_script_replacements = (
        (
            "import argparse\nimport json\n",
            "import argparse\nimport hashlib\nimport json\n",
            1,
        ),
        (
            'CODE25_BASE = "715ba8c2671c7fbceb362ab59052a8a128b67668"\n\n',
            'CODE25_BASE = "715ba8c2671c7fbceb362ab59052a8a128b67668"\n'
            'POS_NOTICE_TEST_PATH = (\n'
            '    "android-native/app/src/androidTest/java/cloud/dcompany/erp/ui/screens/"\n'
            '    "PosEmptyCatalogueUiTest.kt"\n'
            ')\n'
            'POS_NOTICE_TEST_SHA256 = (\n'
            '    "b56a28fda657fd04490b5dd055e24c524747dd3ba5b1b2e5f34af79f7e907ef5"\n'
            ')\n\n',
            1,
        ),
        (
            '"""Fail closed when Code 26/27/28/29 weakens the proven Code 25 regression surface.',
            '"""Fail closed when Code 26 through current Code 29 weakens the proven Code 25 regression surface.',
            1,
        ),
        (
            "expiry repair. Code 28 hardens the release scanner path. Code 29 carries only\n"
            "the reviewed image-format and Android quantity corrections. None may delete,\n"
            "disable, reorder, or rewrite an existing",
            "expiry repair. Code 28 hardens the release scanner path. Original Code 29\n"
            "carries the reviewed image-format and Android quantity corrections; corrected\n"
            "Code 29 adds coordinated identity and the reviewed installer lock path. Current\n"
            "Code 29 adds only the reviewed POS-notice, inventory-layout, and Web session\n"
            "corrections. None may delete, disable, reorder, or rewrite an existing",
            1,
        ),
        (
            '    for current, baseline in (\n        ("3.1.19", "3.1.14"),',
            '    for current, baseline in (\n        ("3.1.21", "3.1.14"),\n'
            '        ("3.1.20", "3.1.14"),\n'
            '        ("3.1.19", "3.1.14"),',
            1,
        ),
        (
            "\n\ndef _missing_ordered_lines(baseline: str, candidate: str) -> list[str]:\n",
            "\n\ndef _normalise_pos_notice_dynamic_state_host(path: str, text: str) -> str:\n"
            "    if path != POS_NOTICE_TEST_PATH:\n"
            "        return text\n"
            "    if hashlib.sha256(text.encode(\"utf-8\")).hexdigest() != POS_NOTICE_TEST_SHA256:\n"
            "        return text\n"
            "    replacements = (\n"
            "        (\"                        state = state.value,\", \"                        state = state,\"),\n"
            "        (\n"
            "            \"                        onDismissNotice = onDismissNotice,\",\n"
            "            \"                        onDismissNotice = {},\",\n"
            "        ),\n"
            "    )\n"
            "    if any(text.count(current) != 1 for current, _ in replacements):\n"
            "        return text\n"
            "    for current, baseline in replacements:\n"
            "        text = text.replace(current, baseline)\n"
            "    return text\n"
            "\n\ndef _missing_ordered_lines(baseline: str, candidate: str) -> list[str]:\n",
            1,
        ),
        (
            "        candidate_normalised = _normalise_release_identity(path, candidate_text)\n"
            "        candidate_normalised = _normalise_audit_reader_locator(path, candidate_normalised)\n",
            "        candidate_normalised = _normalise_release_identity(path, candidate_text)\n"
            "        candidate_normalised = _normalise_pos_notice_dynamic_state_host(\n"
            "            path, candidate_normalised\n"
            "        )\n"
            "        candidate_normalised = _normalise_audit_reader_locator(path, candidate_normalised)\n",
            1,
        ),
    )
    expected_script = _replace_exact(
        _original("scripts/verify_code26_regression_freeze.py"),
        freeze_script_replacements,
    )
    _assert_exact_text(
        "scripts/verify_code26_regression_freeze.py",
        _current("scripts/verify_code26_regression_freeze.py"),
        expected_script,
    )

    freeze_test_path = "tests/test_code26_regression_freeze.py"
    freeze_test_anchor = "def test_pipeline_normalisation_requires_the_exact_counted_transform() -> None:\n"
    freeze_test_addition = (
        "def test_current_code29_patch_identity_normalises_directly_to_inherited_code25_baseline() -> None:\n"
        "    path = \"android-native/app/src/test/java/cloud/dcompany/erp/AndroidReleaseIdentityTest.kt\"\n"
        "    current = 'assertEquals(29, BuildConfig.VERSION_CODE)\\n\"3.1.21\"\\ncode 29 artifact\\n'\n"
        "    assert _normalise_release_identity(path, current) == (\n"
        "        'assertEquals(25, BuildConfig.VERSION_CODE)\\n\"3.1.14\"\\ncode 25 artifact\\n'\n"
        "    )\n\n\n"
    )
    normalizer_test_addition = (
        "def test_pos_notice_dynamic_state_host_normalises_only_the_approved_bytes() -> None:\n"
        "    path = (\n"
        "        \"android-native/app/src/androidTest/java/cloud/dcompany/erp/ui/screens/\"\n"
        "        \"PosEmptyCatalogueUiTest.kt\"\n"
        "    )\n"
        "    current = (ROOT / path).read_text(encoding=\"utf-8\")\n"
        "    baseline = subprocess.run(\n"
        "        [\"git\", \"show\", f\"{CODE25_BASE}:{path}\"],\n"
        "        cwd=ROOT,\n"
        "        check=True,\n"
        "        capture_output=True,\n"
        "        text=True,\n"
        "    ).stdout\n"
        "    normalised = _normalise_pos_notice_dynamic_state_host(path, current)\n\n"
        "    assert normalised != current\n"
        "    assert not _missing_ordered_lines(baseline, normalised)\n"
        "    assert _normalise_pos_notice_dynamic_state_host(\"tests/other.kt\", current) == current\n\n"
        "    for mutated in (\n"
        "        current + \"\\n\",\n"
        "        current.replace(\"                        state = state.value,\", \"\", 1),\n"
        "        current.replace(\n"
        "            \"                        state = state.value,\",\n"
        "            \"                        state = state.value,\\n                        state = state.value,\",\n"
        "            1,\n"
        "        ),\n"
        "    ):\n"
        "        assert _normalise_pos_notice_dynamic_state_host(path, mutated) == mutated\n\n"
        "    original_assertion = (\n"
        "        '        compose.onNodeWithText(\"CONTINUE TO PAYMENT\").assertDoesNotExist()'\n"
        "    )\n"
        "    assertion_mutation = current.replace(original_assertion, \"\", 1)\n"
        "    assert _normalise_pos_notice_dynamic_state_host(path, assertion_mutation) == assertion_mutation\n"
        "    assert _missing_ordered_lines(baseline, assertion_mutation) == [original_assertion]\n"
        "    assert _normalise_pos_notice_dynamic_state_host(path, baseline) == baseline\n\n\n"
    )
    expected_test = _replace_exact(
        _original(freeze_test_path),
        (
            ("from pathlib import Path\n", "from pathlib import Path\nimport subprocess\n", 1),
            (
                "    _normalise_release_identity,\n    _normalise_audit_reader_locator,\n",
                "    _normalise_release_identity,\n"
                "    _normalise_pos_notice_dynamic_state_host,\n"
                "    _normalise_audit_reader_locator,\n",
                1,
            ),
            ("3.1.19", "3.1.20", 1),
            (
                freeze_test_anchor,
                freeze_test_addition + normalizer_test_addition + freeze_test_anchor,
                1,
            ),
        ),
    )
    _assert_exact_text(freeze_test_path, _current(freeze_test_path), expected_test)

    assert hashlib.sha256(
        (ROOT / "tests/test_code29_deployment_identity.py").read_bytes()
    ).hexdigest() == HISTORICAL_CODE29_GUARD_SHA256
    assert hashlib.sha256(
        (ROOT / "tests/test_caddy_dependency_security.py").read_bytes()
    ).hexdigest() == HISTORICAL_CADDY_GUARD_SHA256


def test_reviewed_pos_and_inventory_ui_corrections_are_exact() -> None:
    pos_path = "android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/PosScreen.kt"
    expected_pos = _replace_exact(
        _original(pos_path),
        (
            (
                "    state.heldOrderReview\n"
                "        ?.takeIf { access.canCreateAndCollect && voidTarget == null }\n"
                "        ?.let { review ->\n"
                "            key(review.orderId, review.checkoutVersion) {",
                "    state.heldOrderReview\n"
                "        ?.takeIf { access.canCreateAndCollect && voidTarget == null }\n"
                "        ?.let { review ->\n"
                "            key(review.orderId) {",
                1,
            ),
        ),
    )
    _assert_exact_text(pos_path, _current(pos_path), expected_pos)

    for path, expected_sha256 in REVIEWED_UI_SHA256.items():
        _assert_sha256(path, (ROOT / path).read_bytes(), expected_sha256)


def test_reviewed_web_session_correction_is_exact() -> None:
    for path, expected_sha256 in REVIEWED_WEB_SESSION_SHA256.items():
        _assert_sha256(path, (ROOT / path).read_bytes(), expected_sha256)


@pytest.mark.parametrize("path", REVIEWED_WEB_SESSION_SHA256)
def test_reviewed_web_session_hash_guards_reject_working_tree_mutations(
    path: str,
) -> None:
    with pytest.raises(AssertionError, match="unexpected corrected Code 29 content"):
        _assert_sha256(
            path,
            (ROOT / path).read_bytes() + b"\n",
            REVIEWED_WEB_SESSION_SHA256[path],
        )


@pytest.mark.parametrize("path", REVIEWED_UI_SHA256)
def test_reviewed_ui_hash_guards_reject_working_tree_mutations(path: str) -> None:
    with pytest.raises(AssertionError, match="unexpected corrected Code 29 content"):
        _assert_sha256(path, (ROOT / path).read_bytes() + b"\n", REVIEWED_UI_SHA256[path])


def test_operator_records_are_frozen_and_trial_precedes_production() -> None:
    for path, expected_sha256 in OPERATOR_RECORD_SHA256.items():
        assert hashlib.sha256((ROOT / path).read_bytes()).hexdigest() == expected_sha256
    combined = "\n".join(_current(path) for path in OPERATOR_RECORD_SHA256)
    for contract in (
        "Original signed Code 29",
        "Current Code 29",
        "v3.1.20",
        "cancelled before build or signing",
        "v3.1.21",
        "versionCode=29",
        "migration head `0071`",
        "final-source synthetic trial",
        "authenticated emulator and Web route",
        "prior Lenovo physical",
        "ANDROID_MIN_SUPPORTED_VERSION_CODE=8",
        "channel-wide",
        "user consent",
        "87 checks",
        "cleanup was verified",
        "signed-byte continuity",
        "emulator `5574`",
        "no signed or installed",
        "existing authority",
        "not executed against `3.1.21`",
        "generic permission loop",
    ):
        assert contract in combined
    assert "broader signed-Code29\nscanner-source review remains incomplete" in combined
    assert "does not claim that `3.1.21` final CI" in combined


@pytest.mark.parametrize(
    ("path", "old", "new"),
    [
        ("infra/scripts/install-on-vm.sh", "%f:%d:%i", "%F:%d:%i"),
        (".github/workflows/ci.yml", "sudo -n --", "sudo --"),
        ("backend/app/__init__.py", '"3.1.21"', '"3.1.21-mutated"'),
    ],
)
def test_live_exact_guards_reject_working_tree_mutations(
    path: str, old: str, new: str
) -> None:
    if path.startswith(".github/"):
        expected = _workflow_expected(path)
    elif path == "backend/app/__init__.py":
        expected = _identity_expected(path)
    else:
        expected = _current(path)
    assert old in expected
    mutated = expected.replace(old, new, 1)
    with pytest.raises(AssertionError, match="unexpected corrected Code 29 content"):
        _assert_exact_text(path, mutated, expected)
