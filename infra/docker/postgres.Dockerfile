# syntax=docker/dockerfile:1.7.1@sha256:a57df69d0ea827fb7266491f2813635de6f17269be881f696fbfdf2d83dda33e

# Rebuild gosu from its exact 1.19 source commit with a patched Go toolchain.
# The source archive is content-addressed and the build is single-job to stay
# inside the production VM's memory envelope.
FROM golang:1.26.8-alpine3.24@sha256:ce864e7223ac17b1775e6fd0b4c0db580c2eb50e7953a427916379e4b92a1628 AS gosu-builder

ARG TARGETOS
ARG TARGETARCH

ENV GOTOOLCHAIN=local \
    GOPROXY=https://proxy.golang.org \
    GOSUMDB=sum.golang.org \
    GOVCS=*:off

WORKDIR /src
RUN set -eux; \
    test "${TARGETOS}" = "linux"; \
    test "${TARGETARCH}" = "amd64"; \
    wget -O /tmp/gosu.tar.gz 'https://codeload.github.com/tianon/gosu/tar.gz/6456aaa0f3c854d199d0f037f068eb97515b7513'; \
    echo '33d7537d588ea49458b9509bcf4554bdf5ceacc66da71e5caa1058ea3b689c3b  /tmp/gosu.tar.gz' | sha256sum -c -; \
    tar -xzf /tmp/gosu.tar.gz --strip-components=1; \
    test "$(go env GOVERSION)" = "go1.26.8"; \
    grep -Fx 'const Version = "1.19"' version.go; \
    go mod download; \
    go mod verify; \
    CGO_ENABLED=0 GOOS="${TARGETOS}" GOARCH="${TARGETARCH}" GOMAXPROCS=1 GOMEMLIMIT=768MiB \
      go build -p=1 -mod=readonly -buildvcs=false \
        -trimpath \
        -ldflags='-s -w -buildid=' \
        -o /out/gosu .; \
    echo 'cdcdfbe2a74dc15d62f6f73da877a372641b80104ff04ca5bee7bc36ee9936ab  /out/gosu' | sha256sum -c -; \
    go version -m /out/gosu | grep -F 'go1.26.8'

FROM postgres:16.15-alpine3.24@sha256:cf78e76683b9ca8c5733cbbdce6c9262b45b6767934dd0a95e671f9a0fc20685 AS zlib-builder
WORKDIR /tmp/dcompany-zlib
RUN apk add --no-cache --virtual .zlib-build-deps build-base patch
COPY infra/docker/zlib/ /tmp/dcompany-zlib/
RUN sh /tmp/dcompany-zlib/build-patched-zlib.sh

# Preserve the exact PostgreSQL 16.15 base, entrypoint and data-directory
# contract. Only patched Alpine libraries and the rebuilt gosu are overlaid.
FROM postgres:16.15-alpine3.24@sha256:cf78e76683b9ca8c5733cbbdce6c9262b45b6767934dd0a95e671f9a0fc20685

ARG APP_VERSION=dev
ARG APP_REVISION=unknown

LABEL org.opencontainers.image.title="D Company ERP PostgreSQL" \
      org.opencontainers.image.version="${APP_VERSION}" \
      org.opencontainers.image.revision="${APP_REVISION}" \
      org.opencontainers.image.source="https://github.com/docker-library/postgres" \
      com.dcompany.upstream.version="16.15" \
      com.dcompany.gosu.version="1.19" \
      com.dcompany.gosu.revision="6456aaa0f3c854d199d0f037f068eb97515b7513" \
      com.dcompany.zlib.version="1.3.2" \
      com.dcompany.zlib.source.sha256="bb329a0a2cd0274d05519d61c667c062e06990d72e125ee2dfa8de64f0119d16" \
      com.dcompany.zlib.null-guard.commit="e3dc0a85b7032e98380dec011bc8f2c2ee0d8fca" \
      com.dcompany.zlib.null-guard.sha256="183bc8b9dd078a41a62de5c2d905d9b0196b45bc46f100d7e4147ec228207c74" \
      com.dcompany.zlib.printf-return.commit="bbc2ccf3d0de267576b524b875c769a724a513b0" \
      com.dcompany.zlib.printf-return.sha256="7d00ee29be5e636d30da2890961e83e35cee0b152333ecd97a46ae2e71eb5d47" \
      com.dcompany.zlib.patch.commit="df84af25dc1942490e1d1c899a07619152a46148" \
      com.dcompany.zlib.patch.sha256="110ff14375733173d8aa54574473424fbd7dfe4b81f1ca34a759c6fe14b15b14" \
      com.dcompany.zlib.followup.commit="7235b0a581227c56a79a43ff828f8ef6794194c8" \
      com.dcompany.zlib.followup.sha256="96040ee84d0d187905283912dbd3f7b66ac2033976a2ceefe9b8cca63143d9c2"

COPY --from=zlib-builder /out/usr/lib/libz.so.1.3.2 /usr/lib/libz.so.1.3.2
COPY --from=zlib-builder /out/etc/dcompany/zlib-patch-evidence.env /etc/dcompany/zlib-patch-evidence.env
COPY --from=zlib-builder /out/zlib-runtime-probe /tmp/zlib-runtime-probe
COPY infra/docker/zlib/verify-patched-zlib.sh /tmp/verify-patched-zlib.sh

RUN set -eux; \
    apk add --no-cache --upgrade \
      'libcrypto3=3.5.8-r0' \
      'libssl3=3.5.8-r0' \
      'libuuid=2.42.3-r1'; \
    installed_packages="$(apk info -v)"; \
    printf '%s\n' "$installed_packages" | grep -Fx 'libcrypto3-3.5.8-r0'; \
    printf '%s\n' "$installed_packages" | grep -Fx 'libssl3-3.5.8-r0'; \
    printf '%s\n' "$installed_packages" | grep -Fx 'libuuid-2.42.3-r1'; \
    printf '%s\n' "$installed_packages" | grep -Fx 'zlib-1.3.2-r0'; \
    sh /tmp/verify-patched-zlib.sh; \
    rm -f /tmp/zlib-runtime-probe /tmp/verify-patched-zlib.sh; \
    test "$(postgres --version)" = 'postgres (PostgreSQL) 16.15'

COPY --from=gosu-builder /out/gosu /usr/local/bin/gosu

RUN set -eux; \
    chmod 0755 /usr/local/bin/gosu; \
    gosu --version | grep -F '1.19 (go1.26.8 on linux/'; \
    test "$(readlink /usr/local/bin/su-exec)" = 'gosu'; \
    gosu nobody true; \
    su-exec nobody true
