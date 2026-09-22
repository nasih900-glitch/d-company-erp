from pathlib import Path

from app.models import ClientGamingCleanupReconciliation

LEDGER_MIGRATION = (
    Path(__file__).parents[2] / "alembic/versions/0079_client_gaming_cleanup_reconciliation.py"
).read_text()
IDENTITY_MIGRATION = (
    Path(__file__).parents[2] / "alembic/versions/0081_gaming_cleanup_report_app_identity.py"
).read_text()


def test_revision_ledger_schema_matches_model_and_forward_states() -> None:
    table = ClientGamingCleanupReconciliation.__table__
    assert {
        "revision",
        "local_evidence_revision",
        "reported_app_version_name",
        "reported_app_version_code",
        "local_snapshot",
        "local_snapshot_sha256",
        "start_request_hash",
        "stop_request_hash",
        "original_action_user_id",
        "superseded_at",
    } <= set(table.columns.keys())
    assert "('reported', 'approved', 'applied', 'superseded')" in LEDGER_MIGRATION
    assert "OLD.status = 'reported' AND NEW.status = 'approved'" in LEDGER_MIGRATION
    assert "OLD.status = 'approved' AND NEW.status = 'applied'" in LEDGER_MIGRATION
    assert "OLD.status = 'reported' AND NEW.status = 'superseded'" in LEDGER_MIGRATION
    assert "OLD.status = 'approved' AND NEW.status = 'superseded'" in LEDGER_MIGRATION
    assert "status = 'superseded' AND applied_at IS NULL" in LEDGER_MIGRATION
    assert "uq_client_gaming_cleanup_current_action" in LEDGER_MIGRATION
    assert "uq_client_gaming_cleanup_current_session" in LEDGER_MIGRATION
    assert "status <> 'superseded'" in LEDGER_MIGRATION
    assert "revision BETWEEN 1 AND 32" in LEDGER_MIGRATION

    # Published 0079 history remains immutable. Report-time app identity was
    # added by forward migration 0081 after the split-payment 0080 revision.
    assert "reported_app_version_name" not in LEDGER_MIGRATION
    assert 'down_revision = "0080"' in IDENTITY_MIGRATION
    assert "reported_app_version_code BETWEEN 1 AND 2147483647" in IDENTITY_MIGRATION
    assert "reported_app_version_name ~" in IDENTITY_MIGRATION
    assert "NEW.reported_app_version_name" in IDENTITY_MIGRATION
    assert "OLD.reported_app_version_name" in IDENTITY_MIGRATION
    assert "NEW.reported_app_version_code" in IDENTITY_MIGRATION
    assert "OLD.reported_app_version_code" in IDENTITY_MIGRATION
    assert "OLD.status IN ('reported', 'approved') AND NEW.status = 'superseded'" in (
        IDENTITY_MIGRATION
    )
    assert "NEW.approved_at, NEW.approved_by, NEW.approval_reason" in IDENTITY_MIGRATION
    assert "OLD.approved_at, OLD.approved_by, OLD.approval_reason" in IDENTITY_MIGRATION


def test_downgrade_locks_before_checking_for_evidence() -> None:
    lock = LEDGER_MIGRATION.index(
        'op.execute("LOCK TABLE client_gaming_cleanup_reconciliation IN ACCESS EXCLUSIVE MODE")'
    )
    emptiness_check = LEDGER_MIGRATION.index("SELECT EXISTS (SELECT 1 FROM")
    drop = LEDGER_MIGRATION.index('op.drop_table("client_gaming_cleanup_reconciliation")')
    assert lock < emptiness_check < drop
    assert "0079 downgrade refused" in LEDGER_MIGRATION


def test_report_identity_migration_fails_closed_for_unverifiable_legacy_evidence() -> None:
    lock = IDENTITY_MIGRATION.index(
        'op.execute("LOCK TABLE client_gaming_cleanup_reconciliation IN ACCESS EXCLUSIVE MODE")'
    )
    emptiness_check = IDENTITY_MIGRATION.index("SELECT EXISTS (SELECT 1 FROM")
    add_column = IDENTITY_MIGRATION.index('sa.Column("reported_app_version_name"')
    assert lock < emptiness_check < add_column
    assert "existing cleanup evidence has no trustworthy" in IDENTITY_MIGRATION
    assert '_require_empty_legacy_ledger("upgrade")' in IDENTITY_MIGRATION
    assert '_require_empty_legacy_ledger("downgrade")' in IDENTITY_MIGRATION
