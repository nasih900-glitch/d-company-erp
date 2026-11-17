"""Synthetic online start, durable historic recovery and exact Owner retirement."""

from datetime import UTC, datetime, timedelta
from uuid import UUID, uuid4

import pytest
from sqlalchemy import func, select, text
from sqlalchemy.exc import DBAPIError

from app.api.v1.pos.start_work import StartWorkReference, _reference_hash
from app.core.security import issue_access_token
from app.models import Attendance, Shift, ShiftStartWorkReceipt
from app.services.audit.recorder import install_audit_listeners

install_audit_listeners()

pytestmark = [pytest.mark.asyncio, pytest.mark.integration]
PATH = "/api/v1/pos/shifts/start-work"


def headers(seed, key=None, android=False):
    return {
        "Authorization": "Bearer "
        + issue_access_token(
            user_id=seed["owner"].id,
            company_id=seed["company"].id,
            branch_id=seed["branch"].id,
            roles=["owner"],
            auth_version=seed["owner"].auth_version,
        ),
        "X-Terminal-Id": str(seed["terminal"].id),
        "Idempotency-Key": key or "shift-open:" + str(uuid4()),
        **(
            {
                "X-Client-Platform": "android",
                "X-Client-Version-Code": "72",
                "X-Installation-Id": str(uuid4()),
            }
            if android
            else {}
        ),
    }


def reference(seed, hdr, cash=0):
    ref = StartWorkReference(
        action_id=UUID(hdr["Idempotency-Key"].removeprefix("shift-open:")),
        company_id=seed["company"].id,
        actor_user_id=seed["owner"].id,
        branch_id=seed["branch"].id,
        terminal_id=seed["terminal"].id,
        installation_id=hdr.get("X-Installation-Id"),
        client_platform="android" if "X-Installation-Id" in hdr else "web",
        opening_float_minor=cash,
        request_hash="0" * 64,
    )
    return ref.model_copy(update={"request_hash": _reference_hash(ref)}).model_dump(mode="json")


async def review(client, seed, ref, mode="status", **extra):
    return await client.post(
        PATH + "/recovery",
        json={
            "reference": ref,
            "mode": mode,
            "owner_email": seed["owner"].email,
            "owner_password": "password1234",
            "reason": "Retire this exact unconfirmed start",
            **extra,
        },
    )


async def test_atomic_start_and_replay_after_clockout_and_shift_close(client, session, seed_owner):
    h = headers(seed_owner, android=True)
    r = await client.post(PATH, headers=h, json={"opening_float_minor": 123})
    assert r.status_code == 201, r.text
    value = r.json()
    assert value["attendance_created"]
    assert not value["original_attendance_preserved"]
    assert value["installation_id"] == h["X-Installation-Id"]
    attendance = await session.get(Attendance, UUID(value["attendance_id"]))
    shift = await session.get(Shift, UUID(value["shift_id"]))
    assert attendance.clock_in_at == shift.opened_at
    attendance.clock_out_at = datetime.now(UTC)
    shift.status = "closed"
    shift.closed_by = seed_owner["owner"].id
    shift.closed_at = datetime.now(UTC)
    shift.counted_minor = 123
    shift.variance_minor = 0
    await session.commit()
    again = await client.post(PATH, headers=h, json={"opening_float_minor": 123})
    assert again.status_code == 201
    assert again.json() == value
    await session.refresh(attendance)
    await session.refresh(shift)
    assert attendance.clock_out_at is not None
    assert shift.status == "closed"
    exact = await client.get(
        "/api/v1/pos/shifts", headers=h, params={"shift_id": value["shift_id"]}
    )
    assert [row["id"] for row in exact.json()] == [value["shift_id"]]
    assert exact.json()[0]["status"] == "closed"
    resolved = await review(client, seed_owner, reference(seed_owner, h, 123))
    assert resolved.status_code == 200
    assert resolved.json()["current_shift"]["status"] == "closed"


async def test_existing_attendance_is_preserved_and_other_open_shift_is_not_adopted(
    client, session, seed_owner
):
    original = datetime.now(UTC) - timedelta(hours=2)
    attendance = Attendance(
        id=uuid4(),
        company_id=seed_owner["company"].id,
        user_id=seed_owner["owner"].id,
        branch_id=seed_owner["branch"].id,
        clock_in_at=original,
    )
    session.add(attendance)
    await session.commit()
    h = headers(seed_owner)
    r = await client.post(PATH, headers=h, json={"opening_float_minor": 0})
    assert r.status_code == 201, r.text
    assert r.json()["attendance_id"] == str(attendance.id)
    assert not r.json()["attendance_created"]
    assert r.json()["original_attendance_preserved"]
    await session.refresh(attendance)
    assert attendance.clock_in_at == original
    blocked = await client.post(PATH, headers=headers(seed_owner), json={"opening_float_minor": 0})
    assert blocked.status_code == 409
    assert (
        await session.scalar(
            select(func.count())
            .select_from(Attendance)
            .where(Attendance.user_id == seed_owner["owner"].id)
        )
        == 1
    )


@pytest.mark.parametrize("cash", [-1, True, 1.5, 2**63])
async def test_strict_cash_is_not_coerced(client, seed_owner, cash):
    r = await client.post(PATH, headers=headers(seed_owner), json={"opening_float_minor": cash})
    assert r.status_code == 422


async def test_offline_open_and_invalid_android_identity_do_not_clock_in(
    client, session, seed_owner
):
    h = headers(seed_owner, android=True)
    r = await client.post(
        PATH, headers={**h, "X-Offline-Captured": "true"}, json={"opening_float_minor": 0}
    )
    assert r.status_code == 422
    del h["X-Installation-Id"]
    r = await client.post(PATH, headers=h, json={"opening_float_minor": 0})
    assert r.status_code == 422
    assert (
        await session.scalar(
            select(func.count())
            .select_from(Attendance)
            .where(Attendance.user_id == seed_owner["owner"].id)
        )
        == 0
    )


async def test_owner_cancellation_is_exact_durable_and_blocks_delayed_and_legacy_start(
    client, session, seed_owner
):
    h = headers(seed_owner, android=True)
    company_id, actor_id = seed_owner["company"].id, seed_owner["owner"].id
    ref = reference(seed_owner, h)
    unknown = await review(client, seed_owner, ref)
    assert unknown.json()["state"] == "unconfirmed"
    result = await review(client, seed_owner, ref, "cancel")
    assert result.status_code == 200, result.text
    assert result.json()["state"] == "cancelled"
    retry = await review(client, seed_owner, ref, "cancel")
    assert retry.json() == result.json()
    status = await review(client, seed_owner, ref)
    assert status.json() == result.json()
    assert (await client.post(PATH, headers=h, json={"opening_float_minor": 0})).status_code == 409
    legacy = await client.post(
        "/api/v1/pos/shifts/open", headers=h, json={"opening_float_minor": 0}
    )
    assert legacy.status_code in {409, 422}, legacy.text
    await session.rollback()
    assert (
        await session.scalar(
            select(func.count()).select_from(Shift).where(Shift.company_id == company_id)
        )
        == 0
    )
    assert (
        await session.scalar(
            select(func.count()).select_from(Attendance).where(Attendance.user_id == actor_id)
        )
        == 0
    )


async def test_committed_start_cannot_be_cancelled(client, session, seed_owner):
    h = headers(seed_owner)
    value = await client.post(PATH, headers=h, json={"opening_float_minor": 0})
    assert value.status_code == 201, value.text
    recovery = await review(client, seed_owner, reference(seed_owner, h), "cancel")
    assert recovery.status_code == 200
    assert recovery.json()["state"] == "committed"
    assert recovery.json()["receipt"] == value.json()
    wrong = reference(seed_owner, h, 5)
    assert (await review(client, seed_owner, wrong, "cancel")).status_code == 409


async def test_receipt_is_append_only_and_invalid_response_cannot_be_inserted(
    client, session, seed_owner
):
    h = headers(seed_owner)
    r = await client.post(PATH, headers=h, json={"opening_float_minor": 0})
    assert r.status_code == 201, r.text
    row = await session.get(ShiftStartWorkReceipt, UUID(r.json()["receipt_id"]))
    with pytest.raises(DBAPIError):
        await session.execute(
            text("UPDATE shift_start_work_receipts SET response_body='{}' WHERE id=:id"),
            {"id": row.id},
        )
    await session.rollback()
    assert (await client.post(PATH, headers=h, json={"opening_float_minor": 0})).json() == r.json()


async def test_owner_cancellation_survives_inactive_target_and_is_audited_once(
    client, session, seed_owner
):
    from app.core.security import hash_password
    from app.models import AuditLog, Role, User, UserRole

    h = headers(seed_owner, android=True)
    ref = reference(seed_owner, h)
    company_id, terminal_id = seed_owner["company"].id, seed_owner["terminal"].id
    approver_id = uuid4()
    approver = User(
        id=approver_id,
        company_id=company_id,
        email=f"review-{uuid4().hex}@test.local",
        name="Review Owner",
        password_hash=hash_password("password1234"),
        status="active",
    )
    role_id = await session.scalar(
        select(Role.id).where(Role.company_id == company_id, Role.code == "owner")
    )
    session.add(approver)
    await session.flush()
    session.add(UserRole(id=uuid4(), user_id=approver_id, role_id=role_id))
    seed_owner["owner"].status = "suspended"
    seed_owner["terminal"].is_active = False
    await session.commit()
    first = await review(client, seed_owner, ref, "cancel", owner_email=approver.email)
    assert first.status_code == 200, first.text
    second = await review(client, seed_owner, ref, "cancel", owner_email=approver.email)
    assert second.json() == first.json()
    rows = (
        await session.scalars(
            select(AuditLog).where(
                AuditLog.company_id == company_id, AuditLog.action == "start_work_cancelled"
            )
        )
    ).all()
    assert len(rows) == 1
    assert rows[0].actor_user_id == approver_id
    assert rows[0].action_code == "SHF-SWC"
    assert rows[0].terminal_id == terminal_id
    assert rows[0].catalogue_version == 5
    assert "password" not in str(rows[0].after)


async def test_platform_and_installation_cannot_replay_historic_receipt(client, seed_owner):
    h = headers(seed_owner)
    assert (await client.post(PATH, headers=h, json={"opening_float_minor": 0})).status_code == 201
    changed = {**h, "X-Client-Platform": "ios", "X-Client-Version-Code": "72"}
    result = await client.post(PATH, headers=changed, json={"opening_float_minor": 0})
    assert result.status_code == 409
    changed = {
        **h,
        "X-Client-Platform": "android",
        "X-Client-Version-Code": "72",
        "X-Installation-Id": str(uuid4()),
    }
    assert (
        await client.post(PATH, headers=changed, json={"opening_float_minor": 0})
    ).status_code == 409


async def test_atomic_audit_failure_rolls_back_shift_attendance_receipt_and_reservation(
    client, session, seed_owner, monkeypatch
):
    import app.api.v1.pos.start_work as command
    from app.models import IdempotencyKey

    h = headers(seed_owner)
    company_id = seed_owner["company"].id

    def fail(*args, **kwargs):
        raise RuntimeError("synthetic atomic audit failure")

    monkeypatch.setattr(command, "record_operation", fail)
    with pytest.raises(RuntimeError, match="synthetic atomic audit failure"):
        await client.post(PATH, headers=h, json={"opening_float_minor": 0})
    for model in [Attendance, Shift, ShiftStartWorkReceipt]:
        assert (
            await session.scalar(
                select(func.count()).select_from(model).where(model.company_id == company_id)
            )
            == 0
        )
    assert (
        await session.scalar(
            select(func.count())
            .select_from(IdempotencyKey)
            .where(IdempotencyKey.key == h["Idempotency-Key"])
        )
        == 0
    )


async def test_future_attendance_is_refused_without_shift(client, session, seed_owner):
    actor_id, company_id = seed_owner["owner"].id, seed_owner["company"].id
    session.add(
        Attendance(
            id=uuid4(),
            company_id=company_id,
            user_id=actor_id,
            branch_id=seed_owner["branch"].id,
            clock_in_at=datetime.now(UTC) + timedelta(hours=1),
        )
    )
    await session.commit()
    reply = await client.post(PATH, headers=headers(seed_owner), json={"opening_float_minor": 0})
    assert reply.status_code == 409
    assert (
        await session.scalar(
            select(func.count()).select_from(Shift).where(Shift.company_id == company_id)
        )
        == 0
    )
