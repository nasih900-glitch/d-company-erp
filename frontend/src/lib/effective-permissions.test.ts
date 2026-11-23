import { describe, expect, it } from 'vitest';
import { canAccessBusinessRoute, hasExactPermission } from './effective-permissions';
import { visibleProfileNavigationGroups, webLandingRouteFor } from './product-profile';

const reads = ['finance.read', 'analytics.read', 'staff.read', 'pos.read', 'gaming.read'];
const access = (roles: string[], effective_permissions: string[]) => ({
  hasAuditAccess: false, hasSystemAccess: false, hasMembershipAccess: false,
  hasRefundAccess: false, hasProductManagementAccess: false, accessibleModules: [],
  identity: { roles, effective_permissions },
});

describe('exact business navigation authority', () => {
  it.each(['owner', 'manager', 'auditor', 'partner', 'co_owner', 'super_owner'])('opens authorized business reads for %s without protected-owner inference', (role) => {
    const identity = { roles: [role], effective_permissions: reads, protected_access: false, accessible_modules: [] };
    const routes = visibleProfileNavigationGroups(access([role], reads)).flatMap((group) => group.items.map((item) => item.to));
    for (const route of ['/finance', '/reports', '/staff', '/analytics']) {
      expect(canAccessBusinessRoute(identity, route)).toBe(true);
      expect(routes).toContain(route);
    }
    expect(webLandingRouteFor(identity)).toBe('/analytics');
    expect(routes).not.toContain('/audit');
  });

  it('keeps Store Staff operational with discounts and no business administration', () => {
    const permissions = ['pos.read', 'pos.write', 'pos.discount.large', 'pos.shift.open', 'pos.shift.close', 'gaming.read', 'gaming.write', 'menu.read', 'staff.attendance.write'];
    const identity = { roles: ['store_staff'], effective_permissions: permissions, protected_access: false, accessible_modules: ['pos', 'gaming'] };
    expect(webLandingRouteFor(identity)).toBe('/gaming');
    const routes = visibleProfileNavigationGroups({ ...access(identity.roles, permissions), hasStaffTimeAccess: true }).flatMap((group) => group.items.map((item) => item.to));
    expect(routes).toContain('/staff/time');
    expect(routes).not.toContain('/finance');
    expect(routes).not.toContain('/staff');
    expect(hasExactPermission(identity, 'pos.discount.large')).toBe(true);
    for (const permission of ['pos.void', 'pos.refund', 'finance.write', 'staff.write', 'staff.payroll.write']) {
      expect(hasExactPermission(identity, permission)).toBe(false);
    }
  });

  it('denies business routes for explicit empty or absent capabilities regardless of owner flags', () => {
    for (const effective_permissions of [[], undefined]) {
      const identity = { roles: ['owner'], effective_permissions, protected_access: true, accessible_modules: ['finance', 'insights_reports', 'staff'] };
      for (const route of ['/finance', '/reports', '/staff', '/analytics']) expect(canAccessBusinessRoute(identity, route)).toBe(false);
      expect(webLandingRouteFor(identity)).toBe('/workspace-unavailable');
    }
    expect(canAccessBusinessRoute({ roles: ['regional_owner_manager'], effective_permissions: reads }, '/finance')).toBe(false);
  });
});
