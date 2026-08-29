import { describe, expect, it } from 'vitest';

import type { ReceiptLineHistoryDTO } from '@/lib/erp-api';
import {
  exactQuantityLabel,
  orderTypeLabel,
  paymentMethodLabel,
  receiptLineCustomizationLabels,
  receiptPaymentActorLabel,
  receiptSourceLabel,
  sessionDurationLabel,
} from './receipt-history';

const line: ReceiptLineHistoryDTO = {
  id: 'line-1',
  menu_item_id: 'item-1',
  menu_item_name: 'Salted Crisps',
  menu_item_type: 'food',
  variant_id: 'variant-1',
  variant_snapshot: { name: 'Large' },
  modifiers: [
    { name: 'Extra spice', qty: 1 },
    { name: 'Cheese', qty: 2 },
    { name: '', qty: 99 },
  ],
  qty: '2.000',
  unit_price_minor: 5000,
  line_total_minor: 10000,
  discount_minor: 0,
  hsn_or_sac: null,
  tax_rate: '0.00',
  taxable_value_minor: 10000,
  cgst_minor: 0,
  sgst_minor: 0,
  igst_minor: 0,
  cess_minor: 0,
  note: null,
  voided_at: null,
  voided_by: null,
  voided_by_name: null,
  void_reason: null,
  created_at: '2026-08-29T10:00:00Z',
  updated_at: '2026-08-29T10:00:00Z',
};

describe('receipt history presentation', () => {
  it('maps server payment and order values to staff-facing labels', () => {
    expect(paymentMethodLabel('upi')).toBe('UPI');
    expect(paymentMethodLabel('cash')).toBe('Cash');
    expect(orderTypeLabel('dine_in')).toBe('Dine In');
  });

  it('keeps exact decimal quantities while removing display-only zeroes', () => {
    expect(exactQuantityLabel('002.5000')).toBe('2.5');
    expect(exactQuantityLabel('1.000')).toBe('1');
    expect(exactQuantityLabel('not-recorded')).toBe('not-recorded');
  });

  it('renders immutable variant and modifier snapshots defensively', () => {
    expect(receiptLineCustomizationLabels(line)).toEqual([
      'Large',
      'Extra spice',
      '2 × Cheese',
    ]);
  });

  it('uses linked Gaming station provenance ahead of a generic order type', () => {
    expect(receiptSourceLabel([
      { station_name: 'PS5 Station 1' },
      { station_name: 'PS5 Station 1' },
      { station_name: 'VR Pod 1' },
    ], 'session')).toBe('PS5 Station 1, VR Pod 1');
    expect(receiptSourceLabel([], 'takeaway')).toBe('Takeaway');
  });

  it('formats authoritative billable minutes without inventing precision', () => {
    expect(sessionDurationLabel(65)).toBe('1 hr 5 min');
    expect(sessionDurationLabel(60)).toBe('1 hr');
    expect(sessionDurationLabel(null)).toBe('Not recorded');
  });

  it('attributes split payments to every distinct recorded cashier', () => {
    expect(receiptPaymentActorLabel([
      { recorded_by: 'rafi-id', recorded_by_name: 'Rafi' },
      { recorded_by: 'shameer-id', recorded_by_name: 'Shameer' },
      { recorded_by: 'rafi-id', recorded_by_name: 'Rafi' },
    ])).toBe('Rafi + Shameer');
    expect(receiptPaymentActorLabel([
      { recorded_by: null, recorded_by_name: null },
    ])).toBe('Legacy record — not recorded');
  });
});
