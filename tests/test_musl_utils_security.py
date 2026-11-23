from __future__ import annotations

import hashlib
import os
import subprocess
from pathlib import Path

import pytest


ROOT = Path(__file__).resolve().parents[1]
HELPER = ROOT / "infra/docker/install-pinned-musl-utils.sh"
KEY = "alpine-devel@lists.alpinelinux.org-6165ee59.rsa.pub"
KEY_SHA256 = "207e4696d3c05f7cb05966aee557307151f1f00217af4143c1bcaf33b8df733f"
PINS = {
    "3.24": ("1.2.6-r3", "47263d05842c5e12d01e0369e5b5bea51733323501c00ca21968a5102de137ca"),
    "3.23": ("1.2.5-r24", "ee9a8106067ead36b8930f5ccdb3e515574871ac8e07f88cc7bab7049effc945"),
    "3.21": ("1.2.5-r12", "42bb107b4f139a289b146ac0404e222c0ae74ad759add38ac69b0063d2a5164c"),
}


def test_branch_pins_bind_official_package_bytes_and_trusted_key():
    source = HELPER.read_text()
    for branch, (version, digest) in PINS.items():
        assert f"  {branch})\n    version={version}\n    package_sha256={digest}\n" in source
    assert f"key={KEY}\n" in source
    assert KEY_SHA256 in source
    assert "https://dl-cdn.alpinelinux.org/alpine/v$branch/main/x86_64/musl-utils-$version.apk" in source
    assert "--allow-untrusted" not in source


@pytest.mark.parametrize(
    ("case", "downloads", "installs", "succeeds"),
    [
        ("valid", True, True, True),
        ("wrong-branch", False, False, False),
        ("wrong-arch", False, False, False),
        ("wrong-libc-before", False, False, False),
        ("wrong-key", False, False, False),
        ("wrong-package", True, False, False),
        ("signature-refused", True, True, False),
        ("unchanged-utils", True, True, False),
        ("wrong-libc-after", True, True, False),
    ],
)
def test_helper_fails_closed_at_each_package_boundary(tmp_path, case, downloads, installs, succeeds):
    # Replace only external input bytes and fixture paths. The production helper
    # still performs both real SHA checks and calls APK with its actual arguments.
    key_bytes = b"fixture key\n"
    package_bytes = b"fixture package\n"
    release = tmp_path / "alpine-release"
    release.write_text("3.23.8\n" if case == "wrong-branch" else "3.24.3\n")
    keys = tmp_path / "keys"
    keys.mkdir()
    (keys / KEY).write_bytes(b"wrong key\n" if case == "wrong-key" else key_bytes)
    script = HELPER.read_text()
    for original, replacement in (
        ("/etc/alpine-release", str(release)),
        ("/etc/apk/keys/", f"{keys}/"),
        (KEY_SHA256, hashlib.sha256(key_bytes).hexdigest()),
        (PINS["3.24"][1], hashlib.sha256(package_bytes).hexdigest()),
    ):
        assert script.count(original) == 1
        script = script.replace(original, replacement)
    (tmp_path / "payload").write_bytes(b"wrong package\n" if case == "wrong-package" else package_bytes)
    fixtures = {
        "wget": '''
test "$#" -eq 5
test "$1" = -T && test "$2" = 30 && test "$3" = -qO
test "$5" = https://dl-cdn.alpinelinux.org/alpine/v3.24/main/x86_64/musl-utils-1.2.6-r3.apk
touch "$TEST_DIR/downloaded"
cp "$TEST_DIR/payload" "$4"
''',
        "apk": '''
case "$1" in
  --print-arch)
    if [ "$TEST_CASE" = wrong-arch ]; then echo aarch64; else echo x86_64; fi
    ;;
  info)
    if [ "$2" = -e ]; then test "$3" = musl-utils; exit; fi
    test "$2" = -v
    if [ "$TEST_CASE" = wrong-libc-before ] ||
       { [ -e "$TEST_DIR/installed" ] && [ "$TEST_CASE" = wrong-libc-after ]; }; then
      echo musl-1.2.6-r2
    else
      echo musl-1.2.6-r3
    fi
    if [ -e "$TEST_DIR/installed" ] && [ "$TEST_CASE" != unchanged-utils ]; then
      echo musl-utils-1.2.6-r3
    else
      echo musl-utils-1.2.6-r2
    fi
    ;;
  add)
    test "$#" -eq 9
    test "$2" = --keys-dir
    test "$(ls "$3")" = alpine-devel@lists.alpinelinux.org-6165ee59.rsa.pub
    test "$4 $5 $6 $7 $8" = '--no-cache --no-network --upgrade --repositories-file /dev/null'
    test "$(dirname "$9")/keys" = "$3"
    test "$(basename "$9")" = musl-utils-1.2.6-r3.apk
    cmp "$9" "$TEST_DIR/payload"
    test ! -e "$TEST_DIR/installed"
    touch "$TEST_DIR/installed"
    test "$TEST_CASE" != signature-refused
    ;;
  *) exit 1 ;;
esac
''',
    }
    for command, fixture in fixtures.items():
        path = tmp_path / command
        path.write_text("#!/bin/sh\nset -eu\n" + fixture)
        path.chmod(0o700)
    result = subprocess.run(
        ["sh", "-c", script, "test-musl-utils", "3.24"],
        env={**os.environ, "PATH": f"{tmp_path}:{os.environ['PATH']}",
             "TEST_DIR": str(tmp_path), "TEST_CASE": case, "TMPDIR": str(tmp_path)},
        capture_output=True, text=True, check=False, timeout=10,
    )
    assert (result.returncode == 0) == succeeds, (result.stdout, result.stderr)
    assert (tmp_path / "downloaded").exists() == downloads
    assert (tmp_path / "installed").exists() == installs
    assert not list(tmp_path.glob("tmp.*"))
