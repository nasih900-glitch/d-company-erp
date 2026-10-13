import { describe, expect, it } from 'vitest';

import type { OrderListItemDTO } from '@/lib/erp-api';
import {
  incomingGamingBillDecision, matchesHeldOrderSearch,
} from './incoming-gaming-bill';

const order: OrderListItemDTO = {
  id: 'canonical-order-123', invoice_no: null, type: 'session', status: 'held',
  table_id: null, source_label: 'PS5 1', total_minor: 10_000, items_count: 1,
  customer_name: null, created_at: '2026-10-13T00:00:00Z', held_at: null,
  checkout_version: 1, paid_minor: 0, refundable_minor: 0, pending_refund_minor: 0,
  payment_methods: [],
};
const intent = {
  orderId: order.id, sessionId: 'session-1', shiftId: 'shift-1', terminalId: 'terminal-1',
};
const ready = {
  intent, draftHydrated: true, draftKey: 'scope-1', loadedScope: 'scope-1',
  queueLoading: false, terminalId: 'terminal-1', shiftId: 'shift-1',
  hasOccupiedWork: false, orders: [order],
};

describe('incoming Gaming bill lifecycle', () => {
  it('retains the intent while the first queue response is deferred or the scope changes', () => {
    expect(incomingGamingBillDecision({ ...ready, queueLoading: true }).kind).toBe('wait');
    expect(incomingGamingBillDecision({ ...ready, loadedScope: 'previous-scope' }).kind).toBe('wait');
  });

  it('preserves an occupied cart or payment recovery instead of replacing it', () => {
    expect(incomingGamingBillDecision({ ...ready, hasOccupiedWork: true }).kind).toBe('occupied');
  });

  it('opens only the exact bill in the matching shift and terminal', () => {
    expect(incomingGamingBillDecision(ready)).toEqual({ kind: 'open', order });
    expect(incomingGamingBillDecision({ ...ready, terminalId: 'terminal-2' }).kind)
      .toBe('wrong_terminal');
    expect(incomingGamingBillDecision({ ...ready, shiftId: 'shift-2' }).kind)
      .toBe('wrong_shift');
  });

  it('finds a canonical UUID directly in the visible queue', () => {
    expect(matchesHeldOrderSearch(order, 'CANONICAL-ORDER-123')).toBe(true);
  });
});
