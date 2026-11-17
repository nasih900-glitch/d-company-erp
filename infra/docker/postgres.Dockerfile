# syntax=docker/dockerfile:1.7.1@sha256:a57df69d0ea827fb7266491f2813635de6f17269be881f696fbfdf2d83dda33e

# Rebuild gosu from its exact 1.19 source commit with a patched Go toolchain.
# The source archive is content-addressed and the build is single-job to stay
# inside the production VM's memory envelope.
FROM golang:1.26.9-alpine3.24@sha256:3082400e369fa24d5fc60bca20edab3f6d604e0c5a690ec66b295eff4dd87ade AS gosu-builder

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
    test "$(go env GOVERSION)" = "go1.26.9"; \
    grep -Fx 'const Version = "1.19"' version.go; \
    go mod download; \
    go mod verify; \
    CGO_ENABLED=0 GOOS="${TARGETOS}" GOARCH="${TARGETARCH}" GOMAXPROCS=1 GOMEMLIMIT=768MiB \
      go build -p=1 -mod=readonly -buildvcs=false \
        -trimpath \
        -ldflags='-s -w -buildid=' \
        -o /out/gosu .; \
    echo 'ffd70d9dcf141b9f13ae293ec5a6c48cf8d04f56bddc80cf244c62398b24f2ea  /out/gosu' | sha256sum -c -; \
    go version -m /out/gosu | grep -F 'go1.26.9'

FROM postgres:16.15-alpine3.24@sha256:cf78e76683b9ca8c5733cbbdce6c9262b45b6767934dd0a95e671f9a0fc20685 AS zlib-probe-builder
RUN apk add --no-cache --virtual .zlib-probe-build-deps build-base
COPY infra/docker/caddy-zlib/runtime-probe.c /tmp/zlib-runtime-probe.c
RUN cc -O2 -Wall -Wextra -Werror /tmp/zlib-runtime-probe.c -ldl -o /tmp/dcompany-caddy-zlib-probe

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
      com.dcompany.zlib.version="1.4-r0" \
      com.dcompany.zlib.provenance="signed-alpine-apk" \
      com.dcompany.zlib.apk-port.commit="bdf501c870a92a05c097857401f69c48c4810384"

COPY --from=zlib-probe-builder /tmp/dcompany-caddy-zlib-probe /usr/local/bin/dcompany-caddy-zlib-probe
COPY infra/docker/caddy-zlib/verify-official-zlib.sh /usr/local/bin/verify-caddy-zlib

# Retain only the authenticated exact archive; downloaded dependencies are discarded.
RUN set -eux; \
    mkdir -p /etc/dcompany/zlib; \
    zlib_download_dir="$(mktemp -d)"; \
    apk fetch --no-cache --recursive --output "$zlib_download_dir" 'zlib=1.4-r0'; \
    apk verify --no-network "$zlib_download_dir/zlib-1.4-r0.apk"; \
    cp "$zlib_download_dir/zlib-1.4-r0.apk" /etc/dcompany/zlib/zlib-1.4-r0.apk; \
    chmod 0444 /etc/dcompany/zlib/zlib-1.4-r0.apk; \
    rm -rf -- "$zlib_download_dir"


RUN set -eux; \
    apk add --no-cache --upgrade \
      'zlib=1.4-r0' \
      'libcrypto3=3.5.9-r0' \
      'libssl3=3.5.9-r0' \
      'libuuid=2.42.3-r1'; \
    installed_packages="$(apk info -v)"; \
    printf '%s\n' "$installed_packages" | grep -Fx 'libcrypto3-3.5.9-r0'; \
    printf '%s\n' "$installed_packages" | grep -Fx 'libssl3-3.5.9-r0'; \
    printf '%s\n' "$installed_packages" | grep -Fx 'libuuid-2.42.3-r1'; \
    printf '%s\n' "$installed_packages" | grep -Fx 'zlib-1.4-r0'; \
    test "$(postgres --version)" = 'postgres (PostgreSQL) 16.15'

COPY --from=gosu-builder /out/gosu /usr/local/bin/gosu

RUN set -eux; \
    chmod 0755 /usr/local/bin/gosu; \
    gosu --version | grep -F '1.19 (go1.26.9 on linux/'; \
    test "$(readlink /usr/local/bin/su-exec)" = 'gosu'; \
    gosu nobody true; \
    su-exec nobody true

RUN --mount=type=bind,source=infra/docker/install-pinned-musl-utils.sh,target=/tmp/install-pinned-musl-utils.sh,ro \
    sh /tmp/install-pinned-musl-utils.sh 3.24

RUN /usr/local/bin/verify-caddy-zlib postgres
