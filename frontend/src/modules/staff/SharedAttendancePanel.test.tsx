// @vitest-environment happy-dom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import type { Me } from '@/modules/auth/AuthContext';
const mocks = vi.hoisted(() => ({ generation: 1, terminal: '44444444-4444-4444-8444-444444444444', get: vi.fn(), me: null as Me | null }));
vi.mock('@/modules/auth/AuthContext', () => ({ useAuth: () => ({ me: mocks.me, terminalId: mocks.terminal }) }));
vi.mock('@/lib/api', () => ({ api: { get: mocks.get, defaults: { baseURL: '/api/v1' } }, readAccessToken: () => 'same-bearer', readSessionGeneration: () => mocks.generation }));
vi.mock('@/lib/operational-context', () => ({ readStoredTerminalId: () => mocks.terminal }));
import SharedAttendancePanel, { readSharedAttendance, SHARED_ATTENDANCE_KEY, validateSharedReceipt } from './SharedAttendancePanel';
const actor = '11111111-1111-4111-8111-111111111111', company = '22222222-2222-4222-8222-222222222222', branch = '33333333-3333-4333-8333-333333333333';
const person = { id: '55555555-5555-4555-8555-555555555555', name: 'Staff Two', on_duty: false, attendance_id: null };
const saved = { version: 1 as const, key: 'shared-attendance:66666666-6666-4666-8666-666666666666', actor, company, branch, terminal: mocks.terminal, person, break_minutes: null };
const receipt = { id: '77777777-7777-4777-8777-777777777777', user_id: person.id, branch_id: branch, clock_in_at: '2026-11-12T00:00:00Z', clock_out_at: null, break_minutes: 0, break_confirmed: false, source: 'app', updated_at: '2026-11-12T00:00:00Z', staff_name: 'Staff Two' };
let root: Root, host: HTMLDivElement;
beforeEach(() => {
  localStorage.clear(); vi.clearAllMocks(); mocks.generation = 1; mocks.terminal = saved.terminal;
  mocks.me = { user_id: actor, company_id: company, branch_id: branch, name: 'Staff One', roles: ['store_staff'], effective_permissions: ['staff.attendance.write'] } as Me;
  mocks.get.mockResolvedValue({ data: { items: [person], next_cursor: null } });
  Object.defineProperty(navigator, 'locks', { configurable: true, value: { request: async (_key: string, _options: unknown, fn: (lock: object) => Promise<unknown>) => fn({}) } });
  (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
  host = document.createElement('div'); document.body.append(host); root = createRoot(host);
});
afterEach(async () => { await act(async () => root.unmount()); host.remove(); localStorage.clear(); vi.restoreAllMocks(); vi.unstubAllGlobals(); });
async function render() { await act(async () => root.render(<SharedAttendancePanel/>)); }
async function click(text: string) {
  const button = Array.from(document.querySelectorAll('button')).find(x => x.textContent?.trim() === text);
  expect(button, text).toBeDefined(); await act(async () => button!.click());
}
async function password() {
  const field = document.querySelector<HTMLInputElement>('input[type=password]')!; expect(field).not.toBeNull();
  await act(async () => {
    Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')!.set!.call(field, 'memory-only-secret');
    field.dispatchEvent(new Event('input', { bubbles: true })); field.dispatchEvent(new Event('change', { bubbles: true }));
  });
}
it('automatically loads on-duty staff and clears an unsent password when terminal authority changes', async () => {
  const fetch = vi.fn(); vi.stubGlobal('fetch', fetch); await render(); expect(mocks.get).toHaveBeenCalledTimes(1);
  await click('Staff Two · Clock in'); await password();
  mocks.terminal = '88888888-8888-4888-8888-888888888888'; mocks.generation++;
  await render(); expect(document.querySelector('input[type=password]')).toBeNull(); expect(mocks.get).toHaveBeenCalledTimes(2);
  expect(fetch).not.toHaveBeenCalled(); expect(readSharedAttendance()).toBeNull();
});
it('a retained retry dialog cannot silently target a replacement request', async () => {
  localStorage.setItem(SHARED_ATTENDANCE_KEY, JSON.stringify(saved)); const fetch = vi.fn(); vi.stubGlobal('fetch', fetch);
  await render(); await click('Retry retained attendance · Staff Two'); await password();
  const next = { ...saved, key: 'shared-attendance:99999999-9999-4999-8999-999999999999', person: { ...person, id: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', name: 'Another employee' } };
  localStorage.setItem(SHARED_ATTENDANCE_KEY, JSON.stringify(next));
  await click('Confirm clock-in'); expect(fetch).not.toHaveBeenCalled(); expect(readSharedAttendance()).toEqual(next);
});
it('a malformed successful receipt retains the exact pending action and never stores the password', async () => {
  vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({ ...receipt, id: 'invalid' }), { status: 201 })));
  await render(); await click('Staff Two · Clock in'); await password(); await click('Confirm clock-in');
  expect(readSharedAttendance()?.person.id).toBe(person.id);
  expect(JSON.stringify(localStorage)).not.toContain('memory-only-secret');
  expect(Object.keys(localStorage).some(k => k.includes(':resolved:'))).toBe(false);
});
it('archive write/readback failure leaves the pending action intact', async () => {
  vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify(receipt), { status: 201 })));
  const backing = localStorage;
  vi.stubGlobal('localStorage', {
    getItem: (key: string) => backing.getItem(key),
    setItem: (key: string, value: string) => { if (!key.includes(':resolved:')) backing.setItem(key, value); },
    removeItem: (key: string) => backing.removeItem(key), clear: () => backing.clear(),
  });
  await render(); await click('Staff Two · Clock in'); await password(); await click('Confirm clock-in');
  expect(readSharedAttendance()?.person.id).toBe(person.id); expect(document.body.textContent).toContain('could not be saved safely');
});
it('receipt validation rejects invalid clock-out chronology, substituted attendance and extra fields', () => {
  const out = { ...saved, person: { ...person, on_duty: true, attendance_id: receipt.id }, break_minutes: 0 };
  expect(() => validateSharedReceipt(out, { ...receipt, clock_out_at: '2026-11-11T23:59:59Z', break_confirmed: true })).toThrow();
  expect(() => validateSharedReceipt(out, { ...receipt, id: actor, clock_out_at: '2026-11-12T01:00:00Z', break_confirmed: true })).toThrow();
  expect(() => validateSharedReceipt(saved, { ...receipt, password: 'not-allowed' } as typeof receipt)).toThrow();
});

it('retained attendance remains reviewable when the directory cannot be loaded', async () => {
  localStorage.setItem(SHARED_ATTENDANCE_KEY,JSON.stringify(saved));
  mocks.get.mockRejectedValue(new Error('Directory unavailable'));
  const fetch=vi.fn();vi.stubGlobal('fetch',fetch);await render();
  expect(document.body.textContent).toContain('Retry retained attendance · Staff Two');
  expect(fetch).not.toHaveBeenCalled();expect(readSharedAttendance()).toEqual(saved);
});
