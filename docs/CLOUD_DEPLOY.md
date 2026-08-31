# Cloud backend — deployment playbook

Since you chose **cloud-hosted backend + native client apps**, the backend has to live somewhere always-on with a real domain name. This is the playbook.

> **Code17 status: unsupported planning document. Do not deploy from this
> guide.** The current production contract requires a Redis ACL user named
> `erp_backend`, strict key-prefix/command restrictions, seven pairwise-distinct
> managed secrets (including device pairing and frame-relay AEAD keys), fatal
> seed validation, quiesced backup/restore proof, and exact rollback evidence.
> The generic managed services described below do not prove those properties.
> Use [`FREE_DEPLOY.md`](FREE_DEPLOY.md) and the hardened
> `infra/scripts/install-on-vm.sh` flow until a provider-specific Code17
> deployment and rollback runbook has been reviewed and tested.

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

## Managed-cloud deployment status

There is no supported one-click Render/Upstash/managed-Redis procedure for
Code17. Do not paste an arbitrary provider `REDIS_URL`, run migrations in a web
process start command, or seed interactively after opening public traffic.

A future provider-specific runbook must, at minimum, prove all of the following
before this section can become executable:

- an authenticated `erp_backend` Redis identity with the documented command
  allowlist and `dcompany:*` key scope, plus negative ACL tests;
- independent production-valid JWT, Postgres, object-storage, owner, device
  pairing, frame relay, and Redis credentials, supplied through a secret
  manager without logging;
- `SEED_OWNER_EMAIL` and `SEED_OWNER_PASSWORD` validation and a secret-safe
  internal login proving `admin.system` and audit access;
- a maintenance/write gate, drained tablet outboxes, a final quiesced database
  backup, full disposable restore validation, exact migration head, and tested
  rollback that cannot discard post-cutover financial writes;
- private database/Redis/object-store networking, public `/readyz`, immutable
  image/source provenance, and release-specific acceptance evidence.

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
- `frontend/.env.example` currently targets the deployed production API at
  `VITE_API_URL=https://dcompany.duckdns.org/api/v1`; change it only after the
  replacement custom API domain is live and verified.
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
