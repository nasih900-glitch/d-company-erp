"""Populated immutable0102 GRNs retain physical/AP history and bounded legacy provenance."""

from __future__ import annotations

import asyncio
import os
import sys
from pathlib import Path

import psycopg
import pytest
from psycopg import sql

from tests.integration.test_cafe_workflow_migration import _disposable_database
from tests.integration.test_grn_command_races import old_backend as old_backend_fixture

old_backend = old_backend_fixture
pytestmark = [pytest.mark.asyncio, pytest.mark.integration]
BACKEND = Path(__file__).resolve().parents[2]
TABLES = (
    "companies",
    "branches",
    "terminals",
    "users",
    "purchase_orders",
    "purchase_order_lines",
    "grns",
    "grn_lines",
    "ingredients",
    "batches",
    "stock_movements",
    "orders",
    "order_lines",
    "payments",
    "refunds",
    "supplier_payments",
    "journal_entries",
    "journal_lines",
    "idempotency_keys",
)
ZERO_FIXTURE = """
from uuid import uuid4
import pytest
from sqlalchemy import text
from app.models import Supplier,Ingredient
@pytest.mark.asyncio
async def test_legacy_zero_provenance(client,session,seed_owner):
 s=seed_owner
 supplier=Supplier(id=uuid4(),company_id=s['company'].id,name='Zero legacy source')
 ing=Ingredient(id=uuid4(),company_id=s['company'].id,sku='Z-'+uuid4().hex,
  name='Zero legacy ingredient',base_unit='unit',current_qty=0,avg_cost_minor=0,
  reorder_threshold=0,reorder_qty=0)
 session.add_all([supplier,ing]);await session.commit()
 login=await client.post('/api/v1/auth/login',json={
  'email':s['owner'].email,'password':s['password']})
 assert login.status_code==200
 headers={'Authorization':'Bearer '+login.json()['access_token'],
  'X-Terminal-Id':str(s['terminal'].id)}
 for mode in ('unknown','keyless'):
  key='grn-legacy-'+uuid4().hex
  response=await client.post('/api/v1/inventory/grn',headers={**headers,'Idempotency-Key':key},
   json={'branch_id':str(s['branch'].id),'supplier_id':str(supplier.id),
   'lines':[{'ingredient_id':str(ing.id),'qty':0.25,'unit_cost_minor':0}]})
  assert response.status_code==201
  gid=response.json()['id']
  # Legal0102 missing-provenance/manual context, never a new economic receipt or trigger bypass.
  if mode=='unknown':
   await session.execute(text('UPDATE grns SET received_by=NULL WHERE id=:id'),{'id':gid})
   await session.execute(text('UPDATE stock_movements SET created_by=NULL WHERE ref_id=:id'),
    {'id':gid})
   await session.execute(text('UPDATE idempotency_keys SET user_id=NULL,terminal_id=NULL '
    'WHERE key=:key'),{'key':key})
  else:
   await session.execute(text('UPDATE grns SET idempotency_key=NULL,request_hash=NULL '
    'WHERE id=:id'),{'id':gid})
   await session.execute(text('DELETE FROM idempotency_keys WHERE key=:key'),{'key':key})
  await session.commit()
"""


def connect(database_url):
    return psycopg.connect(database_url.replace("postgresql+psycopg://", "postgresql://", 1))


def snapshot(database_url):
    with connect(database_url) as db:
        return {
            table: db.execute(
                sql.SQL(
                    "SELECT count(*),md5(coalesce(jsonb_agg(to_jsonb(t) "
                    "ORDER BY to_jsonb(t)::text)::text,'[]')) "
                    "FROM {} t"
                ).format(sql.Identifier(table))
            ).fetchone()
            for table in TABLES
        }


def audit_fingerprints(database_url):
    with connect(database_url) as db:
        return dict(db.execute("SELECT id,md5(to_jsonb(t)::text) FROM audit_log t").fetchall())


def old_definitions(database_url):
    with connect(database_url) as db:
        return {
            "functions": dict(
                db.execute("""SELECT p.oid::regprocedure::text,
                pg_get_functiondef(p.oid) FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace
                WHERE n.nspname='public' AND p.prokind='f'""").fetchall()
            ),
            "triggers": dict(
                db.execute("""SELECT c.relname||'.'||t.tgname,pg_get_triggerdef(t.oid)
                FROM pg_trigger t JOIN pg_class c ON c.oid=t.tgrelid
                JOIN pg_namespace n ON n.oid=c.relnamespace
                WHERE n.nspname='public' AND NOT t.tgisinternal""").fetchall()
            ),
        }


async def run(database_url, backend, *args):
    dependencies = [
        p for p in os.environ.get("PYTHONPATH", "").split(os.pathsep) if p and Path(p) != BACKEND
    ]
    env = {
        **os.environ,
        "DATABASE_URL": database_url,
        "ENV": "test",
        "PYTHONPATH": os.pathsep.join([str(backend), *dependencies]),
    }
    process = await asyncio.create_subprocess_exec(
        sys.executable,
        *args,
        cwd=backend,
        env=env,
        stdout=asyncio.subprocess.PIPE,
        stderr=asyncio.subprocess.PIPE,
    )
    try:
        stdout, stderr = await asyncio.wait_for(process.communicate(), 180)
    finally:
        if process.returncode is None:
            process.terminate()
            await process.wait()
    # Do not print nested exception SQL/parameters or private database URLs on failure.
    return process.returncode, stdout.decode() + stderr.decode()


async def test_populated_0102_backfill_preserves_economics_and_unknown_provenance(old_backend):
    with _disposable_database("erp_grn_recovery_20261113_rehearsal") as database_url:
        with connect(database_url) as db:
            db.execute(
                "COMMENT ON DATABASE "
                + sql.Identifier(db.info.dbname).as_string(db)
                + " IS 'erp.qa.owner=grn-durable-recovery-20261113'"
            )
        assert (await run(database_url, BACKEND, "-m", "alembic", "upgrade", "0102"))[0] == 0
        original = old_definitions(database_url)
        # The empty-only downgrade path must really execute before populated history exists.
        for action, revision in (("upgrade", "0103"), ("downgrade", "0102")):
            assert (await run(database_url, BACKEND, "-m", "alembic", action, revision))[0] == 0
        assert old_definitions(database_url) == original
        normal = await run(
            database_url,
            old_backend,
            "-m",
            "pytest",
            "tests/integration/test_inventory_purchase_accounting.py",
            "-q",
            "--no-cov",
        )
        assert normal[0] == 0
        assert "1 passed" in normal[1]
        # Add two ordinary old HTTP zero receipts, then explicit legal legacy provenance setup.
        (old_backend / "tests/integration/test_grn_migration_zero_fixture.py").write_text(
            ZERO_FIXTURE
        )
        zero = await run(
            database_url,
            old_backend,
            "-m",
            "pytest",
            "tests/integration/test_grn_migration_zero_fixture.py",
            "-q",
            "--no-cov",
        )
        assert zero[0] == 0
        assert "1 passed" in zero[1]
        with connect(database_url) as db:
            assert db.execute("SELECT count(*) FROM supplier_payments").fetchone()[0] > 0
            assert (
                db.execute("SELECT count(*) FROM batches WHERE qty_on_hand<qty_initial").fetchone()[
                    0
                ]
                > 0
            )
            # Current status is configuration, not a requirement for immutable historical backfill.
            db.execute(
                "UPDATE users SET status='suspended' WHERE id IN "
                "(SELECT received_by FROM grns WHERE received_by IS NOT NULL)"
            )
            keyed = db.execute(
                "SELECT count(*) FROM grns WHERE idempotency_key IS NOT NULL"
            ).fetchone()[0]
        before = snapshot(database_url)
        old_audits = audit_fingerprints(database_url)
        result = await run(database_url, BACKEND, "-m", "alembic", "upgrade", "0103")
        assert result[0] == 0
        assert snapshot(database_url) == before
        after_audits = audit_fingerprints(database_url)
        assert all(after_audits.get(key) == value for key, value in old_audits.items())
        assert len(after_audits) - len(old_audits) == keyed
        definitions = old_definitions(database_url)
        for kind, entries in original.items():
            assert all(
                definitions[kind].get(name) == definition for name, definition in entries.items()
            )
        with connect(database_url) as db:
            new_audits = db.execute(
                'SELECT action,entity_type,entity_id,"before","after" '
                "FROM audit_log WHERE NOT(id=ANY(%s))",
                (list(old_audits),),
            ).fetchall()
            proof_ids = {str(row[0]) for row in db.execute("SELECT id FROM grn_completion_proofs")}
            assert len(new_audits) == keyed
            for action, entity_type, entity_id, previous, following in new_audits:
                assert action == "grn_command_accepted"
                assert entity_type == "GrnCompletionProof"
                assert entity_id in proof_ids
                assert previous is None
                assert following == {"purpose": "inventory_grn_receive"}
            assert db.execute("SELECT count(*) FROM grn_action_decisions").fetchone() == (keyed,)
            assert db.execute("SELECT count(*) FROM grn_completion_proofs").fetchone() == (keyed,)
            assert db.execute(
                "SELECT count(*) FROM grn_completion_proofs WHERE profile<>'legacy' "
                "OR original_wire_json IS NOT NULL"
            ).fetchone() == (0,)
            assert db.execute(
                "SELECT count(*) FROM grn_action_decisions WHERE actor_user_id IS NULL "
                "AND NOT terminal_known AND terminal_id IS NULL"
            ).fetchone() == (1,)
            assert (
                db.execute(
                    "SELECT count(*) FROM grn_completion_proofs WHERE NOT terminal_known "
                    "AND terminal_id IS NULL"
                ).fetchone()[0]
                >= 1
            )
            assert db.execute(
                "SELECT count(*) FROM grns WHERE idempotency_key IS NULL"
            ).fetchone() == (1,)
        refused = await run(database_url, BACKEND, "-m", "alembic", "downgrade", "0102")
        assert refused[0] != 0
        assert "GRN command evidence prevents downgrade" in refused[1]
        assert snapshot(database_url) == before
        assert audit_fingerprints(database_url) == after_audits
        with connect(database_url) as db:
            assert db.execute("SELECT version_num FROM alembic_version").fetchone() == ("0103",)


async def test_upgrade_function_refuses_0102_missing_zero_cohort_and_rolls_back_fault(old_backend):
    """Fault injection is one rollback-only transaction, never retained business evidence."""
    import runpy

    from alembic.migration import MigrationContext
    from alembic.operations import Operations
    from sqlalchemy import create_engine, text
    from sqlalchemy.exc import DBAPIError

    from tests.integration.test_build71_candidate_migration import _inventory

    with _disposable_database("erp_grn_recovery_20261113_invalid_cohort") as database_url:
        assert (await run(database_url, BACKEND, "-m", "alembic", "upgrade", "0102"))[0] == 0
        (old_backend / "tests/integration/test_grn_migration_zero_fixture.py").write_text(
            ZERO_FIXTURE
        )
        seeded = await run(
            database_url,
            old_backend,
            "-m",
            "pytest",
            "tests/integration/test_grn_migration_zero_fixture.py",
            "-q",
            "--no-cov",
        )
        assert seeded[0] == 0
        assert "1 passed" in seeded[1]
        before = _inventory(database_url)
        source = snapshot(database_url)
        audits = audit_fingerprints(database_url)
        upgrade = runpy.run_path(str(BACKEND / "alembic/versions/0103_grn_durable_commands.py"))[
            "upgrade"
        ]
        engine = create_engine(database_url)
        code, intended_guard = None, False
        try:
            with engine.begin() as connection:
                # 0102 permits this zero/unposted source fault. Do not commit or disable triggers.
                changed = connection.execute(
                    text(
                        "DELETE FROM grn_lines WHERE grn_id IN "
                        "(SELECT id FROM grns WHERE idempotency_key IS NOT NULL)"
                    )
                )
                assert changed.rowcount == 1
                connection.execute(text("SET CONSTRAINTS ALL IMMEDIATE"))
                with Operations.context(MigrationContext.configure(connection)):
                    upgrade()
        except DBAPIError as failure:
            code = failure.orig.sqlstate
            intended_guard = failure.orig.diag.message_primary == (
                "GRN completion requires nonempty exact line total"
            )
        finally:
            engine.dispose()
        assert code == "23514"
        assert intended_guard
        assert snapshot(database_url) == source
        assert audit_fingerprints(database_url) == audits
        assert _inventory(database_url) == before
        with connect(database_url) as db:
            assert db.execute("SELECT version_num FROM alembic_version").fetchone() == ("0102",)
            assert db.execute(
                "SELECT to_regclass('public.grn_action_decisions'),"
                "to_regclass('public.grn_completion_proofs'),"
                "to_regclass('public.grn_recovery_sessions')"
            ).fetchone() == (None, None, None)
