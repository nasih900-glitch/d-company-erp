from __future__ import annotations

import hashlib
import subprocess
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
CODE28_SCANNER_BASE = "aac3a2167a298b0eae5330a831fb01875c37bf25"
CODE28_SIGNED_BASE = "ab10a3138f41c5acf709e6275dfac55c6652d0d8"
CODE29_SIGNED_BASE = "0949620b4632ebd6accdfa62a203be8d85b31a24"
OLD_BINARY_SHA256 = "73c0169f0b72b465e2ae20bdb67a3e017044b2ab3d267816398b9d6ece243fb0"
PATCHED_BINARY_SHA256 = "951a0136950bb9edf60ff5cec6ca2df0a041b27ded9e610f0aab49b168739dac"
GO_MOD = ROOT / "infra" / "docker" / "caddy-build" / "go.mod"
GO_SUM = ROOT / "infra" / "docker" / "caddy-build" / "go.sum"
CADDY_DOCKERFILE = ROOT / "infra" / "docker" / "caddy.Dockerfile"
CI_WORKFLOW = ROOT / ".github" / "workflows" / "ci.yml"
RELEASE_WORKFLOW = ROOT / ".github" / "workflows" / "release.yml"
CURRENT_WORKFLOW_SHA256 = {
    CI_WORKFLOW: "73ec527d02e6b166844f41257cfe97bc709034cbe6507aefdb9a9c4d151e0ff3",
    RELEASE_WORKFLOW: "0c31c53b7a4ab06facfdf92945541241b0f8cb1c4eee97eed93d096988d5e6d7",
}


def _file_at(commit: str, path: Path) -> str:
    relative = path.relative_to(ROOT).as_posix()
    return subprocess.run(
        ["git", "show", f"{commit}:{relative}"],
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
        _file_at(CODE28_SCANNER_BASE, GO_MOD),
        "golang.org/x/net v0.57.0 // indirect",
        "golang.org/x/net v0.58.0 // indirect",
    )
    expected_go_mod = _replace_once(
        expected_go_mod,
        "google.golang.org/grpc v1.83.1 // indirect",
        "google.golang.org/grpc v1.83.2 // indirect",
    )
    assert GO_MOD.read_text(encoding="utf-8") == expected_go_mod
    assert GO_MOD.read_text(encoding="utf-8") == _file_at(CODE28_SIGNED_BASE, GO_MOD)

    expected_go_sum = _replace_once(
        _file_at(CODE28_SCANNER_BASE, GO_SUM),
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
    assert GO_SUM.read_text(encoding="utf-8") == _file_at(CODE28_SIGNED_BASE, GO_SUM)


def test_caddy_build_contract_adds_only_reviewed_module_and_zlib_changes() -> None:
    signed_expected = _replace_once(
        _file_at(CODE28_SCANNER_BASE, CADDY_DOCKERFILE),
        'golang.org/x/net)" = "v0.57.0"',
        'golang.org/x/net)" = "v0.58.0"',
    )
    signed_expected = _replace_once(
        signed_expected,
        'google.golang.org/grpc)" = "v1.83.1"',
        'google.golang.org/grpc)" = "v1.83.2"',
    )
    signed_expected = _replace_once(
        signed_expected, OLD_BINARY_SHA256, PATCHED_BINARY_SHA256
    )
    assert _file_at(CODE28_SIGNED_BASE, CADDY_DOCKERFILE) == signed_expected

    expected = _replace_once(
        signed_expected,
        "    go version -m /out/caddy | grep -F 'v2.11.4'\n\n"
        "# Retain the exact upstream 2.11.4 runtime contract",
        "    go version -m /out/caddy | grep -F 'v2.11.4'\n\n"
        "FROM caddy:2.11.4-alpine@sha256:"
        "5f5c8640aae01df9654968d946d8f1a56c497f1dd5c5cda4cf95ab7c14d58648 "
        "AS zlib-builder\n"
        "WORKDIR /tmp/dcompany-zlib\n"
        "RUN apk add --no-cache --virtual .zlib-build-deps build-base patch\n"
        "COPY infra/docker/zlib/ /tmp/dcompany-zlib/\n"
        "RUN sh /tmp/dcompany-zlib/build-patched-zlib.sh\n\n"
        "# Retain the exact upstream 2.11.4 runtime contract",
    )
    expected = _replace_once(
        expected,
        '      com.dcompany.upstream.version="v2.11.4"\n\nRUN set -eux; \\\n',
        '      com.dcompany.upstream.version="v2.11.4" \\\n'
        '      com.dcompany.zlib.version="1.3.2" \\\n'
        '      com.dcompany.zlib.source.sha256="'
        'bb329a0a2cd0274d05519d61c667c062e06990d72e125ee2dfa8de64f0119d16" \\\n'
        '      com.dcompany.zlib.null-guard.commit="'
        'e3dc0a85b7032e98380dec011bc8f2c2ee0d8fca" \\\n'
        '      com.dcompany.zlib.null-guard.sha256="'
        '183bc8b9dd078a41a62de5c2d905d9b0196b45bc46f100d7e4147ec228207c74" \\\n'
        '      com.dcompany.zlib.printf-return.commit="'
        'bbc2ccf3d0de267576b524b875c769a724a513b0" \\\n'
        '      com.dcompany.zlib.printf-return.sha256="'
        '7d00ee29be5e636d30da2890961e83e35cee0b152333ecd97a46ae2e71eb5d47" \\\n'
        '      com.dcompany.zlib.patch.commit="'
        'df84af25dc1942490e1d1c899a07619152a46148" \\\n'
        '      com.dcompany.zlib.patch.sha256="'
        '110ff14375733173d8aa54574473424fbd7dfe4b81f1ca34a759c6fe14b15b14" \\\n'
        '      com.dcompany.zlib.followup.commit="'
        '7235b0a581227c56a79a43ff828f8ef6794194c8" \\\n'
        '      com.dcompany.zlib.followup.sha256="'
        '96040ee84d0d187905283912dbd3f7b66ac2033976a2ceefe9b8cca63143d9c2"\n\n'
        "COPY --from=zlib-builder /out/usr/lib/libz.so.1.3.2 "
        "/usr/lib/libz.so.1.3.2\n"
        "COPY --from=zlib-builder /out/etc/dcompany/zlib-patch-evidence.env "
        "/etc/dcompany/zlib-patch-evidence.env\n"
        "COPY --from=zlib-builder /out/zlib-runtime-probe /tmp/zlib-runtime-probe\n"
        "COPY infra/docker/zlib/verify-patched-zlib.sh "
        "/tmp/verify-patched-zlib.sh\n\n"
        "RUN set -eux; \\\n",
    )
    expected = _replace_once(
        expected,
        "    printf '%s\\n' \"$installed_packages\" | grep -Fx 'libssl3-3.5.8-r0'\n",
        "    printf '%s\\n' \"$installed_packages\" | grep -Fx 'libssl3-3.5.8-r0'; \\\n"
        "    printf '%s\\n' \"$installed_packages\" | grep -Fx 'zlib-1.3.2-r0'; \\\n"
        "    sh /tmp/verify-patched-zlib.sh; \\\n"
        "    rm -f /tmp/zlib-runtime-probe /tmp/verify-patched-zlib.sh\n",
    )
    assert CADDY_DOCKERFILE.read_text(encoding="utf-8") == expected


def test_caddy_binary_hash_is_coordinated_across_ci_and_release() -> None:
    for workflow in (CI_WORKFLOW, RELEASE_WORKFLOW):
        expected = _replace_once(
            _file_at(CODE28_SCANNER_BASE, workflow),
            OLD_BINARY_SHA256,
            PATCHED_BINARY_SHA256,
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
        assert _file_at(CODE28_SIGNED_BASE, workflow) == expected
        current_bytes = _file_at(CODE29_SIGNED_BASE, workflow).encode()
        current = current_bytes.decode("utf-8")
        assert hashlib.sha256(current_bytes).hexdigest() == CURRENT_WORKFLOW_SHA256[workflow]
        assert current.count(PATCHED_BINARY_SHA256) == 1

    assert "go 1.26.8" in GO_MOD.read_text(encoding="utf-8")
    assert "github.com/caddyserver/caddy/v2 v2.11.4" in GO_MOD.read_text(
        encoding="utf-8"
    )
    assert "v2.11.4-dcompany.1" in CADDY_DOCKERFILE.read_text(encoding="utf-8")
