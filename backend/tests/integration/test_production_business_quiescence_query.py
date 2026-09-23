"""Real PostgreSQL contract for the production maintenance quiescence gate."""

from __future__ import annotations

import hashlib
import importlib.util
import json
import shutil
import subprocess
from datetime import UTC, datetime
from pathlib import Path
from uuid import uuid4

import psycopg
import pytest

from tests.integration.test_cafe_workflow_migration import (
    _disposable_database,
    _run_alembic,
)

_ROOT = Path(__file__).resolve().parents[3]
_QUERY = _ROOT / "infra/scripts/verify-production-business-quiescence.sql"
_VERIFIER = _ROOT / "infra/scripts/verify-production-business-quiescence.py"


def _load_verifier():
    spec = importlib.util.spec_from_file_location("production_business_quiescence", _VERIFIER)
    assert spec is not None
    assert spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def _query(database_url: str) -> dict[str, int]:
    dsn = database_url.replace("postgresql+psycopg://", "postgresql://", 1)
    psql = shutil.which("psql")
    assert psql is not None
    result = subprocess.run(  # noqa: S603 - fixed local psql with disposable DSN
        [
            psql,
            dsn,
            "-X",
            "--no-psqlrc",
            "--quiet",
            "--tuples-only",
            "--no-align",
            "--file",
            str(_QUERY),
        ],
        capture_output=True,
        text=True,
        check=False,
    )
    assert result.returncode == 0, result.stdout + result.stderr
    document = json.loads(result.stdout)
    assert isinstance(document, dict)
    return document


def _seed_racing_business_state(connection: psycopg.Connection) -> None:
    company_id = uuid4()
    branch_id = uuid4()
    terminal_id = uuid4()
    user_id = uuid4()
    shift_id = uuid4()
    station_id = uuid4()
    now = datetime.now(UTC)

    connection.execute(
        "INSERT INTO companies (id, name, gst_registration_type) "
        "VALUES (%s, 'Quiescence race', 'unregistered')",
        (company_id,),
    )
    connection.execute(
        "INSERT INTO branches "
        "(id, company_id, name, code, state_code, invoice_series_code) "
        "VALUES (%s, %s, 'Main', 'M1', '32', 'QB')",
        (branch_id, company_id),
    )
    connection.execute(
        "INSERT INTO terminals (id, branch_id, name, purpose, device_id) "
        "VALUES (%s, %s, 'Till 1', 'hybrid', %s)",
        (terminal_id, branch_id, f"quiescence-{uuid4()}"),
    )
    connection.execute(
        "INSERT INTO users (id, company_id, email, password_hash, name) "
        "VALUES (%s, %s, %s, 'not-a-password', 'Quiescence operator')",
        (user_id, company_id, f"quiescence-{uuid4()}@test.local"),
    )
    connection.execute(
        "INSERT INTO shifts "
        "(id, company_id, branch_id, terminal_id, opened_by, opened_at, status) "
        "VALUES (%s, %s, %s, %s, %s, %s, 'open')",
        (shift_id, company_id, branch_id, terminal_id, user_id, now),
    )
    connection.execute(
        "INSERT INTO stations "
        "(id, company_id, branch_id, code, name, type, rate_per_hour_minor) "
        "VALUES (%s, %s, %s, 'R1', 'Race station', 'simulator', 18000)",
        (station_id, company_id, branch_id),
    )
    connection.execute(
        "INSERT INTO orders "
        "(id, company_id, branch_id, terminal_id, shift_id, opened_by, type, "
        "status, opened_at) VALUES "
        "(%s, %s, %s, %s, %s, %s, 'takeaway', 'held', %s)",
        (uuid4(), company_id, branch_id, terminal_id, shift_id, user_id, now),
    )
    for status, end_at in (("active", None), ("ended", now)):
        connection.execute(
            "INSERT INTO gaming_sessions "
            "(id, company_id, station_id, shift_id, opened_by, start_at, "
            "end_at, paused_minutes, rate_per_hour_minor, billing_mode, "
            "extra_controllers, status) VALUES "
            "(%s, %s, %s, %s, %s, %s, %s, 0, 18000, 'hourly', 0, %s)",
            (
                uuid4(),
                company_id,
                station_id,
                shift_id,
                user_id,
                now,
                end_at,
                status,
            ),
        )

    payload = b"{}"
    connection.execute(
        "INSERT INTO google_sheets_deliveries ("
        "id, company_id, configuration_id, event_id, event_key, event_type, "
        "source_type, source_id, source_revision, schema_version, payload, "
        "payload_sha256, occurred_at, available_at) VALUES ("
        "%s, %s, %s, %s, %s, 'order.paid', 'order', %s, '1', 1, "
        "'{}'::jsonb, %s, %s, %s)",
        (
            uuid4(),
            company_id,
            uuid4(),
            uuid4(),
            f"order:{uuid4()}:1",
            str(uuid4()),
            hashlib.sha256(payload).hexdigest(),
            now,
            now,
        ),
    )


@pytest.mark.integration
def test_quiescence_query_executes_on_both_supported_legacy_heads() -> None:
    verifier = _load_verifier()
    with _disposable_database("erp_business_quiescence_legacy") as database_url:
        for revision in ("0058", "0060"):
            upgraded = _run_alembic(database_url, "upgrade", revision)
            assert upgraded.returncode == 0, upgraded.stdout + upgraded.stderr
            document = _query(database_url)
            assert document["google_sheets_pending_deliveries"] == 0
            assert document["google_sheets_leased_deliveries"] == 0
            assert document["google_sheets_quarantined_deliveries"] == 0
            verifier.verify_state(document)


@pytest.mark.integration
def test_fresh_post_stop_snapshot_detects_business_started_after_live_preflight() -> None:
    verifier = _load_verifier()
    with _disposable_database("erp_business_quiescence_race") as database_url:
        upgraded = _run_alembic(database_url, "upgrade", "head")
        assert upgraded.returncode == 0, upgraded.stdout + upgraded.stderr

        live_preflight = _query(database_url)
        verifier.verify_state(live_preflight)

        dsn = database_url.replace("postgresql+psycopg://", "postgresql://", 1)
        with psycopg.connect(dsn) as connection:
            _seed_racing_business_state(connection)
            connection.commit()

        post_stop = _query(database_url)
        assert post_stop["open_shifts"] == 1
        assert post_stop["open_or_held_orders"] == 1
        assert post_stop["active_or_paused_gaming_sessions"] == 1
        assert post_stop["ended_gaming_awaiting_pos_or_void"] == 1
        assert post_stop["google_sheets_pending_deliveries"] == 1
        with pytest.raises(
            verifier.QuiescenceError,
            match="production still has in-flight business work",
        ):
            verifier.verify_state(post_stop)
