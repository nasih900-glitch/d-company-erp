// @vitest-environment happy-dom
import { beforeEach, expect, it } from 'vitest';
import { action, id, scope } from '@/test-fixtures/paid-bill';
import { isPaidContextRestore, originalPaidTerminal, paidLoginMatches } from './paid-bill-context';
import { PaidBillJournal } from './paid-bill-journal';
beforeEach(() => localStorage.clear());
it('keeps ordinary no-work login independent of paid feature capabilities', () => {
  expect(paidLoginMatches('https://any.test/api', id(70), id(71))).toBe(true);
  expect(originalPaidTerminal(scope.endpoint, scope.userId, scope.companyId, null, [])).toBeNull();
});
it('permits trusted exact original terminal restoration after reload/reauth, without retargeting', () => {
  const j = new PaidBillJournal(scope); j.ensureReady(); j.reserve(action(), 0); const saved = j.read();
  const rows = [{ id: scope.terminalId, branch_id: scope.branchId, is_active: true, name: 'Original register' }];
  expect(paidLoginMatches(scope.endpoint, scope.userId, scope.companyId)).toBe(true);
  expect(originalPaidTerminal(scope.endpoint, scope.userId, scope.companyId, null, rows as never)?.id).toBe(scope.terminalId);
  expect(isPaidContextRestore(scope)).toBe(true); expect(isPaidContextRestore({ ...scope, terminalId: id(90) })).toBe(false);
  expect(j.read()).toEqual(saved);
});
it('refuses wrong account/server, inactive or missing original terminal and branch mismatch', () => {
  const j = new PaidBillJournal(scope); j.ensureReady(); j.reserve(action(), 0);
  expect(paidLoginMatches(scope.endpoint, id(90), scope.companyId)).toBe(false);
  expect(paidLoginMatches(scope.endpoint + '/other', scope.userId, scope.companyId)).toBe(false);
  for (const rows of [[], [{ id: scope.terminalId, branch_id: scope.branchId, is_active: false }], [{ id: scope.terminalId, branch_id: id(90), is_active: true }]]) expect(() => originalPaidTerminal(scope.endpoint, scope.userId, scope.companyId, null, rows as never)).toThrow();
  expect(() => originalPaidTerminal(scope.endpoint, scope.userId, scope.companyId, id(91), [{ id: scope.terminalId, branch_id: scope.branchId, is_active: true }] as never)).toThrow();
  expect(j.read().pending).toEqual(action());
});

it.each(['false', 1, null, undefined, [true]])('requires boolean true for trusted original-terminal active state: %j', is_active => {
  const j = new PaidBillJournal(scope); j.ensureReady(); j.reserve(action(), 0);
  const rows = [{ id: scope.terminalId, branch_id: scope.branchId, is_active }];
  expect(() => originalPaidTerminal(scope.endpoint, scope.userId, scope.companyId, null, rows as never)).toThrow();
  expect(j.read().pending).toEqual(action());
});
