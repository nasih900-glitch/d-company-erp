#!/usr/bin/env python3
"""Check OpenSSL property enforcement by the exact CPython _hashlib extension."""

import ctypes
import json
from pathlib import Path
import ssl
import subprocess
import sys
import sysconfig

import _hashlib


SOURCE_SHA256 = "de414a57119abef52784d8993e341d53aed635fc2b05c2607d9f98a2c5b72032"
PATCH_SHA256 = "346c4b31e1cdd87d08cd2e79dbbcc00e6e8c6d47bdb045346be7bbf40c283fed"
PATCHED_SOURCE_SHA256 = "d907bf6b22ddd8a55e271d19f8e1e5eda07f931d3e5143698171904f0970e549"
EXTENSION = "_hashlib" + sysconfig.get_config_var("EXT_SUFFIX")
MODULE_PATH = Path(sysconfig.get_config_var("DESTSHARED")) / EXTENSION
RECEIPT = Path("/etc/dcompany/cpython-hashlib-build.json")
MD5 = "900150983cd24fb0d6963f7d28e17f72"


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def check_runtime():
    require(sys.version_info[:3] == (3, 14, 10) and ssl.OPENSSL_VERSION_INFO == (3, 5, 0, 9, 0),
            "unexpected CPython/OpenSSL runtime")
    require(sys.executable == "/usr/local/bin/python" and Path(_hashlib.__file__) == MODULE_PATH
            and MODULE_PATH.is_file() and not MODULE_PATH.is_symlink(), "unexpected _hashlib module path")


def probe(name, order, expected):
    check_runtime()
    openssl = ctypes.CDLL("libcrypto.so.3")
    enable = openssl.EVP_default_properties_enable_fips
    enable.argtypes = (ctypes.c_void_p, ctypes.c_int)
    enable.restype = ctypes.c_int
    # Apply the property with only the default provider. This checks policy
    # enforcement without requiring a validated FIPS provider or claiming one.
    require(enable(None, 1) == 1 and _hashlib.get_fips_mode() == 1, "OpenSSL FIPS property did not take effect")
    constructor = _hashlib.openssl_md5 if name == "named" else lambda data, **kw: _hashlib.new("md5", data, **kw)
    if expected == "original":
        require(constructor(b"abc", usedforsecurity=True).hexdigest() == MD5, "unpatched control did not reproduce the bug")
        return
    for secure in ((True, False, True) if order == "security-first" else (False, True, False)):
        try:
            digest = constructor(b"abc", usedforsecurity=secure).hexdigest()
        except ValueError:
            require(secure, "unexpected hash failure")
        else:
            require(digest == MD5 and not secure, "secure hash bypassed the FIPS property")
    require(enable(None, 0) == 1 and _hashlib.get_fips_mode() == 0, "could not restore OpenSSL default properties")


def main():
    if len(sys.argv) == 5 and sys.argv[1] == "--probe":
        probe(*sys.argv[2:])
        return
    check_runtime()
    build_control = sys.argv[1:] == ["--expect-original"]
    require(build_control or len(sys.argv) == 1, "unknown probe argument")
    expected = "original" if build_control else "fixed"
    for name in ("named", "generic"):
        orders = ("security-first",) if build_control else ("security-first", "nonsecurity-first")
        for order in orders:
            subprocess.run([sys.executable, "-I", "-B", __file__, "--probe", name, order, expected],
                           check=True, timeout=5)
    if build_control:
        return
    import hashlib

    expected_receipt = {
        "schema": 1, "cpython": "3.14.10", "openssl": "3.5.9", "source_sha256": SOURCE_SHA256,
        "patch_sha256": PATCH_SHA256, "patched_source_sha256": PATCHED_SOURCE_SHA256,
        "module_path": str(MODULE_PATH), "module_sha256": hashlib.sha256(MODULE_PATH.read_bytes()).hexdigest(),
        "unpatched_control": True,
    }
    require(json.loads(RECEIPT.read_bytes()) == expected_receipt, "_hashlib bytes or upstream build evidence changed")
    print(json.dumps({**expected_receipt, "named_both_cache_orders": True, "generic_both_cache_orders": True}, sort_keys=True))


if __name__ == "__main__":
    main()
