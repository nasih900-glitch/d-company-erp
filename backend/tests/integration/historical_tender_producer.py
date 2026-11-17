"""Populate owned0091 fixtures through their exact immutable API producers."""

from __future__ import annotations

import io
import os
from pathlib import Path
import re
import subprocess
import sys
import tarfile
import tomllib

import psycopg
from sqlalchemy.engine import make_url

from tests.integration.test_client_installation_capacity_migration import (
    _sync_url,
    _validate_local_database_url,
    _validate_no_ambient_libpq_destination,
)

ROOT = Path(__file__).resolve().parents[3]
BUILD51 = "a44397d53d94fb4c83b3fc9ffe696dbf4dce3168"
BUILD54 = "5ad146ae076f5178acc2c8e4cb840ecbb0d0343a"
PRODUCER_VERSIONS = {BUILD51: "3.1.43", BUILD54: "3.1.46"}


def seed_historical_tender_history(database_url: str, destination: Path, *, revision: str) -> dict:
    """No current router, fake future columns, or runtime admission is involved."""
    assert revision in PRODUCER_VERSIONS
    _validate_no_ambient_libpq_destination()
    parsed = make_url(database_url)
    _validate_local_database_url(parsed, label="historical producer database")
    assert re.fullmatch(r"erp_build(?:51_nonempty_tender|55_stable_schema)_[0-9a-f]{16}", parsed.database or "")
    dsn = _sync_url(database_url)
    with psycopg.connect(dsn) as connection:
        assert connection.execute("SELECT current_user").fetchone() == ("erp",)
        assert connection.execute("SELECT version_num FROM alembic_version").fetchone() == ("0091",)
        for table in ("companies", "payment_tender_corrections", "daily_tender_reconciliations"):
            assert connection.execute(psycopg.sql.SQL("SELECT count(*) FROM {}").format(
                psycopg.sql.Identifier(table)
            )).fetchone() == (0,)
    archive = subprocess.run(
        ["git", "archive", "--format=tar", revision, "backend", "infra/scripts"],
        cwd=ROOT, capture_output=True, check=True,
    )
    destination.mkdir()
    with tarfile.open(fileobj=io.BytesIO(archive.stdout)) as package:
        for member in package.getmembers():
            assert member.isfile() or member.isdir()
            assert (
                member.isdir() and member.name in {"backend", "infra", "infra/scripts"}
            ) or member.name.startswith(("backend/", "infra/scripts/"))
        package.extractall(destination, filter="data")
    backend = destination / "backend"
    assert tomllib.loads((backend / "pyproject.toml").read_text())["project"]["version"] == PRODUCER_VERSIONS[revision]
    environment = {**os.environ, "ENV": "test", "DATABASE_URL": database_url}
    for key in ("PYTHONPATH", "PYTHONHOME"):
        environment.pop(key, None)
    seeded = subprocess.run(
        [sys.executable, "-m", "pytest", "tests/integration/test_build51_history_bridge.py::"
         "test_current_legacy_history_preserves_real_nonempty_tender_ledgers", "-q", "--no-cov"],
        cwd=backend, env=environment, capture_output=True, text=True, timeout=180,
        check=False,
    )
    assert seeded.returncode == 0, seeded.stdout + seeded.stderr
    with psycopg.connect(dsn) as connection:
        assert connection.execute("SELECT version_num FROM alembic_version").fetchone() == ("0091",)
        assert connection.execute("SELECT count(*) FROM payment_tender_corrections").fetchone() == (1,)
        assert connection.execute("SELECT count(*) FROM daily_tender_reconciliations").fetchone() == (1,)
        assert connection.execute("SELECT to_regclass('public.pos_receipt_corrections')").fetchone() == (None,)
        assert connection.execute(
            "SELECT count(*) FROM information_schema.columns WHERE table_schema='public' "
            "AND table_name='shifts' AND column_name IN ('operator_user_id','operator_revision')"
        ).fetchone() == (0,)
        row = connection.execute(
            "SELECT c.id,d.id,o.company_id,o.branch_id,s.id,s.opened_by,s.opened_at "
            "FROM payment_tender_corrections c JOIN payments p ON p.id=c.payment_id "
            "JOIN orders o ON o.id=p.order_id JOIN shifts s ON s.id=o.shift_id "
            "JOIN daily_tender_reconciliations d ON d.company_id=o.company_id"
        ).fetchall()
        assert len(row) == 1
    return dict(zip(("payment_correction", "daily_reconciliation", "company", "branch", "shift", "owner", "start_at"), row[0], strict=True))
