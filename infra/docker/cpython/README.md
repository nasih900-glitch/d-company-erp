# CPython 3.14.10 hashlib security backport

The backend retains the official CPython 3.14.10 Alpine ABI and source release.
The build verifies the published CPython source tarball checksum and applies
the exact upstream 3.14 branch security patch for CVE-2026-119170. It checks the
original and patched C source hashes, compiles `_hashlib` with the installed
ABI and pinned Alpine compiler and OpenSSL headers, and verifies the resulting
module checksum. The discarded stage first reproduces the regression on the
original module. The final image runs the fixed behavior checks as the ERP user.

The scanner independently checks the loaded module and both digest cache
orders, offline, in the same immutable backend image used to create the scanned
archive. If Grype reports the exact remaining version-based CVE match, the tool
retains its original report and generates a single backend-image-bound OpenVEX
`fixed` statement. Both filtered scan results must verify the exact fixed
component and preserve the high-severity threshold.

Use the official patched image when available and remove the backport and
its special scan handling together.

- [Python security advisory](https://mail.python.org/archives/list/security-announce%40python.org/message/PXVTTOBPUMFJSSUXN6XCS7FC5DZOSZXK/)
- [Upstream signed 3.14 branch patch](https://github.com/python/cpython/commit/7d9da9208fc3dc126bebca6844dfe312afc43c34)
- [Python 3.14.10 source release](https://www.python.org/downloads/release/python-31410/)
