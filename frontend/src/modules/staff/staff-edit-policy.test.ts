import { describe, expect, it } from 'vitest';

import {
  buildStaffAccessPatch,
  buildStaffDiscountPatch,
  canChangeStaffAccess,
  editableStaffRoleOptions,
  staffRoleOptionGroups,
} from './staff-edit-policy';

const BASE = {
  originalRoleCode: 'owner',
  selectedRoleCode: 'owner',
  roleSelectionChanged: false,
  originalStatus: 'active' as const,
  selectedStatus: 'active' as const,
  accessChangesLocked: false,
};

describe('buildStaffAccessPatch', () => {
  it('does not resend a masked owner title during a routine profile edit', () => {
    expect(buildStaffAccessPatch(BASE)).toEqual({});
  });

  it('sends a role only after the operator explicitly selects a different role', () => {
    expect(buildStaffAccessPatch({
      ...BASE,
      selectedRoleCode: 'co_owner',
      roleSelectionChanged: true,
    })).toEqual({ role_code: 'co_owner' });
  });

  it('omits the role when the selector returns to its original value', () => {
    expect(buildStaffAccessPatch({
      ...BASE,
      roleSelectionChanged: true,
    })).toEqual({});
  });

  it('sends a changed status without resending an untouched role', () => {
    expect(buildStaffAccessPatch({
      ...BASE,
      selectedStatus: 'suspended',
    })).toEqual({ status: 'suspended' });
  });

  it('suppresses role and status when access controls are locked', () => {
    expect(buildStaffAccessPatch({
      ...BASE,
      selectedRoleCode: 'cashier',
      roleSelectionChanged: true,
      selectedStatus: 'suspended',
      accessChangesLocked: true,
    })).toEqual({});
  });
});

it('assigns the generic gaming cashier role only after an explicit selection', () => {
  expect(buildStaffAccessPatch({ ...BASE, selectedRoleCode: 'gaming_cashier', roleSelectionChanged: true }))
    .toEqual({ role_code: 'gaming_cashier' });
  expect(buildStaffAccessPatch({ ...BASE, selectedRoleCode: 'gaming_cashier', roleSelectionChanged: false }))
    .toEqual({});
});

describe('canChangeStaffAccess', () => {
  it('never offers suspend/delete against the caller account', () => {
    expect(canChangeStaffAccess({
      callerUserId: 'user-1',
      targetUserId: 'user-1',
      targetRoles: ['cashier'],
      callerHasAuditAccess: true,
    })).toBe(false);
  });

  it('keeps owner access controls private to the protected audit owner', () => {
    expect(canChangeStaffAccess({
      callerUserId: 'co-owner',
      targetUserId: 'other-owner',
      targetRoles: ['owner'],
      callerHasAuditAccess: false,
    })).toBe(false);
    expect(canChangeStaffAccess({
      callerUserId: 'protected-owner',
      targetUserId: 'other-owner',
      targetRoles: ['owner'],
      callerHasAuditAccess: true,
    })).toBe(true);
  });

  it('never offers access changes against any protected audit-owner account', () => {
    expect(canChangeStaffAccess({
      callerUserId: 'protected-owner-1',
      targetUserId: 'protected-owner-2',
      targetRoles: ['owner', 'super_owner'],
      callerHasAuditAccess: true,
    })).toBe(false);
  });

  it('lets an operational co-owner manage non-owner staff access', () => {
    expect(canChangeStaffAccess({
      callerUserId: 'co-owner',
      targetUserId: 'cashier',
      targetRoles: ['cashier'],
      callerHasAuditAccess: false,
    })).toBe(true);
  });
});

describe('editableStaffRoleOptions', () => {
  const fallback = [
    { code: 'super_owner', name: 'Protected owner' },
    { code: 'co_owner', name: 'Co-owner' },
    { code: 'owner', name: 'Owner' },
    { code: 'manager', name: 'Manager' },
    { code: 'cashier', name: 'Cashier' },
    { code: 'gaming_cashier', name: 'Gaming & POS staff' },
  ];

  it('never shows owner grants to a caller without protected authority', () => {
    expect(editableStaffRoleOptions(fallback, false).map((role) => role.code))
      .toEqual(['manager', 'cashier', 'gaming_cashier']);
  });

  it('allows owner tiers to the protected owner but never offers super_owner', () => {
    expect(editableStaffRoleOptions(fallback, true, true).map((role) => role.code))
      .toEqual(['co_owner', 'owner', 'manager', 'cashier', 'gaming_cashier']);
  });
});

describe('manual discount grants', () => {
  const base = { originalAllowed: false, selectedAllowed: true, canManage: true, selectedRoleCode: 'gaming_cashier' };
  it('sends only a changed Owner-controlled grant', () => {
    expect(buildStaffDiscountPatch(base)).toEqual({ manual_discount_allowed: true });
    expect(buildStaffDiscountPatch({ ...base, originalAllowed: true, selectedAllowed: false }))
      .toEqual({ manual_discount_allowed: false });
  });
  it('omits unchanged grants so ordinary profile edits do not revoke sessions', () => {
    expect(buildStaffDiscountPatch({ ...base, originalAllowed: true })).toEqual({});
  });
  it('never sends a grant for managers or a different primary role', () => {
    expect(buildStaffDiscountPatch({ ...base, canManage: false })).toEqual({});
    expect(buildStaffDiscountPatch({ ...base, selectedRoleCode: 'manager' })).toEqual({});
  });
  it('never offers a supplemental grant as a primary job role', () => {
    expect(editableStaffRoleOptions([{ code: 'gaming_cashier' }, { code: 'pos_discount' }], true))
      .toEqual([{ code: 'gaming_cashier' }]);
  });
  it('reapplies a selected supplement only for an authorized Owner when explicit replacement removes the old grant', () => {
    const input = { originalAllowed: true, selectedAllowed: true, canManage: true, selectedRoleCode: 'gaming_cashier', primaryRoleWillBeReplaced: true };
    expect(buildStaffDiscountPatch(input)).toEqual({ manual_discount_allowed: true });
    expect(buildStaffDiscountPatch({ ...input, canManage: false })).toEqual({});
    expect(buildStaffDiscountPatch({ ...input, primaryRoleWillBeReplaced: false })).toEqual({});
  });
});

describe('everyday roles and exact owner metadata', () => {
  it('uses three everyday role codes and keeps all legacy choices available', () => {
    const roles = ['partner', 'co_owner', 'staff', 'auditor', 'gaming_cashier', 'store_staff', 'manager', 'owner', 'cashier']
      .map((code) => ({ code }));
    const groups = staffRoleOptionGroups(editableStaffRoleOptions(roles, true, true));
    expect(groups.everyday.map((role) => role.code)).toEqual(['owner', 'store_staff', 'auditor']);
    expect(groups.advanced.map((role) => role.code)).toEqual(['partner', 'co_owner', 'staff', 'gaming_cashier', 'manager', 'cashier']);
  });

  it('does not invent an exact owner tier from protected caller authority alone', () => {
    expect(editableStaffRoleOptions([{ code: 'owner' }, { code: 'co_owner' }], true))
      .toEqual([{ code: 'owner' }]);
  });

  it('preserves hidden exact roles even if a stale editor tries to change the displayed owner title', () => {
    expect(buildStaffAccessPatch({ ...BASE, roleChangesLocked: true, selectedRoleCode: 'manager', roleSelectionChanged: true }))
      .toEqual({});
    expect(buildStaffAccessPatch({ ...BASE, originalRoleCode: 'co_owner', selectedRoleCode: 'co_owner' }))
      .toEqual({});
  });

  it('round-trips an existing legacy choice without a permission write', () => {
    expect(buildStaffAccessPatch({ ...BASE, originalRoleCode: 'partner', selectedRoleCode: 'partner', roleSelectionChanged: true }))
      .toEqual({});
  });
  it('only normalizes an existing multi-role union after explicit role intent', () => {
    const input = { ...BASE, originalRoleCode: 'auditor', selectedRoleCode: 'auditor', hasMultiplePrimaryRoles: true };
    expect(buildStaffAccessPatch(input)).toEqual({});
    expect(buildStaffAccessPatch({ ...input, roleSelectionChanged: true })).toEqual({ role_code: 'auditor' });
  });
});
