#!/bin/sh
# Install the exact signed backport pair from a read-only BuildKit context.
set -eu
# This helper is Alpine-only; BusyBox ash supplies pipefail.
# shellcheck disable=SC3040
set -o pipefail
PATH=/usr/sbin:/usr/bin:/sbin:/bin
export PATH

test "$#" -eq 2
service=$1
artifacts=$2
case "$service" in
    caddy) distro=3.23.5; vendor_version=3.5.9-r0 ;;
    backend) distro=3.24.3; vendor_version=3.5.9-r1 ;;
    frontend) distro=3.24.1; vendor_version=3.5.9-r1 ;;
    postgres) distro=3.24.4; vendor_version=3.5.9-r1 ;;
    *) echo 'Unsupported OpenSSL backport service profile.' >&2; exit 1 ;;
esac

version=3.5.9_p20261123-r0
source_sha256=603f5602e2eef00d77fbd429d34dcd5822bb301757a1bc9cdb24c670f1eb859a
signing_fingerprint=B146647E45A7B33947AB226B2A2C87D161692D40

test "$(cat /etc/alpine-release)" = "$distro"
test "$(apk --print-arch)" = x86_64
test -d "$artifacts" && test ! -L "$artifacts"
test "$(realpath "$artifacts")" = "$artifacts"
test "$(apk version --test "$version" "$vendor_version")" = '>'
apk info -e "libcrypto3=$vendor_version" >&2
apk info -e "libssl3=$vendor_version" >&2

provenance="$artifacts/provenance.json"
patch_inventory="$artifacts/patches.sha256"
archives="$artifacts/repository/x86_64"
key_source="$artifacts/keys"
test -f "$provenance" && test ! -L "$provenance"
test "$(realpath "$provenance")" = "$provenance"
test -f "$patch_inventory" && test ! -L "$patch_inventory"
test "$(realpath "$patch_inventory")" = "$patch_inventory"
test -d "$archives" && test ! -L "$archives"
test "$(realpath "$archives")" = "$archives"
test -d "$key_source" && test ! -L "$key_source"
test "$(realpath "$key_source")" = "$key_source"
grep -F '"status":"isolated-proof-only"' "$provenance" >/dev/null
grep -F '"version":"3.5.9"' "$provenance" >/dev/null
grep -F "\"archive_sha256\":\"$source_sha256\"" "$provenance" >/dev/null
grep -F "\"signing_fingerprint\":\"$signing_fingerprint\"" "$provenance" >/dev/null
grep -F "\"package_version\":\"$version\"" "$provenance" >/dev/null

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT HUP INT TERM
mkdir "$work/keys"
set -- "$key_source"/*.rsa.pub
test "$#" -eq 1
key=$1
test -f "$key" && test ! -L "$key"
test "$(find "$key_source" -mindepth 1 -maxdepth 1 -type f | wc -l)" -eq 1
test -z "$(find "$key_source" -mindepth 1 -maxdepth 1 ! -type f -print -quit)"
cp "$key" "$work/keys/"
test "$(find "$work/keys" -mindepth 1 -maxdepth 1 -type f | wc -l)" -eq 1
key_sha256=$(sha256sum "$key" | awk '{print $1}')
test -n "$key_sha256"

test "$(find "$archives" -mindepth 1 -maxdepth 1 -type f | wc -l)" -eq 3
test -z "$(find "$archives" -mindepth 1 -maxdepth 1 ! -type f -print -quit)"
for package in libcrypto3 libssl3; do
    archive="$archives/$package-$version.apk"
    test -f "$archive" && test ! -L "$archive"
    test "$(realpath "$archive")" = "$archive"
    declared_sha=$(sed -n "s/.*\"$package\":\"\\([0-9a-f][0-9a-f]*\\)\".*/\\1/p" "$provenance")
    test "${#declared_sha}" -eq 64
    printf '%s\n' "$declared_sha" | grep -Eq '^[0-9a-f]+$'
    printf '%s  %s\n' "$declared_sha" "$archive" | sha256sum -c - >&2
    apk verify --no-network --repositories-file /dev/null \
        --keys-dir "$work/keys" "$archive" >&2
    apk index --no-network --repositories-file /dev/null \
        --keys-dir "$work/keys" \
        --output "$work/$package.index.tar.gz" "$archive" >&2
    tar -xzOf "$work/$package.index.tar.gz" APKINDEX > "$work/$package.control"
    field() {
        test "$(grep -c "^$1:" "$work/$package.control")" -eq 1
        sed -n "s/^$1://p" "$work/$package.control"
    }
    test "$(field P)" = "$package"
    test "$(field V)" = "$version"
    test "$(field A)" = x86_64
    test "$(field o)" = openssl
    apk manifest --no-network --repositories-file /dev/null \
        --keys-dir "$work/keys" "$archive" \
        > "$work/$package.manifest"
    test -s "$work/$package.manifest"
    if grep -Eq '(^|[ /])(fips\.so|fipsmodule\.cnf|[^/]+\.a|lib(crypto|ssl)\.so)$| usr/include/' \
        "$work/$package.manifest"; then
        echo "Unexpected development, static, or FIPS payload in $package." >&2
        exit 1
    fi
    mkdir "$work/$package"
    apk extract --no-network --repositories-file /dev/null \
        --keys-dir "$work/keys" --no-chown \
        --destination "$work/$package" "$archive" >&2
done
grep -F "libcrypto3=$version" "$work/libssl3.control" >/dev/null

crypto_payload="$work/libcrypto3/usr/lib/libcrypto.so.3"
ssl_payload="$work/libssl3/usr/lib/libssl.so.3"
for payload in "$crypto_payload" "$ssl_payload"; do
    test -f "$payload" && test ! -L "$payload"
    test "$(realpath "$payload")" = "$payload"
    test "$(stat -c '%h' "$payload")" -eq 1
done
crypto_sha256=$(sha256sum "$crypto_payload" | awk '{print $1}')
ssl_sha256=$(sha256sum "$ssl_payload" | awk '{print $1}')
test -n "$crypto_sha256" && test -n "$ssl_sha256"

# One offline transaction replaces both vendor libraries atomically from the
# already authenticated local pair. No repository or network fallback exists.
apk add --no-cache --no-network --upgrade --repositories-file /dev/null \
    --keys-dir "$work/keys" \
    "$archives/libcrypto3-$version.apk" \
    "$archives/libssl3-$version.apk"

apk info -e "libcrypto3=$version" >&2
apk info -e "libssl3=$version" >&2
runtime_crypto=/usr/lib/libcrypto.so.3
runtime_ssl=/usr/lib/libssl.so.3
test -f "$runtime_crypto" && test ! -L "$runtime_crypto"
test -f "$runtime_ssl" && test ! -L "$runtime_ssl"
test "$(realpath "$runtime_crypto")" = "$runtime_crypto"
test "$(realpath "$runtime_ssl")" = "$runtime_ssl"
test "$(sha256sum "$runtime_crypto" | awk '{print $1}')" = "$crypto_sha256"
test "$(sha256sum "$runtime_ssl" | awk '{print $1}')" = "$ssl_sha256"
test ! -e /usr/lib/libcrypto.so
test ! -e /usr/lib/libssl.so
test ! -e /usr/lib/libcrypto.a
test ! -e /usr/lib/libssl.a
test ! -e /usr/lib/ossl-modules/fips.so
test ! -e /etc/ssl/fipsmodule.cnf
ldd "$runtime_crypto" > "$work/libcrypto.ldd"
ldd "$runtime_ssl" > "$work/libssl.ldd"
test -s "$work/libcrypto.ldd" && test -s "$work/libssl.ldd"
grep -F 'libcrypto.so.3' "$work/libssl.ldd" >/dev/null

record_dir=/etc/dcompany/openssl-backport
test ! -e "$record_dir"
install -d -m 0755 "$record_dir"
crypto_archive_sha256=$(sha256sum "$archives/libcrypto3-$version.apk" | awk '{print $1}')
ssl_archive_sha256=$(sha256sum "$archives/libssl3-$version.apk" | awk '{print $1}')
producer_provenance_sha256=$(sha256sum "$provenance" | awk '{print $1}')
patch_inventory_sha256=$(sha256sum "$patch_inventory" | awk '{print $1}')
cat > "$record_dir/provenance.json" <<EOF
{"schema":1,"service":"$service","distro":"alpine","distro_version":"$distro","architecture":"x86_64","source_version":"3.5.9","source_sha256":"$source_sha256","source_signing_fingerprint":"$signing_fingerprint","package_version":"$version","producer_provenance_sha256":"$producer_provenance_sha256","patch_inventory_sha256":"$patch_inventory_sha256","apk_signature_verified":true,"signing_key_sha256":"$key_sha256","archives_sha256":{"libcrypto3":"$crypto_archive_sha256","libssl3":"$ssl_archive_sha256"},"runtime_sha256":{"/usr/lib/libcrypto.so.3":"$crypto_sha256","/usr/lib/libssl.so.3":"$ssl_sha256"}}
EOF
chmod 0444 "$record_dir/provenance.json"
