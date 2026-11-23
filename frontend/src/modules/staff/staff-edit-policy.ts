import { EVERYDAY_ROLE_CODES } from '@/lib/roles';

export type StaffStatus = 'active' | 'suspended';

export interface StaffAccessPatch {
  role_code?: string;
  status?: StaffStatus;
  manual_discount_allowed?: boolean;
}

interface StaffRoleOption {
  code: string;
}

const OWNER_ROLE_CODES = new Set(['super_owner', 'co_owner', 'owner']);

/**
 * Keep the role picker aligned with backend authority even when role-catalog
 * loading falls back to a local list. The backend still enforces this rule;
 * filtering here prevents a guaranteed-to-fail option from misleading staff.
 */
export function editableStaffRoleOptions<T extends StaffRoleOption>(
  roles: readonly T[],
  canManageOwnerAccess: boolean,
  hasManagedRoles = false,
): T[] {
  return roles.filter((role) => (
    role.code !== 'super_owner' && role.code !== 'pos_discount'
    && (role.code !== 'co_owner' || hasManagedRoles)
    && (canManageOwnerAccess || !OWNER_ROLE_CODES.has(role.code))
  ));
}

/** Keep catalog order out of the everyday choices, and retain legacy selections. */
export function staffRoleOptionGroups<T extends StaffRoleOption>(roles: readonly T[]) {
  const everyday = EVERYDAY_ROLE_CODES.flatMap((code) => roles.filter((role) => role.code === code));
  const advanced = roles.filter((role) => !EVERYDAY_ROLE_CODES.some((code) => code === role.code));
  return { everyday, advanced };
}

interface StaffAccessPatchInput {
  originalRoleCode: string;
  selectedRoleCode: string;
  roleSelectionChanged: boolean;
  originalStatus: StaffStatus;
  selectedStatus: StaffStatus;
  accessChangesLocked: boolean;
  roleChangesLocked?: boolean;
  hasMultiplePrimaryRoles?: boolean;
}

/**
 * Build the access-control portion of a Staff PATCH as a semantic diff.
 *
 * Staff list responses deliberately mask both internal owner tiers as the
 * public `owner` title. Resending that displayed value during an unrelated
 * profile edit can therefore replace a real `co_owner` assignment with the
 * narrower public `owner` role. Role updates must only be sent after an
 * operator actually uses the role selector, and locked access fields must
 * never leak into the request body.
 */
export function buildStaffAccessPatch({
  originalRoleCode,
  selectedRoleCode,
  roleSelectionChanged,
  originalStatus,
  selectedStatus,
  accessChangesLocked,
  roleChangesLocked = accessChangesLocked,
  hasMultiplePrimaryRoles = false,
}: StaffAccessPatchInput): StaffAccessPatch {
  if (accessChangesLocked) return {};

  const patch: StaffAccessPatch = {};
  if (!roleChangesLocked && roleSelectionChanged && (selectedRoleCode !== originalRoleCode || hasMultiplePrimaryRoles)) {
    patch.role_code = selectedRoleCode;
  }
  if (selectedStatus !== originalStatus) {
    patch.status = selectedStatus;
  }
  return patch;
}

export function canChangeStaffAccess({
  callerUserId,
  targetUserId,
  targetRoles,
  callerHasAuditAccess,
}: {
  callerUserId: string | null;
  targetUserId: string;
  targetRoles: readonly string[];
  callerHasAuditAccess: boolean;
}): boolean {
  if (callerUserId === targetUserId) return false;
  if (targetRoles.includes('super_owner')) return false;
  const targetIsOwner = targetRoles.some((role) => OWNER_ROLE_CODES.has(role));
  return !targetIsOwner || callerHasAuditAccess;
}

/** Never resend an unchanged grant: real role writes revoke active sessions. */
export function buildStaffDiscountPatch({
  originalAllowed, selectedAllowed, canManage, selectedRoleCode, primaryRoleWillBeReplaced = false,
}: {
  originalAllowed: boolean; selectedAllowed: boolean;
  canManage: boolean; selectedRoleCode: string;
  primaryRoleWillBeReplaced?: boolean;
}): Pick<StaffAccessPatch, 'manual_discount_allowed'> {
  return canManage && selectedRoleCode === 'gaming_cashier'
    && (originalAllowed !== selectedAllowed || (primaryRoleWillBeReplaced && selectedAllowed))
    ? { manual_discount_allowed: selectedAllowed } : {};
}
