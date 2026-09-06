import { describe, expect, it } from 'vitest';
import {
  DEFAULT_EXPENSE_PAYMENT_RAIL,
  EXPENSE_CASH_DRAWER_GUIDANCE,
  EXPENSE_PAYMENT_OPTIONS,
} from './expense-payment-policy';

describe('ordinary expense payment policy', () => {
  it('defaults new expenses to UPI and never offers till cash', () => {
    expect(DEFAULT_EXPENSE_PAYMENT_RAIL).toBe('upi');
    expect(EXPENSE_PAYMENT_OPTIONS.map(({ value }) => value)).toEqual([
      'upi',
      'card',
      'bank',
    ]);
    expect(EXPENSE_PAYMENT_OPTIONS.map(({ value }) => value)).not.toContain('cash');
  });

  it('explains how cash paid-outs will be handled without hiding the limitation', () => {
    expect(EXPENSE_CASH_DRAWER_GUIDANCE).toContain('not linked to the open shift drawer');
    expect(EXPENSE_CASH_DRAWER_GUIDANCE).toContain('future shift-linked drawer workflow');
  });
});
