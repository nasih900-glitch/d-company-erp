import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';

import type { OrderListItemDTO } from '@/lib/erp-api';
import {
  OperationalOrderList,
  RecoverDirectOrderModal,
} from './OrdersAndShiftsScreen';

function order(overrides: Partial<OrderListItemDTO> = {}): OrderListItemDTO {
  return {
    id: '12345678-abandoned-order',
    invoice_no: null,
    type: 'takeaway',
    status: 'open',
    table_id: null,
    source_label: 'Direct POS',
    total_minor: 4_200,
    items_count: 2,
    customer_name: null,
    created_at: '2026-09-03T19:00:00Z',
    held_at: null,
    checkout_version: 7,
    paid_minor: 0,
    refundable_minor: 0,
    pending_refund_minor: 0,
    payment_methods: [],
    ...overrides,
  };
}

function listMarkup(
  row: OrderListItemDTO,
  protectedAccess: boolean,
): string {
  return renderToStaticMarkup(
    <OperationalOrderList
      rows={[row]}
      protectedAccess={protectedAccess}
      onView={vi.fn()}
      onRecover={vi.fn()}
    />,
  );
}

describe('Orders & Shifts abandoned direct-bill recovery', () => {
  it('shows recovery only to protected owners for eligible direct open rows', () => {
    const protectedOwner = listMarkup(order(), true);
    expect(protectedOwner).toContain('Recover to POS');
    expect(protectedOwner).toContain('aria-label="Recover order 12345678 to POS"');

    expect(listMarkup(order(), false)).not.toContain('Recover to POS');
    expect(listMarkup(order({ status: 'held' }), true)).not.toContain('Recover to POS');
    expect(listMarkup(order({ table_id: 'table-1' }), true)).not.toContain('Recover to POS');
    expect(listMarkup(order({ type: 'session' }), true)).not.toContain('Recover to POS');
    expect(listMarkup(order({ items_count: 0 }), true)).not.toContain('Recover to POS');
    expect(listMarkup(order({ invoice_no: 'MN-1' }), true)).not.toContain('Recover to POS');
    expect(listMarkup(order({ paid_minor: 100 }), true)).not.toContain('Recover to POS');
  });

  it('requires both an audited reason and explicit abandoned-device acknowledgement', () => {
    const markup = renderToStaticMarkup(
      <RecoverDirectOrderModal
        order={order()}
        onClose={vi.fn()}
        onRefresh={vi.fn()}
        onSubmit={vi.fn()}
      />,
    );

    expect(markup).toContain('Verify the original checkout is abandoned');
    expect(markup).toContain('Recovery does not collect payment');
    expect(markup).toContain('original checkout/device is abandoned');
    expect(markup).toContain('no one is collecting');
    expect(markup).toContain('minLength="3"');
    expect(markup).toContain('maxLength="500"');
    expect(markup).toContain('Required for the recovery audit trail');

    const confirmLabel = 'Recover to POS</button>';
    const labelIndex = markup.lastIndexOf(confirmLabel);
    const buttonStart = markup.lastIndexOf('<button', labelIndex);
    expect(labelIndex).toBeGreaterThan(-1);
    expect(markup.slice(buttonStart, markup.indexOf('>', buttonStart)))
      .toContain('disabled=""');
  });
});
