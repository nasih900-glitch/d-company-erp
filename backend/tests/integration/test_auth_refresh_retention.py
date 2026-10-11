"""Refresh-session retention boundaries and concurrency guarantees."""

from __future__ import annotations

import asyncio
import hashlib
from datetime import UTC, datetime, timedelta, timezone
from uuid import UUID, uuid4

import pytest
from sqlalchemy import delete, func, select

from app.core.db import AsyncSessionLocal
from app.models import (
    AuditLog,
    AuthRefreshSession,
    Company,
    IdempotencyKey,
    Payment,
    User,
)
from app.services.auth.refresh_sessions import access_family_is_active
from app.services.auth.retention import purge_expired_refresh_sessions


def _session_row(
    user: User,
    *,
    label: str,
    expires_at: datetime,
    consumed_at: datetime | None = None,
    revoked_at: datetime | None = None,
    family_id: UUID | None = None,
) -> AuthRefreshSession:
    return AuthRefreshSession(
        company_id=user.company_id,
        user_id=user.id,
        family_id=family_id or uuid4(),
        token_hash=hashlib.sha256(label.encode()).hexdigest(),
        auth_version=user.auth_version,
        expires_at=expires_at,
        consumed_at=consumed_at,
        revoked_at=revoked_at,
        revocation_reason="logout" if revoked_at is not None else None,
    )


async def _existing_session_ids(ids: set[UUID]) -> set[UUID]:
    async with AsyncSessionLocal() as observer:
        return set(
            (
                await observer.scalars(
                    select(AuthRefreshSession.id).where(AuthRefreshSession.id.in_(ids))
                )
            ).all()
        )


@pytest.mark.integration
@pytest.mark.asyncio
async def test_retention_deletes_only_sessions_expired_more_than_ninety_days(
    session,
    seed_owner,
) -> None:
    now = datetime(2026, 10, 11, 12, 0, tzinfo=UTC)
    owner = seed_owner["owner"]
    protected = [
        _session_row(
            owner,
            label=f"active-{uuid4()}",
            expires_at=now + timedelta(days=1),
        ),
        _session_row(
            owner,
            label=f"recent-expired-{uuid4()}",
            expires_at=now - timedelta(days=89),
            consumed_at=now - timedelta(days=89),
        ),
        _session_row(
            owner,
            label=f"recent-revoked-{uuid4()}",
            expires_at=now - timedelta(days=30),
            revoked_at=now - timedelta(days=20),
        ),
        _session_row(
            owner,
            label=f"exact-boundary-{uuid4()}",
            expires_at=now - timedelta(days=90),
            consumed_at=now - timedelta(days=90),
        ),
    ]
    eligible = [
        _session_row(
            owner,
            label=f"old-consumed-{uuid4()}",
            expires_at=now - timedelta(days=91),
            consumed_at=now - timedelta(days=91),
        ),
        _session_row(
            owner,
            label=f"old-revoked-{uuid4()}",
            expires_at=now - timedelta(days=120),
            revoked_at=now - timedelta(days=100),
        ),
    ]
    session.add_all([*protected, *eligible])
    await session.commit()

    # Exercise normalization from an aware non-UTC operator clock.
    india_now = now.astimezone(timezone(timedelta(hours=5, minutes=30)))
    async with session.begin():
        purged = await purge_expired_refresh_sessions(
            session,
            batch_size=100,
            now=india_now,
        )

    assert purged >= len(eligible)
    remaining = await _existing_session_ids(
        {row.id for row in [*protected, *eligible]}
    )
    assert remaining == {row.id for row in protected}
    await session.execute(
        delete(AuthRefreshSession).where(
            AuthRefreshSession.id.in_({row.id for row in protected})
        )
    )
    await session.commit()


@pytest.mark.integration
@pytest.mark.asyncio
async def test_retention_rejects_invalid_clock_and_batch_bounds(
    session,
) -> None:
    with pytest.raises(ValueError, match="timezone"):
        await purge_expired_refresh_sessions(
            session,
            batch_size=1,
            now=datetime(2026, 10, 11, 12, 0),
        )
    for invalid_batch_size in (0, 1_001):
        with pytest.raises(ValueError, match="between 1 and 1000"):
            await purge_expired_refresh_sessions(
                session,
                batch_size=invalid_batch_size,
            )


@pytest.mark.integration
@pytest.mark.asyncio
async def test_retention_is_repeatable_and_never_exceeds_batch_size(
    session,
    seed_owner,
) -> None:
    now = datetime(2026, 10, 11, 12, 0, tzinfo=UTC)
    rows = [
        _session_row(
            seed_owner["owner"],
            label=f"bounded-{uuid4()}",
            expires_at=now - timedelta(days=100 + index),
            consumed_at=now - timedelta(days=100 + index),
        )
        for index in range(3)
    ]
    session.add_all(rows)
    await session.commit()

    results: list[int] = []
    for _ in range(3):
        async with session.begin():
            results.append(
                await purge_expired_refresh_sessions(
                    session,
                    batch_size=2,
                    now=now,
                )
            )

    assert results == [2, 1, 0]
    assert not await _existing_session_ids({row.id for row in rows})


@pytest.mark.integration
@pytest.mark.asyncio
async def test_retention_skips_a_concurrently_locked_eligible_session(
    session,
    seed_owner,
) -> None:
    now = datetime(2026, 10, 11, 12, 0, tzinfo=UTC)
    locked = _session_row(
        seed_owner["owner"],
        label=f"locked-{uuid4()}",
        expires_at=now - timedelta(days=120),
        consumed_at=now - timedelta(days=120),
    )
    unlocked = _session_row(
        seed_owner["owner"],
        label=f"unlocked-{uuid4()}",
        expires_at=now - timedelta(days=110),
        consumed_at=now - timedelta(days=110),
    )
    session.add_all([locked, unlocked])
    await session.commit()

    async with AsyncSessionLocal() as locker, AsyncSessionLocal() as worker:
        await locker.execute(
            select(AuthRefreshSession)
            .where(AuthRefreshSession.id == locked.id)
            .with_for_update()
        )
        async with asyncio.timeout(2), worker.begin():
            purged = await purge_expired_refresh_sessions(
                worker,
                batch_size=1,
                now=now,
            )
        assert purged == 1
        assert await _existing_session_ids({locked.id, unlocked.id}) == {locked.id}
        await locker.rollback()

    async with AsyncSessionLocal() as final_worker, final_worker.begin():
        assert (
            await purge_expired_refresh_sessions(
                final_worker,
                batch_size=1,
                now=now,
            )
            == 1
        )
    assert not await _existing_session_ids({locked.id})


@pytest.mark.integration
@pytest.mark.asyncio
async def test_retention_preserves_refresh_replay_family_revocation(
    client,
    seed_owner,
) -> None:
    login = await client.post(
        "/api/v1/auth/login",
        json={
            "email": seed_owner["owner"].email,
            "password": seed_owner["password"],
        },
    )
    assert login.status_code == 200, login.text
    original = login.json()["refresh_token"]

    rotated = await client.post(
        "/api/v1/auth/refresh",
        json={"refresh_token": original},
    )
    assert rotated.status_code == 200, rotated.text

    owner_session_ids: set[UUID]
    async with AsyncSessionLocal() as observer:
        owner_session_ids = set(
            (
                await observer.scalars(
                    select(AuthRefreshSession.id).where(
                        AuthRefreshSession.user_id == seed_owner["owner"].id
                    )
                )
            ).all()
        )
    assert len(owner_session_ids) == 2

    async with AsyncSessionLocal() as retention_session, retention_session.begin():
        await purge_expired_refresh_sessions(
            retention_session,
            batch_size=100,
            now=datetime.now(UTC),
        )
    assert await _existing_session_ids(owner_session_ids) == owner_session_ids

    replay = await client.post(
        "/api/v1/auth/refresh",
        json={"refresh_token": original},
    )
    assert replay.status_code == 401
    assert "reuse" in replay.json()["error"]["message"]

    successor = await client.post(
        "/api/v1/auth/refresh",
        json={"refresh_token": rotated.json()["refresh_token"]},
    )
    assert successor.status_code == 401


@pytest.mark.integration
@pytest.mark.asyncio
async def test_retention_preserves_mixed_age_family_access_state(
    session, seed_owner
) -> None:
    now = datetime(2026, 10, 11, 12, tzinfo=UTC)
    owner = seed_owner["owner"]
    active_family = uuid4()
    revoked_mixed_family = uuid4()
    revoked_old_family = uuid4()
    rows = [
        _session_row(
            owner,
            label=f"active-old-{uuid4()}",
            family_id=active_family,
            expires_at=now - timedelta(days=120),
            consumed_at=now - timedelta(days=120),
        ),
        _session_row(
            owner,
            label=f"active-current-{uuid4()}",
            family_id=active_family,
            expires_at=now + timedelta(days=1),
        ),
        _session_row(
            owner,
            label=f"revoked-old-{uuid4()}",
            family_id=revoked_mixed_family,
            expires_at=now - timedelta(days=120),
            revoked_at=now - timedelta(days=100),
        ),
        _session_row(
            owner,
            label=f"revoked-recent-{uuid4()}",
            family_id=revoked_mixed_family,
            expires_at=now - timedelta(days=2),
            revoked_at=now - timedelta(days=1),
        ),
        _session_row(
            owner,
            label=f"revoked-all-old-{uuid4()}",
            family_id=revoked_old_family,
            expires_at=now - timedelta(days=130),
            revoked_at=now - timedelta(days=100),
        ),
    ]
    session.add_all(rows)
    await session.commit()

    async def family_is_active(family_id: UUID) -> bool:
        return await access_family_is_active(
            session,
            user=owner,
            claims={"session_family_id": str(family_id)},
        )

    assert [
        await family_is_active(active_family),
        await family_is_active(revoked_mixed_family),
        await family_is_active(revoked_old_family),
    ] == [True, False, False]
    await session.commit()

    async with session.begin():
        await purge_expired_refresh_sessions(session, batch_size=100, now=now)

    assert [
        await family_is_active(active_family),
        await family_is_active(revoked_mixed_family),
        await family_is_active(revoked_old_family),
    ] == [True, False, False]
    remaining = await _existing_session_ids({row.id for row in rows})
    assert remaining == {rows[1].id, rows[3].id}


@pytest.mark.integration
@pytest.mark.asyncio
async def test_retention_selects_boundaries_across_companies_without_business_mutation(
    session, seed_owner
) -> None:
    now = datetime(2026, 10, 11, 12, tzinfo=UTC)
    foreign_company = Company(id=uuid4(), name=f"Retention foreign {uuid4()}")
    foreign_user = User(
        id=uuid4(),
        company_id=foreign_company.id,
        email=f"retention-{uuid4()}@test.local",
        name="Retention foreign user",
        password_hash=seed_owner["owner"].password_hash,
        status="active",
    )
    session.add_all([foreign_company, foreign_user])
    await session.flush()
    own_eligible = _session_row(
        seed_owner["owner"],
        label=f"own-eligible-{uuid4()}",
        expires_at=now - timedelta(days=91),
        consumed_at=now - timedelta(days=91),
    )
    foreign_eligible = _session_row(
        foreign_user,
        label=f"foreign-eligible-{uuid4()}",
        expires_at=now - timedelta(days=100),
        revoked_at=now - timedelta(days=95),
    )
    exact_boundary = _session_row(
        foreign_user,
        label=f"foreign-boundary-{uuid4()}",
        expires_at=now - timedelta(days=90),
        consumed_at=now - timedelta(days=90),
    )
    active = _session_row(
        seed_owner["owner"],
        label=f"own-active-{uuid4()}",
        expires_at=now + timedelta(days=1),
    )
    session.add_all([own_eligible, foreign_eligible, exact_boundary, active])
    await session.commit()

    business_counts_before: list[int] = []
    for model in (Payment, AuditLog, IdempotencyKey):
        business_counts_before.append(
            int(await session.scalar(select(func.count()).select_from(model)) or 0)
        )
    await session.commit()
    async with session.begin():
        await purge_expired_refresh_sessions(session, batch_size=100, now=now)
    business_counts_after: list[int] = []
    for model in (Payment, AuditLog, IdempotencyKey):
        business_counts_after.append(
            int(await session.scalar(select(func.count()).select_from(model)) or 0)
        )

    remaining = await _existing_session_ids(
        {own_eligible.id, foreign_eligible.id, exact_boundary.id, active.id}
    )
    assert remaining == {exact_boundary.id, active.id}
    assert business_counts_after == business_counts_before
