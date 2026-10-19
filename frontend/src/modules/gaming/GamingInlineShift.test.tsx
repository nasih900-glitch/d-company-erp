// @vitest-environment happy-dom
import { act } from 'react';
import { createRoot } from 'react-dom/client';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, expect, it, vi } from 'vitest';
import GamingScreen from './GamingScreen';
import { gaming, menu, shifts } from '@/lib/erp-api';
import type { ShiftDTO, StationDTO } from '@/lib/erp-api';

const listeners = vi.hoisted(() => new Map<string, () => void>());
vi.mock('@/lib/demo', () => ({ LIVE_MODE: true }));
vi.mock('@/lib/realtime', () => ({ subscribeRealtime: (resource: string, listener: () => void) => {
  listeners.set(resource, listener);
  return () => listeners.delete(resource);
} }));
vi.mock('@/modules/auth/AuthContext', () => ({ useAuth: () => ({
  me: { user_id: 'staff', name: 'Synthetic Staff', company_id: 'company', branch_id: 'branch', roles: ['staff'], effective_permissions: ['gaming.read','gaming.write','pos.shift.open','pos.shift.close'] },
  terminalId: 'terminal', terminalReady: true,
  terminalOptions: [{ id: 'terminal', name: 'Combined test', purpose: 'hybrid', branch_id: 'branch' }],
}) }));
vi.mock('@/components/ui/Notifications', () => ({ useNotifications: () => ({ success: vi.fn(), error: vi.fn(), info: vi.fn(), warning: vi.fn() }) }));
vi.mock('@/lib/alarm', async (original) => ({ ...await original<object>(), alarmPermission: async () => 'granted' }));

const station = { id: 'station', branch_id: 'branch', name: 'Test Station', code: 'TEST', type: 'projector', rate_per_hour_minor: 12000, is_active: true, billing_mode: 'hourly' } as StationDTO;
const openShift = { id: 'shift', branch_id: 'branch', terminal_id: 'terminal', status: 'open', opened_at: '2026-10-19T00:00:00Z', opening_float_minor: 10000, expected_minor: 10000, opened_by: 'staff', opened_by_name: 'Synthetic Staff' } as ShiftDTO;
(globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
let root: ReturnType<typeof createRoot> | undefined;
afterEach(() => { act(() => root?.unmount()); root = undefined; document.body.innerHTML = ''; window.localStorage?.clear(); listeners.clear(); vi.restoreAllMocks(); });
async function settle() { await act(async () => { for(let i=0;i<12;i++) await Promise.resolve(); }); }
function button(label: string) { const found = [...document.querySelectorAll('button')].find(node => node.textContent?.trim() === label); expect(found, label).toBeTruthy(); return found!; }
function prepare() {
  let rows: ShiftDTO[] = [];
  vi.spyOn(gaming, 'listStations').mockResolvedValue([station]);
  vi.spyOn(gaming, 'listOperationalSessions').mockResolvedValue([]);
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
