#!/bin/sh
# Build the exact zlib 1.3.2 release with the upstream CVE-2026-85091 fix.

set -eu

SOURCE_URL='https://zlib.net/fossils/zlib-1.3.2.tar.gz'
SOURCE_SHA256='bb329a0a2cd0274d05519d61c667c062e06990d72e125ee2dfa8de64f0119d16'
NULL_GUARD_COMMIT='e3dc0a85b7032e98380dec011bc8f2c2ee0d8fca'
NULL_GUARD_SHA256='183bc8b9dd078a41a62de5c2d905d9b0196b45bc46f100d7e4147ec228207c74'
NULL_GUARD_PATCH_FILE='/tmp/dcompany-zlib/e3dc0a85-null-guard.patch'
PRINTF_RETURN_COMMIT='bbc2ccf3d0de267576b524b875c769a724a513b0'
PRINTF_RETURN_SHA256='7d00ee29be5e636d30da2890961e83e35cee0b152333ecd97a46ae2e71eb5d47'
PRINTF_RETURN_PATCH_FILE='/tmp/dcompany-zlib/bbc2ccf3-gzvprintf-return.patch'
PRIMARY_PATCH_COMMIT='df84af25dc1942490e1d1c899a07619152a46148'
PRIMARY_PATCH_SHA256='110ff14375733173d8aa54574473424fbd7dfe4b81f1ca34a759c6fe14b15b14'
PRIMARY_PATCH_FILE='/tmp/dcompany-zlib/cve-2026-85091.patch'
FOLLOWUP_PATCH_COMMIT='7235b0a581227c56a79a43ff828f8ef6794194c8'
FOLLOWUP_PATCH_SHA256='96040ee84d0d187905283912dbd3f7b66ac2033976a2ceefe9b8cca63143d9c2'
FOLLOWUP_PATCH_FILE='/tmp/dcompany-zlib/cve-2026-85091-followup.patch'
BUILD_ROOT='/tmp/dcompany-zlib/source'
OUTPUT_ROOT='/out'
EXPECTED_GZWRITE_SHA256='cc6687863cc2560ca866e9fd480802d0a90fe09b65bea37f1b1a59e5a02f9e2b'

test "$(sha256sum "$NULL_GUARD_PATCH_FILE" | awk '{print $1}')" = "$NULL_GUARD_SHA256"
test "$(sha256sum "$PRINTF_RETURN_PATCH_FILE" | awk '{print $1}')" = "$PRINTF_RETURN_SHA256"
test "$(sha256sum "$PRIMARY_PATCH_FILE" | awk '{print $1}')" = "$PRIMARY_PATCH_SHA256"
test "$(sha256sum "$FOLLOWUP_PATCH_FILE" | awk '{print $1}')" = "$FOLLOWUP_PATCH_SHA256"
wget -q -O /tmp/zlib-1.3.2.tar.gz "$SOURCE_URL"
test "$(sha256sum /tmp/zlib-1.3.2.tar.gz | awk '{print $1}')" = "$SOURCE_SHA256"
mkdir -p "$BUILD_ROOT" "$OUTPUT_ROOT/usr/lib" "$OUTPUT_ROOT/etc/dcompany"
tar -xzf /tmp/zlib-1.3.2.tar.gz -C "$BUILD_ROOT" --strip-components=1
cd "$BUILD_ROOT"
patch -p1 --forward --batch < "$NULL_GUARD_PATCH_FILE"
patch -p1 --forward --batch < "$PRINTF_RETURN_PATCH_FILE"
patch -p1 --forward --batch < "$PRIMARY_PATCH_FILE"
patch -p1 --forward --batch < "$FOLLOWUP_PATCH_FILE"

# Fail closed if the exact upstream correction did not land in the source used
# by the compiler. The vendored patch is independently hash-bound above.
grep -F 'state->strm.avail_in = 0;' gzwrite.c >/dev/null
grep -F 'state->strm.next_in = state->in;' gzwrite.c >/dev/null
grep -F 'if (strm->next_in == NULL ||' gzwrite.c >/dev/null
test "$(grep -Fc \
  'gz_error(state, Z_BUF_ERROR, "stalled write on gzprintf");' gzwrite.c)" -eq 2
test "$(awk \
  '/gz_error\(state, Z_BUF_ERROR, "stalled write on gzprintf"\);/ { getline; if ($0 ~ /^[[:space:]]*return state->err;[[:space:]]*$/) adjacent++ } END { print adjacent + 0 }' \
  gzwrite.c)" -eq 2
test "$(sha256sum gzwrite.c | awk '{print $1}')" = "$EXPECTED_GZWRITE_SHA256"

./configure --prefix=/usr
make -j1
make test
make DESTDIR="$OUTPUT_ROOT" install
test -f "$OUTPUT_ROOT/usr/lib/libz.so.1.3.2"

cat > /tmp/zlib-runtime-probe.c <<'EOF'
#include <stdio.h>
#include <string.h>
#include <zlib.h>

int main(void) {
    char line[1024];
    int mapped = 0;
    const char *runtime_version = zlibVersion();

    if (strcmp(runtime_version, "1.3.2") != 0) {
        return 10;
    }
    FILE *maps = fopen("/proc/self/maps", "r");
    if (maps == NULL) {
        return 11;
    }
    while (fgets(line, sizeof(line), maps) != NULL) {
        if (strstr(line, "/usr/lib/libz.so.1.3.2") != NULL) {
            mapped = 1;
            break;
        }
    }
    fclose(maps);
    if (!mapped) {
        return 12;
    }
    printf("zlib_runtime_version=%s\n", runtime_version);
    printf("zlib_runtime_path=/usr/lib/libz.so.1.3.2\n");
    return 0;
}
EOF

cc -O2 -Wl,-rpath,/usr/lib -I"$BUILD_ROOT" \
  /tmp/zlib-runtime-probe.c -L"$OUTPUT_ROOT/usr/lib" -lz \
  -o "$OUTPUT_ROOT/zlib-runtime-probe"

RUNTIME_SHA256=$(sha256sum "$OUTPUT_ROOT/usr/lib/libz.so.1.3.2" | awk '{print $1}')
cat > "$OUTPUT_ROOT/etc/dcompany/zlib-patch-evidence.env" <<EOF
zlib_source_url=$SOURCE_URL
zlib_source_sha256=$SOURCE_SHA256
zlib_null_guard_commit=$NULL_GUARD_COMMIT
zlib_null_guard_patch_sha256=$NULL_GUARD_SHA256
zlib_printf_return_commit=$PRINTF_RETURN_COMMIT
zlib_printf_return_patch_sha256=$PRINTF_RETURN_SHA256
zlib_primary_patch_commit=$PRIMARY_PATCH_COMMIT
zlib_primary_patch_sha256=$PRIMARY_PATCH_SHA256
zlib_followup_patch_commit=$FOLLOWUP_PATCH_COMMIT
zlib_followup_patch_sha256=$FOLLOWUP_PATCH_SHA256
zlib_runtime_path=/usr/lib/libz.so.1.3.2
zlib_runtime_sha256=$RUNTIME_SHA256
EOF
