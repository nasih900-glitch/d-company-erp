#!/bin/sh
set -eu
# This collector is Alpine-only; BusyBox ash supplies pipefail.
# shellcheck disable=SC3040
set -o pipefail
PATH=/usr/sbin:/usr/bin:/sbin:/bin
export PATH

test "$#" -eq 2
service=$1
artifacts=$2
case "$service" in backend|frontend|caddy|postgres) ;; *) exit 1 ;; esac
proof=/etc/dcompany/openssl-backport/provenance.json
test -f "$proof" && test ! -L "$proof"
test "$(realpath "$proof")" = "$proof"
test -d "$artifacts" && test ! -L "$artifacts"
test "$(realpath "$artifacts")" = "$artifacts"

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT HUP INT TERM
mkdir "$work/keys"
set -- "$artifacts"/keys/*.rsa.pub
test "$#" -eq 1
cp "$1" "$work/keys/"
test "$(find "$artifacts/keys" -mindepth 1 -maxdepth 1 -type f | wc -l)" -eq 1
test -z "$(find "$artifacts/keys" -mindepth 1 -maxdepth 1 ! -type f -print -quit)"

version=3.5.9_p20261123-r0
for package in libcrypto3 libssl3; do
  archive="$artifacts/repository/x86_64/$package-$version.apk"
  test -f "$archive" && test ! -L "$archive"
  apk verify --no-network --repositories-file /dev/null --keys-dir "$work/keys" "$archive" >&2
  declared=$(sed -n "s/.*\"$package\":\"\([0-9a-f]\{64\}\)\".*/\1/p" "$proof")
  test "$(sha256sum "$archive" | awk '{print $1}')" = "$declared"
  apk info -e "$package=$version" >&2
done
test "$(sha256sum /usr/lib/libcrypto.so.3 | awk '{print $1}')" = \
  "$(sed -n 's/.*\"\/usr\/lib\/libcrypto.so.3\":\"\([0-9a-f]\{64\}\)\".*/\1/p' "$proof")"
test "$(sha256sum /usr/lib/libssl.so.3 | awk '{print $1}')" = \
  "$(sed -n 's/.*\"\/usr\/lib\/libssl.so.3\":\"\([0-9a-f]\{64\}\)\".*/\1/p' "$proof")"
cat "$proof"
