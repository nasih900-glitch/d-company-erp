// @vitest-environment happy-dom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import ManualCollectionsTab from './ManualCollectionsTab';
import type { ManualCollectionDTO, ShiftDTO } from '@/lib/erp-api';
const m = vi.hoisted(() => ({ list: vi.fn(), branches: vi.fn(), shifts: vi.fn(), identity: vi.fn(), create: vi.fn(), void: vi.fn(), correct: vi.fn(), success: vi.fn(), refresh: null as null | (() => Promise<void>) }));
vi.mock('@/modules/auth/AuthContext', () => ({ useAuth: () => ({ me: { user_id: 'owner', company_id: 'company', branch_id: 'branch', effective_permissions: ['finance.read', 'finance.write'] } }) }));
vi.mock('@/components/ui/Notifications', () => ({ useNotifications: () => ({ success: m.success }) }));
vi.mock('@/hooks/useRealtimeRefresh', () => ({ useRealtimeRefresh: ({ refresh }: { refresh: () => Promise<void> }) => { m.refresh = refresh; } }));
vi.mock('@/lib/erp-api', () => ({ finance: { listManualCollections: m.list, listBranches: m.branches, createManualCollection: m.create, voidManualCollection: m.void, correctManualCollection: m.correct }, shifts: { list: m.shifts }, pos: { receiptBusiness: m.identity } }));
let root: Root, host: HTMLDivElement;
const shift = { id: 'drawer', branch_id: 'branch', status: 'open', opened_by_name: 'Synthetic operator', expected_minor: 30000 } as ShiftDTO;
const row = { id: 'collection', branch_id: 'branch', shift_id: null, method: 'upi', amount_minor: 15000, source_ref: 'Synthetic reference', business_date: '2026-11-13', source_kind: 'manual_daily', created_by: 'owner', created_at: '2026-11-13T08:00:00Z', is_voided: false, is_corrected: false, source_shift_status: null } as ManualCollectionDTO;
function later<T>() { let resolve!: (v: T) => void; const promise = new Promise<T>(r => { resolve = r; }); return { promise, resolve }; }
beforeEach(() => {
  vi.clearAllMocks(); (globalThis as unknown as { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
  m.list.mockResolvedValue([]); m.branches.mockResolvedValue([{ id: 'branch', name: 'Synthetic shop' }]); m.shifts.mockResolvedValue([shift]); m.identity.mockResolvedValue({ timezone: 'Asia/Kolkata' }); m.create.mockResolvedValue({}); m.void.mockResolvedValue({}); m.correct.mockResolvedValue({});
  host = document.createElement('div'); document.body.append(host); root = createRoot(host);
});
afterEach(() => { act(() => root.unmount()); document.body.replaceChildren(); vi.restoreAllMocks(); });
async function render() { await act(async () => root.render(<ManualCollectionsTab/>)); }
function button(text: string, scope: ParentNode = document) { const b = [...scope.querySelectorAll<HTMLButtonElement>('button')].find(n => n.textContent?.trim() === text || n.getAttribute('aria-label') === text); if (!b) throw Error('Missing button '+text); return b; }
async function click(text: string, scope?: ParentNode) { await act(async () => button(text, scope).click()); }
function field(text: string) { const label = [...document.querySelectorAll('label')].find(n => n.textContent?.includes(text)); const node = label?.querySelector<HTMLInputElement | HTMLTextAreaElement | HTMLSelectElement>('input,textarea,select'); if (!node) throw Error('Missing field '+text); return node; }
async function fill(text: string, value: string) { const n=field(text); await act(async () => { Object.getOwnPropertyDescriptor(Object.getPrototypeOf(n), 'value')!.set!.call(n,value); n.dispatchEvent(new Event(n instanceof HTMLSelectElement ? 'change' : 'input', { bubbles:true })); }); }
async function submit() { await act(async () => document.querySelector('[role=dialog] form')!.dispatchEvent(new Event('submit', { bubbles:true, cancelable:true }))); }
it.each(['list','branches','shifts'] as const)('a first failed required %s read cannot claim zero collections or no shop, and Retry recovers', async source => {
  m[source].mockRejectedValueOnce(new Error('Synthetic connection failure')); await render();
  expect(host.querySelector('[role=alert]')?.textContent).toContain('not been verified');
  expect(host.textContent).not.toContain('Active total'); expect(host.textContent).not.toContain('No manual collections recorded'); expect(host.textContent).not.toContain('No shop is available');
  expect(button('Add manual collection').disabled).toBe(true); expect(m.create).not.toHaveBeenCalled();
  await click('Retry manual collections'); expect(host.textContent).toContain('Active total'); expect(host.textContent).toContain('No manual collections recorded'); expect(button('Add manual collection').disabled).toBe(false);
});
it('the optional receipt identity failure keeps the existing house-timezone default and real references', async () => {
  m.identity.mockRejectedValue(new Error('Optional unavailable')); await render(); await click('Add manual collection');
  expect(field('Branch').value).toBe('branch'); expect(field('Business date').value).toMatch(/^\d{4}-\d{2}-\d{2}$/); expect(button('Record collection').disabled).toBe(false);
});
it('a failed refresh keeps the last verified totals visibly stale and pauses writes', async () => {
  m.list.mockResolvedValue([row]); await render(); expect(host.textContent).toContain('150');
  m.branches.mockRejectedValueOnce(new Error('References offline')); await click('Refresh manual collections');
  expect(host.textContent).toContain('last verified records'); expect(host.textContent).toContain(row.source_ref); expect(host.textContent).toContain('150'); expect(button('Add manual collection').disabled).toBe(true);
});
it('an Add draft survives failed and in-flight in-dialog reference retries with its exact payload and original key', async () => {
  await render(); await click('Add manual collection');
  await fill('Amount','123.45'); await fill('Reference','Exact private reference'); await fill('Note','Unsaved note'); const date=field('Business date').value;
  const form=document.querySelector('[role=dialog] form'); const amount=field('Amount');
  m.create.mockRejectedValueOnce(new Error('Write not confirmed')); await submit(); expect(m.create).toHaveBeenCalledTimes(1);
  const originalKey=m.create.mock.calls[0][1];
  m.list.mockRejectedValueOnce(new Error('Read offline')); await click('Refresh current records');
  expect(document.querySelector('[role=dialog] form')).toBe(form); expect(field('Amount')).toBe(amount); expect(field('Amount').value).toBe('123.45'); expect(field('Note').value).toBe('Unsaved note'); expect(button('Record collection').disabled).toBe(true);
  await submit(); expect(m.create).toHaveBeenCalledTimes(1);
  const pending=later<ManualCollectionDTO[]>(); m.list.mockReturnValueOnce(pending.promise);
  let reading!: Promise<void>; await act(async () => { reading=m.refresh!(); }); expect(button('Record collection').disabled).toBe(true); expect(field('Reference').value).toBe('Exact private reference');
  await act(async () => { pending.resolve([]); await reading; }); await submit();
  expect(m.create).toHaveBeenCalledTimes(2); expect(m.create.mock.calls[1]).toEqual([{branch_id:'branch',shift_id:'drawer',business_date:date,method:'cash',amount_minor:12345,source_ref:'Exact private reference',note:'Unsaved note'},originalKey]);
});
it('a removed drawer never silently retargets a retained Add draft', async () => {
  await render(); await click('Add manual collection'); await fill('Amount','20'); m.shifts.mockResolvedValueOnce([{...shift,id:'other'}]); await click('Refresh current records');
  expect(field('Open shift drawer').value).toBe('drawer'); expect(button('Record collection').disabled).toBe(true); await submit(); expect(m.create).not.toHaveBeenCalled();
  await fill('Open shift drawer','other'); expect(button('Record collection').disabled).toBe(false);
});
it.each(['void','correct'] as const)('%s keeps reason/source/key through read failure and does not dispatch until current references verify', async mode => {
  const source=mode==='correct'?{...row,method:'cash',shift_id:'old-drawer',source_shift_status:'closed'}:row;
  m.list.mockResolvedValue([source]); await render(); await click(`${mode==='correct'?'Correct':'Void'} ${row.source_ref}`);
  await fill(mode==='correct'?'Correction reason':'Reason','Exact retained reason'); const form=document.querySelector('[role=dialog] form');
  m[mode].mockRejectedValueOnce(new Error('Write not confirmed')); await submit(); const original=m[mode].mock.calls[0];
  m.list.mockRejectedValueOnce(new Error('Read unavailable')); await click('Refresh current records'); expect(document.querySelector('[role=dialog] form')).toBe(form); expect(field(mode==='correct'?'Correction reason':'Reason').value).toBe('Exact retained reason');
  await submit(); expect(m[mode]).toHaveBeenCalledTimes(1); await click('Retry current records'); await submit(); expect(m[mode]).toHaveBeenCalledTimes(2); expect(m[mode].mock.calls[1]).toEqual(original);
  if(mode==='correct') expect(m.correct.mock.calls[0]).toEqual([row.id,{settlement_shift_id:'drawer',reason:'Exact retained reason'},expect.stringMatching(/^manual-collection-correction:/)]);
  else expect(m.void).toHaveBeenCalledWith(row.id,'Exact retained reason');
});
it('a confirmed write plus failed readback stays confirmed and cannot resubmit the closed draft', async () => {
  await render(); await click('Add manual collection'); await fill('Amount','10'); m.list.mockRejectedValueOnce(new Error('Readback unavailable')); await submit();
  expect(m.create).toHaveBeenCalledTimes(1); expect(m.success).toHaveBeenCalledTimes(1); expect(document.querySelector('[role=dialog]')).toBeNull(); expect(host.textContent).toContain('last verified records');
  await click('Retry manual collections'); expect(m.create).toHaveBeenCalledTimes(1);
});
it('an older read cannot overwrite the newer complete snapshot, and unmounted work is ignored', async () => {
  await render(); const old=later<ManualCollectionDTO[]>(); m.list.mockReturnValueOnce(old.promise); let request!:Promise<void>;
  await act(async () => { request=m.refresh!(); }); m.list.mockResolvedValueOnce([row]); await act(async () => m.refresh!());
  await act(async () => { old.resolve([]); await request; }); expect(host.textContent).toContain(row.source_ref);
  const last=later<ManualCollectionDTO[]>(); m.list.mockReturnValueOnce(last.promise); await act(async () => { request=m.refresh!(); root.render(null); });
  await act(async () => { last.resolve([]); await request; }); expect(host.textContent).toBe('');
});
it('the same focused read Retry survives pending, successful and failed reads without duplicate GETs', async () => {
  await render(); await click('Add manual collection'); await fill('Amount', '42.50');
  m.list.mockRejectedValueOnce(new Error('Read unavailable')); await click('Refresh current records');
  const retry = button('Retry current records'), form = document.querySelector('[role=dialog] form'); retry.focus();
  const pending = later<ManualCollectionDTO[]>(); m.list.mockReturnValueOnce(pending.promise); const before = m.list.mock.calls.length;
  await act(async () => { retry.click(); retry.click(); });
  expect(m.list).toHaveBeenCalledTimes(before + 1); expect(retry.disabled).toBe(false); expect(retry.getAttribute('aria-disabled')).toBe('true'); expect(document.activeElement).toBe(retry);
  await submit(); await act(async () => retry.click()); expect(m.list).toHaveBeenCalledTimes(before + 1); expect(m.create).not.toHaveBeenCalled();
  await act(async () => { pending.resolve([]); });
  expect(button('Refresh current records')).toBe(retry); expect(document.activeElement).toBe(retry); expect(retry.getAttribute('aria-disabled')).toBe('false'); expect(document.querySelector('[role=dialog] form')).toBe(form); expect(field('Amount').value).toBe('42.50');
  m.list.mockRejectedValueOnce(new Error('Read failed again')); await act(async () => retry.click());
  expect(button('Retry current records')).toBe(retry); expect(document.activeElement).toBe(retry); expect(retry.getAttribute('aria-disabled')).toBe('false'); expect(button('Record collection').disabled).toBe(true); expect(field('Amount').value).toBe('42.50');
  expect(m.create).not.toHaveBeenCalled(); expect(m.void).not.toHaveBeenCalled(); expect(m.correct).not.toHaveBeenCalled();
});
it('fast batched successful reads release the guard for the next explicit activation', async () => {
  await render(); await click('Add manual collection'); const retry = button('Refresh current records'); retry.focus();
  const before = m.list.mock.calls.length;
  await act(async () => { retry.click(); retry.click(); }); expect(m.list).toHaveBeenCalledTimes(before + 1);
  await act(async () => { retry.click(); retry.click(); }); expect(m.list).toHaveBeenCalledTimes(before + 2);
  expect(retry.getAttribute('aria-disabled')).toBe('false'); expect(retry.disabled).toBe(false); expect(document.activeElement).toBe(retry); expect(button('Record collection').disabled).toBe(false);
  expect(m.create).not.toHaveBeenCalled(); expect(m.void).not.toHaveBeenCalled(); expect(m.correct).not.toHaveBeenCalled();
});
