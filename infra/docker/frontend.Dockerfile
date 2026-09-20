# syntax=docker/dockerfile:1.7.1@sha256:a57df69d0ea827fb7266491f2813635de6f17269be881f696fbfdf2d83dda33e

FROM nginx:alpine-slim@sha256:3b171d7224b669faa3cc2137fea0a65301791df1ec1f271ebd2a2b7461f7fade AS zlib-builder
WORKDIR /tmp/dcompany-zlib
RUN apk add --no-cache --virtual .zlib-build-deps build-base patch
COPY infra/docker/zlib/ /tmp/dcompany-zlib/
RUN sh /tmp/dcompany-zlib/build-patched-zlib.sh

# --- builder ---
FROM node:22-alpine@sha256:c610fcdfb1d5b4740dd70c284ed3cb16bb857e0f7166196e36a5501df7a3aa32 AS builder
ARG VITE_API_URL=/api/v1
ARG APP_VERSION=dev
ARG VITE_APP_VERSION=dev
ENV VITE_API_URL=${VITE_API_URL} VITE_APP_VERSION=${VITE_APP_VERSION}
RUN test "$VITE_APP_VERSION" = "$APP_VERSION" || { echo 'Web UI and release identity versions must match.' >&2; exit 1; }
WORKDIR /app
COPY frontend/package.json frontend/package-lock.json* ./
RUN npm ci --no-audit --no-fund
COPY frontend/ .
RUN npm run build

# --- runtime ---
FROM nginx:alpine-slim@sha256:3b171d7224b669faa3cc2137fea0a65301791df1ec1f271ebd2a2b7461f7fade
ARG APP_VERSION=dev
ARG APP_REVISION=unknown
LABEL org.opencontainers.image.title="D Company ERP Web" \
      org.opencontainers.image.version="${APP_VERSION}" \
      org.opencontainers.image.revision="${APP_REVISION}" \
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
    apk info -e zlib; \
    apk info -v | grep -Fx 'zlib-1.3.2-r0'; \
    sh /tmp/verify-patched-zlib.sh; \
    rm -f /tmp/zlib-runtime-probe /tmp/verify-patched-zlib.sh
COPY infra/nginx/frontend.conf /etc/nginx/conf.d/default.conf
COPY --from=builder /app/dist /usr/share/nginx/html
COPY infra/docker/write-release-identity.sh /tmp/write-release-identity.sh
RUN sh /tmp/write-release-identity.sh "$APP_VERSION" "$APP_REVISION" /usr/share/nginx/html/.well-known/erp-release.json \
    && rm /tmp/write-release-identity.sh
EXPOSE 80
HEALTHCHECK --interval=30s --timeout=5s --retries=3 \
  CMD wget -qO- http://127.0.0.1/ >/dev/null || exit 1
