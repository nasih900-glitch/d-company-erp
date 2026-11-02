import { describe, expect, it } from 'vitest';
import { gamingCancellationBlock, matchesCancellationIdentity, isConfirmedCancellation, removeConfirmedCancelledSession, participantVoidReview, participantVoidMetadataReady, type GamingCancellationContext } from './GamingCancellation';

const ready: GamingCancellationContext = {
  online: true, status: 'active', hasOrder: false, isOwner: false, ownsCurrentShift: true,
  hasPendingActions: false, hasParticipantEvidence: false, addonLedgerReady: true,
  hasActiveAddons: false, hasUnresolvedExtension: false,
};

describe('active session cancellation', () => {
  it('allows a clean active or paused session while retaining ended cancellation', () => {
    for (const status of ['active', 'paused', 'ended']) {
      expect(gamingCancellationBlock({ ...ready, status })).toBeNull();
    }
  });
  it('permits atomic active, paused and ended participant Void only with billing-mode evidence', () => {
    const activePackage = {
      status: 'active', billing_mode: 'package' as const,
      participant_revision: 3, billing_revision: 1, amount_minor: 18_000,
    };
    const pausedHourly = {
      status: 'paused', billing_mode: 'hourly' as const,
      participant_revision: 4, billing_revision: 2, amount_minor: null,
    };
    expect(participantVoidMetadataReady(activePackage)).toBe(true);
    expect(participantVoidReview('package', activePackage)).toEqual({
      idempotencyKey: 'gaming-session-void:package', expected_participant_revision: 3,
      expected_billing_revision: 1, expected_amount_minor: 18_000,
    });
    expect(participantVoidMetadataReady(pausedHourly)).toBe(true);
    expect(participantVoidReview('hourly', pausedHourly)).toEqual({
      idempotencyKey: 'gaming-session-void:hourly', expected_participant_revision: 4,
      expected_billing_revision: 2,
    });
    expect(gamingCancellationBlock({
      ...ready, status: 'active', hasParticipantEvidence: true, participantVoidReady: true,
    })).toBeNull();
    expect(gamingCancellationBlock({ ...ready, status: 'ended', hasParticipantEvidence: true })).toContain('Refresh Gaming');
    expect(gamingCancellationBlock({ ...ready, status: 'ended', hasParticipantEvidence: true, participantVoidReady: true })).toBeNull();
    const ended = { status: 'ended', billing_mode: 'package' as const, participant_revision: 3, billing_revision: 1, amount_minor: 18_000 };
    expect(participantVoidReview('session', ended)).toEqual({
      idempotencyKey: 'gaming-session-void:session', expected_participant_revision: 3,
      expected_billing_revision: 1, expected_amount_minor: 18_000,
    });
    expect(participantVoidMetadataReady({ ...ended, billing_revision: undefined })).toBe(false);
    expect(participantVoidMetadataReady({ ...ended, amount_minor: null })).toBe(false);
    expect(participantVoidMetadataReady({ ...activePackage, amount_minor: null })).toBe(false);
    expect(participantVoidMetadataReady({ ...pausedHourly, amount_minor: 1 })).toBe(false);
    expect(participantVoidMetadataReady({ ...activePackage, billing_mode: 'legacy_ambiguous' })).toBe(false);
  });
  it('blocks offline, foreign shift, consumed work, and every unresolved operation', () => {
    const changes: Partial<GamingCancellationContext>[] = [
      { online: false }, { ownsCurrentShift: false }, { hasOrder: true },
      { hasPendingActions: true }, { hasParticipantEvidence: true },
      { addonLedgerReady: false }, { hasActiveAddons: true },
      { hasUnresolvedExtension: true }, { status: 'cancelled' }, { status: 'starting' },
    ];
    for (const change of changes) expect(gamingCancellationBlock({ ...ready, ...change })).toBeTruthy();
    expect(gamingCancellationBlock({ ...ready, hasOrder: true, isOwner: true })).toContain('Owner POS review');
    expect(gamingCancellationBlock({ ...ready, ownsCurrentShift: false, isOwner: true })).toBeNull();
  });
});

describe('cancellation response identity', () => {
  const expected = { id: 'session-1', stationId: 'station-1', shiftId: 'shift-1' };
  const receipt = { id: 'session-1', station_id: 'station-1', shift_id: 'shift-1', status: 'cancelled', order_id: null, amount_minor: 0, billable_minutes: 0, cancel_reason: 'Recorded mistake' };
  it('requires the exact session, station, shift and cancelled state', () => {
    expect(isConfirmedCancellation(receipt, expected)).toBe(true);
    for (const changed of [{ id: 'other' }, { station_id: 'other' }, { shift_id: 'other' },
      { status: 'active' }, { order_id: 'paid-order' }, { amount_minor: 1 }, { billable_minutes: 1 }, { cancel_reason: ' ' }]) {
      expect(isConfirmedCancellation({ ...receipt, ...changed }, expected)).toBe(false);
    }
    expect(matchesCancellationIdentity({ ...receipt, id: 'other', status: 'cancelled' }, expected)).toBe(false);
  });
  it('keeps a replacement session loaded by an intervening refresh', () => {
    const replaced = { 'station-1': { backend_session_id: 'replacement' } };
    expect(removeConfirmedCancelledSession(replaced, 'station-1', 'session-1')).toBe(replaced);
    expect(removeConfirmedCancelledSession({ 'station-1': { backend_session_id: 'session-1' } }, 'station-1', 'session-1')).toEqual({});
  });
});
