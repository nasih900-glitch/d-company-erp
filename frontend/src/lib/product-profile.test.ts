import { describe, expect, it } from 'vitest';

import {
  canManageGamingCentreProducts,
  featureForProfileRoute,
  GAMING_CENTRE_FEATURES,
  GAMING_CENTRE_TERMINAL_POLICY,
  isProfileRouteEnabled,
  profileDeferredMoneyLabel,
  profileMembershipMoneyLabel,
  profilePosCheckoutSource,
  profilePosOrderType,
  visibleProfileNavigationGroups,
  WEB_PRODUCT_PROFILE,
  type ProfileAccessContext,
} from './product-profile';

const staffAccess: ProfileAccessContext = {
  isOwner: false,
  hasAuditAccess: false,
  hasSystemAccess: false,
  hasMembershipAccess: true,
  hasRefundAccess: true,
  hasProductManagementAccess: false,
  accessibleModules: ['gaming', 'pos', 'inventory'],
};

function labels(access: ProfileAccessContext): string[] {
  return visibleProfileNavigationGroups(access)
    .flatMap((group) => group.items.map((item) => item.label));
}

describe('Gaming Centre web product profile', () => {
  it('uses one automatic Hybrid workspace with no cross-terminal handoff', () => {
    expect(GAMING_CENTRE_TERMINAL_POLICY).toEqual({
      mode: 'single_hybrid',
      showRoutineSelector: false,
      allowCrossTerminalPosHandoff: false,
    });
    expect(WEB_PRODUCT_PROFILE.terminalPolicy).toBe(GAMING_CENTRE_TERMINAL_POLICY);
  });

  it('keeps the normal staff workspace focused on the daily gaming flow', () => {
    expect(labels(staffAccess)).toEqual(['Gaming', 'POS', 'Shift', 'Stock', 'Help']);
  });

  it('adds finance and management surfaces only for an operational owner', () => {
    const owner = labels({
      ...staffAccess,
      isOwner: true,
      hasProductManagementAccess: true,
      accessibleModules: [],
    });

    expect(owner).toEqual([
      'Gaming', 'POS', 'Shift', 'Stock', 'Help',
      'Dashboard', 'Finance', 'Reports', 'Staff', 'Settings', 'Products',
    ]);
    expect(owner).not.toContain('Audit Log');
    expect(owner).not.toContain('Support Inbox');
  });

  it('keeps Audit Log and Support Inbox on the exact protected signals', () => {
    const protectedOwner = labels({
      ...staffAccess,
      isOwner: true,
      hasAuditAccess: true,
      hasSystemAccess: true,
      hasProductManagementAccess: true,
    });
    expect(protectedOwner).toContain('Audit Log');
    expect(protectedOwner).toContain('Support Inbox');

    const coOwner = labels({
      ...staffAccess,
      isOwner: true,
      hasAuditAccess: false,
      hasSystemAccess: false,
      hasProductManagementAccess: true,
    });
    expect(coOwner).not.toContain('Audit Log');
    expect(coOwner).not.toContain('Support Inbox');
  });

  it('still honours module access for non-owner operational tabs', () => {
    expect(labels({ ...staffAccess, accessibleModules: ['gaming', 'pos'] }))
      .toEqual(['Gaming', 'POS', 'Shift', 'Help']);
    expect(labels({ ...staffAccess, accessibleModules: ['pos'] }))
      .toEqual(['POS', 'Shift', 'Help']);
  });

  it('hides cafe, membership, and deferred workspaces without deleting their route registration', () => {
    const hiddenRoutes = [
      '/tables', '/kitchen', '/reservations', '/customers', '/memberships',
      '/public/menu', '/events', '/ocr', '/refunds', '/insights',
    ];
    for (const route of hiddenRoutes) {
      expect(featureForProfileRoute(route)).not.toBeNull();
      expect(isProfileRouteEnabled(route)).toBe(false);
    }

    expect(isProfileRouteEnabled('/menu')).toBe(true);
    expect(WEB_PRODUCT_PROFILE.defaultRoute).toBe('/gaming');
  });

  it('can re-enable a deferred workflow from one central feature flag', () => {
    const withMemberships = { ...GAMING_CENTRE_FEATURES, memberships: true };
    const groups = visibleProfileNavigationGroups(staffAccess, withMemberships);

    expect(isProfileRouteEnabled('/memberships?customer=123', withMemberships)).toBe(true);
    expect(groups.flatMap((group) => group.items.map((item) => item.label)))
      .toContain('Memberships');
  });

  it('hides zero membership rows but preserves non-zero legacy money under neutral labels', () => {
    expect(profileMembershipMoneyLabel('revenue', 0)).toBeNull();
    expect(profileMembershipMoneyLabel('refund', 0)).toBeNull();
    expect(profileMembershipMoneyLabel('revenue', 12_500)).toBe('Legacy prepaid programme');
    expect(profileMembershipMoneyLabel('refund', 2_500)).toBe('Legacy prepaid refunds');
    expect(profileMembershipMoneyLabel('discount', 500)).toBe('Legacy programme discount');
    expect(profileMembershipMoneyLabel('allowance', 10_000)).toBe('Legacy prepaid allowance');

    const enabled = { ...GAMING_CENTRE_FEATURES, memberships: true };
    expect(profileMembershipMoneyLabel('revenue', 0, enabled)).toBe('Memberships');
    expect(profileMembershipMoneyLabel('refund', 2_500, enabled)).toBe('Membership refunds');
  });

  it('hides deferred zero-value report rows without hiding historical money', () => {
    expect(profileDeferredMoneyLabel('eventRevenue', 0)).toBeNull();
    expect(profileDeferredMoneyLabel('deliveryRevenue', 0)).toBeNull();
    expect(profileDeferredMoneyLabel('taxCollected', 0)).toBeNull();
    expect(profileDeferredMoneyLabel('eventRevenue', 12_500)).toBe('Legacy ticket revenue');
    expect(profileDeferredMoneyLabel('deliveryRevenue', 8_000)).toBe('Legacy platform sales');
    expect(profileDeferredMoneyLabel('taxCollected', 1_800)).toBe('Recorded indirect tax');

    const enabled = {
      ...GAMING_CENTRE_FEATURES,
      events: true,
      restaurantOrderTypes: true,
      taxCompliance: true,
    };
    expect(profileDeferredMoneyLabel('eventRevenue', 0, enabled)).toBe('Event tickets');
    expect(profileDeferredMoneyLabel('deliveryRevenue', 0, enabled)).toBe('Delivery (Zomato/Swiggy)');
    expect(profileDeferredMoneyLabel('taxCollected', 0, enabled)).toBe('GST collected');
  });

  it('uses one counter-sale mode but preserves held bills and exact checkout replays', () => {
    expect(profilePosOrderType()).toBe('takeaway');
    expect(profilePosOrderType({
      orderType: 'delivery',
      hasCheckoutRetry: false,
    })).toBe('takeaway');

    expect(profilePosOrderType({
      orderType: 'delivery',
      hasCheckoutRetry: true,
    })).toBe('delivery');
    expect(profilePosOrderType({
      orderType: 'dine_in',
      hasCheckoutRetry: false,
      resumingOrderId: 'gaming-order-1',
    })).toBe('dine_in');

    expect(profilePosCheckoutSource(undefined, profilePosOrderType())).toEqual({
      kind: 'manual',
      orderType: 'takeaway',
    });
    expect(profilePosCheckoutSource('gaming-order-1', 'takeaway')).toEqual({
      kind: 'incoming',
      orderId: 'gaming-order-1',
    });
  });
});

describe('Gaming Centre product-management access', () => {
  it('uses exact menu.write when the server returns effective permissions', () => {
    expect(canManageGamingCentreProducts({
      roles: ['manager'],
      effective_permissions: ['menu.read'],
    })).toBe(false);
    expect(canManageGamingCentreProducts({
      roles: ['manager'],
      effective_permissions: ['menu.read', 'menu.write'],
    })).toBe(true);
  });

  it('uses the role fallback only for older identity responses', () => {
    expect(canManageGamingCentreProducts({ roles: ['manager'] })).toBe(true);
    expect(canManageGamingCentreProducts({ roles: ['gaming_supervisor'] })).toBe(false);
  });
});
