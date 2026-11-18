// @vitest-environment happy-dom
import { act } from 'react';
import type { Root } from 'react-dom/client';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { action } from '@/test-fixtures/paid-bill';
import { TestBroadcastChannel } from '@/test-fixtures/root-recovery';
import { PaidBillJournal } from '@/lib/paid-bill-journal';
const m = vi.hoisted(() => ({ roots: [] as Root[], ordinary: 0 }));
vi.mock('@/lib/api', () => { throw new Error('ORDINARY_API_EVALUATED_DURING_COLD_BOOT'); });
vi.mock('@/app/OrdinaryApplication', () => { m.ordinary++; return { default: () => <p>Ordinary app</p> }; });
vi.mock('react-dom/client', async original => {
  const actual = await original<typeof import('react-dom/client')>();
  return { ...actual, default: { ...actual, createRoot: (...args: Parameters<typeof actual.createRoot>) => { const root = actual.createRoot(...args); m.roots.push(root); return root; } } };
});
beforeEach(() => {
  (globalThis as unknown as { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
  vi.resetModules(); localStorage.clear(); m.roots = []; m.ordinary = 0;
  vi.stubGlobal('BroadcastChannel', TestBroadcastChannel); vi.stubGlobal('fetch', vi.fn()); vi.stubEnv('VITE_ROUTER_MODE', 'browser');
  window.history.replaceState(null, '', '/owner/paid-bill-recovery');
  document.body.innerHTML = '<div id="root"></div>';
});
afterEach(() => { for (const root of m.roots) act(() => root.unmount()); document.body.replaceChildren(); TestBroadcastChannel.peers.clear(); vi.unstubAllGlobals(); vi.unstubAllEnvs(); });
it.each(['pending', 'corrupt'])('actual cold main boot keeps ordinary records byte-exact and shows only generic %s work', async kind => {
  const a = action(); const j = new PaidBillJournal(a.scope); j.ensureReady(); j.reserve(a, 0);
  if (kind === 'corrupt') localStorage.setItem('dcompany:paid-bill-amendment:v1', '{PRIVATE_CORRUPT_MARKER');
  for (const [key, value] of Object.entries({ access_token: 'ORDINARY_ACCESS_MARKER', refresh_token: 'ORDINARY_REFRESH_MARKER', pricing_token: 'ORDINARY_PRICING_MARKER', pricing_token_expires_at: '123', terminal_id: 'ACTIVE_B_MARKER' })) localStorage.setItem(key, value);
  const before = { ...localStorage };
  await act(async () => { await import('../main'); });
  expect({ ...localStorage }).toEqual(before); expect(m.ordinary).toBe(0); expect(fetch).not.toHaveBeenCalled();
  const dom = document.body.innerHTML;
  for (const secret of [a.key, a.body.reason, a.expected.invoice_no, a.scope.userId, a.scope.terminalId, 'PRIVATE_CORRUPT_MARKER', 'ORDINARY_ACCESS_MARKER', 'ORDINARY_REFRESH_MARKER', 'ACTIVE_B_MARKER']) expect(dom).not.toContain(secret);
  expect(document.body.textContent).toContain('Recover saved Owner work');
  expect(document.querySelector('input[type=password]')?.getAttribute('autocomplete')).toBe('current-password');
});
async function enter(node: HTMLInputElement | HTMLTextAreaElement, value: string) {
  await act(async () => {
    Object.getOwnPropertyDescriptor(Object.getPrototypeOf(node), 'value')!.set!.call(node, value);
    node.dispatchEvent(new Event('input', { bubbles: true }));
  });
}
it('clears a typed pre-authentication password on root and journal changes without sending it', async () => {
  await act(async () => { await import('../main'); });
  const input = document.querySelector<HTMLInputElement>('input[type=password]')!;
  await enter(input, 'typed secret'); expect(input.value).toBe('typed secret');
  await act(async () => { window.dispatchEvent(new StorageEvent('storage', { key: 'terminal_id' })); });
  expect(input.value).toBe('');
  await enter(input, 'another secret');
  await act(async () => { window.dispatchEvent(new StorageEvent('storage', { key: 'dcompany:paid-bill-amendment:v1' })); });
  expect(input.value).toBe(''); expect(fetch).not.toHaveBeenCalled();
});
it('keeps the exact persisted stopping reason visibly immutable after a lost cancellation reply', async () => {
  const a = action(), journal = new PaidBillJournal(a.scope); journal.ensureReady(); journal.reserve(a, 0);
  const { serialLocks } = await import('@/test-fixtures/paid-bill');
  Object.defineProperty(navigator, 'locks', { configurable: true, value: serialLocks() });
  const { historicalPaidApi } = await import('@/lib/paid-bill-historical-api');
  vi.spyOn(historicalPaidApi, 'unlock').mockResolvedValue({ protocol: 1, purpose: 'paid_bill_action_recovery', token: 'RAM_ONLY_RECOVERY_TOKEN', expires_in_seconds: 300 });
  vi.spyOn(historicalPaidApi, 'outcome').mockResolvedValue({ protocol: 1, outcome: 'unconfirmed', action_key: a.key, company_id: a.scope.companyId, actor_user_id: a.scope.userId, branch_id: a.scope.branchId, terminal_id: a.scope.terminalId });
  const cancel = vi.spyOn(historicalPaidApi, 'cancel').mockRejectedValue(new Error('lost reply'));
  vi.spyOn(historicalPaidApi, 'lock').mockResolvedValue({ protocol: 1, locked: true });
  await act(async () => { await import('../main'); });
  await enter(document.querySelector<HTMLInputElement>('input[type=password]')!, 'secret');
  await act(async () => { document.querySelector('form')!.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true })); });
  const field = document.querySelector<HTMLTextAreaElement>('textarea')!;
  expect(field).not.toBeNull(); await enter(field, 'Keep this exact stopping reason');
  await act(async () => { field.closest('form')!.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true })); });
  expect(cancel).toHaveBeenCalledTimes(1); expect(field.value).toBe('Keep this exact stopping reason'); expect(field.disabled).toBe(true);
  expect(journal.read().cancellation_reason).toBe(field.value); expect(journal.read().pending).toEqual(a);
});
