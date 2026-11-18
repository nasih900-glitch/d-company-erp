import { describe, expect, it } from 'vitest';
import { action, accepted, cancelled, id } from '@/test-fixtures/paid-bill';
import { effectivePaidEndpoint, matchesPaidOutcome, matchesPaidRecovery, parsePaidAmount, validPaidAction, validPaidBody, validPaidOutcome, validPaidAcceptance, validPaidHistory } from './paid-bill-contract';

describe('strict paid-bill contract', () => {
  it.each(['', '-1', '1.001', '1e2', 'NaN', 'Infinity', '01', '10000000000.01'])('rejects unsafe amount %s', value => expect(() => parsePaidAmount(value)).toThrow());
  it.each([['0', 0], ['0.01', 1], ['60', 6000], ['10000000000', 1_000_000_000_000]])('parses exact decimal %s', (value, minor) => expect(parsePaidAmount(value)).toBe(minor));
  it('binds endpoint port and API base path without silently stripping routing identity', () => {
    expect(effectivePaidEndpoint('/api/v1', 'https://shop.test')).toBe('https://shop.test/api/v1');
    expect(effectivePaidEndpoint('https://shop.test:8443/other/api/v1/', 'https://shop.test')).toBe('https://shop.test:8443/other/api/v1/');
    for (const v of ['https://u:p@shop.test/api', 'https://shop.test/api?q=x', 'https://shop.test/api#x', 'file:///api']) expect(() => effectivePaidEndpoint(v, 'https://shop.test')).toThrow();
  });
  it.each(['password', 'confirmation_token', 'approved_by', 'stock_effect', 'payout_minor'])('does not store or transmit forbidden %s', field => {
    expect(validPaidAction({ ...action(), [field]: 'unsafe' })).toBe(false);
    expect(validPaidBody({ ...action().body, [field]: 'unsafe' }, true)).toBe(false);
  });
  it('requires a positive complete scope/body/history proof rather than status200', () => {
    const a = action(), proof = accepted(a);
    expect(matchesPaidOutcome(a, proof, null)).toBe(true);
    for (const field of ['action_key', 'company_id', 'actor_user_id', 'branch_id', 'terminal_id', 'order_id', 'request_path']) expect(matchesPaidOutcome(a, { ...proof, reference: { ...proof.reference, [field]: id(99) } }, null)).toBe(false);
    expect(matchesPaidOutcome(a, { ...proof, reference: { ...proof.reference, original_command: { ...a.body, reason: 'A different reason' } } }, null)).toBe(false);
    expect(matchesPaidOutcome(a, { ...proof, current: { ...proof.current, history: [] } }, null)).toBe(false);
    expect(matchesPaidOutcome(a, { protocol: 1, outcome: 'unconfirmed', ...a.scope }, null)).toBe(false);
  });
  it('allows current history to advance while immutable acceptance remains the original', () => {
    const a = action(), proof = accepted(a);
    proof.current.source_revision = 2;
    proof.current.status = 'withdrawn';
    proof.current.history.push({ ...proof.current.history[0], id: id(20), revision: 2, target_minor: 4000, refund_request_id: id(21), status: 'withdrawn' });
    expect(matchesPaidRecovery(a, { accepted: proof.accepted, current: proof.current })).toBe(true);
    expect(matchesPaidOutcome(a, proof, null)).toBe(true);
  });
  it('requires durable exact cancellation reason and false money/stock effects', () => {
    const a = action(), proof = cancelled(a);
    expect(matchesPaidOutcome(a, proof, proof.cancelled.cancellation_reason)).toBe(true);
    expect(matchesPaidOutcome(a, proof, null)).toBe(false);
    expect(matchesPaidOutcome(a, proof, 'Other cancellation reason')).toBe(false);
    expect(validPaidOutcome({ ...proof, cancelled: { ...proof.cancelled, financial_effect: 0 } })).toBe(false);
    expect(validPaidOutcome({ ...proof, cancelled: { ...proof.cancelled, stock_effect: true } })).toBe(false);
  });
  it('accepts null task only for exact fully-refunded metadata-only discard', () => {
    const a = action(); a.body.operation = 'discard'; a.body.target_total_minor = 0; a.body.settlement_shift_id = null; a.body.refund_mode = null;
    a.expected.effective_total_minor = 0; a.expected.proposed_refund_minor = 0; a.expected.task_required = false;
    const proof = accepted(a); proof.current.actual_refunded_minor = 10500; proof.current.effective_total_minor = 0;
    expect(validPaidAction(a)).toBe(true); expect(matchesPaidOutcome(a, proof, null)).toBe(true);
    expect(matchesPaidOutcome(action(), proof, null)).toBe(false);
  });
  it('keeps unknown payout and unknown state neutral rather than treating them as zero', () => {
    const proof = accepted(); proof.current.payout_remaining_minor = null; proof.current.status = 'future_status';
    expect(validPaidOutcome(proof)).toBe(true);
    expect(matchesPaidOutcome(action(), proof, null)).toBe(false);
  });
});
it('rejects control-whitespace and too-short Unicode reasons that cannot roundtrip as the same Python-normalized command', () => {
  for (const reason of ['\u0085Reason\u0085', 'Reason\u001c', '😀😀']) expect(validPaidBody({ ...action().body, reason }, true)).toBe(false);
  expect(validPaidBody({ ...action().body, reason: '😀😀😀' }, true)).toBe(true);
});
it('refuses inconsistent effective money in a purported recovery proof', () => {
  const proof = accepted(); proof.current.actual_refunded_minor = 20000;
  expect(matchesPaidOutcome(action(), proof, null)).toBe(false);
});

it.each(['operation', 'money_received'] as const)('requires primitive body enum %s without coercing canonical JSON arrays or objects', field => {
  const text = field === 'operation' ? 'decrease' : 'received';
  for (const value of [[text], { toString: text }, 1, true, null]) {
    const body = { ...action().body, [field]: value };
    expect(() => validPaidBody(body, true)).not.toThrow();
    expect(validPaidBody(body, true)).toBe(false);
    expect(validPaidAction({ ...action(), body })).toBe(false);
  }
});
it('requires primitive acceptance status and history operation while preserving readable future history strings', () => {
  const proof = accepted();
  for (const value of [['pending_settlement'], { toString: 'pending_settlement' }, 1, true, null]) {
    expect(() => validPaidAcceptance({ ...proof.accepted, status: value })).not.toThrow();
    expect(validPaidAcceptance({ ...proof.accepted, status: value })).toBe(false);
    expect(validPaidOutcome({ ...proof, accepted: { ...proof.accepted, status: value } })).toBe(false);
  }
  for (const value of [['decrease'], { toString: 'decrease' }, 1, true, null]) {
    const row = { ...proof.current.history[0], operation: value };
    expect(() => validPaidHistory(row)).not.toThrow(); expect(validPaidHistory(row)).toBe(false);
    expect(validPaidOutcome({ ...proof, current: { ...proof.current, history: [row] } })).toBe(false);
  }
  expect(validPaidHistory({ ...proof.current.history[0], status: 'future_readable_state' })).toBe(true);
});
