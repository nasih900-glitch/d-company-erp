"""Focused regressions for branch, audit-target, and report-timezone scope."""

from __future__ import annotations

from datetime import UTC, date, datetime
from types import SimpleNamespace
from uuid import uuid4

import pytest
from sqlalchemy.dialects import postgresql

from app.api.v1.admin.router import AUDIT_AREA_ENTITY_TYPES, _access_cell
from app.api.v1.inventory import router as inventory_router
from app.core.errors import NotFoundError
from app.core.tenant import TenantContext
from app.models import RolePermissionOverride
from app.services.audit.recorder import TRACKED, _entity_id
from app.workers.pnl_alerts import _schedule_for_company


class _Result:
    def __init__(self, rows) -> None:
        self.rows = rows

    def scalars(self):
        return self

    def all(self):
        return self.rows


class _Session:
    def __init__(self, rows=()) -> None:
        self.rows = list(rows)
        self.statements = []

    async def execute(self, statement):
        self.statements.append(statement)
        return _Result(self.rows)


def _tenant(*, branch_id=None) -> TenantContext:
    return TenantContext(
        user_id=uuid4(),
        company_id=uuid4(),
        branch_id=branch_id,
        terminal_id=uuid4() if branch_id else None,
        roles=("owner",),
    )


@pytest.mark.asyncio
async def test_batch_listing_is_bound_to_terminal_branch_and_returns_branch_identity() -> None:
    branch_id = uuid4()
    tenant = _tenant(branch_id=branch_id)
    row = SimpleNamespace(
        id=uuid4(),
        ingredient_id=uuid4(),
        branch_id=branch_id,
        received_at=datetime(2026, 8, 27, tzinfo=UTC),
        expires_at=None,
        qty_on_hand=4,
        cost_per_unit_minor=325,
        lot_code="LOT-1",
    )
    session = _Session([row])

    result = await inventory_router.list_batches(session, tenant)

    assert result[0].branch_id == branch_id
    compiled = session.statements[0].compile(dialect=postgresql.dialect())
    sql = str(compiled).upper()
    assert "JOIN BRANCHES ON BRANCHES.ID = BATCHES.BRANCH_ID" in sql
    assert "BRANCHES.COMPANY_ID" in sql
    assert "BATCHES.BRANCH_ID" in sql
    assert branch_id in compiled.params.values()
    assert list(compiled.params.values()).count(tenant.company_id) == 2


@pytest.mark.asyncio
async def test_batch_listing_cannot_widen_a_terminal_to_another_branch() -> None:
    tenant = _tenant(branch_id=uuid4())
    session = _Session()

    with pytest.raises(NotFoundError, match="branch not found"):
        await inventory_router.list_batches(
            session,
            tenant,
            branch_id=uuid4(),
        )

    assert session.statements == []


def test_access_override_is_audited_against_stable_role_module_target() -> None:
    override = RolePermissionOverride(
        id=uuid4(),
        company_id=uuid4(),
        role_code="cashier",
        module="gaming",
        allowed=True,
    )

    assert RolePermissionOverride in TRACKED
    assert _entity_id(override) == "cashier:gaming"
    assert "RolePermissionOverride" in AUDIT_AREA_ENTITY_TYPES["system"]
    assert "Attendance" in AUDIT_AREA_ENTITY_TYPES["staff"]


def test_access_cells_report_partial_permissions_instead_of_overstating_access() -> None:
    cashier_pos = _access_cell(role_code="cashier", module="pos", override=None)
    assert cashier_pos.allowed is True
    assert cashier_pos.access_level == "partial"
    assert cashier_pos.default_access_level == "partial"
    assert "pos.read" in cashier_pos.effective_permissions
    assert "pos.refund" in cashier_pos.unavailable_permissions
    assert "pos.discount.large" in cashier_pos.unavailable_permissions
    assert set(cashier_pos.ceiling_limited_permissions) == {
        "pos.discount.large",
        "pos.refund",
    }

    staff_finance = _access_cell(role_code="staff", module="finance", override=True)
    assert staff_finance.allowed is True
    assert staff_finance.default_allowed is False
    assert staff_finance.default_access_level == "blocked"
    assert staff_finance.access_level == "partial"
    assert staff_finance.effective_permissions == ["finance.read"]
    assert set(staff_finance.unavailable_permissions) == {
        "finance.assets.write",
        "finance.partner.write",
        "finance.write",
    }
    assert staff_finance.ceiling_limited_permissions == staff_finance.unavailable_permissions

    manager_pos = _access_cell(role_code="manager", module="pos", override=None)
    assert manager_pos.access_level == "full"
    assert manager_pos.unavailable_permissions == []
    assert manager_pos.ceiling_limited_permissions == []

    cashier_gaming = _access_cell(role_code="cashier", module="gaming", override=None)
    assert cashier_gaming.access_level == "partial"
    assert set(cashier_gaming.unavailable_permissions) == {
        "gaming.tournament.manage",
        "gaming.write",
    }
    assert cashier_gaming.ceiling_limited_permissions == []


def test_scheduled_reports_use_each_company_timezone_at_calendar_boundary() -> None:
    now = datetime(2026, 8, 31, 23, 30, tzinfo=UTC)

    london_today, london_periods = _schedule_for_company(
        "all_due",
        "Europe/London",
        now=now,
    )
    la_today, la_periods = _schedule_for_company(
        "all_due",
        "America/Los_Angeles",
        now=now,
    )

    assert london_today == date(2026, 9, 1)
    assert "monthly" in london_periods
    assert la_today == date(2026, 8, 31)
    assert "monthly" not in la_periods
    assert "weekly" in la_periods


def test_scheduled_report_as_of_override_is_timezone_independent() -> None:
    as_of = date(2026, 10, 1)
    london = _schedule_for_company("all_due", "Europe/London", as_of=as_of)
    la = _schedule_for_company("all_due", "America/Los_Angeles", as_of=as_of)

    assert london == la
