"""Real0091→0092 full-row proof with nonempty immutable finance receipts."""

from __future__ import annotations

import importlib.util
import json
import shutil
import subprocess
from pathlib import Path
from uuid import uuid4

import pytest
import pytest_asyncio
from sqlalchemy.ext.asyncio import create_async_engine

from tests.integration import test_android_update_policy as policy_fixtures
from tests.integration.test_cafe_workflow_migration import _disposable_database, _run_alembic
from tests.integration.test_tender_corrections import case, unlock

owned_policy_database = policy_fixtures.owned_policy_database

ROOT = Path(__file__).resolve().parents[3]


def _tool(name):
    executable = shutil.which(name)
    assert executable is not None, f"PostgreSQL16 {name} is required"
    return executable


def _snapshot(dsn):
    result = subprocess.run(  # noqa: S603 - fixed local psql and owned disposable DB
        [
            _tool("psql"),
            "-X",
            "--no-psqlrc",
            "--quiet",
            "--tuples-only",
            "--no-align",
            dsn,
            "-f",
            str(ROOT / "infra/scripts/verify-build55-transition-state.sql"),
        ],
        capture_output=True,
        text=True,
        check=False,
    )
    assert result.returncode == 0, result.stderr
    return json.loads(result.stdout)


@pytest_asyncio.fixture(autouse=True)
async def stable_owned_schema(owned_policy_database, tmp_path):
    """Real schema-only roundtrip before any synthetic business rows exist.

    Fresh migration history can retain dropped-column ordinals and CHECK/index
    expression forms which PostgreSQL canonicalizes on its first restore. The
    baseline must be a real restored schema, not an edited inventory document.
    Copy the actual Alembic data separately so the restored revision is genuine.
    """
    original_engine = owned_policy_database.kw["bind"]
    url = original_engine.url.set(drivername="postgresql+psycopg").render_as_string(
        hide_password=False
    )
    dsn = url.replace("postgresql+psycopg://", "postgresql://", 1)
    migration = _run_alembic(url, "downgrade", "0091")
    assert migration.returncode == 0, migration.stderr
    schema = tmp_path / "owned-empty0091-schema.dump"
    revision = tmp_path / "owned-empty0091-alembic.dump"
    for destination, arguments in (
        (schema, ["--schema-only"]),
        (revision, ["--data-only", "--table=public.alembic_version"]),
    ):
        result = subprocess.run(  # noqa: S603 - fixed tool and own empty database
            [_tool("pg_dump"), "--format=custom", *arguments, "--file", str(destination), dsn],
            capture_output=True,
            text=True,
            check=False,
        )
        assert result.returncode == 0, result.stderr
        destination.chmod(0o600)
    with _disposable_database("erp_build55_stable_schema") as restored_url:
        restored_dsn = restored_url.replace("postgresql+psycopg://", "postgresql://", 1)
        for archive in (schema, revision):
            restored = subprocess.run(  # noqa: S603 - fixed tool and own empty database
                [_tool("pg_restore"), "--exit-on-error", "--dbname", restored_dsn, str(archive)],
                capture_output=True,
                text=True,
                check=False,
            )
            assert restored.returncode == 0, restored.stderr
        restored_snapshot = _snapshot(restored_dsn)
        assert restored_snapshot["database_revision"] == "0091"
        assert restored_snapshot["tables"]["alembic_version"]["row_count"] == 1
        for name in (
            "users",
            "payments",
            "payment_tender_corrections",
            "daily_tender_reconciliations",
        ):
            assert restored_snapshot["tables"][name]["row_count"] == 0
        engine = create_async_engine(
            restored_url.replace("postgresql+psycopg://", "postgresql+asyncpg://", 1)
        )
        owned_policy_database.configure(bind=engine)
        try:
            yield
        finally:
            await engine.dispose()
            owned_policy_database.configure(bind=original_engine)


@pytest_asyncio.fixture
async def session(owned_policy_database, stable_owned_schema):
    async with owned_policy_database() as connection:
        yield connection
        await connection.rollback()


@pytest.mark.asyncio
async def test_build55_retains_nonempty_finance_audit_and_exact_stopped_restore(
    client, session, seed_owner, owned_policy_database, tmp_path
):
    headers, day, order, payment, _ = await case(session, seed_owner)
    protected = await unlock(client, seed_owner, headers, "payment")
    preview = await client.post(
        "/api/v1/finance/tender-corrections/payment/prepare",
        headers=protected,
        json={"payment_id": str(payment.id)},
    )
    assert preview.status_code == 200, preview.text
    corrected = await client.post(
        "/api/v1/finance/tender-corrections/payment/commit",
        headers={**protected, "Idempotency-Key": "build55-payment:" + str(uuid4())},
        json={
            "payment_id": str(payment.id),
            "source_fingerprint": preview.json()["source_fingerprint"],
            "reason": "Synthetic owner confirms Cash to UPI; original payment remains",
        },
    )
    assert corrected.status_code == 201, corrected.text
    protected = await unlock(client, seed_owner, headers, "daily")
    preview = await client.post(
        "/api/v1/finance/tender-corrections/daily/prepare",
        headers=protected,
        json={"branch_id": str(order.branch_id), "business_date": str(day)},
    )
    assert preview.status_code == 200, preview.text
    actuals = await client.post(
        "/api/v1/finance/tender-corrections/daily/commit",
        headers={**protected, "Idempotency-Key": "build55-daily:" + str(uuid4())},
        json={
            "branch_id": str(order.branch_id),
            "business_date": str(day),
            "source_fingerprint": preview.json()["source_fingerprint"],
            "actual_cash_minor": 1,
            "actual_upi_minor": 27000,
            "reason": "Synthetic unresolved actuals are evidence, not financial posting",
        },
    )
    assert actuals.status_code == 201, actuals.text
    assert actuals.json()["financial_effect"] is False
    await session.rollback()
    engine = owned_policy_database.kw["bind"]
    url = engine.url.set(drivername="postgresql+psycopg").render_as_string(hide_password=False)
    dsn = url.replace("postgresql+psycopg://", "postgresql://", 1)
    migration = _run_alembic(url, "downgrade", "0091")
    assert migration.returncode == 0, migration.stderr
    before = _snapshot(dsn)
    before_receipt = tmp_path / "actual-before0091.json"
    before_receipt.write_text(json.dumps(before, sort_keys=True))
    before_receipt.chmod(0o600)
    assert before["database_revision"] == "0091"
    for name in (
        "payment_tender_corrections",
        "daily_tender_reconciliations",
        "payments",
        "audit_log",
    ):
        assert before["tables"][name]["row_count"] > 0
    dump = tmp_path / "owned-build55-before.dump"
    archived = subprocess.run(  # noqa: S603 - fixed installed tool, owned DB
        [_tool("pg_dump"), "--format=custom", "--file", str(dump), dsn],
        capture_output=True,
        text=True,
        check=False,
    )
    assert archived.returncode == 0, archived.stderr
    migration = _run_alembic(url, "upgrade", "0092")
    assert migration.returncode == 0, migration.stderr
    after = _snapshot(dsn)
    after_receipt = tmp_path / "actual-after0092.json"
    after_receipt.write_text(json.dumps(after, sort_keys=True))
    after_receipt.chmod(0o600)
    spec = importlib.util.spec_from_file_location(
        "build55_actual_transition", ROOT / "infra/scripts/verify-build55-pending-compatibility.py"
    )
    assert spec is not None
    assert spec.loader is not None
    verifier = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(verifier)
    proof = verifier.verify_pair(before, after, mode="post")
    assert proof["full_audit_finance_rows_preserved"] is True
    assert len(after["tables"]) == len(before["tables"]) + 1
    assert after["tables"]["android_update_policy"] == {
        "row_count": 0,
        "row_sha256": verifier.EMPTY_SHA256,
    }
    with _disposable_database("code17_restore_verify") as restored_url:
        restored_dsn = restored_url.replace("postgresql+psycopg://", "postgresql://", 1)
        result = subprocess.run(  # noqa: S603 - fixed installed tool, own empty DB
            [_tool("pg_restore"), "--exit-on-error", "--dbname", restored_dsn, str(dump)],
            capture_output=True,
            text=True,
            check=False,
        )
        assert result.returncode == 0, result.stderr
        restored = _snapshot(restored_dsn)
        restore_receipt = tmp_path / "actual-restored0091.json"
        restore_receipt.write_text(json.dumps(restored, sort_keys=True))
        restore_receipt.chmod(0o600)
        assert (
            verifier.verify_pair(before, restored, mode="restore")["logical_restore_verified"]
            is True
        )
        assert restored["tables"] == before["tables"]
        assert restored["sequences"] == before["sequences"]
