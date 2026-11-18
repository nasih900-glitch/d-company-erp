"""Shared POS refund reservation. Accepting a request never records a payout."""

from __future__ import annotations

from datetime import UTC, datetime
from typing import TYPE_CHECKING, Protocol
from uuid import UUID, uuid4

from sqlalchemy import func, select

from app.core.errors import BusinessRuleError
from app.models import (
    Customer,
    CustomerMembership,
    MembershipPayment,
    MembershipRefund,
    MembershipRefundResolution,
    MembershipRefundSettlement,
    Order,
    Payment,
    PosRefundRequest,
    PosRefundWithdrawal,
    Refund,
    Shift,
)
from app.services.finance.tender_corrections import corrections_for_payments
from app.services.pos.order_validation import require_operational_order
from app.services.pos.receipt_correction_projection import require_uncorrected_receipt
from app.services.pos.shift_validation import (
    require_open_operational_shift,
    require_operational_shift_scope,
    require_shift_opener,
)

if TYPE_CHECKING:
    from app.core.tenant import TenantContext


class RefundReservation(Protocol):
    order_id: UUID
    shift_id: UUID
    amount_minor: int
    reason_code: str
    expected_paid_minor: int
    expected_refundable_minor: int
    mode: str
    client_action_id: str
    note: str | None


async def refunded_total(session, order_id: UUID) -> int:
    return int(
        (
            await session.execute(
                select(func.coalesce(func.sum(Refund.amount_minor), 0)).where(
                    Refund.order_id == order_id
                )
            )
        ).scalar_one()
        or 0
    )


async def unresolved_pos_refund_total(
    session,
    *,
    order_id: UUID | None = None,
    shift_id: UUID | None = None,
    customer_id: UUID | None = None,
    cash_only: bool = False,
    exclude_request_id: UUID | None = None,
) -> int:
    """Return accepted POS refunds that have neither settled nor withdrawn."""
    stmt = (
        select(func.coalesce(func.sum(PosRefundRequest.amount_minor), 0))
        .outerjoin(Refund, Refund.request_id == PosRefundRequest.id)
        .outerjoin(
            PosRefundWithdrawal,
            PosRefundWithdrawal.refund_request_id == PosRefundRequest.id,
        )
        .where(Refund.id.is_(None), PosRefundWithdrawal.id.is_(None))
    )
    if order_id is not None:
        stmt = stmt.where(PosRefundRequest.order_id == order_id)
    if shift_id is not None:
        stmt = stmt.where(PosRefundRequest.shift_id == shift_id)
    if cash_only:
        stmt = stmt.where(PosRefundRequest.settlement_method == "cash")
    if customer_id is not None:
        stmt = stmt.join(Order, Order.id == PosRefundRequest.order_id).where(
            Order.customer_id == customer_id
        )
    if exclude_request_id is not None:
        stmt = stmt.where(PosRefundRequest.id != exclude_request_id)
    return int((await session.execute(stmt)).scalar_one() or 0)


async def unresolved_membership_cash_refund_total(
    session,
    *,
    shift_id: UUID,
) -> int:
    """Reserve the same physical drawer for membership and POS payouts."""
    return int(
        (
            await session.execute(
                select(func.coalesce(func.sum(MembershipRefund.amount_minor), 0))
                .outerjoin(
                    MembershipRefundSettlement,
                    MembershipRefundSettlement.refund_id == MembershipRefund.id,
                )
                .outerjoin(
                    MembershipRefundResolution,
                    MembershipRefundResolution.refund_id == MembershipRefund.id,
                )
                .where(
                    MembershipRefund.shift_id == shift_id,
                    MembershipRefund.method == "cash",
                    MembershipRefundSettlement.id.is_(None),
                    MembershipRefundResolution.id.is_(None),
                )
            )
        ).scalar_one()
        or 0
    )


async def unresolved_membership_refund_total_for_customer(
    session,
    *,
    customer_id: UUID,
) -> int:
    """Reserve customer LTV for every accepted membership refund rail."""
    return int(
        (
            await session.execute(
                select(func.coalesce(func.sum(MembershipRefund.amount_minor), 0))
                .join(
                    MembershipPayment,
                    MembershipPayment.id == MembershipRefund.payment_id,
                )
                .join(
                    CustomerMembership,
                    CustomerMembership.id == MembershipPayment.membership_id,
                )
                .outerjoin(
                    MembershipRefundSettlement,
                    MembershipRefundSettlement.refund_id == MembershipRefund.id,
                )
                .outerjoin(
                    MembershipRefundResolution,
                    MembershipRefundResolution.refund_id == MembershipRefund.id,
                )
                .where(
                    CustomerMembership.customer_id == customer_id,
                    MembershipRefundSettlement.id.is_(None),
                    MembershipRefundResolution.id.is_(None),
                )
            )
        ).scalar_one()
        or 0
    )


async def prepare_pos_refund_request(
    session,
    *,
    tenant: TenantContext,
    payload: RefundReservation,
    idempotency_key: str,
    request_id: UUID | None = None,
    reserve: bool = True,
    amendment_context: bool = False,
) -> tuple[PosRefundRequest, Order]:
    if tenant.branch_id is None or tenant.terminal_id is None:
        raise BusinessRuleError(
            "Select the original branch and terminal before reserving a refund."
        )
    clean_reason_code = payload.reason_code.strip()
    if not clean_reason_code:
        raise BusinessRuleError("Choose a refund reason before authorising the payout.")
    order = (
        await session.execute(
            select(Order)
            .where(
                Order.id == payload.order_id,
                Order.company_id == tenant.company_id,
                Order.branch_id == tenant.branch_id,
                Order.terminal_id == tenant.terminal_id,
            )
            .with_for_update()
        )
    ).scalar_one_or_none()
    order = require_operational_order(
        order,
        company_id=tenant.company_id,
        branch_id=tenant.branch_id,
        terminal_id=tenant.terminal_id,
        operation="requesting a POS refund",
    )
    await require_uncorrected_receipt(
        session,
        company_id=tenant.company_id,
        order_id=order.id,
    )
    if order.status not in {"paid", "refunded"}:
        raise BusinessRuleError("Only a paid order can be refunded.")
    if (
        order.closed_at is None
        or order.invoice_issued_at is None
        or not (order.invoice_no or "").strip()
        or not (order.fiscal_year or "").strip()
    ):
        raise BusinessRuleError(
            "This paid order is missing immutable invoice evidence. Ask a protected "
            "owner to reconcile the sale before refunding it. No refund task or cash "
            "handover was created."
        )

    # The order lock serializes both duplicate client_action_id recovery and
    # different-key partial refunds, preventing over-reservation.
    existing_action = (
        await session.execute(
            select(PosRefundRequest).where(
                PosRefundRequest.company_id == tenant.company_id,
                PosRefundRequest.client_action_id == payload.client_action_id,
            )
        )
    ).scalar_one_or_none()
    if existing_action is not None:
        replay_settlement_method = existing_action.settlement_method
        if (
            existing_action.order_id != payload.order_id
            or existing_action.shift_id != payload.shift_id
            or int(existing_action.amount_minor) != payload.amount_minor
            or existing_action.mode != payload.mode
            or existing_action.reason_code != clean_reason_code
            or existing_action.settlement_method != replay_settlement_method
            or int(existing_action.order_paid_snapshot_minor) != payload.expected_paid_minor
            or int(existing_action.order_refundable_snapshot_minor)
            != payload.expected_refundable_minor
            or (existing_action.note or None) != (payload.note or None)
        ):
            raise BusinessRuleError(
                "This refund action ID was already used with different details. "
                "Open the existing refund task instead of creating another."
            )
        return existing_action, order

    if not amendment_context:
        from app.models import PaidBillAmendmentIntent

        if await session.scalar(
            select(PaidBillAmendmentIntent.id)
            .where(
                PaidBillAmendmentIntent.company_id == tenant.company_id,
                PaidBillAmendmentIntent.order_id == order.id,
            )
            .limit(1)
        ):
            raise BusinessRuleError(
                "This bill has paid-bill amendment history. "
                "Use its current Owner change-bill flow; "
                "existing linked refund tasks remain recoverable."
            )

    shift = (
        await session.execute(
            select(Shift)
            .where(
                Shift.id == payload.shift_id,
                Shift.company_id == tenant.company_id,
                Shift.branch_id == tenant.branch_id,
                Shift.terminal_id == tenant.terminal_id,
            )
            .with_for_update()
        )
    ).scalar_one_or_none()
    shift = require_open_operational_shift(
        shift,
        company_id=tenant.company_id,
        branch_id=tenant.branch_id,
        terminal_id=tenant.terminal_id,
        operation="requesting this POS refund",
    )
    require_shift_opener(
        shift,
        user_id=tenant.user_id,
        protected_access=tenant.protected_access,
        operation="request this POS refund on the current shift",
    )
    original_shift = (
        await session.execute(
            select(Shift)
            .where(
                Shift.id == order.shift_id,
                Shift.company_id == tenant.company_id,
                Shift.branch_id == tenant.branch_id,
                Shift.terminal_id == tenant.terminal_id,
            )
            .with_for_update()
        )
    ).scalar_one_or_none()
    original_shift = require_operational_shift_scope(
        original_shift,
        company_id=tenant.company_id,
        branch_id=tenant.branch_id,
        terminal_id=tenant.terminal_id,
        operation="requesting a refund for this order",
        resource_branch_id=order.branch_id,
        resource_name="order",
    )
    require_shift_opener(
        original_shift,
        user_id=tenant.user_id,
        protected_access=tenant.protected_access,
        operation="refund an order from its original shift",
    )

    payment_rows = (
        (
            await session.execute(
                select(Payment).where(Payment.order_id == order.id).order_by(Payment.paid_at)
            )
        )
        .scalars()
        .all()
    )
    paid_total = sum(int(row.amount_minor) for row in payment_rows)
    settled_total = await refunded_total(session, order.id)
    reserved_total = await unresolved_pos_refund_total(session, order_id=order.id)
    refundable_minor = paid_total - settled_total - reserved_total
    if payload.expected_paid_minor != paid_total:
        raise BusinessRuleError(
            "The order's paid total changed. Refresh the refund screen before "
            "authorising any payout."
        )
    if payload.expected_refundable_minor != refundable_minor:
        raise BusinessRuleError(
            "The refundable balance changed, possibly because another employee "
            "started a refund. Refresh and review the pending task."
        )
    if refundable_minor <= 0:
        raise BusinessRuleError("This order has no unreserved refundable balance.")
    if payload.amount_minor > refundable_minor:
        raise BusinessRuleError("Refund amount exceeds the available paid balance.")

    settlement_method = "cash"
    if payload.mode == "original":
        corrections = await corrections_for_payments(session, [row.id for row in payment_rows])
        methods = {
            corrections[row.id].effective_method if row.id in corrections else row.method
            for row in payment_rows
        }
        if len(methods) != 1:
            raise BusinessRuleError(
                "Mixed-payment orders need an explicit cash refund. Review the "
                "payment history before continuing."
            )
        settlement_method = next(iter(methods))
    server_now = datetime.now(UTC)
    if settlement_method == "cash":
        pos_reserved = await unresolved_pos_refund_total(session, shift_id=shift.id, cash_only=True)
        membership_reserved = await unresolved_membership_cash_refund_total(
            session, shift_id=shift.id
        )
        available_drawer = int(shift.expected_minor or 0) - pos_reserved - membership_reserved
        if payload.amount_minor > available_drawer:
            raise BusinessRuleError(
                "This shift does not have enough expected drawer cash after other "
                "accepted refunds. Settle or withdraw the earlier tasks, use the "
                "correct shift, or refund to the original non-cash rail."
            )

    if order.customer_id is not None:
        customer = await session.scalar(
            select(Customer)
            .where(Customer.id == order.customer_id, Customer.company_id == tenant.company_id)
            .with_for_update()
        )
        if customer is None or customer.company_id != tenant.company_id:
            raise BusinessRuleError(
                "The order's customer link is invalid. A protected owner must repair "
                "it before any refund is authorised."
            )
        pending_customer_refunds = await unresolved_pos_refund_total(
            session, customer_id=customer.id
        )
        pending_membership_refunds = await unresolved_membership_refund_total_for_customer(
            session, customer_id=customer.id
        )
        if int(customer.total_spent_minor or 0) < (
            pending_customer_refunds + pending_membership_refunds + payload.amount_minor
        ):
            raise BusinessRuleError(
                "Customer lifetime spend is inconsistent with pending refunds. A "
                "protected owner must reconcile LTV before authorising another payout."
            )

    refund_request = PosRefundRequest(
        id=request_id or uuid4(),
        company_id=tenant.company_id,
        order_id=order.id,
        branch_id=shift.branch_id,
        terminal_id=shift.terminal_id,
        shift_id=shift.id,
        approved_by=tenant.user_id,
        # The authenticated actor is the only approval provenance until a
        # separate, server-authenticated two-person approval flow exists.
        manager_override_user_id=None,
        reason_code=clean_reason_code,
        amount_minor=payload.amount_minor,
        mode=payload.mode,
        settlement_method=settlement_method,
        order_paid_snapshot_minor=paid_total,
        order_refundable_snapshot_minor=refundable_minor,
        accepted_at=server_now,
        external_reference=None,
        provider_settled_at=None,
        client_action_id=payload.client_action_id,
        idempotency_key=idempotency_key,
        note=payload.note,
    )
    if reserve:
        session.add(refund_request)
        await session.flush()
    return refund_request, order
