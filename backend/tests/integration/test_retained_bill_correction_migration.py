"""Upgrade and rollback proof on a separately created local PostgreSQL database."""

from __future__ import annotations

import os
import subprocess
import sys
from pathlib import Path

import psycopg
import pytest
from psycopg import sql

from tests.integration.test_cafe_workflow_migration import _disposable_database, _run_alembic
from tests.integration.test_build71_candidate_migration import (
    _assert_0102_catalogue_is_additive, _export_build71_backend, _inventory, _run_feature,
)

TABLES = (
    "companies",
    "branches",
    "terminals",
    "users",
    "shifts",
    "orders",
    "order_lines",
    "gaming_sessions",
    "gaming_session_addons",
    "gaming_void_receipts",
    "gaming_void_addon_lines",
    "payments",
    "refunds",
    "stock_movements",
    "ingredients",
    "batches",
    "audit_log",
)


def _snapshot(database_url: str) -> dict:
    with psycopg.connect(
        database_url.replace("postgresql+psycopg://", "postgresql://", 1)
    ) as connection:
        return {
            table: connection.execute(
                sql.SQL(
                    "SELECT md5(coalesce(jsonb_agg(to_jsonb(x) ORDER BY x.id)::text,'[]')),count(*) FROM {} x"
                ).format(sql.Identifier(table))
            ).fetchone()
            for table in TABLES
        }


def _run_tests(database_url: str, file: str, selector: str) -> None:
    result = subprocess.run(
        [sys.executable, "-m", "pytest", file, "-k", selector, "-q", "--no-cov"],
        cwd=Path(__file__).resolve().parents[2],
        env={**os.environ, "DATABASE_URL": database_url},
        capture_output=True,
        text=True,
        check=False,
    )
    assert result.returncode == 0, result.stdout + result.stderr


@pytest.mark.integration
def test_0096_preserves_existing_retention_and_refuses_correction_history_loss(tmp_path):
    build71_backend = _export_build71_backend(tmp_path / "build71")
    with _disposable_database("erp_retained_correction_0096") as database_url:
        previous = _run_alembic(database_url, "upgrade", "0095")
        assert previous.returncode == 0, previous.stdout + previous.stderr
        _run_feature(
            database_url,
            "tests/integration/test_gaming_session_addons.py",
            "unlinked_void_retains_consumed_addons_on_explicit_pos_shift",
            backend=build71_backend,
        )
        before = _snapshot(database_url)
        assert before["gaming_void_receipts"][1] == 3
        for command in (("upgrade", "0096"), ("downgrade", "0095"), ("upgrade", "0096")):
            result = _run_alembic(database_url, *command)
            assert result.returncode == 0, result.stdout + result.stderr
            assert _snapshot(database_url) == before
        # Current receipt readers require the current schema. Test their
        # retained correction behavior there, rather than pretending the new
        # application can run on 0096 without its later additive tables.
        current = _run_alembic(database_url, "upgrade", "0101")
        assert current.returncode == 0, current.stdout + current.stderr
        _run_tests(
            database_url,
            "tests/integration/test_retained_bill_mistake_correction.py",
            "retained_correction_preserves_history_cold_replay_and_queue",
        )
        corrected = _snapshot(database_url)
        refused = _run_alembic(database_url, "downgrade", "0095")
        assert refused.returncode != 0
        assert "retained correction history prevents downgrade" in refused.stdout + refused.stderr
        assert _snapshot(database_url) == corrected
        with psycopg.connect(
            database_url.replace("postgresql+psycopg://", "postgresql://", 1)
        ) as connection:
            assert connection.execute("SELECT version_num FROM alembic_version").fetchone() == (
                "0101",
            )
            assert connection.execute(
                "SELECT count(*) FROM gaming_retained_bill_corrections"
            ).fetchone() == (1,)

        # Preserve the complete preceding0101 proof; exercise current readers separately.
        at101 = _inventory(database_url)
        current102 = _run_alembic(database_url, "upgrade", "0102")
        assert current102.returncode == 0, current102.stdout + current102.stderr
        at102 = _inventory(database_url)
        assert at102["database_revision"] == "0102"
        assert _snapshot(database_url) == corrected
        _assert_0102_catalogue_is_additive(at101, at102)
        _run_tests(
            database_url,
            "tests/integration/test_retained_bill_mistake_correction.py",
            "retained_correction_preserves_history_cold_replay_and_queue",
        )
        current_corrected = _snapshot(database_url)
        current_inventory = _inventory(database_url)
        refused102 = _run_alembic(database_url, "downgrade", "0095")
        assert refused102.returncode != 0
        assert "retained correction history prevents downgrade" in refused102.stdout + refused102.stderr
        assert _snapshot(database_url) == current_corrected
        assert _inventory(database_url) == current_inventory
        with psycopg.connect(
            database_url.replace("postgresql+psycopg://", "postgresql://", 1)
        ) as connection:
            assert connection.execute("SELECT version_num FROM alembic_version").fetchone() == ("0102",)
            assert connection.execute("SELECT count(*) FROM gaming_retained_bill_corrections").fetchone() == (2,)


@pytest.mark.integration
def test_current_0103_retained_correction_readers_preserve_history_and_downgrade_guard(tmp_path):
    from tests.integration.test_build71_candidate_migration import (
        _assert_0103_catalogue_is_additive,
    )

    historical = _export_build71_backend(tmp_path / "build71-current103-retained")
    with _disposable_database("erp_grn_recovery_20261113_retained103") as database_url:
        assert _run_alembic(database_url, "upgrade", "0095").returncode == 0
        _run_feature(database_url, "tests/integration/test_gaming_session_addons.py",
            "unlinked_void_retains_consumed_addons_on_explicit_pos_shift", backend=historical)
        assert _snapshot(database_url)["gaming_void_receipts"][1] == 3
        assert _run_alembic(database_url, "upgrade", "0102").returncode == 0
        _run_tests(database_url, "tests/integration/test_retained_bill_mistake_correction.py",
            "retained_correction_preserves_history_cold_replay_and_queue")
        before = _inventory(database_url)
        snapshots = _snapshot(database_url)
        assert _run_alembic(database_url, "upgrade", "0103").returncode == 0
        after = _inventory(database_url)
        assert _snapshot(database_url) == snapshots
        _assert_0103_catalogue_is_additive(before, after)
        _run_tests(database_url, "tests/integration/test_retained_bill_mistake_correction.py",
            "retained_correction_preserves_history_cold_replay_and_queue")
        corrected = _snapshot(database_url)
        catalogue = _inventory(database_url)
        refused = _run_alembic(database_url, "downgrade", "0095")
        assert refused.returncode != 0
        assert "retained correction history prevents downgrade" in refused.stdout + refused.stderr
        assert _snapshot(database_url) == corrected
        assert _inventory(database_url) == catalogue
        with psycopg.connect(database_url.replace("postgresql+psycopg://", "postgresql://", 1)) as db:
            assert db.execute("SELECT version_num FROM alembic_version").fetchone() == ("0103",)
            assert db.execute("SELECT count(*) FROM gaming_retained_bill_corrections").fetchone() == (2,)
