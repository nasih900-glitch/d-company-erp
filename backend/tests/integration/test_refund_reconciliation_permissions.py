"""Operational-owner financial recovery without audit/system authority."""

from datetime import UTC, datetime
from uuid import uuid4
import pytest
from sqlalchemy import select
from app.core.security import hash_password, issue_access_token
from app.models import Branch, Refund, Role, Terminal, User, UserRole
from tests.integration.test_pos_refund_settlement import (
    _cleanup,
    _headers,
    _request_payload,
    _seed_case,
)


@pytest.mark.asyncio
async def test_operational_owner_can_resolve_failed_provider_refund_but_cashier_cannot(
    client, session, seed_owner
):
    case = await _seed_case(session, seed_owner, payment_method="upi")
    key = f"permission-refund:{uuid4()}"
    accepted = await client.post(
        "/api/v1/pos/refund-requests",
        json=_request_payload(case, action_id=key, mode="original"),
        headers=_headers(case, key),
    )
    assert accepted.status_code == 201, accepted.text
    url = "/api/v1/pos/refund-requests/" + accepted.json()["id"]
    begun = await client.post(
        url + "/begin-provider-payout",
        json={
            "shift_id": str(case.shift_id),
            "expected_amount_minor": case.amount_minor,
            "ready_to_start_provider_payout": True,
        },
        headers=_headers(case, f"begin:{uuid4()}"),
    )
    assert begun.status_code == 201, begun.text
    payload = {
        "shift_id": str(case.shift_id),
        "expected_amount_minor": case.amount_minor,
        "provider_not_completed": True,
        "provider_status": "no_matching_transaction",
        "verification_reference": "verified-provider-case-reaudit",
        "provider_checked_at": datetime.now(UTC).isoformat(),
        "reason": "Provider transaction history checked; no refund paid",
    }
    async def role_headers(code):
        role = (
            await session.execute(
                select(Role).where(
                    Role.company_id == case.company_id,
                    Role.code == code,
                )
            )
        ).scalar_one_or_none()
        if role is None:
            role = Role(
                id=uuid4(),
                company_id=case.company_id,
                code=code,
                name=code,
                permissions=[],
            )
            session.add(role)
            await session.flush()
        password = f"{code}-refund-password"
        user = User(
            id=uuid4(),
            company_id=case.company_id,
            email=f"{code}-refund-{uuid4().hex[:8]}@test.local",
            name=f"{code.title()} Refund Operator",
            password_hash=hash_password(password),
            status="active",
        )
        session.add(user)
        await session.flush()
        session.add(
            UserRole(
                id=uuid4(),
                user_id=user.id,
                role_id=role.id,
                branch_id=case.branch_id,
                granted_by=seed_owner["owner"].id,
            )
        )
        await session.commit()
        login = await client.post(
            "/api/v1/auth/login",
            json={"email": user.email, "password": password},
        )
        assert login.status_code == 200, login.text
        return {
            "Authorization": "Bearer " + login.json()["access_token"],
            "X-Terminal-Id": str(case.terminal_id),
            "Idempotency-Key": f"resolve:{uuid4()}",
        }

    cashier = await role_headers("cashier")
    refused = await client.post(url + "/resolve-provider-payout", json=payload, headers=cashier)
    assert refused.status_code == 403, refused.text
    # A distinct ordinary owner has the exact high-trust permission but is
    # neither the shift opener nor a protected-access bypass account.
    operational_owner = await role_headers("owner")
    me = await client.get("/api/v1/auth/me", headers=operational_owner)
    assert me.status_code == 200, me.text
    assert "pos.refund.reconcile" in me.json()["effective_permissions"]
    assert (
        "admin.system" not in me.json()["effective_permissions"]
        and not me.json()["audit_access"]
        and not me.json()["protected_access"]
    )
    blocked_completion = await client.post(
        url + "/settle-provider",
        json={
            "shift_id": str(case.shift_id),
            "expected_amount_minor": case.amount_minor,
            "provider_completed": True,
            "external_reference": f"must-not-record:{uuid4()}",
            "provider_settled_at": datetime.now(UTC).isoformat(),
        },
        headers=operational_owner
        | {"Idempotency-Key": f"blocked-completion:{uuid4()}"},
    )
    assert blocked_completion.status_code == 422, blocked_completion.text
    assert "Only the staff member who opened this shift" in blocked_completion.text
    invalid = await client.post(
        url + "/resolve-provider-payout",
        json=payload | {"provider_not_completed": False},
        headers=operational_owner | {"Idempotency-Key": f"invalid-resolve:{uuid4()}"},
    )
    assert invalid.status_code == 422, invalid.text
    resolved = await client.post(
        url + "/resolve-provider-payout",
        json=payload,
        headers=operational_owner | {"Idempotency-Key": f"resolved:{uuid4()}"},
    )
    assert resolved.status_code == 201, resolved.text
    assert resolved.json()["status"] == "withdrawn"
    for path in (
        "/pos/refund-evidence-reconciliations",
        "/pos/refund-evidence-reconciliations/pending",
    ):
        assert (
            await client.get("/api/v1" + path, headers=operational_owner)
        ).status_code == 200
    assert (
        await client.get("/api/v1/audit", headers=operational_owner)
    ).status_code in (403, 404)


async def _settle_provider_refund_with_weak_evidence(client, session, case):
    """Create one real immutable Refund that must enter evidence review."""
    request_key = f"branch-scope-request:{uuid4()}"
    begin_key = f"branch-scope-begin:{uuid4()}"
    completion_key = f"branch-scope-complete:{uuid4()}"
    finalize_key = f"branch-scope-finalize:{uuid4()}"
    accepted = await client.post(
        "/api/v1/pos/refund-requests",
        json=_request_payload(case, action_id=request_key, mode="original"),
        headers=_headers(case, request_key),
    )
    assert accepted.status_code == 201, accepted.text
    request_id = accepted.json()["id"]
    begun = await client.post(
        f"/api/v1/pos/refund-requests/{request_id}/begin-provider-payout",
        json={
            "shift_id": str(case.shift_id),
            "expected_amount_minor": case.amount_minor,
            "ready_to_start_provider_payout": True,
        },
        headers=_headers(case, begin_key),
    )
    assert begun.status_code == 201, begun.text
    completed = await client.post(
        f"/api/v1/pos/refund-requests/{request_id}/settle-provider",
        json={
            "shift_id": str(case.shift_id),
            "expected_amount_minor": case.amount_minor,
            "provider_completed": True,
            # Opaque one-character success evidence is retained but explicitly
            # enters the immutable evidence-review queue.
            "external_reference": "X",
            "provider_settled_at": begun.json()["provider_payout_started_at"],
        },
        headers=_headers(case, completion_key),
    )
    assert completed.status_code == 201, completed.text
    finalized = await client.post(
        f"/api/v1/pos/refund-requests/{request_id}/finalize-provider",
        json={
            "shift_id": str(case.shift_id),
            "expected_amount_minor": case.amount_minor,
        },
        headers=_headers(case, finalize_key),
    )
    assert finalized.status_code == 201, finalized.text
    refund = (
        await session.execute(select(Refund).where(Refund.order_id == case.order_id))
    ).scalar_one()
    assert refund.provider_evidence_reconciled is False
    return refund, (request_key, begin_key, completion_key, finalize_key)


@pytest.mark.asyncio
async def test_refund_evidence_permission_does_not_cross_authenticated_branch(
    client, session, seed_owner
):
    """A branch owner cannot discover or reconcile another shop's evidence."""
    second_branch = Branch(
        id=uuid4(),
        company_id=seed_owner["company"].id,
        name=f"Second refund branch {uuid4().hex[:8]}",
        invoice_series_code="R2",
    )
    second_terminal = Terminal(
        id=uuid4(),
        branch_id=second_branch.id,
        name="Second refund terminal",
        purpose="hybrid",
        device_id=f"refund-scope-{uuid4()}",
    )
    session.add_all([second_branch, second_terminal])
    await session.commit()
    second_seed = {
        **seed_owner,
        "branch": second_branch,
        "terminal": second_terminal,
    }
    case = await _seed_case(session, second_seed, payment_method="upi")
    refund = None
    keys: tuple[str, ...] = ()
    try:
        refund, workflow_keys = await _settle_provider_refund_with_weak_evidence(
            client, session, case
        )
        deny_key = f"branch-scope-denied:{uuid4()}"
        audit_key = f"branch-scope-audit:{uuid4()}"
        keys = workflow_keys + (deny_key, audit_key)

        ordinary_token = issue_access_token(
            user_id=seed_owner["owner"].id,
            company_id=seed_owner["company"].id,
            branch_id=seed_owner["branch"].id,
            roles=["owner"],
            auth_version=seed_owner["owner"].auth_version,
        )
        ordinary_headers = {
            "Authorization": f"Bearer {ordinary_token}",
            "X-Terminal-Id": str(seed_owner["terminal"].id),
        }
        me = await client.get("/api/v1/auth/me", headers=ordinary_headers)
        assert me.status_code == 200, me.text
        assert "pos.refund.reconcile" in me.json()["effective_permissions"]
        assert me.json()["audit_access"] is False

        pending = await client.get(
            "/api/v1/pos/refund-evidence-reconciliations/pending",
            headers=ordinary_headers,
        )
        assert pending.status_code == 200, pending.text
        assert all(row["refund_id"] != str(refund.id) for row in pending.json())

        payload = {
            "refund_id": str(refund.id),
            "evidence_kind": "provider_reference",
            "proof_reference": "Provider dashboard branch-two case",
            "reason": "Verified against the second branch provider settlement",
        }
        denied = await client.post(
            "/api/v1/pos/refund-evidence-reconciliations",
            json=payload,
            headers=ordinary_headers | {"Idempotency-Key": deny_key},
        )
        assert denied.status_code == 404, denied.text

        audit_token = issue_access_token(
            user_id=seed_owner["owner"].id,
            company_id=seed_owner["company"].id,
            branch_id=seed_owner["branch"].id,
            roles=["super_owner"],
            auth_version=seed_owner["owner"].auth_version,
            extra={"protected_access": True, "audit_access": True},
        )
        audit_headers = {
            "Authorization": f"Bearer {audit_token}",
            "X-Terminal-Id": str(seed_owner["terminal"].id),
        }
        reconciled = await client.post(
            "/api/v1/pos/refund-evidence-reconciliations",
            json=payload,
            headers=audit_headers | {"Idempotency-Key": audit_key},
        )
        assert reconciled.status_code == 201, reconciled.text

        ordinary_register = await client.get(
            "/api/v1/pos/refund-evidence-reconciliations",
            headers=ordinary_headers,
        )
        assert ordinary_register.status_code == 200, ordinary_register.text
        assert all(
            row["refund_id"] != str(refund.id)
            for row in ordinary_register.json()
        )
        audit_register = await client.get(
            "/api/v1/pos/refund-evidence-reconciliations",
            headers=audit_headers,
        )
        assert audit_register.status_code == 200, audit_register.text
        assert any(row["refund_id"] == str(refund.id) for row in audit_register.json())
    finally:
        if refund is not None:
            await _cleanup(case, keys=keys)
        await session.delete(second_terminal)
        await session.delete(second_branch)
        await session.commit()
