# Deployment

D Company ERP is cloud-native by design. The same Docker images run on a single Linux VPS or behind a managed load balancer.

## Local dev

```bash
docker compose up --build
docker compose exec backend python -m scripts.seed
```

## Single VPS (Linux)

1. Provision Ubuntu 22.04+. Install Docker.
2. Clone repo, `cp .env.example .env`, edit secrets.
3. Bring up the stack: `docker compose up -d`.
4. Apply migrations and seed: `docker compose exec backend alembic upgrade head && docker compose exec backend python -m scripts.seed`.
5. Front the stack with Caddy or Cloudflare Tunnel for TLS.

## Cloud-native (recommended for production)

Replace the in-compose services with managed equivalents:

| Concern | Local (compose) | Cloud |
|---|---|---|
| Postgres | `postgres:16-alpine` | AWS RDS / GCP Cloud SQL / Render PG |
| Redis | `redis:7-alpine` | AWS ElastiCache / Upstash / Render Redis |
| Object storage | MinIO | S3 / R2 / GCS |
| App | `backend` container | Fargate / Cloud Run / Render |
| Frontend | `frontend` (nginx) | CDN-served static (CloudFront / Cloudflare Pages) |
| Worker | (separate compose service to add) | Same container, different command |

Set the corresponding env vars in your platform's secrets manager:

- `DATABASE_URL` (managed Postgres DSN, asyncpg or psycopg compatible)
- `REDIS_URL`
- `S3_*` (endpoint + creds)
- `JWT_PRIVATE_KEY` / `JWT_PUBLIC_KEY` (use RS256; rotate quarterly)
- `CORS_ORIGINS` (list)

## Backup & restore

- DB: managed daily snapshots + 7-day PITR. Test restore monthly into a staging environment.
- Object storage: enable versioning + lifecycle policy to cold storage after 90 days.
- Application backup (self-hosted): `pg_dump erp > /backups/erp-$(date +%F).sql.gz`. Off-site via rclone to S3.

## Observability

- Logs: structured JSON to stdout. Ship via Loki / CloudWatch / Datadog.
- Metrics: `/metrics` endpoint (Prometheus) — to add in V2.
- Tracing: OpenTelemetry hooks land with the worker module.
- Healthchecks: `/healthz` (process alive), `/readyz` (DB + Redis reachable).

## Performance budgets

See `ARCHITECTURE.md §19`. Failing budgets in CI block merges (perf-test job in V2).
