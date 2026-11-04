import { describe, expect, it } from 'vitest';
import { roleLabel, rolesLabel } from './roles';

describe('role display labels', () => {
  it('displays the generic daily operations role without changing owner labels', () => {
    expect(roleLabel('gaming_cashier')).toBe('Gaming & POS staff');
    for (const role of ['owner', 'super_owner', 'co_owner']) expect(roleLabel(role)).toBe('Owner');
    expect(roleLabel('unknown_role')).toBe('unknown_role');
    expect(rolesLabel(['gaming_cashier', 'staff'])).toBe('Gaming & POS staff, Staff');
    expect(rolesLabel(undefined)).toBe('no role');
  });
});
