import { describe, expect, it } from 'vitest';
import {
  DEFAULT_EXPENSE_PAYMENT_RAIL,
  EXPENSE_CASH_DRAWER_GUIDANCE,
  EXPENSE_PAYMENT_OPTIONS,
} from './expense-payment-policy';

describe('expense payment policy', () => {
  it('defaults new expenses to UPI and offers explicit shift-linked cash', () => {
    expect(DEFAULT_EXPENSE_PAYMENT_RAIL).toBe('upi');
    expect(EXPENSE_PAYMENT_OPTIONS.map(({ value }) => value)).toEqual([
      'cash',
      'upi',
      'card',
      'bank',
    ]);
  });

  it('explains the drawer movement and the safe void rule', () => {
    expect(EXPENSE_CASH_DRAWER_GUIDANCE).toContain('linked to the open shift drawer');
    expect(EXPENSE_CASH_DRAWER_GUIDANCE).toContain('same shift is still open');
  });
});
