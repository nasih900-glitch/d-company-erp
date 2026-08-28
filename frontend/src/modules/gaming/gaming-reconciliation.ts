export const GAMING_SOURCE_SHIFT_CLOSED_CODE = 'gaming_source_shift_closed';

const LEGACY_GAMING_WRITE_ROLES = new Set([
  'super_owner',
  'co_owner',
  'owner',
  'manager',
  'gaming_supervisor',
]);

/** Match the backend's exact gaming.write decision, with a fail-closed
 * compatibility path for /auth/me payloads that predate effective_permissions.
 */
export function canManageGamingSessions({
  liveMode,
  effectivePermissions,
  protectedAccess,
  roles,
}: {
  liveMode: boolean;
  effectivePermissions?: string[];
  protectedAccess?: boolean;
  roles?: string[];
}): boolean {
  if (!liveMode) return true;
  if (effectivePermissions !== undefined) {
    return effectivePermissions.includes('gaming.write');
  }
  if (protectedAccess) return true;
  return roles?.some((role) => LEGACY_GAMING_WRITE_ROLES.has(role)) ?? false;
}

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

/**
 * Resolve the shift scope for an operational Stop.
 *
 * Older deployed APIs omitted SessionRead.shift_id. A server-known session
 * may still be stopped when this browser has independently confirmed the
 * current terminal's open shift; the backend remains authoritative for the
 * real session/branch/terminal check. A known different shift never falls
 * through this compatibility path.
 */
export function resolveGamingStopShiftId({
  liveMode,
  currentShiftId,
  currentShiftConfirmed,
  sessionShiftId,
  serverSessionKnown,
}: {
  liveMode: boolean;
  currentShiftId?: string | null;
  currentShiftConfirmed: boolean;
  sessionShiftId?: string | null;
  serverSessionKnown: boolean;
}): string | null {
  if (!liveMode) return sessionShiftId ?? currentShiftId ?? 'demo';
  if (currentShiftId && sessionShiftId === currentShiftId) return currentShiftId;
  if (
    currentShiftId
    && !sessionShiftId
    && currentShiftConfirmed
    && serverSessionKnown
  ) {
    return currentShiftId;
  }
  return null;
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
