// @vitest-environment happy-dom
import { beforeEach, expect, it, vi } from 'vitest';
import { accepted, action, cancelled, deferred, scope, id } from '@/test-fixtures/paid-bill';
const mocks = vi.hoisted(() => ({ generation: 1, token: '', terminal: '', get: vi.fn(), post: vi.fn(), defaults: { baseURL: '/api/v1', transformRequest: [] } }));
vi.mock('./api', () => ({ api: { defaults: mocks.defaults, get: mocks.get, post: mocks.post }, BASE_URL: '/api/v1', hasSessionCandidate: () => !!mocks.token, readAccessToken: () => mocks.token, readSessionGeneration: () => mocks.generation }));
vi.mock('./operational-context', () => ({ readStoredTerminalId: () => mocks.terminal }));
import { capturePaidBillLease, paidBillAmendments, paidBillCapability, paidBillRequestConfig } from './paid-bill-api';
import { clearOwnerWorkspaces } from './owner-workspace';
import { PaidBillJournal } from './paid-bill-journal';
const token = (branch: string | null = scope.branchId) => btoa('{}') + '.' + btoa(JSON.stringify({ type: 'access', sub: scope.userId, company_id: scope.companyId, branch_id: branch })) + '.signature';
beforeEach(() => { mocks.generation = 1; mocks.token = token(); mocks.terminal = scope.terminalId; mocks.defaults.baseURL = '/api/v1'; mocks.get.mockReset(); mocks.post.mockReset(); localStorage.clear(); });
it('requires both exact authenticated capabilities and active original terminal', () => {
  const terminal = { id: scope.terminalId, branch_id: scope.branchId, is_active: true, paid_bill_amendment_protocol: 1, paid_bill_action_outcome_protocol: 1 };
  expect(paidBillCapability([terminal] as never, scope)).toBe(true);
  for (const change of [{ paid_bill_action_outcome_protocol: undefined }, { paid_bill_action_outcome_protocol: '1' }, { paid_bill_amendment_protocol: 2 }, { is_active: false }, { is_active: 'false' }, { is_active: 1 }, { is_active: null }, { is_active: undefined }, { is_active: [true] }, { branch_id: id(40) }]) expect(paidBillCapability([{ ...terminal, ...change }] as never, scope)).toBe(false);
});
it('places key and confirmation only in headers and cancellation reason outside original body', async () => {
  const a = action(), proof = cancelled(a); mocks.post.mockResolvedValue({ data: proof }); mocks.get.mockResolvedValue({ data: proof });
  const lease = capturePaidBillLease(scope, () => true);
  await paidBillAmendments.cancel(a.body, proof.cancelled.cancellation_reason, a.key, 'ephemeral-owner-grant', lease);
  const [path, body, config] = mocks.post.mock.calls[0];
  expect(path).toBe('/pos/paid-bill-amendments/actions/cancel'); expect(body).toEqual({ original_command: a.body, cancellation_reason: proof.cancelled.cancellation_reason });
  expect(JSON.stringify(body)).not.toContain('ephemeral-owner-grant'); expect(config.headers).toEqual({ 'Idempotency-Key': a.key, 'X-Owner-Confirmation': 'ephemeral-owner-grant' });
  await paidBillAmendments.recoverByKey(a.key, 'fresh-grant', lease);
  expect(mocks.get.mock.calls[0][0]).toBe('/pos/paid-bill-amendments/actions/outcome');
  expect(mocks.get.mock.calls[0][0]).not.toContain(a.key);
});
it.each(['generation', 'terminal', 'endpoint', 'cache', 'authority'])('rejects changed %s before dispatch transform and after awaited reply', async changed => {
  let authority = true; const lease = capturePaidBillLease(scope, () => authority), wait = deferred<{ data: unknown }>();
  mocks.get.mockReturnValue(wait.promise); const pending = paidBillAmendments.recoverByKey(action().key, 'grant', lease);
  const config = paidBillRequestConfig(lease);
  if (changed === 'generation') mocks.generation++;
  if (changed === 'terminal') mocks.terminal = id(40);
  if (changed === 'endpoint') mocks.defaults.baseURL = '/other/api/v1';
  if (changed === 'cache') clearOwnerWorkspaces();
  if (changed === 'authority') authority = false;
  const transform = Array.isArray(config.transformRequest) ? config.transformRequest[0] : null;
  expect(() => transform?.call({} as never, action().body, {} as never)).toThrow();
  wait.resolve({ data: accepted() }); await expect(pending).rejects.toThrow('workspace changed');
});
it('restores the same stable journal identity after a new login generation and fresh grant', async () => {
  const j = new PaidBillJournal(scope), a = action(); j.ensureReady(); j.reserve(a, 0);
  const old = capturePaidBillLease(scope, () => true); mocks.generation = 9; mocks.token = token(null); clearOwnerWorkspaces();
  const renewed = capturePaidBillLease(scope, () => true); expect(old.current()).toBe(false); expect(renewed.current()).toBe(true);
  mocks.get.mockResolvedValue({ data: accepted(a) }); const proof = await paidBillAmendments.recoverByKey(a.key, 'fresh-grant-after-login', renewed);
  j.acknowledge(a, proof); j.clearExact(a, proof); expect(j.read().pending).toBeNull(); expect(mocks.get.mock.calls[0][1]._sessionGeneration).toBe(9);
});
it('retains exact command on404 and malformed responses without rekeying', async () => {
  const j = new PaidBillJournal(scope), a = action(); j.ensureReady(); j.reserve(a, 0); const raw = JSON.stringify(j.read());
  mocks.get.mockRejectedValueOnce(Object.assign(new Error('not found'), { status: 404 }));
  await expect(paidBillAmendments.recoverByKey(a.key, 'grant', capturePaidBillLease(scope, () => true))).rejects.toThrow('not found');
  mocks.get.mockResolvedValue({ data: { ...accepted(a), password: 'unexpected' } });
  await expect(paidBillAmendments.recoverByKey(a.key, 'grant', capturePaidBillLease(scope, () => true))).rejects.toThrow('could not be verified');
  expect(JSON.stringify(j.read())).toBe(raw);
});
it('rechecks ephemeral confirmation immediately at the Axios dispatch transform', () => {
  const current = vi.fn(), lease = capturePaidBillLease(scope, () => true);
  const config = paidBillRequestConfig(lease, action().key, 'memory-only-grant', current);
  expect(current).toHaveBeenCalledTimes(1);
  current.mockImplementation(() => { throw new Error('approval expired'); });
  const transform = Array.isArray(config.transformRequest) ? config.transformRequest[0] : null;
  expect(() => transform?.call({} as never, action().body, {} as never)).toThrow('approval expired');
});
it('keeps same-family token renewal valid but rejects a cross-tab replacement family without a local generation change', () => {
  mocks.token = btoa('{}') + '.' + btoa(JSON.stringify({ type: 'access', sub: scope.userId, company_id: scope.companyId, branch_id: scope.branchId, session_family_id: id(50), auth_version: 2, nonce: 'first' })) + '.signature';
  const lease = capturePaidBillLease(scope, () => true);
  mocks.token = btoa('{}') + '.' + btoa(JSON.stringify({ type: 'access', sub: scope.userId, company_id: scope.companyId, branch_id: scope.branchId, session_family_id: id(50), auth_version: 2, nonce: 'renewed' })) + '.signature';
  expect(lease.current()).toBe(true);
  mocks.token = btoa('{}') + '.' + btoa(JSON.stringify({ type: 'access', sub: scope.userId, company_id: scope.companyId, branch_id: scope.branchId, session_family_id: id(51), auth_version: 2 })) + '.signature';
  expect(lease.current()).toBe(false); expect(mocks.generation).toBe(1);
});

it('rejects coerced enum acceptance and outcome payloads before they become typed proofs', async () => {
  const lease = capturePaidBillLease(scope, () => true), proof = accepted();
  mocks.post.mockResolvedValue({ data: { ...proof.accepted, status: ['pending_settlement'] } });
  await expect(paidBillAmendments.commit(action().body, action().key, 'grant', lease)).rejects.toThrow('could not be verified');
  mocks.get.mockResolvedValue({ data: { ...proof, current: { ...proof.current, history: [{ ...proof.current.history[0], operation: ['decrease'] }] } } });
  await expect(paidBillAmendments.recoverByKey(action().key, 'grant', lease)).rejects.toThrow('could not be verified');
});

it('labels only server-eligible IDs from authorized same-branch shift and till metadata', async () => {
  const eligible = id(6), other = id(7);
  mocks.get.mockImplementation(async path => ({ data: path === '/pos/shifts'
    ? [{ id: eligible, branch_id: scope.branchId, terminal_id: scope.terminalId, opened_by_name: 'Operator', opened_at: '2026-11-13T04:30:00Z' }, { id: other, branch_id: scope.branchId, terminal_id: scope.terminalId, opened_by_name: 'Other', opened_at: '2026-11-13T04:30:00Z' }]
    : [{ id: scope.terminalId, branch_id: scope.branchId, name: 'Front till' }] }));
  const labels = await paidBillAmendments.settlementLabels([eligible], capturePaidBillLease(scope, () => true));
  expect(Object.keys(labels)).toEqual([eligible]); expect(labels[eligible]).toContain('Front till · Operator ·'); expect(labels[eligible]).toContain('13 Nov 2026');
  expect(mocks.get.mock.calls.find(([path]) => path === '/pos/shifts')?.[1].params).toEqual({ only_open: true, limit: 200 });
});
it.each(['foreign branch', 'duplicate shift', 'unknown till', 'invalid date', 'missing operator'] as const)('leaves %s settlement metadata explicitly unavailable', async problem => {
  const row = { id: id(6), branch_id: scope.branchId, terminal_id: scope.terminalId, opened_by_name: 'Operator', opened_at: '2026-11-13T04:30:00Z' };
  if (problem === 'foreign branch') row.branch_id = id(90);
  if (problem === 'unknown till') row.terminal_id = id(90);
  if (problem === 'invalid date') row.opened_at = 'not a date';
  if (problem === 'missing operator') row.opened_by_name = '';
  mocks.get.mockImplementation(async path => ({ data: path === '/pos/shifts' ? problem === 'duplicate shift' ? [row, row] : [row] : [{ id: scope.terminalId, branch_id: scope.branchId, name: 'Front till' }] }));
  expect(await paidBillAmendments.settlementLabels([id(6)], capturePaidBillLease(scope, () => true))).toEqual({});
});
it('rejects settlement metadata after a live generation changes during the read', async () => {
  const wait = deferred<{ data: unknown }>(); mocks.get.mockReturnValue(wait.promise);
  const result = paidBillAmendments.settlementLabels([id(6)], capturePaidBillLease(scope, () => true));
  mocks.generation++; wait.resolve({ data: [] }); await expect(result).rejects.toThrow('workspace changed');
});
