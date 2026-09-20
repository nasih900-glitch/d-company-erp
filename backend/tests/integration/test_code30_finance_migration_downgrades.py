"""Downgrade safety contracts for Code30 finance evidence migrations."""

from __future__ import annotations

import hashlib
from datetime import UTC, datetime
from uuid import uuid4

import psycopg
import pytest

from tests.integration.test_cafe_workflow_migration import (
    _disposable_database,
    _run_alembic,
    _seed_0036_cafe_scope,
)


def _upgrade(url: str, revision: str) -> None:
    result = _run_alembic(url, "upgrade", revision)
    assert result.returncode == 0, result.stdout + result.stderr


def _seed_finance_scope(url: str, target_revision: str) -> tuple[str, dict[str, object]]:
    _upgrade(url, "0036")
    dsn = url.replace("postgresql+psycopg://", "postgresql://", 1)
    with psycopg.connect(dsn) as conn:
        ids = _seed_0036_cafe_scope(conn)
        # This forward cafe fixture is intentionally data-bearing. Remove the
        # second order so later migration guards do not confuse this test's
        # narrowly scoped downgrade evidence.
        conn.execute("DELETE FROM orders WHERE id=%s", (ids["order_two"],))
        conn.commit()
    _upgrade(url, target_revision)
    return dsn, ids


@pytest.mark.integration
@pytest.mark.parametrize(
    ("revision", "previous_revision"),
    [("0074", "0073"), ("0075", "0074"), ("0076", "0075")],
)
def test_code30_finance_migrations_round_trip_before_use(
    revision: str,
    previous_revision: str,
) -> None:
    with _disposable_database(f"erp_{revision}_clean_downgrade") as url:
        _upgrade(url, revision)
        downgraded = _run_alembic(url, "downgrade", previous_revision)
        assert downgraded.returncode == 0, downgraded.stdout + downgraded.stderr
        _upgrade(url, revision)


@pytest.mark.integration
@pytest.mark.parametrize("evidence_kind", ["receipt", "review"])
def test_0074_refuses_to_drop_expense_receipt_or_review_evidence(
    evidence_kind: str,
) -> None:
    with _disposable_database(f"erp_0074_{evidence_kind}_guard") as url:
        dsn, ids = _seed_finance_scope(url, "0074")
        category_id = uuid4()
        expense_id = uuid4()
        evidence_id = uuid4()
        with psycopg.connect(dsn) as conn:
            conn.execute(
                "INSERT INTO expense_categories(id,company_id,name,code) "
                "VALUES(%s,%s,'Receipt downgrade guard',%s)",
                (category_id, ids["company"], f"R74{uuid4().hex[:5]}"),
            )
            conn.execute(
                "INSERT INTO expenses("
                "id,company_id,branch_id,category_id,amount_minor,paid_via,paid_at,"
                "source_integrity_revision) "
                "VALUES(%s,%s,%s,%s,1250,'upi',%s,50)",
                (
                    expense_id,
                    ids["company"],
                    ids["branch"],
                    category_id,
                    datetime.now(UTC),
                ),
            )
            if evidence_kind == "receipt":
                payload = b"0074 downgrade guard receipt"
                conn.execute(
                    "INSERT INTO expense_receipts("
                    "id,company_id,expense_id,uploader_user_id,original_filename,"
                    "content_type,size_bytes,sha256,source,payload) "
                    "VALUES(%s,%s,%s,%s,'guard.jpg','image/jpeg',%s,%s,'file',%s)",
                    (
                        evidence_id,
                        ids["company"],
                        expense_id,
                        ids["user"],
                        len(payload),
                        hashlib.sha256(payload).hexdigest(),
                        payload,
                    ),
                )
            else:
                conn.execute(
                    "INSERT INTO expense_receipt_reviews("
                    "id,company_id,expense_id,status,reviewed_by) "
                    "VALUES(%s,%s,%s,'pending',%s)",
                    (evidence_id, ids["company"], expense_id, ids["user"]),
                )
            conn.commit()

        blocked = _run_alembic(url, "downgrade", "0073")
        output = blocked.stdout + blocked.stderr
        assert blocked.returncode != 0
        assert (
            "0074 downgrade refused: expense receipt evidence or review history exists"
            in output
        )
        with psycopg.connect(dsn) as conn:
            assert conn.execute(
                "SELECT version_num FROM alembic_version"
            ).fetchone() == ("0074",)
            table = (
                "expense_receipts"
                if evidence_kind == "receipt"
                else "expense_receipt_reviews"
            )
            assert conn.execute(
                f"SELECT id FROM {table} WHERE id=%s",  # noqa: S608 - fixed table names
                (evidence_id,),
            ).fetchone() == (evidence_id,)


@pytest.mark.integration
def test_0075_refuses_to_drop_google_sheets_delivery_evidence() -> None:
    with _disposable_database("erp_0075_delivery_guard") as url:
        _upgrade(url, "0075")
        dsn = url.replace("postgresql+psycopg://", "postgresql://", 1)
        company_id = uuid4()
        delivery_id = uuid4()
        with psycopg.connect(dsn) as conn:
            conn.execute(
                "INSERT INTO companies(id,name,gst_registration_type) "
                "VALUES(%s,'0075 delivery guard','unregistered')",
                (company_id,),
            )
            conn.execute(
                "INSERT INTO google_sheets_deliveries("
                "id,company_id,configuration_id,event_id,event_key,event_type,source_type,source_id,"
                "source_revision,schema_version,payload,payload_sha256,occurred_at,"
                "available_at) VALUES("
                "%s,%s,%s,%s,%s,'expense.created','expense',%s,'1',1,'{}'::jsonb,%s,"
                "%s,%s)",
                (
                    delivery_id,
                    company_id,
                    uuid4(),
                    uuid4(),
                    f"expense:{uuid4()}:1",
                    str(uuid4()),
                    hashlib.sha256(b"{}").hexdigest(),
                    datetime.now(UTC),
                    datetime.now(UTC),
                ),
            )
            conn.commit()

        blocked = _run_alembic(url, "downgrade", "0074")
        output = blocked.stdout + blocked.stderr
        assert blocked.returncode != 0
        assert "0075 downgrade refused: Google Sheets delivery evidence exists" in output
        with psycopg.connect(dsn) as conn:
            assert conn.execute(
                "SELECT version_num FROM alembic_version"
            ).fetchone() == ("0075",)
            assert conn.execute(
                "SELECT id FROM google_sheets_deliveries WHERE id=%s",
                (delivery_id,),
            ).fetchone() == (delivery_id,)


@pytest.mark.integration
def test_0076_requires_mirror_disable_before_downgrade() -> None:
    with _disposable_database("erp_0076_active_mirror_guard") as url:
        _upgrade(url, "0076")
        dsn = url.replace("postgresql+psycopg://", "postgresql://", 1)
        company_id = uuid4()
        with psycopg.connect(dsn) as conn:
            conn.execute(
                "INSERT INTO companies("
                "id,name,gst_registration_type,google_sheets_webhook_url,"
                "google_sheets_mirror_enabled,google_sheets_signing_secret_ciphertext,"
                "google_sheets_configured_at,google_sheets_configuration_id) "
                "VALUES(%s,'0076 active mirror guard','unregistered',"
                "'https://script.google.com/macros/s/test/exec',true,'ciphertext',now(),%s)",
                (company_id, uuid4()),
            )
            conn.commit()

        blocked = _run_alembic(url, "downgrade", "0075")
        output = blocked.stdout + blocked.stderr
        assert blocked.returncode != 0
        assert "0076 downgrade refused: disable every Google Sheets mirror first" in output
        with psycopg.connect(dsn) as conn:
            assert conn.execute(
                "SELECT version_num FROM alembic_version"
            ).fetchone() == ("0076",)
            assert conn.execute(
                "SELECT google_sheets_mirror_enabled FROM companies WHERE id=%s",
                (company_id,),
            ).fetchone() == (True,)
            conn.execute(
                "UPDATE companies SET google_sheets_mirror_enabled=false WHERE id=%s",
                (company_id,),
            )
            conn.commit()

        downgraded = _run_alembic(url, "downgrade", "0075")
        assert downgraded.returncode == 0, downgraded.stdout + downgraded.stderr
        with psycopg.connect(dsn) as conn:
            assert conn.execute(
                "SELECT version_num FROM alembic_version"
            ).fetchone() == ("0075",)
