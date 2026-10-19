import { describe, expect, it } from 'vitest';
import {
  emptyReceiptFilters,
  receiptSearchParams,
  receiptSearchScope,
  taxPercentage,
} from './receipt-search';
describe('Exact receipt filters and configured tax display', () => {
  it('uses integer minor units and sends all filters to retained server history', () => {
    const scope = receiptSearchScope('00649', {
      ...emptyReceiptFilters,
      date_from: '2026-10-18',
      date_to: '2026-10-18',
      amount: '270.01',
      method: 'upi',
      station_id: 'station-1',
      staff_user_id: 'staff-1',
    });
    expect(receiptSearchParams(scope)).toEqual({
      q: '00649',
      date_from: '2026-10-18',
      date_to: '2026-10-18',
      amount_minor: 27001,
      method: 'upi',
      station_id: 'station-1',
      staff_user_id: 'staff-1',
    });
  });
  it('keeps legacy unfiltered scope and treats ordinary braces as search text', () => {
    expect(
      receiptSearchParams(receiptSearchScope('{bill}', emptyReceiptFilters)),
    ).toEqual({ q: '{bill}' });
    expect(receiptSearchScope(' 00649 ', emptyReceiptFilters)).toBe('00649');
  });
  it('refuses reversed dates, excess decimals and unsupported payment methods', () => {
    for (const patch of [
      { date_from: '2026-10-19', date_to: '2026-10-18' },
      { amount: '12.345' },
      { method: 'invented' },
    ])
      expect(() =>
        receiptSearchScope('', { ...emptyReceiptFilters, ...patch }),
      ).toThrow();
  });
  it('formats fractional rates exactly without changing quantities or charged money', () => {
    for (const [rate, label] of [
      ['0.18', '18%'],
      ['0.05', '5%'],
      ['0.1800', '18%'],
      ['0.005', '0.5%'],
      ['0', '0%'],
      ['1', '100%'],
      ['invalid', 'Rate unavailable'],
    ])
      expect(taxPercentage(rate)).toBe(label);
  });
});
