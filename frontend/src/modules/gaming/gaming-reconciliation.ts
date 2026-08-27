export const GAMING_SOURCE_SHIFT_CLOSED_CODE = 'gaming_source_shift_closed';

export function isGamingSessionOwnedByCurrentShift({
  liveMode,
  currentShiftId,
  sessionShiftId,
}: {
  liveMode: boolean;
  currentShiftId?: string | null;
  sessionShiftId?: string | null;
}): boolean {
  return !liveMode || Boolean(
    currentShiftId
    && sessionShiftId
    && currentShiftId === sessionShiftId,
  );
}

export function isGamingActiveBillingModeVerified(
  billingMode?: 'hourly' | 'package' | 'legacy_ambiguous',
): boolean {
  return billingMode !== 'legacy_ambiguous';
}

export function canOfferGamingReconciliation({
  auditAccess,
  rejectionCode,
}: {
  auditAccess: boolean;
  rejectionCode?: string;
}): boolean {
  return auditAccess && rejectionCode === GAMING_SOURCE_SHIFT_CLOSED_CODE;
}
