#!/bin/sh
# Prove the final image runs the exact zlib bytes built from the patched source.

set -eu

EVIDENCE='/etc/dcompany/zlib-patch-evidence.env'
EXPECTED_SOURCE_SHA256='bb329a0a2cd0274d05519d61c667c062e06990d72e125ee2dfa8de64f0119d16'
EXPECTED_NULL_GUARD_COMMIT='e3dc0a85b7032e98380dec011bc8f2c2ee0d8fca'
EXPECTED_NULL_GUARD_SHA256='183bc8b9dd078a41a62de5c2d905d9b0196b45bc46f100d7e4147ec228207c74'
EXPECTED_PRINTF_RETURN_COMMIT='bbc2ccf3d0de267576b524b875c769a724a513b0'
EXPECTED_PRINTF_RETURN_SHA256='7d00ee29be5e636d30da2890961e83e35cee0b152333ecd97a46ae2e71eb5d47'
EXPECTED_PRIMARY_PATCH_COMMIT='df84af25dc1942490e1d1c899a07619152a46148'
EXPECTED_PRIMARY_PATCH_SHA256='110ff14375733173d8aa54574473424fbd7dfe4b81f1ca34a759c6fe14b15b14'
EXPECTED_FOLLOWUP_PATCH_COMMIT='7235b0a581227c56a79a43ff828f8ef6794194c8'
EXPECTED_FOLLOWUP_PATCH_SHA256='96040ee84d0d187905283912dbd3f7b66ac2033976a2ceefe9b8cca63143d9c2'

test -f "$EVIDENCE"
test "$(grep -c '^zlib_' "$EVIDENCE")" -eq 12
test "$(sed -n 's/^zlib_source_sha256=//p' "$EVIDENCE")" = "$EXPECTED_SOURCE_SHA256"
test "$(sed -n 's/^zlib_null_guard_commit=//p' "$EVIDENCE")" = "$EXPECTED_NULL_GUARD_COMMIT"
test "$(sed -n 's/^zlib_null_guard_patch_sha256=//p' "$EVIDENCE")" = "$EXPECTED_NULL_GUARD_SHA256"
test "$(sed -n 's/^zlib_printf_return_commit=//p' "$EVIDENCE")" = "$EXPECTED_PRINTF_RETURN_COMMIT"
test "$(sed -n 's/^zlib_printf_return_patch_sha256=//p' "$EVIDENCE")" = "$EXPECTED_PRINTF_RETURN_SHA256"
test "$(sed -n 's/^zlib_primary_patch_commit=//p' "$EVIDENCE")" = "$EXPECTED_PRIMARY_PATCH_COMMIT"
test "$(sed -n 's/^zlib_primary_patch_sha256=//p' "$EVIDENCE")" = "$EXPECTED_PRIMARY_PATCH_SHA256"
test "$(sed -n 's/^zlib_followup_patch_commit=//p' "$EVIDENCE")" = "$EXPECTED_FOLLOWUP_PATCH_COMMIT"
test "$(sed -n 's/^zlib_followup_patch_sha256=//p' "$EVIDENCE")" = "$EXPECTED_FOLLOWUP_PATCH_SHA256"
test "$(sed -n 's/^zlib_runtime_path=//p' "$EVIDENCE")" = '/usr/lib/libz.so.1.3.2'
EXPECTED_RUNTIME_SHA256=$(sed -n 's/^zlib_runtime_sha256=//p' "$EVIDENCE")
test "${#EXPECTED_RUNTIME_SHA256}" -eq 64
test "$(sha256sum /usr/lib/libz.so.1.3.2 | awk '{print $1}')" = "$EXPECTED_RUNTIME_SHA256"
test "$(readlink /usr/lib/libz.so.1)" = 'libz.so.1.3.2'
test "$(realpath /usr/lib/libz.so.1)" = '/usr/lib/libz.so.1.3.2'
/tmp/zlib-runtime-probe | grep -Fx 'zlib_runtime_version=1.3.2'
/tmp/zlib-runtime-probe | grep -Fx 'zlib_runtime_path=/usr/lib/libz.so.1.3.2'
