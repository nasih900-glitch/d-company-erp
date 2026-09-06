"""Only audited reporting reads opt into a stable, read-only transaction."""

import pytest
from fastapi import Request

from app.api.v1.accounting.router import router as accounting
from app.api.v1.analytics.router import router as analytics
from app.api.v1.finance.router import router as finance
from app.api.v1.insights.router import router as insights
from app.api.v1.pos.router import router as pos
from app.api.v1.reports.router import router as reports
from app.core.db import _uses_report_snapshot


@pytest.mark.parametrize("router", [accounting, analytics, finance, insights, reports])
def test_compiled_reporting_get_routes_use_snapshot_but_writes_do_not(router):
    reads = 0
    for route in router.routes:
        for method in route.methods:
            request = Request({"type": "http", "method": method, "route": route})
            assert _uses_report_snapshot(request) is (method == "GET")
            reads += method == "GET"
    assert reads > 0


def test_operational_pos_keeps_existing_transaction_semantics():
    for route in pos.routes:
        for method in route.methods:
            assert not _uses_report_snapshot(
                Request({"type": "http", "method": method, "route": route})
            )
