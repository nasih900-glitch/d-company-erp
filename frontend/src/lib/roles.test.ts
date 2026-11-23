import { describe, expect, it } from 'vitest';
import { managedRoleLabel, primaryRoleCode, roleLabel, rolePurpose, rolesLabel } from './roles';

describe('role display labels', () => {
  it('displays the generic daily operations role without changing owner labels', () => {
    expect(roleLabel('gaming_cashier')).toBe('Gaming & POS staff (legacy)');
    expect(roleLabel('store_staff')).toBe('Store Staff');
    for (const role of ['owner', 'super_owner', 'co_owner']) expect(roleLabel(role)).toBe('Owner');
    expect(roleLabel('unknown_role')).toBe('unknown_role');
    expect(rolesLabel(['gaming_cashier', 'staff'])).toBe('Gaming & POS staff (legacy), Staff');
    expect(rolesLabel(undefined)).toBe('no role');
  });
});

it('labels the supplemental discount grant clearly', () => {
  expect(roleLabel('pos_discount')).toBe('Manual POS discounts');
  expect(primaryRoleCode(['pos_discount', 'gaming_cashier'])).toBe('gaming_cashier');
  expect(primaryRoleCode(['co_owner', 'pos_discount'])).toBe('co_owner');
});

it('keeps exact owner tiers private outside authorized management labels', () => {
  expect(managedRoleLabel('owner')).toBe('Owner');
  expect(managedRoleLabel('co_owner')).toBe('Owner (operational override)');
  expect(managedRoleLabel('super_owner')).toBe('Owner (protected audit)');
  expect(roleLabel('co_owner')).toBe('Owner');
  expect(roleLabel('super_owner')).toBe('Owner');
});

it('distinguishes an immutable business viewer from a configurable legacy partner', () => {
  expect(roleLabel('auditor')).toBe('Business Viewer');
  expect(roleLabel('partner')).toBe('Partner (legacy, configurable)');
  expect(rolePurpose('partner')).toContain('not a guaranteed read-only role');
  expect(rolePurpose('auditor')).toContain('Cannot perform writes');
});

it('describes existing broad powers without implying payroll or Owner-only voids', () => {
  expect(rolePurpose('manager')).toContain('finance writes and staff management');
  expect(rolePurpose('manager')).toContain('No payroll access by default');
  expect(rolePurpose('gaming_cashier')).toContain('POS sales and voids');
  expect(rolePurpose('gaming_cashier')).toContain('shop shifts');
});
