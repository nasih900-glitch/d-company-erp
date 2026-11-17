import { describe, expect, it } from 'vitest';
import type { AccessCellDTO } from '@/lib/erp-api';
import { accessCellLevel, accessCellSummary, accessLevelLabel, isAccessCellConfigurable } from './access-control-policy';

const partial: AccessCellDTO = {
  role_code: 'staff', module: 'finance', allowed: true, default_allowed: false, override: true,
  access_level: 'partial', default_access_level: 'blocked', effective_permissions: ['finance.read'],
  unavailable_permissions: ['finance.write'], ceiling_limited_permissions: ['finance.write'], configurable: true,
};

describe('access matrix presentation', () => {
  it('renders Partial for enabled modules with protected actions unavailable', () => {
    expect(accessLevelLabel(accessCellLevel(partial))).toBe('Partial');
    expect(accessCellSummary(partial)).toContain('Available actions: finance.read');
    expect(accessCellSummary(partial)).toContain('Unavailable actions: finance.write');
    expect(accessCellSummary(partial)).toContain('Unavailable even when enabled (permission ceiling): finance.write');
  });

  it('keeps a ceiling distinct from a currently blocked action', () => {
    const blocked = { ...partial, allowed: false, access_level: 'blocked' as const, effective_permissions: [], unavailable_permissions: ['finance.read', 'finance.write'] };
    expect(accessCellSummary(blocked)).toContain('Unavailable actions: finance.read, finance.write');
    expect(accessCellSummary(blocked)).toContain('(permission ceiling): finance.write');
  });

  it('never promises full access when an older server only returns a switch', () => {
    const legacy: AccessCellDTO = { role_code: 'cashier', module: 'pos', allowed: true, default_allowed: true, override: null };
    expect(accessCellLevel(legacy)).toBe('partial');
    expect(accessCellSummary(legacy)).toContain('Exact permission evidence unavailable');
    expect(isAccessCellConfigurable(legacy)).toBe(true);
  });

  it('does not offer ineffective operational owner override mutations', () => {
    const overrideOwner = { ...partial, role_code: 'co_owner', configurable: false };
    expect(isAccessCellConfigurable(overrideOwner)).toBe(false);
    expect(isAccessCellConfigurable({ ...overrideOwner, configurable: undefined })).toBe(false);
    expect(accessCellSummary(overrideOwner)).toContain('module switches cannot restrict this role');
  });
});
