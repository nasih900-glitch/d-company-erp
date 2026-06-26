# Agent guide — D Company ERP

This file is the entrypoint for any AI coding assistant (Codex, Claude Code, Cursor, etc.) working in this repo. Read this first, then `PROJECT_STATE.md` for the running snapshot.

---

## What this project is

**D Company ERP** — a production-grade, multi-revenue café + gaming lounge management system for D Company (a real Kerala-based café opening soon).

It runs:
- **POS** (food, drinks, hookah, gaming sessions, event tickets)
- **Inventory** (recipe-driven auto-deduction, FIFO batches, suppliers, GRN)
- **Finance** (expenses, partner capital, P&L, GST reports)
- **Customer loyalty + memberships** (D Club Silver/Gold/Platinum)
- **Kitchen Display System** (real-time tickets to kitchen iPad)
- **Reports** (daily/monthly/quarterly/yearly P&L, GSTR-1, GSTR-3B CSV exports)
- **Accounting** (Trial Balance, Balance Sheet, GL viewer)
- **Insights** (inventory valuation, recipe margins, growth, losses, heatmap)
- **Full audit log** of every create/update/delete
- **Public menu page** at `/menu` (for QR table ordering)
- **Google Sheets sync** to `Operations` tab in the user's existing sheet

Live at: <https://dcompany.duckdns.org>

---

## Tech stack

| Layer | Tech |
|---|---|
| **Backend** | FastAPI 0.110+, SQLAlchemy 2.0 async, asyncpg, Pydantic v2, Alembic |
| **Database** | PostgreSQL 16 (UUID PKs, JSONB, multi-tenant by `company_id`) |
| **Auth** | Argon2id passwords · JWT HS256 · 15-min access / 7-day refresh |
| **Frontend** | React 18, TypeScript strict, Vite, TailwindCSS, React Router (Hash router), axios, React Query, Recharts |
| **Deploy** | Docker Compose · Caddy reverse proxy (auto-HTTPS via Let's Encrypt) · DigitalOcean or any VM |
| **Domain** | `dcompany.duckdns.org` (DuckDNS, free) |
| **DB migrations** | Alembic — 6 revisions so far (`0001` → `0006`) |
| **Tax engine** | India GST · Kerala intra-state CGST+SGST · Section 9(5) for delivery aggregators · FY April→March |

---

## Hard rules — never break these

1. **Money is `int` minor units (paise)**. NEVER use `float` for money. Variable names use `_minor` suffix (e.g. `total_minor`, `cgst_minor`).
2. **Multi-tenant by `company_id`**. Every business model has `company_id`; every query filters by it via `tenant.company_id` from `TenantContext`.
3. **Soft delete** via `deleted_at`. Don't hard-delete business entities. Use `Model.deleted_at.is_(None)` in queries.
4. **Audit trail auto-records** every write on tracked models (see `app/services/audit/recorder.py`). To add a new model to the trail, append it to `TRACKED` — no per-endpoint code change.
5. **Permissions** are enforced via `Depends(requires("perm.string"))`. Declared in `app/core/permissions.py`. **Don't use a permission string in code without declaring it first** — the diagnostic script catches drift.
6. **Demo mode is permanently OFF**. `frontend/src/lib/demo.ts` hardcodes `LIVE_MODE = true`. Dead `if (!LIVE_MODE)` branches still exist in some screens — leave them; they're unreachable.
7. **JWT secret must be ≥16 chars**. Pydantic settings validates this.
8. **Schema changes require an alembic migration**. Never add a column to a model without writing `alembic/versions/000N_*.py`. The deploy entrypoint runs `alembic upgrade head`.
9. **GST math is centralised** in `app/services/pos/pricing.py`. Don't re-implement tax splitting elsewhere.
10. **Reports aggregator** is in `app/services/reports/aggregator.py`. All P&L reads go through it for consistency.

---

## Common commands

```bash
# === Backend ===
cd backend
python -m compileall -q app                                # compile-check
python -m pytest tests/ -q --no-header --no-cov            # all tests (26)
python -m scripts.seed                                     # seed (idempotent)
python -m scripts.create_user --email x --name X --role owner --password Y

# === Frontend ===
cd frontend
npx tsc --noEmit                                           # strict type check
npx vite build                                             # production bundle

# === Deploy to droplet ===
DROPLET_IP="YOUR_DROPLET_IP"
KEY="${SSH_KEY:-~/.ssh/dcompany_erp}"
PROJECT_DIR="${PROJECT_DIR:-$PWD}"

rsync -avz --quiet \
  --exclude 'node_modules' --exclude '.venv' --exclude '__pycache__' \
  --exclude 'dist' --exclude 'package-lock.json' \
  -e "ssh -i $KEY" \
  "$PROJECT_DIR/" \
  root@$DROPLET_IP:/tmp/d-company-erp/

ssh -i $KEY root@$DROPLET_IP << 'EOF'
rsync -a /tmp/d-company-erp/ /opt/d-company-erp/
cd /opt/d-company-erp
docker compose -f docker-compose.prod.yml --env-file .env build backend frontend
docker compose -f docker-compose.prod.yml --env-file .env up -d --force-recreate backend frontend
EOF
```

---

## File map (key paths)

```
backend/
  app/
    main.py                          FastAPI app factory + lifespan
    api/v1/router.py                 Wires all module routers
    api/v1/{module}/router.py        20 modules, each one router file
    core/
      tenant.py                      JWT → TenantContext (also feeds audit recorder)
      permissions.py                 Permission strings + role map (DON'T add undeclared perms)
      security.py                    Argon2 + JWT
      db.py                          Async session factory
    models/                          SQLAlchemy 2.0 models, one file per domain
      __init__.py                    Re-exports — add new models here
    services/
      pos/pricing.py                 GST split + invoice numbering
      reports/aggregator.py          All P&L roll-ups
      inventory/deduction.py         Recipe-driven FIFO stock deduction on Order create
      audit/recorder.py              Session events that fill audit_log
      email/mailer.py                SMTP mailer (env-driven)
    workers/
      daily_pnl.py                   Cron-target for 8am IST P&L email
  alembic/versions/                  6 migrations, chained 0001 → 0006
  scripts/seed.py                    Idempotent seed (company, accounts, ingredients, tiers)
  tests/                             pytest — 26 tests (unit + integration)
  entrypoint.sh                      Runs alembic + seed + uvicorn

frontend/
  src/
    app/App.tsx                      Routes (HashRouter)
    components/layout/AppShell.tsx   Sidebar + mobile drawer + nav array
    lib/
      api.ts                         axios client + 401 auto-refresh
      erp-api.ts                     Typed API client — ONE file, all endpoints
      demo.ts                        DEMO_MODE = false (permanent)
      inr.ts                         ₹ formatting + tax math
    modules/
      pos/LivePOSScreen.tsx          Live POS
      pos/OrdersAndShiftsScreen.tsx  Today's orders + shifts
      reports/ReportsScreen.tsx      Print-friendly P&L + GSTR push
      insights/InsightsScreen.tsx    Growth + Inventory + Accounting + Losses tabs
      audit/AuditScreen.tsx          Audit log viewer
      …18 more screens
  vite.config.ts                     Vite + alias '@' → src

infra/
  docker/backend-entrypoint.sh       Runs migrations + seed + uvicorn
  docker/Caddyfile                   Reverse proxy + auto-HTTPS

docker-compose.prod.yml              Postgres + backend + frontend + caddy
integrations/
  google-sheets/Code.gs              Apps Script — writes to "Operations" tab
docs/                                17 markdown docs, all useful
```

---

## Permission strings (declared in `app/core/permissions.py`)

Roles: `owner`, `partner`, `manager`, `cashier`, `kitchen`, `gaming_supervisor`, `auditor`.

Owner has all perms. The full role→perm map is in `permissions.py`. When adding an endpoint, pick the lowest perm that fits or add a new declared perm.

---

## How to verify nothing's broken

```bash
cd backend && python -m pytest tests/ -q && \
cd ../frontend && npx tsc --noEmit && npx vite build
```

If all three pass: you're green.

---

## What's pending (read `PROJECT_STATE.md` for current state)

- WhatsApp alerts (need Twilio or Meta Business creds)
- Daily P&L SMTP (infra ready, need creds — see `docs/DEFERRED_FEATURES.md`)
- Razorpay subscription billing (need test keys)
- KDS doesn't yet filter to food/drink/dessert types (gaming/event orders also show)
- Membership tier discount NOT auto-applied at checkout pricing (~30 min wiring)
- No POS UI for ad-hoc order discount (column exists; UI doesn't)
- Subscription expiry worker doesn't exist (subs expire by date but nothing cancels them)
- Free PS5/hookah weekly counters never auto-reset
- No rate limiting on `/auth/login` (acceptable at current scale)

---

## House style

- **Brevity** in code comments. Explain WHY, not WHAT.
- **TypeScript strict** — no `any`. Use `unknown` and narrow.
- **Avoid bullet lists in chat replies** — prefer prose unless the user asks for a list.
- **Don't apologize**. State the situation, propose a fix.
- **Verify before claiming "done"**. Compile + tests + build before saying it works.

---

## Real-world context

D Company is run by **3 partners**: Nasih (the user — based in UK, café in Kerala), Shemeer, and Rafi. They've invested ₹19,23,365 against a ₹20 lakh budget — the café is in construction. Opening soon.

The user manages:
- An existing Google Sheet configured privately by the owner. Do not commit spreadsheet URLs or Apps Script web-app URLs.
- Their bills in a Drive folder
- A separate Apps Script that matches OCR'd receipts to their Transactions tab (the **`Operations` tab** is where ERP push goes — don't touch other tabs)

Treat this as **production**. Real money flows through it once they open.
