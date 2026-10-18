"""Real PostgreSQL proof for semantic Audit reads and append-only evidence."""

from datetime import UTC, datetime
from uuid import uuid4

import pytest
from sqlalchemy import insert, select, text
from sqlalchemy.exc import IntegrityError

from app.core.roles import PROTECTED_OWNER_ROLE
from app.core.security import issue_access_token, issue_audit_token
from app.models import AuditLog, Ingredient, Role, UserRole
from app.services.audit.catalogue import describe
from app.services.audit.recorder import clear_actor, install_audit_listeners, set_actor


async def protected_headers(session, seed):
    role = Role(
        id=uuid4(),
        company_id=seed["company"].id,
        code=PROTECTED_OWNER_ROLE,
        name="Protected owner",
        permissions=[],
    )
    session.add(role)
    assignment = await session.scalar(select(UserRole).where(UserRole.user_id == seed["owner"].id))
    await session.flush()
    assignment.role_id = role.id
    await session.commit()
    await session.refresh(seed["owner"])
    return {
        "Authorization": "Bearer "
        + issue_access_token(
            user_id=seed["owner"].id,
            company_id=seed["company"].id,
            roles=[PROTECTED_OWNER_ROLE],
            auth_version=seed["owner"].auth_version,
        ),
        "X-Audit-Token": issue_audit_token(user_id=seed["owner"].id, company_id=seed["company"].id),
    }


async def legacy_row(
    session,
    company_id,
    *,
    ref="GRN-TEST",
    qty="2",
    created_at=None,
    actor=None,
    entity="StockMovement",
    action="create",
    after=None,
):
    result = await session.execute(
        insert(AuditLog)
        .values(
            company_id=company_id,
            actor_user_id=actor,
            action=action,
            entity_type=entity,
            entity_id=str(uuid4()),
            after=after
            if after is not None
            else {
                "type": "adjustment",
                "batch_id": "batch-test",
                "qty_delta": qty,
                "reference": ref,
                "name": "Water",
            },
            created_at=created_at or datetime(2026, 10, 18, 10, tzinfo=UTC),
        )
        .returning(AuditLog.id)
    )
    return result.scalar_one()


@pytest.mark.asyncio
async def test_semantic_read_filters_snapshot_legacy_compatibility_and_scope(
    client, session, seed_owner
):
    install_audit_listeners()
    headers = await protected_headers(session, seed_owner)
    company, owner = seed_owner["company"].id, seed_owner["owner"].id
    ids = [await legacy_row(session, company, ref=f"ADJ-TEST-{i}", actor=owner) for i in range(3)]
    foreign = await legacy_row(session, uuid4(), ref="FOREIGN", actor=owner)
    await session.commit()
    params = {
        "code": "STK-ADJ",
        "q": "Water",
        "limit": 2,
        "date_from": "2026-10-18",
        "date_to": "2026-10-18",
        "actor_user_id": str(owner),
        "outcome": "completed",
    }
    first = await client.get("/api/v1/admin/audit/events", headers=headers, params=params)
    assert first.status_code == 200, first.text
    page = first.json()
    assert page["scope"] == "company_wide_protected_owner"
    assert page["matching_count"] == 3
    assert page["loaded_count"] == 2
    assert page["has_more"]
    assert [row["id"] for row in page["entries"]] == ids[-1:0:-1]
    assert page["entries"][0]["classification_source"] == "derived_legacy"
    assert page["entries"][0]["business_details"]["qty_delta"] == "2"
    assert page["shop_timezone"] == "Asia/Kolkata"
    newer = await legacy_row(session, company, ref="NEWER", actor=owner)
    await session.commit()
    second = await client.get(
        "/api/v1/admin/audit/events",
        headers=headers,
        params={**params, "cursor": page["next_cursor"]},
    )
    assert second.status_code == 200, second.text
    assert [row["id"] for row in second.json()["entries"]] == ids[:1]
    assert not second.json()["has_more"]
    assert newer not in [row["id"] for row in second.json()["entries"]]
    assert foreign not in [row["id"] for row in page["entries"]]
    changed = await client.get(
        "/api/v1/admin/audit/events",
        headers=headers,
        params={**params, "q": "Changed", "cursor": page["next_cursor"]},
    )
    assert changed.status_code == 422
    exact = await client.get(
        "/api/v1/admin/audit/events", headers=headers, params={"reference": "ADJ-TEST-1"}
    )
    assert [row["id"] for row in exact.json()["entries"]] == ids[1:2]
    old = await client.get(
        "/api/v1/admin/audit", headers=headers, params={"entity_type": "StockMovement"}
    )
    assert old.status_code == 200
    assert isinstance(old.json(), list)
    assert all("before" in row and "after" in row for row in old.json())
    catalogue = await client.get("/api/v1/admin/audit/catalogue", headers=headers)
    assert catalogue.status_code == 200
    assert len(catalogue.json()["actions"]) == 27
    for query in (
        {"code": "PIK"},
        {"category": "not-real"},
        {"outcome": "paid"},
        {"unexpected": "ignored"},
    ):
        rejected = await client.get("/api/v1/admin/audit/events", headers=headers, params=query)
        assert rejected.status_code == 422, rejected.text
    missing_unlock = await client.get(
        "/api/v1/admin/audit/catalogue", headers={"Authorization": headers["Authorization"]}
    )
    assert missing_unlock.status_code == 401


@pytest.mark.asyncio
async def test_refund_state_filter_and_python_presentation_agree(client, session, seed_owner):
    headers = await protected_headers(session, seed_owner)
    company = seed_owner["company"].id
    unknown = await legacy_row(session, company, entity="Refund", after={"amount_minor": 100})
    paid = await legacy_row(
        session,
        company,
        entity="Refund",
        after={
            "amount_minor": 100,
            "request_id": "request-test",
            "settled_at": "2026-10-18T10:00:00Z",
            "settled_by": str(seed_owner["owner"].id),
            "receipt_no": "REF-TEST",
            "settlement_method": "cash",
        },
    )
    reversed_id = await legacy_row(
        session,
        company,
        entity="MembershipRefundResolution",
        after={
            "refund_id": "refund-test",
            "resolved_at": "2026-10-18T10:00:00Z",
            "resolution": "cash_returned",
        },
    )
    await session.commit()
    for outcome, expected in (("unknown", unknown), ("completed", paid), ("reversed", reversed_id)):
        response = await client.get(
            "/api/v1/admin/audit/events",
            headers=headers,
            params={"code": "POS-RFD", "outcome": outcome},
        )
        assert response.status_code == 200, response.text
        rows = response.json()["entries"]
        assert [row["id"] for row in rows] == [expected]
        raw = await session.get(AuditLog, expected)
        assert describe(raw)["outcome"] == outcome


@pytest.mark.asyncio
async def test_generated_source_identity_typed_snapshot_and_rollback_are_atomic(
    session, seed_owner
):
    install_audit_listeners()
    company = seed_owner["company"].id
    set_actor(user_id=seed_owner["owner"].id, company_id=company)
    try:
        ingredient = Ingredient(
            company_id=company, sku=f"AUDIT-{uuid4().hex}", name="Test water", base_unit="unit"
        )
        session.add(ingredient)
        await session.flush()
        assert ingredient.id is not None
        rows = (
            (
                await session.execute(
                    select(AuditLog).where(
                        AuditLog.company_id == company,
                        AuditLog.entity_type == "Ingredient",
                        AuditLog.entity_id == str(ingredient.id),
                    )
                )
            )
            .scalars()
            .all()
        )
        assert len(rows) == 1
        assert rows[0].after["id"] == str(ingredient.id)
        assert rows[0].after["created_at"]
        source_id = ingredient.id
        await session.rollback()
        assert (
            await session.scalar(
                select(AuditLog.id).where(
                    AuditLog.company_id == company,
                    AuditLog.entity_id == str(source_id),
                )
            )
            is None
        )
    finally:
        clear_actor()


@pytest.mark.asyncio
async def test_database_rejects_direct_audit_update_delete_and_truncate(session, seed_owner):
    identity = await legacy_row(session, seed_owner["company"].id)
    await session.commit()
    for statement in (
        "UPDATE audit_log SET action = 'changed' WHERE id = :id",
        "DELETE FROM audit_log WHERE id = :id",
        "TRUNCATE TABLE audit_log CASCADE",
    ):
        with pytest.raises(IntegrityError, match="append-only"):
            async with session.begin_nested():
                await session.execute(text(statement), {"id": identity})
    assert await session.scalar(select(AuditLog.id).where(AuditLog.id == identity)) == identity


@pytest.mark.asyncio
async def test_materialized_history_excludes_late_lower_id_commit_and_announces_refresh(
    client, session, seed_owner
):
    from app.core.db import AsyncSessionLocal

    headers = await protected_headers(session, seed_owner)
    company = seed_owner["company"].id
    reference = f"SNAP-{uuid4().hex}"
    older = [await legacy_row(session, company, ref=reference) for _ in range(2)]
    await session.commit()
    async with AsyncSessionLocal() as pending, AsyncSessionLocal() as newer:
        late_id = await legacy_row(pending, company, ref=reference)
        newest_id = await legacy_row(newer, company, ref=reference)
        await newer.commit()
        params = {"reference": reference, "limit": 2}
        first = await client.get("/api/v1/admin/audit/events", headers=headers, params=params)
        assert first.status_code == 200, first.text
        assert [row["id"] for row in first.json()["entries"]] == [newest_id, older[-1]]
        await pending.commit()
    second = await client.get(
        "/api/v1/admin/audit/events",
        headers=headers,
        params={**params, "cursor": first.json()["next_cursor"]},
    )
    assert second.status_code == 200, second.text
    assert [row["id"] for row in second.json()["entries"]] == older[:1]
    assert second.json()["matching_count"] == 3
    assert second.json()["new_entries_available"] is True
    # Repeated checks on a completed page must not allocate new snapshots or
    # evict the original history; the delayed lower-ID commit must be noticed.
    assert second.json()["activity_cursor"]
    for _ in range(10):
        checked = await client.get(
            "/api/v1/admin/audit/events", headers=headers,
            params={**params, "cursor": second.json()["activity_cursor"]},
        )
        assert checked.status_code == 200, checked.text
        assert checked.json()["entries"] == []
        assert checked.json()["matching_count"] == 3
        assert checked.json()["new_entries_available"] is True
    retained = await client.get(
        "/api/v1/admin/audit/events", headers=headers,
        params={**params, "cursor": first.json()["next_cursor"]},
    )
    assert retained.status_code == 200
    assert [row["id"] for row in retained.json()["entries"]] == older[:1]
    refreshed = await client.get("/api/v1/admin/audit/events", headers=headers, params=params)
    assert late_id in [row["id"] for row in refreshed.json()["entries"]]
    assert refreshed.json()["matching_count"] == 4


@pytest.mark.asyncio
async def test_refresh_indicator_detects_new_ids_when_current_profile_matches_cancel_out(
    client, session, seed_owner
):
    from app.models import User

    headers = await protected_headers(session, seed_owner)
    needle = "PROFILE-" + uuid4().hex
    owner = seed_owner["owner"]
    owner.name = needle
    colleague = User(
        id=uuid4(), company_id=owner.company_id, email=f"colleague-{uuid4()}@test.local",
        name=needle, password_hash=owner.password_hash, status="active",
    )
    session.add(colleague)
    await session.flush()
    old_id = await legacy_row(session, owner.company_id, actor=owner.id)
    retained_id = await legacy_row(session, owner.company_id, actor=colleague.id)
    await session.commit()
    params = {"q": needle, "entity_type": "StockMovement", "limit": 1}
    first = await client.get("/api/v1/admin/audit/events", headers=headers, params=params)
    assert first.status_code == 200, first.text
    assert first.json()["matching_count"] == 2
    assert [row["id"] for row in first.json()["entries"]] == [retained_id]
    # One existing match disappears because its current display label changed;
    # one newly committed matching event appears. Counts remain equal.
    owner.name = "Changed display label"
    new_id = await legacy_row(session, owner.company_id, actor=colleague.id)
    await session.commit()
    second = await client.get(
        "/api/v1/admin/audit/events", headers=headers,
        params={**params, "cursor": first.json()["next_cursor"]},
    )
    assert second.status_code == 200, second.text
    assert second.json()["matching_count"] == 2
    assert [row["id"] for row in second.json()["entries"]] == [old_id]
    assert second.json()["new_entries_available"] is True
    refreshed = await client.get("/api/v1/admin/audit/events", headers=headers, params=params)
    assert [row["id"] for row in refreshed.json()["entries"]] == [new_id]


@pytest.mark.asyncio
async def test_failed_login_target_filter_is_distinct_from_authenticated_actor(
    client, session, seed_owner
):
    headers = await protected_headers(session, seed_owner)
    owner = seed_owner["owner"]
    identity = await legacy_row(
        session,
        owner.company_id,
        actor=owner.id,
        entity="User",
        action="login_failed",
        after={"email": owner.email},
    )
    await session.commit()
    actor = await client.get(
        "/api/v1/admin/audit/events",
        headers=headers,
        params={
            "code": "SEC-LGN",
            "actor_user_id": str(owner.id),
        },
    )
    assert identity not in [row["id"] for row in actor.json()["entries"]]
    target = await client.get(
        "/api/v1/admin/audit/events",
        headers=headers,
        params={
            "code": "SEC-LGN",
            "target_user_id": str(owner.id),
        },
    )
    event = next(row for row in target.json()["entries"] if row["id"] == identity)
    assert event["actor_user_id"] is None
    assert event["actor_name"] is None
    assert event["target_user_id"] == str(owner.id)
    assert event["actor_kind"] == "unauthenticated"


@pytest.mark.asyncio
async def test_stock_event_snapshots_unit_name_and_real_before_after_once(session, seed_owner):
    from decimal import Decimal

    from app.models import Batch, StockMovement

    install_audit_listeners()
    company = seed_owner["company"].id
    set_actor(user_id=seed_owner["owner"].id, company_id=company)
    try:
        ingredient = Ingredient(
            id=uuid4(),
            company_id=company,
            sku=f"STK-{uuid4().hex}",
            name="Test Water",
            base_unit="unit",
            current_qty=Decimal("10"),
        )
        session.add(ingredient)
        await session.flush()
        batch = Batch(
            id=uuid4(),
            ingredient_id=ingredient.id,
            branch_id=seed_owner["branch"].id,
            received_at=datetime.now(UTC),
            qty_initial=10,
            qty_on_hand=10,
            cost_per_unit_minor=100,
        )
        session.add(batch)
        await session.flush()
        ingredient.current_qty = Decimal("8")
        batch.qty_on_hand = Decimal("8")
        movement = StockMovement(
            id=uuid4(),
            batch_id=batch.id,
            branch_id=seed_owner["branch"].id,
            type="adjustment",
            qty_delta=Decimal("-2"),
            cost_per_unit_minor=100,
        )
        session.add(movement)
        await session.flush()
        evidence = await session.scalar(
            select(AuditLog).where(
                AuditLog.company_id == company,
                AuditLog.entity_type == "StockMovement",
                AuditLog.entity_id == str(movement.id),
            )
        )
        assert evidence.action_code == "STK-ADJ"
        assert evidence.business_details["ingredient_name"] == "Test Water"
        assert evidence.business_details["unit"] == "unit"
        assert str(evidence.business_details["balance_before"]) == "10"
        assert str(evidence.business_details["balance_after"]) == "8"
        ingredient.name = "Later catalogue name"
        await session.flush()
        assert evidence.business_details["ingredient_name"] == "Test Water"
        await session.rollback()
    finally:
        clear_actor()


@pytest.mark.asyncio
async def test_business_view_keeps_security_and_access_but_hides_connection_noise(
    client, session, seed_owner
):
    headers = await protected_headers(session, seed_owner)
    company = seed_owner["company"].id
    remote = await legacy_row(
        session, company, entity="RemoteAssistanceDeviceKey", action="enrolled", after={}
    )
    access = await legacy_row(
        session, company, entity="RolePermissionOverride", action="update", after={"allowed": True}
    )
    await session.commit()
    business = await client.get("/api/v1/admin/audit/events", headers=headers)
    assert remote not in [row["id"] for row in business.json()["entries"]]
    assert access in [row["id"] for row in business.json()["entries"]]
    system = await client.get(
        "/api/v1/admin/audit/events", headers=headers, params={"view": "all", "category": "system"}
    )
    assert remote in [row["id"] for row in system.json()["entries"]]
    assert access not in [row["id"] for row in system.json()["entries"]]


@pytest.mark.asyncio
@pytest.mark.parametrize("method", ["qr", "wallet"])
async def test_pos_non_cash_rail_sql_matches_python_outcome(client, session, seed_owner, method):
    headers = await protected_headers(session, seed_owner)
    identity = await legacy_row(
        session,
        seed_owner["company"].id,
        entity="Refund",
        after={
            "amount_minor": 100,
            "request_id": "request-test",
            "settled_at": "2026-10-18T10:00:00Z",
            "settled_by": str(seed_owner["owner"].id),
            "receipt_no": "REF-TEST",
            "settlement_method": method,
            "external_reference": "provider-test",
            "provider_settled_at": "2026-10-18T10:00:00Z",
        },
    )
    await session.commit()
    response = await client.get(
        "/api/v1/admin/audit/events",
        headers=headers,
        params={"code": "POS-RFD", "outcome": "completed"},
    )
    assert response.status_code == 200, response.text
    assert identity in [row["id"] for row in response.json()["entries"]]


@pytest.mark.asyncio
async def test_parent_lifecycle_keeps_original_audit_actor_and_terminal_ids(session, seed_owner):
    from sqlalchemy import delete

    from app.models import Terminal, User

    actor = User(
        id=uuid4(),
        company_id=seed_owner["company"].id,
        email=f"audit-parent-{uuid4().hex}@test.local",
        name="Synthetic prior operator",
        password_hash=seed_owner["owner"].password_hash,
        status="active",
    )
    terminal = Terminal(
        id=uuid4(),
        branch_id=seed_owner["branch"].id,
        name="Synthetic retired till",
        purpose="hybrid",
        is_active=False,
    )
    session.add_all([actor, terminal])
    await session.flush()
    identity = (
        await session.execute(
            insert(AuditLog)
            .values(
                actor_user_id=actor.id,
                terminal_id=terminal.id,
                company_id=seed_owner["company"].id,
                action="legacy_test",
                entity_type="LegacyAuditProbe",
                entity_id="retained-parent-identity",
            )
            .returning(AuditLog.id)
        )
    ).scalar_one()
    await session.commit()
    actor_id, terminal_id = actor.id, terminal.id
    await session.execute(delete(User).where(User.id == actor_id))
    await session.execute(delete(Terminal).where(Terminal.id == terminal_id))
    await session.commit()
    evidence = await session.get(AuditLog, identity)
    assert evidence.actor_user_id == actor_id
    assert evidence.terminal_id == terminal_id


@pytest.mark.asyncio
async def test_empty_activity_checks_preserve_other_snapshot_and_report_later_match(
    client, session, seed_owner
):
    from app.core.security import decode_token, issue_audit_page_cursor

    headers = await protected_headers(session, seed_owner)
    company = seed_owner["company"].id
    retained_ref, empty_ref = f"RETAIN-{uuid4().hex}", f"EMPTY-{uuid4().hex}"
    retained_ids = [await legacy_row(session, company, ref=retained_ref) for _ in range(3)]
    await session.commit()
    retained_params = {"reference": retained_ref, "limit": 1}
    retained = await client.get(
        "/api/v1/admin/audit/events", headers=headers, params=retained_params
    )
    assert retained.status_code == 200
    empty_params = {"reference": empty_ref, "limit": 1}
    empty = await client.get("/api/v1/admin/audit/events", headers=headers, params=empty_params)
    assert empty.status_code == 200
    empty_cursor = empty.json()["activity_cursor"]
    assert empty_cursor
    claims = decode_token(empty_cursor)
    assert claims["snapshot"] == claims["last"] == 0
    for _ in range(10):
        checked = await client.get(
            "/api/v1/admin/audit/events",
            headers=headers,
            params={**empty_params, "cursor": empty_cursor},
        )
        assert checked.status_code == 200, checked.text
        assert checked.json()["entries"] == []
        assert checked.json()["matching_count"] == 0
        assert not checked.json()["new_entries_available"]
        assert (
            decode_token(checked.json()["activity_cursor"])["snapshot_id"] == claims["snapshot_id"]
        )
    older = await client.get(
        "/api/v1/admin/audit/events",
        headers=headers,
        params={**retained_params, "cursor": retained.json()["next_cursor"]},
    )
    assert older.status_code == 200, older.text
    assert [row["id"] for row in older.json()["entries"]] == [retained_ids[1]]
    late = await legacy_row(session, company, ref=empty_ref)
    await session.commit()
    checked = await client.get(
        "/api/v1/admin/audit/events",
        headers=headers,
        params={**empty_params, "cursor": empty_cursor},
    )
    assert checked.status_code == 200
    assert checked.json()["entries"] == []
    assert checked.json()["matching_count"] == 0
    assert checked.json()["new_entries_available"]
    fresh = await client.get("/api/v1/admin/audit/events", headers=headers, params=empty_params)
    assert [row["id"] for row in fresh.json()["entries"]] == [late]
    # Even correctly signed boundary mutations must match the retained set.
    for snapshot, last in ((0, 1), (1, 0), (-1, -1), (1, 1)):
        altered = issue_audit_page_cursor({**claims, "snapshot": snapshot, "last": last})
        refused = await client.get(
            "/api/v1/admin/audit/events",
            headers=headers,
            params={**empty_params, "cursor": altered},
        )
        assert refused.status_code == 422, refused.text
    nonempty_claims = decode_token(retained.json()["activity_cursor"])
    signed_empty = issue_audit_page_cursor({**nonempty_claims, "snapshot": 0, "last": 0})
    refused = await client.get(
        "/api/v1/admin/audit/events",
        headers=headers,
        params={**retained_params, "cursor": signed_empty},
    )
    assert refused.status_code == 422
