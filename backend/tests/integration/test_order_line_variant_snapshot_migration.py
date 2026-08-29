"""Migration proof for immutable POS variant snapshots."""

from __future__ import annotations

import psycopg
import pytest

from tests.integration.test_cafe_workflow_migration import (
    _disposable_database,
    _run_alembic,
)


@pytest.mark.integration
def test_0041_adds_snapshot_and_checkout_version_guard_reversibly_when_empty() -> None:
    with _disposable_database("erp_order_variant_snapshot_0041") as database_url:
        upgraded = _run_alembic(database_url, "upgrade", "0040")
        assert upgraded.returncode == 0, upgraded.stdout + upgraded.stderr
        upgraded = _run_alembic(database_url, "upgrade", "0041")
        assert upgraded.returncode == 0, upgraded.stdout + upgraded.stderr

        dsn = database_url.replace("postgresql+psycopg://", "postgresql://", 1)
        with psycopg.connect(dsn) as connection:
            column_type = connection.execute(
                "SELECT data_type FROM information_schema.columns "
                "WHERE table_name = 'order_lines' "
                "AND column_name = 'variant_snapshot'"
            ).fetchone()
            assert column_type == ("jsonb",)
            constraint = connection.execute(
                "SELECT pg_get_constraintdef(oid) FROM pg_constraint "
                "WHERE conname = 'ck_order_line_variant_snapshot_object'"
            ).fetchone()
            assert constraint is not None
            assert "jsonb_typeof(variant_snapshot)" in constraint[0]
            function_definition = connection.execute(
                "SELECT pg_get_functiondef('dcompany_bump_order_checkout_version_from_line()'::regprocedure)"
            ).fetchone()[0]
            assert "OLD.variant_snapshot IS NOT DISTINCT FROM NEW.variant_snapshot" in (
                function_definition
            )
            trigger_definition = connection.execute(
                "SELECT pg_get_triggerdef(oid) FROM pg_trigger "
                "WHERE tgname = 'trg_order_lines_checkout_version_update'"
            ).fetchone()[0]
            assert "variant_snapshot" in trigger_definition

        downgraded = _run_alembic(database_url, "downgrade", "0040")
        assert downgraded.returncode == 0, downgraded.stdout + downgraded.stderr
        with psycopg.connect(dsn) as connection:
            assert connection.execute(
                "SELECT count(*) FROM information_schema.columns "
                "WHERE table_name = 'order_lines' "
                "AND column_name = 'variant_snapshot'"
            ).fetchone()[0] == 0
            function_definition = connection.execute(
                "SELECT pg_get_functiondef('dcompany_bump_order_checkout_version_from_line()'::regprocedure)"
            ).fetchone()[0]
            assert "variant_snapshot" not in function_definition

        reupgraded = _run_alembic(database_url, "upgrade", "0041")
        assert reupgraded.returncode == 0, reupgraded.stdout + reupgraded.stderr
