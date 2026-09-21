from pathlib import Path

from app.models import ClientGamingCleanupReconciliation

MIGRATION = (
    Path(__file__).parents[2] / "alembic/versions/0079_client_gaming_cleanup_reconciliation.py"
).read_text()


def test_revision_ledger_schema_matches_model_and_forward_states() -> None:
    table = ClientGamingCleanupReconciliation.__table__
    assert {
        "revision",
        "local_evidence_revision",
        "local_snapshot",
        "local_snapshot_sha256",
        "start_request_hash",
        "stop_request_hash",
        "original_action_user_id",
        "superseded_at",
    } <= set(table.columns.keys())
    assert "('reported', 'approved', 'applied', 'superseded')" in MIGRATION
    assert "OLD.status = 'reported' AND NEW.status = 'approved'" in MIGRATION
    assert "OLD.status = 'approved' AND NEW.status = 'applied'" in MIGRATION
    assert "OLD.status = 'reported' AND NEW.status = 'superseded'" in MIGRATION
    assert "OLD.status = 'approved' AND NEW.status = 'superseded'" in MIGRATION
    assert "OLD.status IN ('reported', 'approved') AND NEW.status = 'superseded'" in MIGRATION
    assert "NEW.approved_at, NEW.approved_by, NEW.approval_reason" in MIGRATION
    assert "OLD.approved_at, OLD.approved_by, OLD.approval_reason" in MIGRATION
    assert "revision BETWEEN 1 AND 32" in MIGRATION
    assert "status = 'superseded' AND applied_at IS NULL" in MIGRATION
    assert "uq_client_gaming_cleanup_current_action" in MIGRATION
    assert "uq_client_gaming_cleanup_current_session" in MIGRATION
    assert "status <> 'superseded'" in MIGRATION


def test_downgrade_locks_before_checking_for_evidence() -> None:
    lock = MIGRATION.index(
        'op.execute("LOCK TABLE client_gaming_cleanup_reconciliation IN ACCESS EXCLUSIVE MODE")'
    )
    emptiness_check = MIGRATION.index("SELECT EXISTS (SELECT 1 FROM")
    drop = MIGRATION.index('op.drop_table("client_gaming_cleanup_reconciliation")')
    assert lock < emptiness_check < drop
    assert "0079 downgrade refused" in MIGRATION
