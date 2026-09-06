# --- builder ---
FROM python:3.11-slim@sha256:9534e5a8e315485d4061ed659af0fd78a284c015f9b73661b41d6bab25604534 AS builder
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 PIP_NO_CACHE_DIR=1
WORKDIR /app
COPY backend/requirements.lock .
RUN pip install --prefix=/install --only-binary=:all: --require-hashes -r requirements.lock

# --- runtime ---
FROM python:3.11-slim@sha256:9534e5a8e315485d4061ed659af0fd78a284c015f9b73661b41d6bab25604534
ARG APP_VERSION=dev
ARG APP_REVISION=unknown
LABEL org.opencontainers.image.title="D Company ERP Backend" \
      org.opencontainers.image.version="${APP_VERSION}" \
      org.opencontainers.image.revision="${APP_REVISION}"
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
RUN useradd --create-home --uid 1001 erp
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
