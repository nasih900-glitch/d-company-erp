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
      org.opencontainers.image.revision="${APP_REVISION}"
COPY infra/nginx/frontend.conf /etc/nginx/conf.d/default.conf
COPY --from=builder /app/dist /usr/share/nginx/html
COPY infra/docker/write-release-identity.sh /tmp/write-release-identity.sh
RUN sh /tmp/write-release-identity.sh "$APP_VERSION" "$APP_REVISION" /usr/share/nginx/html/.well-known/erp-release.json \
    && rm /tmp/write-release-identity.sh
EXPOSE 80
HEALTHCHECK --interval=30s --timeout=5s --retries=3 \
  CMD wget -qO- http://127.0.0.1/ >/dev/null || exit 1
