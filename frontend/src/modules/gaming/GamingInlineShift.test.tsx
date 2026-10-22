// @vitest-environment happy-dom
import { act } from 'react';
import { createHash, webcrypto } from 'node:crypto';
import { createRoot } from 'react-dom/client';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, expect, it, vi } from 'vitest';
import GamingScreen from './GamingScreen';
import { gaming, menu, shifts } from '@/lib/erp-api';
import type { GameSessionDTO, ShiftDTO, StationDTO } from '@/lib/erp-api';
import { listGamingSessionActions } from './gaming-session-action';

const listeners = vi.hoisted(() => new Map<string, () => void>());
const authScope = vi.hoisted(() => ({ terminalId: 'terminal' }));
vi.mock('@/lib/demo', () => ({ LIVE_MODE: true }));
vi.mock('@/lib/realtime', () => ({ subscribeRealtime: (resource: string, listener: () => void) => {
  listeners.set(resource, listener);
  return () => listeners.delete(resource);
} }));
vi.mock('@/modules/auth/AuthContext', () => ({ useAuth: () => ({
  me: { user_id: 'staff', name: 'Synthetic Staff', company_id: 'company', branch_id: 'branch', roles: ['staff'], effective_permissions: ['gaming.read','gaming.write','pos.shift.open','pos.shift.close'] },
  terminalId: authScope.terminalId, terminalReady: true,
  terminalOptions: ['terminal', 'terminal-2'].map(id => ({ id, name: 'Combined test', purpose: 'hybrid', branch_id: 'branch' })),
}) }));
vi.mock('@/components/ui/Notifications', () => ({ useNotifications: () => ({ success: vi.fn(), error: vi.fn(), info: vi.fn(), warning: vi.fn() }) }));
vi.mock('@/lib/alarm', async (original) => ({ ...await original<object>(), alarmPermission: async () => 'granted' }));

const station = { id: 'station', branch_id: 'branch', name: 'Test Station', code: 'TEST', type: 'projector', rate_per_hour_minor: 12000, is_active: true, billing_mode: 'hourly' } as StationDTO;
const openShift = { id: 'shift', branch_id: 'branch', terminal_id: 'terminal', status: 'open', opened_at: '2026-10-19T00:00:00Z', opening_float_minor: 10000, expected_minor: 10000, opened_by: 'staff', opened_by_name: 'Synthetic Staff' } as ShiftDTO;
(globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
class MemoryStorage implements Storage {
  private readonly values = new Map<string, string>();
  get length(): number { return this.values.size; }
  key(index: number): string | null { return Array.from(this.values.keys())[index] ?? null; }
  getItem(key: string): string | null { return this.values.get(key) ?? null; }
  setItem(key: string, value: string): void { this.values.set(key, value); }
  removeItem(key: string): void { this.values.delete(key); }
  clear(): void { this.values.clear(); }
}

let root: ReturnType<typeof createRoot> | undefined;
afterEach(() => { act(() => root?.unmount()); root = undefined; document.body.innerHTML = ''; window.localStorage?.clear(); listeners.clear(); authScope.terminalId = 'terminal'; vi.restoreAllMocks(); vi.unstubAllGlobals(); });
async function settle() { await act(async () => { for(let i=0;i<12;i++) await Promise.resolve(); }); }
function button(label: string) { const found = [...document.querySelectorAll('button')].find(node => node.textContent?.trim() === label); expect(found, label).toBeTruthy(); return found!; }
function prepare(activeSession?: GameSessionDTO) {
  vi.spyOn(window.navigator, 'onLine', 'get').mockReturnValue(true);
  // Exercise the real outbox APIs with deterministic empty browser storage.
  vi.stubGlobal('localStorage', new MemoryStorage());
  expect(listGamingSessionActions(globalThis.localStorage, { actorUserId: 'staff', companyId: 'company', branchId: 'branch', terminalId: authScope.terminalId })).toEqual([]);
  let rows: ShiftDTO[] = activeSession ? [openShift] : [];
  vi.spyOn(gaming, 'listStations').mockResolvedValue([station]);
  vi.spyOn(gaming, 'listOperationalSessions').mockResolvedValue(activeSession ? [activeSession] : []);
  vi.spyOn(gaming, 'listSessionAddons').mockResolvedValue([]);
  vi.spyOn(gaming, 'listPackages').mockResolvedValue([]);
  vi.spyOn(menu, 'items').mockResolvedValue([]);
  vi.spyOn(menu, 'categories').mockResolvedValue([]);
  vi.spyOn(shifts, 'list').mockImplementation(async () => rows);
  vi.spyOn(shifts, 'open').mockImplementation(async () => { rows=[openShift]; return openShift; });
  const host=document.createElement('div'); document.body.append(host); root=createRoot(host);
  act(() => root!.render(<MemoryRouter><GamingScreen/></MemoryRouter>));
  return { setRows: (next: ShiftDTO[]) => { rows=next; } };
}
it('returns from the real inline Shift form to an authoritative ready Gaming board without realtime', async () => {
  prepare(); await settle();
  act(() => button('Open shift here').click()); await settle();
  act(() => button('Open shift').click()); await settle();
  const form=document.querySelector('form')!;
  await act(async () => form.dispatchEvent(new Event('submit', {bubbles:true,cancelable:true}))); await settle();
  expect(shifts.open).toHaveBeenCalledOnce();
  act(() => button('Return to work').click()); await settle();
  expect(document.body.textContent).not.toContain('Open a shift before starting a session');
  expect(document.body.textContent).toContain('Test Station');
  expect(document.querySelector('[aria-label="Shift workspace"]')).toBeNull();
});
it('refreshes the Gaming shift blocker for another staff member\'s shift event', async () => {
  const state=prepare(); await settle();
  expect(document.body.textContent).toContain('Open a shift before starting a session');
  state.setRows([openShift]);
  act(() => listeners.get('shifts')!()); await settle();
  expect(document.body.textContent).not.toContain('Open a shift before starting a session');
  state.setRows([]);
  act(() => listeners.get('shifts')!()); await settle();
  expect(document.body.textContent).toContain('Open a shift before starting a session');
});

it('never cancels after a deferred session read resolves following workspace unmount', async () => {
  const active = {
    id: 'active-session', station_id: station.id, shift_id: openShift.id,
    status: 'active', start_at: '2026-10-21T10:00:00Z', billing_mode: 'hourly',
    rate_per_hour_minor: 12_000, participant_revision: 0, billing_revision: 0,
    order_id: null, pause_available: true, pause_version: 0,
  } as GameSessionDTO;
  let completeRead!: (value: GameSessionDTO) => void;
  const read = vi.spyOn(gaming, 'getSession').mockImplementation(() => new Promise((resolve) => { completeRead = resolve; }));
  const cancel = vi.spyOn(gaming, 'cancelSession').mockResolvedValue({ ...active, status: 'cancelled' });
  prepare(active); await settle();
  const cancelButton = button('Cancel session');
  expect(cancelButton.disabled, cancelButton.title).toBe(false);
  act(() => cancelButton.click()); await settle();
  const input = document.querySelector<HTMLInputElement>('input[placeholder="Why is this session being cancelled?"]')!;
  expect(input).toBeTruthy();
  act(() => {
    Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')!.set!.call(input, 'Entered by mistake');
    input.dispatchEvent(new Event('input', { bubbles: true }));
  });
  const form = input.closest('form')!;
  await act(async () => { form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true })); });
  expect(read).toHaveBeenCalledOnce();
  act(() => root!.unmount()); root = undefined;
  await act(async () => { completeRead(active); await Promise.resolve(); });
  await settle();
  expect(cancel).not.toHaveBeenCalled();
});

it('clears obsolete cancellation UI after an in-place terminal change', async () => {
  const active = {
    id: 'active-session', station_id: station.id, shift_id: openShift.id,
    status: 'active', start_at: '2026-10-21T10:00:00Z', billing_mode: 'hourly',
    rate_per_hour_minor: 12_000, participant_revision: 0, billing_revision: 0,
    order_id: null, pause_available: true, pause_version: 0,
  } as GameSessionDTO;
  let completeRead!: (value: GameSessionDTO) => void;
  const read = vi.spyOn(gaming, 'getSession').mockImplementation(() => new Promise((resolve) => { completeRead = resolve; }));
  const cancel = vi.spyOn(gaming, 'cancelSession').mockResolvedValue({ ...active, status: 'cancelled' });
  const state = prepare(active); await settle();
  const initialCancel = button('Cancel session');
  expect(initialCancel.disabled, initialCancel.title).toBe(false);
  act(() => initialCancel.click()); await settle();
  const input = document.querySelector<HTMLInputElement>('input[placeholder="Why is this session being cancelled?"]')!;
  expect(input).toBeTruthy();
  act(() => {
    Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')!.set!.call(input, 'Entered by mistake');
    input.dispatchEvent(new Event('input', { bubbles: true }));
  });
  await act(async () => { input.closest('form')!.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true })); });
  expect(read).toHaveBeenCalledOnce();
  const replacement = { ...active, id: 'replacement', shift_id: 'shift-2' };
  state.setRows([{ ...openShift, id: 'shift-2', terminal_id: 'terminal-2' }]);
  vi.mocked(gaming.listOperationalSessions).mockResolvedValue([replacement]);
  authScope.terminalId = 'terminal-2';
  act(() => root!.render(<MemoryRouter><GamingScreen/></MemoryRouter>)); await settle();
  const recovered = button('Cancel session');
  expect(recovered.disabled, recovered.title).toBe(false);
  await act(async () => { completeRead(active); await Promise.resolve(); }); await settle();
  expect(cancel).not.toHaveBeenCalled();
  expect(button('Cancel session').disabled).toBe(false);
});


it('voids a reviewed stopped-player session here with exact metadata and no POS handoff', async () => {
  vi.stubGlobal('crypto', webcrypto);
  const ended = {
    id: 'ended-session', station_id: station.id, shift_id: openShift.id,
    status: 'ended', start_at: '2026-10-21T10:00:00Z', end_at: '2026-10-21T10:30:00Z',
    billing_mode: 'hourly', rate_per_hour_minor: 12_000, billable_minutes: 30,
    amount_minor: 6_000, participant_revision: 2, billing_revision: 0, order_id: null,
  } as GameSessionDTO;
  const read = vi.spyOn(gaming, 'getSession').mockResolvedValue(ended);
  const handoff = vi.spyOn(gaming, 'sendToPos');
  const expectedReview = { idempotencyKey: 'gaming-session-void:ended-session',
    expected_participant_revision: 2, expected_billing_revision: 0, expected_amount_minor: 6_000 };
  const requestBody = { reason: 'Entered by mistake', expected_participant_revision: 2,
    expected_billing_revision: 0, expected_amount_minor: 6_000 };
  const expectedHash = createHash('sha256').update(
    'POST\n/api/v1/gaming/sessions/ended-session/cancel\n\ngaming-session-void:ended-session\n' + JSON.stringify(requestBody),
  ).digest('hex');
  const cancel = vi.spyOn(gaming, 'cancelSession').mockResolvedValue({ ...ended, status: 'cancelled',
    amount_minor: 0, billable_minutes: 0, cancel_reason: 'Entered by mistake',
    cancellation_receipt_id: 'receipt', cancellation_idempotency_key: expectedReview.idempotencyKey,
    cancellation_request_hash: expectedHash });
  prepare(ended); await settle();
  expect(button('Void').disabled).toBe(false);
  act(() => button('Void').click()); await settle();
  expect(document.body.textContent).toContain('Void ₹60.00 before POS');
  const input = document.querySelector<HTMLInputElement>('input[placeholder="Why is this session being cancelled?"]')!;
  act(() => {
    Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')!.set!.call(input, 'Entered by mistake');
    input.dispatchEvent(new Event('input', { bubbles: true }));
  });
  await act(async () => {
    input.closest('form')!.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
    await vi.waitFor(() => expect(cancel).toHaveBeenCalledOnce());
  });
  await settle();
  expect(read).toHaveBeenCalledOnce();
  expect(cancel).toHaveBeenCalledWith('ended-session', 'Entered by mistake', expectedReview);
  expect(handoff).not.toHaveBeenCalled();
  expect(document.querySelector('input[placeholder="Why is this session being cancelled?"]')).toBeNull();
});

it('keeps stopped-player Void blocked until its billing review metadata is known', async () => {
  const ended = { id: 'unverified-ended', station_id: station.id, shift_id: openShift.id,
    status: 'ended', start_at: '2026-10-21T10:00:00Z', end_at: '2026-10-21T10:30:00Z',
    billing_mode: 'hourly', billable_minutes: 30, amount_minor: 6_000,
    participant_revision: 2, order_id: null } as GameSessionDTO;
  const cancel = vi.spyOn(gaming, 'cancelSession');
  prepare(ended); await settle();
  expect(button('Void').disabled).toBe(true);
  expect(button('Void').title).toContain('verify the stopped player settlement');
  act(() => button('Void').click());
  expect(cancel).not.toHaveBeenCalled();
  expect(document.querySelector('input[placeholder="Why is this session being cancelled?"]')).toBeNull();
});
