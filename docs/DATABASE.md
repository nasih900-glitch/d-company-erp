# Database

PostgreSQL 16. UUID primary keys (`uuid_generate_v4`-equivalent via app; `pgcrypto` enabled for future use). Timezone-aware timestamps everywhere. Money is `BIGINT` minor units.

## Conventions

- Primary keys: `id UUID` (except `audit_log.id BIGSERIAL`).
- Timestamps: `created_at`, `updated_at` (server-default `now()`, `updated_at` on update).
- Soft delete: `deleted_at TIMESTAMPTZ NULL` on most user-facing tables. Hard delete only for OCR raw uploads after retention.
- Multi-tenancy: `company_id` on every business row. `branch_id` on operational rows.
- Indexes: every FK that filters queries. Time-series tables index `created_at`.
- Constraints: `CHECK (amount_minor > 0)` on `journal_lines`; `EXCLUDE` using `tstzrange` on `gaming_bookings` to prevent overlap.

## Money

All money stored as `BIGINT` in minor units (paise for INR). The application's `Money` value object is the only safe way to add/subtract; raw int arithmetic in the services layer is forbidden. See `backend/app/core/money.py` and `backend/tests/unit/test_money.py`.

## Audit log

- Table: `audit_log` (`BIGSERIAL` PK).
- Append-only. No updates, no deletes.
- Partitioned by month in production (migration in V2; baseline is non-partitioned for simplicity).
- Indexed on `(company_id)`, `(entity_type, entity_id)`, `(action)`, `(created_at)`.

## Idempotency

- Table: `idempotency_keys`. Key is the client-supplied `Idempotency-Key`.
- Stores the request hash, response status, response body.
- TTL: 24h (sweeper task in `workers/`).
- POS, payments, and refunds REQUIRE the header. Other writes accept it.

## Chart of accounts

Seeded by `scripts/seed.py` (see `DEFAULT_ACCOUNTS`). Every sale, payment, refund, expense, and capital event posts a balanced journal entry. Reports recompute from the journal.

## Migrations

- Baseline: `backend/alembic/versions/0001_initial.py` (hand-written to match models).
- Add new migrations with `alembic revision --autogenerate -m "<message>"`.
- CI runs `alembic upgrade head` against an empty Postgres to verify migrations apply cleanly.
