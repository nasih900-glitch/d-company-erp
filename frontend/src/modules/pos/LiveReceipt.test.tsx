import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';

import type { OrderDTO } from '@/lib/erp-api';
import LiveReceipt from './LiveReceipt';
import type { ReceiptBusinessDetails } from './receipt-business';
import type { SplitPaymentReceiptLeg } from './split-payment-receipt';

const business: ReceiptBusinessDetails = {
  brandName: 'D Company',
  supplierName: 'D Company Private Limited',
  branchName: 'Nilambur',
  address: 'Nilambur, Kerala',
  gstin: null,
  gstRegistrationType: 'unregistered',
  isComposition: false,
  fssaiLicenseNo: null,
  tradeLicenseNo: null,
  stateCode: '32',
  cashierName: 'Nasih',
  timezone: 'Asia/Kolkata',
  upiVpa: null,
};

function order(overrides: Partial<OrderDTO> = {}): OrderDTO {
  return {
    id: 'order-1',
    invoice_no: 'INV-1',
    fiscal_year: '2026-27',
    status: 'paid',
    type: 'takeaway',
    table_id: null,
    source_label: 'Direct POS',
    subtotal_minor: 4_200,
    discount_minor: 0,
    manual_discount_minor: 0,
    points_redeemed_minor: 0,
    points_redeemed: 0,
    cgst_minor: 0,
    sgst_minor: 0,
    igst_minor: 0,
    cess_minor: 0,
    tax_minor: 0,
    round_off_minor: 0,
    tip_minor: 0,
    total_minor: 4_200,
    paid_minor: 4_200,
    due_minor: 0,
    free_gaming_minutes_applied: 0,
    free_hookah_count_applied: 0,
    delivery_via: null,
    place_of_supply_state_code: '32',
    customer_name: null,
    customer_phone: null,
    customer_gstin: null,
    customer_state_code: null,
    opened_at: '2026-09-21T11:55:00Z',
    closed_at: '2026-09-21T12:00:00Z',
    invoice_issued_at: '2026-09-21T12:00:00Z',
    held_at: null,
    checkout_version: 2,
    lines: [{
      menu_item_id: 'item-1',
      variant_id: null,
      modifiers: null,
      name: 'Gaming',
      sku: 'GAME-1',
      hsn_or_sac: '9996',
      qty: 1,
      unit_price_minor: 4_200,
      line_total_minor: 4_200,
      taxable_value_minor: 4_200,
      tax_rate: 0,
      cgst_minor: 0,
      sgst_minor: 0,
      igst_minor: 0,
      note: null,
    }],
    ...overrides,
  };
}

const splitPayments: SplitPaymentReceiptLeg[] = [
  {
    id: 'payment-cash',
    method: 'cash',
    amount_minor: 1_700,
    tendered_minor: 2_000,
    change_minor: 300,
    ref_external: null,
    paid_at: '2026-09-21T12:00:00Z',
  },
  {
    id: 'payment-upi',
    method: 'upi',
    amount_minor: 2_500,
    tendered_minor: null,
    change_minor: null,
    ref_external: 'upi-ref-1',
    paid_at: '2026-09-21T12:00:00Z',
  },
];

describe('LiveReceipt payment details', () => {
  it('prints each confirmed split rail plus cash tender and change', () => {
    const markup = renderToStaticMarkup(
      <LiveReceipt order={order()} business={business} splitPayments={splitPayments}/>,
    );

    expect(markup).toContain('aria-label="Payment split details"');
    expect(markup).toContain('PAYMENT SPLIT');
    expect(markup).toContain('Cash');
    expect(markup).toContain('₹17.00');
    expect(markup).toContain('UPI');
    expect(markup).toContain('₹25.00');
    expect(markup).toContain('Cash tendered');
    expect(markup).toContain('₹20.00');
    expect(markup).toContain('Change given');
    expect(markup).toContain('₹3.00');
    expect(markup).toContain('upi-ref-1');
  });

  it.each([
    ['single payment', order()],
    ['zero due', order({ subtotal_minor: 0, total_minor: 0, paid_minor: 0, lines: [] })],
  ])('keeps the existing %s receipt layout when no split bundle is supplied', (_label, receiptOrder) => {
    const markup = renderToStaticMarkup(
      <LiveReceipt order={receiptOrder} business={business}/>,
    );

    expect(markup).not.toContain('PAYMENT SPLIT');
    expect(markup).not.toContain('Cash tendered');
    expect(markup).not.toContain('Change given');
  });
});
