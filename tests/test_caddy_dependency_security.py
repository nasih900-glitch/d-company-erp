from __future__ import annotations

import hashlib
import json
import os
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
BUILD67_RELEASE_BASE = "6224fccac9be7f32c0119ef73b8ed66f9c1aec3c"
BUILD69_RELEASE_BASE = "0b15b6a87516125b4ef2628b7b89b84853f3058b"
COMPRESS_SHA256 = "h1:v5tiZvm1KGLyg5d5oXdXe3lsSPrbEUiKoXGia//I7Qo="
COMPRESS_MOD_SHA256 = "h1:LUdAzn7YLVvxLpc7y3V1m40wESHTgc1422pwwBSKYuI="
MUSL_UTILS_INSTALL = (
    "RUN --mount=type=bind,source=infra/docker/install-pinned-musl-utils.sh,"
    "target=/tmp/install-pinned-musl-utils.sh,ro \\\n"
    "    sh /tmp/install-pinned-musl-utils.sh "
)
OLD_BINARY_SHA256 = "73c0169f0b72b465e2ae20bdb67a3e017044b2ab3d267816398b9d6ece243fb0"
PATCHED_BINARY_SHA256 = "951a0136950bb9edf60ff5cec6ca2df0a041b27ded9e610f0aab49b168739dac"
BUILD67_BINARY_SHA256 = "f2fcd13d0aeb36b7622b041fb6a607bd9cd0bef846291f0751a2e20ee4ab827b"
BUILD69_BINARY_SHA256 = "db2378c42f9ae1b9db772cad5f857026138cee72ac91c057114faf138afdceb2"
CURRENT_BINARY_SHA256 = "bab9ead64a1462df94c98fc8046af79601867b9e4b9cfa1359e0073acc546699"
# GO-2026-6860 and GO-2026-6866 require x/net v0.62.0 and its selected graph.
NET_SECURITY_UPGRADES = (
    (
        "golang.org/x/crypto", "v0.57.0", "v0.58.0",
        "h1:COkYLr4k7nDI4r6QtMML2AfzeIswWHAJthLS6K/M/54=",
        "h1:Xh+kl5A+M0gMiWDytH2Hhr5Z7WbNCpv/eB/EFgfSk+w=",
    ),
    (
        "golang.org/x/net", "v0.60.0", "v0.62.0",
        "h1:18ELalfM5+XNRwzM2nYqXsZQ9ByS1uhj2O/U7+5jqRY=",
        "h1:WgDCOTH8iwtB66T4/GfI94AHo/hhdb7Hah9NDseQfiI=",
    ),
    (
        "golang.org/x/sync", "v0.23.0", "v0.24.0",
        "h1:iqiDrGoDgyuvAKrhLQthUEzU/R378N78gXbBjxed7oA=",
        "h1:sUUOizhqBxiL6pEWpqNLUiaJn1ShEbZ6BBqskPbjZm0=",
    ),
    (
        "golang.org/x/sys", "v0.48.0", "v0.49.0",
        "h1:XbzkgYJHdqh/8m2Uu0W/dQv8nktxx4BFHp1M0gROTXA=",
        "h1:hNLxWAXmnKAxqDtdwIYC4bM9oQPEecfsnNMuSxOs3og=",
    ),
    (
        "golang.org/x/telemetry", "v0.0.0-20260811182544-a038080d80e5", "v0.0.0-20260924152758-ed294f943157",
        "h1:DftxASHKl6JolFPqoN+2WrFO53JvkHwRXvFqf8gsivA=",
        "h1:i+ivNqjDnTF3WTElsdk5g9V5DTSBYgdNo7xTU9SDwYA=",
    ),
    (
        "golang.org/x/term", "v0.46.0", "v0.47.0",
        "h1:cpyOwQkr2Nzxe2p6KnrTG7Ei2wtzjsuSjhpXXepZ/BM=",
        "h1:efAfAuG6RKABvg1ArGebRkvX6LXul14SM67o2e4mUCY=",
    ),
    (
        "golang.org/x/text", "v0.42.0", "v0.43.0",
        "h1:1QivrlhwAsnMOcqgOdl9my7s9OyZl8O5/UGUTJVXoWc=",
        "h1:hbSIYA/amXcRXTFSZqh8tWHEoqRvvbKPJ2uz/KU+a+s=",
    ),
    (
        "golang.org/x/tools", "v0.49.0", "v0.51.0",
        "h1:k4Xc/1Om9jwkBJBo4NVLMSARBoWtK10mx+W5BnXCeAI=",
        "h1:9eEncMayCV6zRMGhR5eZEC2iBx98qWcF1HZ9Z7wJOoA=",
    ),
)
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
    for module, old, new in (
        ("github.com/go-jose/go-jose/v3", "v3.0.5", "v3.0.6-0.20261030183946-0e502b87c7aa"),
        ("github.com/go-jose/go-jose/v4", "v4.1.4", "v4.1.7"),
    ):
        expected_go_mod = _replace_once(
            expected_go_mod, f"{module} {old} // indirect", f"{module} {new} // indirect"
        )
    assert _file_at(BUILD67_RELEASE_BASE, GO_MOD) == expected_go_mod
    for module, old, new, _, _ in NET_SECURITY_UPGRADES:
        if module == "golang.org/x/telemetry":
            anchor = "\tgolang.org/x/term v0.46.0 // indirect\n"
            expected_go_mod = _replace_once(
                expected_go_mod, anchor, f"\t{module} {new} // indirect\n{anchor}"
            )
        else:
            expected_go_mod = _replace_once(
                expected_go_mod, f"{module} {old} // indirect", f"{module} {new} // indirect"
            )
    assert _file_at(BUILD69_RELEASE_BASE, GO_MOD) == expected_go_mod
    assert GO_MOD.read_text(encoding="utf-8") == _replace_once(
        expected_go_mod,
        "github.com/klauspost/compress v1.18.6 // indirect",
        "github.com/klauspost/compress v1.20.2 // indirect",
    )

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
    for anchor, added in (
        (
            "github.com/go-jose/go-jose/v3 v3.0.5/go.mod h1:5b+7YgP7ZICgJDBdfjZaIt+H/9L9T/YQrVfLAMboGkQ=",
            "github.com/go-jose/go-jose/v3 v3.0.6-0.20261030183946-0e502b87c7aa h1:AHZcbOqCCO38+3UfsccMAi5D+c0D+JnVGopumm3zLfg=\n"
            "github.com/go-jose/go-jose/v3 v3.0.6-0.20261030183946-0e502b87c7aa/go.mod h1:5b+7YgP7ZICgJDBdfjZaIt+H/9L9T/YQrVfLAMboGkQ=",
        ),
        (
            "github.com/go-jose/go-jose/v4 v4.1.4/go.mod h1:x4oUasVrzR7071A4TnHLGSPpNOm2a21K9Kf04k1rs08=",
            "github.com/go-jose/go-jose/v4 v4.1.7 h1:Nac/98uT5LPkfbiFuYa8ktf62a2VxwQ4xbq1GK8pV5E=\n"
            "github.com/go-jose/go-jose/v4 v4.1.7/go.mod h1:x4oUasVrzR7071A4TnHLGSPpNOm2a21K9Kf04k1rs08=",
        ),
    ):
        expected_go_sum = _replace_once(expected_go_sum, anchor, f"{anchor}\n{added}")
    assert _file_at(BUILD67_RELEASE_BASE, GO_SUM) == expected_go_sum
    assert len(expected_go_sum.splitlines()) == len(signed_go_sum.splitlines()) + 20
    expected_lines = expected_go_sum.splitlines(keepends=True)
    for module, _, new, checksum, mod_checksum in NET_SECURITY_UPGRADES:
        last = max(
            index for index, line in enumerate(expected_lines) if line.startswith(f"{module} ")
        )
        expected_lines[last + 1:last + 1] = [
            f"{module} {new} {checksum}\n",
            f"{module} {new}/go.mod {mod_checksum}\n",
        ]
    expected_go_sum = "".join(expected_lines)
    assert _file_at(BUILD69_RELEASE_BASE, GO_SUM) == expected_go_sum
    anchor = "github.com/klauspost/compress v1.18.6/go.mod h1:cwPg85FWrGar70rWktvGQj8/hthj3wpl0PGDogxkrSQ=\n"
    assert GO_SUM.read_text(encoding="utf-8") == _replace_once(
        expected_go_sum,
        anchor,
        anchor + f"github.com/klauspost/compress v1.20.2 {COMPRESS_SHA256}\n"
        f"github.com/klauspost/compress v1.20.2/go.mod {COMPRESS_MOD_SHA256}\n",
    )


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
    expected = _replace_once(expected, PATCHED_BINARY_SHA256, BUILD67_BINARY_SHA256)
    expected = _replace_once(
        expected,
        "    go version -m /out/caddy | grep -F 'go1.26.8'; \\\n",
        "    go version -m /out/caddy | grep -F 'go1.26.9'; \\\n",
    )
    # Build55 advances only the signed Alpine c-ares security package. Keep
    # the complete inherited graph/pins and full-file equality assertion.
    expected = _replace_once(
        expected, "'c-ares=1.34.8-r0'", "'c-ares=1.34.9-r0'"
    )
    expected = _replace_once(
        expected, "'c-ares-1.34.8-r0'", "'c-ares-1.34.9-r0'"
    )
    # Build55 removes unused vulnerable curl/libcurl. Retain the exact
    # Caddy/base/other-package contract and full-file equality; no scan exception.
    expected = _replace_once(
        expected,
        "      'curl=8.22.0-r0' \\\n"
        "      'libcurl=8.22.0-r0' \\\n",
        '',
    )
    expected = _replace_once(
        expected,
        '    printf \'%s\\n\' "$installed_packages" | grep -Fx \'curl-8.22.0-r0\'; \\\n'
        '    printf \'%s\\n\' "$installed_packages" | grep -Fx \'libcurl-8.22.0-r0\'; \\\n',
        '',
    )
    expected = _replace_once(
        expected,
        '    installed_packages="$(apk info -v)"; \\\n',
        '    retained_apk_constraints="$(awk -F: \'$1 == "P" { name = $2 } $1 == "V" && name != "curl" && name != "libcurl" { print name "=" $2 }\' /lib/apk/db/installed | LC_ALL=C sort)"; \\\n'
        '    test -n "$retained_apk_constraints"; \\\n'
        '    apk add --no-network --repositories-file /dev/null $retained_apk_constraints; \\\n'
        '    apk del --no-network --repositories-file /dev/null curl libcurl; \\\n'
        '    remaining_apk_constraints="$(awk -F: \'$1 == "P" { name = $2 } $1 == "V" { print name "=" $2 }\' /lib/apk/db/installed | LC_ALL=C sort)"; \\\n'
        '    test "$remaining_apk_constraints" = "$retained_apk_constraints"; \\\n'
        '    if command -v curl >/dev/null 2>&1 || command -v wcurl >/dev/null 2>&1; then exit 1; fi; \\\n'
        '    test ! -e /usr/lib/libcurl.so.4; \\\n'
        '    test ! -e /usr/lib/libcurl.so.4.8.0; \\\n'
        '    installed_packages="$(apk info -v)"; \\\n',
    )
    # Build55 restores both signed CA packages and checks the effective Go pool.
    # Preserve complete inherited file equality and all unrelated pins/guards.
    expected = _replace_once(
        expected,
        'FROM caddy:2.11.4-alpine@sha256:5f5c8640aae01df9654968d946d8f1a56c497f1dd5c5cda4cf95ab7c14d58648 AS zlib-builder',
        'COPY infra/docker/caddy-build/verify-system-ca/ /tmp/dcompany-verify-system-ca/\n'
        'RUN set -eux; \\\n'
        '    export CGO_ENABLED=0 GOOS="${TARGETOS}" GOARCH="${TARGETARCH}" GOMAXPROCS=1 GOMEMLIMIT=256MiB; \\\n'
        '    go test -p=1 -mod=readonly -count=1 -v \\\n'
        '      /tmp/dcompany-verify-system-ca/main.go /tmp/dcompany-verify-system-ca/main_test.go; \\\n'
        "    go build -p=1 -mod=readonly -buildvcs=false -trimpath -ldflags='-s -w -buildid=' \\\n"
        '      -o /out/verify-caddy-system-ca /tmp/dcompany-verify-system-ca/main.go\n'
        '\n'
        'FROM caddy:2.11.4-alpine@sha256:5f5c8640aae01df9654968d946d8f1a56c497f1dd5c5cda4cf95ab7c14d58648 AS zlib-builder',
    )
    expected = _replace_once(
        expected,
        'RUN set -eux; \\\n'
        '    apk add --no-cache --upgrade \\\n',
        'COPY --from=caddy-builder /out/verify-caddy-system-ca /tmp/verify-caddy-system-ca\n'
        '\n'
        'RUN set -eux; \\\n'
        '    apk add --no-cache --upgrade \\\n'
        "      'ca-certificates=20261020-r0' \\\n"
        "      'ca-certificates-bundle=20261020-r0' \\\n",
    )
    expected = _replace_once(
        expected,
        "      'zlib=1.3.2-r1'; \\\n",
        "      'zlib=1.3.2-r1'; \\\n"
        '    update-ca-certificates --fresh; \\\n'
        '    /tmp/verify-caddy-system-ca; \\\n'
        '    rm -f /tmp/verify-caddy-system-ca; \\\n',
    )
    expected = _replace_once(
        expected,
        '    printf \'%s\\n\' "$installed_packages" | grep -Fx \'c-ares-1.34.9-r0\'; \\\n',
        '    printf \'%s\\n\' "$installed_packages" | grep -Fx \'ca-certificates-20261020-r0\'; \\\n'
        '    printf \'%s\\n\' "$installed_packages" | grep -Fx \'ca-certificates-bundle-20261020-r0\'; \\\n'
        '    printf \'%s\\n\' "$installed_packages" | grep -Fx \'c-ares-1.34.9-r0\'; \\\n',
    )
    # Preserve the full historical reconstruction, then admit only these
    # checksum-verified official JOSE and Brotli security corrections.
    module_anchor = '    test "$(go list -mod=readonly -m -f \'{{.Version}}\' github.com/caddyserver/caddy/v2)" = "v2.11.4"; \\\n'
    expected = _replace_once(
        expected, module_anchor,
        module_anchor
        + '    test "$(go list -mod=readonly -m -f \'{{.Version}}\' github.com/go-jose/go-jose/v3)" = "v3.0.6-0.20261030183946-0e502b87c7aa"; \\\n'
        + '    test "$(go list -mod=readonly -m -f \'{{.Version}}\' github.com/go-jose/go-jose/v4)" = "v4.1.7"; \\\n',
    )
    assert expected.count("v2.11.4-dcompany.1") == 2
    expected = expected.replace("v2.11.4-dcompany.1", "v2.11.4-dcompany.2")
    expected = _replace_once(
        expected, "      'libssl3=3.5.9-r0' \\\n",
        "      'libssl3=3.5.9-r0' \\\n      'brotli-libs=1.2.1-r0' \\\n",
    )
    package_anchor = '    printf \'%s\\n\' "$installed_packages" | grep -Fx \'libssl3-3.5.9-r0\'; \\\n'
    expected = _replace_once(
        expected, package_anchor,
        package_anchor + '    printf \'%s\\n\' "$installed_packages" | grep -Fx \'brotli-libs-1.2.1-r0\'; \\\n',
    )
    assert _file_at(BUILD67_RELEASE_BASE, CADDY_DOCKERFILE) == expected
    for module, old, new, _, _ in NET_SECURITY_UPGRADES:
        expected = _replace_once(expected, f'{module})" = "{old}"', f'{module})" = "{new}"')
    expected = _replace_once(expected, BUILD67_BINARY_SHA256, BUILD69_BINARY_SHA256)
    expected = _replace_once(
        expected,
        "    go version -m /out/caddy | grep -F 'v2.11.4'\n",
        "    go version -m /out/caddy | grep -F 'v2.11.4'; \\\n"
        '    go version -m /out/caddy | grep -Fx "$(printf '
        r"'\tdep\tgolang.org/x/net\tv0.62.0\th1:18ELalfM5+XNRwzM2nYqXsZQ9ByS1uhj2O/U7+5jqRY='"
        ')"\n',
    )
    assert _file_at(BUILD69_RELEASE_BASE, CADDY_DOCKERFILE) == expected
    expected = _replace_once(expected, BUILD69_BINARY_SHA256, CURRENT_BINARY_SHA256)
    assert expected.count("v2.11.4-dcompany.2") == 2
    expected = expected.replace("v2.11.4-dcompany.2", "v2.11.4-dcompany.3")
    module_anchor = '    test "$(go list -mod=readonly -m -f \'{{.Version}}\' github.com/go-jose/go-jose/v4)" = "v4.1.7"; \\\n'
    expected = _replace_once(
        expected, module_anchor, module_anchor
        + '    test "$(go list -mod=readonly -m -f \'{{.Version}}\' github.com/klauspost/compress)" = "v1.20.2"; \\\n',
    )
    binary_anchor = (
        '    go version -m /out/caddy | grep -Fx "$(printf '
        r"'\tdep\tgolang.org/x/net\tv0.62.0\th1:18ELalfM5+XNRwzM2nYqXsZQ9ByS1uhj2O/U7+5jqRY='"
        ')"'
    )
    expected = _replace_once(
        expected,
        binary_anchor + "\n",
        binary_anchor + "; \\\n"
        + '    go version -m /out/caddy | grep -Fx "$(printf '
        + rf"'\tdep\tgithub.com/klauspost/compress\tv1.20.2\t{COMPRESS_SHA256}'"
        + ')"\n',
    )
    expected += "\n" + MUSL_UTILS_INSTALL + "3.23\n"
    assert _file_at('b9b19bd2462e0f417922849a455e12116e8207e2', CADDY_DOCKERFILE) == expected
    # Caddy alone replaces its custom overlay with the authenticated official1.4 package.
    # The complete inherited reconstruction above and all unrelated pins stay exact.
    for before, after in (
        ('      -o /out/verify-caddy-system-ca /tmp/dcompany-verify-system-ca/main.go\n\nFROM caddy:2.11.4-alpine@sha256:5f5c8640aae01df9654968d946d8f1a56c497f1dd5c5cda4cf95ab7c14d58648 AS zlib-builder\nWORKDIR /tmp/dcompany-zlib\nRUN apk add --no-cache --virtual .zlib-build-deps build-base patch\nCOPY infra/docker/zlib/ /tmp/dcompany-zlib/\nRUN sh /tmp/dcompany-zlib/build-patched-zlib.sh\n\n# Retain the exact upstream 2.11.4 runtime contract and replace only the Go\n',
         '      -o /out/verify-caddy-system-ca /tmp/dcompany-verify-system-ca/main.go\n\nFROM caddy:2.11.4-alpine@sha256:5f5c8640aae01df9654968d946d8f1a56c497f1dd5c5cda4cf95ab7c14d58648 AS caddy-zlib-probe-builder\nRUN apk add --no-cache --virtual .zlib-probe-build-deps build-base\nCOPY infra/docker/caddy-zlib/runtime-probe.c /tmp/caddy-zlib-runtime-probe.c\nRUN cc -O2 -Wall -Wextra -Werror /tmp/caddy-zlib-runtime-probe.c -ldl -o /tmp/caddy-zlib-runtime-probe\n\n# Retain the exact upstream 2.11.4 runtime contract and replace only the Go\n'),
        ('      org.opencontainers.image.source="https://github.com/caddyserver/caddy" \\\n      com.dcompany.upstream.version="v2.11.4" \\\n      com.dcompany.zlib.version="1.3.2" \\\n      com.dcompany.zlib.source.sha256="bb329a0a2cd0274d05519d61c667c062e06990d72e125ee2dfa8de64f0119d16" \\\n      com.dcompany.zlib.null-guard.commit="e3dc0a85b7032e98380dec011bc8f2c2ee0d8fca" \\\n      com.dcompany.zlib.null-guard.sha256="183bc8b9dd078a41a62de5c2d905d9b0196b45bc46f100d7e4147ec228207c74" \\\n      com.dcompany.zlib.printf-return.commit="bbc2ccf3d0de267576b524b875c769a724a513b0" \\\n      com.dcompany.zlib.printf-return.sha256="7d00ee29be5e636d30da2890961e83e35cee0b152333ecd97a46ae2e71eb5d47" \\\n      com.dcompany.zlib.patch.commit="df84af25dc1942490e1d1c899a07619152a46148" \\\n      com.dcompany.zlib.patch.sha256="110ff14375733173d8aa54574473424fbd7dfe4b81f1ca34a759c6fe14b15b14" \\\n      com.dcompany.zlib.followup.commit="7235b0a581227c56a79a43ff828f8ef6794194c8" \\\n      com.dcompany.zlib.followup.sha256="96040ee84d0d187905283912dbd3f7b66ac2033976a2ceefe9b8cca63143d9c2"\n\nCOPY --from=caddy-builder /out/verify-caddy-system-ca /tmp/verify-caddy-system-ca\n\nRUN set -eux; \\\n    apk add --no-cache --upgrade \\\n      \'ca-certificates=20261020-r0\' \\\n',
         '      org.opencontainers.image.source="https://github.com/caddyserver/caddy" \\\n      com.dcompany.upstream.version="v2.11.4" \\\n      com.dcompany.zlib.version="1.4-r0" \\\n      com.dcompany.zlib.provenance="signed-alpine-3.23-apk" \\\n      com.dcompany.zlib.apk-port.commit="0436c6d655a04cbfd7987202a970f5aa7870a901"\n\nCOPY --from=caddy-builder /out/verify-caddy-system-ca /tmp/verify-caddy-system-ca\n\nRUN set -eux; \\\n    mkdir -p /etc/dcompany/caddy-zlib; \\\n    apk fetch --no-cache --output /etc/dcompany/caddy-zlib \'zlib=1.4-r0\'; \\\n    apk verify --no-network /etc/dcompany/caddy-zlib/zlib-1.4-r0.apk; \\\n    apk add --no-cache --upgrade \\\n      \'ca-certificates=20261020-r0\' \\\n'),
        ("      'libssl3=3.5.9-r0' \\\n      'brotli-libs=1.2.1-r0' \\\n      'zlib=1.3.2-r1'; \\\n    update-ca-certificates --fresh; \\\n    /tmp/verify-caddy-system-ca; \\\n",
         "      'libssl3=3.5.9-r0' \\\n      'brotli-libs=1.2.1-r0' \\\n      'zlib=1.4-r0'; \\\n    update-ca-certificates --fresh; \\\n    /tmp/verify-caddy-system-ca; \\\n"),
        ('    printf \'%s\\n\' "$installed_packages" | grep -Fx \'libssl3-3.5.9-r0\'; \\\n    printf \'%s\\n\' "$installed_packages" | grep -Fx \'brotli-libs-1.2.1-r0\'; \\\n    printf \'%s\\n\' "$installed_packages" | grep -Fx \'zlib-1.3.2-r1\'\n\nCOPY --from=zlib-builder /out/usr/lib/libz.so.1.3.2 /usr/lib/libz.so.1.3.2\nCOPY --from=zlib-builder /out/etc/dcompany/zlib-patch-evidence.env /etc/dcompany/zlib-patch-evidence.env\nCOPY --from=zlib-builder /out/zlib-runtime-probe /tmp/zlib-runtime-probe\nCOPY infra/docker/zlib/verify-patched-zlib.sh /tmp/verify-patched-zlib.sh\n\nRUN set -eux; \\\n    sh /tmp/verify-patched-zlib.sh; \\\n    rm -f /tmp/zlib-runtime-probe /tmp/verify-patched-zlib.sh\n\nCOPY --from=caddy-builder /out/caddy /usr/bin/caddy\n',
         '    printf \'%s\\n\' "$installed_packages" | grep -Fx \'libssl3-3.5.9-r0\'; \\\n    printf \'%s\\n\' "$installed_packages" | grep -Fx \'brotli-libs-1.2.1-r0\'; \\\n    printf \'%s\\n\' "$installed_packages" | grep -Fx \'zlib-1.4-r0\'\n\nCOPY --from=caddy-zlib-probe-builder /tmp/caddy-zlib-runtime-probe /usr/local/bin/dcompany-caddy-zlib-probe\nCOPY infra/docker/caddy-zlib/verify-official-zlib.sh /usr/local/bin/verify-caddy-zlib\n\nCOPY --from=caddy-builder /out/caddy /usr/bin/caddy\n'),
        ('RUN --mount=type=bind,source=infra/docker/install-pinned-musl-utils.sh,target=/tmp/install-pinned-musl-utils.sh,ro \\\n    sh /tmp/install-pinned-musl-utils.sh 3.23\n',
         'RUN --mount=type=bind,source=infra/docker/install-pinned-musl-utils.sh,target=/tmp/install-pinned-musl-utils.sh,ro \\\n    sh /tmp/install-pinned-musl-utils.sh 3.23\n\nRUN /usr/local/bin/verify-caddy-zlib\n'),
    ):
        expected = _replace_once(expected, before, after)
    expected = _replace_once(
        expected,
        "    apk verify --no-network /etc/dcompany/caddy-zlib/zlib-1.4-r0.apk; \\\n",
        "    apk verify --no-network /etc/dcompany/caddy-zlib/zlib-1.4-r0.apk; \\\n"
        "    chmod 0444 /etc/dcompany/caddy-zlib/zlib-1.4-r0.apk; \\\n",
    )
    expected = _replace_once(
        expected,
        "    apk fetch --no-cache --output /etc/dcompany/caddy-zlib 'zlib=1.4-r0'; \\\n"
        "    apk verify --no-network /etc/dcompany/caddy-zlib/zlib-1.4-r0.apk; \\\n",
        '    zlib_download_dir="$(mktemp -d)"; \\\n'
        '    apk fetch --no-cache --recursive --output "$zlib_download_dir" \'zlib=1.4-r0\'; \\\n'
        '    apk verify --no-network "$zlib_download_dir/zlib-1.4-r0.apk"; \\\n'
        '    cp "$zlib_download_dir/zlib-1.4-r0.apk" /etc/dcompany/caddy-zlib/zlib-1.4-r0.apk; \\\n',
    )
    expected = _replace_once(
        expected,
        "    chmod 0444 /etc/dcompany/caddy-zlib/zlib-1.4-r0.apk; \\\n",
        "    chmod 0444 /etc/dcompany/caddy-zlib/zlib-1.4-r0.apk; \\\n"
        '    rm -rf -- "$zlib_download_dir"; \\\n',
    )
    assert CADDY_DOCKERFILE.read_text(encoding="utf-8") == expected


@pytest.mark.parametrize("service", ("backend", "frontend", "caddy", "postgres"))
@pytest.mark.parametrize("mode", ("success", "fetch-failure", "signature-failure"))
def test_caddy_fetch_retains_only_verified_exact_archive(tmp_path: Path, mode: str, service: str) -> None:
    source = (ROOT / "infra/docker" / f"{service}.Dockerfile").read_text()
    start = source.index('    zlib_download_dir="$(mktemp -d)";')
    end = source.index('    rm -rf -- "$zlib_download_dir"', start)
    block = source[start:end] + '    rm -rf -- "$zlib_download_dir"\n'
    retained = tmp_path / "retained"
    retained.mkdir()
    downloads = tmp_path / "downloads"
    downloads.mkdir()
    fake_bin = tmp_path / "bin"
    fake_bin.mkdir()
    apk = fake_bin / "apk"
    apk.write_text(
        "#!/usr/bin/env python3\n"
        "import json, os, pathlib, stat, sys\n"
        "args = sys.argv[1:]\n"
        "retained = pathlib.Path(os.environ['RETAINED']) / 'zlib-1.4-r0.apk'\n"
        "if args[0] == 'fetch':\n"
        "    assert args[:4] == ['fetch', '--no-cache', '--recursive', '--output']\n"
        "    assert len(args) == 6 and args[5] == 'zlib=1.4-r0'\n"
        "    directory = pathlib.Path(args[4])\n"
        "    assert stat.S_IMODE(directory.stat().st_mode) == 0o700\n"
        "    with open(os.environ['EVENT_LOG'], 'a') as log: log.write(json.dumps(args) + '\\n')\n"
        "    if os.environ['MODE'] == 'fetch-failure': raise SystemExit(17)\n"
        "    (directory / 'zlib-1.4-r0.apk').write_bytes(b'exact-fixture-archive')\n"
        "    (directory / 'musl-dependency.apk').write_bytes(b'discarded-dependency')\n"
        "elif args[0] == 'verify':\n"
        "    assert args[:2] == ['verify', '--no-network'] and len(args) == 3\n"
        "    assert pathlib.Path(args[2]).name == 'zlib-1.4-r0.apk'\n"
        "    assert pathlib.Path(args[2]).read_bytes() == b'exact-fixture-archive'\n"
        "    assert not retained.exists(), 'archive promoted before authentication'\n"
        "    with open(os.environ['EVENT_LOG'], 'a') as log: log.write(json.dumps(args) + '\\n')\n"
        "    if os.environ['MODE'] == 'signature-failure': raise SystemExit(23)\n"
        "else: raise AssertionError('unexpected APK command')\n",
        encoding="utf-8",
    )
    apk.chmod(0o755)
    event_log = tmp_path / "events.jsonl"
    environment = {**os.environ, "PATH": str(fake_bin) + os.pathsep + os.environ["PATH"],
                   "MODE": mode, "EVENT_LOG": str(event_log), "RETAINED": str(retained),
                   "TMPDIR": str(downloads)}
    result = subprocess.run(
        ["sh", "-c", "set -eu\n" + block.replace('/etc/dcompany/caddy-zlib', str(retained)).replace('/etc/dcompany/zlib', str(retained))],
        env=environment, text=True, capture_output=True,
    )
    events = [json.loads(line) for line in event_log.read_text().splitlines()]
    if mode == "success":
        assert result.returncode == 0, result.stderr
        assert [event[0] for event in events] == ["fetch", "verify"]
        assert [path.name for path in retained.iterdir()] == ["zlib-1.4-r0.apk"]
        archive = retained / "zlib-1.4-r0.apk"
        assert archive.read_bytes() == b"exact-fixture-archive"
        assert archive.stat().st_mode & 0o777 == 0o444
        assert list(downloads.iterdir()) == []
    else:
        assert result.returncode == (17 if mode == "fetch-failure" else 23), result.stderr
        assert list(retained.iterdir()) == []
        assert [event[0] for event in events] == (["fetch"] if mode == "fetch-failure" else ["fetch", "verify"])


REDIS_NATIVE_CACHE_ACQUISITION = r'''    zlib_download_dir="$(mktemp -d)"; \
    trap 'rm -rf -- "$zlib_download_dir"' EXIT; \
    mkdir "$zlib_download_dir/cache"; \
    apk --cache-dir "$zlib_download_dir/cache" update; \
    apk --cache-dir "$zlib_download_dir/cache" cache download 'zlib=1.4-r0'; \
    zlib_archive=; \
    zlib_archive_count=0; \
    for cached_apk in "$zlib_download_dir/cache"/zlib*; do \
      if [ ! -e "$cached_apk" ] && [ ! -L "$cached_apk" ]; then continue; fi; \
      test -f "$cached_apk"; \
      test ! -L "$cached_apk"; \
      case "${cached_apk##*/}" in \
        zlib-1.4-r0.[0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f].apk) ;; \
        *) echo 'Invalid cached zlib archive.' >&2; exit 1 ;; \
      esac; \
      zlib_archive_count=$((zlib_archive_count + 1)); \
      zlib_archive=$cached_apk; \
    done; \
    if [ "$zlib_archive_count" -ne 1 ]; then \
      apk --no-network --cache-dir "$zlib_download_dir/cache" policy zlib 2>&1 | head -n 30 >&2 || true; \
      exit 1; \
    fi; \
    apk verify --no-network "$zlib_archive"; \
    cp "$zlib_archive" /etc/dcompany/zlib/zlib-1.4-r0.apk; \
    chmod 0444 /etc/dcompany/zlib/zlib-1.4-r0.apk; \
    rm -rf -- "$zlib_download_dir"; \
    trap - EXIT'''


@pytest.mark.parametrize("mode", (
    "success", "update-failure", "download-failure", "no-archive", "no-archive-policy-error", "duplicate", "wrong-version",
    "short-hash", "uppercase-hash", "bad-hash", "wrong-extension", "symlink", "dangling-symlink",
    "directory", "signature-failure", "valid-and-wrong-version", "valid-and-malformed", "valid-and-symlink",
    "newline-name",
))
def test_redis_native_cache_requires_one_exact_authenticated_archive(tmp_path: Path, mode: str) -> None:
    source = REDIS_DOCKERFILE.read_text()
    start = source.index('    zlib_download_dir="$(mktemp -d)";')
    end = source.index('    trap - EXIT', start) + len('    trap - EXIT')
    block = source[start:end]
    for forbidden in ('--no-cache', '--latest', '--upgrade', '--add-dependencies', 'apk fetch', 'wget', 'curl'):
        assert forbidden not in block
    retained, downloads, fake_bin = (tmp_path / name for name in ('retained', 'downloads', 'bin'))
    for directory in (retained, downloads, fake_bin): directory.mkdir()
    event_log = tmp_path / 'events.jsonl'
    apk = fake_bin / 'apk'
    apk.write_text(
        '#!/usr/bin/env python3\n'
        'import json, os, pathlib, stat, sys\n'
        'args = sys.argv[1:]\n'
        'with open(os.environ["EVENT_LOG"], "a") as log: log.write(json.dumps(args) + "\\n")\n'
        'mode = os.environ["MODE"]\n'
        'if args[:1] == ["--cache-dir"]:\n'
        '    cache = pathlib.Path(args[1])\n'
        '    assert cache.is_dir() and stat.S_IMODE(cache.parent.stat().st_mode) == 0o700\n'
        '    if args[2:] == ["update"]:\n'
        '        assert list(cache.iterdir()) == []\n'
        '        if mode == "update-failure": raise SystemExit(17)\n'
        '        (cache / "APKINDEX.fixture.tar.gz").write_bytes(b"index fixture")\n'
        '    else:\n'
        '        assert args[2:] == ["cache", "download", "zlib=1.4-r0"]\n'
        '        assert (cache / "APKINDEX.fixture.tar.gz").exists()\n'
        '        if mode == "download-failure": raise SystemExit(19)\n'
        '        if mode in ("no-archive", "no-archive-policy-error"): raise SystemExit(0)\n'
        '        (cache / "musl-dependency.12345678.apk").write_bytes(b"discarded dependency")\n'
        '        names = {\n'
        '            "wrong-version": ["zlib-1.3.2-r0.c0ffee12.apk"],\n'
        '            "short-hash": ["zlib-1.4-r0.1234567.apk"],\n'
        '            "uppercase-hash": ["zlib-1.4-r0.C0FFEE12.apk"],\n'
        '            "bad-hash": ["zlib-1.4-r0.zzzzzzzz.apk"],\n'
        '            "wrong-extension": ["zlib-1.4-r0.c0ffee12.apk.extra"],\n'
        '            "duplicate": ["zlib-1.4-r0.c0ffee12.apk", "zlib-1.4-r0.01234567.apk"],\n'
        '            "valid-and-wrong-version": ["zlib-1.4-r0.c0ffee12.apk", "zlib-1.3.2-r0.12345678.apk"],\n'
        '            "valid-and-malformed": ["zlib-1.4-r0.c0ffee12.apk", "zlib-1.4-r0.bad.apk"],\n'
        '            "newline-name": ["zlib-junk\\nzlib-1.4-r0.c0ffee12.apk"],\n'
        '        }.get(mode, ["zlib-1.4-r0.c0ffee12.apk"])\n'
        '        for name in names:\n'
        '            path = cache / name\n'
        '            if mode == "directory": path.mkdir()\n'
        '            elif mode == "dangling-symlink": path.symlink_to(cache / "absent")\n'
        '            elif mode == "symlink":\n'
        '                target = cache / "linked-payload"; target.write_bytes(b"exact-fixture-archive"); path.symlink_to(target)\n'
        '            else: path.write_bytes(b"exact-fixture-archive")\n'
        '        if mode == "valid-and-symlink": (cache / "zlib-1.4-r0.abcdef12.apk").symlink_to(cache / names[0])\n'
        'elif args[:2] == ["verify", "--no-network"]:\n'
        '    assert len(args) == 3\n'
        '    path = pathlib.Path(args[2])\n'
        '    assert path.name == "zlib-1.4-r0.c0ffee12.apk" and not path.is_symlink()\n'
        '    assert path.read_bytes() == b"exact-fixture-archive"\n'
        '    assert not (pathlib.Path(os.environ["RETAINED"]) / "zlib-1.4-r0.apk").exists()\n'
        '    if mode == "signature-failure": raise SystemExit(23)\n'
        'elif args[:1] == ["--no-network"]:\n'
        '    assert len(args) == 5 and args[1] == "--cache-dir" and args[3:] == ["policy", "zlib"]\n'
        '    assert pathlib.Path(args[2]).is_dir()\n'
        '    if mode == "no-archive-policy-error":\n'
        '        print("PUBLIC_POLICY_ERROR"); raise SystemExit(41)\n'
        '    for index in range(100): print("PUBLIC_POLICY_VERSION_" + str(index))\n'
        'else: raise AssertionError("unexpected APK command")\n'
    )
    apk.chmod(0o755)
    result = subprocess.run(['sh', '-c', 'set -eu\n' + block.replace('/etc/dcompany/zlib', str(retained))],
                            env={**os.environ, 'PATH': str(fake_bin) + os.pathsep + os.environ['PATH'],
                                 'MODE': mode, 'EVENT_LOG': str(event_log), 'RETAINED': str(retained),
                                 'TMPDIR': str(downloads)}, text=True, capture_output=True)
    events = [json.loads(line) for line in event_log.read_text().splitlines()]
    assert events[0][0] == '--cache-dir' and events[0][2:] == ['update']
    if mode != 'update-failure':
        assert events[1][:2] == events[0][:2]
        assert events[1][2:] == ['cache', 'download', 'zlib=1.4-r0']
    assert list(downloads.iterdir()) == [], 'private cache must be removed on success and failure'
    if mode == 'success':
        assert result.returncode == 0, result.stderr
        assert len(events) == 3 and events[2][:2] == ['verify', '--no-network']
        assert [path.name for path in retained.iterdir()] == ['zlib-1.4-r0.apk']
        archive = retained / 'zlib-1.4-r0.apk'
        assert archive.read_bytes() == b'exact-fixture-archive'
        assert archive.stat().st_mode & 0o777 == 0o444
    else:
        assert result.returncode == {'update-failure': 17, 'download-failure': 19, 'signature-failure': 23}.get(mode, 1), result.stderr
        assert list(retained.iterdir()) == []
        count_failure = mode in ('no-archive', 'no-archive-policy-error', 'duplicate')
        assert len(events) == (1 if mode == 'update-failure' else 3 if mode == 'signature-failure' or count_failure else 2)
        if count_failure:
            assert events[-1][0] == '--no-network' and events[-1][3:] == ['policy', 'zlib']
            if mode == 'no-archive-policy-error': assert result.stderr == 'PUBLIC_POLICY_ERROR\n'
            else: assert result.stderr.count('PUBLIC_POLICY_VERSION_') == 30


def _official_zlib_expected(expected: str, service: str) -> str:
    s = expected
    pattern=r'(FROM [^\n]+) AS zlib-builder\nWORKDIR /tmp/dcompany-zlib\nRUN apk add --no-cache --virtual .zlib-build-deps build-base patch\nCOPY infra/docker/zlib/ /tmp/dcompany-zlib/\nRUN sh /tmp/dcompany-zlib/build-patched-zlib.sh'
    s,n=re.subn(pattern,r'\1 AS zlib-probe-builder\nRUN apk add --no-cache --virtual .zlib-probe-build-deps build-base\nCOPY infra/docker/caddy-zlib/runtime-probe.c /tmp/zlib-runtime-probe.c\nRUN cc -O2 -Wall -Wextra -Werror /tmp/zlib-runtime-probe.c -ldl -o /tmp/dcompany-caddy-zlib-probe',s);assert n==1,service
    port='d3332621d44c2c4cf52e52217b16674db190212c' if service=='redis' else 'bdf501c870a92a05c097857401f69c48c4810384'
    a=s.index('      com.dcompany.zlib.version=');b=s.index('\n',s.index('com.dcompany.zlib.followup.sha256=',a))
    s=s[:a]+f'''      com.dcompany.zlib.version="1.4-r0" \\
      com.dcompany.zlib.provenance="signed-alpine-apk" \\
      com.dcompany.zlib.apk-port.commit="{port}"'''+s[b:]
    a=s.index('COPY --from=zlib-builder');b=s.index('\n',s.index('COPY infra/docker/zlib/verify-patched-zlib.sh',a))
    s=s[:a]+'''COPY --from=zlib-probe-builder /tmp/dcompany-caddy-zlib-probe /usr/local/bin/dcompany-caddy-zlib-probe
COPY infra/docker/caddy-zlib/verify-official-zlib.sh /usr/local/bin/verify-caddy-zlib

# Retain only the authenticated exact archive; downloaded dependencies are discarded.
RUN set -eux; \\
    mkdir -p /etc/dcompany/zlib; \\
    zlib_download_dir="$(mktemp -d)"; \\
    apk fetch --no-cache --recursive --output "$zlib_download_dir" 'zlib=1.4-r0'; \\
    apk verify --no-network "$zlib_download_dir/zlib-1.4-r0.apk"; \\
    cp "$zlib_download_dir/zlib-1.4-r0.apk" /etc/dcompany/zlib/zlib-1.4-r0.apk; \\
    chmod 0444 /etc/dcompany/zlib/zlib-1.4-r0.apk; \\
    rm -rf -- "$zlib_download_dir"
'''+s[b:]
    # Upgrade only the runtime package transaction; preserve all existing pins.
    runtime=s.index('# --- runtime ---') if '# --- runtime ---' in s else s.index('ARG APP_VERSION')
    a=s.index('apk add --no-cache --upgrade',runtime)
    s=s[:a]+s[a:].replace('apk add --no-cache --upgrade \\\n', "apk add --no-cache --upgrade \\\n      'zlib=1.4-r0' \\\n",1)
    # Redis's package transaction precedes the retained-archive block; still exact1.4.
    s=s.replace("'zlib-1.3.2-r0'", "'zlib-1.4-r0'").replace("'zlib-1.3.2-r1'", "'zlib-1.4-r0'")
    s=s.replace('    && sh /tmp/verify-patched-zlib.sh \\\n','').replace('    sh /tmp/verify-patched-zlib.sh; \\\n','')
    s=s.replace('    && rm -f /tmp/zlib-runtime-probe /tmp/verify-patched-zlib.sh \\\n','')
    s=s.replace('    rm -f /tmp/zlib-runtime-probe /tmp/verify-patched-zlib.sh; \\\n','')
    s=s.replace('    rm -f /tmp/zlib-runtime-probe /tmp/verify-patched-zlib.sh\n','')
    if service=='frontend':s=s.replace("    apk info -v | grep -Fx 'zlib-1.4-r0'; \\\n", "    apk info -v | grep -Fx 'zlib-1.4-r0'\n")
    if service=='redis':s=s.replace("    redis-server --version | grep -F 'v=7.4.11'; \\\n", "    redis-server --version | grep -F 'v=7.4.11'\n")
    marker='USER erp' if service=='backend' else None
    proof=f'RUN /usr/local/bin/verify-caddy-zlib {service}\n'
    if marker:s=s.replace(marker,proof+marker,1)
    else:s=s.rstrip()+'\n\n'+proof
    s=s.replace('# Build the independently verified zlib correction in a discarded stage.','# Build only the runtime verifier in a discarded stage; use the signed official zlib package.')
    s=s.replace('# Install packages before overlaying the independently verified zlib runtime\n# so package transactions cannot replace the reviewed shared object.','# Authenticate the exact official zlib package and prove the final mapped runtime.')
    if service == "redis":
        a = s.index('RUN apk add --no-cache --upgrade')
        b = s.index('\n\n', a)
        transaction = s[a:b]
        s = s[:a] + s[b:]
        pos = s.index('    rm -rf -- "$zlib_download_dir"') + len('    rm -rf -- "$zlib_download_dir"')
        s = s[:pos] + '\n\n' + transaction + s[pos:]
    s = s.replace("# Preserve Redis 7.4.11 and upgrade only its OpenSSL security packages.",
                  "# Preserve Redis 7.4.11 and upgrade its OpenSSL and zlib security packages.")
    if service == "redis":
        s = _replace_once(s, r'''    zlib_download_dir="$(mktemp -d)"; \
    apk fetch --no-cache --recursive --output "$zlib_download_dir" 'zlib=1.4-r0'; \
    apk verify --no-network "$zlib_download_dir/zlib-1.4-r0.apk"; \
    cp "$zlib_download_dir/zlib-1.4-r0.apk" /etc/dcompany/zlib/zlib-1.4-r0.apk; \
    chmod 0444 /etc/dcompany/zlib/zlib-1.4-r0.apk; \
    rm -rf -- "$zlib_download_dir"''', REDIS_NATIVE_CACHE_ACQUISITION)
    return s


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
    historical = expected + "\n" + MUSL_UTILS_INSTALL + "3.24\n"
    assert _file_at("b9b19bd2462e0f417922849a455e12116e8207e2", POSTGRES_DOCKERFILE) == historical
    expected = _official_zlib_expected(historical, "postgres")
    for name, version in (("libxslt", "1.1.43-r4"), ("krb5-libs", "1.22.3-r0")):
        pin = "      'libuuid=2.42.3-r1'; \\\n"
        check = '    printf \'%s\\n\' "$installed_packages" | grep -Fx \'libuuid-2.42.3-r1\'; \\\n'
        expected = _replace_once(expected, pin, f"      '{name}={version}' \\\n" + pin)
        expected = _replace_once(
            expected, check,
            f'    printf \'%s\\n\' "$installed_packages" | grep -Fx \'{name}-{version}\'; \\\n' + check,
        )
    # Preserve the complete reviewed r0 recipe, then permit only the vendor
    # Alpine3.24 revision in installation pins and installed-package checks.
    assert _file_at("f3178f70b4994c46fd0d2e48af76bbb7606c941b", POSTGRES_DOCKERFILE) == expected
    expected = expected.replace("3.5.9-r0", "3.5.9-r1")
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
    # Build55 adds only the reviewed Python/system-CA trust correction. Keep
    # every historical transform above and full-file equality below intact.
    helper_copy = "COPY infra/scripts/bootstrap-verified-pip.py /tmp/bootstrap-verified-pip.py\n"
    helper_install = "RUN python -I -B /tmp/bootstrap-verified-pip.py && rm /tmp/bootstrap-verified-pip.py\n"
    for environment in (
        "ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 PIP_NO_CACHE_DIR=1\n",
        "ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1\n",
    ):
        expected = _replace_once(expected, environment, environment + helper_copy)
    ca_install = _replace_once(
        package_install,
        "      'libuuid=2.42.3-r1' \\\n",
        "      'libuuid=2.42.3-r1' \\\n      'ca-certificates=20261020-r0' \\\n      'ca-certificates-bundle=20261020-r0' \\\n",
    )
    ca_install = _replace_once(
        ca_install,
        '    && printf \'%s\\n\' "$installed_packages" | grep -Fx \'libuuid-2.42.3-r1\' \\\n',
        '    && printf \'%s\\n\' "$installed_packages" | grep -Fx \'libuuid-2.42.3-r1\' \\\n    && printf \'%s\\n\' "$installed_packages" | grep -Fx \'ca-certificates-20261020-r0\' \\\n    && printf \'%s\\n\' "$installed_packages" | grep -Fx \'ca-certificates-bundle-20261020-r0\' \\\n    && update-ca-certificates \\\n    && python -I -B /tmp/bootstrap-verified-pip.py --verify-system-ca \\\n',
    )
    assert expected.count(package_install) == 2
    expected = expected.replace(package_install, ca_install)
    expected = _replace_once(expected, "COPY backend/requirements.lock .\n", "COPY backend/requirements.lock .\n" + helper_install)
    expected = _replace_once(expected, "WORKDIR /app\nCOPY --from=builder /install", helper_install + "WORKDIR /app\nCOPY --from=builder /install")
    # Build55's later CPython trust-store fix is an exact additive transform.
    # Keep the historical 3.14.7 -> 3.14.8 reconstruction above unchanged.
    assert expected.count("python:3.14.8-alpine3.24@sha256:f6a589d43c42b9e7f7dc67a12d37132491f362859a5d750607710cc56da3bc72") == 3
    expected = expected.replace(
        "python:3.14.8-alpine3.24@sha256:f6a589d43c42b9e7f7dc67a12d37132491f362859a5d750607710cc56da3bc72",
        "python:3.14.10-alpine3.24@sha256:eb2c62658b8d51bff0acc408504b76521d02030de56a8a3c1e830083e89b89d2",
    )
    expected = _replace_once(expected, 'com.dcompany.python.version="3.14.8"', 'com.dcompany.python.version="3.14.10"')
    expected = _replace_once(expected, "'Python 3.14.8'", "'Python 3.14.10'")
    expected = _replace_once(
        expected,
        "    && sh /tmp/verify-patched-zlib.sh \\\n",
        "    && sh /tmp/verify-patched-zlib.sh \\\n"
        + "    && printf '%s\\n' \"$installed_packages\" | grep -Fx 'zlib-1.3.2-r1' \\\n",
    )
    # Build64 removes only the optional SQLite production profile. Retain all
    # historical transforms above and the complete exact-file comparison.
    expected = _replace_once(
        expected,
        "WORKDIR /app\nCOPY --from=builder /install /usr/local\nCOPY backend/ .\n",
        "COPY infra/docker/prune-python-sqlite.sh /tmp/prune-python-sqlite.sh\n"
        "RUN sh /tmp/prune-python-sqlite.sh\n"
        "WORKDIR /app\nCOPY --from=builder /install /usr/local\n"
        "RUN sh /tmp/prune-python-sqlite.sh --after-wheels && rm /tmp/prune-python-sqlite.sh\n"
        "COPY backend/ .\n"
        "COPY infra/docker/verify-backend-runtime-profile.py /usr/local/bin/verify-backend-runtime-profile.py\n",
    )
    historical = expected
    assert _file_at("b9b19bd2462e0f417922849a455e12116e8207e2", BACKEND_DOCKERFILE) == historical
    expected = _official_zlib_expected(historical, "backend")
    builder_anchor = "# --- Python dependency builder ---\n"
    expected = _replace_once(expected, builder_anchor,
        "# Backport the upstream CVE-2026-119170 fix to the official CPython ABI.\n"
        "FROM python:3.14.10-alpine3.24@sha256:eb2c62658b8d51bff0acc408504b76521d02030de56a8a3c1e830083e89b89d2 AS hashlib-backport\n"
        "RUN apk add --no-cache 'gcc=15.2.0-r5' 'musl-dev=1.2.6-r3' 'binutils=2.45.1-r1' \\\n"
        "      'openssl-dev=3.5.9-r0' 'patch=2.8.1-r0'\n"
        "WORKDIR /tmp/cpython\n"
        "COPY infra/docker/cpython/cpython-7d9da920.patch .\n"
        "COPY infra/docker/cpython/build-hashlib-backport.py .\n"
        "COPY infra/docker/cpython/verify-hashlib-fips.py ./verify_hashlib_fips.py\n"
        "RUN python -B /tmp/cpython/build-hashlib-backport.py\n\n" + builder_anchor,
    )
    entrypoint = "COPY infra/docker/backend-entrypoint.sh /entrypoint.sh\n"
    expected = _replace_once(expected, entrypoint,
        "COPY --from=hashlib-backport /out/_hashlib*.so /usr/local/lib/python3.14/lib-dynload/\n"
        "COPY --from=hashlib-backport /out/cpython-hashlib-build.json /etc/dcompany/cpython-hashlib-build.json\n"
        "COPY --chmod=0644 infra/docker/cpython/verify-hashlib-fips.py /usr/local/bin/verify-hashlib-fips.py\n" + entrypoint,
    )
    expected = _replace_once(expected, "USER erp\n", "USER erp\nRUN python -I -B /usr/local/bin/verify-hashlib-fips.py\n")
    # Preserve the complete reviewed r0 recipe, then permit only the vendor
    # Alpine3.24 revision in installation pins and installed-package checks.
    assert _file_at("f3178f70b4994c46fd0d2e48af76bbb7606c941b", BACKEND_DOCKERFILE) == expected
    expected = expected.replace("3.5.9-r0", "3.5.9-r1")
    assert BACKEND_DOCKERFILE.read_text(encoding="utf-8") == expected


def test_backend_python_patch_version_is_exact_in_ci_and_release() -> None:
    ci = CI_WORKFLOW.read_text(encoding="utf-8")
    release = RELEASE_WORKFLOW.read_text(encoding="utf-8")

    assert ci.count("python-version: '3.14.10'") == 1
    assert release.count("python-version: '3.14.10'") == 1
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
    # Copied public files inherit private source modes from the installer.
    # Permit only these exact public-root modes; retain the full-file equality
    # check so broader paths, writable modes or unrelated image changes fail.
    expected = _replace_once(
        expected,
        "    && rm /tmp/write-release-identity.sh\n",
        "    && rm /tmp/write-release-identity.sh \\\n"
        "    && find /usr/share/nginx/html -type d -exec chmod 0755 {} + \\\n"
        "    && find /usr/share/nginx/html -type f -exec chmod 0644 {} +\n",
    )
    # Build55 upgrades only the signed nginx APK; retain the pinned base,
    # r0 zlib overlay and full inherited-file equality. No scan exception.
    expected = _replace_once(
        expected,
        'FROM nginx:alpine-slim@sha256:3b171d7224b669faa3cc2137fea0a65301791df1ec1f271ebd2a2b7461f7fade\n'
        'ARG APP_VERSION=dev\n',
        'FROM nginx:alpine-slim@sha256:3b171d7224b669faa3cc2137fea0a65301791df1ec1f271ebd2a2b7461f7fade\n'
        'ENV NGINX_VERSION=1.31.8\n'
        'ARG APP_VERSION=dev\n',
    )
    expected = _replace_once(
        expected,
        "      'pcre2=10.49-r0'; \\\n"
        '    installed_packages="$(apk info -v)"; \\\n',
        "      'pcre2=10.49-r0'; \\\n"
        '    test "$(apk --print-arch)" = \'x86_64\'; \\\n'
        "    printf '%s\\n' 'e09fa32f0a0eab2b879ccbbc4d0e4fb9751486eedda75e35fac65802cc9faa266425edf83e261137a2f4d16281ce2c1a5f4502930fe75154723da014214f0655  /etc/apk/keys/nginx_signing.rsa.pub' | sha512sum -c -; \\\n"
        "    wget -T 30 -qO /tmp/dcompany-nginx-1.31.8-r1.apk 'https://nginx.org/packages/mainline/alpine/v3.24/main/x86_64/nginx-1.31.8-r1.apk'; \\\n"
        "    printf '%s\\n' '51bb8ca7072b422c9355656e45a1aafec24eb1606adb3a84c562f80731d3f848  /tmp/dcompany-nginx-1.31.8-r1.apk' | sha256sum -c -; \\\n"
        '    apk add --no-cache --no-network --upgrade --repositories-file /dev/null /tmp/dcompany-nginx-1.31.8-r1.apk; \\\n'
        '    rm /tmp/dcompany-nginx-1.31.8-r1.apk; \\\n'
        '    installed_packages="$(apk info -v)"; \\\n',
    )
    expected = _replace_once(
        expected,
        '    printf \'%s\\n\' "$installed_packages" | grep -Fx \'pcre2-10.49-r0\'; \\\n',
        '    printf \'%s\\n\' "$installed_packages" | grep -Fx \'pcre2-10.49-r0\'; \\\n'
        '    printf \'%s\\n\' "$installed_packages" | grep -Fx \'nginx-1.31.8-r1\'; \\\n'
        '    test "$NGINX_VERSION" = \'1.31.8\'; \\\n'
        "    nginx -v 2>&1 | grep -Fx 'nginx version: nginx/1.31.8'; \\\n",
    )
    expected = _replace_once(
        expected,
        "COPY infra/nginx/frontend.conf",
        MUSL_UTILS_INSTALL + "3.24\nCOPY infra/nginx/frontend.conf",
    )
    historical = expected
    assert _file_at("b9b19bd2462e0f417922849a455e12116e8207e2", FRONTEND_DOCKERFILE) == historical
    expected = _replace_once(
        _official_zlib_expected(historical, "frontend"),
        "RUN npm ci --no-audit --no-fund\n",
        "COPY frontend/vendor/picomatch-v2 ./vendor/picomatch-v2\n"
        "COPY frontend/vendor/picomatch-v4 ./vendor/picomatch-v4\n"
        "RUN npm ci --no-audit --no-fund\n",
    )
    # Preserve the complete reviewed r0 recipe, then permit only the vendor
    # Alpine3.24 revision in installation pins and installed-package checks.
    assert _file_at("f3178f70b4994c46fd0d2e48af76bbb7606c941b", FRONTEND_DOCKERFILE) == expected
    expected = expected.replace("3.5.9-r0", "3.5.9-r1")
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
    historical = expected + "\n" + MUSL_UTILS_INSTALL + "3.21\n"
    assert _file_at("b9b19bd2462e0f417922849a455e12116e8207e2", REDIS_DOCKERFILE) == historical
    assert REDIS_DOCKERFILE.read_text(encoding="utf-8") == _official_zlib_expected(historical, "redis")


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

        build67 = _file_at(BUILD67_RELEASE_BASE, workflow)
        build69 = _replace_once(build67, BUILD67_BINARY_SHA256, BUILD69_BINARY_SHA256)
        assert _file_at(BUILD69_RELEASE_BASE, workflow) == build69
        expected = _replace_once(build69, BUILD69_BINARY_SHA256, CURRENT_BINARY_SHA256)
        expected = _replace_once(
            expected, "caddy version | grep -F v2.11.4-dcompany.2;",
            "caddy version | grep -F v2.11.4-dcompany.3;",
        )
        backend_test_step = (
            "      - name: Tests\n"
            if workflow == CI_WORKFLOW
            else "      - name: Run complete backend suite\n        working-directory: backend\n"
        )
        backend_test_env = backend_test_step + "        env:\n"
        expected = _replace_once(expected, backend_test_env, backend_test_env + "          ENV: test\n")
        instrumentation_step = "      - name: Run Android instrumentation tests\n"
        assembly_anchor = (
            "      # Room migration tests need a real Android SQLite/Room runtime. Keeping\n"
            if workflow == CI_WORKFLOW
            else instrumentation_step
        )
        assembly = (
            "      - name: Assemble Android instrumentation APKs before emulator startup\n"
            "        run: bash scripts/run_android_instrumentation_ci.sh --assemble-only\n"
        )
        expected = _replace_once(expected, assembly_anchor, assembly + assembly_anchor)
        emulator_action = instrumentation_step + (
            "        uses: reactivecircus/android-emulator-runner@"
            "a421e43855164a8197daf9d8d40fe71c6996bb0d # v2.38.0\n"
        )
        expected = _replace_once(
            expected, emulator_action,
            emulator_action + "        env:\n          D_COMPANY_HOSTED_COLD_BOOT: '1'\n",
        )
        if workflow == CI_WORKFLOW:
            expected = _replace_once(
                expected,
                "          # Pin the reviewed emulator 37.1.11 build used by the bounded host comparison.\n",
                "          # Pin the official 37.2.13 stable build after the 37.1.11 native crash.\n",
            )
        expected = _replace_once(expected, "          emulator-build: 15917651\n", "          emulator-build: 16740729\n")
        renderer_comment = (
            "          # Request swiftshader directly with Vulkan disabled. These flags do\n"
            "          # not prove the selected mode; retained runtime diagnostics must.\n"
            if workflow == CI_WORKFLOW
            else "          # Match CI's direct swiftshader request with Vulkan disabled. The\n"
            "          # retained runtime diagnostics must prove the actually selected mode.\n"
        )
        expected = _replace_once(
            expected,
            renderer_comment + "          emulator-options: -no-window -gpu swiftshader -feature -Vulkan -no-snapshot -noaudio -no-boot-anim\n",
            "          # Use ANGLE over SwiftShader while keeping guest Vulkan disabled.\n"
            "          # The test process verifies the renderer from its startup log.\n"
            "          emulator-options: -no-window -gpu swangle -feature -Vulkan -no-snapshot -noaudio -no-boot-anim\n",
        )
        assert workflow.read_text(encoding="utf-8") == expected

    assert "go 1.26.9" in GO_MOD.read_text(encoding="utf-8")
    assert "github.com/caddyserver/caddy/v2 v2.11.4" in GO_MOD.read_text(
        encoding="utf-8"
    )
    assert "v2.11.4-dcompany.3" in CADDY_DOCKERFILE.read_text(encoding="utf-8")
