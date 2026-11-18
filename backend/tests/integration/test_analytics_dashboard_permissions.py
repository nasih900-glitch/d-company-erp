"""The finance-bearing dashboard requires both capabilities; operational reads do not."""
from uuid import uuid4

import pytest

from app.core.security import issue_access_token
from app.models.access_control import RolePermissionOverride
from app.services.reports.aggregator import ReportsAggregator


@pytest.mark.asyncio
async def test_dashboard_requires_analytics_and_finance_without_widening_operational_reads(
    client, session, seed_owner, monkeypatch,
):
    company_id = seed_owner["company"].id
    owner = seed_owner["owner"]
    grants = {
        "analytics_only": (True, False),
        "finance_only": (False, True),
        "ordinary_both": (True, True),
    }
    session.add_all([
        RolePermissionOverride(id=uuid4(), company_id=company_id, role_code=role, module=module, allowed=allowed)
        for role, permissions in grants.items()
        for module, allowed in zip(("insights_reports", "finance"), permissions, strict=True)
    ])
    await session.commit()

    def headers(role):
        token = issue_access_token(
            user_id=owner.id, company_id=company_id, branch_id=seed_owner["branch"].id,
            roles=[role], auth_version=owner.auth_version,
        )
        return {"Authorization": "Bearer " + token, "X-Terminal-Id": str(seed_owner["terminal"].id)}

    aggregate_calls = []
    original = ReportsAggregator.aggregate_daily

    async def record_aggregate(self, *args, **kwargs):
        aggregate_calls.append(kwargs)
        return await original(self, *args, **kwargs)

    monkeypatch.setattr(ReportsAggregator, "aggregate_daily", record_aggregate)
    for role in ("analytics_only", "finance_only", "staff"):
        denied = await client.get("/api/v1/analytics/dashboard", headers=headers(role))
        assert denied.status_code == 403, (role, denied.text)
        assert "net_profit_minor" not in denied.json()
    assert aggregate_calls == []

    for role in ("super_owner", "owner", "ordinary_both"):
        allowed = await client.get("/api/v1/analytics/dashboard", headers=headers(role))
        assert allowed.status_code == 200, (role, allowed.text)
        assert {"cogs_minor", "expense_total_minor", "net_profit_minor"} <= allowed.json().keys()
        assert aggregate_calls[-1]["company_id"] == company_id
        assert aggregate_calls[-1]["branch_id"] == seed_owner["branch"].id
    assert len(aggregate_calls) == 3

    # These endpoints remain analytics-only, including both Overview versions.
    for path, params in (
        ("/analytics/home", {}),
        ("/reports/overview", {}),
        ("/reports/overview", {"schema_version": "reports.overview.v2"}),
        ("/insights/growth", {}),
        ("/insights/top-items", {}),
    ):
        allowed = await client.get("/api/v1" + path, params=params, headers=headers("analytics_only"))
        assert allowed.status_code == 200, (path, allowed.text)
        denied = await client.get("/api/v1" + path, params=params, headers=headers("finance_only"))
        assert denied.status_code == 403, (path, denied.text)
