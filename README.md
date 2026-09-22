# D Company ERP — Enterprise V1

Production-grade café + gaming-lounge ERP. POS, tables, menu, inventory (FIFO + recipes), gaming sessions, finance (double-entry), OCR receipts, staff, analytics. Multi-branch and multi-terminal from day one. Cloud-native — the web/PWA client needs a live connection to the backend; it has no offline queue.

This repository contains the implemented ERP and its release-gated web, backend,
and native Android clients. Core Gaming, POS, shift, inventory, finance, audit,
idempotency, offline-recovery, Docker, migration, and CI workflows are present.
A green local checkout is still only a candidate: follow the release evidence and
production migration gates before distributing or activating a build.

## Quickstart (Docker)

```bash
cp .env.example .env
printf '\nSEED_OWNER_PASSWORD=%s\n' "$(openssl rand -base64 24)" >> .env
docker compose up --build
# wait for healthy postgres + backend
docker compose exec backend python -m scripts.seed
open http://localhost:5173
# login: owner@dcompany.local / the SEED_OWNER_PASSWORD value in your local .env
```

## Quickstart (local Mac dev)

```bash
# 1. Postgres + Redis + MinIO (use compose for these even if running app locally):
docker compose up -d postgres redis minio

# 2. Backend
cd backend
python -m venv .venv && source .venv/bin/activate
pip install -e '.[dev]'
cp ../.env.example .env
alembic upgrade head
python -m scripts.seed
uvicorn app.main:app --reload --port 8000

# 3. Frontend
cd ../frontend
npm install
npm run dev
```

## Repo layout

```
d-company-erp/
├── README.md
├── docker-compose.yml
├── .env.example
├── .github/workflows/ci.yml
├── docs/
│   ├── ARCHITECTURE.md       Decisions, layers, data flow, deployment
│   ├── er-diagram.svg        Full ER diagram for all 9 modules
│   ├── DATABASE.md           Schema overview + indexing strategy
│   ├── DEPLOYMENT.md         Cloud + VPS playbook
│   ├── SECURITY.md           AuthN/AuthZ, secrets, audit
│   ├── MODULES.md            One-paragraph orientation per module
│   ├── CONTRIBUTING.md       How to work in the repo
│   └── modules/              Per-module README
├── backend/
│   ├── app/
│   │   ├── main.py           FastAPI app factory + lifespan
│   │   ├── api/v1/           Routers per module
│   │   ├── core/             Config, security, db, errors, middleware, tenant, permissions, money, idempotency
│   │   ├── models/           SQLAlchemy 2.0 declarative models
│   │   ├── services/         Use-cases (transaction boundaries)
│   │   ├── repositories/     Data access (interface + SQLAlchemy impl)
│   │   ├── schemas/          Pydantic v2 request/response
│   │   ├── domain/           Pure value objects, enums
│   │   ├── events/           Domain events + in-process bus
│   │   ├── sync/             Offline outbox/conflict logic (POS)
│   │   └── workers/          Arq workers (OCR, EOD, rollups)
│   ├── alembic/              Migrations (0001 = baseline)
│   ├── scripts/seed.py       Seed company/owner/menu
│   └── tests/                pytest (unit, integration)
├── frontend/
│   ├── src/
│   │   ├── main.tsx          React entrypoint
│   │   ├── app/App.tsx       Router
│   │   ├── modules/          One folder per module
│   │   ├── components/       Layout, UI, charts
│   │   ├── lib/              api client, checkout retry/draft recovery
│   │   └── styles/           Tailwind + design tokens
│   └── package.json
└── infra/
    ├── docker/               Backend & frontend Dockerfiles
    └── nginx/                Frontend nginx + reverse-proxy config
```

## What V1 delivers

- ✅ Architecture & ER diagram
- ✅ PostgreSQL schema for every module, with audit, idempotency, multi-tenancy
- ✅ FastAPI app with JWT, RBAC, structured logging, request context, idempotency, exception handlers, eventing
- ✅ Working endpoints in all 9 modules (auth, POS, tables, menu, inventory, gaming, finance, OCR, staff, analytics + admin)
- ✅ React + TypeScript + Tailwind dark gaming-cafe UI shell with routing, auth, and per-module screens
- ✅ Docker Compose (postgres, redis, minio, backend, frontend) — cloud-portable
- ✅ GitHub Actions CI (lint, type-check, migrations, tests, image builds)
- ✅ pytest + vitest scaffolding with seed fixtures

## Release status

The current local candidate is documented in
[`docs/CODE30_3_PATCH_CANDIDATE.md`](docs/CODE30_3_PATCH_CANDIDATE.md). That file
separates source/test evidence from signing, hosted-update, physical-device, and
production-deployment approval.

## Web and mobile apps

D Company ERP currently has two supported clients backed by the same cloud API:

- **Web** — the primary production client.
- **Android** — the native Kotlin/Compose app in `android-native`, producing a
  signed `.apk` for direct installation and `.aab` for Play internal testing.
The old Capacitor shells in `frontend/android` and `frontend/ios`, plus the
Tauri macOS/Windows wrapper in `frontend/src-tauri`, are outside the supported
release scope. Never build or distribute `frontend/android`: it is an archived,
separately identified prototype, not another supported ERP app.

- [`docs/DISTRIBUTION.md`](docs/DISTRIBUTION.md) — Android signing, testing, and publishing setup.
- [`docs/CLOUD_DEPLOY.md`](docs/CLOUD_DEPLOY.md) — backend hosting (Render / AWS / Fly.io).
- [`frontend/TAURI.md`](frontend/TAURI.md) — desktop build commands.
- [`frontend/CAPACITOR.md`](frontend/CAPACITOR.md) — archived-shell warning;
  supported Android releases come only from `android-native`.
- [`download/index.html`](download/index.html) — release-status landing page that
  links only to the live web ERP and verified artifacts from the official GitHub repository.

Tagging a release that exactly matches the Android `versionName` triggers
`.github/workflows/release.yml`. The current local candidate is the additive
Code30.3 patch, technical version `3.1.30` with Android installation build `38`,
Room schema `52`, and backend migration head `0081`. It preserves Code30.2
manual finance, private receipt evidence and the optional durable
`ERP Mirror v1` Google Sheets backup. Code30.3 adds owner-reviewed,
exact-candidate recovery for a stale tablet Gaming overlay, atomic split
tender, Web station-transfer parity, and business-day shift opening/closing
times. The build-38 tablet must reconnect, apply a matching cleanup directive
and acknowledge it; Web cannot rewrite an offline Room database and exposes no
broad clear-all action. Authorized users retain
eligible same-branch cross-user shift, POS and Gaming workflows while actor
attribution and sensitive-operation permissions remain intact.
PostgreSQL remains authoritative. An unconfigured Sheet mirror is disabled;
saving configuration enables it as verification pending, and the ERP calls it
Connected only after a connection-test event for that exact configuration is
delivered. Business events are held durably before verification. A verified
connection must drain pending, leased and quarantined work before URL/secret
rotation or disconnect; old events are never retargeted. Reward activation and
WhatsApp messaging remain disabled. Android code `8` remains the
minimum-compatible floor.

Code30.2 `v3.1.29` / build `37` at commit
`3ea84be4718a794d5a2e8efc7ac9bcacbc0cee01` is the immutable release
predecessor.
Its exact scope and evidence remain in
[`docs/CODE30_2_PATCH_CANDIDATE.md`](docs/CODE30_2_PATCH_CANDIDATE.md). Code30.3
must pass a same-signer in-place upgrade from that exact predecessor without
clearing data. A source change, green local build, or emulator run does not sign,
deploy, stage, activate, offer, install, or approve the update. The public status
contract remains
`/api/v1/public/client-compatibility?platform=android&version_code=<installed-code>`.
Android still requires the employee to approve installation, and emulator proof
is not physical Redmi Pad 2 acceptance.

See [`docs/CODE30_3_PATCH_CANDIDATE.md`](docs/CODE30_3_PATCH_CANDIDATE.md) for
the current scope, evidence boundaries, and remaining delivery gates.

## License

Proprietary — D Company. Not for redistribution.
