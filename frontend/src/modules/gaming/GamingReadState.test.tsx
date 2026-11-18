// @vitest-environment happy-dom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import GamingScreen from './GamingScreen';
import { gaming, menu, type ShiftDTO, shifts, type StationDTO } from '@/lib/erp-api';

const context = vi.hoisted(() => ({ terminalId: 'terminal' }));
const listeners = vi.hoisted(() => new Map<string, () => void>());
vi.mock('@/modules/auth/AuthContext', () => ({ useAuth: () => ({
  me: { user_id: 'staff', company_id: 'company', branch_id: 'branch', roles: ['staff'], name: 'Staff', effective_permissions: ['gaming.read', 'gaming.write', 'pos.shift.open'] },
  terminalId: context.terminalId, terminalReady: true,
  terminalOptions: ['terminal', 'terminal2'].map(id => ({ id, branch_id: 'branch', purpose: 'hybrid' })),
}) }));
vi.mock('@/lib/realtime', () => ({ subscribeRealtime: (name: string, listener: () => void) => { listeners.set(name, listener); return () => listeners.delete(name); } }));
vi.mock('@/components/ui/Notifications', () => ({ useNotifications: () => ({ success: vi.fn(), error: vi.fn(), info: vi.fn(), warning: vi.fn() }) }));
vi.mock('@/lib/alarm', async original => ({ ...await original<object>(), alarmPermission: async () => 'granted' }));
(globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
const station = { id: 'station', branch_id: 'branch', name: 'Retained station', code: 'TEST', type: 'projector', rate_per_hour_minor: 12000, is_active: true, billing_mode: 'hourly' } as StationDTO;
const shift = { id: 'shift', branch_id: 'branch', terminal_id: 'terminal', status: 'open', opened_at: '2026-11-13T08:00:00Z', opened_by: 'staff', opening_float_minor: 0, expected_minor: 0 } as ShiftDTO;
let root: Root;
function deferred<T>() { let resolve!: (value: T) => void; const promise = new Promise<T>(yes => { resolve = yes; }); return { promise, resolve }; }
async function settle() { await act(async () => { for (let i = 0; i < 12; i++) await Promise.resolve(); }); }
function render() { act(() => root.render(<MemoryRouter><GamingScreen/></MemoryRouter>)); }
function refresh() { act(() => document.querySelector<HTMLButtonElement>('[aria-label="Refresh Gaming"]')!.click()); }
beforeEach(() => {
  context.terminalId = 'terminal';
  vi.spyOn(window.navigator, 'onLine', 'get').mockReturnValue(true);
  vi.spyOn(gaming, 'listStations').mockResolvedValue([]);
  vi.spyOn(gaming, 'listOperationalSessions').mockResolvedValue([]);
  vi.spyOn(gaming, 'listPackages').mockResolvedValue([]);
  vi.spyOn(menu, 'items').mockResolvedValue([]);
  vi.spyOn(menu, 'categories').mockResolvedValue([]);
  vi.spyOn(shifts, 'list').mockResolvedValue([shift]);
  vi.spyOn(shifts, 'open');
  const host = document.createElement('div'); document.body.append(host); root = createRoot(host);
});
afterEach(() => { act(() => root.unmount()); document.body.innerHTML = ''; listeners.clear(); vi.restoreAllMocks(); });

it.each(['listStations', 'listOperationalSessions', 'listPackages'] as const)('never treats the first failed %s read as an empty/no-shift board and retries normally', async required => {
  vi.mocked(gaming[required]).mockRejectedValueOnce(new Error('Required board read failed'));
  render(); await settle();
  expect(document.body.textContent).toContain('Board verification pending');
  expect(document.body.textContent).toContain('Station and session counts are unverified');
  expect(document.body.textContent).toContain('The station board has not been verified');
  expect(document.body.textContent).not.toMatch(/0 stations|No stations|verified that this terminal has no open shift|Open shift here/);
  expect(shifts.open).not.toHaveBeenCalled();
  refresh(); await settle();
  expect(document.body.textContent).toContain('Last verified load');
  expect(document.body.textContent).toContain('0 stations');
  expect(document.body.textContent).toContain('No stations yet');
  expect(document.body.textContent).not.toContain('Open shift here');
});

it('offers shift entry only after a successful read actually proves no open shift', async () => {
  vi.mocked(shifts.list).mockResolvedValue([]); render(); await settle();
  expect(document.body.textContent).toContain('0 stations');
  expect(document.body.textContent).toContain('Open shift here');
  expect(document.body.textContent).toContain('No shift is open');
  expect(shifts.open).not.toHaveBeenCalled();
});

it('keeps a verified station and customer draft visible and marked stale after failed refresh', async () => {
  vi.mocked(gaming.listStations).mockResolvedValue([station]); render(); await settle();
  const add = [...document.querySelectorAll('button')].find(node => node.textContent === 'Add new')!;
  act(() => add.click());
  const input = document.querySelector<HTMLInputElement>('input[placeholder="Customer name (optional)"]')!;
  expect(input).toBeTruthy();
  act(() => { Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')!.set!.call(input, 'Retained guest'); input.dispatchEvent(new Event('input', { bubbles: true })); });
  vi.mocked(gaming.listStations).mockRejectedValueOnce(new Error('Read interrupted'));
  refresh(); await settle();
  expect(document.body.textContent).toContain('Latest refresh failed.');
  expect(document.body.textContent).toContain('Retained station');
  expect(input.isConnected).toBe(true); expect(input.value).toBe('Retained guest');
  refresh(); await settle();
  expect(document.body.textContent).not.toContain('Latest refresh failed.');
  expect(input.value).toBe('Retained guest');
});

it('does not publish a delayed board from the old terminal after context changes', async () => {
  const old = deferred<StationDTO[]>(); vi.mocked(gaming.listStations).mockReturnValueOnce(old.promise);
  render(); await settle(); context.terminalId = 'terminal2';
  vi.mocked(shifts.list).mockResolvedValue([{ ...shift, terminal_id: 'terminal2' }]);
  render(); await settle();
  expect(document.body.textContent).toContain('0 stations');
  await act(async () => old.resolve([station])); await settle();
  expect(document.body.textContent).not.toContain('Retained station');
  expect(document.body.textContent).toContain('0 stations');
  expect(document.body.textContent).not.toContain('Open shift here');
});
