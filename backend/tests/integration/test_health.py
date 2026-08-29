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

    me_schema = spec["components"]["schemas"]["MeResponse"]
    assert me_schema["properties"]["release_control_access"]["type"] == "boolean"
    assert "release_control_access" in me_schema["required"]
    for release_path in [
        "/api/v1/client-updates/android/releases",
        "/api/v1/client-updates/android/releases/{release_id}/activate",
        "/api/v1/client-updates/android/releases/{release_id}/withdraw",
    ]:
        assert release_path in spec["paths"]

    release_schema = spec["components"]["schemas"]["AndroidReleaseRead"]
    for field in (
        "source_git_sha",
        "source_release_ref",
        "source_workflow_run_id",
        "source_workflow_run_attempt",
    ):
        assert field in release_schema["required"]
    assert release_schema["properties"]["source_workflow_run_id"]["type"] == "string"
    assert release_schema["properties"]["source_workflow_run_attempt"]["type"] == "integer"
