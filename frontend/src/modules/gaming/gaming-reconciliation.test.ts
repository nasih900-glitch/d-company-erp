import { describe, expect, it } from 'vitest';

import {
  GAMING_SOURCE_SHIFT_CLOSED_CODE,
  canOfferGamingReconciliation,
  isGamingActiveBillingModeVerified,
  isGamingSessionOwnedByCurrentShift,
} from './gaming-reconciliation';

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
});
