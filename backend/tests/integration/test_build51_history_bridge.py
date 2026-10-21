"""Actual PostgreSQL projection/guard evidence for Build51, synthetic history only."""

from __future__ import annotations

import importlib.util
from datetime import UTC, datetime
from pathlib import Path
from uuid import uuid4

import psycopg
import pytest
import pytest_asyncio
from psycopg.types.json import Jsonb

from tests.integration.test_cafe_workflow_migration import _disposable_database, _run_alembic

ROOT = Path(__file__).resolve().parents[3]
COMPANY = "8f323fba-4358-45fe-9d3b-a8e0fae52993"
FIELDS = [
    "action_code",
    "category",
    "stage",
    "outcome",
    "operation_id",
    "source_reference",
    "actor_kind",
    "target_user_id",
    "business_details",
    "catalogue_version",
]


def verifier():
    spec = importlib.util.spec_from_file_location(
        "build51_history_sql_verifier",
        ROOT / "infra/scripts/verify-build51-post-migration-state.py",
    )
    assert spec
    assert spec.loader
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def statement(name):
    source = (ROOT / "infra/scripts" / name).read_text()
    start = source.index("WITH receipt_candidates AS")
    return source[start : source.index("\n;", start)]


def read(connection, new=True):
    connection.execute("SET LOCAL TIME ZONE 'UTC'")
    connection.execute("SET LOCAL search_path=pg_catalog,public")
    return connection.execute(
        statement(
            "verify-build51-post-migration-state.sql"
            if new
            else "verify-code30-2-post-cleanup-state.sql"
        )
    ).fetchone()[0]


@pytest.fixture(scope="module")
def bridge_db():
    with _disposable_database("erp_build51_history_projection") as url:
        result = _run_alembic(url, "upgrade", "0089")
        assert result.returncode == 0, result.stdout + result.stderr
        dsn = url.replace("postgresql+psycopg://", "postgresql://", 1)
        user = uuid4()
        with psycopg.connect(dsn) as c:
            assert c.execute("SELECT current_user").fetchone() == ("erp",)
            c.execute(
                "INSERT INTO companies(id,name) VALUES(%s,%s)",
                (COMPANY, "Synthetic bridge history"),
            )
            c.execute(
                (
                    "INSERT INTO users(id,company_id,email,name,password_hash,status) "
                    "VALUES(%s,%s,'bridge@synthetic.invalid','Bridge','unused','active')"
                ),
                (user, COMPANY),
            )
            c.execute(
                (
                    "INSERT INTO "
                    "audit_log(id,company_id,action,entity_type,entity_id,created_at) "
                    "VALUES(2000,%s,'legacy','Synthetic','baseline',%s)"
                ),
                (COMPANY, datetime(2026, 9, 19, tzinfo=UTC)),
            )
            c.execute(
                (
                    "INSERT INTO "
                    "audit_log(id,company_id,actor_user_id,action,entity_type,entity_id,before,after,"
                    "ip,user_agent,request_id,client_platform,client_was_offline,created_at) "
                    "VALUES(28203,%s,%s,'login_success','User',%s,%s,%s,'192.0.2.1','synthetic','old-login','web',false,%s)"
                ),
                (
                    COMPANY,
                    user,
                    str(user),
                    Jsonb(None),
                    Jsonb(
                        {
                            "email": "bridge@synthetic.invalid",
                            "name": "Bridge",
                            "roles": ["owner"],
                            "result": "login_success",
                        }
                    ),
                    datetime(2026, 9, 20, tzinfo=UTC),
                ),
            )
            c.execute(
                (
                    "INSERT INTO "
                    "audit_log(id,company_id,action,entity_type,entity_id,created_at) "
                    "VALUES(28204,%s,'production_trial_cleanup','ReleaseCleanup','code30.1-20260920',%s)"
                ),
                (COMPANY, datetime(2026, 9, 21, tzinfo=UTC)),
            )
            before = read(c, new=False)
            c.commit()
        result = _run_alembic(url, "upgrade", "0091")
        assert result.returncode == 0, result.stdout + result.stderr
        yield dsn, before, user


def test_old_hash_changes_but_new_projection_restores_both_exact_hashes(bridge_db):
    dsn, before, _ = bridge_db
    with psycopg.connect(dsn) as c:
        old = read(c, new=False)
        new = read(c)
        for key in ["retained_baseline_sha256", "pre_receipt_login_success_sha256"]:
            assert old["audit_integrity"][key] != before["audit_integrity"][key]
            assert new["historical_state"]["audit_integrity"][key] == before["audit_integrity"][key]
        assert new["historical_state"]["cleanup_receipts"] == before["cleanup_receipts"]
        assert new["guard_inventory"] == verifier().EXPECTED_GUARD_INVENTORY
        assert all(
            new["audit_projection"][key] == 0
            for key in [
                "retained_baseline_semantic_count",
                "pre_receipt_semantic_count",
                "cleanup_receipt_semantic_count",
            ]
        )


@pytest.mark.parametrize(
    ("row_id", "count_key"),
    [
        (2000, "retained_baseline_semantic_count"),
        (28203, "pre_receipt_semantic_count"),
        (28204, "cleanup_receipt_semantic_count"),
    ],
)
@pytest.mark.parametrize("field", FIELDS)
def test_every_sql_nonnull_semantic_fact_refuses_even_when_projected_hash_is_unchanged(
    bridge_db, row_id, count_key, field
):
    dsn, before, user = bridge_db
    value = (
        Jsonb(None)
        if field == "business_details"
        else (0 if field == "catalogue_version" else (user if field == "target_user_id" else ""))
    )
    with psycopg.connect(dsn) as c:
        # Simulate a corrupted historical row without disabling evidence checks
        # in the reader. Restore the original guard BEFORE reading its inventory.
        c.execute("ALTER TABLE audit_log DISABLE TRIGGER audit_log_append_only")
        c.execute(
            psycopg.sql.SQL("UPDATE audit_log SET {}=%s WHERE id=%s").format(
                psycopg.sql.Identifier(field)
            ),
            (value, row_id),
        )
        c.execute("ALTER TABLE audit_log ENABLE TRIGGER audit_log_append_only")
        result = read(c)
        assert result["audit_projection"][count_key] == 1
        assert (
            result["historical_state"]["audit_integrity"]["retained_baseline_sha256"]
            == before["audit_integrity"]["retained_baseline_sha256"]
        )
        with pytest.raises(ValueError, match="semantic evidence"):
            verifier().verify_envelope(result)
        c.rollback()


@pytest.mark.parametrize(
    "change",
    [
        "ALTER TABLE payments DISABLE TRIGGER zz_collection_fence",
        "ALTER TABLE payments ENABLE REPLICA TRIGGER zz_collection_fence",
        "ALTER TABLE payments ENABLE ALWAYS TRIGGER zz_collection_fence",
        "DROP TRIGGER zz_collection_fence ON membership_refunds",
        (
            "CREATE OR REPLACE FUNCTION public.dcompany_collection_touch(p_company "
            "uuid) RETURNS void LANGUAGE plpgsql AS $$ BEGIN RETURN; END $$"
        ),
        "ALTER FUNCTION public.dcompany_collection_lock(uuid) SECURITY DEFINER",
        "ALTER FUNCTION public.dcompany_collection_day_snapshot(uuid,uuid,date,text) VOLATILE",
        "ALTER FUNCTION public.dcompany_collection_fingerprint(uuid,uuid,date,text) RESET timezone",
        "ALTER FUNCTION public.dcompany_collection_fence() SET search_path=public",
        "ALTER FUNCTION public.dcompany_collection_company(jsonb) STRICT",
    ],
)
def test_actual_guard_tampering_is_rejected_not_just_counted(bridge_db, change):
    dsn, _, _ = bridge_db
    with psycopg.connect(dsn) as c:
        c.execute(change)
        with pytest.raises(ValueError, match="guard identity"):
            verifier().verify_envelope(read(c))
        c.rollback()


def test_original_field_stays_in_digest_and_post_receipt_semantics_are_allowed(bridge_db):
    dsn, before, _ = bridge_db
    with psycopg.connect(dsn) as c:
        c.execute("ALTER TABLE audit_log DISABLE TRIGGER audit_log_append_only")
        c.execute("UPDATE audit_log SET entity_id='changed-original-history' WHERE id=2000")
        c.execute("ALTER TABLE audit_log ENABLE TRIGGER audit_log_append_only")
        assert (
            read(c)["historical_state"]["audit_integrity"]["retained_baseline_sha256"]
            != before["audit_integrity"]["retained_baseline_sha256"]
        )
        c.rollback()
        c.execute(
            (
                "INSERT INTO "
                "audit_log(id,company_id,action,entity_type,entity_id,category,business_details) "
                "VALUES(28205,%s,'current','Synthetic','new','finance','{}'::jsonb)"
            ),
            (COMPANY,),
        )
        value = read(c)
        verifier().verify_envelope(value)
        assert value["historical_state"]["audit_integrity"] == before["audit_integrity"]
        c.rollback()


def test_fresh_birth_next_retry_and_verified_logical_restore_keep_working(tmp_path):
    import shutil
    import subprocess

    from sqlalchemy.engine import make_url

    pg_dump = shutil.which("pg_dump")
    pg_restore = shutil.which("pg_restore")
    assert pg_dump, "pg_dump is required for the actual restore proof"
    assert pg_restore, "pg_restore is required for the actual restore proof"
    birth_dump = tmp_path / "birth-only.dump"
    with _disposable_database("erp_build51_fresh_lineage") as url:
        result = _run_alembic(url, "upgrade", "0091")
        assert result.returncode == 0, result.stdout + result.stderr
        dsn = url.replace("postgresql+psycopg://", "postgresql://", 1)
        module = verifier()
        with psycopg.connect(dsn) as c:
            # This represents the successful seeded-owner login at first boot;
            # its actual semantic facts stay in the complete bootstrap prefix.
            c.execute(
                (
                    "INSERT INTO "
                    "audit_log(id,company_id,action,entity_type,entity_id,category,"
                    "actor_kind,business_details,catalogue_version) "
                    "VALUES(1,%s,'login_success','User',%s,'security','authenticated','{}'::jsonb,1)"
                ),
                (uuid4(), str(uuid4())),
            )
            c.execute("SET LOCAL dcompany.build51_fresh_lineage='true'")
            birth = read(c)
            origin = module.make_origin(
                birth,
                project="fresh-proof",
                source_git_sha="a" * 40,
                source_archive_sha256="b" * 64,
                admission_confirmed=True,
            )
            c.commit()
            result = subprocess.run(  # noqa: S603 - our local disposable DB only.
                [pg_dump, "--format=custom", "--dbname", dsn, "--file", str(birth_dump)],
                capture_output=True,
                text=True,
                check=False,
            )
            assert result.returncode == 0, result.stderr
            birth_dump.chmod(0o600)
            c.execute(
                (
                    "INSERT INTO "
                    "audit_log(id,company_id,action,entity_type,entity_id,category) "
                    "VALUES(2,%s,'legitimate_later_event','Synthetic','new','finance')"
                ),
                (uuid4(),),
            )
            c.execute("SET LOCAL dcompany.build51_fresh_lineage='true'")
            c.execute("SET LOCAL dcompany.build51_origin_cutoff='1'")
            before = read(c)
            module.verify_fresh_preservation(before, origin, project="fresh-proof")
            c.commit()
        dump = tmp_path / "fresh-origin-verified.dump"
        result = subprocess.run(  # noqa: S603 - argv uses local tools and our disposable DSN.
            [pg_dump, "--format=custom", "--dbname", dsn, "--file", str(dump)],
            capture_output=True,
            text=True,
            check=False,
        )
        assert result.returncode == 0, result.stderr
        dump.chmod(0o600)
        result = subprocess.run(  # noqa: S603 - argv uses local tools and our disposable DSN.
            [pg_restore, "--list", str(dump)], capture_output=True, text=True, check=False
        )
        assert result.returncode == 0, result.stderr
        parsed = make_url(url)
        admin_dsn = parsed.set(drivername="postgresql", database="postgres").render_as_string(
            hide_password=False
        )
        with psycopg.connect(admin_dsn, autocommit=True) as admin:
            admin.execute(
                psycopg.sql.SQL("DROP DATABASE {}").format(psycopg.sql.Identifier(parsed.database))
            )
            admin.execute(
                psycopg.sql.SQL("CREATE DATABASE {}").format(
                    psycopg.sql.Identifier(parsed.database)
                )
            )
        result = subprocess.run(  # noqa: S603 - argv uses local tools and our disposable DSN.
            [pg_restore, "--exit-on-error", "--no-owner", "--dbname", dsn, str(dump)],
            capture_output=True,
            text=True,
            check=False,
        )
        assert result.returncode == 0, result.stderr
        with psycopg.connect(dsn) as c:
            c.execute("SET LOCAL dcompany.build51_fresh_lineage='true'")
            c.execute("SET LOCAL dcompany.build51_origin_cutoff='1'")
            c.execute("SET LOCAL dcompany.build51_preserve_cutoff='2'")
            after = read(c)
            assert (
                after["database_identity"]["database_oid"]
                != before["database_identity"]["database_oid"]
            )
            module.verify_fresh_preservation(after, origin, project="fresh-proof", baseline=before)
            assert after["origin_prefix"] == birth["origin_prefix"]
            assert after["finance_history"] == before["finance_history"]

        # Restore the older dump from the same birth lineage: the origin still
        # matches, but the complete quiesced Audit prefix must reject it.
        with psycopg.connect(admin_dsn, autocommit=True) as admin:
            admin.execute(
                psycopg.sql.SQL("DROP DATABASE {}").format(psycopg.sql.Identifier(parsed.database))
            )
            admin.execute(
                psycopg.sql.SQL("CREATE DATABASE {}").format(
                    psycopg.sql.Identifier(parsed.database)
                )
            )
        result = subprocess.run(  # noqa: S603 - our earlier disposable dump only.
            [pg_restore, "--exit-on-error", "--no-owner", "--dbname", dsn, str(birth_dump)],
            capture_output=True,
            text=True,
            check=False,
        )
        assert result.returncode == 0, result.stderr
        with psycopg.connect(dsn) as c:
            c.execute("SET LOCAL dcompany.build51_fresh_lineage='true'")
            c.execute("SET LOCAL dcompany.build51_origin_cutoff='1'")
            c.execute("SET LOCAL dcompany.build51_preserve_cutoff='2'")
            older = read(c)
            assert older["origin_prefix"] == birth["origin_prefix"]
            with pytest.raises(ValueError, match="prefix exceeds|history changed"):
                module.verify_fresh_preservation(
                    older, origin, project="fresh-proof", baseline=before
                )


@pytest.mark.parametrize(
    "replacement",
    [
        (
            "CREATE TRIGGER zz_collection_fence BEFORE INSERT OR UPDATE OR DELETE "
            "ON payments FOR EACH ROW EXECUTE FUNCTION "
            "dcompany_collection_fence()"
        ),
        (
            "CREATE TRIGGER zz_collection_fence AFTER INSERT OR UPDATE OR DELETE "
            "ON payments FOR EACH STATEMENT EXECUTE FUNCTION "
            "dcompany_collection_fence()"
        ),
        (
            "CREATE TRIGGER zz_collection_fence AFTER INSERT OR UPDATE ON "
            "payments FOR EACH ROW EXECUTE FUNCTION dcompany_collection_fence()"
        ),
        (
            "CREATE TRIGGER zz_collection_fence AFTER INSERT OR UPDATE OR DELETE "
            "ON payments FOR EACH ROW WHEN (pg_trigger_depth()>=0) EXECUTE "
            "FUNCTION dcompany_collection_fence()"
        ),
        (
            "CREATE TRIGGER zz_collection_fence AFTER INSERT OR UPDATE OR DELETE "
            "ON payments FOR EACH ROW EXECUTE FUNCTION "
            "guard_audit_log_append_only()"
        ),
        (
            "CREATE TRIGGER zz_collection_fence AFTER INSERT OR UPDATE OR DELETE "
            "ON users FOR EACH ROW EXECUTE FUNCTION dcompany_collection_fence()"
        ),
    ],
)
def test_same_trigger_count_cannot_hide_wrong_timing_events_binding_or_table(
    bridge_db, replacement
):
    dsn, _, _ = bridge_db
    with psycopg.connect(dsn) as c:
        c.execute("DROP TRIGGER zz_collection_fence ON payments")
        c.execute(replacement)
        value = read(c)
        assert len(value["guard_inventory"]["triggers"]) == 34
        with pytest.raises(ValueError, match="guard identity"):
            verifier().verify_envelope(value)
        c.rollback()


def test_guard_owner_and_schema_are_bound_independently_of_function_body(bridge_db):
    dsn, _, _ = bridge_db
    with psycopg.connect(dsn) as c:
        role = "bridge_guard_" + uuid4().hex[:12]
        c.execute(psycopg.sql.SQL("CREATE ROLE {}").format(psycopg.sql.Identifier(role)))
        c.execute(
            psycopg.sql.SQL("ALTER FUNCTION dcompany_collection_lock(uuid) OWNER TO {}").format(
                psycopg.sql.Identifier(role)
            )
        )
        with pytest.raises(ValueError, match="guard identity"):
            verifier().verify_envelope(read(c))
        c.rollback()
        c.execute("CREATE SCHEMA bridge_guard_other")
        c.execute("ALTER FUNCTION dcompany_collection_fence() SET SCHEMA bridge_guard_other")
        with pytest.raises(ValueError, match="guard identity"):
            verifier().verify_envelope(read(c))
        c.rollback()


@pytest_asyncio.fixture
async def tender_history_0091(monkeypatch):
    """The frozen51 reader is exercised against its actual0091 schema."""
    from sqlalchemy.ext.asyncio import async_sessionmaker, create_async_engine

    from app.api.v1.public import router as public
    from app.core import db

    with _disposable_database("erp_build51_nonempty_tender") as url:
        migration = _run_alembic(url, "upgrade", "0091")
        assert migration.returncode == 0, migration.stderr
        engine = create_async_engine(
            url.replace("postgresql+psycopg://", "postgresql+asyncpg://", 1)
        )
        factory = async_sessionmaker(engine, expire_on_commit=False, autoflush=False)
        monkeypatch.setattr(db, "AsyncSessionLocal", factory)
        monkeypatch.setattr(public, "AsyncSessionLocal", factory)
        try:
            yield factory, url.replace("postgresql+psycopg://", "postgresql://", 1)
        finally:
            await engine.dispose()


@pytest_asyncio.fixture
async def session(tender_history_0091):
    factory, _dsn = tender_history_0091
    async with factory() as connection:
        yield connection
        await connection.rollback()


@pytest.mark.asyncio
async def test_current_legacy_history_preserves_real_nonempty_tender_ledgers(
    client, session, seed_owner, tender_history_0091
):
    """Valid public commands populate both ledgers with every guard enabled."""
    from tests.integration.test_tender_corrections import case, unlock

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
        headers={**protected, "Idempotency-Key": "bridge-payment:" + str(uuid4())},
        json={
            "payment_id": str(payment.id),
            "source_fingerprint": preview.json()["source_fingerprint"],
            "reason": "Synthetic owner confirms full Cash to UPI for history proof",
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
        headers={**protected, "Idempotency-Key": "bridge-daily:" + str(uuid4())},
        json={
            "branch_id": str(order.branch_id),
            "business_date": str(day),
            "source_fingerprint": preview.json()["source_fingerprint"],
            "actual_cash_minor": 1,
            "actual_upi_minor": 27000,
            "reason": "Synthetic unresolved actuals are retained without posting",
        },
    )
    assert actuals.status_code == 201, actuals.text
    assert actuals.json()["financial_effect"] is False
    _factory, dsn = tender_history_0091
    module = verifier()
    with psycopg.connect(dsn) as c:
        c.execute("SET LOCAL dcompany.build51_fresh_lineage='true'")
        before = read(c)
        module.verify_current_preservation(before)
        assert before["finance_history"]["payment_correction_count"] >= 1
        assert before["finance_history"]["daily_reconciliation_count"] >= 1
        c.rollback()
        for table, identity in [
            ("payment_tender_corrections", corrected.json()["id"]),
            ("daily_tender_reconciliations", actuals.json()["id"]),
        ]:
            # Model a wrong restore missing one retained receipt. Restore the
            # immutable guard before reading; exact inventory remains valid.
            c.execute(
                psycopg.sql.SQL("ALTER TABLE {} DISABLE TRIGGER immutable_tender_receipt").format(
                    psycopg.sql.Identifier(table)
                )
            )
            c.execute(
                psycopg.sql.SQL("DELETE FROM {} WHERE id=%s").format(psycopg.sql.Identifier(table)),
                (identity,),
            )
            c.execute(
                psycopg.sql.SQL("ALTER TABLE {} ENABLE TRIGGER immutable_tender_receipt").format(
                    psycopg.sql.Identifier(table)
                )
            )
            c.execute("SET LOCAL dcompany.build51_fresh_lineage='true'")
            cutoff = str(before["preserved_prefix"]["cutoff"])
            c.execute("SELECT set_config('dcompany.build51_origin_cutoff',%s,true)", (cutoff,))
            c.execute("SELECT set_config('dcompany.build51_preserve_cutoff',%s,true)", (cutoff,))
            after = read(c)
            assert after["guard_inventory"] == module.EXPECTED_GUARD_INVENTORY
            with pytest.raises(ValueError, match="finance_history"):
                module.verify_current_preservation(after, before)
            c.rollback()
        c.execute("SET LOCAL dcompany.build51_fresh_lineage='true'")
        assert read(c)["finance_history"] == before["finance_history"]
