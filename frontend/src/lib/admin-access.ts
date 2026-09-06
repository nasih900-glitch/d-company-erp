/** Protected audit and evidence controls remain narrower than owner access. */
type AdminIdentity = {
  audit_access?: boolean | null;
  protected_access?: boolean | null;
  effective_permissions?: readonly string[] | null;
} | null | undefined;

export function hasAuditAccess(identity: AdminIdentity): boolean {
  return identity?.audit_access === true;
}

export function hasAdminSystemAccess(
  identity: AdminIdentity,
): boolean {
  return hasAuditAccess(identity);
}

/**
 * Current servers expose tenant support authority as an exact permission.
 * Audit access is a safe compatibility fallback for older `/auth/me` payloads
 * that predate `effective_permissions`.
 */
export function hasSupportAccess(identity: AdminIdentity): boolean {
  if (identity?.effective_permissions !== undefined) {
    return identity.effective_permissions?.includes('admin.support') === true;
  }
  return hasAuditAccess(identity);
}
