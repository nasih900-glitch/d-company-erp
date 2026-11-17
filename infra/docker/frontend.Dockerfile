# syntax=docker/dockerfile:1.7.1@sha256:a57df69d0ea827fb7266491f2813635de6f17269be881f696fbfdf2d83dda33e

FROM nginx:alpine-slim@sha256:3b171d7224b669faa3cc2137fea0a65301791df1ec1f271ebd2a2b7461f7fade AS zlib-probe-builder
RUN apk add --no-cache --virtual .zlib-probe-build-deps build-base
COPY infra/docker/caddy-zlib/runtime-probe.c /tmp/zlib-runtime-probe.c
RUN cc -O2 -Wall -Wextra -Werror /tmp/zlib-runtime-probe.c -ldl -o /tmp/dcompany-caddy-zlib-probe

# --- builder ---
FROM node:22-alpine@sha256:c610fcdfb1d5b4740dd70c284ed3cb16bb857e0f7166196e36a5501df7a3aa32 AS builder
ARG VITE_API_URL=/api/v1
ARG APP_VERSION=dev
ARG VITE_APP_VERSION=dev
ENV VITE_API_URL=${VITE_API_URL} VITE_APP_VERSION=${VITE_APP_VERSION}
RUN test "$VITE_APP_VERSION" = "$APP_VERSION" || { echo 'Web UI and release identity versions must match.' >&2; exit 1; }
WORKDIR /app
COPY frontend/package.json frontend/package-lock.json* ./
COPY frontend/braces-compat ./braces-compat
COPY frontend/vendor/dcompany-braces-depth-guard ./vendor/dcompany-braces-depth-guard
RUN npm ci --no-audit --no-fund
COPY frontend/ .
RUN npm run build

# --- runtime ---
FROM nginx:alpine-slim@sha256:3b171d7224b669faa3cc2137fea0a65301791df1ec1f271ebd2a2b7461f7fade
ENV NGINX_VERSION=1.31.8
ARG APP_VERSION=dev
ARG APP_REVISION=unknown
LABEL org.opencontainers.image.title="D Company ERP Web" \
      org.opencontainers.image.version="${APP_VERSION}" \
      org.opencontainers.image.revision="${APP_REVISION}" \
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
      'pcre2=10.49-r0'; \
    test "$(apk --print-arch)" = 'x86_64'; \
    printf '%s\n' 'e09fa32f0a0eab2b879ccbbc4d0e4fb9751486eedda75e35fac65802cc9faa266425edf83e261137a2f4d16281ce2c1a5f4502930fe75154723da014214f0655  /etc/apk/keys/nginx_signing.rsa.pub' | sha512sum -c -; \
    wget -T 30 -qO /tmp/dcompany-nginx-1.31.8-r1.apk 'https://nginx.org/packages/mainline/alpine/v3.24/main/x86_64/nginx-1.31.8-r1.apk'; \
    printf '%s\n' '51bb8ca7072b422c9355656e45a1aafec24eb1606adb3a84c562f80731d3f848  /tmp/dcompany-nginx-1.31.8-r1.apk' | sha256sum -c -; \
    apk add --no-cache --no-network --upgrade --repositories-file /dev/null /tmp/dcompany-nginx-1.31.8-r1.apk; \
    rm /tmp/dcompany-nginx-1.31.8-r1.apk; \
    installed_packages="$(apk info -v)"; \
    printf '%s\n' "$installed_packages" | grep -Fx 'libcrypto3-3.5.9-r0'; \
    printf '%s\n' "$installed_packages" | grep -Fx 'libssl3-3.5.9-r0'; \
    printf '%s\n' "$installed_packages" | grep -Fx 'pcre2-10.49-r0'; \
    printf '%s\n' "$installed_packages" | grep -Fx 'nginx-1.31.8-r1'; \
    test "$NGINX_VERSION" = '1.31.8'; \
    nginx -v 2>&1 | grep -Fx 'nginx version: nginx/1.31.8'; \
    apk info -e zlib; \
    apk info -v | grep -Fx 'zlib-1.4-r0'
RUN --mount=type=bind,source=infra/docker/install-pinned-musl-utils.sh,target=/tmp/install-pinned-musl-utils.sh,ro \
    sh /tmp/install-pinned-musl-utils.sh 3.24
COPY infra/nginx/frontend.conf /etc/nginx/conf.d/default.conf
COPY --from=builder /app/dist /usr/share/nginx/html
COPY infra/docker/write-release-identity.sh /tmp/write-release-identity.sh
RUN sh /tmp/write-release-identity.sh "$APP_VERSION" "$APP_REVISION" /usr/share/nginx/html/.well-known/erp-release.json \
    && rm /tmp/write-release-identity.sh \
    && find /usr/share/nginx/html -type d -exec chmod 0755 {} + \
    && find /usr/share/nginx/html -type f -exec chmod 0644 {} +
EXPOSE 80
HEALTHCHECK --interval=30s --timeout=5s --retries=3 \
  CMD wget -qO- http://127.0.0.1/ >/dev/null || exit 1

RUN /usr/local/bin/verify-caddy-zlib frontend
