// @vitest-environment happy-dom
import { beforeEach, expect, it, vi } from 'vitest';
import { deferred, scope, serialLocks, id } from '@/test-fixtures/paid-bill';
const m = vi.hoisted(() => ({ token: '', generation: 1, terminal: '' }));
vi.mock('./api', () => ({ readAccessToken: () => m.token, readSessionGeneration: () => m.generation }));
vi.mock('./paid-bill-api', () => ({ currentPaidEndpoint: () => 'http://localhost:3000/api/v1' }));
vi.mock('./operational-context', () => ({ readStoredTerminalId: () => m.terminal }));
import { capturePaidWorkspaceFence, withoutPaidBillWork } from './paid-bill-admission';
const token = (change: Record<string, unknown> = {}) => btoa('{}') + '.' + btoa(JSON.stringify({ type: 'access', sub: scope.userId, company_id: scope.companyId, branch_id: scope.branchId, session_family_id: id(50), auth_version: 7, nonce: 'original', ...change })) + '.signature';
beforeEach(() => { localStorage.clear(); m.token = token(); m.generation = 1; m.terminal = scope.terminalId; Object.defineProperty(navigator, 'locks', { configurable: true, value: serialLocks() }); });
it('allows ordinary successful close/start/settings work through a same-family access-token rotation', async () => {
  const fence = capturePaidWorkspaceFence();
  const result = await withoutPaidBillWork(async () => { m.token = token({ nonce: 'renewed', exp: 9999999999 }); return 'server operation saved'; });
  expect(result).toBe('server operation saved'); expect(fence.current()).toBe(true);
});
it.each([{ sub: id(90) }, { company_id: id(90) }, { session_family_id: id(90) }, { auth_version: 8 }, { branch_id: id(90) }])('rejects real authority replacement after an await: %j', async change => {
  const entered = deferred(), wait = deferred();
  const operation = withoutPaidBillWork(async () => { entered.resolve(); await wait.promise; return 'not attributable to replacement account'; });
  await entered.promise; m.token = token(change); wait.resolve(); await expect(operation).rejects.toThrow('workspace changed');
});
it('rejects a new login generation even if the actor/family fields happen to match', async () => {
  const fence = capturePaidWorkspaceFence(); m.generation++; expect(fence.current()).toBe(false);
});
it('continues to reject changed terminal despite a legitimate bearer rotation', () => {
  const fence = capturePaidWorkspaceFence(); m.token = token({ nonce: 'renewed' }); m.terminal = id(90); expect(fence.current()).toBe(false);
});
