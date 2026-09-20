# syntax=docker/dockerfile:1.7.1@sha256:a57df69d0ea827fb7266491f2813635de6f17269be881f696fbfdf2d83dda33e

# Build the independently verified zlib correction in a discarded stage.
FROM python:3.14.7-alpine3.24@sha256:016508ba505da24f7139765bc4bb669df4e88eb2f12eeadd571bf2f88d7533df AS zlib-builder
WORKDIR /tmp/dcompany-zlib
RUN apk add --no-cache --virtual .zlib-build-deps build-base patch
COPY infra/docker/zlib/ /tmp/dcompany-zlib/
RUN sh /tmp/dcompany-zlib/build-patched-zlib.sh

# --- Python dependency builder ---
FROM python:3.14.7-alpine3.24@sha256:016508ba505da24f7139765bc4bb669df4e88eb2f12eeadd571bf2f88d7533df AS builder
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 PIP_NO_CACHE_DIR=1
RUN apk add --no-cache --upgrade 'libuuid=2.42.3-r1'
WORKDIR /app
COPY backend/requirements.lock .
RUN pip install --prefix=/install --only-binary=:all: --require-hashes -r requirements.lock

# --- runtime ---
FROM python:3.14.7-alpine3.24@sha256:016508ba505da24f7139765bc4bb669df4e88eb2f12eeadd571bf2f88d7533df
ARG APP_VERSION=dev
ARG APP_REVISION=unknown
LABEL org.opencontainers.image.title="D Company ERP Backend" \
      org.opencontainers.image.version="${APP_VERSION}" \
      org.opencontainers.image.revision="${APP_REVISION}" \
      com.dcompany.python.version="3.14.7" \
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
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
COPY --from=zlib-builder /out/usr/lib/libz.so.1.3.2 /usr/lib/libz.so.1.3.2
COPY --from=zlib-builder /out/etc/dcompany/zlib-patch-evidence.env /etc/dcompany/zlib-patch-evidence.env
COPY --from=zlib-builder /out/zlib-runtime-probe /tmp/zlib-runtime-probe
COPY infra/docker/zlib/verify-patched-zlib.sh /tmp/verify-patched-zlib.sh
RUN apk add --no-cache --upgrade 'libuuid=2.42.3-r1' \
    && test "$(python --version)" = 'Python 3.14.7' \
    && sh /tmp/verify-patched-zlib.sh \
    && rm -f /tmp/zlib-runtime-probe /tmp/verify-patched-zlib.sh \
    && addgroup -S -g 1001 erp \
    && adduser -S -D -u 1001 -G erp -h /home/erp erp
WORKDIR /app
COPY --from=builder /install /usr/local
COPY backend/ .
COPY infra/docker/backend-entrypoint.sh /entrypoint.sh
COPY infra/docker/write-release-identity.sh /tmp/write-release-identity.sh
RUN sh /tmp/write-release-identity.sh "$APP_VERSION" "$APP_REVISION" /etc/dcompany/release-identity.json \
    && rm /tmp/write-release-identity.sh
# Explicit 755 so the non-root `erp` user can read+execute regardless of
# what the host's umask did to the source file when it was copied across.
RUN chmod 755 /entrypoint.sh && chown erp:erp /entrypoint.sh && chown -R erp:erp /app
USER erp
EXPOSE 8000
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=5 \
  CMD ["python", "-c", "import urllib.request; urllib.request.urlopen('http://localhost:8000/healthz', timeout=4).read()"]
ENTRYPOINT ["/entrypoint.sh"]
