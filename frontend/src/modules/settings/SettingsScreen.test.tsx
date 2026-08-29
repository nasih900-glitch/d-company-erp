import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';

import SettingsScreen from './SettingsScreen';

type TestIdentity = {
  audit_access: boolean;
  release_control_access?: boolean;
  protected_access: boolean;
  effective_permissions: string[];
  name: string;
  email: string;
  roles: string[];
};

const auth = vi.hoisted(() => ({ me: null as TestIdentity | null }));

vi.mock('@/modules/auth/AuthContext', () => ({
  useAuth: () => ({ me: auth.me, demo: false }),
}));

function identity(overrides: Partial<TestIdentity> = {}): TestIdentity {
  return {
    audit_access: false,
    release_control_access: false,
    protected_access: false,
    effective_permissions: [],
    name: 'Owner',
    email: 'owner@dcompany.local',
    roles: ['owner'],
    ...overrides,
  };
}

describe('Settings protected device update navigation', () => {
  it('shows Devices & Updates only for the dedicated release_control_access authority', () => {
    auth.me = identity({ audit_access: true, release_control_access: true });
    const allowed = renderToStaticMarkup(<SettingsScreen />);
    expect(allowed).toContain('Devices &amp; Updates');

    auth.me = identity({ audit_access: true });
    const auditOnly = renderToStaticMarkup(<SettingsScreen />);
    expect(auditOnly).not.toContain('Devices &amp; Updates');

    auth.me = identity({
      protected_access: true,
      effective_permissions: ['settings.manage'],
    });
    const denied = renderToStaticMarkup(<SettingsScreen />);
    expect(denied).not.toContain('Devices &amp; Updates');

    auth.me = {
      audit_access: true,
      protected_access: true,
      effective_permissions: ['settings.manage'],
      name: 'Legacy owner',
      email: 'legacy@dcompany.local',
      roles: ['owner'],
    };
    const missingDedicatedGrant = renderToStaticMarkup(<SettingsScreen />);
    expect(missingDedicatedGrant).not.toContain('Devices &amp; Updates');
  });
});
