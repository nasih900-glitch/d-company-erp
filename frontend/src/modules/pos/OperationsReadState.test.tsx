// @vitest-environment happy-dom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import Operations from './OrdersAndShiftsScreen';
import DailyWorkWorkspace from '@/modules/staff/DailyWorkWorkspace';
import { gaming, orders, receipts, shifts, type ShiftDTO } from '@/lib/erp-api';

const state = vi.hoisted(() => ({ terminal: 'terminal', online: true, pending: null as null | { actorName: string; phase: string } }));
const listeners = vi.hoisted(() => new Map<string, () => void>());
const work = vi.hoisted(() => ({ run: vi.fn(), recover: vi.fn() }));
vi.mock('@/modules/auth/AuthContext', () => ({ useAuth: () => ({
  me: { user_id: 'staff', company_id: 'company', branch_id: 'branch', name: 'Staff', roles: ['staff'], effective_permissions: ['pos.read', 'pos.shift.open', 'pos.shift.close', 'staff.attendance.write'] },
  terminalId: state.terminal, terminalReady: true,
  terminalOptions: ['terminal', 'terminal2'].map(id => ({ id, branch_id: 'branch', name: id, purpose: 'hybrid', online_start_work_supported: state.online })),
}) }));
vi.mock('@/lib/realtime', () => ({ subscribeRealtime: (name: string, callback: () => void) => { listeners.set(name, callback); return () => listeners.delete(name); } }));
vi.mock('@/hooks/useRealtimeRefresh', () => ({ useRealtimeRefresh: vi.fn() }));
vi.mock('@/components/ui/Notifications', () => ({ useNotifications: () => ({ success: vi.fn(), error: vi.fn(), info: vi.fn() }) }));
vi.mock('@/modules/staff/SharedAttendancePanel', () => ({ default: () => <button>Separate attendance</button> }));
vi.mock('@/lib/start-work', () => ({ hasPendingWork: () => state.pending !== null, readPendingWork: () => state.pending, workConfirmation: () => 'Saved start checked', runStartWork: work.run, recoverStartWork: work.recover }));
vi.mock('@/lib/paid-bill-admission', () => ({ withoutPaidBillWork: async (run: () => Promise<unknown>) => run() }));

(globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
let root: Root;
const shift = { id: 'shift', branch_id: 'branch', terminal_id: 'terminal', status: 'open', opened_at: '2026-11-13T08:00:00Z', opened_by: 'staff', opened_by_name: 'Verified opener', opening_float_minor: 0, expected_minor: 0 } as ShiftDTO;
function deferred<T>() { let resolve!: (value: T) => void, reject!: (error: Error) => void; const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no; }); return { promise, resolve, reject }; }
async function settle() { await act(async () => { for (let i = 0; i < 12; i++) await Promise.resolve(); }); }
function button(name: string) { const found = [...document.querySelectorAll('button')].find(node => node.getAttribute('aria-label') === name || node.textContent?.trim() === name); expect(found, name).toBeTruthy(); return found!; }
function enter(input: HTMLInputElement, value: string) { act(() => { Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')!.set!.call(input, value); input.dispatchEvent(new Event('input', { bubbles: true })); }); }
function render(tab = 'shifts') { act(() => root.render(<MemoryRouter initialEntries={['/?tab=' + tab]}><Operations/></MemoryRouter>)); }
beforeEach(() => {
  state.terminal = 'terminal'; state.online = true; state.pending = null;
  vi.spyOn(gaming, 'listStations').mockResolvedValue([]);
  vi.spyOn(orders, 'list').mockResolvedValue([]);
  vi.spyOn(receipts, 'list').mockResolvedValue({ items: [], next_cursor: null, has_more: false });
  vi.spyOn(shifts, 'list').mockResolvedValue([]);
  vi.spyOn(shifts, 'open').mockResolvedValue(shift);
  work.run.mockResolvedValue(undefined); work.recover.mockResolvedValue(undefined);
  const host = document.createElement('div'); document.body.append(host); root = createRoot(host);
});
afterEach(() => { act(() => root.unmount()); document.body.innerHTML = ''; listeners.clear(); vi.restoreAllMocks(); work.run.mockReset(); work.recover.mockReset(); });

it('keeps the first failed shift read unknown and permits saved recovery and attendance', async () => {
  state.pending = { actorName: 'Saved staff', phase: 'UNKNOWN' };
  vi.mocked(shifts.list).mockRejectedValue(new Error('Network Error'));
  render(); await settle();
  expect(document.body.textContent).toContain('Shift history and open-shift status are unverified');
  expect(document.body.textContent).not.toMatch(/0 business days|0 shift records|No shift currently open|No shifts recorded|last confirmed information remains/);
  expect(document.querySelector('input[inputmode="decimal"]')).toBeNull();
  expect(button('Separate attendance').disabled).toBe(false);
  act(() => button('Check saved start').click()); await settle();
  expect(work.recover).toHaveBeenCalledOnce(); expect(work.run).not.toHaveBeenCalled();
  vi.mocked(shifts.list).mockResolvedValue([shift]);
  act(() => button('Refresh shift information').click()); await settle();
  expect(document.body.textContent).toContain('Open shift active');
  expect(document.body.textContent).toContain('Verified opener');
});

it('distinguishes verified empty from stale history, preserves float, and fences a queued new start', async () => {
  render(); await settle();
  expect(document.body.textContent).toContain('No shifts recorded');
  const input = document.querySelector<HTMLInputElement>('input[inputmode="decimal"]')!;
  enter(input, '125.50'); const form = input.closest('form')!;
  const read = deferred<ShiftDTO[]>(); vi.mocked(shifts.list).mockReturnValue(read.promise);
  act(() => { listeners.get('shifts')!(); form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true })); });
  expect(work.run).not.toHaveBeenCalled();
  await act(async () => { read.reject(new Error('Network Error')); }); await settle();
  expect(document.body.textContent).toContain('Last verified:');
  expect(document.body.textContent).toContain('The last verified history had no shifts');
  expect(document.body.textContent).not.toContain('No shift currently open');
  vi.mocked(shifts.list).mockResolvedValue([]); act(() => button('Refresh shift information').click()); await settle();
  expect(document.querySelector<HTMLInputElement>('input[inputmode="decimal"]')!.value).toBe('125.50');
});

it('keeps the close-count draft and known open shift through a failed read', async () => {
  vi.mocked(shifts.list).mockResolvedValue([shift]); render(); await settle();
  act(() => button('Close shift opened by Verified opener').click()); await settle();
  const input = document.querySelector<HTMLInputElement>('input[type="number"]')!;
  enter(input, '240');
  vi.mocked(shifts.list).mockRejectedValue(new Error('Network Error'));
  act(() => button('Refresh shift information').click()); await settle();
  expect(input.isConnected).toBe(true); expect(input.value).toBe('240');
  expect(document.body.textContent).toContain('Verified opener');
  expect(document.body.textContent).toContain('last confirmed information remains');
});

it('blocks legacy opening after a refresh starts without discarding its float', async () => {
  state.online = false; render(); await settle();
  act(() => button('Open shift').click()); await settle();
  const input = document.querySelector<HTMLInputElement>('input[type="number"]')!;
  enter(input, '350'); const form = input.closest('form')!;
  const read = deferred<ShiftDTO[]>(); vi.mocked(shifts.list).mockReturnValue(read.promise);
  act(() => { listeners.get('shifts')!(); form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true })); });
  expect(shifts.open).not.toHaveBeenCalled(); expect(input.value).toBe('350');
  await act(async () => read.resolve([])); await settle();
  expect(input.isConnected).toBe(true); expect(input.value).toBe('350');
});

it('rejects a late old-terminal read after a new scope verifies empty', async () => {
  const old = deferred<ShiftDTO[]>(); vi.mocked(shifts.list).mockReturnValueOnce(old.promise);
  render(); await settle(); state.terminal = 'terminal2'; render(); await settle();
  expect(document.body.textContent).toContain('No shifts recorded');
  await act(async () => old.resolve([shift])); await settle();
  expect(document.body.textContent).not.toContain('Verified opener');
  expect(document.body.textContent).toContain('No shifts recorded');
});

it.each(['success', 'failure'] as const)('preserves the focused Orders refresh through pending and %s with one read group', async outcome => {
  render('orders'); await settle();
  const control = button('Refresh orders and receipt history'); control.focus();
  const operational = deferred<Awaited<ReturnType<typeof orders.list>>>();
  const history = deferred<Awaited<ReturnType<typeof receipts.list>>>();
  vi.mocked(orders.list).mockReturnValue(operational.promise); vi.mocked(receipts.list).mockReturnValue(history.promise);
  const before = [vi.mocked(orders.list).mock.calls.length, vi.mocked(receipts.list).mock.calls.length];
  act(() => { control.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true })); control.click(); control.dispatchEvent(new KeyboardEvent('keydown', { key: ' ', bubbles: true })); control.click(); });
  expect(orders.list).toHaveBeenCalledTimes(before[0] + 1); expect(receipts.list).toHaveBeenCalledTimes(before[1] + 1);
  expect(control.disabled).toBe(false); expect(control.getAttribute('aria-disabled')).toBe('true'); expect(document.activeElement).toBe(control);
  await act(async () => { operational.resolve([]); if (outcome === 'success') history.resolve({ items: [], next_cursor: null, has_more: false }); else history.reject(new Error('History unavailable')); }); await settle();
  expect(document.activeElement).toBe(control); expect(control.isConnected).toBe(true); expect(control.getAttribute('aria-disabled')).toBe('false');
  vi.mocked(orders.list).mockResolvedValue([]); vi.mocked(receipts.list).mockResolvedValue({ items: [], next_cursor: null, has_more: false });
  act(() => control.click()); await settle(); act(() => control.click()); await settle();
  expect(orders.list).toHaveBeenCalledTimes(before[0] + 3); expect(receipts.list).toHaveBeenCalledTimes(before[1] + 3);
});

it('exposes actual pressed state while switching Operations sections', async () => {
  render('orders'); await settle();
  expect(button('Orders').getAttribute('aria-pressed')).toBe('true'); expect(button('Shifts').getAttribute('aria-pressed')).toBe('false');
  act(() => button('Shifts').click()); await settle();
  expect(button('Orders').getAttribute('aria-pressed')).toBe('false'); expect(button('Shifts').getAttribute('aria-pressed')).toBe('true');
});


it('starts only the freshly verified empty workspace with the unchanged exact amount', async () => {
  render(); await settle();
  const input = document.querySelector<HTMLInputElement>('input[inputmode="decimal"]')!;
  enter(input, '125.50');
  await act(async () => input.closest('form')!.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))); await settle();
  expect(work.run).toHaveBeenCalledOnce();
  expect(work.run).toHaveBeenCalledWith(expect.objectContaining({ user_id: 'staff' }), 'terminal', 12550);
});

it('keeps other Daily Work callers compatible when no freshness callback is supplied', async () => {
  act(() => root.render(<MemoryRouter><DailyWorkWorkspace allowNewStart/></MemoryRouter>)); await settle();
  const input = document.querySelector<HTMLInputElement>('input[inputmode="decimal"]')!;
  enter(input, '7.40');
  await act(async () => input.closest('form')!.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))); await settle();
  expect(work.run).toHaveBeenCalledWith(expect.objectContaining({ user_id: 'staff' }), 'terminal', 740);
  expect(button('Separate attendance').disabled).toBe(false);
});

it('keeps only the latest same-terminal history response authoritative', async () => {
  render(); await settle(); const old = deferred<ShiftDTO[]>();
  vi.mocked(shifts.list).mockReturnValueOnce(old.promise).mockResolvedValueOnce([]);
  act(() => { listeners.get('shifts')!(); listeners.get('shifts')!(); }); await settle();
  expect(document.body.textContent).toContain('No shifts recorded');
  await act(async () => old.resolve([shift])); await settle();
  expect(document.body.textContent).not.toContain('Verified opener');
  expect(document.querySelector('input[inputmode="decimal"]')).toBeTruthy();
});
