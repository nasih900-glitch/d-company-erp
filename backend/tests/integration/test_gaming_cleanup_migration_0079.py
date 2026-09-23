"""Real PostgreSQL proof for 0079 -> 0081 cleanup evidence evolution."""

from __future__ import annotations

from datetime import UTC, datetime
from typing import TYPE_CHECKING
from uuid import uuid4

import psycopg
import pytest
from psycopg import sql
from sqlalchemy import create_engine, text
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
def migrated_0081_database() -> Iterator[str]:
    with _disposable_database("erp_gaming_cleanup_0081") as database_url:
        predecessor = _run_alembic(database_url, "upgrade", "0078")
        assert predecessor.returncode == 0, predecessor.stdout + predecessor.stderr
        upgraded = _run_alembic(database_url, "upgrade", "0081")
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
                reported_app_version_name=installation.version_name,
                reported_app_version_code=installation.version_code,
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


@pytest.mark.integration
def test_committed_0079_upgrades_forward_to_0081_and_round_trips_when_unused() -> None:
    with _disposable_database("erp_gaming_cleanup_0079_to_0081") as database_url:
        old_head = _run_alembic(database_url, "upgrade", "0079")
        assert old_head.returncode == 0, old_head.stdout + old_head.stderr
        with psycopg.connect(_dsn(database_url)) as connection:
            old_columns = connection.execute(
                "SELECT column_name FROM information_schema.columns "
                "WHERE table_schema = current_schema() "
                "AND table_name = 'client_gaming_cleanup_reconciliation' "
                "AND column_name LIKE 'reported_app_version_%' ORDER BY column_name"
            ).fetchall()
            old_guard = connection.execute(
                "SELECT pg_get_functiondef("
                "'dcompany_guard_client_gaming_cleanup_reconciliation()'::regprocedure)"
            ).fetchone()
        assert old_columns == []
        assert old_guard is not None
        assert "reported_app_version_name" not in old_guard[0]

        upgraded = _run_alembic(database_url, "upgrade", "0081")
        assert upgraded.returncode == 0, upgraded.stdout + upgraded.stderr
        with psycopg.connect(_dsn(database_url)) as connection:
            new_columns = connection.execute(
                "SELECT column_name, is_nullable FROM information_schema.columns "
                "WHERE table_schema = current_schema() "
                "AND table_name = 'client_gaming_cleanup_reconciliation' "
                "AND column_name LIKE 'reported_app_version_%' ORDER BY column_name"
            ).fetchall()
            new_guard = connection.execute(
                "SELECT pg_get_functiondef("
                "'dcompany_guard_client_gaming_cleanup_reconciliation()'::regprocedure)"
            ).fetchone()
            constraint = connection.execute(
                "SELECT conname FROM pg_constraint "
                "WHERE conrelid = 'client_gaming_cleanup_reconciliation'::regclass "
                "AND conname = 'ck_client_gaming_cleanup_reported_app_version'"
            ).fetchall()
        assert new_columns == [
            ("reported_app_version_code", "NO"),
            ("reported_app_version_name", "NO"),
        ]
        assert new_guard is not None
        assert "reported_app_version_name" in new_guard[0]
        assert constraint == [("ck_client_gaming_cleanup_reported_app_version",)]

        downgraded = _run_alembic(database_url, "downgrade", "0080")
        assert downgraded.returncode == 0, downgraded.stdout + downgraded.stderr
        with psycopg.connect(_dsn(database_url)) as connection:
            round_trip_columns = connection.execute(
                "SELECT column_name FROM information_schema.columns "
                "WHERE table_schema = current_schema() "
                "AND table_name = 'client_gaming_cleanup_reconciliation' "
                "AND column_name LIKE 'reported_app_version_%'"
            ).fetchall()
        assert round_trip_columns == []
        reupgraded = _run_alembic(database_url, "upgrade", "0081")
        assert reupgraded.returncode == 0, reupgraded.stdout + reupgraded.stderr


@pytest.mark.integration
def test_0081_refuses_to_fabricate_app_identity_for_populated_legacy_ledger() -> None:
    with _disposable_database("erp_gaming_cleanup_0081_legacy_evidence") as database_url:
        old_head = _run_alembic(database_url, "upgrade", "0079")
        assert old_head.returncode == 0, old_head.stdout + old_head.stderr
        engine = create_engine(database_url)
        now = datetime.now(UTC)
        with Session(engine) as session:
            company = Company(name=f"0081 legacy {uuid4()}")
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
                name="0081 legacy terminal",
                device_id=f"0081-legacy-{uuid4()}",
            )
            user = User(
                company_id=company.id,
                email=f"legacy-{uuid4()}@test.local",
                password_hash="not-a-real-password-hash",
                name="Legacy reporter",
            )
            station = Station(
                company_id=company.id,
                branch_id=branch.id,
                code=f"S{uuid4().hex[:6]}",
                name="0081 legacy station",
                type="ps5",
                rate_per_hour_minor=15_000,
            )
            session.add_all([terminal, user, station])
            session.flush()
            installation = ClientInstallation(
                company_id=company.id,
                installation_id=uuid4(),
                registered_by_user_id=user.id,
                last_user_id=user.id,
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
            session.add(installation)
            session.flush()
            receipt = AuditLog(
                actor_user_id=user.id,
                company_id=company.id,
                action="maintenance.production_trial_cleanup",
                entity_type="system",
                entity_id="production-trial-cleanup",
            )
            session.add(receipt)
            session.flush()
            row_id = uuid4()
            session.execute(
                text(
                    "INSERT INTO client_gaming_cleanup_reconciliation ("
                    "id, company_id, client_installation_id, branch_id, terminal_id, "
                    "station_id, local_action_id, server_session_id, revision, "
                    "reported_local_state, local_evidence_revision, local_snapshot, "
                    "local_snapshot_sha256, start_request_hash, stop_request_hash, "
                    "original_action_user_id, candidate_sha256, unresolved_child_count, "
                    "cleanup_receipt_audit_id, status, reported_at"
                    ") VALUES ("
                    ":id, :company_id, :installation_id, :branch_id, :terminal_id, "
                    ":station_id, :action_id, :server_session_id, 1, 'stop_pending', 7, "
                    "CAST(:snapshot AS jsonb), :snapshot_hash, :start_hash, :stop_hash, "
                    ":user_id, :candidate_hash, 0, :receipt_id, 'reported', :reported_at"
                    ")"
                ),
                {
                    "id": row_id,
                    "company_id": company.id,
                    "installation_id": installation.id,
                    "branch_id": branch.id,
                    "terminal_id": terminal.id,
                    "station_id": station.id,
                    "action_id": uuid4(),
                    "server_session_id": uuid4(),
                    "snapshot": '{"evidence_revision":7}',
                    "snapshot_hash": "a" * 64,
                    "start_hash": "b" * 64,
                    "stop_hash": "c" * 64,
                    "user_id": user.id,
                    "candidate_hash": "d" * 64,
                    "receipt_id": receipt.id,
                    "reported_at": now,
                },
            )
            session.commit()
        engine.dispose()

        refused = _run_alembic(database_url, "upgrade", "0081")
        assert refused.returncode != 0
        assert "existing cleanup evidence has no trustworthy report-time app identity" in (
            refused.stdout + refused.stderr
        )
        with psycopg.connect(_dsn(database_url)) as connection:
            assert connection.execute(
                "SELECT count(*) FROM client_gaming_cleanup_reconciliation WHERE id = %s",
                (row_id,),
            ).fetchone() == (1,)
            assert (
                connection.execute(
                    "SELECT column_name FROM information_schema.columns "
                    "WHERE table_schema = current_schema() "
                    "AND table_name = 'client_gaming_cleanup_reconciliation' "
                    "AND column_name LIKE 'reported_app_version_%'"
                ).fetchall()
                == []
            )


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
@pytest.mark.parametrize(
    ("assignment", "value"),
    [
        ("reported_app_version_name = %s", "3.1.30-tampered"),
        ("reported_app_version_code = %s", 38),
    ],
)
def test_0081_trigger_rejects_report_time_app_identity_mutation(
    migrated_0081_database: str,
    assignment: str,
    value: object,
) -> None:
    ids = _seed_scope(migrated_0081_database)
    with psycopg.connect(_dsn(migrated_0081_database)) as connection:
        with pytest.raises(
            psycopg.errors.CheckViolation,
            match="Gaming cleanup reconciliation identity is immutable",
        ):
            connection.execute(
                sql.SQL("UPDATE client_gaming_cleanup_reconciliation SET {} WHERE id = %s").format(
                    sql.SQL(assignment)
                ),
                (value, ids["reported_allowed_id"]),
            )
        connection.rollback()


@pytest.mark.integration
def test_0081_trigger_rejects_fabricated_reported_approval(
    migrated_0081_database: str,
) -> None:
    ids = _seed_scope(migrated_0081_database)
    with psycopg.connect(_dsn(migrated_0081_database)) as connection:
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
def test_0081_trigger_rejects_every_approved_tuple_mutation(
    migrated_0081_database: str,
) -> None:
    ids = _seed_scope(migrated_0081_database)
    rejected_ids = ids["approved_rejected_ids"]
    assert isinstance(rejected_ids, list)
    mutations: list[tuple[str, tuple[object, ...]]] = [
        ("approved_at = approved_at + interval '1 second'", ()),
        ("approved_by = %s", (ids["other_user_id"],)),
        ("approval_reason = 'Tampered approval reason'", ()),
        ("approval_idempotency_key = 'tampered-approval-key'", ()),
    ]
    with psycopg.connect(_dsn(migrated_0081_database)) as connection:
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
def test_0081_trigger_allows_exact_reported_and_approved_supersession(
    migrated_0081_database: str,
) -> None:
    ids = _seed_scope(migrated_0081_database)
    with psycopg.connect(_dsn(migrated_0081_database)) as connection:
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
