/**
 * `admin.system` is deliberately narrower than general owner access.
 *
 * The backend exposes that effective permission as `audit_access` on
 * `/auth/me`. Keep route and navigation decisions on this single predicate so
 * a co-owner never sees an inbox the API will correctly reject.
 */
type AdminIdentity = {
  audit_access?: boolean | null;
  // Accepted only so callers can prove protected_access is deliberately not
  // treated as audit/system authority.
  protected_access?: boolean | null;
} | null | undefined;

export function hasAuditAccess(identity: AdminIdentity): boolean {
  return identity?.audit_access === true;
}

export function hasAdminSystemAccess(
  identity: AdminIdentity,
): boolean {
  return hasAuditAccess(identity);
}
