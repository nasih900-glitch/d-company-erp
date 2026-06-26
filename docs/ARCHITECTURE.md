# D Company ERP — Architecture

**Version:** Enterprise V1
**Status:** Design baseline (scaffold-stage)
**Owner:** D Company
**Last updated:** 2026-05-20

---

## 1. Purpose

D Company ERP is the operating system for a hybrid café + gaming lounge: coffee/food/desserts, PS5 stations, football screening events, and (future) VR + simulator areas. It must run a real, multi-branch business: every order is auditable, every cup of coffee debits ingredient stock, every PS5 minute is billable, every expense reconciles to a partner ledger.

This document defines the boundaries, layers, and contracts the rest of the codebase is built against. If a future change conflicts with this document, update the document first.

## 2. Non-negotiable principles

1. **Production first.** No demo logic. No placeholder math in financial paths. No "TODO: real auth later". If a feature isn't ready, it's gated behind a feature flag and the gate is honored.
2. **Financial accuracy.** All money is stored as integer minor units (e.g. paise/cents). All money math goes through a single `Money` value object. Floats are forbidden in finance paths.
3. **Auditability.** Every mutation to a financial, inventory, or staff record writes an audit row. Audit rows are append-only.
4. **Offline-capable POS.** The cashier must be able to take an order if the network dies. Sync resumes when the network returns. Conflict resolution is deterministic.
5. **Multi-tenant from day one.** Every row that belongs to a business entity carries `company_id`, `branch_id`, and (where relevant) `terminal_id`. There is no "single-branch shortcut" anywhere in the schema.
6. **Clean architecture.** Routers depend on services. Services depend on repository interfaces. Repositories depend on SQLAlchemy. Nothing depends on FastAPI inside `domain/` or `services/`.
7. **Idempotency.** Every write endpoint accepts an `Idempotency-Key`. Replays return the original response. Critical paths (payments, inventory deductions) require it.

## 3. System context

```
                  ┌───────────────────────────────────────────┐
                  │           Cashiers / Managers             │
                  │   (Tablet PWA, dark theme, touch-first)   │
                  └───────────────┬───────────────────────────┘
                                  │ HTTPS / WebSocket
                                  ▼
                  ┌───────────────────────────────────────────┐
                  │              Frontend (React)             │
                  │  Vite • TS • Tailwind • PWA • IndexedDB   │
                  └───────────────┬───────────────────────────┘
                                  │ REST + WS
                                  ▼
                  ┌───────────────────────────────────────────┐
                  │             API Gateway (FastAPI)         │
                  │   Auth • RBAC • Rate-limit • Idempotency  │
                  └─┬──────────┬──────────┬──────────┬────────┘
                    │          │          │          │
              POS/Tables   Inventory   Gaming     Finance/OCR
                    │          │          │          │
                    └──────────┴────┬─────┴──────────┘
                                    ▼
                  ┌───────────────────────────────────────────┐
                  │        PostgreSQL (managed)               │
                  │   • Row-level multi-tenant                │
                  │   • Logical replication ready             │
                  └───────────────────────────────────────────┘
                  ┌───────────────────────────────────────────┐
                  │     Redis      │   Object Storage (S3)    │
                  │  cache, queue  │  receipts, OCR uploads   │
                  └───────────────────────────────────────────┘
                  ┌───────────────────────────────────────────┐
                  │  External: Payment Gateway • OCR engine   │
                  │            Power BI export • Email/SMS    │
                  └───────────────────────────────────────────┘
```

## 4. Logical layers

| Layer | Folder | Depends on | Notes |
|---|---|---|---|
| Presentation (HTTP) | `backend/app/api/` | services, schemas | FastAPI routers. No business logic. |
| Schemas | `backend/app/schemas/` | — | Pydantic v2 request/response models. |
| Services | `backend/app/services/` | repositories, domain | Use-cases. Transaction boundaries live here. |
| Domain | `backend/app/domain/` | — | Value objects (Money, Quantity), enums, domain events. Pure Python. |
| Repositories | `backend/app/repositories/` | models, db | Interfaces + SQLAlchemy implementations. |
| Models | `backend/app/models/` | db | SQLAlchemy 2.0 declarative. |
| Infrastructure | `backend/app/core/` | — | Settings, security, logging, db engine, idempotency store. |

Rule of thumb: **dependencies point inward.** A router may import a service; a service may NOT import a router. The domain layer imports nothing from the framework.

## 5. Module map

Each module is a vertical slice. A module owns its router, schemas, service, repository, and models. Cross-module communication goes through service interfaces, never direct repository calls.

| # | Module | Owns | Key entities |
|---|---|---|---|
| 1 | **POS** | Billing, payments, receipts, shifts, refunds | Order, OrderLine, Payment, Shift, Refund |
| 2 | **Tables** | Floor plan, reservations, status, merge/move | Table, Floor, Reservation |
| 3 | **Menu** | Items, variants, modifiers, recipes, availability | MenuItem, Variant, Modifier, Recipe |
| 4 | **Inventory** | Stock, batches, FIFO, GRN, waste, suppliers, POs | Ingredient, Batch, StockMovement, Supplier, PurchaseOrder, GRN |
| 5 | **Gaming** | PS5 stations, sessions, bookings, tournaments | Station, GamingSession, Booking, Tournament |
| 6 | **Finance** | Capital, expenses, P&L, assets, partner ledger | Partner, CapitalEntry, Expense, Asset, JournalEntry |
| 7 | **OCR** | Receipt/invoice scan, extraction, verification queue | OcrUpload, OcrExtraction, OcrVerification |
| 8 | **Staff** | Users, roles, attendance, salary, activity | User, Role, Attendance, Payroll, ActivityLog |
| 9 | **Analytics** | KPIs, dashboards, exports | (read-models / views) |

Cross-cutting: **Audit**, **Auth/RBAC**, **Multi-tenancy**, **Idempotency**, **Sync (offline)**.

## 6. Data flow: a sale, end-to-end

This walkthrough is the canonical example the rest of the system is modeled after.

1. Cashier scans / taps **Cappuccino** on the React POS.
2. UI optimistically adds the line and shows the new total. Locally cached menu + prices used.
3. UI calls `POST /pos/orders` with `Idempotency-Key`. If offline, the request is queued in IndexedDB.
4. Gateway validates JWT, resolves `(company_id, branch_id, terminal_id, user_id)`, attaches them to the request scope.
5. `OrderService.create_order(...)` opens a transaction:
   - Inserts `orders` row (status=`open`).
   - Inserts `order_lines` row (Cappuccino, qty=1, unit_price snapshot).
   - Calls `RecipeService.deduct(...)` which:
     - Looks up the recipe for Cappuccino → Milk 150ml, Beans 18g, Sugar 1u.
     - For each ingredient, picks the oldest non-expired batch (FIFO), inserts `stock_movements` with negative qty, updates batch.
     - If any ingredient is below `reorder_threshold`, emits `LowStockEvent`.
   - Writes `audit_log` row for the order.
6. Commit. Response returns the order with line totals.
7. Cashier taps **Pay → Cash**. UI calls `POST /pos/orders/{id}/payments`.
8. `PaymentService.record(...)` inserts `payments`, transitions order to `paid`, generates `JournalEntry` (Dr Cash, Cr Sales-Food).
9. Receipt is generated (PDF + thermal ESC/POS) and either printed locally or queued for the thermal printer service.
10. `RevenueRollupTask` (nightly) aggregates into materialized views consumed by the Analytics module.

A PS5 session is the same shape, but the "line" is time-based: `start_at`, `end_at`, `rate_per_hour`, billed on stop.

## 7. Multi-tenancy & branching

- Top-level tenant is **Company** (`companies` table). For D Company today, there is one row.
- Below that, **Branch** (`branches`). Multi-branch is real from V1, even if only one branch exists.
- Below that, **Terminal** (`terminals`) — a physical POS device or tablet.
- Every business row carries `company_id` (NOT NULL) and `branch_id` (NOT NULL where applicable).
- All queries are scoped by a `TenantContext` dependency injected into every request. Repositories refuse cross-tenant reads.
- Future: enable Postgres row-level security policies for defense-in-depth.

## 8. Authentication & authorization

- **AuthN**: JWT (RS256), short access token (15 min) + refresh token (7 days, rotating). Password hashing: Argon2id.
- **AuthZ**: RBAC with hierarchical roles:
  - `owner` → all permissions
  - `partner` → finance read, capital write
  - `manager` → POS, inventory, gaming, staff write (within branch)
  - `cashier` → POS write, inventory read
  - `kitchen` → orders read (KDS)
  - `gaming_supervisor` → gaming write
  - `auditor` → read-only across modules
- Permissions are declared as `(module, action)` tuples. Endpoints decorate with `@requires("pos.refund")`.
- **Manager override**: a second user can elevate a single action (refund, void, big discount) by entering credentials at the till. The override is recorded with both user IDs.
- Failed logins: rate-limited, locked after N attempts, logged.
- Session timeout: configurable per role (cashiers shorter than managers).

## 9. Offline strategy (POS)

- The POS frontend caches: menu, prices, tables, current shift, last 200 orders. Stored in IndexedDB.
- Writes are queued in an outbox (IndexedDB) with monotonic `client_seq` per terminal.
- Each write carries `Idempotency-Key = sha256(terminal_id || client_seq || payload)`.
- On reconnect, the outbox replays in order. The server's idempotency store dedupes.
- Conflict resolution rules:
  - **Sales**: server-wins on price/menu; client-wins on order content (cashier is authoritative on what was ordered).
  - **Inventory deductions**: re-derived server-side from the order at sync time. The client never sends raw stock movements.
  - **Shifts**: a shift can be opened offline; if a conflicting open shift exists server-side, the offline shift becomes a "sub-shift" merged into the existing one.
- A **recovery log** records every replay and every conflict for the audit module.

## 10. Idempotency

Every write endpoint reads `Idempotency-Key` from the header (required for POS/Payment/Refund endpoints, optional elsewhere). Keys are stored in `idempotency_keys (key, request_hash, response_body, status_code, created_at)` with a 24h TTL. On duplicate key with matching hash, the original response is returned; on duplicate key with mismatched hash, 409.

## 11. Audit & history

- A single `audit_log` table captures `(actor_id, action, entity_type, entity_id, before, after, ip, user_agent, created_at)`.
- Critical entities (orders, payments, refunds, stock movements, journal entries) also carry their own `*_history` table populated by triggers — this is the "time machine" for the auditor role.
- Soft-delete via `deleted_at` on most tables. Hard-delete is reserved for OCR uploads after retention expiry.

## 12. Financial integrity

- Single **Chart of Accounts** seeded on company creation.
- Every sale, refund, payment, expense, capital movement produces a `journal_entries` row with balanced debits/credits.
- The journal is the source of truth for P&L and the partner ledger. Reports recompute from the journal; they are never stored as canonical values.
- Daily, an `EodCloseTask` snapshots balances into `gl_period_balances` for fast P&L queries.

## 13. Inventory math

- Ingredients are tracked in their **base unit** (ml, g, unit). Recipes declare quantities in the same base unit.
- Batches carry `received_at`, `expires_at`, `cost_per_unit`, `qty_on_hand`.
- FIFO deduction: oldest non-expired batch first. If a batch is exhausted mid-deduction, the next batch picks up the remainder.
- **Waste/damage/transfer** are first-class movement types, not adjustments.
- **Adjustments** require manager role + a reason code.
- Valuation: weighted-average per ingredient, recomputed on every GRN.

## 14. Gaming math

- A `GamingSession` has `station_id`, `start_at`, `end_at`, `paused_minutes`, `rate_per_hour`, `package_id?`.
- Billing on stop: `billable_minutes = (end_at - start_at) - paused_minutes`; `amount = ceil(billable_minutes / 60 * rate_per_hour)` (rounding rule configurable per company).
- Bookings hold a station between two timestamps; conflict detection at insert time using `tstzrange` exclusion constraint.
- Tournament mode: a session is created per match, settled to the tournament ledger rather than the till.

## 15. OCR pipeline

1. Upload (image or PDF) → object storage. Row in `ocr_uploads` with sha256.
2. Background worker (Celery/RQ-equivalent; Arq for asyncio) calls the OCR engine (pluggable: Tesseract local, Google Vision, AWS Textract).
3. Extraction parses vendor, date, amount, line items. Confidence per field.
4. Duplicate detection: hash + (vendor, date, amount) fuzzy match.
5. Row inserted in `ocr_extractions` (status=`needs_review` if any field below threshold).
6. Manager reviews in the OCR verification queue, edits if needed, approves → creates an `Expense` + `JournalEntry`.
7. Retention: configurable; images purged after N days, extracted data retained per finance policy.

## 16. Eventing

A lightweight in-process event bus (FastAPI dependency-injected) emits domain events: `OrderPaid`, `LowStock`, `SessionEnded`, `ExpenseApproved`, etc. Subscribers (analytics rollups, low-stock notifier, journal poster) are wired at startup. The bus has a Redis-backed implementation behind the same interface for cross-process delivery when we add workers.

## 17. Deployment topology (cloud-native from day one)

- **App**: 2× FastAPI containers behind a load balancer (managed). Autoscale on CPU.
- **Worker**: 1× Arq worker container for OCR, EOD close, low-stock notifications.
- **DB**: managed PostgreSQL 16 (e.g. AWS RDS, GCP Cloud SQL, Render). PITR enabled.
- **Cache/queue**: managed Redis.
- **Object storage**: S3-compatible (AWS S3, R2, MinIO in dev) for OCR uploads and receipt PDFs.
- **Static**: frontend built to static assets, served from CDN.
- **Secrets**: env vars from secret manager; never in repo.
- **Observability**: structured JSON logs, OpenTelemetry traces, /metrics (Prometheus), /healthz + /readyz.
- **Local dev**: docker-compose mirrors prod (Postgres, Redis, MinIO, backend, frontend).

## 18. Backup & disaster recovery

- DB: managed daily snapshots + 7-day PITR.
- Object storage: versioning + lifecycle to cold storage at 90d.
- Application backup script (`scripts/backup.sh`) for self-hosted VPS path.
- Restore drill documented in `docs/DEPLOYMENT.md`.

## 19. Performance budgets

| Endpoint class | p95 | p99 |
|---|---|---|
| POS read (menu, tables) | 80ms | 200ms |
| POS order create | 150ms | 400ms |
| Payment record | 200ms | 500ms |
| Gaming session start/stop | 120ms | 300ms |
| Analytics dashboard | 800ms | 1.5s |
| OCR upload (sync part) | 300ms | 600ms |

Frontend: time-to-interactive on tablet < 2.5s on 3G; cashier order-entry interaction < 50ms perceived.

## 20. Build order (what V1 implements)

This scaffold delivers items 1–4 of the build order in skeleton form, with all 9 modules stubbed:

1. ✅ Architecture documented
2. ✅ Database schema designed + migrations
3. ✅ Folder structure
4. ✅ Backend skeleton with auth, RBAC, audit, idempotency, eventing
5. ⏳ POS — wire-frames + endpoints; deep work next session
6. ⏳ Inventory — schema + endpoints; recipe engine next session
7. ⏳ Gaming — schema + endpoints; timer engine next session
8. ⏳ Finance — schema + journal stubs; reports next session
9. ⏳ OCR — schema + upload endpoint; worker next session
10. ⏳ Analytics — read-model placeholders
11. ⏳ Testing — pytest + vitest scaffolds
12. ✅ Deployment — docker-compose + Dockerfiles
13. ✅ Documentation — this doc + module READMEs

## 21. Future work (named, not built)

- WhatsApp / Telegram expense ingestion (OCR module extension)
- Power BI export pipeline (Analytics)
- VR + simulator station types (Gaming, schema already polymorphic via `station_type`)
- Franchise / multi-company billing (Finance + Auth)
- Customer-facing mobile app (loyalty, bookings)
- Kitchen Display System (separate frontend, same API)

## 22. Glossary

- **GRN**: Goods Received Note — the document created when a purchase order is received into inventory.
- **FIFO**: First-In-First-Out — oldest stock batch is consumed first.
- **KDS**: Kitchen Display System.
- **EOD**: End-of-Day close.
- **COGS**: Cost of Goods Sold.
- **Terminal**: a physical POS device (Mac, tablet) identified for offline sync and audit.
