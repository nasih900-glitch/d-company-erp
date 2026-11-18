"""Settlement-conditioned paid-bill intentions. No commercial rewrite or money posting."""

from __future__ import annotations

import json
from dataclasses import dataclass
from datetime import UTC, datetime
from uuid import UUID, uuid4

from sqlalchemy import select, text

from app.api.v1.finance.tender_corrections import require_confirmation, require_protected_owner
from app.core.errors import BusinessRuleError, ConflictError, IdempotencyConflict, NotFoundError
from app.core.permissions import require_permission
from app.models import (
    Branch,
    Company,
    Order,
    PaidBillAmendmentIntent,
    Shift,
    Terminal,
    User,
)
from app.schemas.paid_bill_amendment import PAID_BILL_AMENDMENT_PROTOCOL
from app.services.integrations.google_sheets_mirror import enqueue_google_sheets_event_if_enabled
from app.services.pos.refund_requests import prepare_pos_refund_request

PATH = "/api/v1/pos/paid-bill-amendments/commit"
PURPOSE = "paid_bill_amendment"


@dataclass(frozen=True)
class Reservation:
    order_id: UUID
    shift_id: UUID
    amount_minor: int
    expected_paid_minor: int
    expected_refundable_minor: int
    mode: str
    client_action_id: str
    reason_code: str = "paid_bill_amendment"
    note: str | None = None


async def scoped_order(session, tenant, order_id, *, lock=False):
    if tenant.branch_id is None or tenant.terminal_id is None:
        raise BusinessRuleError(
            "Select the original branch and terminal before reviewing a paid bill."
        )
    stmt = select(Order).where(
        Order.id == order_id,
        Order.company_id == tenant.company_id,
        Order.branch_id == tenant.branch_id,
        Order.terminal_id == tenant.terminal_id,
    )
    if lock:
        stmt = stmt.with_for_update()
    row = await session.scalar(stmt)
    if row is None:
        raise NotFoundError("Paid bill not found in this branch and terminal")
    return row


async def capture(session, tenant, payload):
    selection = payload.model_dump(mode="json", exclude={"source_revision", "source_fingerprint"})
    source = await session.scalar(
        text("SELECT dcompany_paid_bill_source(:o,:c,:s)"),
        {"o": payload.order_id, "c": tenant.company_id, "s": payload.settlement_shift_id},
    )
    if source is None:
        raise BusinessRuleError("The original invoice source is unavailable.")
    source["selection"] = selection
    encoded = json.dumps(source)
    values = (
        await session.execute(
            text("""SELECT
        encode(sha256(convert_to(CAST(:src AS jsonb)::text,'UTF8')),'hex'),
        dcompany_paid_bill_revision(CAST(:src AS jsonb)),
        dcompany_paid_bill_blockers(CAST(:src AS jsonb)) ||
        dcompany_paid_bill_allocation_blockers(CAST(:src AS jsonb),:target)"""),
            {"src": encoded, "target": payload.target_total_minor},
        )
    ).one()
    return source, values[0], int(values[1]), list(values[2])


def balances(source):
    paid = sum(int(p["amount_minor"]) for p in source["payments"])
    refunded = sum(int(r["amount_minor"]) for r in source["refunds"])
    return (
        paid,
        refunded,
        paid - refunded - sum(int(c["amount_minor"]) for c in source["corrections"]),
    )


async def review(session, tenant, payload):
    await require_protected_owner(session, tenant)
    order = await scoped_order(session, tenant, payload.order_id)
    source, fingerprint, revision, blockers = await capture(session, tenant, payload)
    paid, refunded, effective = balances(source)
    difference = effective - payload.target_total_minor
    if payload.money_received != "received":
        blockers.append(
            "A refund requires money actually received. Use the separate qualified "
            "no-payment correction when applicable."
        )
    if difference < 0:
        blockers.append(
            "This review supports decreases only; an increase requires a separate "
            "supplemental sale."
        )
    if difference == 0 and not (payload.operation == "discard" and effective == 0 and refunded > 0):
        blockers.append("Choose a lower effective amount.")
    if any(
        a["operation"] == "discard"
        and (
            a["refund_request_id"] is None
            or any(r["request_id"] == a["refund_request_id"] for r in source["refunds"])
        )
        for a in source["amendments"]
    ):
        blockers.append("This receipt is already discarded.")
    choices = list(
        await session.scalars(
            select(Shift.id).where(
                Shift.company_id == tenant.company_id,
                Shift.branch_id == tenant.branch_id,
                Shift.terminal_id == tenant.terminal_id,
                Shift.status == "open",
                Shift.closed_at.is_(None),
            )
        )
    )
    selected = payload.settlement_shift_id is not None
    if difference == 0 and selected:
        blockers.append("An already fully refunded discard has no settlement shift or refund mode.")
    if difference > 0 and selected and not blockers:
        await require_permission(session, tenant, "pos.refund")
        try:
            await prepare_pos_refund_request(
                session,
                tenant=tenant,
                payload=Reservation(
                    order_id=order.id,
                    shift_id=payload.settlement_shift_id,
                    amount_minor=difference,
                    expected_paid_minor=paid,
                    expected_refundable_minor=effective,
                    mode=payload.refund_mode,
                    client_action_id="paid-bill-preview:" + str(uuid4()),
                ),
                idempotency_key="paid-bill-preview:" + str(uuid4()),
                reserve=False,
                amendment_context=True,
            )
            # Lock waits may have changed authoritative task/drawer/source facts.
            source, fingerprint, revision, locked_blockers = await capture(session, tenant, payload)
            blockers.extend(locked_blockers)
        except BusinessRuleError as exc:
            blockers.append(exc.message)
    tender = {c["payment_id"]: c["effective_method"] for c in source["tender_corrections"]}
    methods = {tender.get(p["id"], p["method"]) for p in source["payments"]}
    return {
        "protocol": PAID_BILL_AMENDMENT_PROTOCOL,
        "order_id": str(order.id),
        "invoice_no": order.invoice_no,
        "source_revision": revision,
        "source_fingerprint": fingerprint,
        "original_total_minor": int(order.total_minor),
        "actual_refunded_minor": refunded,
        "effective_total_minor": effective,
        "proposed_total_minor": payload.target_total_minor,
        "proposed_refund_minor": max(0, difference),
        "eligible": not blockers,
        "commit_available": not blockers and (difference == 0 or selected),
        "blockers": blockers,
        "settlement_shift_ids": [str(x) for x in choices],
        "refund_modes": ["cash", "original"] if len(methods) == 1 else ["cash"],
        "settlement_shift_id": str(payload.settlement_shift_id) if selected else None,
        "refund_mode": payload.refund_mode,
        "financial_effect": False,
        "stock_effect": False,
    }


async def _digest(session, body):
    return await session.scalar(
        text("SELECT encode(sha256(convert_to(CAST(:body AS jsonb)::text,'UTF8')),'hex')"),
        {"body": json.dumps(body)},
    )


async def lock_actor(session, tenant):
    await session.scalar(
        select(User.id)
        .where(User.id == tenant.user_id, User.company_id == tenant.company_id)
        .with_for_update(read=True)
    )


async def commit(session, tenant, payload, *, action_key, confirmation, reauthorize):
    if not action_key or action_key.strip() != action_key or len(action_key) > 160:
        raise BusinessRuleError("Keep one saved action key of 1–160 characters.")
    # Revalidate the original access family after the actor-lock wait.
    await lock_actor(session, tenant)
    await reauthorize()
    await require_protected_owner(session, tenant)
    order = await scoped_order(session, tenant, payload.order_id, lock=True)
    await reauthorize()
    await require_confirmation(
        session, tenant, confirmation, branch_id=order.branch_id, scope=PURPOSE
    )
    body = payload.model_dump(mode="json")
    digest = await _digest(session, body)
    prior = await session.scalar(
        select(PaidBillAmendmentIntent).where(
            PaidBillAmendmentIntent.company_id == tenant.company_id,
            PaidBillAmendmentIntent.action_key == action_key,
            PaidBillAmendmentIntent.recorded_by == tenant.user_id,
            PaidBillAmendmentIntent.branch_id == tenant.branch_id,
            PaidBillAmendmentIntent.terminal_id == tenant.terminal_id,
        )
    )
    if prior is not None:
        if (
            prior.recorded_by != tenant.user_id
            or prior.order_id != order.id
            or prior.branch_id != tenant.branch_id
            or prior.terminal_id != tenant.terminal_id
            or prior.request_path != PATH
            or prior.request_hash != digest
            or prior.request_body != body
        ):
            raise IdempotencyConflict(
                "This saved paid-bill action belongs to different details or scope."
            )
        await require_confirmation(
            session, tenant, confirmation, branch_id=prior.branch_id, scope=PURPOSE
        )
        if prior.difference_minor > 0:
            await require_permission(session, tenant, "pos.refund")
        await reauthorize()
        return prior.response_body
    if payload.settlement_shift_id:
        await session.scalar(
            select(Shift.id)
            .where(
                Shift.id == payload.settlement_shift_id,
                Shift.company_id == tenant.company_id,
                Shift.branch_id == order.branch_id,
                Shift.terminal_id == order.terminal_id,
            )
            .with_for_update()
        )
    await session.scalar(
        select(Shift.id)
        .where(Shift.id == order.shift_id, Shift.company_id == tenant.company_id)
        .with_for_update()
    )
    # Match Order/Shift -> Company -> FK parents before customer/mirror reads.
    await session.scalar(
        select(Company.id).where(Company.id == tenant.company_id).with_for_update(read=True)
    )
    await session.scalar(
        select(Branch.id)
        .where(Branch.id == order.branch_id, Branch.company_id == tenant.company_id)
        .with_for_update(read=True, key_share=True)
    )
    await session.scalar(
        select(Terminal.id)
        .where(Terminal.id == order.terminal_id, Terminal.branch_id == order.branch_id)
        .with_for_update(read=True, key_share=True)
    )
    await reauthorize()
    await require_confirmation(
        session, tenant, confirmation, branch_id=order.branch_id, scope=PURPOSE
    )
    quote = await review(session, tenant, payload)
    await reauthorize()
    await require_confirmation(
        session, tenant, confirmation, branch_id=order.branch_id, scope=PURPOSE
    )
    if not quote["commit_available"]:
        raise BusinessRuleError(
            "Paid bill change is not available.", details={"blockers": quote["blockers"]}
        )
    if (
        quote["source_revision"] != payload.source_revision
        or quote["source_fingerprint"] != payload.source_fingerprint
    ):
        raise ConflictError(
            "The reviewed paid bill or settlement context changed. Keep the saved "
            "action and review again."
        )
    source, fingerprint, revision, blockers = await capture(session, tenant, payload)
    paid, refunded, effective = balances(source)
    difference = effective - payload.target_total_minor
    if blockers:
        raise ConflictError("The paid bill source changed while reserving its refund.")
    if difference > 0:
        await require_permission(session, tenant, "pos.refund")
    await reauthorize()
    await require_confirmation(
        session, tenant, confirmation, branch_id=order.branch_id, scope=PURPOSE
    )
    ident = uuid4()
    request_id = uuid4() if difference > 0 else None
    response = {
        "id": str(ident),
        "order_id": str(order.id),
        "revision": revision + 1,
        "refund_request_id": str(request_id) if request_id else None,
        "target_total_minor": payload.target_total_minor,
        "status": "pending_settlement" if request_id else "discarded",
        "financial_effect": False,
    }
    intent = PaidBillAmendmentIntent(
        id=ident,
        company_id=tenant.company_id,
        order_id=order.id,
        branch_id=order.branch_id,
        terminal_id=order.terminal_id,
        original_shift_id=order.shift_id,
        settlement_shift_id=payload.settlement_shift_id,
        recorded_by=tenant.user_id,
        refund_request_id=request_id,
        invoice_no=order.invoice_no,
        operation=payload.operation,
        refund_mode=payload.refund_mode,
        prior_effective_minor=effective,
        target_minor=payload.target_total_minor,
        difference_minor=difference,
        source_revision=revision,
        revision=revision + 1,
        source_fingerprint=fingerprint,
        source_snapshot=source,
        reason=payload.reason,
        action_key=action_key,
        request_path=PATH,
        request_hash=digest,
        request_body=body,
        response_body=response,
        recorded_at=datetime.now(UTC),
    )
    session.add(intent)
    await session.flush()
    if request_id:
        reservation_key = "paid-bill:" + str(ident)
        await prepare_pos_refund_request(
            session,
            tenant=tenant,
            payload=Reservation(
                order_id=order.id,
                shift_id=payload.settlement_shift_id,
                amount_minor=difference,
                expected_paid_minor=paid,
                expected_refundable_minor=effective,
                mode=payload.refund_mode,
                client_action_id=reservation_key,
            ),
            idempotency_key=reservation_key,
            request_id=request_id,
            amendment_context=True,
        )
    await enqueue_google_sheets_event_if_enabled(
        session,
        company_id=tenant.company_id,
        event_type="pos.paid_bill.intent",
        source_type="paid_bill_amendment_intent",
        source_id=str(ident),
        source_revision=str(revision + 1),
        occurred_at=intent.recorded_at,
        payload={
            "intent_id": str(ident),
            "order_id": str(order.id),
            "operation": intent.operation,
            "status": response["status"],
            "financial_effect": False,
            "stock_effect": False,
        },
    )
    await session.flush()
    await reauthorize()
    await require_confirmation(
        session, tenant, confirmation, branch_id=order.branch_id, scope=PURPOSE
    )
    return response


async def current_state(session, tenant, order_id):
    await require_protected_owner(session, tenant)
    order = await scoped_order(session, tenant, order_id)
    from app.services.pos.paid_bill_projection import paid_bill_states

    return (await paid_bill_states(session, company_id=tenant.company_id, order_ids=[order.id]))[
        order.id
    ]
