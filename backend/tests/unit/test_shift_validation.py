"""Regression tests for branch/terminal-safe operational shifts."""

from __future__ import annotations

from datetime import UTC, datetime
from uuid import uuid4

import pytest

from app.core.errors import BusinessRuleError, NotFoundError
from app.models import Shift
from app.services.pos.shift_validation import (
    require_open_operational_shift,
    require_operational_shift_scope,
    require_shift_opener,
)


def _shift(**overrides) -> Shift:
    values = {
        "id": uuid4(),
        "company_id": uuid4(),
        "branch_id": uuid4(),
        "terminal_id": uuid4(),
        "opened_by": uuid4(),
        "opened_at": datetime.now(UTC),
        "opening_float_minor": 0,
        "expected_minor": 0,
        "status": "open",
    }
    values.update(overrides)
    return Shift(**values)


def _validate(shift: Shift | None, **overrides) -> Shift:
    values = {
        "company_id": shift.company_id if shift else uuid4(),
        "branch_id": shift.branch_id if shift else uuid4(),
        "terminal_id": shift.terminal_id if shift else uuid4(),
        "operation": "starting a gaming session",
    }
    values.update(overrides)
    return require_open_operational_shift(shift, **values)


def test_exact_company_branch_terminal_open_shift_is_accepted() -> None:
    shift = _shift()
    assert _validate(shift) is shift


def test_exact_scope_validator_can_replay_a_closed_shift() -> None:
    shift = _shift(status="closed")
    assert require_operational_shift_scope(
        shift,
        company_id=shift.company_id,
        branch_id=shift.branch_id,
        terminal_id=shift.terminal_id,
        operation="replaying shift close",
    ) is shift


def test_missing_and_cross_company_shifts_do_not_leak_tenant_data() -> None:
    with pytest.raises(NotFoundError, match="Shift not found for this company"):
        _validate(None)

    shift = _shift()
    with pytest.raises(NotFoundError, match="Shift not found for this company"):
        _validate(shift, company_id=uuid4())


@pytest.mark.parametrize("status", ["closed", "reconciled"])
def test_non_open_shift_has_precise_status_error(status: str) -> None:
    shift = _shift(status=status)
    with pytest.raises(BusinessRuleError, match=rf"Shift is {status}"):
        _validate(shift)


def test_station_branch_mismatch_is_not_reported_as_missing_shift() -> None:
    shift = _shift()
    with pytest.raises(
        BusinessRuleError,
        match="Shift branch does not match the gaming station branch",
    ):
        _validate(
            shift,
            resource_branch_id=uuid4(),
            resource_name="gaming station",
        )


def test_current_branch_mismatch_is_precise() -> None:
    shift = _shift()
    with pytest.raises(BusinessRuleError, match="Shift belongs to a different branch"):
        _validate(shift, branch_id=uuid4())


def test_current_terminal_mismatch_is_precise() -> None:
    shift = _shift()
    with pytest.raises(BusinessRuleError, match="Shift belongs to a different terminal"):
        _validate(shift, terminal_id=uuid4())


def test_missing_terminal_is_rejected_before_an_operational_write() -> None:
    shift = _shift()
    with pytest.raises(BusinessRuleError, match="X-Terminal-Id header required"):
        _validate(shift, terminal_id=None)


def test_missing_branch_is_rejected_before_an_operational_write() -> None:
    shift = _shift()
    with pytest.raises(BusinessRuleError, match="account has no branch assigned"):
        _validate(shift, branch_id=None)


class TestRequireShiftOpener:
    def test_the_opener_may_act_on_their_own_shift(self) -> None:
        opener_id = uuid4()
        shift = _shift(opened_by=opener_id)
        require_shift_opener(
            shift, user_id=opener_id, protected_access=False, operation="bill an order",
        )  # must not raise

    def test_a_different_staff_member_is_rejected(self) -> None:
        shift = _shift(opened_by=uuid4())
        with pytest.raises(BusinessRuleError, match="Only the staff member who opened this shift"):
            require_shift_opener(
                shift, user_id=uuid4(), protected_access=False, operation="bill an order",
            )

    def test_protected_owner_overrides_regardless_of_opener(self) -> None:
        shift = _shift(opened_by=uuid4())
        require_shift_opener(
            shift, user_id=uuid4(), protected_access=True, operation="bill an order",
        )  # must not raise
