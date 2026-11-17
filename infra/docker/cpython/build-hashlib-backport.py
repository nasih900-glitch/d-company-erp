"""Build Python's exact security backport against the official installed ABI."""

import hashlib
import json
import os
from pathlib import Path
import shlex
import subprocess
import sys
import sysconfig
import tarfile
import urllib.request

from verify_hashlib_fips import PATCH_SHA256, PATCHED_SOURCE_SHA256, SOURCE_SHA256, EXTENSION, MODULE_PATH


def verify_sha(path, expected):
    actual = hashlib.sha256(path.read_bytes()).hexdigest()
    if actual != expected:
        raise RuntimeError(f"unexpected {path.name} checksum")


def main():
    output = Path("/out")
    output.mkdir()
    original_sha = hashlib.sha256(MODULE_PATH.read_bytes()).hexdigest()
    probe = Path(__file__).with_name("verify_hashlib_fips.py")
    subprocess.run([sys.executable, "-I", "-B", str(probe), "--expect-original"], check=True)

    patch = Path(__file__).with_name("cpython-7d9da920.patch")
    verify_sha(patch, PATCH_SHA256)
    archive = Path("Python-3.14.10.tar.xz")
    with urllib.request.urlopen("https://www.python.org/ftp/python/3.14.10/Python-3.14.10.tar.xz", timeout=30) as response:
        archive.write_bytes(response.read(30 * 1024 * 1024))
    verify_sha(archive, SOURCE_SHA256)
    with tarfile.open(archive, "r:xz") as package:
        package.extractall(filter="data")
    source = Path("Python-3.14.10")
    implementation = source / "Modules/_hashopenssl.c"
    verify_sha(implementation, "4e7f7b86d7fedd8fecca4ea719146ef8ca3d176fce4f0d3f77f65fc04b419a61")
    subprocess.run(["patch", "--batch", "--forward", "--fuzz=0", "-p1", "-i", str(patch)], cwd=source, check=True)
    verify_sha(implementation, PATCHED_SOURCE_SHA256)

    # Reuse the official installed Python headers, generated pyconfig and
    # extension linker settings. The source tree supplies private C headers.
    target = output / EXTENSION
    flags = sysconfig.get_config_vars()
    subprocess.run([
        *shlex.split(flags["LDSHARED"]), *shlex.split(flags["CFLAGS"]), *shlex.split(flags["CCSHARED"]),
        "-I" + sysconfig.get_path("include"), "-I" + str(source / "Include"),
        "-I" + str(source / "Include/internal"), "-DPy_BUILD_CORE_MODULE", f"-ffile-prefix-map={Path.cwd()}=/usr/src",
        "-Wl,--build-id=sha1", str(implementation), "-o", str(target), "-lcrypto",
    ], check=True, env={**os.environ, "SOURCE_DATE_EPOCH": "1793971619"})
    digest = hashlib.sha256(target.read_bytes()).hexdigest()
    if digest == original_sha:
        raise RuntimeError("compiled _hashlib is identical to the vulnerable module")
    verify_sha(target, "3ea80f99a8d7ba7011b20eeef31ecb120c0914c8241dc209c38515e994a01671")
    target.chmod(0o755)
    receipt = output / "cpython-hashlib-build.json"
    receipt.write_text(json.dumps({
        "schema": 1, "cpython": "3.14.10", "openssl": "3.5.9", "source_sha256": SOURCE_SHA256,
        "patch_sha256": PATCH_SHA256, "patched_source_sha256": PATCHED_SOURCE_SHA256,
        "module_path": str(MODULE_PATH), "module_sha256": digest, "unpatched_control": True,
    }, sort_keys=True) + "\n", encoding="utf-8")
    receipt.chmod(0o644)


if __name__ == "__main__":
    main()
