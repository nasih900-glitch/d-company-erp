# Agent guide — D Company ERP

This file is the entrypoint for any AI coding assistant (Codex, Claude Code,
Cursor, etc.) working in this repo. Read this first, then inspect the current
candidate document, Git status, and live CI/deployment evidence.

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
| **DB migrations** | Alembic — current production-candidate head `0071` |
| **Tax engine** | India GST · Kerala intra-state CGST+SGST · Section 9(5) for delivery aggregators · FY April→March |

---

## Hard rules — never break these

1. **Money is `int` minor units (paise)**. NEVER use `float` for money. Variable names use `_minor` suffix (e.g. `total_minor`, `cgst_minor`).
2. **Multi-tenant by `company_id`**. Every business model has `company_id`; every query filters by it via `tenant.company_id` from `TenantContext`.
3. **Soft delete** via `deleted_at`. Don't hard-delete business entities. Use `Model.deleted_at.is_(None)` in queries.
4. **Audit trail auto-records** every write on tracked models (see `app/services/audit/recorder.py`). To add a new model to the trail, append it to `TRACKED` — no per-endpoint code change.
5. **Permissions** are enforced via `Depends(requires("perm.string"))`. Declared in `app/core/permissions.py`. **Don't use a permission string in code without declaring it first** — the diagnostic script catches drift.
6. **Demo mode is permanently OFF**. `frontend/src/lib/demo.ts` hardcodes `LIVE_MODE = true`. Dead `if (!LIVE_MODE)` branches still exist in some screens — leave them; they're unreachable.
7. **JWT secret must be at least 32 chars**. Pydantic settings validates this.
8. **Schema changes require an alembic migration**. Never add a column to a model without writing `alembic/versions/000N_*.py`. The deploy entrypoint runs `alembic upgrade head`.
9. **GST math is centralised** in `app/services/pos/pricing.py`. Don't re-implement tax splitting elsewhere.
10. **Reports aggregator** is in `app/services/reports/aggregator.py`. All P&L reads go through it for consistency.

---

## Common commands

```bash
# === Backend ===
cd backend
python -m compileall -q app                                # compile-check
python -m pytest tests/ -q --no-header --no-cov            # all tests
python -m scripts.seed                                     # seed (idempotent)
python -m scripts.create_user --email x@example.com --name X --role owner  # no-echo prompt

# === Frontend ===
cd frontend
npx tsc --noEmit                                           # strict type check
npx vite build                                             # production bundle

# === Production upgrade on the VM (never rsync/direct Compose) ===
cd /opt/d-company-erp
git fetch --tags --prune
git checkout --detach REVIEWED_40_HEX_RELEASE_COMMIT
test -z "$(git status --porcelain --untracked-files=normal)"
sudo bash infra/scripts/install-on-vm.sh EXISTING_DOMAIN --maintenance-confirmed
```

Fresh installs omit `--maintenance-confirmed`. Existing installations preserve
`/opt`, `.env`, volumes, and rollback evidence and use only the hardened
installer/verified Code16 bridge documented in `backend/docs/REMOTE_ASSISTANCE_API.md`.

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
  alembic/versions/                  migrations chained through current head 0071
  scripts/seed.py                    Idempotent seed (company, accounts, ingredients, tiers)
  tests/                             full pytest unit + integration suite
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

These are baseline source checks, not a production-release verdict. Android,
guarded workflow, migration/restore, signing, upgrade, sync, device and live
acceptance gates still apply according to the affected scope.

---

## What's pending

Do not treat a static list in this guide as current release truth. Read the
current candidate document, `git status`, and live CI or deployment evidence
before describing any feature or gate as complete.

---

## House style

- **Brevity** in code comments. Explain WHY, not WHAT.
- **TypeScript strict** — no `any`. Use `unknown` and narrow.
- **Avoid bullet lists in chat replies** — prefer prose unless the user asks for a list.
- **Don't apologize**. State the situation, propose a fix.
- **Verify before claiming "done"**. Compile + tests + build before saying it works.

---

## Real-world context

D Company is a real café and gaming-lounge operation in Kerala. Ownership, investment figures, personal locations, and credentials are private production information and must not be committed to this public repository.

The user manages:
- An existing Google Sheet configured privately by the owner. Do not commit spreadsheet URLs or Apps Script web-app URLs.
- Their bills in a Drive folder
- A separate Apps Script that matches OCR'd receipts to their Transactions tab (the **`Operations` tab** is where ERP push goes — don't touch other tabs)

Treat this as **production**. Real money flows through it once they open.

---

## Codex task routing and verification phases

These instructions apply to Codex subagent workflows in this repository. They
do not change the primary agent's model, sandbox, or approval policy. Those
remain selected by the user, Codex client, or managed platform policy.

### Route each work item

Before delegating, split the request into bounded work items and choose the
narrowest matching project agent. Do not delegate a trivial task when the main
agent can complete it directly. For non-trivial work, select roles by the work
item's risk and purpose:

| Work item | Project agent | Required use |
|---|---|---|
| Read-only discovery, call-path tracing, repository mapping, or log triage | `erp_explorer` | Use before editing when ownership or failure location is unclear. |
| A scoped implementation or defect fix after the path is understood | `erp_implementer` | Use as the only code-writing agent for that scope. |
| Focused tests, contract checks, build checks, or evidence assessment | `erp_verifier` | Use after implementation; keep verification independent from the writer. |
| Authentication, authorization, tenant isolation, money, secrets, injection, audit integrity, or remote-assistance boundaries | `erp_security_reviewer` | Use for a read-only security pass before completion. |
| Schema migrations, offline/sync behavior, signed artifacts, deployment, rollback, cross-device behavior, or production-readiness claims | `erp_release_auditor` | Use for a read-only release-gate assessment; it never deploys. |
| An unresolved cross-system incident that still spans at least three trust boundaries after focused high/max investigation | `erp_incident_architect` | Escalation-only read-only synthesis; use Astra ultra only when the ordinary roles cannot reach a defensible conclusion. |

Use parallel subagents only for independent, read-only scopes. Keep dependent
phases sequential, and never let multiple agents edit overlapping files. The
main agent owns task decomposition, conflict resolution, and the final claim.
Custom role files select each subagent's model and reasoning effort; the main
agent must not claim that its own model switched dynamically.

Use the lowest-cost role that can answer the bounded question. Escalate to
`erp_incident_architect` only after recording the conflicting evidence and the
specific uncertainty left by the focused implementer, verifier, security, or
release-auditor pass. Do not use ultra for routine implementation, broad
"check everything" requests, or as a substitute for running tests. Return to
the normal role matrix once the uncertainty is resolved.

### Work in evidence-gated phases

1. **Scope:** read the applicable instructions and current project state,
   inspect `git status`, identify existing user changes, and establish a
   reproducible baseline without modifying files.
2. **Plan:** classify risk, trace affected contracts and consumers, choose the
   required agent route, and define focused acceptance checks. For large
   features, briefly explain the architecture before implementation.
3. **Implement:** assign one writer, make the smallest coherent change, preserve
   unrelated work, and include migrations or compatibility handling when the
   contract requires them.
4. **Verify:** run the narrowest meaningful checks first. Record the command,
   exit status, and relevant result. Diagnose failures before changing code or
   broadening the test surface.
5. **Review:** run the security or release auditor when the routing table
   requires it, then run broader regression checks proportional to risk.
6. **Conclude:** distinguish source review, tests, builds, emulator evidence,
   physical-device evidence, signed artifacts, deployment, and production
   observation. A green lower phase is not proof of a higher phase.

Never bypass approvals or sandboxing automatically. Do not use unsafe bypass
flags, do not add `approval_policy = "never"`, and do not select
`danger-full-access` in project configuration. Writer and verifier agents
inherit the active permission mode. If that mode blocks required work, report
the exact blocked action and let the user or managed platform decide whether to
change permissions.
