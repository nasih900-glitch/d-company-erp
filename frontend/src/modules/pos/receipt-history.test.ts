import { describe, expect, it, vi } from 'vitest';

import type {
  ReceiptHistoryDTO,
  ReceiptHistoryPageDTO,
  ReceiptLineHistoryDTO,
} from '@/lib/erp-api';
import {
  exactQuantityLabel,
  orderTypeLabel,
  paymentMethodLabel,
  receiptLineCustomizationLabels,
  receiptPaymentActorLabel,
  receiptSourceLabel,
  sessionDurationLabel,
  mergeReceiptWindow,
  refreshKnownReceipts,
  collectReceiptRefreshWindow,
  receiptScopeMatches,
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

  it('merges a refreshed newest page without dropping or duplicating loaded history', () => {
    const receipt = (orderId: string, invoiceNo: string) => ({
      order_id: orderId,
      invoice_no: invoiceNo,
    }) as ReceiptHistoryDTO;
    const olderWindow = [receipt('order-2', 'INV-2'), receipt('order-1', 'INV-1')];
    const refreshedFirstPage = [receipt('order-3', 'INV-3'), receipt('order-2', 'INV-2-updated')];

    expect(mergeReceiptWindow(olderWindow, refreshedFirstPage).map((row) => row.invoice_no))
      .toEqual(['INV-3', 'INV-2-updated', 'INV-1']);
    expect(refreshKnownReceipts(olderWindow, refreshedFirstPage).map((row) => row.invoice_no))
      .toEqual(['INV-2-updated', 'INV-1']);
  });

  it('rejects responses and cursors owned by an older receipt query scope', () => {
    const current = { epoch: 4, query: 'INV-NEW', loaded: false };
    expect(receiptScopeMatches(current, { epoch: 3, query: 'INV-OLD' })).toBe(false);
    expect(receiptScopeMatches(current, { epoch: 4, query: 'INV-OLD' })).toBe(false);
    expect(receiptScopeMatches(current, { epoch: 4, query: 'INV-NEW' })).toBe(true);
  });

  it('page-walks the retained window so an older refunded receipt updates in place', async () => {
    const receipt = (orderId: string, invoiceNo: string, refundedMinor = 0) => ({
      order_id: orderId,
      invoice_no: invoiceNo,
      refunded_minor: refundedMinor,
    }) as ReceiptHistoryDTO;
    const current = Array.from({ length: 51 }, (_, index) => (
      receipt(`order-${index + 1}`, `INV-${index + 1}`)
    ));
    const firstPage: ReceiptHistoryPageDTO = {
      items: [
        receipt('order-new', 'INV-NEW'),
        ...current.slice(0, 49),
      ],
      next_cursor: 'page-2',
      has_more: true,
    };
    const loadPage = vi.fn(async (cursor: string): Promise<ReceiptHistoryPageDTO> => {
      expect(cursor).toBe('page-2');
      return {
        items: [
          receipt('order-50', 'INV-50'),
          receipt('order-51', 'INV-51', 1_200),
        ],
        next_cursor: null,
        has_more: false,
      };
    });

    const refreshedWindow = await collectReceiptRefreshWindow(firstPage, current, loadPage);
    expect(refreshedWindow).not.toBeNull();
    expect(refreshedWindow?.complete).toBe(true);
    expect(refreshedWindow?.unseenPrefix.map((row) => row.order_id)).toEqual(['order-new']);
    const refreshedRows = refreshKnownReceipts(current, refreshedWindow!.knownUpdates);
    expect(loadPage).toHaveBeenCalledTimes(1);
    expect(refreshedRows).toHaveLength(51);
    expect(refreshedRows.map((row) => row.order_id)).toEqual(current.map((row) => row.order_id));
    expect(refreshedRows.at(-1)?.refunded_minor).toBe(1_200);
  });

  it('stages all 60 new receipts before a retained 100-row window without creating a cursor gap', async () => {
    const receipt = (orderId: string, refundedMinor = 0) => ({
      order_id: orderId,
      invoice_no: `INV-${orderId}`,
      refunded_minor: refundedMinor,
    }) as ReceiptHistoryDTO;
    const current = Array.from({ length: 100 }, (_, index) => receipt(`old-${index + 1}`));
    const newRows = Array.from({ length: 60 }, (_, index) => receipt(`new-${index + 1}`));
    const pages: ReceiptHistoryPageDTO[] = [
      { items: newRows.slice(0, 50), next_cursor: 'page-2', has_more: true },
      {
        items: [...newRows.slice(50), ...current.slice(0, 40)],
        next_cursor: 'page-3',
        has_more: true,
      },
      {
        items: current.slice(40, 90),
        next_cursor: 'page-4',
        has_more: true,
      },
      {
        items: [
          ...current.slice(90, 99),
          receipt('old-100', 2_400),
          receipt('older-unrequested'),
        ],
        next_cursor: 'older-tail',
        has_more: true,
      },
    ];
    const loadPage = vi.fn(async (cursor: string) => {
      const pageNumber = Number(cursor.replace('page-', ''));
      return pages[pageNumber - 1];
    });

    const refresh = await collectReceiptRefreshWindow(pages[0], current, loadPage);
    expect(refresh?.complete).toBe(true);
    expect(refresh?.missingKnownIds).toEqual([]);
    expect(refresh?.unseenPrefix.map((row) => row.order_id))
      .toEqual(newRows.map((row) => row.order_id));
    expect(refresh?.unseenPrefix.some((row) => row.order_id === 'older-unrequested')).toBe(false);
    const updatedCurrent = refreshKnownReceipts(current, refresh!.knownUpdates);
    expect(updatedCurrent.at(-1)?.refunded_minor).toBe(2_400);
    const shown = mergeReceiptWindow(updatedCurrent, refresh!.unseenPrefix);
    expect(shown).toHaveLength(160);
    expect(shown.slice(0, 60).map((row) => row.order_id))
      .toEqual(newRows.map((row) => row.order_id));
    expect(shown.slice(60).map((row) => row.order_id))
      .toEqual(current.map((row) => row.order_id));
  });

  it('reports an incomplete refresh when the bounded walk cannot cover the retained snapshot', async () => {
    const receipt = (index: number) => ({
      order_id: `order-${index}`,
      invoice_no: `INV-${index}`,
    }) as ReceiptHistoryDTO;
    const current = Array.from({ length: 1_001 }, (_, index) => receipt(index + 1));
    const firstPage: ReceiptHistoryPageDTO = {
      items: current.slice(0, 50),
      next_cursor: '50',
      has_more: true,
    };
    const loadPage = vi.fn(async (cursor: string): Promise<ReceiptHistoryPageDTO> => {
      const start = Number(cursor);
      const end = start + 50;
      return {
        items: current.slice(start, end),
        next_cursor: String(end),
        has_more: true,
      };
    });

    const refresh = await collectReceiptRefreshWindow(firstPage, current, loadPage);
    expect(loadPage).toHaveBeenCalledTimes(19);
    expect(refresh?.complete).toBe(false);
    expect(refresh?.missingKnownIds).toEqual(['order-1001']);
    expect(refresh?.knownUpdates).toHaveLength(1_000);
    // Callers must keep the old window intact when complete is false.
    const applied = refresh?.complete
      ? refreshKnownReceipts(current, refresh.knownUpdates)
      : current;
    expect(applied).toBe(current);
  });
});
