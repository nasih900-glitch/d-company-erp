// @vitest-environment happy-dom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import type { ReceiptHistoryDTO } from '@/lib/erp-api';
import { accepted, action, cancelled, id, originalState, scope, serialLocks } from '@/test-fixtures/paid-bill';
import { PaidBillJournal, paidCanonicalJson, PAID_BILL_STORAGE_KEY, readPaidBillEnvelope } from '@/lib/paid-bill-journal';
import PaidBillAmendment from './PaidBillAmendment';
const m = vi.hoisted(() => ({ auth: {} as Record<string, unknown>, generation: 1, session: 'original-family', online: true, failWrite: false, failClear: false, terminals: vi.fn(), settlementLabels: vi.fn(), state: vi.fn(), preview: vi.fn(), unlock: vi.fn(), commit: vi.fn(), recoverByKey: vi.fn(), cancel: vi.fn() }));
vi.mock('@/modules/auth/AuthContext', () => ({ useAuth: () => m.auth }));
vi.mock('@/hooks/useOnlineStatus', () => ({ useOnlineStatus: () => m.online }));
vi.mock('@/lib/start-work', () => ({ hasPendingWork: () => false }));
vi.mock('@/lib/paid-bill-api', async importOriginal => ({
  ...await importOriginal<typeof import('@/lib/paid-bill-api')>(),
  currentPaidEndpoint: () => 'http://localhost:3000/api/v1',
  capturePaidBillLease: (s: unknown, current: () => boolean) => { const generation = m.generation, identity = m.session; return { scope: s, generation, identity, current: () => current() && generation === m.generation && identity === m.session }; },
  paidBillAmendments: m,
}));
let root: Root, host: HTMLDivElement;
const receipt = { order_id: id(5), branch_id: id(3), terminal_id: id(4), company_id: id(1), invoice_no: 'D/QA/26-27/00001', total_minor: 10500, receipt_correction: null, tax_minor: 500 } as ReceiptHistoryDTO;
const committed = vi.fn();
beforeEach(() => {
  (globalThis as unknown as { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
  localStorage.clear(); vi.clearAllMocks(); m.generation = 1; m.session = 'original-family'; m.online = true; m.failWrite = false; m.failClear = false;
  const records = new Map<string, string>();
  vi.stubGlobal('localStorage', { getItem: (key: string) => records.get(key) ?? null, setItem(key: string, value: string) { if (m.failWrite || m.failClear && JSON.parse(value).pending === null) throw new Error('quota'); records.set(key, value); }, clear: () => records.clear() });
  m.auth = { me: { user_id: scope.userId, company_id: scope.companyId, branch_id: null, audit_access: true, protected_access: true, effective_permissions: ['finance.receipt.correct', 'pos.refund'] }, terminalId: scope.terminalId, terminalReady: true };
  Object.defineProperty(navigator, 'locks', { configurable: true, value: serialLocks() });
  m.terminals.mockResolvedValue([{ id: scope.terminalId, branch_id: scope.branchId, is_active: true, paid_bill_amendment_protocol: 1, paid_bill_action_outcome_protocol: 1 }]);
  m.state.mockResolvedValue(originalState());
  m.settlementLabels.mockResolvedValue({ [id(6)]: 'Front till · Owner · 13 Nov 2026, 10:00 am · 00000000' });
  m.preview.mockImplementation(async body => ({ protocol: 1, order_id: body.order_id, invoice_no: receipt.invoice_no, source_revision: 0, source_fingerprint: 'a'.repeat(64), original_total_minor: 10500, actual_refunded_minor: 0, effective_total_minor: 10500, proposed_total_minor: body.target_total_minor, proposed_refund_minor: 10500 - body.target_total_minor, eligible: true, commit_available: !!body.settlement_shift_id && !!body.refund_mode, blockers: [], settlement_shift_ids: [id(6)], refund_modes: ['cash'], settlement_shift_id: body.settlement_shift_id, refund_mode: body.refund_mode, financial_effect: false, stock_effect: false }));
  m.unlock.mockResolvedValue({ token: 'memory-only-owner-grant', expires_in_seconds: 300, purpose: 'paid_bill_amendment' });
  m.commit.mockImplementation(async (body, key) => { const pending = readPaidBillEnvelope().pending!; expect(pending.key).toBe(key); expect(pending.body).toEqual(body); return accepted(pending).accepted; });
  m.recoverByKey.mockImplementation(async () => accepted(readPaidBillEnvelope().pending!));
  m.cancel.mockImplementation(async (_body, why) => cancelled(readPaidBillEnvelope().pending!, why));
  committed.mockResolvedValue(undefined); host = document.createElement('div'); document.body.append(host); root = createRoot(host);
});
afterEach(() => { act(() => root.unmount()); document.body.replaceChildren(); vi.unstubAllGlobals(); vi.useRealTimers(); localStorage.clear(); vi.restoreAllMocks(); });
async function render(value = receipt) { await act(async () => { root.render(<MemoryRouter><PaidBillAmendment receipt={value} onCommitted={committed}/></MemoryRouter>); }); }
const button = (label: string) => { const v = [...host.querySelectorAll('button')].find(n => n.textContent === label); if (!v) throw new Error('Missing button ' + label); return v; };
async function click(label: string) { await act(async () => button(label).click()); }
async function field(label: string, value: string) {
  const owner = [...host.querySelectorAll('label')].find(n => n.textContent?.startsWith(label));
  const node = owner?.querySelector('input,select,textarea') as HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement;
  if (!node) throw new Error('Missing field ' + label);
  await act(async () => { Object.getOwnPropertyDescriptor(Object.getPrototypeOf(node), 'value')!.set!.call(node, value); node.dispatchEvent(new Event(node instanceof HTMLSelectElement ? 'change' : 'input', { bubbles: true })); });
}
async function prepare() { await click('Change amount'); await field('Was money', 'received'); await field('New bill amount', '60'); await field('Reason for changing', 'Original bill amount was mistaken'); await click('Review exact change'); await field('Settlement shift', id(6)); await field('Refund method', 'cash'); await click('Review exact change'); }
async function unlock(label = 'Unlock reviewed change') { await field('Owner password', 'test-password'); await click(label); expect(host.querySelector<HTMLInputElement>('input[type=password]')?.value).toBe(''); }
it('does not autofocus on mount; explicit entry and close move focus to heading and actual invoker', async () => {
  const outside = document.createElement('button'); document.body.append(outside); outside.focus(); await render(); expect(document.activeElement).toBe(outside);
  const trigger = button('Change amount'); trigger.focus(); await click('Change amount'); expect(document.activeElement?.textContent).toBe('Review lower bill amount');
  await click('Close change review'); expect(document.activeElement).toBe(trigger);
});
it('requires explicit money classification and never turns unknown/not-received into a refund or auto-attestation', async () => {
  await render(); await click('Discard bill'); expect(host.querySelector('select')?.value).toBe('');
  await field('Was money', 'unknown'); expect(host.textContent).toContain('Confirm the actual payment'); expect(m.preview).not.toHaveBeenCalled();
  await field('Was money', 'not_received'); expect(host.textContent).toContain('separate'); expect(host.querySelectorAll('input[type=checkbox]')).toHaveLength(0); expect(m.commit).not.toHaveBeenCalled();
});
it('does not enable new actions on a missing capability or unreadable saved evidence', async () => {
  m.terminals.mockResolvedValue([{ id: scope.terminalId, branch_id: scope.branchId, is_active: true, paid_bill_amendment_protocol: 1 }]); await render(); expect(button('Change amount').disabled).toBe(true);
  m.terminals.mockResolvedValue([{ id: scope.terminalId, branch_id: scope.branchId, is_active: true, paid_bill_amendment_protocol: 1, paid_bill_action_outcome_protocol: 1 }]); localStorage.setItem(PAID_BILL_STORAGE_KEY, '{corrupt'); await render({ ...receipt }); expect(button('Change amount').disabled).toBe(true); expect(m.commit).not.toHaveBeenCalled();
});
it('saves exact reviewed GST-compatible command before transport and only then acknowledges its matched proof', async () => {
  await render(); await prepare(); expect(m.preview).toHaveBeenCalledTimes(2); expect(m.preview.mock.calls[0][0].settlement_shift_id).toBeNull(); expect(m.preview.mock.calls[1][0].settlement_shift_id).toBe(id(6));
  await unlock(); await click('Save reviewed change'); expect(m.commit).toHaveBeenCalledTimes(1); expect(readPaidBillEnvelope().pending).toBeNull();
  expect(localStorage.getItem(PAID_BILL_STORAGE_KEY)).not.toMatch(/test-password|memory-only-owner-grant/); expect(host.textContent).toContain('refund pending'); expect(host.textContent).not.toContain('Money returned'); expect(button('Open existing refund task')).toBeTruthy();
});
it('changing final selection invalidates the review and in-memory grant', async () => {
  await render(); await prepare(); await unlock(); await field('Refund method', ''); expect([...host.querySelectorAll('button')].some(n => n.textContent === 'Save reviewed change')).toBe(false); expect(m.commit).not.toHaveBeenCalled();
});
it('quota/readback failure before transport never sends the paid mutation', async () => {
  await render(); await prepare(); await unlock();
  m.failWrite = true; await click('Save reviewed change'); expect(m.commit).not.toHaveBeenCalled(); expect(host.textContent).toContain('quota');
});
it('lost commit reply and404 outcome retain the same original key/body; stopping requires durable reason and positive proof', async () => {
  m.commit.mockRejectedValue(new Error('Lost success reply')); await render(); await prepare(); await unlock(); await click('Save reviewed change');
  const pending = readPaidBillEnvelope().pending!; expect(pending).not.toBeNull(); expect(host.textContent).toContain('Saved request for');
  await unlock('Unlock saved request'); m.recoverByKey.mockRejectedValueOnce(Object.assign(new Error('404 unconfirmed'), { status: 404 })); await click('Check saved request outcome'); expect(readPaidBillEnvelope().pending).toEqual(pending);
  await unlock('Unlock saved request'); await field('Reason for stopping', 'Stop unresolved saved request'); m.cancel.mockImplementation(async (_body, why, key) => { expect(readPaidBillEnvelope().cancellation_reason).toBe(why); expect(key).toBe(pending.key); return cancelled(pending, why); });
  await click('Stop saved request safely'); expect(readPaidBillEnvelope().pending).toBeNull(); expect(m.commit).toHaveBeenCalledTimes(1); expect(host.textContent).toContain('No bill value, payout or stock changed');
});
it('an accepted winner of cancellation opens its existing task and never withdraws it', async () => {
  const j = new PaidBillJournal(scope); j.ensureReady(); j.reserve(action(), 0); await render(); await unlock('Unlock saved request'); await field('Reason for stopping', 'Stop unresolved saved request'); m.cancel.mockResolvedValue(accepted()); await click('Stop saved request safely');
  expect(readPaidBillEnvelope().pending).toBeNull(); expect(button('Open existing refund task')).toBeTruthy(); expect(m.commit).not.toHaveBeenCalled(); expect(host.textContent).toContain('refund pending');
});
it('lost cancellation reply survives remount/new generation and fresh grant without changing saved identity', async () => {
  const j = new PaidBillJournal(scope); j.ensureReady(); j.reserve(action(), 0); await render(); await unlock('Unlock saved request'); await field('Reason for stopping', 'Stop unresolved saved request'); m.cancel.mockRejectedValue(new Error('Lost cancellation reply')); await click('Stop saved request safely');
  expect(j.read().cancellation_reason).toBe('Stop unresolved saved request'); const pending = j.read().pending!;
  await act(async () => { root.unmount(); }); root = createRoot(host); m.generation = 8; await render(); await unlock('Unlock saved request'); m.recoverByKey.mockResolvedValue(cancelled(pending, j.read().cancellation_reason!)); await click('Check saved request outcome'); expect(j.read().pending).toBeNull(); expect(m.commit).not.toHaveBeenCalled();
});
it('a different receipt cannot unlock or dispatch a saved action for the unassigned Owner', async () => {
  const j = new PaidBillJournal(scope); j.ensureReady(); j.reserve(action(), 0); await render({ ...receipt, order_id: id(99), invoice_no: 'OTHER-RECEIPT' });
  expect(host.textContent).toContain('Open receipt D/QA/26-27/00001'); expect([...host.querySelectorAll('button')].some(n => n.textContent === 'Unlock saved request')).toBe(false); expect(m.unlock).not.toHaveBeenCalled(); expect(j.read().pending).toEqual(action());
});
it('a mismatched positive reference keeps pending evidence and never enables another refund', async () => {
  const j = new PaidBillJournal(scope); j.ensureReady(); j.reserve(action(), 0); await render(); await unlock('Unlock saved request'); const proof = accepted(); proof.reference.order_id = id(90); m.recoverByKey.mockResolvedValue(proof); await click('Check saved request outcome'); expect(j.read().pending).toEqual(action()); expect(host.textContent).toContain('No matching durable outcome'); expect(m.commit).not.toHaveBeenCalled();
});
it('unmount before a waited capability read forbids late paid dispatch', async () => {
  await render(); await prepare(); await unlock(); let reply!: (v: unknown) => void; m.terminals.mockImplementationOnce(() => new Promise(r => { reply = r; }));
  await act(async () => { button('Save reviewed change').click(); }); await act(async () => root.unmount()); root = createRoot(host);
  await act(async () => reply([{ id: scope.terminalId, branch_id: scope.branchId, is_active: true, paid_bill_amendment_protocol: 1, paid_bill_action_outcome_protocol: 1 }])); expect(m.commit).not.toHaveBeenCalled();
});
it('server-confirmed outcome with failed local cleanup remains recoverable without resubmitting', async () => {
  await render(); await prepare(); await unlock(); m.failClear = true; await click('Save reviewed change');
  expect(readPaidBillEnvelope().pending).not.toBeNull(); expect(readPaidBillEnvelope().accepted).not.toBeNull(); expect(host.textContent).toContain('local recovery acknowledgement');
  m.failClear = false; await unlock('Unlock saved request'); await click('Check saved request outcome'); expect(readPaidBillEnvelope().pending).toBeNull(); expect(m.commit).toHaveBeenCalledTimes(1);
});
it('a first definite source refusal is retained until exact retirement, just like an uncertain retry', async () => {
  m.commit.mockRejectedValue(Object.assign(new Error('Source changed; original request refused'), { status: 422 }));
  await render(); await prepare(); await unlock(); await click('Save reviewed change');
  const pending = readPaidBillEnvelope().pending!; expect(pending.body.source_fingerprint).toBe('a'.repeat(64));
  await unlock('Unlock saved request'); await field('Reason for stopping', 'Retire stale reviewed command'); await click('Stop saved request safely');
  expect(m.cancel.mock.calls[0][0]).toEqual(pending.body); expect(m.cancel.mock.calls[0][2]).toBe(pending.key); expect(readPaidBillEnvelope().pending).toBeNull();
});
it('metadata-only discard after a full prior refund creates no task or new payout', async () => {
  m.state.mockResolvedValue({ ...originalState(), actual_refunded_minor: 10500, effective_total_minor: 0 });
  m.preview.mockImplementation(async body => ({ protocol: 1, order_id: body.order_id, invoice_no: receipt.invoice_no, source_revision: 0, source_fingerprint: 'a'.repeat(64), original_total_minor: 10500, actual_refunded_minor: 10500, effective_total_minor: 0, proposed_total_minor: 0, proposed_refund_minor: 0, eligible: true, commit_available: true, blockers: [], settlement_shift_ids: [], refund_modes: [], settlement_shift_id: null, refund_mode: null, financial_effect: false, stock_effect: false }));
  m.recoverByKey.mockImplementation(async () => { const proof = accepted(readPaidBillEnvelope().pending!); proof.current.actual_refunded_minor = 10500; proof.current.effective_total_minor = 0; return proof; });
  await render(); await click('Discard bill'); await field('Was money', 'received'); await field('Reason for changing', 'Discard fully refunded mistaken bill'); await click('Review exact change'); await unlock(); await click('Save reviewed change');
  expect(readPaidBillEnvelope().pending).toBeNull(); expect(host.textContent).toContain('Earlier recorded refunds already cover'); expect([...host.querySelectorAll('button')].some(n => n.textContent === 'Open existing refund task')).toBe(false); expect(m.commit.mock.calls[0][0].settlement_shift_id).toBeNull();
});
it('expires RAM-only Owner approval at the conservative monotonic deadline', async () => {
  let now = 0; vi.spyOn(performance, 'now').mockImplementation(() => now);
  await render(); await prepare(); vi.useFakeTimers(); await unlock(); expect(button('Save reviewed change').disabled).toBe(false);
  now = 300000;
  await act(async () => { await vi.advanceTimersByTimeAsync(300000); });
  expect(button('Save reviewed change').disabled).toBe(true);
  await click('Save reviewed change'); expect(m.commit).not.toHaveBeenCalled(); expect(readPaidBillEnvelope().pending).toBeNull(); expect(button('Save reviewed change').disabled).toBe(true);
  vi.useRealTimers();
});
it('checks expiry after a waited capability request before creating any saved unknown command', async () => {
  let now = 0; vi.spyOn(performance, 'now').mockImplementation(() => now);
  await render(); await prepare(); await unlock();
  m.terminals.mockImplementationOnce(async () => { now = 300000; return [{ id: scope.terminalId, branch_id: scope.branchId, is_active: true, paid_bill_amendment_protocol: 1, paid_bill_action_outcome_protocol: 1 }]; });
  await click('Save reviewed change'); expect(m.commit).not.toHaveBeenCalled(); expect(readPaidBillEnvelope().pending).toBeNull(); expect(host.textContent).toContain('Owner approval expired');
});
it('explicit locking clears the approval and slow unlock replies cannot extend its lifetime', async () => {
  let now = 0; vi.spyOn(performance, 'now').mockImplementation(() => now);
  await render(); await prepare(); await unlock(); await click('Lock Owner approval'); expect(button('Save reviewed change').disabled).toBe(true);
  m.unlock.mockImplementationOnce(async () => { now = 300001; return { token: 'too-late-grant', expires_in_seconds: 300 }; });
  await unlock(); expect(button('Save reviewed change').disabled).toBe(true); expect(host.textContent).toContain('expired while unlocking'); expect(m.commit).not.toHaveBeenCalled();
});
it('a different tab’s replacement family clears RAM approval while keeping the original journal identity', async () => {
  await render(); await prepare(); await unlock(); m.session = 'replacement-family';
  await act(async () => window.dispatchEvent(new StorageEvent('storage', { key: 'access_token' })));
  expect(button('Save reviewed change').disabled).toBe(true); expect(readPaidBillEnvelope().pending).toBeNull(); expect(m.commit).not.toHaveBeenCalled();
});

it('never offers retry or dispatch for a canonical array-operation journal', async () => {
  const invalid = { version: 1, purpose: 'paid_bill_amendment', generation: 0, scope, pending: { ...action(), body: { ...action().body, operation: ['decrease'] } }, accepted: null, cancelled: null, cancellation_reason: null };
  const raw = paidCanonicalJson(invalid); localStorage.setItem(PAID_BILL_STORAGE_KEY, raw); await render();
  expect(button('Change amount').disabled).toBe(true); expect(host.textContent?.toLowerCase()).toContain('evidence');
  expect([...host.querySelectorAll('button')].some(n => n.textContent === 'Retry exact saved change')).toBe(false);
  expect(m.preview).not.toHaveBeenCalled(); expect(m.commit).not.toHaveBeenCalled(); expect(m.cancel).not.toHaveBeenCalled(); expect(m.unlock).not.toHaveBeenCalled();
  expect(localStorage.getItem(PAID_BILL_STORAGE_KEY)).toBe(raw);
});

it('enriches only eligible settlement options without selecting one or changing the preview', async () => {
  await render(); await click('Change amount'); await field('Was money', 'received'); await field('New bill amount', '100'); await field('Reason for changing', 'Correct the mistaken total'); await click('Review exact change');
  const select = [...host.querySelectorAll('label')].find(l => l.textContent?.startsWith('Settlement shift'))!.querySelector('select')!;
  expect(select.value).toBe(''); expect([...select.options].map(o => o.value)).toEqual(['', id(6)]);
  expect(select.options[1].textContent).toContain('Front till · Owner'); expect(m.preview).toHaveBeenCalledTimes(1);
});
it('metadata read failure keeps eligible choices with an explicit unavailable label', async () => {
  m.settlementLabels.mockRejectedValue(new Error('shift read unavailable')); await render(); await prepare();
  const select = [...host.querySelectorAll('label')].find(l => l.textContent?.startsWith('Settlement shift'))!.querySelector('select')!;
  expect(select.options[1].textContent).toContain('details unavailable'); expect(select.value).toBe(id(6));
  expect(m.preview.mock.calls[1][0].settlement_shift_id).toBe(id(6));
});
it('late label responses cannot publish after lease change or removal of the reviewed options', async () => {
  let resolve!: (labels: Record<string, string>) => void;
  m.settlementLabels.mockImplementation(() => new Promise(r => { resolve = r; }));
  await render(); await prepare(); m.generation++;
  await act(async () => resolve({ [id(6)]: 'Other workspace secret label' }));
  expect(host.textContent).not.toContain('Other workspace secret label');
  await click('Close change review'); expect(host.querySelector('select')).toBeNull();
});
