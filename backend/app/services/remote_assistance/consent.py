"""Reconcile remote-assistance consent when a tablet's authenticated user changes."""

from __future__ import annotations

import hashlib
from dataclasses import dataclass
from datetime import UTC, datetime
from typing import TYPE_CHECKING
from uuid import UUID, uuid4

from sqlalchemy import exists, select

from app.models import (
    AuditLog,
    ClientInstallation,
    RemoteAssistanceCommand,
    RemoteAssistanceGrant,
    RemoteAssistanceSession,
)
from app.services.remote_assistance.relay import delete_latest_frame

if TYPE_CHECKING:
    from sqlalchemy.ext.asyncio import AsyncSession


@dataclass(frozen=True, slots=True)
class ConsentReconciliation:
    invalidated_grant_ids: tuple[UUID, ...]
    terminal_session_ids: tuple[UUID, ...]

    @property
    def changed(self) -> bool:
        return bool(self.invalidated_grant_ids or self.terminal_session_ids)


def _device_ref(company_id: UUID, installation_id: UUID) -> str:
    material = f"{company_id}:{installation_id}".encode("ascii")
    return hashlib.sha256(material).hexdigest()[:20]


def _audit(
    session: AsyncSession,
    *,
    installation: ClientInstallation,
    actor_user_id: UUID,
    terminal_id: UUID | None,
    action: str,
    entity_type: str,
    entity_id: UUID,
    before_status: str,
    after_status: str,
    extra: dict[str, object] | None = None,
) -> None:
    after: dict[str, object] = {
        "status": after_status,
        "device_ref": _device_ref(installation.company_id, installation.installation_id),
        "reason": "device_user_changed",
    }
    if extra:
        after.update(extra)
    session.add(
        AuditLog(
            actor_user_id=actor_user_id,
            company_id=installation.company_id,
            terminal_id=terminal_id,
            action=action,
            entity_type=entity_type,
            entity_id=str(entity_id),
            before={"status": before_status},
            after=after,
        )
    )


async def reconcile_remote_assistance_user_binding(
    session: AsyncSession,
    *,
    installation: ClientInstallation,
    current_user_id: UUID,
    terminal_id: UUID | None,
    now: datetime | None = None,
) -> ConsentReconciliation:
    """Revoke consent accepted by another user and end its open session.

    The caller must hold the ``ClientInstallation`` row lock. That serializes
    this check with the installation heartbeat UPSERT that changes
    ``last_user_id``. Grant/session rows are then locked in deterministic order.
    """

    if installation.last_user_id != current_user_id:
        # A caller with stale tenant/device state must not mutate another
        # user's consent. Endpoint-level actor validation supplies the 403.
        return ConsentReconciliation((), ())

    now = now or datetime.now(UTC)
    open_session_for_grant = exists(
        select(RemoteAssistanceSession.id).where(
            RemoteAssistanceSession.company_id == RemoteAssistanceGrant.company_id,
            RemoteAssistanceSession.grant_id == RemoteAssistanceGrant.id,
            RemoteAssistanceSession.status.in_(("requested", "active")),
        )
    )
    grants = list(
        (
            await session.execute(
                select(RemoteAssistanceGrant)
                .where(
                    RemoteAssistanceGrant.company_id == installation.company_id,
                    RemoteAssistanceGrant.client_installation_id == installation.id,
                    RemoteAssistanceGrant.requested_for_user_id != current_user_id,
                    (RemoteAssistanceGrant.status == "requested")
                    | (RemoteAssistanceGrant.status == "active")
                    | (
                        (RemoteAssistanceGrant.status == "consumed")
                        & open_session_for_grant
                    ),
                )
                .order_by(RemoteAssistanceGrant.id)
                .with_for_update()
            )
        )
        .scalars()
        .all()
    )
    if not grants:
        return ConsentReconciliation((), ())

    grant_ids = {grant.id for grant in grants}
    open_sessions = list(
        (
            await session.execute(
                select(RemoteAssistanceSession)
                .where(
                    RemoteAssistanceSession.company_id == installation.company_id,
                    RemoteAssistanceSession.grant_id.in_(grant_ids),
                    RemoteAssistanceSession.status.in_(("requested", "active")),
                )
                .order_by(RemoteAssistanceSession.id)
                .with_for_update()
            )
        )
        .scalars()
        .all()
    )
    sessions_by_grant: dict[UUID, list[RemoteAssistanceSession]] = {}
    for support_session in open_sessions:
        sessions_by_grant.setdefault(support_session.grant_id, []).append(support_session)

    for grant in grants:
        before = grant.status
        revocation_id = uuid4() if before != "requested" else None
        if before == "requested":
            grant.status = "expired"
        else:
            grant.status = "revoked"
            grant.revoked_at = now
            grant.revoked_by_user_id = current_user_id
            grant.revocation_id = revocation_id
        _audit(
            session,
            installation=installation,
            actor_user_id=current_user_id,
            terminal_id=terminal_id,
            action=(
                "remote_assistance.grant.expired"
                if before == "requested"
                else "remote_assistance.grant.revoked"
            ),
            entity_type="RemoteAssistanceGrant",
            entity_id=grant.id,
            before_status=before,
            after_status=grant.status,
        )

        for support_session in sessions_by_grant.get(grant.id, ()):
            session_before = support_session.status
            if before == "requested":
                support_session.status = "expired"
            else:
                support_session.status = "ended"
                support_session.ended_at = now
                support_session.ended_by_user_id = current_user_id
                support_session.end_id = revocation_id
                support_session.end_reason = "grant_revoked"
            _audit(
                session,
                installation=installation,
                actor_user_id=current_user_id,
                terminal_id=terminal_id,
                action=(
                    "remote_assistance.session.expired"
                    if before == "requested"
                    else "remote_assistance.session.ended"
                ),
                entity_type="RemoteAssistanceSession",
                entity_id=support_session.id,
                before_status=session_before,
                after_status=support_session.status,
                extra=(None if before == "requested" else {"end_reason": "grant_revoked"}),
            )

    if open_sessions:
        commands = list(
            (
                await session.execute(
                    select(RemoteAssistanceCommand)
                    .where(
                        RemoteAssistanceCommand.company_id == installation.company_id,
                        RemoteAssistanceCommand.session_id.in_(
                            [support_session.id for support_session in open_sessions]
                        ),
                        RemoteAssistanceCommand.status == "pending",
                    )
                    .order_by(RemoteAssistanceCommand.id)
                    .with_for_update()
                )
            )
            .scalars()
            .all()
        )
        for command in commands:
            command.status = "rejected"
            command.resolved_at = now
            command.resolved_by_user_id = current_user_id
            command.rejection_reason_code = "session_ended"
            _audit(
                session,
                installation=installation,
                actor_user_id=current_user_id,
                terminal_id=terminal_id,
                action="remote_assistance.command.rejected",
                entity_type="RemoteAssistanceCommand",
                entity_id=command.id,
                before_status="pending",
                after_status="rejected",
                extra={"reason_code": "session_ended", "sequence": command.sequence},
            )

    # Autoflush is disabled. Persist the terminal authority state before any
    # caller continues with a fresh lookup against former open statuses.
    await session.flush()
    for support_session in open_sessions:
        # This is intentionally best effort. Retrieval authorizes against the
        # now-terminal DB row before Redis, and relay bytes have a five-second
        # TTL even if Redis is temporarily unavailable during early eviction.
        await delete_latest_frame(
            company_id=installation.company_id,
            session_id=support_session.id,
        )
    return ConsentReconciliation(
        tuple(grant.id for grant in grants),
        tuple(support_session.id for support_session in open_sessions),
    )


__all__ = ["ConsentReconciliation", "reconcile_remote_assistance_user_binding"]
