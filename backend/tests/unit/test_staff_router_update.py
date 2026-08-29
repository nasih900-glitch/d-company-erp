"""Database-free regression for update_user's auth_version bump.

Covers a real bug found while reviewing the native Android app's offline
Staff outbox (Phase 8): update_user bumped auth_version whenever `status` or
`role_code` was merely *present* in the PATCH body, regardless of whether it
actually differed from the user's current value. auth_version is the
session-eviction counter (see tenant.py) — every live JWT for that user is
invalidated the instant it changes. Both the web app's edit form and the
native offline outbox always resend the row's current status/role on every
save (an absolute-PATCH, not a diff), so editing nothing but e.g. a coworker's
phone number silently force-logged them out mid-shift, twice over (status
bump + role bump), on every single edit. Same database-free style as
test_menu_router_create_race.py.
"""

from __future__ import annotations

from datetime import datetime, timezone
from uuid import UUID, uuid4

import pytest

from app.api.v1.staff import router as staff_router
from app.core.tenant import TenantContext
from app.models import User


class _Result:
    def __init__(self, *, rows: list | None = None, scalar=None) -> None:
        self._rows = rows or []
        self._scalar = scalar

    def scalars(self):
        return self

    def all(self):
        return self._rows

    def scalar_one_or_none(self):
        return self._scalar


class _Session:
    """Only what update_user touches: its locked User lookup and role queries."""

    def __init__(self, user: User, *, current_role_codes: list[str]) -> None:
        self._user = user
        self._current_role_codes = current_role_codes
        self.flush_count = 0

    async def execute(self, statement):
        if statement.column_descriptions[0].get("entity") is User:
            return _Result(scalar=self._user)
        return _Result(rows=list(self._current_role_codes))

    async def flush(self) -> None:
        self.flush_count += 1


def _tenant(*, user_id: UUID | None = None, company_id: UUID) -> TenantContext:
    return TenantContext(
        user_id=user_id or uuid4(),
        company_id=company_id,
        branch_id=uuid4(),
        terminal_id=uuid4(),
        roles=("owner",),
    )


def _user(*, company_id: UUID, status: str = "active", auth_version: int = 3) -> User:
    return User(
        id=uuid4(),
        company_id=company_id,
        email="cashier@example.com",
        password_hash="x",
        name="Cashier One",
        phone="+911234567890",
        status=status,
        auth_version=auth_version,
        last_login_at=datetime.now(timezone.utc),
    )


@pytest.mark.asyncio
async def test_resubmitting_the_same_status_and_role_does_not_bump_auth_version() -> None:
    """The core bug: an edit that resends the user's current, unchanged
    status/role (exactly what both the web form and the native offline
    outbox do on every save) must not evict that user's live sessions."""
    company_id = uuid4()
    u = _user(company_id=company_id, status="active", auth_version=3)
    session = _Session(u, current_role_codes=["cashier"])
    tenant = _tenant(company_id=company_id)

    await staff_router.update_user(
        u.id,
        staff_router.UserUpdate(name="Cashier One Fixed", status="active", role_code="cashier"),
        session,
        tenant,
    )

    assert u.auth_version == 3, "unchanged status/role must not evict the user's live sessions"
    assert u.name == "Cashier One Fixed"
    assert session.flush_count == 1


@pytest.mark.asyncio
async def test_actually_suspending_a_user_still_bumps_auth_version() -> None:
    """The fix must not neuter the real eviction case — a genuine status
    change is exactly when kicking the user out is the point."""
    company_id = uuid4()
    u = _user(company_id=company_id, status="active", auth_version=3)
    session = _Session(u, current_role_codes=["cashier"])
    tenant = _tenant(company_id=company_id)

    await staff_router.update_user(
        u.id,
        staff_router.UserUpdate(status="suspended"),
        session,
        tenant,
    )

    assert u.auth_version == 4
    assert u.status == "suspended"


@pytest.mark.asyncio
async def test_phone_can_be_cleared_without_conflating_omitted_field() -> None:
    company_id = uuid4()
    u = _user(company_id=company_id)
    session = _Session(u, current_role_codes=["cashier"])
    tenant = _tenant(company_id=company_id)

    await staff_router.update_user(
        u.id,
        staff_router.UserUpdate(name="Only Name Changed"),
        session,
        tenant,
    )
    assert u.phone == "+911234567890"

    await staff_router.update_user(
        u.id,
        staff_router.UserUpdate(phone="   "),
        session,
        tenant,
    )
    assert u.phone is None


def test_explicit_null_phone_is_retained_as_a_clear_operation() -> None:
    payload = staff_router.UserUpdate(phone=None)

    assert "phone" in payload.model_fields_set
    assert payload.phone is None
