export interface PermissionIdentity {
  roles?: readonly string[] | null;
  effective_permissions?: readonly string[] | null;
}

/** A current server response is the only authority; absent and empty both deny. */
export function hasExactPermission(identity: PermissionIdentity | null | undefined, permission: string): boolean {
  return identity?.effective_permissions?.includes(permission) === true;
}

export function hasBusinessAudience(identity: PermissionIdentity | null | undefined): boolean {
  return identity?.roles?.some((role) => ['owner', 'co_owner', 'super_owner', 'manager', 'partner', 'auditor'].includes(role)) === true;
}

const BUSINESS_ROUTE_PERMISSIONS: Readonly<Record<string, readonly string[]>> = {
  '/analytics': ['analytics.read', 'finance.read'],
  '/finance': ['finance.read'],
  '/reports': ['analytics.read'],
  '/staff': ['staff.read'],
  '/insights': ['analytics.read', 'finance.read'],
};

export function canAccessBusinessRoute(identity: PermissionIdentity | null | undefined, pathname: string): boolean {
  const permissions = BUSINESS_ROUTE_PERMISSIONS[pathname];
  return permissions !== undefined && hasBusinessAudience(identity)
    && permissions.every((permission) => hasExactPermission(identity, permission));
}
