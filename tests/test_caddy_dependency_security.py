from __future__ import annotations

import subprocess
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
CODE28_SCANNER_BASE = "aac3a2167a298b0eae5330a831fb01875c37bf25"
OLD_BINARY_SHA256 = "73c0169f0b72b465e2ae20bdb67a3e017044b2ab3d267816398b9d6ece243fb0"
PATCHED_BINARY_SHA256 = "951a0136950bb9edf60ff5cec6ca2df0a041b27ded9e610f0aab49b168739dac"
GO_MOD = ROOT / "infra" / "docker" / "caddy-build" / "go.mod"
GO_SUM = ROOT / "infra" / "docker" / "caddy-build" / "go.sum"
CADDY_DOCKERFILE = ROOT / "infra" / "docker" / "caddy.Dockerfile"
CI_WORKFLOW = ROOT / ".github" / "workflows" / "ci.yml"
RELEASE_WORKFLOW = ROOT / ".github" / "workflows" / "release.yml"


def _baseline_file(path: Path) -> str:
    relative = path.relative_to(ROOT).as_posix()
    return subprocess.run(
        ["git", "show", f"{CODE28_SCANNER_BASE}:{relative}"],
        cwd=ROOT,
        check=True,
        capture_output=True,
        text=True,
    ).stdout


def _replace_once(source: str, old: str, new: str) -> str:
    assert source.count(old) == 1
    return source.replace(old, new)


def test_caddy_graph_changes_only_grpc_and_its_required_existing_xnet_module() -> None:
    expected_go_mod = _replace_once(
        _baseline_file(GO_MOD),
        "golang.org/x/net v0.57.0 // indirect",
        "golang.org/x/net v0.58.0 // indirect",
    )
    expected_go_mod = _replace_once(
        expected_go_mod,
        "google.golang.org/grpc v1.83.1 // indirect",
        "google.golang.org/grpc v1.83.2 // indirect",
    )
    assert GO_MOD.read_text(encoding="utf-8") == expected_go_mod

    expected_go_sum = _replace_once(
        _baseline_file(GO_SUM),
        "golang.org/x/net v0.57.0/go.mod h1:KpXc8iv+r3XplLAG/f7Jsf9RPszJzdR0f58q9vGOuEU=",
        "golang.org/x/net v0.57.0/go.mod h1:KpXc8iv+r3XplLAG/f7Jsf9RPszJzdR0f58q9vGOuEU=\n"
        "golang.org/x/net v0.58.0 h1:ynWG7rqYi4ccpTEuPZ2QGWHktVEM9DMCj9yzDE0Q7To=\n"
        "golang.org/x/net v0.58.0/go.mod h1:YwCddHnFlT7eLQqVprV19OnhLGtc5xOKgE0RyqgfWAU=",
    )
    expected_go_sum = _replace_once(
        expected_go_sum,
        "google.golang.org/grpc v1.83.1/go.mod h1:kDyl6SKsiHKt0uylY5gtn5cEjkrIOhQOGDgIc4JGwzQ=",
        "google.golang.org/grpc v1.83.1/go.mod h1:kDyl6SKsiHKt0uylY5gtn5cEjkrIOhQOGDgIc4JGwzQ=\n"
        "google.golang.org/grpc v1.83.2 h1:EManeRomTObA0BU7I8vXgg/78uE5MJ9M8B39EX2WscU=\n"
        "google.golang.org/grpc v1.83.2/go.mod h1:YPI1hK3kDked6iHvgX3tR0y+nX/qpMFKhPgFsokw1S8=",
    )
    assert GO_SUM.read_text(encoding="utf-8") == expected_go_sum


def test_caddy_build_contract_changes_only_module_assertions_and_binary_hash() -> None:
    expected = _replace_once(
        _baseline_file(CADDY_DOCKERFILE),
        'golang.org/x/net)" = "v0.57.0"',
        'golang.org/x/net)" = "v0.58.0"',
    )
    expected = _replace_once(
        expected,
        'google.golang.org/grpc)" = "v1.83.1"',
        'google.golang.org/grpc)" = "v1.83.2"',
    )
    expected = _replace_once(expected, OLD_BINARY_SHA256, PATCHED_BINARY_SHA256)
    assert CADDY_DOCKERFILE.read_text(encoding="utf-8") == expected


def test_caddy_binary_hash_is_coordinated_across_ci_and_release() -> None:
    for workflow in (CI_WORKFLOW, RELEASE_WORKFLOW):
        expected = _replace_once(
            _baseline_file(workflow), OLD_BINARY_SHA256, PATCHED_BINARY_SHA256
        )
        if workflow == CI_WORKFLOW:
            expected = _replace_once(
                expected,
                "          :audit-driver:assembleDebug :audit-driver:assembleDebugAndroidTest\n",
                "          :audit-driver:testDebugUnitTest\n"
                "          :audit-driver:assembleDebug :audit-driver:assembleDebugAndroidTest\n",
            )
            expected = _replace_once(
                expected,
                "            android-native/app/build/outputs/ci-diagnostics/\n",
                "            android-native/app/build/outputs/ci-diagnostics/\n"
                "            android-native/audit-driver/build/reports/tests/\n"
                "            android-native/audit-driver/build/test-results/\n",
            )
        current = workflow.read_text(encoding="utf-8")
        assert current == expected
        assert current.count(PATCHED_BINARY_SHA256) == 1

    assert "go 1.26.8" in GO_MOD.read_text(encoding="utf-8")
    assert "github.com/caddyserver/caddy/v2 v2.11.4" in GO_MOD.read_text(
        encoding="utf-8"
    )
    assert "v2.11.4-dcompany.1" in CADDY_DOCKERFILE.read_text(encoding="utf-8")
