"""Narrow customer identity resolution for new gaming starts."""

from __future__ import annotations

import re
from uuid import UUID, uuid4

from sqlalchemy import and_, case, func, select
from sqlalchemy.exc import IntegrityError

from app.models import Customer

_ALLOWED_PHONE = re.compile(r"^[+0-9\s().-]+$")


def normalize_indian_phone(value: str | None) -> str | None:
    """Return a 10-digit identity for common Indian representations."""

    if not value or not _ALLOWED_PHONE.fullmatch(value.strip()):
        return None
    digits = re.sub(r"\D", "", value)
    if len(digits) == 12 and digits.startswith("91"):
        digits = digits[2:]
    return digits if len(digits) == 10 else None


def _normalized_phone_expression():
    digits = func.regexp_replace(Customer.phone, "[^0-9]", "", "g")
    return case(
        (
            and_(
                Customer.phone.op("~")(r"^[+0-9[:space:]().-]+$"),
                digits.op("~")("^(91)?[0-9]{10}$"),
            ),
            func.right(digits, 10),
        ),
        else_=None,
    )


async def resolve_gaming_customer(
    session,
    *,
    company_id: UUID,
    phone: str | None,
    name: str | None,
) -> Customer | None:
    """Resolve one live identity without changing legacy phone snapshots.

    Invalid numbers and ambiguous normalized duplicates deliberately return
    no customer. Billing still proceeds through the legacy snapshot path.
    """

    normalized = normalize_indian_phone(phone)
    if normalized is None:
        return None

    lock_key = f"gaming-customer:{company_id}:{normalized}"
    await session.execute(
        select(func.pg_advisory_xact_lock(func.hashtextextended(lock_key, 0)))
    )

    async def matches() -> list[Customer]:
        return list(
            (
                await session.execute(
                    select(Customer)
                    .where(
                        Customer.company_id == company_id,
                        Customer.deleted_at.is_(None),
                        _normalized_phone_expression() == normalized,
                    )
                    .order_by(Customer.id)
                    .with_for_update()
                )
            )
            .scalars()
            .all()
        )

    candidates = await matches()
    if len(candidates) != 1:
        if candidates:
            return None
        customer = Customer(
            id=uuid4(),
            company_id=company_id,
            phone=normalized,
            name=name,
        )
        try:
            async with session.begin_nested():
                session.add(customer)
                await session.flush()
        except IntegrityError:
            candidates = await matches()
            if len(candidates) != 1:
                return None
            customer = candidates[0]
    else:
        customer = candidates[0]

    if name and not customer.name:
        customer.name = name
    return customer
