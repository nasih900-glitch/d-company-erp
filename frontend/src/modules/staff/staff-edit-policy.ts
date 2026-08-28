export type StaffStatus = 'active' | 'suspended';

export interface StaffAccessPatch {
  role_code?: string;
  status?: StaffStatus;
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
  const targetIsOwner = targetRoles.some((role) => (
    role === 'owner' || role === 'co_owner' || role === 'super_owner'
  ));
  return !targetIsOwner || callerHasAuditAccess;
}
