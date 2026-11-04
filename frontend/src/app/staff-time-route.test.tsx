// @vitest-environment happy-dom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { MemoryRouter, Outlet } from 'react-router-dom';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
const auth = vi.hoisted(() => ({
  me: {
    user_id: 'worker',
    name: 'Worker',
    roles: ['gaming_cashier'],
    protected_access: false,
    effective_permissions: ['staff.attendance.write'],
    accessible_modules: ['gaming']
  }
}));
vi.mock('@/modules/auth/AuthContext', () => ({
  useAuth: () => ({
    me: auth.me,
    demo: false
  })
}));
vi.mock('@/modules/auth/RequireAuth', () => ({
  default: ({
    children
  }: {
    children: React.ReactNode;
  }) => children
}));
vi.mock('@/components/layout/AppShell', () => ({
  default: () => <Outlet />
}));
vi.mock('@/modules/staff/StaffTimeScreen', () => ({
  default: () => <p>Working-time screen</p>
}));
vi.mock('@/modules/staff/StaffScreen', () => ({
  default: () => <p>Account administration screen</p>
}));
vi.mock('@/modules/gaming/GamingScreen', () => ({
  default: () => <p>Gaming landing</p>
}));
import App from './App';
let host: HTMLDivElement, root: Root;
const env = globalThis as typeof globalThis & {
  IS_REACT_ACT_ENVIRONMENT?: boolean;
};
beforeEach(() => {
  env.IS_REACT_ACT_ENVIRONMENT = true;
  auth.me = {
    user_id: 'worker',
    name: 'Worker',
    roles: ['gaming_cashier'],
    protected_access: false,
    effective_permissions: ['staff.attendance.write'],
    accessible_modules: ['gaming']
  };
  host = document.createElement('div');
  document.body.append(host);
  root = createRoot(host);
});
afterEach(() => {
  act(() => root.unmount());
  host.remove();
  delete env.IS_REACT_ACT_ENVIRONMENT;
});
async function render(path: string) {
  await act(async () => root.render(<MemoryRouter initialEntries={[path]}><App /></MemoryRouter>));
}
it('allows the attendance-only route without the staff directory module', async () => {
  await render('/staff/time');
  expect(host.textContent).toContain('Working-time screen');
  expect(host.textContent).not.toContain('Account administration');
});
it('retains the protected-owner gate on the original administration route', async () => {
  await render('/staff');
  expect(host.textContent).not.toContain('Account administration');
  expect(host.textContent).toContain('Gaming landing');
});
it('rejects the new route when effective attendance/pay permission is absent', async () => {
  auth.me.effective_permissions = [];
  await render('/staff/time');
  expect(host.textContent).not.toContain('Working-time screen');
  expect(host.textContent).toContain('Gaming landing');
});
