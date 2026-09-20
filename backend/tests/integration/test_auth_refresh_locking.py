"""PostgreSQL lock-order regressions for refresh-token rotation."""

from __future__ import annotations

import asyncio
from datetime import UTC, datetime
from time import monotonic
from typing import TYPE_CHECKING
from uuid import UUID, uuid4

import pytest
from sqlalchemy import select, text, update
from sqlalchemy.exc import DBAPIError

from app.core.db import AsyncSessionLocal
from app.core.security import decode_token, issue_refresh_token
from app.models import AuditLog, AuthRefreshSession, Company, User
from app.services.auth.refresh_sessions import (
    consume_refresh_session,
    refresh_token_hash,
    register_refresh_session,
)

if TYPE_CHECKING:
    from sqlalchemy.ext.asyncio import AsyncSession


async def _wait_for_blocker(
    observer: AsyncSession,
    *,
    blocked_pid: int,
    blocker_pid: int,
    timeout_seconds: float = 5.0,
) -> None:
    deadline = monotonic() + timeout_seconds
    while monotonic() < deadline:
        blockers = await observer.scalar(
            text("SELECT pg_blocking_pids(:blocked_pid)"),
            {"blocked_pid": blocked_pid},
        )
        if blocker_pid in (blockers or []):
            return
        await asyncio.sleep(0.01)
    pytest.fail("expected database transaction did not reach its bounded lock wait")


async def _cancel_tasks(*tasks: asyncio.Task[object]) -> None:
    for task in tasks:
        if not task.done():
            task.cancel()
    if tasks:
        await asyncio.gather(*tasks, return_exceptions=True)


def _refresh_credential(user: User, family_id: UUID) -> tuple[str, dict[str, object]]:
    token = issue_refresh_token(
        user_id=user.id,
        jti=str(uuid4()),
        auth_version=user.auth_version,
        family_id=family_id,
    )
    return token, decode_token(token)


async def _seed_refresh_credential(
    session: AsyncSession,
    *,
    user: User,
) -> tuple[str, dict[str, object], UUID]:
    family_id = uuid4()
    token, claims = _refresh_credential(user, family_id)
    register_refresh_session(
        session,
        user=user,
        token=token,
        claims=claims,
        family_id=family_id,
    )
    await session.commit()
    return token, claims, family_id


@pytest.mark.integration
@pytest.mark.asyncio
async def test_refresh_successor_insert_does_not_deadlock_company_then_user_fk_writer(
    session: AsyncSession,
    seed_owner: dict,
) -> None:
    owner = seed_owner["owner"]
    company = seed_owner["company"]
    predecessor, claims, family_id = await _seed_refresh_credential(
        session,
        user=owner,
    )
    successor, successor_claims = _refresh_credential(owner, family_id)

    rotation_task: asyncio.Task[object] | None = None
    company_writer_task: asyncio.Task[object] | None = None
    async with (
        AsyncSessionLocal() as rotation,
        AsyncSessionLocal() as company_writer,
        AsyncSessionLocal() as observer,
    ):
        rotation_pid = await rotation.scalar(text("SELECT pg_backend_pid()"))
        company_writer_pid = await company_writer.scalar(text("SELECT pg_backend_pid()"))
        assert isinstance(rotation_pid, int)
        assert isinstance(company_writer_pid, int)
        await company_writer.execute(
            select(Company).where(Company.id == company.id).with_for_update()
        )

        async def rotate() -> None:
            consumed = await consume_refresh_session(
                rotation,
                token=predecessor,
                claims=claims,
            )
            assert consumed.family_id == family_id
            register_refresh_session(
                rotation,
                user=consumed.user,
                token=successor,
                claims=successor_claims,
                family_id=family_id,
            )
            await rotation.flush()
            await rotation.commit()

        async def write_company_audit() -> None:
            company_writer.add(
                AuditLog(
                    actor_user_id=owner.id,
                    company_id=company.id,
                    action="refresh_lock_interleaving_probe",
                    entity_type="Company",
                    entity_id=str(company.id),
                    before=None,
                    after=None,
                    created_at=datetime.now(UTC),
                )
            )
            await company_writer.commit()

        try:
            rotation_task = asyncio.create_task(rotate())
            await _wait_for_blocker(
                observer,
                blocked_pid=rotation_pid,
                blocker_pid=company_writer_pid,
            )
            company_writer_task = asyncio.create_task(write_company_audit())
            try:
                async with asyncio.timeout(5):
                    await asyncio.gather(rotation_task, company_writer_task)
            except DBAPIError as exc:
                sqlstate = getattr(exc.orig, "sqlstate", None)
                raise AssertionError(
                    f"refresh lock interleaving failed with database SQLSTATE {sqlstate}"
                ) from None
        finally:
            await _cancel_tasks(
                *(task for task in (rotation_task, company_writer_task) if task is not None)
            )
            await rotation.rollback()
            await company_writer.rollback()
            await observer.rollback()

    rows = (
        await session.scalars(
            select(AuthRefreshSession).where(
                AuthRefreshSession.token_hash.in_(
                    (refresh_token_hash(predecessor), refresh_token_hash(successor))
                )
            )
        )
    ).all()
    assert len(rows) == 2
    by_hash = {row.token_hash: row for row in rows}
    consumed_predecessor = by_hash[refresh_token_hash(predecessor)]
    active_successor = by_hash[refresh_token_hash(successor)]
    assert consumed_predecessor.consumed_at is not None
    assert consumed_predecessor.revoked_at is None
    assert active_successor.consumed_at is None
    assert active_successor.revoked_at is None
    assert active_successor.family_id == consumed_predecessor.family_id == family_id


@pytest.mark.integration
@pytest.mark.asyncio
async def test_refresh_user_lock_still_serializes_concurrent_auth_version_update(
    session: AsyncSession,
    seed_owner: dict,
) -> None:
    owner = seed_owner["owner"]
    predecessor, claims, _ = await _seed_refresh_credential(session, user=owner)

    update_task: asyncio.Task[object] | None = None
    async with (
        AsyncSessionLocal() as rotation,
        AsyncSessionLocal() as user_writer,
        AsyncSessionLocal() as observer,
    ):
        rotation_pid = await rotation.scalar(text("SELECT pg_backend_pid()"))
        user_writer_pid = await user_writer.scalar(text("SELECT pg_backend_pid()"))
        assert isinstance(rotation_pid, int)
        assert isinstance(user_writer_pid, int)
        await consume_refresh_session(rotation, token=predecessor, claims=claims)

        async def update_auth_version() -> None:
            await user_writer.execute(
                update(User).where(User.id == owner.id).values(auth_version=User.auth_version + 1)
            )
            await user_writer.commit()

        try:
            update_task = asyncio.create_task(update_auth_version())
            await _wait_for_blocker(
                observer,
                blocked_pid=user_writer_pid,
                blocker_pid=rotation_pid,
            )
            await rotation.rollback()
            async with asyncio.timeout(5):
                await update_task
        finally:
            await _cancel_tasks(*(task for task in (update_task,) if task is not None))
            await rotation.rollback()
            await user_writer.rollback()
            await observer.rollback()

    await session.refresh(owner)
    assert owner.auth_version == claims["auth_version"] + 1
