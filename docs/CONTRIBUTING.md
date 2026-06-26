# Contributing

## Local setup

See `README.md` quickstart.

## Code style

- Python: `ruff check . && ruff format .` and `mypy app`.
- TypeScript: `npm run lint && npm run typecheck`.
- Pre-commit hooks (to wire in V2): ruff, mypy, prettier, eslint.

## Layering

- Routers → schemas → services → repositories → models.
- Routers MUST NOT touch SQLAlchemy directly.
- Services own transactions (the request boundary commits).
- Domain layer is framework-free.

## Migrations

- Add a migration with `alembic revision --autogenerate -m "<short message>"`.
- Always review the generated SQL — `--autogenerate` is a starting point, not the answer.
- Migrations must be **forward and backward compatible** with the previous release for one deploy cycle (no `DROP COLUMN` in the same release that stops writing it).

## Tests

- Unit tests: pure functions in `app/core` and `app/domain`. Fast, no DB.
- Integration tests: spin up the app, hit endpoints, use the `seed_owner` fixture. Real Postgres in CI.
- Money math has 100% coverage requirement (it's `app/core/money.py`).

## Commits

- One logical change per commit.
- Body explains *why*. The diff already shows *what*.
- Reference the issue/ticket if applicable.

## Reviews

- Two approvals for changes in `finance/`, `pos/`, `inventory/`, or anything touching `journal_lines` / `stock_movements` / `audit_log`.
- One approval elsewhere.

## Security

- Never commit `.env`. Always commit `.env.example` with placeholders when adding a new env var.
- Touching auth or permissions? Add tests AND request a second reviewer.
