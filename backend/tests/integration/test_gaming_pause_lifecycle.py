"""Authoritative pause lifecycle, exact billing, conflict and durable retry proof."""

import asyncio
from datetime import UTC, datetime, timedelta
from uuid import uuid4

import pytest
from sqlalchemy import delete, func, select

from app.api.v1.gaming import router as gaming_router
from app.core.security import issue_access_token
from app.core.config import get_settings
from app.core.db import AsyncSessionLocal
from app.models import (
    AuditLog,
    Branch,
    GamingSession,
    IdempotencyKey,
    Order,
    Shift,
    Station,
    Terminal,
)
from app.models.gaming import GamingPauseEvent, GamingSessionExtension


async def fixture(session, seed_owner, monkeypatch, *, package=False):
    monkeypatch.setattr(get_settings(), 'gaming_pause_enabled', True)
    t0 = datetime.now(UTC)
    clock = {"at": t0}
    class Clock(datetime):
        @classmethod
        def now(cls, tz=None):
            return clock["at"]
    monkeypatch.setattr(gaming_router, "datetime", Clock)
    company, branch, terminal, owner = [seed_owner[name] for name in ("company", "branch", "terminal", "owner")]
    shift = Shift(id=uuid4(), company_id=company.id, branch_id=branch.id, terminal_id=terminal.id,
                  opened_by=owner.id, opened_at=t0, opening_float_minor=50_000, expected_minor=50_000, status="open")
    station = Station(id=uuid4(), company_id=company.id, branch_id=branch.id, code=f"PAUSE-{uuid4().hex[:8]}",
                      name="Pause audit VR", type="vr", rate_per_hour_minor=60_000, tax_rate=0)
    session.add_all([shift, station])
    await session.flush()
    game = GamingSession(id=uuid4(), company_id=company.id, station_id=station.id, shift_id=shift.id,
                         opened_by=owner.id, start_at=t0, rate_per_hour_minor=60_000, status="active",
                         timer_minutes=30, paused_minutes=0, paused_duration_ms=0, pause_version=0,
                         billing_mode="package" if package else "hourly", amount_minor=8_000 if package else None)
    session.add(game)
    await session.commit()
    token = issue_access_token(user_id=owner.id, company_id=company.id, branch_id=branch.id,
                               roles=["owner"], auth_version=owner.auth_version)
    headers = {"Authorization": f"Bearer {token}", "X-Terminal-Id": str(terminal.id)}
    return clock, t0, game, headers


async def change(client, game, headers, action, version, *, key=None, reason="Controller connection check", extra=None):
    return await client.post(f"/api/v1/gaming/sessions/{game.id}/{action}",
        json={"reason":reason, "expected_pause_version":version},
        headers=headers | {"Idempotency-Key": key or f"pause-test:{uuid4()}"} | (extra or {}))


def legacy_resolution_payload(
    game,
    *,
    ended_at: datetime,
    billable_minutes: int,
    amount_minor: int,
) -> dict:
    return {
        "expected_status": "paused",
        "expected_paused_at": None,
        "expected_pause_version": 0,
        "expected_end_at": None,
        "expected_order_id": None,
        "expected_billable_minutes": None,
        "expected_paused_duration_ms": int(game.paused_duration_ms or 0),
        "expected_amount_minor": game.amount_minor,
        "ended_at": ended_at.isoformat(),
        "billable_minutes": billable_minutes,
        "amount_minor": amount_minor,
        "timing_evidence_reviewed": True,
        "reason": "Reviewed the station log and customer checkout time",
    }


def protected_headers(seed_owner, *, branch_id=None, terminal_id=None, key=None):
    token = issue_access_token(
        user_id=seed_owner["owner"].id,
        company_id=seed_owner["company"].id,
        branch_id=branch_id or seed_owner["branch"].id,
        roles=["super_owner"],
        auth_version=seed_owner["owner"].auth_version,
    )
    headers = {
        "Authorization": f"Bearer {token}",
        "Idempotency-Key": key or f"legacy-pause-resolution:{uuid4()}",
    }
    if terminal_id is not None:
        headers["X-Terminal-Id"] = str(terminal_id)
    return headers


@pytest.mark.asyncio
async def test_exact_pause_resume_and_stop_keep_one_authoritative_clock(client, session, seed_owner, monkeypatch):
    clock,t0,game,headers = await fixture(session,seed_owner,monkeypatch)
    clock['at'] = t0 + timedelta(seconds=30)
    paused = await change(client,game,headers,'pause',0)
    assert paused.status_code == 200, paused.text
    assert paused.json()['timer_ends_at'] is None
    assert paused.json()['pause_version'] == 1
    clock['at'] = t0 + timedelta(seconds=59,milliseconds=500)
    resumed = await change(client,game,headers,'resume',1)
    assert resumed.status_code == 200, resumed.text
    assert resumed.json()['paused_duration_ms'] == 29_500
    assert resumed.json()['timer_alarm_version'] == 2
    clock['at'] = t0 + timedelta(seconds=60)
    assert (await change(client,game,headers,'pause',2)).status_code == 200
    clock['at'] = t0 + timedelta(seconds=90,milliseconds=500)
    resumed = await change(client,game,headers,'resume',3)
    assert resumed.json()['paused_duration_ms'] == 60_000
    assert resumed.json()['paused_minutes'] == 1
    clock['at'] = t0 + timedelta(minutes=2)
    stopped = await client.post(f'/api/v1/gaming/sessions/{game.id}/stop', json={},
        headers=headers | {'Idempotency-Key':f'stop:{uuid4()}'})
    assert stopped.status_code == 200, stopped.text
    assert stopped.json()['billable_minutes'] == 1
    assert stopped.json()['amount_minor'] == 1_000
    assert (await session.execute(select(func.count()).select_from(GamingPauseEvent).where(GamingPauseEvent.gaming_session_id==game.id))).scalar_one() == 4
    assert (await session.execute(select(func.count()).select_from(AuditLog).where(AuditLog.entity_id==str(game.id),AuditLog.action.in_(['gaming.session.pause','gaming.session.resume'])))).scalar_one() == 4


@pytest.mark.asyncio
@pytest.mark.parametrize('package',[False,True])
async def test_stop_while_paused_is_safe_and_package_price_does_not_change(client, session, seed_owner, monkeypatch, package):
    clock,t0,game,headers = await fixture(session,seed_owner,monkeypatch,package=package)
    clock['at'] = t0 + timedelta(seconds=30)
    assert (await change(client,game,headers,'pause',0)).status_code == 200
    clock['at'] = t0 + timedelta(hours=3)
    url=f'/api/v1/gaming/sessions/{game.id}/stop'
    stopped=await client.post(url,json={},headers=headers|{'Idempotency-Key':f'stop:{uuid4()}'})
    assert stopped.status_code==200,stopped.text
    assert stopped.json()['billable_minutes']==1
    assert stopped.json()['amount_minor']==(8_000 if package else 1_000)
    assert stopped.json()['paused_at'] is None
    clock['at'] += timedelta(hours=1)
    replay=await client.post(url,json={},headers=headers|{'Idempotency-Key':f'stop:{uuid4()}'})
    assert replay.json()==stopped.json()


@pytest.mark.asyncio
async def test_pause_retry_survives_cache_expiry_and_stale_cycle_is_rejected(client, session, seed_owner, monkeypatch):
    clock,t0,game,headers = await fixture(session,seed_owner,monkeypatch)
    key=f'pause:{uuid4()}'
    paused=await change(client,game,headers,'pause',0,key=key)
    assert paused.status_code==200,paused.text
    await session.execute(delete(IdempotencyKey).where(IdempotencyKey.key==key))
    await session.commit()
    replay=await change(client,game,headers,'pause',0,key=key)
    assert replay.status_code==200,replay.text
    assert replay.json()==paused.json()
    clock['at']=t0+timedelta(seconds=30)
    assert (await change(client,game,headers,'resume',1)).status_code==200
    stale=await change(client,game,headers,'pause',0)
    assert stale.status_code==409,stale.text
    assert stale.json()['error']['details']['issue']=='pause_state_changed'
    clock['at']=t0+timedelta(seconds=40)
    concurrent=await asyncio.gather(change(client,game,headers,'pause',2),change(client,game,headers,'pause',2))
    assert sorted(r.status_code for r in concurrent)==[200,409]


@pytest.mark.asyncio
async def test_pause_rejects_offline_and_blank_reasons_and_cancel_clears_paused_clock(client, session, seed_owner, monkeypatch):
    _,_,game,headers = await fixture(session,seed_owner,monkeypatch)
    assert (await change(client,game,headers,'pause',0,reason='   ')).status_code==422
    offline=await change(client,game,headers,'pause',0,extra={'X-Offline-Captured':'true'})
    assert offline.status_code==422,offline.text
    assert 'live connection' in offline.text
    assert (await change(client,game,headers,'pause',0)).status_code==200
    cancelled=await client.post(f'/api/v1/gaming/sessions/{game.id}/cancel',json={'reason':'Customer left before playing'},headers=headers|{'Idempotency-Key':f'cancel:{uuid4()}'})
    assert cancelled.status_code==200,cancelled.text
    assert cancelled.json()['paused_at'] is None
    assert cancelled.json()['status']=='cancelled'


@pytest.mark.asyncio
async def test_rollout_gate_blocks_new_pause_not_replay_resume_or_stop(client, session, seed_owner, monkeypatch):
    clock,t0,game,headers = await fixture(session,seed_owner,monkeypatch)
    monkeypatch.setattr(get_settings(), 'gaming_pause_enabled', False)
    blocked = await change(client,game,headers,'pause',0)
    assert blocked.status_code == 422
    assert 'Update the active tablets' in blocked.text
    monkeypatch.setattr(get_settings(), 'gaming_pause_enabled', True)
    key = f'rollout-pause:{uuid4()}'
    paused = await change(client,game,headers,'pause',0,key=key)
    assert paused.json()['pause_available'] is True
    monkeypatch.setattr(get_settings(), 'gaming_pause_enabled', False)
    replay = await change(client,game,headers,'pause',0,key=key)
    assert replay.status_code == 200
    clock['at'] = t0 + timedelta(seconds=10)
    resumed = await change(client,game,headers,'resume',1)
    assert resumed.status_code == 200, resumed.text
    assert resumed.json()['pause_available'] is False
    stopped = await client.post(f'/api/v1/gaming/sessions/{game.id}/stop',json={},headers=headers|{'Idempotency-Key':f'stop:{uuid4()}'})
    assert stopped.status_code == 200


@pytest.mark.asyncio
async def test_shift_close_does_not_wait_for_locked_gaming_row(client, session, seed_owner, monkeypatch):
    _,_,game,headers = await fixture(session,seed_owner,monkeypatch)
    async with AsyncSessionLocal() as blocker:
        await blocker.execute(select(GamingSession).where(GamingSession.id==game.id).with_for_update())
        # Close holds Shift then reads MVCC counts, not a conflicting Gaming
        # lock. This forced interleaving would hang if close locked the row.
        closed = await asyncio.wait_for(client.post(
            f'/api/v1/pos/shifts/{game.shift_id}/close',json={'counted_minor':50_000},headers=headers),timeout=3)
        assert closed.status_code == 422,closed.text
        assert closed.json()['error']['details']['issue'] == 'running_gaming_sessions'
        await blocker.rollback()
    paused,closed = await asyncio.wait_for(asyncio.gather(
        change(client,game,headers,'pause',0),
        client.post(f'/api/v1/pos/shifts/{game.shift_id}/close',json={'counted_minor':50_000},headers=headers),
    ),timeout=3)
    assert paused.status_code == 200,paused.text
    assert closed.status_code == 422,closed.text


@pytest.mark.asyncio
async def test_old_paused_row_without_known_start_cannot_be_stopped_or_resumed(client, session, seed_owner, monkeypatch):
    _,_,game,headers = await fixture(session,seed_owner,monkeypatch)
    game.status='paused'
    await session.commit()
    refused=await change(client,game,headers,'resume',0)
    assert refused.status_code == 422
    assert 'no recorded pause start time' in refused.text
    stopped=await client.post(f'/api/v1/gaming/sessions/{game.id}/stop',json={},headers=headers|{'Idempotency-Key':f'legacy-stop:{uuid4()}'})
    assert stopped.status_code == 409,stopped.text
    assert stopped.json()['error']['code'] == 'gaming_billing_repair_required'
    assert stopped.json()['error']['details']['issue'] == 'legacy_pause_time_unknown'
    await session.refresh(game)
    assert game.status == 'paused'
    assert game.end_at is None
    assert game.amount_minor is None


@pytest.mark.asyncio
async def test_protected_owner_resolves_legacy_pause_idempotently_and_frees_station(
    client,
    session,
    seed_owner,
    monkeypatch,
):
    seed_owner["branch"].state_code = "32"
    seed_owner["company"].gstin = "32AAAAA0000A1Z5"
    await session.commit()
    clock, t0, game, _ = await fixture(session, seed_owner, monkeypatch)
    game.status = "paused"
    game.paused_minutes = 3
    game.paused_duration_ms = 180_000
    await session.commit()
    clock["at"] = t0 + timedelta(hours=2)
    ended_at = t0 + timedelta(hours=1)
    payload = legacy_resolution_payload(
        game,
        ended_at=ended_at,
        billable_minutes=30,
        amount_minor=30_000,
    )
    key = f"legacy-pause-resolution:{uuid4()}"
    headers = protected_headers(
        seed_owner,
        terminal_id=seed_owner["terminal"].id,
        key=key,
    )
    path = f"/api/v1/gaming/sessions/{game.id}/resolve-legacy-pause"

    repaired = await client.post(path, json=payload, headers=headers)
    replay = await client.post(path, json=payload, headers=headers)

    assert repaired.status_code == 200, repaired.text
    assert replay.status_code == 200, replay.text
    assert replay.json() == repaired.json()
    assert repaired.json()["status"] == "ended"
    assert repaired.json()["end_at"] == ended_at.isoformat().replace("+00:00", "Z")
    assert repaired.json()["billable_minutes"] == 30
    assert repaired.json()["amount_minor"] == 30_000
    assert repaired.json()["paused_duration_ms"] == 180_000
    assert repaired.json()["pause_version"] == 0

    await session.refresh(game)
    assert game.status == "ended"
    assert game.stopped_by == seed_owner["owner"].id
    assert game.paused_at is None
    assert game.paused_duration_ms == 180_000
    audit = (
        await session.execute(
            select(AuditLog).where(
                AuditLog.company_id == seed_owner["company"].id,
                AuditLog.action == "gaming_session_legacy_pause_resolution",
                AuditLog.entity_id == str(game.id),
            )
        )
    ).scalar_one()
    assert audit.reason == payload["reason"]
    assert audit.before["status"] == "paused"
    assert audit.after["status"] == "ended"
    assert audit.after["legacy_pause_resolution"] == {
        "timing_evidence_reviewed": True,
        "known_paused_duration_ms": 180_000,
        "maximum_billable_minutes": 57,
        "selected_billable_minutes": 30,
        "selected_amount_minor": 30_000,
        "selected_end_at": ended_at.isoformat(),
        "amount_basis": {
            "kind": "locked_hourly_rate",
            "rate_per_hour_minor": 60_000,
        },
        "reason": payload["reason"],
    }

    already_repaired = await client.post(
        path,
        json=payload,
        headers=headers | {"Idempotency-Key": f"legacy-pause-second:{uuid4()}"},
    )
    assert already_repaired.status_code == 409, already_repaired.text
    assert "no longer an unresolved legacy pause" in already_repaired.text

    sent = await client.post(
        f"/api/v1/gaming/sessions/{game.id}/send-to-pos",
        headers={
            "Authorization": headers["Authorization"],
            "X-Terminal-Id": str(seed_owner["terminal"].id),
        },
    )
    assert sent.status_code == 201, sent.text
    await session.refresh(game)
    assert game.order_id is not None


@pytest.mark.asyncio
async def test_legacy_pause_resolution_derives_missing_package_total_from_locked_ledger(
    client,
    session,
    seed_owner,
    monkeypatch,
):
    clock, t0, game, _ = await fixture(session, seed_owner, monkeypatch)
    game.status = "paused"
    game.billing_mode = "package"
    game.package_price_minor_snapshot = 8_000
    game.package_duration_minutes_snapshot = 30
    game.package_variant_snapshot = "single"
    game.package_station_type_snapshot = "vr"
    game.timer_minutes = 60
    game.amount_minor = None
    extension = GamingSessionExtension(
        id=uuid4(),
        company_id=seed_owner["company"].id,
        gaming_session_id=game.id,
        package_id=None,
        package_name="30 minute extension",
        package_variant="single",
        station_type="vr",
        duration_minutes=30,
        package_price_minor=6_000,
        controller_surcharge_minor=0,
        total_minor=6_000,
        timer_before_minutes=30,
        timer_after_minutes=60,
        amount_before_minor=8_000,
        amount_after_minor=14_000,
        idempotency_key=f"legacy-extension:{uuid4()}",
        created_by=seed_owner["owner"].id,
    )
    session.add(extension)
    await session.commit()
    clock["at"] = t0 + timedelta(hours=2)
    path = f"/api/v1/gaming/sessions/{game.id}/resolve-legacy-pause"
    wrong_amount = legacy_resolution_payload(
        game,
        ended_at=t0 + timedelta(hours=1),
        billable_minutes=30,
        amount_minor=13_000,
    )

    refused = await client.post(
        path,
        json=wrong_amount,
        headers=protected_headers(
            seed_owner,
            terminal_id=seed_owner["terminal"].id,
        ),
    )
    assert refused.status_code == 422, refused.text
    assert "server-calculated package total" in refused.text
    await session.refresh(game)
    assert game.status == "paused"
    assert game.amount_minor is None

    exact_amount = {**wrong_amount, "amount_minor": 14_000}
    accepted = await client.post(
        path,
        json=exact_amount,
        headers=protected_headers(
            seed_owner,
            terminal_id=seed_owner["terminal"].id,
        ),
    )
    assert accepted.status_code == 200, accepted.text
    assert accepted.json()["amount_minor"] == 14_000
    audit = (
        await session.execute(
            select(AuditLog).where(
                AuditLog.action == "gaming_session_legacy_pause_resolution",
                AuditLog.entity_id == str(game.id),
            )
        )
    ).scalar_one()
    assert audit.after["legacy_pause_resolution"]["amount_basis"] == {
        "kind": "derived_locked_package_ledger",
        "base_package_price_minor": 8_000,
        "base_duration_minutes": 30,
        "extra_controllers": 0,
        "extension_receipt_count": 1,
        "derived_amount_minor": 14_000,
    }


@pytest.mark.asyncio
async def test_legacy_pause_resolution_refuses_unprovable_missing_package_total(
    client,
    session,
    seed_owner,
    monkeypatch,
):
    clock, t0, game, _ = await fixture(session, seed_owner, monkeypatch)
    game.status = "paused"
    game.billing_mode = "package"
    game.amount_minor = None
    await session.commit()
    clock["at"] = t0 + timedelta(hours=1)

    refused = await client.post(
        f"/api/v1/gaming/sessions/{game.id}/resolve-legacy-pause",
        json=legacy_resolution_payload(
            game,
            ended_at=t0 + timedelta(minutes=30),
            billable_minutes=30,
            amount_minor=8_000,
        ),
        headers=protected_headers(
            seed_owner,
            terminal_id=seed_owner["terminal"].id,
        ),
    )
    assert refused.status_code == 409, refused.text
    assert "locked pricing snapshot is incomplete" in refused.text
    await session.refresh(game)
    assert game.status == "paused"
    assert game.amount_minor is None


@pytest.mark.asyncio
async def test_legacy_pause_resolution_is_not_disclosed_across_branches(
    client,
    session,
    seed_owner,
    monkeypatch,
):
    clock, t0, _, _ = await fixture(session, seed_owner, monkeypatch)
    second_branch = Branch(
        id=uuid4(),
        company_id=seed_owner["company"].id,
        name=f"Legacy pause branch {uuid4().hex[:8]}",
        invoice_series_code="L2",
    )
    second_terminal = Terminal(
        id=uuid4(),
        branch_id=second_branch.id,
        name="Legacy pause workspace",
        purpose="hybrid",
        device_id=f"legacy-pause-{uuid4()}",
    )
    second_shift = Shift(
        id=uuid4(),
        company_id=seed_owner["company"].id,
        branch_id=second_branch.id,
        terminal_id=second_terminal.id,
        opened_by=seed_owner["owner"].id,
        opened_at=t0,
        opening_float_minor=0,
        expected_minor=0,
        status="open",
    )
    second_station = Station(
        id=uuid4(),
        company_id=seed_owner["company"].id,
        branch_id=second_branch.id,
        code=f"LEGACY-{uuid4().hex[:8]}",
        name="Second-branch legacy station",
        type="vr",
        rate_per_hour_minor=60_000,
        tax_rate=0,
    )
    second_game = GamingSession(
        id=uuid4(),
        company_id=seed_owner["company"].id,
        station_id=second_station.id,
        shift_id=second_shift.id,
        opened_by=seed_owner["owner"].id,
        start_at=t0,
        rate_per_hour_minor=60_000,
        status="paused",
        paused_minutes=0,
        paused_duration_ms=0,
        pause_version=0,
        billing_mode="hourly",
    )
    session.add(second_branch)
    await session.flush()
    session.add(second_terminal)
    await session.flush()
    session.add_all([second_shift, second_station])
    await session.flush()
    session.add(second_game)
    await session.commit()
    clock["at"] = t0 + timedelta(hours=1)
    payload = legacy_resolution_payload(
        second_game,
        ended_at=t0 + timedelta(minutes=30),
        billable_minutes=30,
        amount_minor=30_000,
    )
    denied_key = f"legacy-pause-wrong-branch:{uuid4()}"
    path = f"/api/v1/gaming/sessions/{second_game.id}/resolve-legacy-pause"

    denied = await client.post(
        path,
        json=payload,
        headers=protected_headers(
            seed_owner,
            terminal_id=seed_owner["terminal"].id,
            key=denied_key,
        ),
    )
    assert denied.status_code == 404, denied.text
    assert (
        await session.execute(
            select(func.count()).select_from(IdempotencyKey).where(
                IdempotencyKey.key == denied_key
            )
        )
    ).scalar_one() == 0
    await session.refresh(second_game)
    assert second_game.status == "paused"

    accepted = await client.post(
        path,
        json=payload,
        headers=protected_headers(
            seed_owner,
            branch_id=second_branch.id,
            terminal_id=second_terminal.id,
        ),
    )
    assert accepted.status_code == 200, accepted.text
    assert accepted.json()["status"] == "ended"


@pytest.mark.asyncio
async def test_legacy_pause_resolution_refuses_session_with_pos_order(
    client,
    session,
    seed_owner,
    monkeypatch,
):
    clock, t0, game, _ = await fixture(session, seed_owner, monkeypatch)
    order = Order(
        id=uuid4(),
        company_id=seed_owner["company"].id,
        branch_id=seed_owner["branch"].id,
        terminal_id=seed_owner["terminal"].id,
        shift_id=game.shift_id,
        opened_by=seed_owner["owner"].id,
        type="session",
        status="held",
        subtotal_minor=0,
        total_minor=0,
        opened_at=t0,
    )
    session.add(order)
    await session.flush()
    game.status = "paused"
    game.order_id = order.id
    await session.commit()
    clock["at"] = t0 + timedelta(hours=1)
    payload = legacy_resolution_payload(
        game,
        ended_at=t0 + timedelta(minutes=30),
        billable_minutes=30,
        amount_minor=30_000,
    )

    refused = await client.post(
        f"/api/v1/gaming/sessions/{game.id}/resolve-legacy-pause",
        json=payload,
        headers=protected_headers(
            seed_owner,
            terminal_id=seed_owner["terminal"].id,
        ),
    )
    assert refused.status_code == 409, refused.text
    assert "already has a POS order" in refused.text
    await session.refresh(game)
    assert game.status == "paused"
    assert game.end_at is None
    assert game.billable_minutes is None


def test_pause_rollout_is_disabled_by_default_in_production_templates():
    from pathlib import Path
    from app.core.config import Settings
    root=Path(__file__).resolve().parents[3]
    assert Settings.model_fields['gaming_pause_enabled'].default is False
    assert 'GAMING_PAUSE_ENABLED=false' in (root/'.env.production.example').read_text()
    assert '${GAMING_PAUSE_ENABLED:-false}' in (root/'docker-compose.prod.yml').read_text()


@pytest.mark.asyncio
async def test_offline_stop_before_newer_resume_conflicts_without_losing_session(client, session, seed_owner, monkeypatch):
    clock,t0,game,headers = await fixture(session,seed_owner,monkeypatch)
    clock['at']=t0+timedelta(seconds=30)
    assert (await change(client,game,headers,'pause',0)).status_code==200
    clock['at']=t0+timedelta(seconds=90)
    assert (await change(client,game,headers,'resume',1)).status_code==200
    captured=t0+timedelta(seconds=60)
    key=f'captured-stop:{uuid4()}'
    response=await client.post(f'/api/v1/gaming/sessions/{game.id}/stop',json={'ended_at':captured.isoformat()},headers=headers|{
        'Idempotency-Key':key,'X-Client-Action-Id':key,'X-Offline-Captured':'true',
        'X-Client-Occurred-At':captured.isoformat(),
    })
    assert response.status_code==409,response.text
    assert response.json()['error']['details']['issue']=='stop_predates_pause_transition'
    await session.refresh(game)
    assert game.status=='active' and game.end_at is None
    assert game.paused_duration_ms==60_000


@pytest.mark.asyncio
async def test_pause_event_is_append_only_in_database(client, session, seed_owner, monkeypatch):
    from sqlalchemy import update
    from sqlalchemy.exc import IntegrityError
    _,_,game,headers = await fixture(session,seed_owner,monkeypatch)
    assert (await change(client,game,headers,'pause',0)).status_code==200
    with pytest.raises(IntegrityError,match='gaming pause event is immutable'):
        await session.execute(update(GamingPauseEvent).where(GamingPauseEvent.gaming_session_id==game.id).values(reason='Rewrite history'))
    await session.rollback()
