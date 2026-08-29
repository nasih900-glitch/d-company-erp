"""resource_for_path is pure and DB-free — real coverage belongs here, not
only in the Postgres-gated broadcast integration test."""

from __future__ import annotations

from app.services.realtime import RESOURCES, resource_for_path, resources_for_path


def test_every_mapped_resource_is_declared() -> None:
    from app.services.realtime import _PATH_RESOURCE_MAP

    for _substring, resource in _PATH_RESOURCE_MAP:
        assert resource in RESOURCES


def test_attendance_takes_precedence_over_the_bare_staff_row() -> None:
    assert resource_for_path("/api/v1/staff/attendance/clock-in") == "attendance"
    assert resource_for_path("/api/v1/staff/users/abc-123") == "staff"


def test_access_control_is_narrow_and_does_not_swallow_other_admin_routes() -> None:
    assert resource_for_path("/api/v1/admin/access-control") == "access_control"
    assert resource_for_path("/api/v1/admin/pricing/unlock") is None
    assert resource_for_path("/api/v1/admin/audit") is None


def test_public_auth_audit_writes_have_explicit_realtime_contracts() -> None:
    assert "audit" in RESOURCES
    for path in (
        "/api/v1/auth/login",
        "/api/v1/auth/register/confirm",
        "/api/v1/auth/password-reset/confirm",
    ):
        assert resource_for_path(path) == "audit"
        assert resources_for_path(path) == ("audit",)


def test_bug_report_writes_notify_support_and_reporter_views() -> None:
    assert resource_for_path("/api/v1/bug-reports") == "bug_reports"
    assert resource_for_path(
        "/api/v1/bug-reports/9c846314-7df0-4bf5-a310-712f56db697f/public-replies"
    ) == "bug_reports"


def test_new_phase_2_resources_all_resolve() -> None:
    cases = {
        "/api/v1/menu/items": "menu",
        "/api/v1/customers/abc": "customers",
        "/api/v1/inventory/ingredients": "inventory",
        "/api/v1/finance/expenses": "finance",
        "/api/v1/events/upcoming": "events",
        "/api/v1/memberships/tiers": "memberships",
        "/api/v1/ocr/uploads": "ocr",
    }
    for path, expected in cases.items():
        assert resource_for_path(path) == expected


def test_read_only_aggregate_paths_deliberately_have_no_resource() -> None:
    for path in (
        "/api/v1/reports/daily",
        "/api/v1/analytics/dashboard",
        "/api/v1/insights/growth",
        "/api/v1/accounting/trial-balance",
    ):
        assert resource_for_path(path) is None


def test_membership_money_write_invalidates_every_affected_register() -> None:
    assert resources_for_path("/api/v1/memberships/subscribe") == (
        "memberships",
        "shifts",
        "customers",
        "finance",
        "audit",
    )


def test_pos_payment_invalidates_shift_customer_finance_and_inventory() -> None:
    assert resources_for_path("/api/v1/pos/orders/order-123/payments") == (
        "orders",
        "receipts",
        "tables",
        "kitchen",
        "shifts",
        "customers",
        "finance",
        "inventory",
        "audit",
    )


def test_zero_total_completion_invalidates_customer_finance_inventory_not_shift() -> None:
    assert resources_for_path("/api/v1/pos/orders/order-123/finalize-zero/") == (
        "orders",
        "receipts",
        "tables",
        "kitchen",
        "customers",
        "finance",
        "inventory",
        "audit",
    )


def test_unpaid_pos_order_edits_do_not_overinvalidate_customer_or_finance_reads() -> None:
    ordinary_order_writes = (
        "/api/v1/pos/orders",
        "/api/v1/pos/orders/order-123/lines",
        "/api/v1/pos/orders/order-123/customer",
        "/api/v1/pos/orders/order-123/discount",
        "/api/v1/pos/orders/order-123/points",
        "/api/v1/pos/orders/order-123/reward",
        "/api/v1/pos/orders/order-123/send-to-pos",
        "/api/v1/pos/orders/order-123/checkout-claim",
        "/api/v1/pos/orders/order-123/payments-preview",
    )
    for path in ordinary_order_writes:
        assert resources_for_path(path) == (
            "orders",
            "tables",
            "kitchen",
            "audit",
        )


def test_pos_refund_completion_keeps_existing_money_invalidation_convention() -> None:
    assert resources_for_path(
        "/api/v1/pos/refund-requests/refund-123/finalize-provider"
    ) == ("orders", "receipts", "shifts", "customers", "finance", "audit")


def test_customer_spend_reconciliation_invalidates_customer_and_finance_reads() -> None:
    assert resources_for_path(
        "/api/v1/pos/customer-spend-reconciliations"
    ) == ("customers", "finance", "audit")


def test_gaming_pos_handoffs_refresh_both_session_and_held_order_queues() -> None:
    for path in (
        "/api/v1/gaming/sessions/session-123/send-to-pos",
        "/api/v1/gaming/sessions/session-123/reconcile-to-pos",
    ):
        assert resources_for_path(path) == ("gaming", "orders", "audit")


def test_receipt_history_is_a_declared_realtime_resource() -> None:
    assert "receipts" in RESOURCES
    assert resource_for_path("/api/v1/pos/receipts") == "receipts"


def test_inventory_receipt_invalidates_finance_but_catalog_edits_do_not() -> None:
    assert resources_for_path("/api/v1/inventory/grn") == (
        "inventory",
        "finance",
        "audit",
    )
    assert resources_for_path("/api/v1/inventory/ingredients/ingredient-123") == (
        "inventory",
        "audit",
    )


def test_every_mapped_business_write_includes_audit_invalidation() -> None:
    from app.services.realtime import _PATH_RESOURCE_MAP

    for path_fragment, _resource in _PATH_RESOURCE_MAP:
        assert "audit" in resources_for_path(f"/api/v1{path_fragment}")
