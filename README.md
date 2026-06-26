# D Company ERP — Enterprise V1

Production-grade café + gaming-lounge ERP. POS, tables, menu, inventory (FIFO + recipes), gaming sessions, finance (double-entry), OCR receipts, staff, analytics. Multi-branch and multi-terminal from day one. Cloud-native, with first-class offline support for the POS.

This repository is the **scaffold-stage** baseline. Architecture, schema, auth/RBAC, audit, idempotency, eventing, Docker, and CI are wired end-to-end. Every module has working endpoints; the deep business logic (recipe deduction, journal posting, OCR worker, analytics rollups) lands in subsequent sessions per the build order.

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
│   │   ├── lib/              api client, IndexedDB outbox
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

## What's next (per build order)

Module deep-dives: POS pipeline (recipe deduction → journal posting → receipt rendering), gaming timer engine + tournament mode, inventory FIFO + GRN + reorder, finance reports + partner ledger, OCR worker + verification queue UI, analytics rollups + Power BI export, offline outbox replayer, role-aware UI gating.

## Installer apps (Mac / Windows / Android / iOS)

D Company ERP ships as real installable apps, not just a website. Same React UI, four wrappers:

- **Mac** — Tauri-wrapped native `.dmg`. macOS 11+.
- **Windows** — Tauri-wrapped `.exe` (NSIS) and `.msi`. Windows 10+.
- **Android** — Capacitor-wrapped `.apk` (sideload) and `.aab` (Play Store).
- **iPhone / iPad** — Capacitor-wrapped, distributed via App Store / TestFlight.

All four are built from a single React codebase and point to one cloud API (`VITE_API_URL`).

- [`docs/DISTRIBUTION.md`](docs/DISTRIBUTION.md) — one-time signing & publishing setup (Apple Developer, Windows code-signing, Android keystore, App Store submission).
- [`docs/CLOUD_DEPLOY.md`](docs/CLOUD_DEPLOY.md) — backend hosting (Render / AWS / Fly.io).
- [`frontend/TAURI.md`](frontend/TAURI.md) — desktop build commands.
- [`frontend/CAPACITOR.md`](frontend/CAPACITOR.md) — mobile build commands.
- [`download/index.html`](download/index.html) — auto-platform-detecting download landing page.

Tagging a git release (`git tag v1.2.3 && git push --follow-tags`) triggers `.github/workflows/release.yml`, which builds all four installers in parallel and attaches them to a GitHub Release.

## License

Proprietary — D Company. Not for redistribution.
