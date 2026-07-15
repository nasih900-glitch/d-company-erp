"""Regression tests for branch/terminal-safe POS order access."""

from __future__ import annotations

from datetime import UTC, datetime
from uuid import uuid4

import pytest

from app.core.errors import BusinessRuleError, NotFoundError
from app.models import Order
from app.services.pos.order_validation import require_operational_order


def _order(**overrides) -> Order:
    values = {
        "id": uuid4(),
        "company_id": uuid4(),
        "branch_id": uuid4(),
        "terminal_id": uuid4(),
        "shift_id": uuid4(),
        "opened_by": uuid4(),
        "type": "dine_in",
        "status": "held",
        "opened_at": datetime.now(UTC),
    }
    values.update(overrides)
    return Order(**values)


def _validate(order: Order | None, **overrides) -> Order:
    values = {
        "company_id": order.company_id if order else uuid4(),
        "branch_id": order.branch_id if order else uuid4(),
        "terminal_id": order.terminal_id if order else uuid4(),
        "operation": "billing an order",
    }
    values.update(overrides)
    return require_operational_order(order, **values)


def test_exact_company_branch_terminal_order_is_accepted() -> None:
    order = _order()
    assert _validate(order) is order


def test_missing_and_cross_company_orders_do_not_leak_tenant_data() -> None:
    with pytest.raises(NotFoundError, match="Order not found for this company"):
        _validate(None)
    with pytest.raises(NotFoundError, match="Order not found for this company"):
        _validate(_order(), company_id=uuid4())


def test_cross_branch_order_is_rejected_precisely() -> None:
    with pytest.raises(BusinessRuleError, match="different branch"):
        _validate(_order(), branch_id=uuid4())


def test_cross_terminal_order_is_rejected_precisely() -> None:
    with pytest.raises(BusinessRuleError, match="different terminal"):
        _validate(_order(), terminal_id=uuid4())


def test_missing_operational_context_is_rejected() -> None:
    order = _order()
    with pytest.raises(BusinessRuleError, match="no branch assigned"):
        _validate(order, branch_id=None)
    with pytest.raises(BusinessRuleError, match="X-Terminal-Id"):
        _validate(order, terminal_id=None)
