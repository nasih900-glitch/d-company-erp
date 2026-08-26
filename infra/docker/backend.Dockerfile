# --- builder ---
FROM python:3.11-slim AS builder
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 PIP_NO_CACHE_DIR=1
RUN apt-get update && apt-get install -y --no-install-recommends build-essential libpq-dev \
    && rm -rf /var/lib/apt/lists/*
RUN python -m pip install --upgrade pip==26.1.2
WORKDIR /app
COPY backend/requirements.lock .
RUN pip install --prefix=/install -r requirements.lock

# --- runtime ---
FROM python:3.11-slim
ARG APP_VERSION=dev
ARG APP_REVISION=unknown
LABEL org.opencontainers.image.title="D Company ERP Backend" \
      org.opencontainers.image.version="${APP_VERSION}" \
      org.opencontainers.image.revision="${APP_REVISION}"
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
RUN apt-get update && apt-get install -y --no-install-recommends libpq5 curl \
    && rm -rf /var/lib/apt/lists/*
RUN python -m pip install --upgrade pip==26.1.2
RUN useradd --create-home --uid 1001 erp
WORKDIR /app
COPY --from=builder /install /usr/local
COPY backend/ .
COPY infra/docker/backend-entrypoint.sh /entrypoint.sh
# Explicit 755 so the non-root `erp` user can read+execute regardless of
# what the host's umask did to the source file when it was copied across.
RUN chmod 755 /entrypoint.sh && chown erp:erp /entrypoint.sh && chown -R erp:erp /app
USER erp
EXPOSE 8000
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=5 \
  CMD curl -fsS http://localhost:8000/healthz || exit 1
ENTRYPOINT ["/entrypoint.sh"]
