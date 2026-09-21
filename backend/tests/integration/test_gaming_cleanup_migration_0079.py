"""Real PostgreSQL proof for 0079 supersession evidence immutability."""

from __future__ import annotations

from datetime import UTC, datetime
from typing import TYPE_CHECKING
from uuid import uuid4

import psycopg
import pytest
from psycopg import sql
from sqlalchemy import create_engine
from sqlalchemy.orm import Session

from app.models import (
    AuditLog,
    Branch,
    ClientGamingCleanupReconciliation,
    ClientInstallation,
    Company,
    Station,
    Terminal,
    User,
)
from tests.integration.test_cafe_workflow_migration import (
    _disposable_database,
    _run_alembic,
)

if TYPE_CHECKING:
    from collections.abc import Iterator


@pytest.fixture(scope="module")
def migrated_0079_database() -> Iterator[str]:
    with _disposable_database("erp_gaming_cleanup_0079") as database_url:
        predecessor = _run_alembic(database_url, "upgrade", "0078")
        assert predecessor.returncode == 0, predecessor.stdout + predecessor.stderr
        upgraded = _run_alembic(database_url, "upgrade", "0079")
        assert upgraded.returncode == 0, upgraded.stdout + upgraded.stderr
        yield database_url


def _seed_scope(database_url: str) -> dict[str, object]:
    engine = create_engine(database_url)
    now = datetime.now(UTC)
    with Session(engine) as session:
        company = Company(name=f"0079 trigger {uuid4()}")
        session.add(company)
        session.flush()
        branch = Branch(
            company_id=company.id,
            name="Main",
            code=f"B{uuid4().hex[:4]}",
            invoice_series_code=uuid4().hex[:2].upper(),
            state_code="32",
        )
        session.add(branch)
        session.flush()
        terminal = Terminal(
            branch_id=branch.id,
            name="0079 terminal",
            device_id=f"0079-{uuid4()}",
        )
        session.add(terminal)
        session.flush()
        approver = User(
            company_id=company.id,
            email=f"approver-{uuid4()}@test.local",
            password_hash="not-a-real-password-hash",
            name="Approver",
        )
        other_user = User(
            company_id=company.id,
            email=f"other-{uuid4()}@test.local",
            password_hash="not-a-real-password-hash",
            name="Other user",
        )
        session.add_all([approver, other_user])
        session.flush()
        station = Station(
            company_id=company.id,
            branch_id=branch.id,
            code=f"S{uuid4().hex[:6]}",
            name="0079 station",
            type="ps5",
            rate_per_hour_minor=15_000,
        )
        installation = ClientInstallation(
            company_id=company.id,
            installation_id=uuid4(),
            registered_by_user_id=approver.id,
            last_user_id=approver.id,
            terminal_id=terminal.id,
            platform="android",
            distribution_channel="direct",
            version_name="3.1.29",
            version_code=37,
            pending_outbox_count=0,
            last_successful_sync_at=now,
            update_state="idle",
            update_error_code=None,
            last_seen_at=now,
        )
        session.add_all([station, installation])
        session.flush()
        receipt = AuditLog(
            actor_user_id=approver.id,
            company_id=company.id,
            action="maintenance.production_trial_cleanup",
            entity_type="system",
            entity_id="production-trial-cleanup",
        )
        session.add(receipt)
        session.flush()

        def reconciliation(status: str) -> ClientGamingCleanupReconciliation:
            approved = status == "approved"
            return ClientGamingCleanupReconciliation(
                company_id=company.id,
                client_installation_id=installation.id,
                branch_id=branch.id,
                terminal_id=terminal.id,
                station_id=station.id,
                local_action_id=uuid4(),
                server_session_id=uuid4(),
                revision=1,
                reported_local_state="stop_pending",
                local_evidence_revision=7,
                local_snapshot={"evidence_revision": 7},
                local_snapshot_sha256="a" * 64,
                start_request_hash="b" * 64,
                stop_request_hash="c" * 64,
                original_action_user_id=approver.id,
                candidate_sha256="d" * 64,
                unresolved_child_count=0,
                cleanup_receipt_audit_id=receipt.id,
                status=status,
                reported_at=now,
                approved_at=now if approved else None,
                approved_by=approver.id if approved else None,
                approval_reason="Owner approved exact evidence" if approved else None,
                approval_idempotency_key="approval-key" if approved else None,
            )

        reported_rejected = reconciliation("reported")
        reported_allowed = reconciliation("reported")
        approved_rejected = [reconciliation("approved") for _ in range(4)]
        approved_allowed = reconciliation("approved")
        session.add_all(
            [
                reported_rejected,
                reported_allowed,
                *approved_rejected,
                approved_allowed,
            ]
        )
        session.commit()
        result: dict[str, object] = {
            "approver_id": approver.id,
            "other_user_id": other_user.id,
            "reported_rejected_id": reported_rejected.id,
            "reported_allowed_id": reported_allowed.id,
            "approved_rejected_ids": [row.id for row in approved_rejected],
            "approved_allowed_id": approved_allowed.id,
            "approved_at": now,
            "approval_tuple": (
                now,
                approver.id,
                "Owner approved exact evidence",
                "approval-key",
            ),
        }
    engine.dispose()
    return result


def _dsn(database_url: str) -> str:
    return database_url.replace("postgresql+psycopg://", "postgresql://", 1)


def _assert_trigger_rejection(
    connection: psycopg.Connection,
    statement: str | sql.Composed,
    parameters: tuple[object, ...],
) -> None:
    with pytest.raises(
        psycopg.errors.CheckViolation,
        match="Gaming cleanup approval evidence is immutable",
    ):
        connection.execute(statement, parameters)
    connection.rollback()


@pytest.mark.integration
def test_0079_trigger_rejects_fabricated_reported_approval(
    migrated_0079_database: str,
) -> None:
    ids = _seed_scope(migrated_0079_database)
    with psycopg.connect(_dsn(migrated_0079_database)) as connection:
        trigger = connection.execute(
            "SELECT tgname FROM pg_trigger "
            "WHERE tgrelid = 'client_gaming_cleanup_reconciliation'::regclass "
            "AND NOT tgisinternal"
        ).fetchall()
        assert trigger == [("trg_client_gaming_cleanup_reconciliation_guard",)]
        _assert_trigger_rejection(
            connection,
            "UPDATE client_gaming_cleanup_reconciliation SET "
            "status = 'superseded', superseded_at = now(), approved_at = now(), "
            "approved_by = %s, approval_reason = 'Fabricated approval', "
            "approval_idempotency_key = 'fabricated-key' WHERE id = %s",
            (ids["approver_id"], ids["reported_rejected_id"]),
        )
        assert connection.execute(
            "SELECT status, approved_at, approved_by, approval_reason, "
            "approval_idempotency_key FROM client_gaming_cleanup_reconciliation "
            "WHERE id = %s",
            (ids["reported_rejected_id"],),
        ).fetchone() == ("reported", None, None, None, None)


@pytest.mark.integration
def test_0079_trigger_rejects_every_approved_tuple_mutation(
    migrated_0079_database: str,
) -> None:
    ids = _seed_scope(migrated_0079_database)
    rejected_ids = ids["approved_rejected_ids"]
    assert isinstance(rejected_ids, list)
    mutations: list[tuple[str, tuple[object, ...]]] = [
        ("approved_at = approved_at + interval '1 second'", ()),
        ("approved_by = %s", (ids["other_user_id"],)),
        ("approval_reason = 'Tampered approval reason'", ()),
        ("approval_idempotency_key = 'tampered-approval-key'", ()),
    ]
    with psycopg.connect(_dsn(migrated_0079_database)) as connection:
        for row_id, (mutation, mutation_parameters) in zip(
            rejected_ids,
            mutations,
            strict=True,
        ):
            statement = sql.SQL(
                "UPDATE client_gaming_cleanup_reconciliation SET "
                "status = 'superseded', superseded_at = now(), {} WHERE id = %s"
            ).format(sql.SQL(mutation))
            _assert_trigger_rejection(
                connection,
                statement,
                (*mutation_parameters, row_id),
            )
        rows = connection.execute(
            "SELECT status, approved_at, approved_by, approval_reason, "
            "approval_idempotency_key FROM client_gaming_cleanup_reconciliation "
            "WHERE id = ANY(%s) ORDER BY id",
            (rejected_ids,),
        ).fetchall()
        assert len(rows) == 4
        assert all(row[0] == "approved" for row in rows)
        assert all(row[1:] == ids["approval_tuple"] for row in rows)


@pytest.mark.integration
def test_0079_trigger_allows_exact_reported_and_approved_supersession(
    migrated_0079_database: str,
) -> None:
    ids = _seed_scope(migrated_0079_database)
    with psycopg.connect(_dsn(migrated_0079_database)) as connection:
        connection.execute(
            "UPDATE client_gaming_cleanup_reconciliation "
            "SET status = 'superseded', superseded_at = now() WHERE id = %s",
            (ids["reported_allowed_id"],),
        )
        connection.execute(
            "UPDATE client_gaming_cleanup_reconciliation "
            "SET status = 'superseded', superseded_at = now() WHERE id = %s",
            (ids["approved_allowed_id"],),
        )
        connection.commit()
        reported = connection.execute(
            "SELECT status, approved_at, approved_by, approval_reason, "
            "approval_idempotency_key FROM client_gaming_cleanup_reconciliation "
            "WHERE id = %s",
            (ids["reported_allowed_id"],),
        ).fetchone()
        approved = connection.execute(
            "SELECT status, approved_at, approved_by, approval_reason, "
            "approval_idempotency_key FROM client_gaming_cleanup_reconciliation "
            "WHERE id = %s",
            (ids["approved_allowed_id"],),
        ).fetchone()
        assert reported == ("superseded", None, None, None, None)
        assert approved == ("superseded", *ids["approval_tuple"])
