import { describe, expect, it } from 'vitest';

import {
  GAMING_SOURCE_SHIFT_CLOSED_CODE,
  canOfferGamingReconciliation,
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
