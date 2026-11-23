# syntax=docker/dockerfile:1.7.1@sha256:a57df69d0ea827fb7266491f2813635de6f17269be881f696fbfdf2d83dda33e

# Isolated proof producer only. Nothing from this image is installed into a
# D Company runtime unless the separate release process explicitly adopts it.
FROM --platform=linux/amd64 alpine:3.24.4@sha256:f59abe656b692d901e184be3445e084786602108599d2a51d229c0f5a48c71bf AS builder

ARG TARGETARCH
ENV SOURCE_DATE_EPOCH=1795370212 \
    OPENSSL_VERSION=3.5.9 \
    OPENSSL_SOURCE_SHA256=603f5602e2eef00d77fbd429d34dcd5822bb301757a1bc9cdb24c670f1eb859a \
    OPENSSL_SIGNATURE_SHA256=bdccf2057a7c4ae377b40e832cb8c632ae8b532d4fb64cc7b843066eee748f89 \
    OPENSSL_KEYS_SHA256=56e106cd1c44bdb117aec24795f6dfbecaa9bfa0a2901b85331f0059aad16d53 \
    OPENSSL_SIGNING_FINGERPRINT=B146647E45A7B33947AB226B2A2C87D161692D40 \
    ALPINE_APORTS_COMMIT=0f4c7e8d0f5f7cebbdc2156eca4391080b732bf2 \
    DANE_PATCH_1=da8187ff0a61e878fb412817a9bf9fe90a2e2d5b \
    DANE_PATCH_1_SHA256=d69aa6d67e48df86b7ec853812edc145dece65582f6c75ab928e95c9c86357a7 \
    DANE_PATCH_2=ea3a0647446e34d142e844e3026cdf53391a63f8 \
    DANE_PATCH_2_SHA256=0267bf8ee53a8bb0b5f3c88af286307748921f36c74740acb69d4429ba1d2d8a \
    DANE_PATCH_3=d54155a80a4fa8d2ebc2a5a850e7ea8270818217 \
    DANE_PATCH_3_SHA256=69e82e374ef534262fa4f6b556a1201f1643f2307a7e8235472540db6a4288f1 \
    APK_VERSION=3.5.9_p20261123-r0

RUN set -eu; \
    test "$TARGETARCH" = amd64; \
    test "$(apk --print-arch)" = x86_64; \
    apk add --no-cache \
      'alpine-sdk=1.1-r1' \
      'coreutils=9.11-r0' \
      'curl=8.23.0-r0' \
      'gnupg=2.4.9-r1' \
      'linux-headers=7.0.0-r1' \
      'patch=2.8.1-r0' \
      'perl=5.42.3-r0'; \
    installed="$(apk info -v)"; \
    for package in \
      'abuild-3.17.0-r0' \
      'binutils-2.45.1-r1' \
      'gcc-15.2.0-r5' \
      'make-4.4.1-r4' \
      'musl-1.2.6-r3' \
      'musl-dev-1.2.6-r3' \
      'scanelf-1.3.9-r1'; do \
        printf '%s\n' "$installed" | grep -Fx "$package"; \
    done; \
    adduser -D -h /home/builder builder; \
    addgroup builder abuild; \
    install -d -o builder -g builder \
      /home/builder/aports/backport/openssl \
      /home/builder/.abuild \
      /home/builder/packages \
      /var/cache/distfiles; \
    chown builder:builder /var/cache/distfiles; \
    printf '%s\n' "$installed" | LC_ALL=C sort > /home/builder/toolchain-packages.txt; \
    chown builder:builder /home/builder/toolchain-packages.txt

WORKDIR /home/builder/aports/backport/openssl

RUN set -eu; \
    release_base="https://github.com/openssl/openssl/releases/download/openssl-$OPENSSL_VERSION"; \
    curl --fail --location --proto '=https' --tlsv1.2 \
      --output "openssl-$OPENSSL_VERSION.tar.gz" \
      "$release_base/openssl-$OPENSSL_VERSION.tar.gz"; \
    curl --fail --location --proto '=https' --tlsv1.2 \
      --output "openssl-$OPENSSL_VERSION.tar.gz.asc" \
      "$release_base/openssl-$OPENSSL_VERSION.tar.gz.asc"; \
    curl --fail --location --proto '=https' --tlsv1.2 \
      --output openssl-release-keys.asc \
      'https://openssl-library.org/source/pubkeys.asc'; \
    printf '%s  %s\n' "$OPENSSL_SOURCE_SHA256" "openssl-$OPENSSL_VERSION.tar.gz" | sha256sum -c -; \
    printf '%s  %s\n' "$OPENSSL_SIGNATURE_SHA256" "openssl-$OPENSSL_VERSION.tar.gz.asc" | sha256sum -c -; \
    printf '%s  %s\n' "$OPENSSL_KEYS_SHA256" openssl-release-keys.asc | sha256sum -c -; \
    gpg_home="$(mktemp -d)"; \
    chmod 0700 "$gpg_home"; \
    gpg --batch --homedir "$gpg_home" --import openssl-release-keys.asc; \
    gpg --batch --homedir "$gpg_home" --status-fd 1 \
      --verify "openssl-$OPENSSL_VERSION.tar.gz.asc" "openssl-$OPENSSL_VERSION.tar.gz" \
      > openssl-signature.status 2> openssl-signature.log; \
    awk -v expected="$OPENSSL_SIGNING_FINGERPRINT" \
      '$2 == "VALIDSIG" && $NF == expected { valid = 1 } END { exit !valid }' \
      openssl-signature.status; \
    alpine_base="https://raw.githubusercontent.com/alpinelinux/aports/$ALPINE_APORTS_COMMIT/main/openssl"; \
    for item in \
      'auxv.patch:0b3a36c0f1b3d8e0adcf02c251374ce4d2578b0c9f4b88207bf85c5c1b58c7d8' \
      'CVE-2026-88822.patch:50f7cc52fb146703379ca043d17e204c4fda99c684efe3db6d6f3ae1d175bd2a' \
      'CVE-2026-88823.patch:a945077c0a978dbfe5af5e4f7174a2151466547f8fb94562f395baa7b6b7c147' \
      'CVE-2026-95691.patch:93d8e82fdc995172a6d5cc6952491658106191bd5ac221d34596adbddfcd1afe' \
      'CVE-2026-95692.patch:0e328604c0038de6629a29322ae5f02d2f5f5128c2625b57ca64262efab99e05' \
      'CVE-2026-107496.patch:77d3759d279c71af59629c4a0fa82d8272a099762f2fa9e3f66d7ca5ed544157'; do \
        name=${item%%:*}; digest=${item##*:}; \
        curl --fail --location --proto '=https' --tlsv1.2 --output "$name" "$alpine_base/$name"; \
        printf '%s  %s\n' "$digest" "$name" | sha256sum -c -; \
    done; \
    for item in \
      'c8f6d04f8e6d40b1bf83334e4a2ec9daf5943a3d:6176d694ac38251bfa4e11198d002d8720f9efb05deffb35703adb93be6173e1:07-pem-x509-info.patch' \
      '1c65edebefa0dd11aa41cb1a31078ecd9c79d472:a9897de897e3cf34206ba9a4ccb461927bd04c310e9354682ceca317c1537502:08-cmsapi-regression.patch' \
      'f3acb0a3328046741479482c51cec815839e1b05:d52a6af23bc966daaa9eded816a392d429a67bb76faafd922f4c4cf35a9349f4:09-quic-stream-gc.patch' \
      '734c6cd7444bdbf589bfec917e47598c1b21ddd3:c1726a50d589b2dc289727865c184382c2bd846733ddb139427062713ebeb5bd:10-quic-channel-bound.patch' \
      '42919a7f4e00aa217fce1532fbeb49f21be2ee7b:ca74f8bd3f7d6337fae3fe5038604f013c76590b89d6f44593adcb5661f9a0ba:11-quic-radix-regression.patch' \
      '468c287cf451a834b370b69ab321b8ffdfac23aa:6bdbb111a7ce640e42c558aaf912fe19b3770aa9afc7c8c5a0d36d20dcb2d5e9:12-crl-cpu-bound.patch'; do \
        commit=${item%%:*}; remainder=${item#*:}; digest=${remainder%%:*}; name=${remainder##*:}; \
        curl --fail --location --proto '=https' --tlsv1.2 \
          --output "$name" "https://github.com/openssl/openssl/commit/$commit.patch"; \
        printf '%s  %s\n' "$digest" "$name" | sha256sum -c -; \
    done; \
    for item in \
      "$DANE_PATCH_1:$DANE_PATCH_1_SHA256:13-dane-npe.patch" \
      "$DANE_PATCH_2:$DANE_PATCH_2_SHA256:14-dane-fixup.patch" \
      "$DANE_PATCH_3:$DANE_PATCH_3_SHA256:15-dane-review.patch"; do \
        commit=${item%%:*}; remainder=${item#*:}; digest=${remainder%%:*}; name=${remainder##*:}; \
        curl --fail --location --proto '=https' --tlsv1.2 \
          --output "$name" "https://github.com/openssl/openssl/commit/$commit.patch"; \
        printf '%s  %s\n' "$digest" "$name" | sha256sum -c -; \
    done; \
    chown builder:builder ./*

RUN <<'DOCKER_APKBUILD'
cat > APKBUILD <<'APKBUILD'
# Maintainer: D Company isolated security proof <security@invalid.example>
pkgname=openssl
# _p is Alpine's valid patch-level suffix. It denotes a release plus backports
# and cannot masquerade as a future upstream OpenSSL release.
pkgver=3.5.9_p20261123
pkgrel=0
pkgdesc="OpenSSL 3.5.9 with Alpine r1 and reviewed scoped backports"
url="https://www.openssl.org/"
arch="x86_64"
license="Apache-2.0"
provider_priority=100
makedepends="linux-headers perl"
subpackages="libcrypto3:_libcrypto libssl3:_libssl"
source="
	openssl-3.5.9.tar.gz
	auxv.patch
	CVE-2026-88822.patch
	CVE-2026-88823.patch
	CVE-2026-95691.patch
	CVE-2026-95692.patch
	CVE-2026-107496.patch
	07-pem-x509-info.patch
	08-cmsapi-regression.patch
	09-quic-stream-gc.patch
	10-quic-channel-bound.patch
	11-quic-radix-regression.patch
	12-crl-cpu-bound.patch
	13-dane-npe.patch
	14-dane-fixup.patch
	15-dane-review.patch
	"
builddir="$srcdir/openssl-3.5.9"

prepare() {
	cd "$builddir"
	test "$(sed -n 's/^MAJOR=//p' VERSION.dat)" = 3
	test "$(sed -n 's/^MINOR=//p' VERSION.dat)" = 5
	test "$(sed -n 's/^PATCH=//p' VERSION.dat)" = 9
	test -z "$(sed -n 's/^PRE_RELEASE_TAG=//p' VERSION.dat)"

	git init -q
	git config user.name 'D Company proof builder'
	git config user.email 'security@invalid.example'
	git add .
	git commit -q -m 'authenticated OpenSSL 3.5.9 release'

	for patch in \
		auxv.patch \
		CVE-2026-88822.patch \
		CVE-2026-88823.patch \
		CVE-2026-95691.patch \
		CVE-2026-95692.patch \
		CVE-2026-107496.patch \
		07-pem-x509-info.patch \
		08-cmsapi-regression.patch \
		09-quic-stream-gc.patch \
		10-quic-channel-bound.patch \
		11-quic-radix-regression.patch \
		12-crl-cpu-bound.patch; do
		git apply --check "$srcdir/$patch"
		git apply "$srcdir/$patch"
	done
	# The reviewed DANE series needs one line of context instead of the
	# commit-format patch's default; the final file digests below fail closed.
	for patch in 13-dane-npe.patch 14-dane-fixup.patch 15-dane-review.patch; do
		git apply --check -C1 "$srcdir/$patch"
		git apply -C1 "$srcdir/$patch"
	done

	printf '%s  %s\n' \
		25b5bdecc831e734fd3af3d8b1222883929a57f4f15fa44190c0e4f88376a475 crypto/pem/pem_info.c \
		0ef96f292f8fc84e01bc1486bcc9fc6cc52fd0b79930725aa24501fcd16eeccb crypto/x509/x509_vfy.c \
		7bceb5bdc408c3e5a46eb38944f5d16727b812e3e049dae6f2b2e4cdadce3616 ssl/quic/quic_stream_map.c \
		f2d149fc745d3e0862d62ec94517c6558e7c813d0788e7862763d00a114e55a1 ssl/quic/quic_channel.c \
		456ddccb3f84d055d77be8743c2602901180451daa162d33a9653511adb02ef8 test/danetest.c \
		| sha256sum -c -
}

build() {
	local target=linux-x86_64
	CC=${CC#"$CROSS_COMPILE"}
	CXX=${CXX#"$CROSS_COMPILE"}
	CPP=${CPP#"$CROSS_COMPILE"}
	perl ./Configure \
		$target \
		--prefix=/usr \
		--libdir=lib \
		--openssldir=/etc/ssl \
		enable-fips \
		enable-ktls \
		shared \
		no-zlib \
		no-async \
		no-comp \
		no-idea \
		no-mdc2 \
		no-rc5 \
		no-ec2m \
		no-ssl3 \
		no-seed \
		no-weak-ssl-ciphers \
		enable-ec_nistp_64_gcc_128 \
		$CPPFLAGS \
		$CFLAGS \
		$LDFLAGS -Wa,--noexecstack
	perl configdata.pm --dump
	make
}

check() {
	rm -f test/recipes/30-test_afalg.t
	make test TESTS="test_dane test_cmsapi test_cms test_quicapi test_quic_radix test_x509_check_cert_pkey test_sslapi"
}

package() {
	depends="libssl3=$pkgver-r$pkgrel libcrypto3=$pkgver-r$pkgrel"
	provides="openssl3=$pkgver-r$pkgrel"
	replaces="openssl3"
	# Runtime packages need the software and SSL configuration trees, not the
	# generated manuals. This also keeps the discarded main package bounded.
	make DESTDIR="$pkgdir" install_sw install_ssldirs
	rm "$pkgdir/usr/bin/c_rehash"
}

_libcrypto() {
	pkgdesc="Crypto library from OpenSSL 3.5.9 with reviewed backports"
	replaces="libcrypto1.1"
	amove etc/ssl/ct_log_list.cnf
	amove etc/ssl/ct_log_list.cnf.dist
	amove etc/ssl/openssl.cnf
	amove etc/ssl/openssl.cnf.dist
	amove usr/lib/engines-3/afalg.so
	amove usr/lib/engines-3/capi.so
	amove usr/lib/engines-3/loader_attic.so
	amove usr/lib/engines-3/padlock.so
	amove usr/lib/libcrypto.so.3
	amove usr/lib/ossl-modules/legacy.so
}

_libssl() {
	pkgdesc="SSL shared library from OpenSSL 3.5.9 with reviewed backports"
	depends="libcrypto3=$pkgver-r$pkgrel"
	amove usr/lib/libssl.so.3
}
APKBUILD
chown builder:builder APKBUILD
install -d -o builder -g builder /out
test "$(apk version --test "$APK_VERSION" 3.5.9-r1)" = '>'
DOCKER_APKBUILD

USER builder
ENV HOME=/home/builder \
    PACKAGER="D Company isolated proof <security@invalid.example>" \
    REPODEST=/home/builder/packages \
    ABUILD_COMMIT=openssl-3.5.9+dcompany-backports-20261123

RUN set -eu; \
    abuild-keygen -a -n; \
    abuild checksum; \
    abuild -r validate builddeps clean fetch unpack prepare mkusers build check

# Keep the authenticated compile and targeted tests in their own cacheable
# layer. rootpkg signs packages but does not create or validate a repository
# index; index only the two exported runtime archives with the ephemeral key.
RUN set -eu; \
    abuild -r rootpkg; \
    repository="$(find "$REPODEST" -type d -path '*/x86_64' -print -quit)"; \
    test -n "$repository"; \
    mkdir -p /out/repository/x86_64 /out/keys; \
    cp "$repository/libcrypto3-$APK_VERSION.apk" /out/repository/x86_64/; \
    cp "$repository/libssl3-$APK_VERSION.apk" /out/repository/x86_64/; \
    private_key="$(sed -n 's/^PACKAGER_PRIVKEY=//p' "$HOME/.abuild/abuild.conf" | tr -d '\"')"; \
    test -f "$private_key"; \
    test -f "$private_key.pub"; \
    test ! -e "/etc/apk/keys/${private_key##*/}.pub"; \
    cp "$private_key.pub" /out/keys/; \
    for archive in \
      "/out/repository/x86_64/libcrypto3-$APK_VERSION.apk" \
      "/out/repository/x86_64/libssl3-$APK_VERSION.apk"; do \
        apk verify --no-network --repositories-file /dev/null \
          --keys-dir /out/keys "$archive"; \
    done; \
    apk index --no-network --repositories-file /dev/null --keys-dir /out/keys \
      --output /out/repository/x86_64/APKINDEX.tar.gz \
      "/out/repository/x86_64/libcrypto3-$APK_VERSION.apk" \
      "/out/repository/x86_64/libssl3-$APK_VERSION.apk"; \
    abuild-sign -q /out/repository/x86_64/APKINDEX.tar.gz; \
    chmod 0444 /out/repository/x86_64/* /out/keys/*

USER root

RUN <<'VERIFY_PACKAGES'
set -eu
set -o pipefail
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT HUP INT TERM
keys="$work/keys"
mkdir "$keys"
cp /etc/apk/keys/*.rsa.pub "$keys/"
cp /out/keys/*.rsa.pub "$keys/"

for package in libcrypto3 libssl3; do
	archive="/out/repository/x86_64/$package-$APK_VERSION.apk"
	test -f "$archive" && test ! -L "$archive"
	apk verify --no-network --repositories-file /dev/null --keys-dir "$keys" "$archive"
	apk index --no-network --repositories-file /dev/null --keys-dir "$keys" \
		--output "$work/$package.index.tar.gz" "$archive"
	tar -xzOf "$work/$package.index.tar.gz" APKINDEX > "$work/$package.control"
	field() {
		test "$(grep -c "^$1:" "$work/$package.control")" -eq 1
		sed -n "s/^$1://p" "$work/$package.control"
	}
	test "$(field P)" = "$package"
	test "$(field V)" = "$APK_VERSION"
	test "$(field A)" = x86_64
	test "$(field o)" = openssl
	mkdir "$work/$package"
	apk extract --no-network --repositories-file /dev/null --keys-dir "$keys" --no-chown \
		--destination "$work/$package" "$archive"
done

find "$work/libcrypto3" \( -type f -o -type l \) -print \
	| sed "s#^$work/libcrypto3/##" | LC_ALL=C sort > "$work/libcrypto3.files"
printf '%s\n' \
	etc/ssl/ct_log_list.cnf \
	etc/ssl/ct_log_list.cnf.dist \
	etc/ssl/openssl.cnf \
	etc/ssl/openssl.cnf.dist \
	usr/lib/engines-3/afalg.so \
	usr/lib/engines-3/capi.so \
	usr/lib/engines-3/loader_attic.so \
	usr/lib/engines-3/padlock.so \
	usr/lib/libcrypto.so.3 \
	usr/lib/ossl-modules/legacy.so \
	| LC_ALL=C sort > "$work/libcrypto3.expected"
diff -u "$work/libcrypto3.expected" "$work/libcrypto3.files"
find "$work/libssl3" \( -type f -o -type l \) -print \
	| sed "s#^$work/libssl3/##" | LC_ALL=C sort > "$work/libssl3.files"
printf '%s\n' usr/lib/libssl.so.3 > "$work/libssl3.expected"
diff -u "$work/libssl3.expected" "$work/libssl3.files"

crypto="$work/libcrypto3/usr/lib/libcrypto.so.3"
ssl="$work/libssl3/usr/lib/libssl.so.3"
test -f "$crypto" && test -f "$ssl"
readelf -d "$crypto" | grep -F 'Library soname: [libcrypto.so.3]'
readelf -d "$ssl" | grep -F 'Library soname: [libssl.so.3]'
readelf -d "$ssl" | grep -F 'Shared library: [libcrypto.so.3]'

for library in libcrypto.so.3 libssl.so.3; do
	case "$library" in
		libcrypto.so.3) package=libcrypto3 ;;
		libssl.so.3) package=libssl3 ;;
	esac
	nm -D --defined-only "/usr/lib/$library" | awk '{print $3}' | LC_ALL=C sort -u > "$work/$library.official.symbols"
	nm -D --defined-only "$work/$package/usr/lib/$library" | awk '{print $3}' | LC_ALL=C sort -u > "$work/$library.backport.symbols"
	test -s "$work/$library.official.symbols"
	test -s "$work/$library.backport.symbols"
	comm -23 "$work/$library.official.symbols" "$work/$library.backport.symbols" > "$work/$library.missing"
	test ! -s "$work/$library.missing"
done

root="$work/root"
printf '%s\n%s\n' 'file:///out/repository' 'https://dl-cdn.alpinelinux.org/alpine/v3.24/main' > "$work/repositories"
apk add --root "$root" --initdb --no-cache --keys-dir "$keys" --repositories-file "$work/repositories" \
	"libcrypto3=$APK_VERSION" "libssl3=$APK_VERSION"
apk info --root "$root" -e "libcrypto3=$APK_VERSION"
apk info --root "$root" -e "libssl3=$APK_VERSION"
apk info --root "$root" -v | grep -Fx "libcrypto3-$APK_VERSION"
apk info --root "$root" -v | grep -Fx "libssl3-$APK_VERSION"
LD_LIBRARY_PATH="$root/usr/lib" \
	OPENSSL_CONF="$root/etc/ssl/openssl.cnf" \
	OPENSSL_MODULES="$root/usr/lib/ossl-modules" \
	/usr/bin/openssl version -a | tee "$work/version.txt"
grep -F 'OpenSSL 3.5.9' "$work/version.txt"
LD_LIBRARY_PATH="$root/usr/lib" \
	OPENSSL_CONF="$root/etc/ssl/openssl.cnf" \
	OPENSSL_MODULES="$root/usr/lib/ossl-modules" \
	/usr/bin/openssl list -providers -provider default -provider legacy > "$work/providers.txt"
grep -F 'default' "$work/providers.txt"
grep -F 'legacy' "$work/providers.txt"

cp /home/builder/toolchain-packages.txt /out/toolchain-packages.txt
sha256sum \
	auxv.patch \
	CVE-2026-88822.patch \
	CVE-2026-88823.patch \
	CVE-2026-95691.patch \
	CVE-2026-95692.patch \
	CVE-2026-107496.patch \
	07-pem-x509-info.patch \
	08-cmsapi-regression.patch \
	09-quic-stream-gc.patch \
	10-quic-channel-bound.patch \
	11-quic-radix-regression.patch \
	12-crl-cpu-bound.patch \
	13-dane-npe.patch \
	14-dane-fixup.patch \
	15-dane-review.patch \
	> /out/patches.sha256
cp openssl-signature.status /out/openssl-signature.status
toolchain_sha256=$(sha256sum /home/builder/toolchain-packages.txt | awk '{print $1}')
crypto_sha256=$(sha256sum "/out/repository/x86_64/libcrypto3-$APK_VERSION.apk" | awk '{print $1}')
ssl_sha256=$(sha256sum "/out/repository/x86_64/libssl3-$APK_VERSION.apk" | awk '{print $1}')
index_sha256=$(sha256sum /out/repository/x86_64/APKINDEX.tar.gz | awk '{print $1}')
runtime_crypto_sha256=$(sha256sum "$crypto" | awk '{print $1}')
runtime_ssl_sha256=$(sha256sum "$ssl" | awk '{print $1}')
patch_inventory_sha256=$(sha256sum /out/patches.sha256 | awk '{print $1}')
signature_status_sha256=$(sha256sum /out/openssl-signature.status | awk '{print $1}')
cat > /out/provenance.json <<EOF
{"schema":1,"status":"isolated-proof-only","distro":"alpine-3.24.4","architecture":"x86_64","source":{"version":"3.5.9","archive_sha256":"$OPENSSL_SOURCE_SHA256","signature_sha256":"$OPENSSL_SIGNATURE_SHA256","signing_fingerprint":"$OPENSSL_SIGNING_FINGERPRINT"},"alpine_aports_commit":"$ALPINE_APORTS_COMMIT","dane_series":[{"commit":"$DANE_PATCH_1","sha256":"$DANE_PATCH_1_SHA256"},{"commit":"$DANE_PATCH_2","sha256":"$DANE_PATCH_2_SHA256"},{"commit":"$DANE_PATCH_3","sha256":"$DANE_PATCH_3_SHA256"}],"package_version":"$APK_VERSION","targeted_tests":["test_dane","test_cmsapi","test_cms","test_quicapi","test_quic_radix","test_x509_check_cert_pkey","test_sslapi"],"toolchain_manifest_sha256":"$toolchain_sha256","source_evidence_sha256":{"patch_inventory":"$patch_inventory_sha256","signature_status":"$signature_status_sha256"},"archives_sha256":{"libcrypto3":"$crypto_sha256","libssl3":"$ssl_sha256","APKINDEX":"$index_sha256"},"runtime_payload_sha256":{"/usr/lib/libcrypto.so.3":"$runtime_crypto_sha256","/usr/lib/libssl.so.3":"$runtime_ssl_sha256"}}
EOF
chmod 0444 \
	/out/provenance.json \
	/out/toolchain-packages.txt \
	/out/patches.sha256 \
	/out/openssl-signature.status
find /out -type f -name '*.rsa' -print > "$work/private-signing-keys"
test ! -s "$work/private-signing-keys"
VERIFY_PACKAGES

FROM scratch AS artifact
LABEL org.opencontainers.image.title="D Company isolated OpenSSL backport proof" \
      org.opencontainers.image.version="3.5.9_p20261123-r0" \
      org.opencontainers.image.revision="openssl-3.5.9+dcompany-backports-20261123" \
      com.dcompany.release.status="proof-only-not-for-runtime"
COPY --from=builder /out /
