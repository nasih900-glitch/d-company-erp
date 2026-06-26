#!/bin/sh
# D Company ERP backend entrypoint.
#
# 1. Wait for Postgres to accept connections (compose healthcheck also enforces this,
#    but a belt-and-braces wait inside the container survives slow disk on macOS).
# 2. Apply Alembic migrations.
# 3. Run scripts/seed.py + scripts/seed_india.py — both are idempotent and skip
#    if data already exists.
# 4. Hand off to uvicorn.

set -e

echo "============================================================"
echo "  D Company ERP backend starting…"
echo "============================================================"

# Wait for Postgres (defensive — compose healthcheck should handle this).
echo "Waiting for Postgres…"
python - <<'PY'
import os, time
import psycopg
dsn = os.environ["DATABASE_URL"].replace("+asyncpg", "+psycopg").replace("postgresql+psycopg://", "postgresql://")
for i in range(60):
    try:
        with psycopg.connect(dsn, connect_timeout=2) as _:
            print("  ✓ Postgres ready")
            break
    except Exception as e:
        if i == 59:
            raise SystemExit(f"Postgres never came up: {e}")
        time.sleep(1)
PY

echo "Applying migrations…"
alembic upgrade head

echo "Seeding base data…"
python -m scripts.seed || echo "  (seed already applied)"

echo "Seeding Kerala/India reference data…"
python -m scripts.seed_india || echo "  (seed already applied)"

echo "============================================================"
echo "  Backend ready at http://localhost:8000"
echo "  API docs:           http://localhost:8000/docs"
echo "  Login credentials are managed by the owner accounts."
echo "============================================================"

exec uvicorn app.main:app --host 0.0.0.0 --port 8000 --proxy-headers
