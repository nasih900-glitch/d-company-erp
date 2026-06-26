# Cloud backend — deployment playbook

Since you chose **cloud-hosted backend + native client apps**, the backend has to live somewhere always-on with a real domain name. This is the playbook.

## What you're deploying

- **API** (the FastAPI app from `backend/`) — needs to be reachable at `https://api.dcompany.cloud`
- **Postgres** — managed; no self-hosting unless you have a reason
- **Redis** — managed (cheap tier is fine)
- **Object storage** — for receipt OCR uploads
- **Workers** (V2) — same container, different command (Arq workers for OCR + EOD)

## Cheapest production setup

| Component | Provider | Plan | Monthly |
|---|---|---|---|
| API | [Render](https://render.com) Web Service | Starter (512 MB) | $7 |
| Postgres | Render Postgres | Starter (1 GB) | $7 |
| Redis | Render Redis | Free tier (25 MB) | $0 |
| Object storage | [Cloudflare R2](https://www.cloudflare.com/products/r2/) | 10 GB free, then $0.015/GB | ~$0–$1 |
| DNS / TLS | Cloudflare | Free | $0 |
| **Total** | | | **~$15/mo** |

That's enough for the first 6-12 months of a single café. Scale up when traffic warrants it.

## Production-grade setup (multi-branch ready)

| Component | Provider | Plan | Monthly |
|---|---|---|---|
| API (autoscale) | AWS Fargate or Fly.io | 2× 1 vCPU / 1 GB | $40 |
| Postgres | AWS RDS / Neon | 2 vCPU / 4 GB | $50 |
| Redis | Upstash | Pay-as-you-go | $5 |
| Object storage | AWS S3 | Standard | $5 |
| CDN + DNS | Cloudflare | Free or Pro | $0–$20 |
| **Total** | | | **~$100/mo** |

## Deploy steps (Render — easiest path)

1. Push the repo to GitHub.
2. Create a new **Web Service** on Render → connect the repo.
3. Set:
   - **Build command**: `cd backend && pip install -r requirements.txt`
   - **Start command**: `cd backend && alembic upgrade head && uvicorn app.main:app --host 0.0.0.0 --port $PORT --proxy-headers`
   - **Dockerfile path** (alternative): `infra/docker/backend.Dockerfile`
4. Create a new **PostgreSQL** instance. Render gives you a `DATABASE_URL`.
5. Create a **Redis** instance.
6. On the Web Service, set environment variables (from `.env.example`):
   - `ENV=prod`
   - `DATABASE_URL` (paste from Render Postgres)
   - `REDIS_URL` (paste from Render Redis)
   - `JWT_SECRET` (generate: `openssl rand -base64 48`)
   - `S3_*` (Cloudflare R2 creds)
   - `CORS_ORIGINS=["https://app.dcompany.cloud","https://get.dcompany.cloud"]`
7. Render gives you `your-app.onrender.com`. Add a custom domain (`api.dcompany.cloud`) and point a CNAME in Cloudflare.
8. SSH into the running container once to seed:
   ```bash
   python -m scripts.seed
   ```
   ⚠️ Change the seeded owner password on first login.

## Going from dev to production

| Setting | Dev | Prod |
|---|---|---|
| `ENV` | `dev` | `prod` |
| `EXPOSE_DOCS` | `true` | `false` |
| `LOG_FORMAT` | `console` | `json` |
| `JWT_ALGORITHM` | `HS256` | `RS256` (with key files) |
| `JWT_SECRET` | `CHANGE_ME` | rotated quarterly from secrets manager |
| `S3_ENDPOINT_URL` | MinIO local | leave blank for AWS, or set to R2 endpoint |
| `CORS_ORIGINS` | `["http://localhost:5173"]` | locked to your real domains |

## Custom domain checklist

In Cloudflare DNS, create:

| Record | Type | Value | Purpose |
|---|---|---|---|
| `api.dcompany.cloud` | CNAME | `your-app.onrender.com` | API |
| `app.dcompany.cloud` | CNAME | (if you also host the web UI) | Browser-only users |
| `get.dcompany.cloud` | CNAME | `your-pages-site.pages.dev` | Download page |

After DNS propagates (5-30 min), update:
- `frontend/.env.example` → `VITE_API_URL=https://api.dcompany.cloud/api/v1`
- The CI release workflow's `API_URL` secret
- `tauri.conf.json` → CSP `connect-src` allowlist already includes `*.dcompany.cloud`

## Backups

- **Postgres**: Render Postgres has daily automated backups + PITR on paid plans. Test the restore once a month.
- **Object storage**: enable versioning + lifecycle to delete versions older than 90 days.
- **Off-site copy**: weekly `pg_dump` into an R2 bucket via a Render Cron Job (see `scripts/backup.sh` — TODO).

## Observability

- **Logs**: Render captures stdout. For long retention ship to [Better Stack](https://betterstack.com) (free tier 1 GB).
- **Uptime**: [UptimeRobot](https://uptimerobot.com) free tier pings `/healthz` every 5 minutes.
- **Errors**: [Sentry](https://sentry.io) free tier — add `sentry-sdk[fastapi]` and one init line.

## Scaling triggers

| Metric | Action |
|---|---|
| API p95 latency > 400 ms sustained | Bump Render plan to Standard (2 vCPU) |
| Postgres CPU > 60% sustained | Move to managed Postgres (Neon / RDS) with read replicas |
| OCR queue growing | Add a worker service (same image, different command) |
| Multi-branch ≥ 3 | Move to Fargate / Cloud Run for autoscaling |
