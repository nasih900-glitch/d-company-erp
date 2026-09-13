import { describe, expect, it } from 'vitest';

import { canEditPlaytimeDraft, canWriteCustomers } from './customer-access';

describe('customer capability gates', () => {
  it('lets pos readers see the surface without exposing profile writes', () => {
    const reader = { effective_permissions: ['pos.read'] };
    expect(canWriteCustomers(reader, false)).toBe(false);
    expect(canEditPlaytimeDraft(reader, false)).toBe(false);
  });

  it('uses exact write and settings capabilities', () => {
    expect(canWriteCustomers({ effective_permissions: ['pos.read', 'pos.write'] }, false)).toBe(true);
    expect(canEditPlaytimeDraft({ effective_permissions: ['settings.manage'] }, false)).toBe(true);
    expect(canWriteCustomers({ effective_permissions: ['gaming.write'] }, false)).toBe(false);
  });

  it('uses protected access only for older identities without effective permissions', () => {
    expect(canWriteCustomers({ protected_access: true }, false)).toBe(true);
    expect(canWriteCustomers({ protected_access: true, effective_permissions: [] }, false)).toBe(false);
  });
});
