import { describe, expect, it } from 'vitest';

import { buildStaffAccessPatch, canChangeStaffAccess } from './staff-edit-policy';

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

  it('lets an operational co-owner manage non-owner staff access', () => {
    expect(canChangeStaffAccess({
      callerUserId: 'co-owner',
      targetUserId: 'cashier',
      targetRoles: ['cashier'],
      callerHasAuditAccess: false,
    })).toBe(true);
  });
});
