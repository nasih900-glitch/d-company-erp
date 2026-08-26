export const GAMING_SOURCE_SHIFT_CLOSED_CODE = 'gaming_source_shift_closed';

export function canOfferGamingReconciliation({
  auditAccess,
  rejectionCode,
}: {
  auditAccess: boolean;
  rejectionCode?: string;
}): boolean {
  return auditAccess && rejectionCode === GAMING_SOURCE_SHIFT_CLOSED_CODE;
}
