export type StaffStatus = 'active' | 'suspended';

export interface StaffAccessPatch {
  role_code?: string;
  status?: StaffStatus;
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
): T[] {
  return roles.filter((role) => (
    role.code !== 'super_owner'
    && (canManageOwnerAccess || !OWNER_ROLE_CODES.has(role.code))
  ));
}

interface StaffAccessPatchInput {
  originalRoleCode: string;
  selectedRoleCode: string;
  roleSelectionChanged: boolean;
  originalStatus: StaffStatus;
  selectedStatus: StaffStatus;
  accessChangesLocked: boolean;
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
}: StaffAccessPatchInput): StaffAccessPatch {
  if (accessChangesLocked) return {};

  const patch: StaffAccessPatch = {};
  if (roleSelectionChanged && selectedRoleCode !== originalRoleCode) {
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
