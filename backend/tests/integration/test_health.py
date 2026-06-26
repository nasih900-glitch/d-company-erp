import pytest


@pytest.mark.asyncio
async def test_healthz(client) -> None:
    r = await client.get("/healthz")
    assert r.status_code == 200
    assert r.json() == {"status": "ok"}


@pytest.mark.asyncio
async def test_openapi_renders(client) -> None:
    r = await client.get("/openapi.json")
    assert r.status_code == 200
    spec = r.json()
    # Every module should register at least one route.
    paths = list(spec.get("paths", {}).keys())
    for prefix in [
        "/api/v1/auth/login",
        "/api/v1/pos/orders",
        "/api/v1/tables",
        "/api/v1/menu/items",
        "/api/v1/inventory/ingredients",
        "/api/v1/gaming/stations",
        "/api/v1/finance/expenses",
        "/api/v1/ocr/uploads",
        "/api/v1/staff/users",
        "/api/v1/analytics/dashboard",
        "/api/v1/admin/audit",
    ]:
        assert any(p.startswith(prefix) for p in paths), f"missing route prefix {prefix}"
