# syntax=docker/dockerfile:1.7.1@sha256:a57df69d0ea827fb7266491f2813635de6f17269be881f696fbfdf2d83dda33e

# Redis 7.4.11 on current Alpine 3.21 carries OpenSSL 3.3.7-r1. Build the
# independently verified zlib correction in a discarded stage, then overlay
# only the patched shared object and its non-secret provenance in the runtime.
FROM redis:7.4.11-alpine3.21@sha256:520775a41a63e77e06c73e35d2fd9cc15921a609516818796b4ecbb813078bc7 AS zlib-builder
WORKDIR /tmp/dcompany-zlib
RUN apk add --no-cache --virtual .zlib-build-deps build-base patch
COPY infra/docker/zlib/ /tmp/dcompany-zlib/
RUN sh /tmp/dcompany-zlib/build-patched-zlib.sh

FROM redis:7.4.11-alpine3.21@sha256:520775a41a63e77e06c73e35d2fd9cc15921a609516818796b4ecbb813078bc7

ARG APP_VERSION=dev
ARG APP_REVISION=unknown

LABEL org.opencontainers.image.title="D Company ERP Redis" \
      org.opencontainers.image.version="${APP_VERSION}" \
      org.opencontainers.image.revision="${APP_REVISION}" \
      org.opencontainers.image.source="https://github.com/redis/docker-library-redis" \
      com.dcompany.upstream.version="7.4.11-alpine3.21" \
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
    installed_packages="$(apk info -v)"; \
    printf '%s\n' "$installed_packages" | grep -Fx 'libcrypto3-3.3.7-r1'; \
    printf '%s\n' "$installed_packages" | grep -Fx 'libssl3-3.3.7-r1'; \
    printf '%s\n' "$installed_packages" | grep -Fx 'zlib-1.3.2-r0'; \
    sh /tmp/verify-patched-zlib.sh; \
    redis-server --version | grep -F 'v=7.4.11'; \
    rm -f /tmp/zlib-runtime-probe /tmp/verify-patched-zlib.sh
