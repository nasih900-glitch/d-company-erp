"""Exact-key arbitration without business-parent locks after the decision key."""

from __future__ import annotations

from uuid import uuid4

from sqlalchemy import select, text

from app.api.v1.finance.tender_corrections import require_confirmation, require_protected_owner
from app.core.errors import BusinessRuleError, ConflictError, IdempotencyConflict, NotFoundError
from app.core.permissions import require_permission
from app.models import Order, PaidBillActionDecision
from app.services.pos import paid_bill_amendments as amendments


async def require_read_committed(session):
    if await session.scalar(text("SHOW transaction_isolation")) != "read committed":
        raise ConflictError(
            "Paid-bill decisions require a fresh read-committed transaction. "
            "Retry the original key.",
            details={"retryable": True, "outcome": "unconfirmed"},
        )


def validate_key(key):
    if not key or key.strip() != key or len(key) > 160:
        raise BusinessRuleError("Keep the exact original action key of 1–160 characters.")


async def authorize(session, tenant, confirmation, fresh):
    await fresh()
    await require_protected_owner(session, tenant)
    if tenant.branch_id is None or tenant.terminal_id is None:
        raise BusinessRuleError("Restore the original branch and terminal to recover this action.")
    await require_confirmation(
        session, tenant, confirmation, branch_id=tenant.branch_id, scope=amendments.PURPOSE
    )


async def original_order(session, tenant, order_id, authorized):
    # Plain MVCC read only. 0048 protects issued identity; avoid financial/source locks.
    order = await session.scalar(
        select(Order)
        .where(
            Order.id == order_id,
            Order.company_id == tenant.company_id,
            Order.branch_id == tenant.branch_id,
            Order.terminal_id == tenant.terminal_id,
        )
        .execution_options(populate_existing=True)
    )
    await authorized()
    if (
        order is None
        or order.source_integrity_revision != 48
        or order.status not in {"paid", "refunded"}
        or not order.invoice_no
        or not order.invoice_issued_at
        or not order.closed_at
    ):
        raise NotFoundError("The original issued receipt is not in this exact workspace")
    return order


async def decision(session, tenant, key):
    row = await session.scalar(
        select(PaidBillActionDecision).where(
            PaidBillActionDecision.company_id == tenant.company_id,
            PaidBillActionDecision.action_key == key,
        )
    )
    if row is not None and (
        row.recorded_by != tenant.user_id
        or row.branch_id != tenant.branch_id
        or row.terminal_id != tenant.terminal_id
    ):
        raise NotFoundError("Saved paid-bill decision not found in this workspace")
    return row


def reference(row):
    return {
        "action_key": row.action_key,
        "company_id": row.company_id,
        "actor_user_id": row.recorded_by,
        "branch_id": row.branch_id,
        "terminal_id": row.terminal_id,
        "order_id": row.order_id,
        "request_path": row.request_path,
        "request_hash": row.request_hash,
        "original_command": row.request_body,
    }


async def present(session, tenant, row, authorized):
    await original_order(session, tenant, row.order_id, authorized)
    if row.outcome == "accepted":
        if row.response_body["refund_request_id"] is not None:
            await require_permission(session, tenant, "pos.refund")
        current = await amendments.current_state(session, tenant, row.order_id)
        await authorized()
        return {
            "protocol": 1,
            "outcome": "accepted",
            "reference": reference(row),
            "accepted": row.response_body,
            "current": current,
        }
    await authorized()
    return {
        "protocol": 1,
        "outcome": "cancelled",
        "reference": reference(row),
        "cancelled": row.response_body,
    }


async def outcome_authorized(session, tenant, *, key, authorized):
    validate_key(key)
    await require_read_committed(session)
    await authorized()
    row = await decision(session, tenant, key)
    await authorized()
    if row is not None:
        return await present(session, tenant, row, authorized)
    return {
        "protocol": 1,
        "outcome": "unconfirmed",
        "action_key": key,
        "company_id": tenant.company_id,
        "actor_user_id": tenant.user_id,
        "branch_id": tenant.branch_id,
        "terminal_id": tenant.terminal_id,
    }


async def cancel_authorized(session, tenant, payload, *, key, authorized):
    validate_key(key)
    await require_read_committed(session)
    await authorized()
    command = payload.original_command
    body = command.model_dump(mode="json")
    digest = await amendments._digest(session, body)
    await original_order(session, tenant, command.order_id, authorized)
    row = await decision(session, tenant, key)
    await authorized()
    if row is not None:
        if (
            row.order_id != command.order_id
            or row.request_path != amendments.PATH
            or row.request_hash != digest
            or row.request_body != body
            or row.outcome == "cancelled"
            and row.cancellation_reason != payload.cancellation_reason
        ):
            raise IdempotencyConflict(
                "This key already has a different exact command or cancellation reason. "
                "Keep its original evidence."
            )
        return await present(session, tenant, row, authorized)
    ident = uuid4()
    at = await session.scalar(text("SELECT statement_timestamp()"))
    receipt = await session.scalar(
        text("SELECT dcompany_paid_action_cancel_response(:id,:at,:reason)"),
        {"id": ident, "at": at, "reason": payload.cancellation_reason},
    )
    row = PaidBillActionDecision(
        id=ident,
        company_id=tenant.company_id,
        recorded_by=tenant.user_id,
        order_id=command.order_id,
        branch_id=tenant.branch_id,
        terminal_id=tenant.terminal_id,
        action_key=key,
        request_path=amendments.PATH,
        request_hash=digest,
        request_body=body,
        outcome="cancelled",
        accepted_intent_id=None,
        cancellation_reason=payload.cancellation_reason,
        response_body=receipt,
        decided_at=at,
    )
    session.add(row)
    # UNIQUE waits here. Resolve a loser in a whole new transaction, never a savepoint.
    await session.flush()
    await authorized()
    return await present(session, tenant, row, authorized)


async def outcome(session, tenant, *, key, confirmation, fresh):
    validate_key(key)
    await require_read_committed(session)
    await amendments.lock_actor(session, tenant)

    async def authorized():
        await authorize(session, tenant, confirmation, fresh)

    return await outcome_authorized(session, tenant, key=key, authorized=authorized)


async def cancel(session, tenant, payload, *, key, confirmation, fresh):
    validate_key(key)
    await require_read_committed(session)
    await amendments.lock_actor(session, tenant)

    async def authorized():
        await authorize(session, tenant, confirmation, fresh)

    return await cancel_authorized(session, tenant, payload, key=key, authorized=authorized)
