"""Database enforcement for immutable, tenant-scoped Gaming actors."""

from __future__ import annotations

from datetime import UTC, datetime, timedelta
from uuid import uuid4

import pytest
from sqlalchemy import select, update
from sqlalchemy.exc import IntegrityError

from app.core.security import hash_password
from app.models import Company, GamingSession, Shift, Station, User


@pytest.mark.integration
@pytest.mark.asyncio
async def test_gaming_transition_actors_are_tenant_scoped_and_immutable(
    session,
    seed_owner,
) -> None:
    company = seed_owner["company"]
    branch = seed_owner["branch"]
    terminal = seed_owner["terminal"]
    owner = seed_owner["owner"]
    now = datetime.now(UTC)

    shift = Shift(
        id=uuid4(),
        company_id=company.id,
        branch_id=branch.id,
        terminal_id=terminal.id,
        opened_by=owner.id,
        opened_at=now - timedelta(hours=1),
        opening_float_minor=0,
        expected_minor=0,
        status="open",
    )
    station = Station(
        id=uuid4(),
        company_id=company.id,
        branch_id=branch.id,
        code=f"ACT-{uuid4().hex[:8]}",
        name="Actor integrity station",
        type="ps5",
        rate_per_hour_minor=20_000,
        is_active=True,
    )
    other_company = Company(id=uuid4(), name="Other actor company")
    other_user = User(
        id=uuid4(),
        company_id=other_company.id,
        email=f"other-{uuid4().hex[:8]}@test.local",
        name="Cross-tenant actor",
        password_hash=hash_password("password1234"),
        status="active",
    )
    attributed = GamingSession(
        id=uuid4(),
        company_id=company.id,
        station_id=station.id,
        opened_by=owner.id,
        stopped_by=owner.id,
        sent_to_pos_by=owner.id,
        sent_to_pos_at=now,
        shift_id=shift.id,
        start_at=now - timedelta(minutes=20),
        end_at=now,
        paused_minutes=0,
        rate_per_hour_minor=20_000,
        billing_mode="hourly",
        billable_minutes=20,
        amount_minor=6_667,
        status="ended",
        extra_controllers=0,
    )
    legacy_unattributed = GamingSession(
        id=uuid4(),
        company_id=company.id,
        station_id=station.id,
        opened_by=owner.id,
        shift_id=shift.id,
        start_at=now - timedelta(minutes=10),
        end_at=now,
        paused_minutes=0,
        rate_per_hour_minor=20_000,
        billing_mode="hourly",
        billable_minutes=10,
        amount_minor=3_333,
        status="ended",
        extra_controllers=0,
    )
    # These fixtures intentionally use foreign-key ids rather than ORM
    # relationships, so establish parents explicitly before the dependent rows.
    session.add_all([shift, station, other_company])
    await session.flush()
    session.add(other_user)
    await session.flush()
    session.add_all([attributed, legacy_unattributed])
    await session.commit()
    attributed_id = attributed.id
    legacy_unattributed_id = legacy_unattributed.id
    owner_id = owner.id
    other_user_id = other_user.id

    # Core SQL bypasses SQLAlchemy's model listener; this proves the database
    # trigger itself protects historical evidence.
    with pytest.raises(IntegrityError, match="stopped_by is immutable"):
        await session.execute(
            update(GamingSession)
            .where(GamingSession.id == attributed_id)
            .values(stopped_by=None)
        )
    await session.rollback()

    with pytest.raises(IntegrityError, match="must belong to the session company"):
        await session.execute(
            update(GamingSession)
            .where(GamingSession.id == legacy_unattributed_id)
            .values(sent_to_pos_by=other_user_id, sent_to_pos_at=now)
        )
    await session.rollback()

    # A truthful first attribution remains allowed for legacy NULL rows.
    await session.execute(
        update(GamingSession)
        .where(GamingSession.id == legacy_unattributed_id)
        .values(
            stopped_by=owner_id,
            sent_to_pos_by=owner_id,
            sent_to_pos_at=now,
        )
    )
    await session.commit()
    actors = (
        await session.execute(
            select(GamingSession.stopped_by, GamingSession.sent_to_pos_by).where(
                GamingSession.id == legacy_unattributed_id
            )
        )
    ).one()
    assert actors == (owner_id, owner_id)

    with pytest.raises(IntegrityError, match="POS handoff attribution is immutable"):
        await session.execute(
            update(GamingSession)
            .where(GamingSession.id == legacy_unattributed_id)
            .values(sent_to_pos_by=None, sent_to_pos_at=None)
        )
    await session.rollback()
