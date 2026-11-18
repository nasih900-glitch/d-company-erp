// @vitest-environment happy-dom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import type { UserDTO } from '@/lib/erp-api';
import { action, id, scope, serialLocks } from '@/test-fixtures/paid-bill';
import { PaidBillJournal } from '@/lib/paid-bill-journal';
const m = vi.hoisted(() => ({ request: vi.fn(), confirm: vi.fn() }));
vi.mock('@/modules/auth/AuthContext', () => ({ useAuth: () => ({ me: { user_id: '00000000-0000-4000-8000-000000000002', effective_permissions: ['staff.write'] } }) }));
vi.mock('@/lib/erp-api', () => ({ auth: { requestPasswordReset: m.request, confirmPasswordReset: m.confirm }, staff: {} }));
vi.mock('@/lib/paid-bill-api', () => ({ currentPaidEndpoint: () => 'http://localhost:3000/api/v1' }));
vi.mock('@/lib/api', () => ({ readAccessToken: () => null, readSessionGeneration: () => 1 }));
vi.mock('@/lib/operational-context', () => ({ readStoredTerminalId: () => '00000000-0000-4000-8000-000000000004' }));
import { PasswordModal } from './StaffScreen';
let root: Root, host: HTMLDivElement;
beforeEach(() => {
  (globalThis as unknown as { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
  localStorage.clear(); vi.clearAllMocks(); Object.defineProperty(navigator, 'locks', { configurable: true, value: serialLocks() });
  m.request.mockResolvedValue({ challenge_id: id(60), destination: 'Synthetic approval inbox' }); m.confirm.mockResolvedValue({});
  host = document.createElement('div'); document.body.append(host); root = createRoot(host);
});
afterEach(() => { act(() => root.unmount()); document.body.replaceChildren(); localStorage.clear(); });
async function render(self: boolean) { await act(async () => root.render(<PasswordModal user={{ id: self ? scope.userId : id(99), name: 'Synthetic user', email: 'synthetic@test.local' } as UserDTO} onClose={vi.fn()} onSuccess={vi.fn()}/>)); }
async function submit() { await act(async () => document.querySelector('form')!.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))); }
async function input(label: string, value: string) {
  const field = [...document.querySelectorAll('label')].find(n => n.textContent?.startsWith(label))!.querySelector('input')!;
  await act(async () => { Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')!.set!.call(field, value); field.dispatchEvent(new Event('input', { bubbles: true })); });
}
function pending() { const j = new PaidBillJournal(scope); j.ensureReady(); j.reserve(action(), 0); return j; }
it('blocks signed-in self approval-code request with unknown paid work before the endpoint', async () => {
  const j = pending(); await render(true); await submit(); expect(m.request).not.toHaveBeenCalled(); expect(m.confirm).not.toHaveBeenCalled(); expect(j.read().pending).toEqual(action()); expect(document.body.textContent).toContain('Resolve the saved Owner paid-bill action');
});
it('blocks signed-in self confirmation when paid work appears after the approval-code request', async () => {
  await render(true); await submit(); expect(m.request).toHaveBeenCalledTimes(1); const j = pending();
  await input('6-digit approval code', '123456'); await input('New password', 'synthetic-password'); await input('Confirm new password', 'synthetic-password'); await submit();
  expect(m.confirm).not.toHaveBeenCalled(); expect(j.read().pending).toEqual(action());
});
it.each([true, false])('preserves normal clear-context self=%s request and confirmation', async self => {
  await render(self); await submit(); await input('6-digit approval code', '123456'); await input('New password', 'synthetic-password'); await input('Confirm new password', 'synthetic-password'); await submit();
  expect(m.request).toHaveBeenCalledTimes(1); expect(m.confirm).toHaveBeenCalledWith(id(60), '123456', 'synthetic-password'); expect(document.body.textContent).toContain('Password updated');
});
it('preserves nonself staff password administration while the actor has paid work', async () => {
  const j = pending(); await render(false); await submit(); await input('6-digit approval code', '123456'); await input('New password', 'synthetic-password'); await input('Confirm new password', 'synthetic-password'); await submit();
  expect(m.request).toHaveBeenCalledTimes(1); expect(m.confirm).toHaveBeenCalledTimes(1); expect(j.read().pending).toEqual(action());
});
