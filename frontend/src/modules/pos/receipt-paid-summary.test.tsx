import { renderToStaticMarkup } from 'react-dom/server';
import { expect, it, vi } from 'vitest';
import type { ReceiptHistoryDTO } from '@/lib/erp-api';
import { id } from '@/test-fixtures/paid-bill';
import { ReceiptDetail } from './OrdersAndShiftsScreen';
const original = { protocol: 1, original_total_minor: 10500, actual_refunded_minor: 0, effective_total_minor: 10500, proposed_total_minor: null, status: 'original', refund_request_id: null, physical_completed_pending_accounting_minor: 0, payout_remaining_minor: 0, stock_effect: false };
function receipt(metadata: unknown): ReceiptHistoryDTO { return {
  order_id: id(5), company_id: id(1), branch_id: id(3), terminal_id: id(4), shift_id: id(6),
  shift_opened_by: id(2), shift_opened_by_name: 'Owner', shift_opened_at: '2026-11-13T00:00:00Z', shift_closed_at: null,
  opened_by: id(2), opened_by_name: 'Owner', invoice_no: 'QA-1', invoice_issued_at: '2026-11-13T00:00:00Z', fiscal_year: '26-27',
  created_at: '2026-11-13T00:00:00Z', order_type: 'takeaway', status: 'paid',
  total_minor: 10500, subtotal_minor: 10000, tax_minor: 500, cgst_minor: 250, sgst_minor: 250, igst_minor: 0, cess_minor: 0,
  discount_minor: 0, manual_discount_minor: 0, points_redeemed_minor: 0, round_off_minor: 0, tip_minor: 0,
  paid_minor: 10500, refunded_minor: 0, refundable_minor: 10500, pending_refund_minor: 0, net_collected_minor: 10500,
  checkout_version: 1, table_id: null, customer_name: null, customer_phone: null, customer_gstin: null,
  customer_address: null, customer_state_code: null, place_of_supply_state_code: null, is_reverse_charge: false,
  irn: null, irn_ack_no: null, irn_acknowledged_at: null, e_invoice_qr: null, notes: null,
  opened_at: '2026-11-13T00:00:00Z', held_at: null, closed_at: '2026-11-13T00:00:00Z', updated_at: '2026-11-13T00:00:00Z',
  lines: [], payments: [], refunds: [], gaming_sessions: [], paid_bill_amendment: metadata as ReceiptHistoryDTO['paid_bill_amendment'],
} as ReceiptHistoryDTO; }
function markup(metadata: unknown, permitted = true, refundable = 10500) { return renderToStaticMarkup(<ReceiptDetail receipt={{ ...receipt(metadata), refundable_minor: refundable }} refreshing={false} onCopy={vi.fn()} onRefund={permitted ? vi.fn() : undefined}/>); }
it.each([undefined, null, original])('preserves legacy refund entry for absent metadata or valid original state: %j', metadata => {
  expect(markup(metadata)).toContain('Refund this receipt');
});
it.each([{}, [], false, 0, '', { protocol: 1, status: 'original' }, { ...original, status: ['original'] }, { ...original, payout_remaining_minor: '0' }])('does not enable legacy creation or silently omit malformed present metadata: %j', metadata => {
  const html = markup(metadata); expect(html).not.toContain('Refund this receipt'); expect(html).toContain('Bill-change details could not be verified');
});
it.each(['pending_settlement', 'settled', 'withdrawn', 'discarded', 'future_state'])('does not route amendment state %s into legacy creation', status => {
  expect(markup({ ...original, status })).not.toContain('Refund this receipt');
});
it('preserves permission/balance checks and treats future protocol as readable but unsupported', () => {
  expect(markup(original, false)).not.toContain('Refund this receipt'); expect(markup(original, true, 0)).not.toContain('Refund this receipt');
  const future = markup({ ...original, protocol: 2 }); expect(future).not.toContain('Refund this receipt'); expect(future).toContain('needs Owner review');
});
