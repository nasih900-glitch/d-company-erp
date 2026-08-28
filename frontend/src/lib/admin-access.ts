/**
 * `admin.system` is deliberately narrower than general owner access.
 *
 * The backend exposes that effective permission as `audit_access` on
 * `/auth/me`. Keep route and navigation decisions on this single predicate so
 * a co-owner never sees an inbox the API will correctly reject.
 */
export function hasAdminSystemAccess(
  identity: { audit_access?: boolean | null } | null | undefined,
): boolean {
  return identity?.audit_access === true;
}
