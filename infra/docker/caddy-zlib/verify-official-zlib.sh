#!/bin/sh
# Authenticate the retained official APK, then verify the final mapped runtime.
set -eu
PATH=/usr/sbin:/usr/bin:/sbin:/bin:/usr/local/bin
export PATH
test "$#" -le 1
service=${1:-caddy}
case "$service" in
    caddy) distro=3.23.5; PORT=0436c6d655a04cbfd7987202a970f5aa7870a901; directory=caddy-zlib ;;
    backend) distro=3.24.3; PORT=bdf501c870a92a05c097857401f69c48c4810384; directory=zlib ;;
    frontend) distro=3.24.1; PORT=bdf501c870a92a05c097857401f69c48c4810384; directory=zlib ;;
    postgres) distro=3.24.4; PORT=bdf501c870a92a05c097857401f69c48c4810384; directory=zlib ;;
    redis) distro=3.21.8; PORT=d3332621d44c2c4cf52e52217b16674db190212c; directory=zlib ;;
    *) echo 'Unknown official zlib service profile.' >&2; exit 1 ;;
esac
APK=/etc/dcompany/$directory/zlib-1.4-r0.apk
RUNTIME=/usr/lib/libz.so.1.4
test "$(cat /etc/alpine-release)" = "$distro"
test "$(apk --print-arch)" = x86_64
test -f "$APK" && test ! -L "$APK"
apk verify --no-network --repositories-file /dev/null "$APK" >&2
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
# The generated index describes this authenticated archive, not installed DB text.
apk index --no-network --repositories-file /dev/null --output "$work/index.tar.gz" "$APK" >&2
tar -xzOf "$work/index.tar.gz" APKINDEX > "$work/control"
field() {
    test "$(grep -c "^$1:" "$work/control")" -eq 1
    sed -n "s/^$1://p" "$work/control"
}
test "$(field P)" = zlib
test "$(field V)" = 1.4-r0
test "$(field A)" = x86_64
test "$(field o)" = zlib
test "$(field c)" = "$PORT"
apk info -e zlib=1.4-r0 >&2
apk manifest --no-network --repositories-file /dev/null "$APK" > "$work/manifest"
manifest_digest=$(awk '$2 == "usr/lib/libz.so.1.4" { count++; value=$1 } END { if (count != 1) exit 1; print value }' "$work/manifest")
case "$manifest_digest" in
    sha1:*) algorithm=sha1; digest=${manifest_digest#sha1:}; length=40 ;;
    sha256:*) algorithm=sha256; digest=${manifest_digest#sha256:}; length=64 ;;
    *) echo 'Unsupported authenticated APK file checksum.' >&2; exit 1 ;;
esac
test "${#digest}" -eq "$length"
printf '%s\n' "$digest" | grep -Eq '^[0-9a-f]+$'
test -f "$RUNTIME" && test ! -L "$RUNTIME"
test "$(readlink /usr/lib/libz.so.1)" = libz.so.1.4
test "$(realpath /usr/lib/libz.so.1)" = "$RUNTIME"
test "$("${algorithm}sum" "$RUNTIME" | awk '{print $1}')" = "$digest"
test "$(/usr/local/bin/dcompany-caddy-zlib-probe)" = "$(printf 'runtime_version=1.4\nruntime_path=%s\nsoname=libz.so.1\nproc_map=%s' "$RUNTIME" "$RUNTIME")"
# Preserve the format-aware apk3 extractor for the four closed non-Redis profiles.
# Redis's apk2 CLI lacks extract; its strict APK2 member path has no fallback.
if [ "$service" = redis ]; then
# Refuse duplicate, missing, directory, symlink or hardlink payload members.
tar -tzf "$APK" > "$work/members"
test "$(awk '$0 == "usr/lib/libz.so.1.4" { n++ } END { print n+0 }' "$work/members")" -eq 1
tar -tvzf "$APK" usr/lib/libz.so.1.4 > "$work/member-details"
test "$(wc -l < "$work/member-details")" -eq 1
grep -Eq '^-.* usr/lib/libz\.so\.1\.4$' "$work/member-details"
if grep -Fq ' -> ' "$work/member-details"; then exit 1; fi
if grep -Fq ' link to ' "$work/member-details"; then exit 1; fi
# Redis uses apk2: read only its authenticated exact regular member to stdout.
tar -xzOf "$APK" usr/lib/libz.so.1.4 > "$work/runtime"
payload="$work/runtime"
else
    mkdir "$work/extracted"
    apk extract --no-network --repositories-file /dev/null --no-chown \
        --destination "$work/extracted" "$APK" >&2
    payload="$work/extracted/usr/lib/libz.so.1.4"
fi
# Do not follow payload/parent symlinks or accept another hard link to its bytes.
test -f "$payload" && test ! -L "$payload"
test "$(realpath "$payload")" = "$payload"
test "$(stat -c '%h' "$payload")" -eq 1
test "$("${algorithm}sum" "$payload" | awk '{print $1}')" = "$digest"
apk_runtime_sha256=$(sha256sum "$payload" | awk '{print $1}')
apk_sha256=$(sha256sum "$APK" | awk '{print $1}')
runtime_sha256=$(sha256sum "$RUNTIME" | awk '{print $1}')
test "$apk_runtime_sha256" = "$runtime_sha256"
printf '{"service":"%s","package":"zlib","package_version":"1.4-r0","port_commit":"%s","distro":"alpine","distro_version":"%s","architecture":"x86_64","apk_signature_verified":true,"apk_sha256":"%s","manifest_algorithm":"%s","manifest_digest":"%s","runtime_path":"%s","runtime_sha256":"%s","apk_runtime_sha256":"%s","runtime_version":"1.4","soname":"libz.so.1","proc_map":"%s"}\n' "$service" "$PORT" "$distro" "$apk_sha256" "$algorithm" "$digest" "$RUNTIME" "$runtime_sha256" "$apk_runtime_sha256" "$RUNTIME"
