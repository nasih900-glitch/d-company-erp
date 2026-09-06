"""Schema contract for durable cross-staff shift-close attribution."""

from __future__ import annotations

from pathlib import Path

from app.models import Shift


def test_shift_model_exposes_nullable_closer_for_historical_compatibility() -> None:
    column = Shift.__table__.c.closed_by

    assert column.nullable is True
    assert column.index is True
    assert {str(foreign_key.column) for foreign_key in column.foreign_keys} == {
        "users.id"
    }


def test_0066_adds_scoped_immutable_closer_attribution() -> None:
    source = (
        Path(__file__).parents[2]
        / "alembic/versions/0066_shift_closer_attribution.py"
    ).read_text()

    assert 'revision = "0066"' in source
    assert 'down_revision = "0065"' in source
    assert '"closed_by"' in source
    assert 'ondelete="RESTRICT"' in source
    assert "shift closed_by is immutable" in source
    assert "open shift cannot have closed_by attribution" in source
    assert "closed shift cannot be reopened" in source
    assert "closing a shift requires closed_by attribution" in source
    assert "shift closed_by must belong to the shift company" in source
    assert "TG_OP = 'INSERT'" in source
    assert "BEFORE INSERT OR UPDATE OF closed_by, status, company_id" in source
    assert "OLD.status IS DISTINCT FROM 'open'" in source
    assert "NEW.status IS NOT DISTINCT FROM 'open'" in source
    assert "OLD.status = 'open'" in source
    assert "NEW.closed_by IS NULL" in source
    assert "u.company_id = NEW.company_id" in source
    assert "DROP TRIGGER IF EXISTS trg_shifts_closer_attribution" in source
    assert "DROP FUNCTION IF EXISTS dcompany_guard_shift_closer_attribution()" in source
