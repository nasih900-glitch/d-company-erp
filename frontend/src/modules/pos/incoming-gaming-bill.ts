import type { OrderListItemDTO } from '@/lib/erp-api';
import type { GamingPosNavigationIntent } from '@/modules/gaming/gaming-pos-navigation';

export type IncomingGamingBillDecision =
  | { kind: 'wait' }
  | { kind: 'wrong_terminal' }
  | { kind: 'wrong_shift' }
  | { kind: 'occupied' }
  | { kind: 'missing' }
  | { kind: 'open'; order: OrderListItemDTO };

export function incomingGamingBillDecision(input: {
  intent: GamingPosNavigationIntent;
  draftHydrated: boolean;
  draftKey: string | null;
  loadedScope: string | null;
  queueLoading: boolean;
  terminalId: string | null;
  shiftId: string | null;
  hasOccupiedWork: boolean;
  orders: readonly OrderListItemDTO[];
}): IncomingGamingBillDecision {
  if (!input.draftHydrated || input.queueLoading || input.loadedScope !== input.draftKey) {
    return { kind: 'wait' };
  }
  if (input.terminalId !== input.intent.terminalId) return { kind: 'wrong_terminal' };
  if (input.shiftId !== input.intent.shiftId) return { kind: 'wrong_shift' };
  if (input.hasOccupiedWork) return { kind: 'occupied' };
  const order = input.orders.find((candidate) => candidate.id === input.intent.orderId);
  return order ? { kind: 'open', order } : { kind: 'missing' };
}

export function matchesHeldOrderSearch(order: OrderListItemDTO, rawQuery: string): boolean {
  const query = rawQuery.trim().toLowerCase();
  if (!query) return true;
  return [order.id, order.source_label, order.customer_name, order.invoice_no]
    .some((value) => value?.toLowerCase().includes(query));
}
