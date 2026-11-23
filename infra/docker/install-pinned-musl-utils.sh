#!/bin/sh
set -eu

test "$#" -eq 1
branch=$1
case "$branch" in
  3.24)
    version=1.2.6-r3
    package_sha256=47263d05842c5e12d01e0369e5b5bea51733323501c00ca21968a5102de137ca
    ;;
  3.23)
    version=1.2.5-r24
    package_sha256=ee9a8106067ead36b8930f5ccdb3e515574871ac8e07f88cc7bab7049effc945
    ;;
  3.21)
    version=1.2.5-r12
    package_sha256=42bb107b4f139a289b146ac0404e222c0ae74ad759add38ac69b0063d2a5164c
    ;;
  *) echo "Unsupported Alpine branch for musl-utils." >&2; exit 1 ;;
esac

test "$(cut -d. -f1,2 /etc/alpine-release)" = "$branch"
test "$(apk --print-arch)" = x86_64
installed_packages=$(apk info -v)
printf '%s\n' "$installed_packages" | grep -Fx "musl-$version"
apk info -e musl-utils

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
mkdir "$work/keys"
key=alpine-devel@lists.alpinelinux.org-6165ee59.rsa.pub
cp "/etc/apk/keys/$key" "$work/keys/$key"
printf '%s  %s\n' '207e4696d3c05f7cb05966aee557307151f1f00217af4143c1bcaf33b8df733f' \
  "$work/keys/$key" | sha256sum -c -

package="$work/musl-utils-$version.apk"
wget -T 30 -qO "$package" "https://dl-cdn.alpinelinux.org/alpine/v$branch/main/x86_64/musl-utils-$version.apk"
printf '%s  %s\n' "$package_sha256" "$package" | sha256sum -c -
apk add --keys-dir "$work/keys" --no-cache --no-network --upgrade --repositories-file /dev/null "$package"

installed_packages=$(apk info -v)
printf '%s\n' "$installed_packages" | grep -Fx "musl-$version"
printf '%s\n' "$installed_packages" | grep -Fx "musl-utils-$version"
