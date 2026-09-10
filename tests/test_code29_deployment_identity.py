from __future__ import annotations

import hashlib
import json
import subprocess
from pathlib import Path

import pytest


ROOT = Path(__file__).resolve().parents[1]
CODE28_SIGNED_BASE = "ab10a3138f41c5acf709e6275dfac55c6652d0d8"
CADDY_SHA256 = "951a0136950bb9edf60ff5cec6ca2df0a041b27ded9e610f0aab49b168739dac"
PROTECTED_APPLICATION_PREFIXES = (
    "backend/app/",
    "backend/alembic/",
    "frontend/src/",
    "android-native/app/src/main/",
    "android-native/app/schemas/",
    "android-native/audit-driver/src/",
)
QUANTITY_HASHES = {
    "android-native/app/src/main/java/cloud/dcompany/erp/core/quantity/QuantityInput.kt":
        "dda127f5286e4eef50edba0cd5e4bc2a69c706b48a90337ac317b2ac37a40001",
    "android-native/app/src/main/java/cloud/dcompany/erp/ui/components/QuantityField.kt":
        "7ecd1b6180fbde2623c6dd6e004c9913d1213d7d2b249da4a2a99ca82f59add4",
    "android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/inventory/InventoryScreen.kt":
        "4fc2996e39049b7824fbb1a5b6c2f13e420d3b781e03e62bd78a2a3cd0df5759",
    "android-native/app/src/test/java/cloud/dcompany/erp/core/quantity/QuantityInputTest.kt":
        "5524be07edbef3bf5550f4bc65c3c0d49a05ac27e8c20d826a5aef622a4a1bbf",
    "android-native/app/src/test/java/cloud/dcompany/erp/ui/screens/inventory/InventoryQuantityContractTest.kt":
        "451ef166585eabf2b9587d0ecf5f8b7f7733e392f2f7912f52601099fd591457",
    "android-native/app/src/androidTest/java/cloud/dcompany/erp/ui/screens/inventory/QuantityFieldUiTest.kt":
        "33c01b1ff19cf62552b0860d0e34b9e8ebe78334f70fc1cd35e862656d5ee5f6",
}
SCANNER_HASHES = {
    "infra/scripts/run-hardened-image-scanners.sh":
        "17205e2d7b64f8e07ba4057a7c5913af3ad90bf22f872d973b685b51455a7ee4",
    "infra/scripts/verify-image-archive-identity.py":
        "89c0b3e567a61a7623f4cd205b8e3fc28da6be18c13765a0c942b188fa248318",
    "tests/fixtures/image_archive_identity_fixtures.py":
        "024da0b2d997c4cd3ed3839e9a38687b7fbfcc6aa05e7b0a20677747b8e1bb89",
    "tests/test_image_archive_identity.py":
        "3170037455528a351136e75890d685f71208a8b8d07f02053c78f5b845f7d925",
    "tests/test_hardened_scanner_runtime.py":
        "c72d6822acf03d3fd7a2a3d9f8d10691cf4142a3c6b52d31569c2aa79e33fe95",
}
REVIEWED_EXISTING_TEST_HASHES = {
    "android-native/app/src/test/java/cloud/dcompany/erp/AndroidReleaseIdentityTest.kt":
        "6a209574898c95b5ea3681180f87a7482cb453a51d55e9ff6253137310e66959",
    "backend/tests/unit/test_client_compatibility.py":
        "6477d909230b64d87b5a13cf217c8af6f0a7939377f8a5fc701f4f38e18b0a29",
    "backend/tests/unit/test_release_audit_fixes.py":
        "79186c191bc1b6bf817de77297b82d0f0c02056209fad6119677d897634d02f5",
    "backend/tests/unit/test_release_contracts.py":
        "d3e0f77ab5dbdb12cf9e486a2dd0fed7e15b75cf4e800bf9378f4dfb18706cb6",
    "backend/tests/unit/test_remote_assistance_contract.py":
        "cbc8ea6ed6da908b6c8ce893259fc54f5c3ff5cae9c0a7ed710117e5135ff692",
    "backend/tests/unit/test_runtime_release_parity.py":
        "07300c9df07a61acf08f8f9fe69815fd813315964bc11ee14fa43dcdd5d0cd50",
    "tests/test_android_release_pipeline.py":
        "c75f5fc5508abf0d7d97f1279792087dedfd4fa4a781e79b823181266c995b93",
    "tests/test_android_release_version.py":
        "d7f357eb9713f0e1f8359f677c7b75c73332abb5f40caa920c377d78da3b9f06",
    "tests/test_android_runtime_parity.py":
        "8290224155977f4d159223e03b06206c808c38a8a6b91977a1542b1adede5704",
    "tests/test_caddy_dependency_security.py":
        "2c66eceafc62e0eec4cbf0174a77717b856333ae45accd93cb4066ba2d68139a",
    "tests/test_code26_physical_audit_lane.py":
        "84af8e8af26b5185099075aae50f2db1311848c0af14d8699e61b043efb90849",
    "tests/test_code26_regression_freeze.py":
        "367d856e83ab3fc53e68964d89d2dc21afd02af363cedbf44998c47884fd9760",
    "tests/test_code28_deployment_identity.py":
        "dfbc182b2076d758bcc4d841c4b34d215664a5510358470d6460a54ecb4750e1",
    "tests/test_hardened_scanner_runtime.py":
        "c72d6822acf03d3fd7a2a3d9f8d10691cf4142a3c6b52d31569c2aa79e33fe95",
}
PHASE2C_INFRA_HASHES = {
    ".github/actions/scan-production-images/action.yml":
        "dd20083cc1ac2f75629d5859468f2f3ef969086b0a981bfed68ed7866c431e64",
    ".github/workflows/ci.yml":
        "e89ad041dd684a18b00da7c5c8bd67b6106fd0a4d4ef84e6807390bb33ac82f9",
    ".github/workflows/release.yml":
        "f2feb493ff6237c48f9d5618ab4e625a9cc25c3c737640e6c794765f13bceb6a",
    "infra/scripts/run-ci-docker-connection-check.sh":
        "bb3e8751fa6cafae0560e05d915db44e766434b93a703e14cd2413d6c8914a29",
    "infra/scripts/verify-ci-docker-connection.py":
        "6fabbd63d1ad422ef6f537671da065ee2d53ced18f260c391734454105f19eec",
    "infra/scripts/verify-production-runtime-images.sh":
        "12a2014cb9176ad4b1e6dbab67152ce4abf49739f78d611f389b46cc546d207a",
    "scripts/verify_image_archive_parser_python312.py":
        "7788b7f3a2c5e3d12e0904a2e2628aee2a0ca107b09930d592174aad57505a6e",
}
OPERATOR_RECORD_HASHES = {
    ".env.production.example":
        "461bc7178d90bf06ce222f6c2a42f5cba6778139574629a9941dbce66719a4b0",
    "docs/CODE29_RELEASE_CANDIDATE.md":
        "f873ba63532408b5540620e00210a4cace68ea720fce6d2518ac2bdf007381e3",
    "docs/DISTRIBUTION.md":
        "f0d7b770bab05bad668267cc4905a97546f5cb9c8a5ba95fda298c12eeb19853",
    "docs/SERVER_DRIVEN_ANDROID_UPDATES.md":
        "b9a21bafeb87a89beda50144b414455d792411f5da1b2165b8f653239b8e4b3e",
}


def _git(*arguments: str) -> str:
    return subprocess.run(
        ["git", *arguments], cwd=ROOT, check=True, capture_output=True, text=True
    ).stdout


def _file_at(path: str) -> str:
    return _git("show", f"{CODE28_SIGNED_BASE}:{path}")


def _replace_exact(source: str, replacements: tuple[tuple[str, str, int], ...]) -> str:
    for old, new, expected_count in replacements:
        assert source.count(old) == expected_count
        source = source.replace(old, new)
    return source


def _current_changed(*prefixes: str) -> set[str]:
    changed = set(
        _git("diff", "--name-only", CODE28_SIGNED_BASE, "--", *prefixes).splitlines()
    )
    changed.update(
        path
        for path in _git("ls-files", "--others", "--exclude-standard").splitlines()
        if path.startswith(prefixes)
    )
    return {path for path in changed if path}


def _sha256(path: str) -> str:
    return hashlib.sha256((ROOT / path).read_bytes()).hexdigest()


def _validate_reviewed_test_source(path: str, content: bytes, expected_sha256: str) -> None:
    actual = hashlib.sha256(content).hexdigest()
    if actual != expected_sha256:
        raise AssertionError(
            f"signed28 test/support source differs from its reviewed Code29 snapshot: {path}"
        )


def _is_test_support(path: str) -> bool:
    if path.startswith(("backend/tests/", "tests/")):
        return path.endswith(".py")
    if path.startswith(
        ("android-native/app/src/test/", "android-native/app/src/androidTest/")
    ):
        return path.endswith((".kt", ".java"))
    if path.startswith("frontend/src/"):
        return any(marker in Path(path).name for marker in (".test.", ".spec."))
    return False


def _validate_workflow_contracts(ci: str, release: str, action: str) -> None:
    release_image_start = release.index("  production-image-gates:")
    release_instrumentation_start = release.index("  android-instrumentation:")
    release_image = release[release_image_start:release_instrumentation_start]
    coordinated = release[
        release.index("  coordinated-release-gates:"):release_image_start
    ]
    ci_image = ci[ci.index("  docker:"):]
    for workflow, image_job in ((ci, ci_image), (release, release_image)):
        assert image_job.count("- store: classic") == 1
        assert image_job.count("- store: containerd") == 1
        assert image_job.count("docker buildx build --load") == 4
        assert image_job.count("--provenance=false") == 1
        assert image_job.count("--provenance=mode=min") == 1
        assert image_job.count("containerd-snapshotter") == 1
        assert "fail-fast: false" in image_job
        assert "continue-on-error" not in image_job
        evidence_start = image_job.index(
            "      - name: Retain bounded Docker connection evidence"
        )
        assert "if: always()" not in image_job[:evidence_start]
        evidence_step = image_job[evidence_start:]
        assert evidence_step.count("if: always()") == 1
        assert "docker-connection-pre-build.json.runner" in evidence_step
        assert "docker-connection-pre-build.json.root" in evidence_step
        assert "scanner-runtime" not in evidence_step
        assert ".env" not in evidence_step
        assert "docker/setup-buildx-action@" not in image_job
        assert "docker/setup-docker-action@77e84dbf09b47d1e29270283c22f16145aa85ca1" in image_job
        assert "verify-postgres16-image-compatibility.sh" in image_job
        assert "verify-production-runtime-images.sh" in image_job
        assert "verify_image_archive_parser_python312.py" in image_job
        assert "uses: ./.github/actions/scan-production-images" in image_job
        assert image_job.count(CADDY_SHA256) == 1
    assert "needs: [backend, frontend]" in ci_image
    assert "needs: coordinated-release-gates" in release_image
    assert "run: python -m pytest tests" in coordinated
    assert "npm run test" in coordinated
    assert "docker buildx build" not in coordinated
    assert "scan-production-images" not in coordinated
    build_job = release[
        release.index("  build-android:"):release.index(
            "  verify-android-reproducibility:"
        )
    ]
    required_needs = (
        "needs: [coordinated-release-gates, production-image-gates, "
        "android-instrumentation]"
    )
    assert build_job.count(required_needs) == 1
    guarded = "steps.docker-connection.outcome == 'success'"
    assert action.count(guarded) == 12
    assert "continue-on-error" not in action
    assert "sudo -E" not in action
    assert action.count("syft-version: v1.42.3") == 5
    assert action.count("grype-version: v0.118.0") == 5
    assert "services=(backend frontend caddy postgres)" in action
    assert 'docker image save "$image_id" --output "$archive"' in action


def test_code29_application_scope_is_strictly_signed28_plus_reviewed_quantity() -> None:
    assert _current_changed(*PROTECTED_APPLICATION_PREFIXES) == {
        "backend/app/__init__.py",
        *(
            path
            for path in QUANTITY_HASHES
            if "/src/main/" in path
        ),
    }
    for path, expected in QUANTITY_HASHES.items():
        assert _sha256(path) == expected


def test_code29_coordinated_identity_is_exactly_29_and_3_1_19() -> None:
    replacements = {
        "android-native/app/build.gradle.kts": (
            ("versionCode = 28", "versionCode = 29", 1),
            ('versionName = "3.1.18"', 'versionName = "3.1.19"', 1),
        ),
        "backend/pyproject.toml": (
            ('version = "3.1.18"', 'version = "3.1.19"', 1),
        ),
        "backend/app/__init__.py": (("3.1.18", "3.1.19", 1),),
        "frontend/package.json": (("3.1.18", "3.1.19", 1),),
        "frontend/package-lock.json": (("3.1.18", "3.1.19", 2),),
        "frontend/.env.example": (("3.1.18", "3.1.19", 1),),
        "docker-compose.prod.yml": (("3.1.18", "3.1.19", 6),),
    }
    for path, path_replacements in replacements.items():
        expected = _replace_exact(_file_at(path), path_replacements)
        assert (ROOT / path).read_text(encoding="utf-8") == expected


def test_database_audit_money_and_display_boundaries_are_byte_identical() -> None:
    protected = (
        "backend/app/services/pos/pricing.py",
        "backend/app/services/reports/aggregator.py",
        "frontend/src/lib/inr.ts",
        "android-native/app/src/main/java/cloud/dcompany/erp/core/money/MoneyInput.kt",
        "android-native/app/src/main/java/cloud/dcompany/erp/ui/components/Primitives.kt",
    )
    for path in protected:
        assert (ROOT / path).read_text(encoding="utf-8") == _file_at(path)
    assert not _current_changed(
        "backend/alembic/",
        "android-native/app/schemas/",
        "android-native/audit-driver/src/",
    )


def test_audit_driver_lock_only_completes_existing_release_unit_dependencies() -> None:
    path = "android-native/audit-driver/gradle.lockfile"
    baseline = _file_at(path)
    expected = baseline
    for dependency in ("junit:junit:4.13.2", "org.hamcrest:hamcrest-core:1.3"):
        line = next(line for line in baseline.splitlines() if line.startswith(dependency + "="))
        configurations = line.split("=", 1)[1].split(",")
        required = {"releaseUnitTestCompileClasspath", "releaseUnitTestRuntimeClasspath"}
        assert not required.intersection(configurations)
        updated = dependency + "=" + ",".join(sorted([*configurations, *required]))
        expected = _replace_exact(expected, ((line, updated, 1),))
    # Gradle removes this formerly contradictory empty marker when persisting
    # the existing testImplementation dependency's resolved configurations.
    empty = next(line for line in baseline.splitlines() if line.startswith("empty="))
    configurations = empty.split("=", 1)[1].split(",")
    assert configurations.count("testImplementationDependenciesMetadata") == 1
    configurations.remove("testImplementationDependenciesMetadata")
    expected = _replace_exact(expected, ((empty, "empty=" + ",".join(configurations), 1),))
    assert (ROOT / path).read_text(encoding="utf-8") == expected
    build = "android-native/audit-driver/build.gradle.kts"
    assert (ROOT / build).read_text(encoding="utf-8") == _file_at(build)


def test_code29_scanner_files_are_exactly_pinned() -> None:
    assert SCANNER_HASHES
    for path, expected in SCANNER_HASHES.items():
        assert _sha256(path) == expected


def test_code29_pipeline_and_runtime_identity_files_are_exactly_pinned() -> None:
    for path, expected in PHASE2C_INFRA_HASHES.items():
        assert _sha256(path) == expected
    assert _current_changed(".github/") == {
        ".github/actions/scan-production-images/action.yml",
        ".github/workflows/ci.yml",
        ".github/workflows/release.yml",
    }
    assert _current_changed("infra/scripts/") == {
        "infra/scripts/run-ci-docker-connection-check.sh",
        "infra/scripts/run-hardened-image-scanners.sh",
        "infra/scripts/verify-ci-docker-connection.py",
        "infra/scripts/verify-image-archive-identity.py",
        "infra/scripts/verify-production-runtime-images.sh",
    }


def test_code29_physical_lane_changes_identity_only() -> None:
    plan_path = "android-native/audit-driver/plans/code26-gaming-finance-physical.json"
    code28_plan = json.loads(_file_at(plan_path))
    code29_plan = json.loads((ROOT / plan_path).read_text(encoding="utf-8"))
    assert len(code29_plan["steps"]) == 413
    assert code29_plan["expected_sessions"] == 16
    assert code29_plan["name"] == "Code 29 full-route Gaming and Finance physical acceptance"
    code29_plan["name"] = code28_plan["name"]
    assert code29_plan == code28_plan

    runner_path = "scripts/run_code26_physical_business_audit.sh"
    runner_replacements = (
        ("Run the Code 28 business", "Run the Code 29 business", 1),
        ("after the Code 28 source-settled", "after the Code 29 source-settled", 1),
        ("a Code 28 result", "a Code 29 result", 1),
        ('"28" || "$VERSION_NAME" != "3.1.18"', '"29" || "$VERSION_NAME" != "3.1.19"', 1),
        ("non-Code-28 source identity", "non-Code-29 source identity", 1),
        (
            "cloud.dcompany.erp.physicalaudit 28 3.1.18-physical-audit",
            "cloud.dcompany.erp.physicalaudit 29 3.1.19-physical-audit",
            1,
        ),
    )
    assert (ROOT / runner_path).read_text(encoding="utf-8") == _replace_exact(
        _file_at(runner_path), runner_replacements
    )

    analyzer_path = "scripts/analyze_code26_physical_evidence.py"
    analyzer_replacements = (
        ("Code 28 tablet business", "Code 29 tablet business", 1),
        (
            'payload.get("version_code") != 28 or payload.get("version_name") != "3.1.18"',
            'payload.get("version_code") != 29 or payload.get("version_name") != "3.1.19"',
            1,
        ),
        ("source is not Code 28 / 3.1.18", "source is not Code 29 / 3.1.19", 1),
        ('get("version_code") != "28"', 'get("version_code") != "29"', 1),
        (
            'get("version_name") != "3.1.18-physical-audit"',
            'get("version_name") != "3.1.19-physical-audit"',
            1,
        ),
        (
            "physicalAudit APK is not Code 28 / 3.1.18-physical-audit",
            "physicalAudit APK is not Code 29 / 3.1.19-physical-audit",
            1,
        ),
    )
    assert (ROOT / analyzer_path).read_text(encoding="utf-8") == _replace_exact(
        _file_at(analyzer_path), analyzer_replacements
    )


def test_code29_operator_records_preserve_history_and_current_authority() -> None:
    historical_path = "docs/CODE28_RELEASE_CANDIDATE.md"
    assert (ROOT / historical_path).read_text(encoding="utf-8") == _file_at(
        historical_path
    )
    assert _current_changed("docs/") == {
        "docs/CODE29_RELEASE_CANDIDATE.md",
        "docs/DISTRIBUTION.md",
        "docs/SERVER_DRIVEN_ANDROID_UPDATES.md",
    }
    for path, expected in OPERATOR_RECORD_HASHES.items():
        assert _sha256(path) == expected
    combined = "\n".join(
        (ROOT / path).read_text(encoding="utf-8")
        for path in (
            "docs/CODE29_RELEASE_CANDIDATE.md",
            "docs/DISTRIBUTION.md",
            "docs/SERVER_DRIVEN_ANDROID_UPDATES.md",
        )
    )
    for contract in (
        "unsigned",
        "signed Code 28",
        "image-identity gate",
        "Code 21",
        "ANDROID_MIN_SUPPORTED_VERSION_CODE=8",
        "CLIENT_COMPATIBILITY_POLICY_REVISION",
        "channel-wide",
        "user consent",
    ):
        assert contract in combined
    assert "Code 29 is not currently signed" in combined
    assert "stage only the exact verified code 29" in combined.lower()


def test_code29_workflow_contracts_are_complete_and_fail_closed() -> None:
    _validate_workflow_contracts(
        (ROOT / ".github/workflows/ci.yml").read_text(encoding="utf-8"),
        (ROOT / ".github/workflows/release.yml").read_text(encoding="utf-8"),
        (ROOT / ".github/actions/scan-production-images/action.yml").read_text(
            encoding="utf-8"
        ),
    )


@pytest.mark.parametrize(
    ("target", "old", "new"),
    [
        ("release", "- store: containerd", "- store: omitted"),
        (
            "release",
            "needs: [coordinated-release-gates, production-image-gates, android-instrumentation]",
            "needs: [coordinated-release-gates, android-instrumentation]",
        ),
        ("ci", "verify-production-runtime-images.sh", "runtime-check-removed.sh"),
        ("action", "fail-build: true", "continue-on-error: true\n        fail-build: true"),
    ],
)
def test_workflow_negative_mutations_are_rejected(target: str, old: str, new: str) -> None:
    sources = {
        "ci": (ROOT / ".github/workflows/ci.yml").read_text(encoding="utf-8"),
        "release": (ROOT / ".github/workflows/release.yml").read_text(encoding="utf-8"),
        "action": (ROOT / ".github/actions/scan-production-images/action.yml").read_text(encoding="utf-8"),
    }
    assert old in sources[target]
    sources[target] = sources[target].replace(old, new, 1)
    with pytest.raises((AssertionError, ValueError)):
        _validate_workflow_contracts(sources["ci"], sources["release"], sources["action"])


def test_every_signed28_test_support_file_is_unchanged_or_exactly_pinned() -> None:
    baseline_paths = _git(
        "ls-tree", "-r", "--name-only", CODE28_SIGNED_BASE
    ).splitlines()
    test_paths = sorted(path for path in baseline_paths if _is_test_support(path))
    assert len(test_paths) == 512
    assert set(REVIEWED_EXISTING_TEST_HASHES) <= set(test_paths)
    changed_existing = _current_changed(
        "backend/tests/",
        "tests/",
        "android-native/app/src/test/",
        "android-native/app/src/androidTest/",
        "frontend/src/",
    ) & set(test_paths)
    assert changed_existing == set(REVIEWED_EXISTING_TEST_HASHES)
    for path in test_paths:
        candidate = ROOT / path
        assert candidate.is_file()
        if path in REVIEWED_EXISTING_TEST_HASHES:
            _validate_reviewed_test_source(
                path, candidate.read_bytes(), REVIEWED_EXISTING_TEST_HASHES[path]
            )
        else:
            assert candidate.read_text(encoding="utf-8") == _file_at(path)


def test_exact_test_snapshot_rejects_early_return_and_unexpected_rewrite() -> None:
    source = b"def test_gate():\n    assert first\n    assert second\n"
    expected = hashlib.sha256(source).hexdigest()
    _validate_reviewed_test_source("tests/test_gate.py", source, expected)
    for changed in (
        b"def test_gate():\n    return\n    assert first\n    assert second\n",
        b"def test_gate():\n    assert first\n    assert weakened\n",
    ):
        with pytest.raises(AssertionError, match="differs from its reviewed Code29 snapshot"):
            _validate_reviewed_test_source("tests/test_gate.py", changed, expected)
