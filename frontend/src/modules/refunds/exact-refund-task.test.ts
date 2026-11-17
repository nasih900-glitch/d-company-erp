import { expect, it } from 'vitest';
import { id, refundTask } from '@/test-fixtures/paid-bill';
import { matchesExactRefundTask, parseExactRefundTask } from './exact-refund-task';
const target = { id: id(7), orderId: id(5), amountMinor: 4500 };
it('matches the exact task and original immutable amount, not a latest-task convention', () => {
  expect(matchesExactRefundTask(refundTask(), target, id(3), id(4))).toBe(true);
  expect(parseExactRefundTask(new URLSearchParams({ refund_request_id: id(7), receipt_order_id: id(5), refund_amount_minor: '4500' }))).toEqual(target);
  for (const change of [{ id: id(90) }, { order_id: id(90) }, { branch_id: id(90) }, { terminal_id: id(90) }, { amount_minor: 4501 }, { amount_minor: null }, { amount_minor: '4500' }, { status: 'future_status' }, { customer_spend_reconciled: 1 }]) expect(matchesExactRefundTask({ ...refundTask(), ...change }, target, id(3), id(4))).toBe(false);
});
it.each(['', '-1', '1e3', '45.00', 'Infinity', '1000000000001'])('rejects malformed expected amount %s without creating another request', amount => {
  expect(() => parseExactRefundTask(new URLSearchParams({ refund_request_id: id(7), receipt_order_id: id(5), refund_amount_minor: amount }))).toThrow();
});
it('requires an explicit receipt identity and preserves completed task IDs', () => {
  expect(() => parseExactRefundTask(new URLSearchParams({ refund_request_id: id(7), order_id: id(5) }))).toThrow();
  expect(matchesExactRefundTask({ ...refundTask(), status: 'settled', settled_at: '2026-11-13T00:00:00Z', settled_by: id(2), refund_id: id(9) }, target, id(3), id(4))).toBe(true);
});

it.each([['mode', 'cash'], ['settlement_method', 'cash'], ['status', 'accepted_cash_due'], ['loyalty_reconciliation_state', 'applied']])('requires actual primitive string for task %s', (field, text) => {
  for (const value of [[text], { toString: text }, 1, true]) {
    const row = { ...refundTask(), [field]: value };
    expect(() => matchesExactRefundTask(row, target, id(3), id(4))).not.toThrow();
    expect(matchesExactRefundTask(row, target, id(3), id(4))).toBe(false);
  }
  expect(matchesExactRefundTask({ ...refundTask(), [field]: text }, target, id(3), id(4))).toBe(true);
});
