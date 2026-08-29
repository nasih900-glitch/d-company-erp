"""Forward actor attribution remains truthful and nullable for legacy rows."""

from app.models import GamingSession, Payment


def test_receipt_actor_columns_exist_with_restricting_foreign_keys() -> None:
    cases = (
        (Payment.__table__.c.recorded_by, "users.id"),
        (GamingSession.__table__.c.stopped_by, "users.id"),
        (GamingSession.__table__.c.sent_to_pos_by, "users.id"),
    )
    for column, target in cases:
        assert column.nullable is True
        assert {str(foreign_key.column) for foreign_key in column.foreign_keys} == {
            target
        }
        assert {foreign_key.ondelete for foreign_key in column.foreign_keys} == {
            "RESTRICT"
        }
    assert GamingSession.__table__.c.sent_to_pos_at.nullable is True
