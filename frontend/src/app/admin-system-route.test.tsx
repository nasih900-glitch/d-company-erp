import { isValidElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { Navigate } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';

import { AdminSystemOnly } from './App';

const auth = vi.hoisted(() => ({
  me: null as { audit_access?: boolean; protected_access?: boolean } | null,
}));

vi.mock('@/modules/auth/AuthContext', () => ({
  useAuth: () => ({ me: auth.me, demo: false }),
}));

describe('admin.system route protection', () => {
  it('renders Device Centre content only for the exact protected System Health grant', () => {
    auth.me = { audit_access: true, protected_access: false };
    const allowed = renderToStaticMarkup(
      AdminSystemOnly({ children: <div>Protected Device Centre</div> }),
    );
    expect(allowed).toContain('Protected Device Centre');

    auth.me = { audit_access: false, protected_access: true };
    const generalOwner = AdminSystemOnly({ children: <div>Protected Device Centre</div> });
    expect(isValidElement(generalOwner)).toBe(true);
    expect(generalOwner.type).toBe(Navigate);
    expect(generalOwner.props).toMatchObject({ to: '/pos', replace: true });

    auth.me = null;
    const anonymous = AdminSystemOnly({ children: <div>Protected Device Centre</div> });
    expect(isValidElement(anonymous)).toBe(true);
    expect(anonymous.type).toBe(Navigate);
  });
});
