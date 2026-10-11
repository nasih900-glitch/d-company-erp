from __future__ import annotations

import hashlib
import re
import subprocess
import sys
import types
from pathlib import Path

import pytest


ROOT = Path(__file__).resolve().parents[1]
CODE28_SCANNER_BASE = "aac3a2167a298b0eae5330a831fb01875c37bf25"
CODE28_SIGNED_BASE = "ab10a3138f41c5acf709e6275dfac55c6652d0d8"
CODE29_SIGNED_BASE = "0949620b4632ebd6accdfa62a203be8d85b31a24"
BUILD42_RELEASE_BASE = "1c1c7582eb8d6d6c509ddddc159014af10ce5f89"
BUILD43_RELEASE_BASE = "8418e349eac0707ef62629f6cd3b7dc434690436"
BUILD45_RUNTIME_BASE = "5b8ef38597871599fde4a42a5b895cd9a05c4d11"
OLD_BINARY_SHA256 = "73c0169f0b72b465e2ae20bdb67a3e017044b2ab3d267816398b9d6ece243fb0"
PATCHED_BINARY_SHA256 = "951a0136950bb9edf60ff5cec6ca2df0a041b27ded9e610f0aab49b168739dac"
CURRENT_BINARY_SHA256 = "1b95bdb9ca9ffaec90a9e3a9592e8cedc2212abd64c709219149b8b4285d07d7"
OLD_GOSU_SHA256 = "cdcdfbe2a74dc15d62f6f73da877a372641b80104ff04ca5bee7bc36ee9936ab"
CURRENT_GOSU_SHA256 = "ffd70d9dcf141b9f13ae293ec5a6c48cf8d04f56bddc80cf244c62398b24f2ea"
OLD_GO_BUILDER = (
    "golang:1.26.8-alpine3.24@sha256:"
    "ce864e7223ac17b1775e6fd0b4c0db580c2eb50e7953a427916379e4b92a1628"
)
CURRENT_GO_BUILDER = (
    "golang:1.26.9-alpine3.24@sha256:"
    "3082400e369fa24d5fc60bca20edab3f6d604e0c5a690ec66b295eff4dd87ade"
)
GO_MOD = ROOT / "infra" / "docker" / "caddy-build" / "go.mod"
GO_SUM = ROOT / "infra" / "docker" / "caddy-build" / "go.sum"
CADDY_DOCKERFILE = ROOT / "infra" / "docker" / "caddy.Dockerfile"
POSTGRES_DOCKERFILE = ROOT / "infra" / "docker" / "postgres.Dockerfile"
POSTGRES_COMPATIBILITY = (
    ROOT / "infra" / "scripts" / "verify-postgres16-image-compatibility.sh"
)
BACKEND_DOCKERFILE = ROOT / "infra" / "docker" / "backend.Dockerfile"
FRONTEND_DOCKERFILE = ROOT / "infra" / "docker" / "frontend.Dockerfile"
REDIS_DOCKERFILE = ROOT / "infra" / "docker" / "redis.Dockerfile"
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


def test_caddy_graph_applies_only_the_reviewed_go_security_closure() -> None:
    signed_go_mod = _replace_once(
        _file_at(CODE28_SCANNER_BASE, GO_MOD),
        "golang.org/x/net v0.57.0 // indirect",
        "golang.org/x/net v0.58.0 // indirect",
    )
    signed_go_mod = _replace_once(
        signed_go_mod,
        "google.golang.org/grpc v1.83.1 // indirect",
        "google.golang.org/grpc v1.83.2 // indirect",
    )
    assert signed_go_mod == _file_at(CODE28_SIGNED_BASE, GO_MOD)

    expected_go_mod = _replace_once(signed_go_mod, "go 1.26.8", "go 1.26.9")
    for module, old, new in (
        ("golang.org/x/crypto", "v0.56.0", "v0.57.0"),
        ("golang.org/x/mod", "v0.38.0", "v0.41.0"),
        ("golang.org/x/net", "v0.58.0", "v0.60.0"),
        ("golang.org/x/sync", "v0.22.0", "v0.23.0"),
        ("golang.org/x/sys", "v0.47.0", "v0.48.0"),
        ("golang.org/x/term", "v0.45.0", "v0.46.0"),
        ("golang.org/x/text", "v0.41.0", "v0.42.0"),
        ("golang.org/x/tools", "v0.48.0", "v0.49.0"),
    ):
        expected_go_mod = _replace_once(
            expected_go_mod,
            f"{module} {old} // indirect",
            f"{module} {new} // indirect",
        )
    assert GO_MOD.read_text(encoding="utf-8") == expected_go_mod

    signed_go_sum = _replace_once(
        _file_at(CODE28_SCANNER_BASE, GO_SUM),
        "golang.org/x/net v0.57.0/go.mod h1:KpXc8iv+r3XplLAG/f7Jsf9RPszJzdR0f58q9vGOuEU=",
        "golang.org/x/net v0.57.0/go.mod h1:KpXc8iv+r3XplLAG/f7Jsf9RPszJzdR0f58q9vGOuEU=\n"
        "golang.org/x/net v0.58.0 h1:ynWG7rqYi4ccpTEuPZ2QGWHktVEM9DMCj9yzDE0Q7To=\n"
        "golang.org/x/net v0.58.0/go.mod h1:YwCddHnFlT7eLQqVprV19OnhLGtc5xOKgE0RyqgfWAU=",
    )
    signed_go_sum = _replace_once(
        signed_go_sum,
        "google.golang.org/grpc v1.83.1/go.mod h1:kDyl6SKsiHKt0uylY5gtn5cEjkrIOhQOGDgIc4JGwzQ=",
        "google.golang.org/grpc v1.83.1/go.mod h1:kDyl6SKsiHKt0uylY5gtn5cEjkrIOhQOGDgIc4JGwzQ=\n"
        "google.golang.org/grpc v1.83.2 h1:EManeRomTObA0BU7I8vXgg/78uE5MJ9M8B39EX2WscU=\n"
        "google.golang.org/grpc v1.83.2/go.mod h1:YPI1hK3kDked6iHvgX3tR0y+nX/qpMFKhPgFsokw1S8=",
    )
    assert signed_go_sum == _file_at(CODE28_SIGNED_BASE, GO_SUM)

    expected_go_sum = signed_go_sum
    additions = (
        (
            "golang.org/x/crypto v0.56.0/go.mod h1:OMW5y6CY9l38uPLmxU6l6pwcXp1obtLo3e6gT7gQR2I=",
            "golang.org/x/crypto v0.57.0 h1:3ZVCjf8Ggz7zneR/EHRVx68Ctf+2pmIMP2UFhh9cC6M=\n"
            "golang.org/x/crypto v0.57.0/go.mod h1:Fdz0i5U6CoizGwLda9DttjSk6qlZo25zYNtR+ycvuZA=",
        ),
        (
            "golang.org/x/mod v0.38.0/go.mod h1:V6Xz0pq8TQ3dGqVQ1FVHuelZpAL0uNhSkk9ogYP3c40=",
            "golang.org/x/mod v0.41.0 h1:qJmnOUb4YB+FsEuM3HcWucdZASCPGhsX6uljO6pog0c=\n"
            "golang.org/x/mod v0.41.0/go.mod h1:Ek9pY8RKWXwsWvd3rQiHYtMqkjSUV+s1Rj7j4H5Ur6o=",
        ),
        (
            "golang.org/x/net v0.58.0/go.mod h1:YwCddHnFlT7eLQqVprV19OnhLGtc5xOKgE0RyqgfWAU=",
            "golang.org/x/net v0.60.0 h1:79p50tfZlm0J9YfoDsSi639qSXNGVwEzOPLCxM2FsYU=\n"
            "golang.org/x/net v0.60.0/go.mod h1:2DA/G1UfVbCpQPeWTmMPGY7Cs2PkBkwu743bVX5PIVg=",
        ),
        (
            "golang.org/x/sync v0.22.0/go.mod h1:9xrNwdLfx4jkKbNva9FpL6vEN7evnE43NNNJQ2LF3+0=",
            "golang.org/x/sync v0.23.0 h1:KameEIfc1IkluZyXWLn39Wd4tURc6GbCiISGiZm2bQk=\n"
            "golang.org/x/sync v0.23.0/go.mod h1:sUUOizhqBxiL6pEWpqNLUiaJn1ShEbZ6BBqskPbjZm0=",
        ),
        (
            "golang.org/x/sys v0.47.0/go.mod h1:4GL1E5IUh+htKOUEOaiffhrAeqysfVGipDYzABqnCmw=",
            "golang.org/x/sys v0.48.0 h1:bbX/i/6MgT9BVLM9RT1thmxL04yeTAhbEz4SyadbXoo=\n"
            "golang.org/x/sys v0.48.0/go.mod h1:hNLxWAXmnKAxqDtdwIYC4bM9oQPEecfsnNMuSxOs3og=",
        ),
        (
            "golang.org/x/term v0.45.0/go.mod h1:9aqxs0blBcrm/n0L9QW0aRVD+ktan8ssZromtqJC43w=",
            "golang.org/x/term v0.46.0 h1:3+OXuTbaKDgwk8jTi3aSLHRlmWqHEUDUtxnbFigO4YE=\n"
            "golang.org/x/term v0.46.0/go.mod h1:+K02xbkittuwc0Am4abfA3Fc+XRGXkvBXNO88NCXPoc=",
        ),
        (
            "golang.org/x/text v0.41.0/go.mod h1:jvf1O8ajNzZqhSrQBPbutR/EB83Cc0CFrezNQIwbb5M=",
            "golang.org/x/text v0.42.0 h1:JbOZXgfeCPU9gacVtYliJqOhD+zhrEqK4LfdpmlUZqI=\n"
            "golang.org/x/text v0.42.0/go.mod h1:ojzP1Z+2QtioaF8DTtO8K5q7JWVVYwZKenzujK0Zd0E=",
        ),
        (
            "golang.org/x/tools v0.48.0/go.mod h1:08xX0orndb/F7jJxGDicx061tyd5pcMto75YMAXr6lk=",
            "golang.org/x/tools v0.49.0 h1:3NI7VXzL9+1WZD52Dx2ttoPwD5DWrFGpl9mFZDlmisI=\n"
            "golang.org/x/tools v0.49.0/go.mod h1:SJNXV9DBKT0UbdttsQjbfJlAE/q+y36++zo3uL3N0Oo=",
        ),
    )
    for anchor, added in additions:
        expected_go_sum = _replace_once(
            expected_go_sum, anchor, f"{anchor}\n{added}"
        )
    assert GO_SUM.read_text(encoding="utf-8") == expected_go_sum
    assert len(expected_go_sum.splitlines()) == len(signed_go_sum.splitlines()) + 16


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
        "RUN set -eux; \\\n",
    )
    expected = _replace_once(
        expected,
        "    printf '%s\\n' \"$installed_packages\" | grep -Fx 'libssl3-3.5.8-r0'\n",
        "    printf '%s\\n' \"$installed_packages\" | grep -Fx 'libssl3-3.5.8-r0'; \\\n"
        "    printf '%s\\n' \"$installed_packages\" | grep -Fx 'zlib-1.3.2-r1'\n",
    )
    expected = _replace_once(
        expected, "'libcrypto3=3.5.8-r0'", "'libcrypto3=3.5.9-r0'"
    )
    expected = _replace_once(
        expected, "'libssl3=3.5.8-r0'", "'libssl3=3.5.9-r0'"
    )
    expected = _replace_once(
        expected, "'libcrypto3-3.5.8-r0'", "'libcrypto3-3.5.9-r0'"
    )
    expected = _replace_once(
        expected, "'libssl3-3.5.8-r0'", "'libssl3-3.5.9-r0'"
    )
    expected = _replace_once(
        expected,
        "      'libssl3=3.5.9-r0'; \\\n",
        "      'libssl3=3.5.9-r0' \\\n"
        "      'zlib=1.3.2-r1'; \\\n",
    )
    expected = _replace_once(
        expected,
        "    printf '%s\\n' \"$installed_packages\" | grep -Fx 'zlib-1.3.2-r1'\n\n"
        "COPY --from=caddy-builder /out/caddy /usr/bin/caddy\n",
        "    printf '%s\\n' \"$installed_packages\" | grep -Fx 'zlib-1.3.2-r1'\n\n"
        "COPY --from=zlib-builder /out/usr/lib/libz.so.1.3.2 "
        "/usr/lib/libz.so.1.3.2\n"
        "COPY --from=zlib-builder /out/etc/dcompany/zlib-patch-evidence.env "
        "/etc/dcompany/zlib-patch-evidence.env\n"
        "COPY --from=zlib-builder /out/zlib-runtime-probe /tmp/zlib-runtime-probe\n"
        "COPY infra/docker/zlib/verify-patched-zlib.sh "
        "/tmp/verify-patched-zlib.sh\n\n"
        "RUN set -eux; \\\n"
        "    sh /tmp/verify-patched-zlib.sh; \\\n"
        "    rm -f /tmp/zlib-runtime-probe /tmp/verify-patched-zlib.sh\n\n"
        "COPY --from=caddy-builder /out/caddy /usr/bin/caddy\n",
    )
    expected = _replace_once(expected, OLD_GO_BUILDER, CURRENT_GO_BUILDER)
    expected = _replace_once(
        expected,
        '    test "$(go env GOVERSION)" = "go1.26.8"; \\\n',
        '    test "$(go env GOVERSION)" = "go1.26.9"; \\\n',
    )
    expected = _replace_once(
        expected,
        "    test \"$(go list -mod=readonly -m -f '{{.Version}}' "
        "golang.org/x/crypto)\" = \"v0.56.0\"; \\\n"
        "    test \"$(go list -mod=readonly -m -f '{{.Version}}' "
        "golang.org/x/net)\" = \"v0.58.0\"; \\\n"
        "    test \"$(go list -mod=readonly -m -f '{{.Version}}' "
        "golang.org/x/text)\" = \"v0.41.0\"; \\\n",
        "    test \"$(go list -mod=readonly -m -f '{{.Version}}' "
        "golang.org/x/crypto)\" = \"v0.57.0\"; \\\n"
        "    test \"$(go list -mod=readonly -m -f '{{.Version}}' "
        "golang.org/x/mod)\" = \"v0.41.0\"; \\\n"
        "    test \"$(go list -mod=readonly -m -f '{{.Version}}' "
        "golang.org/x/net)\" = \"v0.60.0\"; \\\n"
        "    test \"$(go list -mod=readonly -m -f '{{.Version}}' "
        "golang.org/x/sync)\" = \"v0.23.0\"; \\\n"
        "    test \"$(go list -mod=readonly -m -f '{{.Version}}' "
        "golang.org/x/sys)\" = \"v0.48.0\"; \\\n"
        "    test \"$(go list -mod=readonly -m -f '{{.Version}}' "
        "golang.org/x/telemetry)\" = "
        "\"v0.0.0-20260811182544-a038080d80e5\"; \\\n"
        "    test \"$(go list -mod=readonly -m -f '{{.Version}}' "
        "golang.org/x/term)\" = \"v0.46.0\"; \\\n"
        "    test \"$(go list -mod=readonly -m -f '{{.Version}}' "
        "golang.org/x/text)\" = \"v0.42.0\"; \\\n"
        "    test \"$(go list -mod=readonly -m -f '{{.Version}}' "
        "golang.org/x/tools)\" = \"v0.49.0\"; \\\n",
    )
    expected = _replace_once(expected, PATCHED_BINARY_SHA256, CURRENT_BINARY_SHA256)
    expected = _replace_once(
        expected,
        "    go version -m /out/caddy | grep -F 'go1.26.8'; \\\n",
        "    go version -m /out/caddy | grep -F 'go1.26.9'; \\\n",
    )
    assert CADDY_DOCKERFILE.read_text(encoding="utf-8") == expected


def test_postgres_runtime_changes_only_reviewed_security_corrections() -> None:
    expected = _file_at(BUILD42_RELEASE_BASE, POSTGRES_DOCKERFILE)
    for old, new in (
        ("'libcrypto3=3.5.8-r0'", "'libcrypto3=3.5.9-r0'"),
        ("'libssl3=3.5.8-r0'", "'libssl3=3.5.9-r0'"),
        ("'libcrypto3-3.5.8-r0'", "'libcrypto3-3.5.9-r0'"),
        ("'libssl3-3.5.8-r0'", "'libssl3-3.5.9-r0'"),
    ):
        expected = _replace_once(expected, old, new)
    expected = _replace_once(expected, OLD_GO_BUILDER, CURRENT_GO_BUILDER)
    expected = _replace_once(
        expected,
        '    test "$(go env GOVERSION)" = "go1.26.8"; \\\n',
        '    test "$(go env GOVERSION)" = "go1.26.9"; \\\n',
    )
    expected = _replace_once(expected, OLD_GOSU_SHA256, CURRENT_GOSU_SHA256)
    expected = _replace_once(
        expected,
        "    go version -m /out/gosu | grep -F 'go1.26.8'",
        "    go version -m /out/gosu | grep -F 'go1.26.9'",
    )
    expected = _replace_once(
        expected,
        "    gosu --version | grep -F '1.19 (go1.26.8 on linux/'; \\\n",
        "    gosu --version | grep -F '1.19 (go1.26.9 on linux/'; \\\n",
    )
    assert POSTGRES_DOCKERFILE.read_text(encoding="utf-8") == expected


def test_postgres_compatibility_gate_binds_the_current_gosu_binary() -> None:
    expected = _file_at(BUILD45_RUNTIME_BASE, POSTGRES_COMPATIBILITY)
    expected = _replace_once(
        expected,
        "1.19 (go1.26.8 on linux/",
        "1.19 (go1.26.9 on linux/",
    )
    expected = _replace_once(expected, OLD_GOSU_SHA256, CURRENT_GOSU_SHA256)
    assert POSTGRES_COMPATIBILITY.read_text(encoding="utf-8") == expected


def test_backend_image_changes_only_reviewed_openssl_runtime_controls() -> None:
    expected = _file_at(BUILD43_RELEASE_BASE, BACKEND_DOCKERFILE)
    old_base = (
        "python:3.14.7-alpine3.24@sha256:"
        "016508ba505da24f7139765bc4bb669df4e88eb2f12eeadd571bf2f88d7533df"
    )
    new_base = (
        "python:3.14.8-alpine3.24@sha256:"
        "f6a589d43c42b9e7f7dc67a12d37132491f362859a5d750607710cc56da3bc72"
    )
    assert expected.count(old_base) == 3
    expected = expected.replace(old_base, new_base)
    expected = _replace_once(
        expected,
        'com.dcompany.python.version="3.14.7"',
        'com.dcompany.python.version="3.14.8"',
    )
    expected = _replace_once(
        expected,
        "test \"$(python --version)\" = 'Python 3.14.7'",
        "test \"$(python --version)\" = 'Python 3.14.8'",
    )
    package_install = (
        "RUN apk add --no-cache --upgrade \\\n"
        "      'libcrypto3=3.5.9-r0' \\\n"
        "      'libssl3=3.5.9-r0' \\\n"
        "      'libuuid=2.42.3-r1' \\\n"
        "    && installed_packages=\"$(apk info -v)\" \\\n"
        "    && printf '%s\\n' \"$installed_packages\" | grep -Fx 'libcrypto3-3.5.9-r0' \\\n"
        "    && printf '%s\\n' \"$installed_packages\" | grep -Fx 'libssl3-3.5.9-r0' \\\n"
        "    && printf '%s\\n' \"$installed_packages\" | grep -Fx 'libuuid-2.42.3-r1' \\\n"
        "    && python -c 'import ssl; assert ssl.OPENSSL_VERSION_INFO == (3, 5, 0, 9, 0), (ssl.OPENSSL_VERSION_INFO, ssl.OPENSSL_VERSION)'"
    )
    expected = _replace_once(
        expected,
        "RUN apk add --no-cache --upgrade 'libuuid=2.42.3-r1'\nWORKDIR /app",
        package_install + "\nWORKDIR /app",
    )
    expected = _replace_once(
        expected,
        "RUN apk add --no-cache --upgrade 'libuuid=2.42.3-r1' \\\n"
        "    && test \"$(python --version)\" = 'Python 3.14.8' \\\n",
        package_install
        + " \\\n"
        + "    && test \"$(python --version)\" = 'Python 3.14.8' \\\n",
    )
    assert BACKEND_DOCKERFILE.read_text(encoding="utf-8") == expected


def test_backend_python_patch_version_is_exact_in_ci_and_release() -> None:
    ci = CI_WORKFLOW.read_text(encoding="utf-8")
    release = RELEASE_WORKFLOW.read_text(encoding="utf-8")

    assert ci.count("python-version: '3.14.8'") == 1
    assert release.count("python-version: '3.14.8'") == 1
    assert "python-version: '3.14.7'" not in ci
    assert "python-version: '3.14.7'" not in release


@pytest.mark.parametrize(
    ("version_info", "accepted"),
    (
        ((3, 5, 0, 9, 0), True),
        ((3, 5, 0, 8, 0), False),
        ((3, 5, 0, 10, 0), False),
        ((3, 6, 0, 9, 0), False),
        ((3, 5, 9), False),
    ),
)
def test_backend_openssl_assertions_require_exact_legacy_version_tuple(
    monkeypatch: pytest.MonkeyPatch,
    version_info: tuple[int, ...],
    accepted: bool,
) -> None:
    commands = re.findall(
        r"python -c '([^'\n]*OPENSSL_VERSION_INFO[^'\n]*)'",
        BACKEND_DOCKERFILE.read_text(encoding="utf-8"),
    )
    assert len(commands) == 2

    synthetic_ssl = types.ModuleType("ssl")
    synthetic_ssl.OPENSSL_VERSION_INFO = version_info
    synthetic_ssl.OPENSSL_VERSION = f"OpenSSL synthetic {version_info!r}"
    monkeypatch.setitem(sys.modules, "ssl", synthetic_ssl)

    for command in commands:
        if accepted:
            exec(command, {})
        else:
            with pytest.raises(AssertionError) as error:
                exec(command, {})
            assert error.value.args == (
                (version_info, synthetic_ssl.OPENSSL_VERSION),
            )


def test_frontend_image_changes_only_reviewed_security_controls() -> None:
    expected = _file_at(BUILD43_RELEASE_BASE, FRONTEND_DOCKERFILE)
    expected = _replace_once(
        expected,
        "RUN set -eux; \\\n"
        "    apk info -e zlib; \\\n",
        "RUN set -eux; \\\n"
        "    apk add --no-cache --upgrade \\\n"
        "      'libcrypto3=3.5.9-r0' \\\n"
        "      'libssl3=3.5.9-r0' \\\n"
        "      'pcre2=10.49-r0'; \\\n"
        "    installed_packages=\"$(apk info -v)\"; \\\n"
        "    printf '%s\\n' \"$installed_packages\" | grep -Fx 'libcrypto3-3.5.9-r0'; \\\n"
        "    printf '%s\\n' \"$installed_packages\" | grep -Fx 'libssl3-3.5.9-r0'; \\\n"
        "    printf '%s\\n' \"$installed_packages\" | grep -Fx 'pcre2-10.49-r0'; \\\n"
        "    apk info -e zlib; \\\n",
    )
    expected = _replace_once(
        expected,
        "COPY frontend/package.json frontend/package-lock.json* ./\n"
        "RUN npm ci --no-audit --no-fund\n",
        "COPY frontend/package.json frontend/package-lock.json* ./\n"
        "COPY frontend/braces-compat ./braces-compat\n"
        "COPY frontend/vendor/dcompany-braces-depth-guard "
        "./vendor/dcompany-braces-depth-guard\n"
        "RUN npm ci --no-audit --no-fund\n",
    )
    assert FRONTEND_DOCKERFILE.read_text(encoding="utf-8") == expected


def test_redis_changes_only_reviewed_openssl_packages_before_zlib_overlay() -> None:
    expected = _file_at(BUILD45_RUNTIME_BASE, REDIS_DOCKERFILE)
    expected = _replace_once(
        expected,
        "# Redis 7.4.11 on current Alpine 3.21 carries OpenSSL 3.3.7-r1. Build the\n"
        "# independently verified zlib correction in a discarded stage, then overlay\n"
        "# only the patched shared object and its non-secret provenance in the runtime.\n",
        "# Preserve Redis 7.4.11 and upgrade only its OpenSSL security packages.\n"
        "# Install packages before overlaying the independently verified zlib runtime\n"
        "# so package transactions cannot replace the reviewed shared object.\n",
    )
    expected = _replace_once(
        expected,
        "COPY --from=zlib-builder /out/usr/lib/libz.so.1.3.2 /usr/lib/libz.so.1.3.2\n",
        "RUN apk add --no-cache --upgrade \\\n"
        "      'libcrypto3=3.3.7-r2' \\\n"
        "      'libssl3=3.3.7-r2'\n\n"
        "COPY --from=zlib-builder /out/usr/lib/libz.so.1.3.2 /usr/lib/libz.so.1.3.2\n",
    )
    for package in ("libcrypto3", "libssl3"):
        expected = _replace_once(
            expected, f"'{package}-3.3.7-r1'", f"'{package}-3.3.7-r2'"
        )
    assert REDIS_DOCKERFILE.read_text(encoding="utf-8") == expected


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

        live = workflow.read_text(encoding="utf-8")
        assert live.count(CURRENT_BINARY_SHA256) == 1
        assert PATCHED_BINARY_SHA256 not in live

    assert "go 1.26.9" in GO_MOD.read_text(encoding="utf-8")
    assert "github.com/caddyserver/caddy/v2 v2.11.4" in GO_MOD.read_text(
        encoding="utf-8"
    )
    assert "v2.11.4-dcompany.1" in CADDY_DOCKERFILE.read_text(encoding="utf-8")
