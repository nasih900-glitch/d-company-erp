// @vitest-environment happy-dom
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { action, accepted, cancelled, deferred, serialLocks } from '@/test-fixtures/paid-bill';
import { TestBroadcastChannel } from '@/test-fixtures/root-recovery';
import { PaidBillJournal, PAID_BILL_STORAGE_KEY, readPaidBillEnvelope } from './paid-bill-journal';
const m = vi.hoisted(() => ({ unlock: vi.fn(), outcome: vi.fn(), cancel: vi.fn(), lock: vi.fn() }));
vi.mock('./paid-bill-historical-api', async original => ({ ...await original<typeof import('./paid-bill-historical-api')>(), historicalPaidApi: m }));
let controller: import('./paid-bill-historical-controller').HistoricalPaidRecoveryController;
const a = action(), unconfirmed = { protocol: 1, outcome: 'unconfirmed', action_key: a.key, company_id: a.scope.companyId, actor_user_id: a.scope.userId, branch_id: a.scope.branchId, terminal_id: a.scope.terminalId };
beforeEach(async () => {
  vi.resetModules(); vi.clearAllMocks(); localStorage.clear(); vi.stubEnv('VITE_API_URL', ''); vi.stubGlobal('BroadcastChannel', TestBroadcastChannel);
  Object.defineProperty(navigator, 'locks', { configurable: true, value: serialLocks() });
  const journal = new PaidBillJournal(a.scope); journal.ensureReady(); journal.reserve(a, 0);
  m.unlock.mockResolvedValue({ token: 'RAM_TOKEN_ONLY', protocol: 1, purpose: 'paid_bill_action_recovery', expires_in_seconds: 300 });
  m.outcome.mockResolvedValue(unconfirmed); m.cancel.mockImplementation(async (_a, reason) => cancelled(a, reason)); m.lock.mockResolvedValue({ protocol: 1, locked: true });
  const { HistoricalPaidRecoveryController } = await import('./paid-bill-historical-controller'); controller = new HistoricalPaidRecoveryController();
});
afterEach(() => { controller.dispose(); TestBroadcastChannel.peers.clear(); vi.restoreAllMocks(); vi.unstubAllGlobals(); vi.unstubAllEnvs(); vi.useRealTimers(); });
it('reveals no private facts before unlock, then checks only and keeps unconfirmed work', async () => {
  expect(controller.view.private).toBeNull(); await controller.unlock('PASSWORD_ONLY');
  expect(m.unlock).toHaveBeenCalledTimes(1); expect(m.outcome).toHaveBeenCalledTimes(1); expect(m.cancel).not.toHaveBeenCalled();
  expect(controller.view.private?.action).toEqual(a); expect(readPaidBillEnvelope().pending).toEqual(a);
  expect(localStorage.getItem(PAID_BILL_STORAGE_KEY)).not.toMatch(/PASSWORD_ONLY|RAM_TOKEN_ONLY/);
});
it('persists the exact cancellation reason before POST and clears only matching durable proof', async () => {
  await controller.unlock('secret'); m.cancel.mockImplementation(async (_a, reason) => { expect(readPaidBillEnvelope().cancellation_reason).toBe(reason); return cancelled(a, reason); });
  await controller.check(true, '  Stop this exact saved command  ');
  expect(readPaidBillEnvelope().pending).toBeNull(); expect(controller.view.private?.outcome?.outcome).toBe('cancelled'); expect(controller.view.private?.pending).toBe(false);
});
it('lost cancellation reply and restart keep the exact key/body/reason for fresh authenticated lookup', async () => {
  await controller.unlock('secret'); m.cancel.mockRejectedValueOnce(new Error('lost reply')); await controller.check(true, 'Retire exact saved command');
  const saved = readPaidBillEnvelope(); expect(saved.cancellation_reason).toBe('Retire exact saved command');
  controller.dispose(); const { HistoricalPaidRecoveryController } = await import('./paid-bill-historical-controller'); controller = new HistoricalPaidRecoveryController();
  m.outcome.mockResolvedValue(cancelled(a, saved.cancellation_reason!)); await controller.unlock('fresh secret'); expect(readPaidBillEnvelope().pending).toBeNull(); expect(m.cancel).toHaveBeenCalledTimes(1);
});
it('an accepted winner resolves uncertainty using existing task guidance without any payout navigation', async () => {
  m.outcome.mockResolvedValue(accepted()); await controller.unlock('secret');
  expect(readPaidBillEnvelope().pending).toBeNull(); expect(controller.view.private?.outcome?.outcome).toBe('accepted'); expect(controller.view.notice).toContain('normal authorized operations'); expect(m.cancel).not.toHaveBeenCalled();
});
it.each(['404', '409', 'wrong reference', 'wrong reason'])('%s never clears the exact journal', async problem => {
  const saved = localStorage.getItem(PAID_BILL_STORAGE_KEY);
  if (problem === '404' || problem === '409') m.outcome.mockRejectedValue(new Error(problem));
  else if (problem === 'wrong reason') m.outcome.mockResolvedValue(cancelled(a, 'Unrequested cancellation'));
  else { const proof = accepted(); proof.reference.order_id = a.scope.companyId; m.outcome.mockResolvedValue(proof); }
  await controller.unlock('secret'); expect(localStorage.getItem(PAID_BILL_STORAGE_KEY)).toBe(saved); expect(controller.view.error).toBeTruthy();
});
it.each(['session', 'terminal', 'journal', 'endpoint', 'dispose'])('a %s change while unlock waits hides all late private data', async change => {
  const wait = deferred<{ token: string; protocol: number; expires_in_seconds: number }>(); m.unlock.mockReturnValue(wait.promise);
  const pending = controller.unlock('secret'); await vi.waitFor(() => expect(m.unlock).toHaveBeenCalled());
  if (change === 'session') { const peer = new TestBroadcastChannel('dcompany-root-workspace-v1'); peer.postMessage({ kind: 'workspace-changed' }); peer.close(); }
  if (change === 'terminal') { localStorage.setItem('terminal_id', 'changed'); window.dispatchEvent(new StorageEvent('storage', { key: 'terminal_id' })); }
  if (change === 'journal') { localStorage.setItem(PAID_BILL_STORAGE_KEY, '{corrupt'); window.dispatchEvent(new StorageEvent('storage', { key: PAID_BILL_STORAGE_KEY })); }
  if (change === 'endpoint') vi.stubEnv('VITE_API_URL', '/other/api/v1');
  if (change === 'dispose') controller.dispose();
  wait.resolve({ token: 'LATE_PRIVATE_TOKEN', protocol: 1, expires_in_seconds: 300 }); await pending;
  expect(controller.view.private).toBeNull(); expect(m.outcome).not.toHaveBeenCalled();
  if (change === 'journal') expect(localStorage.getItem(PAID_BILL_STORAGE_KEY)).toBe('{corrupt');
  else expect(readPaidBillEnvelope().pending).toEqual(a);
});
it('expires conservative RAM approval and disables later cancellation', async () => {
  let now = 0; vi.spyOn(performance, 'now').mockImplementation(() => now); await controller.unlock('secret');
  vi.useFakeTimers(); // Re-arm through another explicit unlock under the same monotonic clock.
  await controller.unlock('secret'); now = 299000; await vi.advanceTimersByTimeAsync(299000);
  expect(controller.view.private).toBeNull(); await controller.check(true, 'No longer approved'); expect(m.cancel).not.toHaveBeenCalled(); expect(readPaidBillEnvelope().pending).toEqual(a);
});
it('explicit Lock immediately hides facts and lost Lock reply cannot become a revocation claim', async () => {
  await controller.unlock('secret'); const wait = deferred<{ protocol: number; locked: boolean }>(); m.lock.mockReturnValue(wait.promise);
  const locking = controller.lock(); expect(controller.view.private).toBeNull(); expect(controller.view.notice).not.toContain('Server recovery access revoked');
  wait.resolve({ protocol: 1, locked: true }); await locking; expect(controller.view.notice).toContain('Server recovery access revoked'); expect(readPaidBillEnvelope().pending).toEqual(a);
  await controller.unlock('secret'); m.lock.mockRejectedValue(new Error('lost reply')); await controller.lock();
  expect(controller.view.private).toBeNull(); expect(controller.view.notice).toContain('server confirmation is pending'); expect(readPaidBillEnvelope().pending).toEqual(a);
});
it('reason write failure sends no cancellation and preserves original command', async () => {
  const storage = localStorage; let full = false, intercepted = 0;
  vi.stubGlobal('localStorage', { getItem: storage.getItem.bind(storage), setItem(key: string, value: string) { if (full && key === PAID_BILL_STORAGE_KEY) { intercepted++; throw new Error('quota'); } storage.setItem(key, value); } });
  await controller.unlock('secret'); full = true;
  await controller.check(true, 'Stop exact saved command'); expect(intercepted).toBe(1); expect(m.cancel).not.toHaveBeenCalled(); expect(readPaidBillEnvelope().pending).toEqual(a);
});
it('expires a lost-Lock revocation handle even after a later authentication attempt fails', async () => {
  vi.useFakeTimers(); let now = 0; vi.spyOn(performance, 'now').mockImplementation(() => now);
  await controller.unlock('secret'); m.lock.mockRejectedValue(new Error('lost reply')); await controller.lock();
  expect(Reflect.get(controller, 'revocation')).not.toBeNull();
  m.unlock.mockRejectedValue(new Error('wrong password')); await controller.unlock('wrong password');
  expect(controller.view.private).toBeNull(); expect(Reflect.get(controller, 'revocation')).not.toBeNull();
  now = 299000; await vi.advanceTimersByTimeAsync(299000);
  expect(Reflect.get(controller, 'revocation')).toBeNull();
  const calls = m.lock.mock.calls.length; await controller.lock(); expect(m.lock).toHaveBeenCalledTimes(calls);
  expect(readPaidBillEnvelope().pending).toEqual(a);
});
it('missing browser admission support never sends the password', async () => {
  Object.defineProperty(navigator, 'locks', { configurable: true, value: undefined }); await controller.unlock('secret'); expect(m.unlock).not.toHaveBeenCalled(); expect(readPaidBillEnvelope().pending).toEqual(a);
});
it('an expired queued unlock never dispatches the password after admission resumes', async () => {
  let now = 0; vi.spyOn(performance, 'now').mockImplementation(() => now);
  const wait = deferred(); Object.defineProperty(navigator, 'locks', { configurable: true, value: { request: async (_key: string, _options: unknown, run: () => Promise<unknown>) => { await wait.promise; return run(); } } });
  const operation = controller.unlock('not-dispatched'); now = 299000; wait.resolve(); await operation;
  expect(m.unlock).not.toHaveBeenCalled(); expect(controller.view.private).toBeNull(); expect(readPaidBillEnvelope().pending).toEqual(a);
});
it('confirmed outcome with failed local clear remains recoverable without another financial command', async () => {
  m.outcome.mockResolvedValue(accepted()); const storage = localStorage; let full = true;
  vi.stubGlobal('localStorage', { getItem: storage.getItem.bind(storage), setItem(key: string, value: string) { if (full && key === PAID_BILL_STORAGE_KEY && JSON.parse(value).pending === null) throw new Error('quota'); storage.setItem(key, value); } });
  await controller.unlock('secret'); expect(readPaidBillEnvelope().pending).toEqual(a); expect(readPaidBillEnvelope().accepted).not.toBeNull(); expect(controller.view.notice).toContain('local acknowledgement is pending');
  full = false; await controller.check(); expect(readPaidBillEnvelope().pending).toBeNull(); expect(m.cancel).not.toHaveBeenCalled();
});
it('accepts original immutable acceptance when later bill history advanced, retaining the original task identity', async () => {
  const proof = accepted(); proof.current.source_revision = 2;
  proof.current.history.push({ ...proof.current.history[0], id: '00000000-0000-4000-8000-000000000012', revision: 2, target_minor: 5000, refund_request_id: '00000000-0000-4000-8000-000000000013' });
  m.outcome.mockResolvedValue(proof); await controller.unlock('secret');
  expect(readPaidBillEnvelope().pending).toBeNull();
  const result = controller.view.private?.outcome;
  expect(result?.outcome).toBe('accepted');
  if (result?.outcome === 'accepted') expect(result.accepted.refund_request_id).toBe(proof.accepted.refund_request_id);
});
