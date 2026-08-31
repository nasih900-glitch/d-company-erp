# D Company ERP — Enterprise V1

Production-grade café + gaming-lounge ERP. POS, tables, menu, inventory (FIFO + recipes), gaming sessions, finance (double-entry), OCR receipts, staff, analytics. Multi-branch and multi-terminal from day one. Cloud-native — the web/PWA client needs a live connection to the backend; it has no offline queue.

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

## What's next (per build order)

Module deep-dives: POS pipeline (recipe deduction → journal posting → receipt rendering), gaming timer engine + tournament mode, inventory FIFO + GRN + reorder, finance reports + partner ledger, OCR worker + verification queue UI, analytics rollups + Power BI export, offline outbox replayer, role-aware UI gating.

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
`.github/workflows/release.yml`. The current local release candidate is `3.1.7`
(`18`); Android code `8` remains the minimum-compatible floor. The signed
`3.1.3` (`14`) direct-release APK remains the manual, update-capable partner
baseline. It must not be uploaded to the server release directory, published as
a GitHub or Play release, or registered as an update. The public status contract
is `/api/v1/public/client-compatibility?platform=android&version_code=<installed-code>`.

The immutable signed `3.1.2` (`13`) APK remains the predecessor used to prove
the supported in-place upgrade to code `14`; neither signed identity may be
rebuilt with different bytes. Code `15` (`3.1.4`) is the first identity admitted
by the server-release registry, but it is an immutable held audit build, not the
current rollout target. Do not rebuild, overwrite, or activate it as a shortcut.
Code `16` (`3.1.5`) and Code `17` (`3.1.6`) are immutable predecessors. Code 17
introduced consent-gated ERP-only remote assistance. Code `18` (`3.1.7`) is a
separate standard-premium visual candidate; it does not publish, advertise,
stage, or activate an Android server update. The production compatibility
defaults remain pinned until a later rollout is explicitly reviewed. Any
eventual artifact must be newly built and signed, verified against its exact
SHA-256, byte size, package, version and expected signer, and pass same-lineage
upgrade proof. Android still requires the employee to approve installation.
Emulator evidence is not physical Redmi Pad 2 proof.

## License

Proprietary — D Company. Not for redistribution.
