"""Privacy and accounting contract for membership Google Sheets events."""

from datetime import UTC, datetime
from types import SimpleNamespace
from uuid import UUID

import pytest

from app.api.v1.memberships import router as membership_router
from app.models import Branch, Company, User

COMPANY_ID = UUID("11111111-1111-4111-8111-111111111111")
BRANCH_ID = UUID("22222222-2222-4222-8222-222222222222")
USER_ID = UUID("33333333-3333-4333-8333-333333333333")
SOURCE_ID = UUID("44444444-4444-4444-8444-444444444444")


class _Session:
    async def get(self, model, key):
        if model is Company and key == COMPANY_ID:
            return SimpleNamespace(
                id=key,
                deleted_at=None,
                currency="INR",
            )
        if model is Branch and key == BRANCH_ID:
            return SimpleNamespace(id=key, name="D Company Myladi")
        if model is User and key == USER_ID:
            return SimpleNamespace(id=key, name="Owner")
        raise AssertionError(f"unexpected get({model}, {key})")


@pytest.mark.asyncio
@pytest.mark.parametrize(
    ("event_type", "source_type", "amount_minor", "description"),
    [
        (
            "membership.payment.settled",
            "membership_payment",
            12_000,
            "Membership · Gold",
        ),
        (
            "membership.refund.settled",
            "membership_refund_settlement",
            -12_000,
            "Membership refund · Gold",
        ),
    ],
)
async def test_membership_mirror_is_signed_and_excludes_customer_evidence(
    monkeypatch,
    event_type: str,
    source_type: str,
    amount_minor: int,
    description: str,
) -> None:
    session = _Session()
    captured: dict = {}

    async def _capture(caller_session, **kwargs):
        assert caller_session is session
        captured.update(kwargs)

    monkeypatch.setattr(
        membership_router,
        "enqueue_google_sheets_event_if_enabled",
        _capture,
    )

    await membership_router._enqueue_membership_accounting_mirror(
        session,
        company_id=COMPANY_ID,
        branch_id=BRANCH_ID,
        actor_user_id=USER_ID,
        event_type=event_type,
        source_type=source_type,
        source_id=SOURCE_ID,
        source_revision="settled-v1",
        occurred_at=datetime(2026, 9, 19, 10, 30, tzinfo=UTC),
        receipt_no="MEM/26-27/00001",
        tier_name=description,
        amount_minor=amount_minor,
        payment_method="cash",
        status_label="settled",
        identifiers={"membership_payment_id": str(SOURCE_ID)},
    )

    assert captured["company_id"] == COMPANY_ID
    assert captured["event_type"] == event_type
    assert captured["source_type"] == source_type
    assert captured["source_id"] == str(SOURCE_ID)
    assert captured["source_revision"] == "settled-v1"
    assert captured["payload"] == {
        "branch": "D Company Myladi",
        "reference": "MEM/26-27/00001",
        "description": description,
        "customer": "",
        "quantity": 1,
        "amount_minor": amount_minor,
        "payment_method": "cash",
        "actor": "Owner",
        "status": "settled",
        "currency": "INR",
        "branch_id": str(BRANCH_ID),
        "membership_payment_id": str(SOURCE_ID),
    }
    serialized = str(captured).lower()
    assert "phone" not in serialized
    assert "customer_name" not in serialized
    assert "external_reference" not in serialized
    assert "reason" not in serialized
