"""Stopped-player Void keeps immutable attendance and settlement evidence before POS."""
from __future__ import annotations

import asyncio
from uuid import UUID, uuid4

import pytest
from sqlalchemy import func, select, text

from app.models import (
    AuditLog, Batch, Branch, Customer, CustomerGamingRankAward,
    GamingParticipantSettlement, GamingParticipantSettlementLine, GamingSession,
    GamingSessionExtension, GamingSessionParticipant, IdempotencyKey, JournalEntry,
    JournalLine, Order, OrderLine, OrderLoyaltySettlement, Payment, PointsRedemption,
    Refund, RefundLoyaltyAdjustment, Role, StockMovement, User, UserRole,
)
from tests.integration.test_gaming_session_participants import _headers, _setup, _start, _login
from app.core.security import hash_password
from tests.integration.test_gaming_package_amendments import _amend_payload

pytestmark = [pytest.mark.integration, pytest.mark.asyncio]


async def _stopped(client, session, seed_owner, *, returner=False, amended=False, extended=False):
    token, shift_id, station, friends, packages = await _setup(client, session, seed_owner)
    started = await _start(client, seed_owner, token, shift_id, station,
        packages['standard-single-session-60m' if amended else 'standard-single-session-30m'])
    sid = started['id']
    if amended:
        changed = await client.post(f'/api/v1/gaming/sessions/{sid}/amend-package',
            json=_amend_payload(packages['standard-single-session-30m'], amount_minor=12_000),
            headers=_headers(seed_owner, token, f'amend:{uuid4()}'))
        assert changed.status_code == 200, changed.text
        started = changed.json()
    if extended:
        extension = packages['standard-single-extension-30m']
        added = await client.post(f'/api/v1/gaming/sessions/{sid}/extend', json={
            'package_id': str(extension.id), 'expected_timer_minutes': started['timer_minutes'],
            'expected_amount_minor': started['amount_minor'],
            'expected_package_price_minor': int(extension.price_minor),
            'expected_package_duration_minutes': int(extension.duration_minutes),
            'expected_package_variant': extension.variant,
            'expected_billing_revision': started['billing_revision'],
        }, headers=_headers(seed_owner, token, f'extend:{uuid4()}'))
        assert added.status_code == 200, added.text
    join_body = {'expected_participant_revision': 0}
    if returner:
        join_body.update(customer_id=str(friends[0].id), customer_directory_revision=0,
            customer_directory_company_id=str(seed_owner['company'].id))
    join_key = f'join:{uuid4()}'
    joined = await client.post(f'/api/v1/gaming/sessions/{sid}/participants/join',
        json=join_body, headers=_headers(seed_owner, token, join_key))
    assert joined.status_code == 200, joined.text
    revision = joined.json()['participant_revision']
    if returner:
        participant = joined.json()['participants'][0]
        left = await client.post(f"/api/v1/gaming/sessions/{sid}/participants/{participant['id']}/leave",
            json={'expected_participant_revision': revision}, headers=_headers(seed_owner, token, f'leave:{uuid4()}'))
        assert left.status_code == 200, left.text
        rejoined = await client.post(f'/api/v1/gaming/sessions/{sid}/participants/join', json={
            'expected_participant_revision': left.json()['participant_revision'],
            'participant_identity_id': participant['participant_identity_id'],
            'customer_id': str(friends[0].id), 'customer_directory_revision': 0,
            'customer_directory_company_id': str(seed_owner['company'].id),
        }, headers=_headers(seed_owner, token, f'rejoin:{uuid4()}'))
        assert rejoined.status_code == 200, rejoined.text
        revision = rejoined.json()['participant_revision']
    stop_key = f'stop:{uuid4()}'
    stop_body = {'expected_participant_revision': revision, 'expected_billing_revision': started['billing_revision']}
    stopped = await client.post(f'/api/v1/gaming/sessions/{sid}/stop', json=stop_body,
        headers=_headers(seed_owner, token, stop_key))
    assert stopped.status_code == 200, stopped.text
    ended = stopped.json()
    ended['_stop_key'], ended['_stop_body'] = stop_key, stop_body
    ended['_join_key'], ended['_join_body'], ended['_join_response'] = join_key, join_body, joined.json()
    return token, ended


def _void_body(ended):
    return {'reason': 'Mistaken session reviewed by opener',
        'expected_participant_revision': ended['participant_revision'],
        'expected_billing_revision': ended['billing_revision'],
        'expected_amount_minor': ended['amount_minor']}


async def _evidence(session, sid):
    result = []
    for model in [GamingSessionParticipant, GamingParticipantSettlement, GamingParticipantSettlementLine]:
        rows = (await session.execute(select(model.__table__).where(model.gaming_session_id == UUID(sid)))).mappings().all()
        result.append(sorted([dict(row) for row in rows], key=lambda row: str(row['id'])))
    return result


async def _business_history(session, company_id):
    """Snapshot actual tenant rows, including legacy refunds scoped via their order.

    The intended Void mutation is confined to GamingSession plus its audit and
    idempotency evidence. It must neither settle a POS bill nor change stock,
    posted journals, customer balances or any loyalty evidence.
    """
    company_orders = select(Order.id).where(Order.company_id == company_id)
    company_branches = select(Branch.id).where(Branch.company_id == company_id)
    company_journals = select(JournalEntry.id).where(JournalEntry.company_id == company_id)
    queries = {
        "orders": select(Order.__table__).where(Order.company_id == company_id),
        "order_lines": select(OrderLine.__table__).where(OrderLine.order_id.in_(company_orders)),
        "payments": select(Payment.__table__).where(Payment.order_id.in_(company_orders)),
        "refunds": select(Refund.__table__).where(Refund.order_id.in_(company_orders)),
        "batches": select(Batch.__table__).where(Batch.branch_id.in_(company_branches)),
        "stock_movements": select(StockMovement.__table__).where(StockMovement.branch_id.in_(company_branches)),
        "journal_entries": select(JournalEntry.__table__).where(JournalEntry.company_id == company_id),
        "journal_lines": select(JournalLine.__table__).where(JournalLine.journal_entry_id.in_(company_journals)),
        "customers": select(Customer.__table__).where(Customer.company_id == company_id),
        "points_redemptions": select(PointsRedemption.__table__).where(PointsRedemption.order_id.in_(company_orders)),
        "loyalty_settlements": select(OrderLoyaltySettlement.__table__).where(OrderLoyaltySettlement.company_id == company_id),
        "refund_loyalty_adjustments": select(RefundLoyaltyAdjustment.__table__).where(RefundLoyaltyAdjustment.company_id == company_id),
        "rank_awards": select(CustomerGamingRankAward.__table__).where(CustomerGamingRankAward.company_id == company_id),
    }
    snapshot = {}
    for name, query in queries.items():
        rows = (await session.execute(query)).mappings().all()
        snapshot[name] = sorted([dict(row) for row in rows], key=lambda row: str(row['id']))
    return snapshot


@pytest.mark.parametrize('returner,amended', [(False, False), (True, True)])
async def test_stopped_player_void_retains_history_and_durable_exact_replay(client, session, seed_owner, returner, amended):
    token, ended = await _stopped(client, session, seed_owner, returner=returner, amended=amended, extended=amended)
    sid = ended['id']; path = f'/api/v1/gaming/sessions/{sid}/cancel'; key = f'gaming-session-void:{sid}'
    before = await _evidence(session, sid)
    history_before = await _business_history(session, seed_owner['company'].id)
    assert len(history_before['customers']) >= 4
    payload = _void_body(ended)
    response = await client.post(path, json=payload, headers=_headers(seed_owner, token, key))
    assert response.status_code == 200, response.text
    data = response.json()
    assert data['status'] == 'cancelled' and data['order_id'] is None
    assert data['amount_minor'] == data['billable_minutes'] == 0
    assert data['end_at'] == ended['end_at']
    assert data['participant_revision'] == ended['participant_revision']
    assert data['billing_revision'] == ended['billing_revision']
    assert data['cancellation_idempotency_key'] == key and len(data['cancellation_request_hash']) == 64
    assert await _evidence(session, sid) == before
    assert await _business_history(session, seed_owner['company'].id) == history_before
    rows = (await session.execute(select(AuditLog).where(AuditLog.company_id == seed_owner['company'].id, AuditLog.entity_id == sid,
        AuditLog.action == 'gaming_session_voided'))).scalars().all()
    assert len(rows) == 1
    assert rows[0].before['amount_minor'] == ended['amount_minor']
    assert rows[0].after['original']['settlement_id'] == str(before[1][0]['id'])
    assert rows[0].actor_user_id == seed_owner['owner'].id
    assert rows[0].reason == payload['reason']
    assert rows[0].business_details['voided_amount_minor'] == ended['amount_minor']
    assert rows[0].business_details['amount_after_void_minor'] == 0
    assert rows[0].business_details['added_player_interval_count'] == (2 if returner else 1)
    assert rows[0].business_details['attendance_retained'] is True
    assert await session.scalar(select(func.count(Order.id)).where(Order.company_id == seed_owner['company'].id)) == 0
    stale_stop = await client.post(f'/api/v1/gaming/sessions/{sid}/stop', json=ended['_stop_body'],
        headers=_headers(seed_owner, token, ended['_stop_key']))
    assert stale_stop.status_code in (200, 409, 422), stale_stop.text
    if stale_stop.status_code == 200:
        assert stale_stop.json()['status'] == 'cancelled' and stale_stop.json()['amount_minor'] == 0
    stale_pos = await client.post(f'/api/v1/gaming/sessions/{sid}/send-to-pos', headers=_headers(seed_owner, token))
    assert stale_pos.status_code == 422, stale_pos.text
    assert await session.scalar(select(func.count(Order.id)).where(Order.company_id == seed_owner['company'].id)) == 0
    first_replay = await client.post(path, json=payload, headers=_headers(seed_owner, token, key))
    assert first_replay.status_code == 200 and first_replay.json() == data
    # The durable receipt remains authoritative after technical key retention expires.
    await session.execute(text('DELETE FROM idempotency_keys WHERE key=:key'), {'key': key})
    await session.commit()
    replay = await client.post(path, json=payload, headers=_headers(seed_owner, token, key))
    assert replay.status_code == 200 and replay.json() == data
    exact = await client.get(f'/api/v1/gaming/sessions/{sid}', headers=_headers(seed_owner, token))
    assert exact.status_code == 200 and exact.json() == data
    for changed, changed_key in [(dict(payload, reason='Different reason'), key), (payload, f'new-void:{uuid4()}')]:
        refused = await client.post(path, json=changed, headers=_headers(seed_owner, token, changed_key))
        assert refused.status_code == 409, refused.text
    assert await _evidence(session, sid) == before
    assert await _business_history(session, seed_owner['company'].id) == history_before


async def test_stale_join_replay_after_void_cannot_reopen_or_rebill_cancelled_session(client, session, seed_owner):
    token, ended = await _stopped(client, session, seed_owner)
    sid = ended['id']
    result = await client.post(f'/api/v1/gaming/sessions/{sid}/cancel', json=_void_body(ended),
        headers=_headers(seed_owner, token, f'gaming-session-void:{sid}'))
    assert result.status_code == 200, result.text
    cancelled = result.json()
    evidence_before = await _evidence(session, sid)
    history_before = await _business_history(session, seed_owner['company'].id)
    assert evidence_before[0] and all(row['left_at'] is not None for row in evidence_before[0])
    join_path = f'/api/v1/gaming/sessions/{sid}/participants/join'

    # Join receipts intentionally describe that original operation, not today's
    # roster. Both generic-cache and durable replay may return the old receipt;
    # neither is permission to mutate the closed attendance or cancelled bill.
    cached = await client.post(join_path, json=ended['_join_body'],
        headers=_headers(seed_owner, token, ended['_join_key']))
    assert cached.status_code == 200 and cached.json() == ended['_join_response']
    await session.execute(text('DELETE FROM idempotency_keys WHERE key=:key'), {'key': ended['_join_key']})
    await session.commit()
    durable = await client.post(join_path, json=ended['_join_body'],
        headers=_headers(seed_owner, token, ended['_join_key']))
    assert durable.status_code == 200 and durable.json() == ended['_join_response']
    changed = await client.post(join_path,
        json=dict(ended['_join_body'], guest_name='Different participant'),
        headers=_headers(seed_owner, token, ended['_join_key']))
    assert changed.status_code == 409, changed.text

    late_join = await client.post(join_path,
        json={'expected_participant_revision': cancelled['participant_revision']},
        headers=_headers(seed_owner, token, f'join-after-void:{uuid4()}'))
    assert late_join.status_code == 422, late_join.text
    assert 'only while the session is active and unbilled' in late_join.json()['error']['message']
    current = await client.get(f'/api/v1/gaming/sessions/{sid}', headers=_headers(seed_owner, token))
    assert current.status_code == 200 and current.json() == cancelled
    assert current.json()['status'] == 'cancelled' and current.json()['order_id'] is None
    assert current.json()['amount_minor'] == current.json()['billable_minutes'] == 0
    assert await _evidence(session, sid) == evidence_before
    assert await _business_history(session, seed_owner['company'].id) == history_before
    assert await session.scalar(select(func.count(AuditLog.id)).where(
        AuditLog.company_id == seed_owner['company'].id, AuditLog.entity_id == sid,
        AuditLog.action == 'gaming_session_voided',
    )) == 1


async def test_stopped_void_requires_every_review_field_and_preserves_original_on_conflicts(client, session, seed_owner):
    token, ended = await _stopped(client, session, seed_owner)
    path = f"/api/v1/gaming/sessions/{ended['id']}/cancel"; payload = _void_body(ended)
    missing_requests = [({'reason': payload['reason']}, None), (payload, None)]
    for field in ['expected_participant_revision', 'expected_billing_revision', 'expected_amount_minor']:
        body = dict(payload); del body[field]
        missing_requests.append((body, f'void-missing:{uuid4()}'))
    for body, key in missing_requests:
        refused = await client.post(path, json=body, headers=_headers(seed_owner, token, key))
        assert refused.status_code == 422, refused.text
    for field in ['expected_participant_revision', 'expected_billing_revision', 'expected_amount_minor']:
        refused = await client.post(path, json=dict(payload, **{field: payload[field] + 1}),
            headers=_headers(seed_owner, token, f'void-stale:{uuid4()}'))
        assert refused.status_code == 409, refused.text
    current = await client.get(f"/api/v1/gaming/sessions/{ended['id']}", headers=_headers(seed_owner, token))
    assert current.json()['status'] == 'ended' and current.json()['amount_minor'] == ended['amount_minor']
    assert await session.scalar(select(func.count(AuditLog.id)).where(AuditLog.company_id == seed_owner['company'].id,
        AuditLog.entity_id == ended['id'], AuditLog.action == 'gaming_session_voided')) == 0


async def test_void_and_pos_handoff_have_only_one_atomic_winner(client, session, seed_owner):
    token, ended = await _stopped(client, session, seed_owner)
    sid = ended['id']
    voided, sent = await asyncio.gather(
        client.post(f'/api/v1/gaming/sessions/{sid}/cancel', json=_void_body(ended),
            headers=_headers(seed_owner, token, f'gaming-session-void:{sid}')),
        client.post(f'/api/v1/gaming/sessions/{sid}/send-to-pos', headers=_headers(seed_owner, token)),
    )
    assert (voided.status_code, sent.status_code) in {(200, 422), (422, 201)}, (voided.text, sent.text)
    current = await client.get(f'/api/v1/gaming/sessions/{sid}', headers=_headers(seed_owner, token))
    assert current.status_code == 200, current.text
    if current.json()['status'] == 'cancelled':
        assert current.json()['order_id'] is None and current.json()['amount_minor'] == 0
        assert await session.scalar(select(func.count(Order.id)).where(Order.company_id == seed_owner['company'].id)) == 0
    else:
        assert current.json()['status'] == 'ended' and current.json()['order_id'] is not None
        assert current.json()['amount_minor'] == ended['amount_minor']
        order_id = UUID(current.json()['order_id'])
        assert await session.scalar(select(func.count(Order.id)).where(
            Order.company_id == seed_owner['company'].id, Order.id == order_id,
        )) == 1
        assert await session.scalar(select(func.count(Order.id)).where(
            Order.company_id == seed_owner['company'].id,
        )) == 1
        assert await session.scalar(select(func.count(AuditLog.id)).where(
            AuditLog.company_id == seed_owner['company'].id,
            AuditLog.entity_id == ended['id'], AuditLog.action == 'gaming_session_voided',
        )) == 0


async def test_corrupt_cancelled_extension_chain_is_not_accepted_as_void_confirmation(client, session, seed_owner):
    token, ended = await _stopped(client, session, seed_owner, extended=True)
    sid = ended['id']; path = f'/api/v1/gaming/sessions/{sid}/cancel'; key = f'gaming-session-void:{sid}'
    payload = _void_body(ended)
    response = await client.post(path, json=payload, headers=_headers(seed_owner, token, key))
    assert response.status_code == 200, response.text
    # Simulate restored/corrupt evidence while retaining the same extension total.
    # Never disable append-only guards in product code; only the isolated fixture uses SQL.
    await session.execute(text('ALTER TABLE gaming_session_extensions DISABLE TRIGGER USER'))
    await session.execute(text('UPDATE gaming_session_extensions SET timer_before_minutes=timer_before_minutes+1, '
        'timer_after_minutes=timer_after_minutes+1 WHERE gaming_session_id=:id'), {'id': UUID(sid)})
    await session.execute(text('ALTER TABLE gaming_session_extensions ENABLE TRIGGER USER'))
    await session.execute(text('DELETE FROM idempotency_keys WHERE key=:key'), {'key': key})
    await session.commit()
    for response in [
        await client.get(f'/api/v1/gaming/sessions/{sid}', headers=_headers(seed_owner, token)),
        await client.post(path, json=payload, headers=_headers(seed_owner, token, key)),
    ]:
        assert response.status_code == 409, response.text
        assert response.json()['error']['code'] == 'gaming_billing_repair_required'


@pytest.mark.parametrize('field', ['amount_minor', 'billable_minutes'])
async def test_corrupt_stopped_projection_is_rejected_before_void_receipt(client, session, seed_owner, field):
    token, ended = await _stopped(client, session, seed_owner)
    sid = ended['id']
    assert field in {'amount_minor', 'billable_minutes'}
    await session.execute(text(f'UPDATE gaming_sessions SET {field}={field}+1 WHERE id=:id'), {'id': UUID(sid)})
    await session.commit()
    result = await client.post(f'/api/v1/gaming/sessions/{sid}/cancel', json=_void_body(ended),
        headers=_headers(seed_owner, token, f'gaming-session-void:{sid}'))
    assert result.status_code == 409, result.text
    assert result.json()['error']['code'] == 'gaming_billing_repair_required'
    assert await session.scalar(select(func.count(AuditLog.id)).where(AuditLog.company_id == seed_owner['company'].id,
        AuditLog.entity_id == ended['id'], AuditLog.action == 'gaming_session_voided')) == 0


async def test_durable_void_replay_cannot_be_claimed_by_another_actor(client, session, seed_owner):
    token, ended = await _stopped(client, session, seed_owner)
    sid = ended['id']; path = f'/api/v1/gaming/sessions/{sid}/cancel'; key = f'gaming-session-void:{sid}'
    payload = _void_body(ended)
    accepted = await client.post(path, json=payload, headers=_headers(seed_owner, token, key))
    assert accepted.status_code == 200, accepted.text
    role = (await session.execute(select(Role).where(Role.company_id == seed_owner['company'].id, Role.code == 'owner'))).scalar_one()
    other = User(id=uuid4(), company_id=seed_owner['company'].id, name='Other owner',
        email=f'other-{uuid4().hex[:8]}@test.local', password_hash=hash_password('password1234'), status='active')
    session.add(other); await session.flush()
    session.add(UserRole(id=uuid4(), user_id=other.id, role_id=role.id))
    await session.execute(text('DELETE FROM idempotency_keys WHERE key=:key'), {'key': key})
    await session.commit()
    other_token = await _login(client, other.email, 'password1234')
    replay = await client.post(path, json=payload, headers=_headers(seed_owner, other_token, key))
    assert replay.status_code == 409, replay.text
    original = await client.post(path, json=payload, headers=_headers(seed_owner, token, key))
    assert original.status_code == 200 and original.json() == accepted.json()
