#!/bin/sh
# Authenticate the exact vendor backports and compare signed payload to runtime.
set -eu
PATH=/usr/sbin:/usr/bin:/sbin:/bin:/usr/local/bin
export PATH
test "$#" -eq 2
service=$1
archives=$2
case "$service" in
    backend) distro=3.24.3 ;;
    frontend) distro=3.24.1 ;;
    postgres) distro=3.24.4 ;;
    *) echo 'No fixed OpenSSL vendor profile for this service.' >&2; exit 1 ;;
esac
port=0f4c7e8d0f5f7cebbdc2156eca4391080b732bf2
test "$(cat /etc/alpine-release)" = "$distro"
test "$(apk --print-arch)" = x86_64
test -d "$archives" && test ! -L "$archives"
test "$(realpath "$archives")" = "$archives"
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT HUP INT TERM
# Refuse loader/configuration overrides; do not hide an env/grep pipeline error.
env > "$work/environment"
sed -n '/^OPENSSL_/p;/^LD_/p;/^CRYPTOGRAPHY_/p' "$work/environment" > "$work/overrides"
test ! -s "$work/overrides"
mkdir "$work/keys"
key=/etc/apk/keys/alpine-devel@lists.alpinelinux.org-6165ee59.rsa.pub
test -f "$key" && test ! -L "$key"
printf '%s  %s\n' '207e4696d3c05f7cb05966aee557307151f1f00217af4143c1bcaf33b8df733f' "$key" | sha256sum -c - >&2
cp "$key" "$work/keys/"
for package in libcrypto3 libssl3; do
    case "$package" in
        libcrypto3) archive_sha=042026082cc9bc7872d1baa81755c50efa70b05f03b5369fdc5f0a1303009720
            library=libcrypto.so.3; runtime_sha=d3170451aed163f70460726a08bfadd8c899d0ca43ea9b6105eaa45d3d1aa5c0 ;;
        libssl3) archive_sha=18c5778cbe52e1991303f8531970b05d4e6e036ea08349322f7422f42a689dc7
            library=libssl.so.3; runtime_sha=6f35d9d1edb0f75228af331a88d740a891753a4fed32a1fccdbf8bf9183cd3cd ;;
    esac
    archive="$archives/$package-3.5.9-r1.apk"
    runtime="/usr/lib/$library"
    test -f "$archive" && test ! -L "$archive"
    test -f "$runtime" && test ! -L "$runtime"
    test "$(realpath "$runtime")" = "$runtime"
    printf '%s  %s\n' "$archive_sha" "$archive" | sha256sum -c - >&2
    printf '%s  %s\n' "$runtime_sha" "$runtime" | sha256sum -c - >&2
    apk verify --no-network --repositories-file /dev/null --keys-dir "$work/keys" "$archive" >&2
    apk index --no-network --repositories-file /dev/null --output "$work/index.tar.gz" "$archive" >&2
    tar -xzOf "$work/index.tar.gz" APKINDEX > "$work/control"
    field() {
        test "$(grep -c "^$1:" "$work/control")" -eq 1
        sed -n "s/^$1://p" "$work/control"
    }
    test "$(field P)" = "$package"
    test "$(field V)" = 3.5.9-r1
    test "$(field A)" = x86_64
    test "$(field o)" = openssl
    test "$(field c)" = "$port"
    apk info -e "$package=3.5.9-r1" >&2
    apk manifest --no-network --repositories-file /dev/null "$archive" > "$work/manifest"
    manifest_digest=$(awk -v name="usr/lib/$library" '$2 == name { n++; value=$1 } END { if (n != 1) exit 1; print value }' "$work/manifest")
    case "$manifest_digest" in
        sha1:*) algorithm=sha1; digest=${manifest_digest#sha1:}; length=40 ;;
        sha256:*) algorithm=sha256; digest=${manifest_digest#sha256:}; length=64 ;;
        *) echo 'Unsupported signed OpenSSL file checksum.' >&2; exit 1 ;;
    esac
    test "${#digest}" -eq "$length"
    printf '%s\n' "$digest" | grep -Eq '^[0-9a-f]+$'
    test "$("${algorithm}sum" "$runtime" | awk '{print $1}')" = "$digest"
    mkdir "$work/$package"
    apk extract --no-network --repositories-file /dev/null --no-chown \
        --destination "$work/$package" "$archive" >&2
    payload="$work/$package/usr/lib/$library"
    test -f "$payload" && test ! -L "$payload"
    test "$(realpath "$payload")" = "$payload"
    test "$(stat -c '%h' "$payload")" -eq 1
    printf '%s  %s\n' "$runtime_sha" "$payload" | sha256sum -c - >&2
    test "$("${algorithm}sum" "$payload" | awk '{print $1}')" = "$digest"
done
printf '{"schema":1,"service":"%s","distro_version":"%s","architecture":"x86_64","port_commit":"%s","signature_key":"alpine-devel@lists.alpinelinux.org-6165ee59","signature_verified":true,"signature_key_sha256":"207e4696d3c05f7cb05966aee557307151f1f00217af4143c1bcaf33b8df733f","package_version":"3.5.9-r1","archives_sha256":{"libcrypto3":"042026082cc9bc7872d1baa81755c50efa70b05f03b5369fdc5f0a1303009720","libssl3":"18c5778cbe52e1991303f8531970b05d4e6e036ea08349322f7422f42a689dc7"},"runtime_sha256":{"/usr/lib/libcrypto.so.3":"d3170451aed163f70460726a08bfadd8c899d0ca43ea9b6105eaa45d3d1aa5c0","/usr/lib/libssl.so.3":"6f35d9d1edb0f75228af331a88d740a891753a4fed32a1fccdbf8bf9183cd3cd"}}\n' "$service" "$distro" "$port"
