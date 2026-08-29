import { describe, expect, it } from 'vitest';

import {
  GAMING_SOURCE_SHIFT_CLOSED_CODE,
  canManageGamingSessions,
  canOfferGamingReconciliation,
  isGamingActiveBillingModeVerified,
  isGamingSessionOwnedByCurrentShift,
  resolveGamingStopShiftId,
} from './gaming-reconciliation';

describe('gaming write permission presentation policy', () => {
  it('uses exact effective permission and keeps the legacy fallback fail-closed', () => {
    expect(canManageGamingSessions({
      liveMode: true,
      effectivePermissions: ['gaming.read', 'gaming.write'],
      roles: ['partner'],
    })).toBe(true);
    expect(canManageGamingSessions({
      liveMode: true,
      effectivePermissions: ['gaming.read'],
      protectedAccess: true,
      roles: ['co_owner'],
    })).toBe(false);
    expect(canManageGamingSessions({
      liveMode: true,
      protectedAccess: true,
      roles: ['co_owner'],
    })).toBe(true);
    expect(canManageGamingSessions({
      liveMode: true,
      roles: ['manager'],
    })).toBe(true);
    expect(canManageGamingSessions({
      liveMode: true,
      roles: ['partner'],
    })).toBe(false);
    expect(canManageGamingSessions({ liveMode: false })).toBe(true);
  });
});

describe('gaming reconciliation presentation policy', () => {
  it('offers recovery only to the audit-access protected owner after the exact rejection', () => {
    expect(canOfferGamingReconciliation({
      auditAccess: true,
      rejectionCode: GAMING_SOURCE_SHIFT_CLOSED_CODE,
    })).toBe(true);
    expect(canOfferGamingReconciliation({
      auditAccess: false,
      rejectionCode: GAMING_SOURCE_SHIFT_CLOSED_CODE,
    })).toBe(false);
    expect(canOfferGamingReconciliation({
      auditAccess: true,
      rejectionCode: 'business_rule',
    })).toBe(false);
    expect(canOfferGamingReconciliation({
      auditAccess: true,
    })).toBe(false);
  });
});

describe('legacy gaming billing-mode policy', () => {
  it('fails active mutations closed for an ambiguous legacy mode', () => {
    expect(isGamingActiveBillingModeVerified('hourly')).toBe(true);
    expect(isGamingActiveBillingModeVerified('package')).toBe(true);
    expect(isGamingActiveBillingModeVerified('legacy_ambiguous')).toBe(false);
  });
});

describe('gaming session shift ownership policy', () => {
  it('allows demo sessions but requires an exact verified shift in live mode', () => {
    expect(isGamingSessionOwnedByCurrentShift({
      liveMode: false,
      currentShiftId: null,
      sessionShiftId: null,
    })).toBe(true);
    expect(isGamingSessionOwnedByCurrentShift({
      liveMode: true,
      currentShiftId: 'shift-current',
      sessionShiftId: 'shift-current',
    })).toBe(true);
    expect(isGamingSessionOwnedByCurrentShift({
      liveMode: true,
      currentShiftId: 'shift-current',
      sessionShiftId: 'shift-other',
    })).toBe(false);
    expect(isGamingSessionOwnedByCurrentShift({
      liveMode: true,
      currentShiftId: null,
      sessionShiftId: 'shift-other',
    })).toBe(false);
    expect(isGamingSessionOwnedByCurrentShift({
      liveMode: true,
      currentShiftId: 'shift-current',
      sessionShiftId: null,
    })).toBe(false);
  });

  it('permits only the server-known legacy null-shift stop fallback', () => {
    expect(resolveGamingStopShiftId({
      liveMode: true,
      currentShiftId: 'shift-current',
      currentShiftConfirmed: true,
      sessionShiftId: null,
      serverSessionKnown: true,
    })).toBe('shift-current');

    for (const unsafe of [
      {
        currentShiftId: 'shift-current', currentShiftConfirmed: false,
        sessionShiftId: null, serverSessionKnown: true,
      },
      {
        currentShiftId: 'shift-current', currentShiftConfirmed: true,
        sessionShiftId: null, serverSessionKnown: false,
      },
      {
        currentShiftId: 'shift-current', currentShiftConfirmed: true,
        sessionShiftId: 'shift-other', serverSessionKnown: true,
      },
      {
        currentShiftId: null, currentShiftConfirmed: true,
        sessionShiftId: null, serverSessionKnown: true,
      },
    ]) {
      expect(resolveGamingStopShiftId({ liveMode: true, ...unsafe })).toBeNull();
    }
  });
});
