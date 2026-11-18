# syntax=docker/dockerfile:1.7.1@sha256:a57df69d0ea827fb7266491f2813635de6f17269be881f696fbfdf2d83dda33e

# Build only the runtime verifier in a discarded stage; use the signed official zlib package.
FROM python:3.14.10-alpine3.24@sha256:eb2c62658b8d51bff0acc408504b76521d02030de56a8a3c1e830083e89b89d2 AS zlib-probe-builder
RUN apk add --no-cache --virtual .zlib-probe-build-deps build-base
COPY infra/docker/caddy-zlib/runtime-probe.c /tmp/zlib-runtime-probe.c
RUN cc -O2 -Wall -Wextra -Werror /tmp/zlib-runtime-probe.c -ldl -o /tmp/dcompany-caddy-zlib-probe

# Backport the upstream CVE-2026-119170 fix to the official CPython ABI.
FROM python:3.14.10-alpine3.24@sha256:eb2c62658b8d51bff0acc408504b76521d02030de56a8a3c1e830083e89b89d2 AS hashlib-backport
RUN apk add --no-cache 'gcc=15.2.0-r5' 'musl-dev=1.2.6-r3' 'binutils=2.45.1-r1' \
      'openssl-dev=3.5.9-r0' 'patch=2.8.1-r0'
WORKDIR /tmp/cpython
COPY infra/docker/cpython/cpython-7d9da920.patch .
COPY infra/docker/cpython/build-hashlib-backport.py .
COPY infra/docker/cpython/verify-hashlib-fips.py ./verify_hashlib_fips.py
RUN python -B /tmp/cpython/build-hashlib-backport.py

# --- Python dependency builder ---
FROM python:3.14.10-alpine3.24@sha256:eb2c62658b8d51bff0acc408504b76521d02030de56a8a3c1e830083e89b89d2 AS builder
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 PIP_NO_CACHE_DIR=1
COPY infra/scripts/bootstrap-verified-pip.py /tmp/bootstrap-verified-pip.py
RUN apk add --no-cache --upgrade \
      'libcrypto3=3.5.9-r0' \
      'libssl3=3.5.9-r0' \
      'libuuid=2.42.3-r1' \
      'ca-certificates=20261020-r0' \
      'ca-certificates-bundle=20261020-r0' \
    && installed_packages="$(apk info -v)" \
    && printf '%s\n' "$installed_packages" | grep -Fx 'libcrypto3-3.5.9-r0' \
    && printf '%s\n' "$installed_packages" | grep -Fx 'libssl3-3.5.9-r0' \
    && printf '%s\n' "$installed_packages" | grep -Fx 'libuuid-2.42.3-r1' \
    && printf '%s\n' "$installed_packages" | grep -Fx 'ca-certificates-20261020-r0' \
    && printf '%s\n' "$installed_packages" | grep -Fx 'ca-certificates-bundle-20261020-r0' \
    && update-ca-certificates \
    && python -I -B /tmp/bootstrap-verified-pip.py --verify-system-ca \
    && python -c 'import ssl; assert ssl.OPENSSL_VERSION_INFO == (3, 5, 0, 9, 0), (ssl.OPENSSL_VERSION_INFO, ssl.OPENSSL_VERSION)'
WORKDIR /app
COPY backend/requirements.lock .
RUN python -I -B /tmp/bootstrap-verified-pip.py && rm /tmp/bootstrap-verified-pip.py
RUN pip install --prefix=/install --only-binary=:all: --require-hashes -r requirements.lock

# --- runtime ---
FROM python:3.14.10-alpine3.24@sha256:eb2c62658b8d51bff0acc408504b76521d02030de56a8a3c1e830083e89b89d2
ARG APP_VERSION=dev
ARG APP_REVISION=unknown
LABEL org.opencontainers.image.title="D Company ERP Backend" \
      org.opencontainers.image.version="${APP_VERSION}" \
      org.opencontainers.image.revision="${APP_REVISION}" \
      com.dcompany.python.version="3.14.10" \
      com.dcompany.zlib.version="1.4-r0" \
      com.dcompany.zlib.provenance="signed-alpine-apk" \
      com.dcompany.zlib.apk-port.commit="bdf501c870a92a05c097857401f69c48c4810384"
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
COPY infra/scripts/bootstrap-verified-pip.py /tmp/bootstrap-verified-pip.py
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

RUN apk add --no-cache --upgrade \
      'zlib=1.4-r0' \
      'libcrypto3=3.5.9-r0' \
      'libssl3=3.5.9-r0' \
      'libuuid=2.42.3-r1' \
      'ca-certificates=20261020-r0' \
      'ca-certificates-bundle=20261020-r0' \
    && installed_packages="$(apk info -v)" \
    && printf '%s\n' "$installed_packages" | grep -Fx 'libcrypto3-3.5.9-r0' \
    && printf '%s\n' "$installed_packages" | grep -Fx 'libssl3-3.5.9-r0' \
    && printf '%s\n' "$installed_packages" | grep -Fx 'libuuid-2.42.3-r1' \
    && printf '%s\n' "$installed_packages" | grep -Fx 'ca-certificates-20261020-r0' \
    && printf '%s\n' "$installed_packages" | grep -Fx 'ca-certificates-bundle-20261020-r0' \
    && update-ca-certificates \
    && python -I -B /tmp/bootstrap-verified-pip.py --verify-system-ca \
    && python -c 'import ssl; assert ssl.OPENSSL_VERSION_INFO == (3, 5, 0, 9, 0), (ssl.OPENSSL_VERSION_INFO, ssl.OPENSSL_VERSION)' \
    && test "$(python --version)" = 'Python 3.14.10' \
    && printf '%s\n' "$installed_packages" | grep -Fx 'zlib-1.4-r0' \
    && addgroup -S -g 1001 erp \
    && adduser -S -D -u 1001 -G erp -h /home/erp erp
RUN python -I -B /tmp/bootstrap-verified-pip.py && rm /tmp/bootstrap-verified-pip.py
COPY infra/docker/prune-python-sqlite.sh /tmp/prune-python-sqlite.sh
RUN sh /tmp/prune-python-sqlite.sh
WORKDIR /app
COPY --from=builder /install /usr/local
RUN sh /tmp/prune-python-sqlite.sh --after-wheels && rm /tmp/prune-python-sqlite.sh
COPY backend/ .
COPY infra/docker/verify-backend-runtime-profile.py /usr/local/bin/verify-backend-runtime-profile.py
COPY --from=hashlib-backport /out/_hashlib*.so /usr/local/lib/python3.14/lib-dynload/
COPY --from=hashlib-backport /out/cpython-hashlib-build.json /etc/dcompany/cpython-hashlib-build.json
COPY --chmod=0644 infra/docker/cpython/verify-hashlib-fips.py /usr/local/bin/verify-hashlib-fips.py
COPY infra/docker/backend-entrypoint.sh /entrypoint.sh
COPY infra/docker/write-release-identity.sh /tmp/write-release-identity.sh
RUN sh /tmp/write-release-identity.sh "$APP_VERSION" "$APP_REVISION" /etc/dcompany/release-identity.json \
    && rm /tmp/write-release-identity.sh
# Explicit 755 so the non-root `erp` user can read+execute regardless of
# what the host's umask did to the source file when it was copied across.
RUN chmod 755 /entrypoint.sh && chown erp:erp /entrypoint.sh && chown -R erp:erp /app
RUN /usr/local/bin/verify-caddy-zlib backend
USER erp
RUN python -I -B /usr/local/bin/verify-hashlib-fips.py
EXPOSE 8000
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=5 \
  CMD ["python", "-c", "import urllib.request; urllib.request.urlopen('http://localhost:8000/healthz', timeout=4).read()"]
ENTRYPOINT ["/entrypoint.sh"]
