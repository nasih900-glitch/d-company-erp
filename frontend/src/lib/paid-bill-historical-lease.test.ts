// @vitest-environment happy-dom
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { action } from '@/test-fixtures/paid-bill';
import { TestBroadcastChannel } from '@/test-fixtures/root-recovery';
let bridge: typeof import('./root-workspace-epoch'), Lease: typeof import('./paid-bill-historical-lease').HistoricalPaidLease;
let journal: typeof import('./paid-bill-journal');
let unsubscribe: () => void;
beforeEach(async () => {
  vi.resetModules(); vi.stubGlobal('BroadcastChannel', TestBroadcastChannel); vi.stubEnv('VITE_API_URL', ''); localStorage.clear();
  bridge = await import('./root-workspace-epoch'); ({ HistoricalPaidLease: Lease } = await import('./paid-bill-historical-lease')); journal = await import('./paid-bill-journal');
  const j = new journal.PaidBillJournal(action().scope); j.ensureReady(); j.reserve(action(), 0); unsubscribe = bridge.subscribeRootWorkspace(() => {});
});
afterEach(() => { unsubscribe(); TestBroadcastChannel.peers.clear(); vi.unstubAllGlobals(); vi.unstubAllEnvs(); });
it('captures a fresh process lease without touching any ordinary credentials or terminal selection', () => {
  localStorage.setItem('terminal_id', 'active-other-till'); localStorage.setItem('refresh_token', 'ordinary-private-token');
  const before = { ...localStorage }; const lease = new Lease(journal.readPaidBillEnvelope());
  expect(lease.current()).toBe(true); expect({ ...localStorage }).toEqual(before);
});
it('compares actual already-loaded ordinary endpoint and live lineage in addition to configured path', () => {
  let endpoint = '/api/v1', lineage = 'same-session'; bridge.registerOrdinaryWorkspace(() => ({ endpoint, lineage }));
  const lease = new Lease(journal.readPaidBillEnvelope()); lineage = 'replacement'; expect(lease.current()).toBe(false);
  lineage = 'same-session'; endpoint = '/other/api/v1'; expect(() => new Lease(journal.readPaidBillEnvelope())).toThrow(); expect(lease.current()).toBe(false);
});
it('checks nonsecret epoch publication and cross-tab uncertainty without breaking ordinary operations', () => {
  const lease = new Lease(journal.readPaidBillEnvelope()); const storage = localStorage;
  vi.stubGlobal('localStorage', { getItem: storage.getItem.bind(storage), setItem() { throw new Error('quota'); } });
  expect(() => bridge.advanceRootWorkspaceEpoch()).not.toThrow(); expect(lease.current()).toBe(false); expect(() => new Lease(journal.readPaidBillEnvelope())).toThrow();
});
it('allows only the explicit same-command reason/ack/clear transitions, not arbitrary changed journal content', async () => {
  const lease = new Lease(journal.readPaidBillEnvelope()); lease.reason('Stop original saved command'); expect(lease.current()).toBe(true);
  const other = { ...action(), key: action().key + ':different' };
  const original = journal.readPaidBillEnvelope(); localStorage.setItem(journal.PAID_BILL_STORAGE_KEY, journal.paidCanonicalJson({ ...original, pending: other }));
  expect(lease.current()).toBe(false);
});
