# syntax=docker/dockerfile:1.7.1@sha256:a57df69d0ea827fb7266491f2813635de6f17269be881f696fbfdf2d83dda33e

# Preserve Redis 7.4.11 and upgrade its OpenSSL and zlib security packages.
# Authenticate the exact official zlib package and prove the final mapped runtime.
FROM redis:7.4.11-alpine3.21@sha256:520775a41a63e77e06c73e35d2fd9cc15921a609516818796b4ecbb813078bc7 AS zlib-probe-builder
RUN apk add --no-cache --virtual .zlib-probe-build-deps build-base
COPY infra/docker/caddy-zlib/runtime-probe.c /tmp/zlib-runtime-probe.c
RUN cc -O2 -Wall -Wextra -Werror /tmp/zlib-runtime-probe.c -ldl -o /tmp/dcompany-caddy-zlib-probe

FROM redis:7.4.11-alpine3.21@sha256:520775a41a63e77e06c73e35d2fd9cc15921a609516818796b4ecbb813078bc7

ARG APP_VERSION=dev
ARG APP_REVISION=unknown

LABEL org.opencontainers.image.title="D Company ERP Redis" \
      org.opencontainers.image.version="${APP_VERSION}" \
      org.opencontainers.image.revision="${APP_REVISION}" \
      org.opencontainers.image.source="https://github.com/redis/docker-library-redis" \
      com.dcompany.upstream.version="7.4.11-alpine3.21" \
      com.dcompany.zlib.version="1.4-r0" \
      com.dcompany.zlib.provenance="signed-alpine-apk" \
      com.dcompany.zlib.apk-port.commit="d3332621d44c2c4cf52e52217b16674db190212c"



COPY --from=zlib-probe-builder /tmp/dcompany-caddy-zlib-probe /usr/local/bin/dcompany-caddy-zlib-probe
COPY infra/docker/caddy-zlib/verify-official-zlib.sh /usr/local/bin/verify-caddy-zlib

# Retain only the authenticated exact archive; downloaded dependencies are discarded.
RUN set -eux; \
    mkdir -p /etc/dcompany/zlib; \
    zlib_download_dir="$(mktemp -d)"; \
    trap 'rm -rf -- "$zlib_download_dir"' EXIT; \
    mkdir "$zlib_download_dir/cache"; \
    apk --cache-dir "$zlib_download_dir/cache" update; \
    apk --cache-dir "$zlib_download_dir/cache" cache download 'zlib=1.4-r0'; \
    zlib_archive=; \
    zlib_archive_count=0; \
    for cached_apk in "$zlib_download_dir/cache"/zlib*; do \
      if [ ! -e "$cached_apk" ] && [ ! -L "$cached_apk" ]; then continue; fi; \
      test -f "$cached_apk"; \
      test ! -L "$cached_apk"; \
      case "${cached_apk##*/}" in \
        zlib-1.4-r0.[0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f].apk) ;; \
        *) echo 'Invalid cached zlib archive.' >&2; exit 1 ;; \
      esac; \
      zlib_archive_count=$((zlib_archive_count + 1)); \
      zlib_archive=$cached_apk; \
    done; \
    if [ "$zlib_archive_count" -ne 1 ]; then \
      apk --no-network --cache-dir "$zlib_download_dir/cache" policy zlib 2>&1 | head -n 30 >&2 || true; \
      exit 1; \
    fi; \
    apk verify --no-network "$zlib_archive"; \
    cp "$zlib_archive" /etc/dcompany/zlib/zlib-1.4-r0.apk; \
    chmod 0444 /etc/dcompany/zlib/zlib-1.4-r0.apk; \
    rm -rf -- "$zlib_download_dir"; \
    trap - EXIT

RUN apk add --no-cache --upgrade \
      'zlib=1.4-r0' \
      'libcrypto3=3.3.7-r2' \
      'libssl3=3.3.7-r2'


RUN set -eux; \
    installed_packages="$(apk info -v)"; \
    printf '%s\n' "$installed_packages" | grep -Fx 'libcrypto3-3.3.7-r2'; \
    printf '%s\n' "$installed_packages" | grep -Fx 'libssl3-3.3.7-r2'; \
    printf '%s\n' "$installed_packages" | grep -Fx 'zlib-1.4-r0'; \
    redis-server --version | grep -F 'v=7.4.11'

RUN --mount=type=bind,source=infra/docker/install-pinned-musl-utils.sh,target=/tmp/install-pinned-musl-utils.sh,ro \
    sh /tmp/install-pinned-musl-utils.sh 3.21

RUN /usr/local/bin/verify-caddy-zlib redis
