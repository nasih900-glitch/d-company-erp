from __future__ import annotations

from uuid import uuid4

import pytest
import pytest_asyncio
from sqlalchemy import text

from app.core.security import issue_access_token


@pytest_asyncio.fixture(autouse=True)
async def require_cleanup_reconciliation_migration(session) -> None:
    try:
        await session.execute(text("select 1 from client_gaming_cleanup_reconciliation limit 1"))
    except Exception as exc:
        pytest.skip(f"cleanup reconciliation migration/local PostgreSQL unavailable: {exc}")


def _headers(seed: dict, *, roles: list[str], audit_access: bool) -> dict[str, str]:
    owner = seed["owner"]
    token = issue_access_token(
        user_id=owner.id,
        company_id=owner.company_id,
        roles=roles,
        branch_id=seed["branch"].id,
        auth_version=owner.auth_version,
        extra={
            "protected_access": "super_owner" in roles,
            "audit_access": audit_access,
        },
    )
    return {
        "Authorization": f"Bearer {token}",
        "X-Terminal-Id": str(seed["terminal"].id),
    }


@pytest.mark.integration
@pytest.mark.asyncio
async def test_cleanup_list_and_approval_are_protected_owner_http_routes(
    client,
    seed_owner,
) -> None:
    target = "/api/v1/client-installations/gaming-cleanup-reconciliations"
    owner_headers = _headers(seed_owner, roles=["super_owner"], audit_access=True)
    staff_headers = _headers(seed_owner, roles=["staff"], audit_access=False)

    denied_list = await client.get(target, headers=staff_headers)
    assert denied_list.status_code == 403
    listing = await client.get(target, headers=owner_headers)
    assert listing.status_code == 200, listing.text
    assert "items" in listing.json()

    approval_target = f"{target}/{uuid4()}/approve"
    payload = {
        "expected_candidate_sha256": "a" * 64,
        "reason": "Reviewed exact immutable cleanup evidence",
    }
    denied_approval = await client.post(
        approval_target,
        headers={**staff_headers, "Idempotency-Key": str(uuid4())},
        json=payload,
    )
    assert denied_approval.status_code == 403
    owner_missing = await client.post(
        approval_target,
        headers={**owner_headers, "Idempotency-Key": str(uuid4())},
        json=payload,
    )
    assert owner_missing.status_code == 422
    assert "current branch" in owner_missing.text
