import { isValidElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { Navigate } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';

import { SupportAccessOnly } from './App';

const auth = vi.hoisted(() => ({
  me: null as {
    audit_access?: boolean;
    protected_access?: boolean;
    effective_permissions?: string[];
  } | null,
}));

vi.mock('@/modules/auth/AuthContext', () => ({
  useAuth: () => ({ me: auth.me, demo: false }),
}));

describe('admin.support route protection', () => {
  it('renders Device Centre only for the exact tenant support permission', () => {
    auth.me = {
      audit_access: false,
      protected_access: false,
      effective_permissions: ['admin.support'],
    };
    const allowed = renderToStaticMarkup(
      SupportAccessOnly({ children: <div>Owner Device Centre</div> }),
    );
    expect(allowed).toContain('Owner Device Centre');

    auth.me = {
      audit_access: false,
      protected_access: true,
      effective_permissions: ['admin.support'],
    };
    const coOwner = renderToStaticMarkup(
      SupportAccessOnly({ children: <div>Owner Device Centre</div> }),
    );
    expect(coOwner).toContain('Owner Device Centre');

    auth.me = {
      audit_access: false,
      protected_access: true,
      effective_permissions: [],
    };
    const protectedWithoutPermission = SupportAccessOnly({
      children: <div>Owner Device Centre</div>,
    });
    expect(isValidElement(protectedWithoutPermission)).toBe(true);
    expect(protectedWithoutPermission.type).toBe(Navigate);
    expect(protectedWithoutPermission.props).toMatchObject({ to: '/pos', replace: true });

    auth.me = { audit_access: true, effective_permissions: [] };
    const missingPermission = SupportAccessOnly({ children: <div>Owner Device Centre</div> });
    expect(isValidElement(missingPermission)).toBe(true);
    expect(missingPermission.type).toBe(Navigate);
    expect(missingPermission.props).toMatchObject({ to: '/pos', replace: true });

    auth.me = null;
    const anonymous = SupportAccessOnly({ children: <div>Owner Device Centre</div> });
    expect(isValidElement(anonymous)).toBe(true);
    expect(anonymous.type).toBe(Navigate);
  });
});
