"""resource_for_path is pure and DB-free — real coverage belongs here, not
only in the Postgres-gated broadcast integration test."""

from __future__ import annotations

from app.services.realtime import RESOURCES, resource_for_path


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
