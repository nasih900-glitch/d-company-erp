# Project State — D Company ERP

**Snapshot date:** 2026-06-15

Read `AGENTS.md` first for project rules and conventions. This file is the running status snapshot — what's built, what's deployed, what's pending.

---

## Production status — LIVE

| Field | Value |
|---|---|
| Public URL | <https://dcompany.duckdns.org> |
| Droplet | Configured in the owner's private deployment notes. Do not commit live IP addresses. |
| SSH key | Configured locally by the owner. Do not commit SSH key names or paths. |
| Protected owner control | Configured in production DB; do not document live access details or passwords. |
| Business owners | Configured in production DB; do not document live access details or passwords. |
| Seed owner login | Bootstrap owner is suspended in production |
| DB | Postgres 16 in docker compose, volume `d-company-erp_pg-data` |
| Auto-deploy | None. Manual rsync + `docker compose build && up -d`. |
| Auto-migrations | Yes — `backend-entrypoint.sh` runs `alembic upgrade head` on container start |

---

## Backend — 94 routes across 20 modules

Module → number of routes:

```
accounting       4   (COA, TB, Balance Sheet, GL)
admin            3   (audit unlock + audit log + facets)
analytics        2   (dashboard, CSV export)
auth             3   (login, refresh, me)
customers        3   (list, by-phone, upsert, get, patch)
events           6   (CRUD + tickets + check-in)
finance          8   (expenses, partners, capital entries, assets, pnl)
gaming           5   (stations CRUD + sessions start/stop)
insights         6   (inventory valuation, recipe margin, growth, top items, heatmap, losses)
inventory        7   (ingredients CRUD, suppliers CRUD, GRN, adjustments, batches)
kitchen          2   (queue, set state)
memberships      5   (tiers CRUD, subscribe, get customer sub, cancel)
menu             4   (categories + items CRUD)
ocr              3   (upload, queue, verify)
pos              7   (orders CRUD + list, payments, refunds, shifts open/close/list)
public           1   (read-only menu — no auth)
reports          7   (daily/monthly/quarterly/yearly/range + GSTR-1/GSTR-3B CSVs)
settings         6   (company, branches, terminals, expense categories)
staff            6   (users CRUD + roles + me/password + attendance)
tables           6   (floors, tables CRUD + status + reservations)
```

---

## Frontend — 22 screens

```
src/modules/
  auth/              Login, AuthContext, RequireAuth
  pos/               LivePOSScreen, POSScreen (demo, unreachable), OrdersAndShiftsScreen, LiveReceipt, Receipt
  tables/            TablesScreen
  menu/              MenuScreen
  inventory/         InventoryScreen
  gaming/            GamingScreen
  events/            EventsScreen
  finance/           FinanceScreen (3 tabs: Overview, Expenses, Partners)
  ocr/               OcrScreen
  staff/             StaffScreen
  customers/         CustomersScreen
  analytics/         AnalyticsScreen
  insights/          InsightsScreen (4 tabs: Growth, Inventory, Accounting, Losses)
  reports/           ReportsScreen (print-friendly, push to Sheets)
  audit/             AuditScreen (password unlock + owner-readable summaries + filters)
  settings/          SettingsScreen (5 tabs: Account, Company, Branches, Memberships, Sheets)
  kitchen/           KitchenScreen (no shell, full-screen tiles, polls every 3s)
  public/            PublicMenuScreen (no auth, /menu)
```

**Sidebar order** (in `components/layout/AppShell.tsx`):
POS · Operations · Tables · Menu · Inventory · Gaming · Events · Finance · OCR · Staff · Customers · Analytics · Insights · Reports · Audit Log · Settings

`Inventory`, `Audit Log`, `Staff`, and `Settings` are protected for Nasih only. Backend also enforces protected audit/system/staff-write/inventory-write controls.

---

## Database — 58 tables, migrations 0001 → 0006

Highlights:
- Multi-tenant via `companies.id` + `TenantMixin` on every business model
- Soft-delete via `SoftDeleteMixin.deleted_at`
- `audit_log` table fed by SQLAlchemy session events (see `services/audit/recorder.py`)
- India compliance: `in_state_codes`, `in_hsn_codes`, `in_sac_codes`, `in_gst_rate_slabs`, `in_kerala_pt_slabs`, `in_invoice_counters`
- Membership: `membership_tiers` + `customer_memberships`
- Customer loyalty: `customers` (visit_count, total_spent_minor, loyalty_points)
- KDS: `orders.kitchen_state`, `orders.kitchen_ready_at`

---

## Seeded data (idempotent — re-runnable)

When `scripts/seed.py` runs:

| Entity | Default |
|---|---|
| Company | "D Company" |
| Branch | "Main Branch" |
| Terminal | "Counter 1" |
| Owner user | Seed bootstrap uses `SEED_OWNER_PASSWORD` in production; live seed owner is suspended and live passwords are intentionally not documented |
| Roles | owner, partner, manager, cashier, kitchen, gaming_supervisor, auditor, plus protected owner access for Nasih |
| Chart of Accounts | 46 accounts (1000-Cash, 2100-Tax Payable, 3000-Partner Capital, 4000-Revenue, 4100-Gaming, 4150-Hookah, 4200-Events, …) |
| Menu | 1 category, 1 item (Cappuccino @ ₹180) |
| Ingredients | Milk, Beans, Sugar |
| Stations | PS5-01 to PS5-04 @ ₹200/hr |
| **Partners** (real data) | Nasih (₹5,62,984.90 / 29.27%) · Shemeer (₹6,49,940 / 33.79%) · Rafi (₹7,10,441 / 36.94%) |
| Expense categories | 15 (Furnitures, Building Materials, Labour Charges, Rent, etc. — matches user's existing Lists tab spelling) |
| Membership tiers | D Club Silver / Gold / Platinum (see `seed.py` for prices & perks) |

---

## Integrations

| Integration | Status |
|---|---|
| **Google Sheets** | LIVE — Apps Script writes ERP entries to `Operations` tab. Script: `integrations/google-sheets/Code.gs`. The live spreadsheet URL is private and must not be committed. |
| **Email (SMTP)** | Infra ready (`services/email/mailer.py`), `workers/pnl_alerts.py` sends daily/weekly/monthly/quarterly/half-yearly/yearly P&L + alerts via `/etc/cron.d/dcompany-erp-alerts`. Awaits SMTP_* env vars and a real recipient address on droplet. |
| **WhatsApp / Twilio** | NOT WIRED. Documented in `docs/DEFERRED_FEATURES.md`. |
| **Razorpay** | NOT WIRED. User onboarding in progress. |
| **OCR (Vision API)** | Upload endpoint works; OCR pipeline is a stub. User has a separate Apps Script doing OCR on their Drive folder. |

---

## What's pending (the backlog)

### Critical (do before opening day)
- SMTP/report recipient setup is still required before automated emails can actually leave the VPS.

### Wanted features (need user input)
- WhatsApp birthday + low-stock alerts (needs Twilio or Meta creds)
- Automated P&L emails (needs SMTP creds + real recipient email — infra and cron are there)
- Razorpay subscription billing for memberships (needs test keys)
- Online deposit-on-booking for PS5/hookah (needs Razorpay)
- POS ad-hoc discount UI (~20 min — backend already supports `Order.discount_minor`)

### Hardening
- Subscription expiry worker — subs expire on date but nothing cancels them
- Weekly free-gaming-minutes counter reset worker
- Multi-terminal SELECT FOR UPDATE on inventory batches (irrelevant at 1 terminal)
- IP/device rate limiting on `/auth/login` (account lockout now exists)
- Full double-entry journal posting (currently TB/BS are derived from operational data, not from `journal_lines`)
- Recipe refund handling on order refund

### Nice-to-have
- App Store / Play Store distribution path (`docs/DISTRIBUTION.md`)
- Multi-branch UI (schema ready, no UI)
- Power BI direct connector (CSV exports work today)
- Bank reconciliation (Postgres records vs bank statement matching)

---

## Recent change history (what I built in the last session)

1. **Audit log** — auto-recorded via SQLAlchemy session events. 28 tracked models. UI viewer at `/audit`.
2. **Migration `0006_index_orders_opened_at`** — speeds up daily/monthly aggregation.
3. **Logged recipe-deduction failures** (was silent before).
4. **Token auto-refresh** on 401 in `frontend/src/lib/api.ts`.
5. **Migration `0005_customers_memberships_kds`** — added customers, membership_tiers, customer_memberships tables + Order.kitchen_state/kitchen_ready_at columns.
6. **Insights screen** — Growth (MoM/YoY/WoW), Inventory Valuation + Recipe Margin, Accounting (TB/BS/COA/GL), Losses tracker.
7. **Accounting endpoints** — derive TB and BS from operational data.
8. **Membership system** — tiers + subscribe + cancel + point multiplier.
9. **Kitchen Display System** — `/kitchen` route, polls every 3s.
10. **Daily P&L email infrastructure** — Mailer + worker.
11. **Hookah** — added as 1st-class revenue stream + station type.
12. **Customer DB with phone-based loyalty** — auto-attached on POS checkout with point multipliers (1× food, 2× gaming/hookah/event, × membership tier).
13. **Recipe-driven auto inventory deduction** on order create — FIFO from batches, logs StockMovements.
14. **GSTR-1 + GSTR-3B CSV exports**.
15. **Public menu page** at `/menu`.
16. **Membership tier discounts** — auto-applied during POS pricing before GST split.
17. **KDS filtering** — kitchen queue only includes food, drink, and dessert order lines.
18. **Audit hardening** — audit tab requires password unlock, records login/unlock events, and shows owner-readable summaries with filters.
19. **Protected ownership model** — production roles corrected: Nasih has protected owner access; Shemeer/Rafi/Sameer have owner access; seed owner suspended.
20. **Automated P&L + alerts** — daily/weekly/monthly/quarterly/half-yearly/yearly worker with rule-based operational alerts and cron schedule.
21. **Auth/session hardening** — suspended users are blocked on login/refresh/every authenticated request; repeated bad passwords lock the account temporarily; protected access is hidden from public roles and access-token roles.
22. **Nasih-only controls** — Inventory, Audit Log, Staff, and Settings are hidden from normal owners and backend-protected.
23. **POS settlement hardening** — live POS now records the selected payment method, marks orders paid only after payment, prevents overpayment, and updates shift expected cash.
24. **Financial reporting accuracy** — P&L, GST exports, accounting, and insights count paid orders only; KDS still sees open food/drink/dessert orders.
25. **Startup secret hygiene** — production startup logs no longer print any password; fresh production seed requires `SEED_OWNER_PASSWORD`.
26. **GST/tax safety release** — POS now carries delivery channel/place-of-supply into the pricing engine; aggregator delivery under section 9(5) is separated with zero D Company output GST; event-ticket GST is included in P&L tax totals; Reports has an owner-facing GST health check; branch GST state code normalizes Kerala to `32` via migration `0007`.

---

## Memory hints for the next AI session

If you find these claims, verify before acting:
- "POS already supports discounts" → backend yes, UI no.
- "Membership tier auto-applies discount" → true. Pricing service applies active tier discounts before GST split.
- "All demo data stripped" → true, but dead `if (!LIVE_MODE)` branches exist as unreachable code.
- "Razorpay is wired" → false.
- "Apple App Store submission ready" → false. See `docs/DISTRIBUTION.md`.

The diagnostic script (run by the previous agent) verified:
- ✅ 39 tests pass locally; 3 DB-backed auth-security tests skip when local Postgres is unavailable
- ✅ All 77 frontend API calls match backend routes
- ✅ Zero leaked secrets in source
- ✅ Permission integrity (every used → declared)
- ✅ Money fields all `int`, never `float`
- ✅ No bare except blocks
- ✅ No SQL f-string injection risk

Latest live deploy verified on 2026-06-15:
- ✅ Alembic head is `0007`
- ✅ Backend and frontend containers healthy
- ✅ `https://dcompany.duckdns.org/healthz` returns `{"status":"ok"}`
- ✅ Frontend bundle assets: `assets/index-C0ixt1gO.js`, `assets/index-CY76V4CZ.css`

---

## Where things are documented (existing docs/)

- `docs/ARCHITECTURE.md` — high-level architecture
- `docs/DATABASE.md` — schema reference
- `docs/MODULES.md` — module-by-module functional spec
- `docs/INDIA_TAX_COMPLIANCE.md` — Kerala GST + FSSAI rules
- `docs/GAP_INDIA.md` — gap analysis (mostly closed)
- `docs/DEPLOY_LIVE.md` — full deploy walkthrough
- `docs/QUICKSTART_DO.md` — DigitalOcean condensed
- `docs/SECURITY.md` — security posture
- `docs/PARTNERS.md` — partner onboarding
- `docs/DEFERRED_FEATURES.md` — what needs user creds
- `docs/GOOGLE_SHEETS.md` — Apps Script integration
- `docs/CONTRIBUTING.md` — code conventions
- `docs/RUN_IT_REAL.md` — running with real backend
- `docs/DISTRIBUTION.md` — App Store path notes
- `docs/CLOUD_DEPLOY.md` — cloud deploy
- `docs/FREE_DEPLOY.md` — $0 walkthrough
- `docs/TRY_IT.md` — non-dev preview
- `integrations/google-sheets/Code.gs` — Apps Script source

---

## When you arrive in this repo

```bash
# 1. Sanity check — should all be green
cd backend && python -m pytest tests/ -q
cd ../frontend && npx tsc --noEmit && npx vite build

# 2. Verify the live system
curl -s https://dcompany.duckdns.org/healthz
# expect: {"status":"ok"}

# 3. Read AGENTS.md
# 4. Then read this file
# 5. Then ask the user what they want next
```

Be honest about gaps. Verify before claiming things work. Use real money discipline (int minor units, never floats).
