import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';

import type { OrderListItemDTO, ShiftDTO } from '@/lib/erp-api';
import {
  ExistingShiftFeedback,
  OpenShiftStatusPanel,
  OperationalOrderList,
  RecoverDirectOrderModal,
  canUseShiftPermission,
  isUnknownShiftMutationOutcome,
  shiftActionErrorMessage,
  shiftCloserLabel,
  shiftOpenerLabel,
  shiftWorkspaceLabel,
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

function shift(overrides: Partial<ShiftDTO> = {}): ShiftDTO {
  return {
    id: 'shift-12345678',
    branch_id: 'branch-1',
    terminal_id: 'workspace-1',
    status: 'open',
    opened_at: '2026-09-04T09:15:00Z',
    closed_at: null,
    opening_float_minor: 50_000,
    expected_minor: 62_000,
    counted_minor: null,
    variance_minor: null,
    pos_sales_minor: 12_000,
    membership_sales_minor: 0,
    gross_collections_minor: 12_000,
    settled_pos_refunds_minor: 0,
    settled_membership_refunds_minor: 0,
    total_refunds_minor: 0,
    net_collections_minor: 12_000,
    total_sales_minor: 12_000,
    opened_by: 'rafi-user-id',
    opened_by_name: 'Rafi',
    opened_by_email: 'rafi@dcompany.local',
    ...overrides,
  };
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

describe('Orders & Shifts staff-facing shift feedback', () => {
  it('allows another staff member to start the close flow while preserving opener identity', () => {
    const markup = renderToStaticMarkup(
      <OpenShiftStatusPanel
        shift={shift()}
        workspaceName="Combined register"
        currentUserId="sameer-user-id"
        canClose
        onClose={vi.fn()}
      />,
    );

    expect(markup).toContain('Shift is open and ready');
    expect(markup).toContain('Opened by <strong>Rafi</strong>');
    expect(markup).toContain('Combined register');
    expect(markup).toContain('Any staff member with Close shift access can close it');
    expect(markup).toContain('Count &amp; close shift');
    expect(markup).toContain('aria-label="Close shift opened by Rafi"');
    expect(markup).not.toContain('Only the opener');

    const buttonStart = markup.indexOf('<button');
    expect(markup.slice(buttonStart, markup.indexOf('>', buttonStart)))
      .not.toContain('disabled');
  });

  it('disables the close action while its cash-count flow is already opening', () => {
    const markup = renderToStaticMarkup(
      <OpenShiftStatusPanel
        shift={shift()}
        workspaceName="Combined register"
        currentUserId="sameer-user-id"
        canClose
        closing
        onClose={vi.fn()}
      />,
    );

    expect(markup).toContain('Opening cash count…');
    const buttonStart = markup.indexOf('<button');
    expect(markup.slice(buttonStart, markup.indexOf('>', buttonStart)))
      .toContain('disabled=""');
  });

  it('does not expose a close action when the exact server permission is absent', () => {
    expect(canUseShiftPermission({
      roles: ['owner'],
      protected_access: false,
      effective_permissions: ['pos.read'],
    }, 'pos.shift.close')).toBe(false);
    expect(canUseShiftPermission({
      roles: ['staff'],
      protected_access: false,
      effective_permissions: ['pos.read', 'pos.shift.close'],
    }, 'pos.shift.close')).toBe(true);

    const markup = renderToStaticMarkup(
      <OpenShiftStatusPanel
        shift={shift()}
        workspaceName="Combined register"
        currentUserId="readonly-user-id"
        canClose={false}
        onClose={vi.fn()}
      />,
    );

    expect(markup).toContain('can view this shift but cannot close it');
    expect(markup).not.toContain('Count &amp; close shift');
    expect(markup).not.toContain('aria-label="Close shift');
  });

  it('explains who owns an existing shift and tells staff exactly what to do next', () => {
    const markup = renderToStaticMarkup(
      <ExistingShiftFeedback
        shift={shift()}
        workspaceName="Combined register"
        onContinue={vi.fn()}
      />,
    );

    expect(markup).toContain('A shift is already open');
    expect(markup).toContain('<strong>Rafi</strong> opened it');
    expect(markup).toContain('Do not open another shift');
    expect(markup).toContain('Use existing shift');
    expect(markup).toContain('role="status"');
    expect(markup).toContain('aria-live="polite"');
  });

  it('shows stable opener, closer and workspace fallbacks for old and new servers', () => {
    expect(shiftOpenerLabel(shift())).toBe('Rafi');
    expect(shiftCloserLabel(shift({
      status: 'closed',
      closed_by: 'sameer-user-id',
      closed_by_name: 'Sameer',
    }))).toBe('Sameer');
    expect(shiftCloserLabel(shift({ status: 'closed' }))).toBe('Not recorded');
    expect(shiftWorkspaceLabel(shift(), [{ id: 'workspace-1', name: 'Combined register' }]))
      .toBe('Combined register');
    expect(shiftWorkspaceLabel(shift({ terminal_id: 'legacy-workspace-id' }), []))
      .toBe('Workspace legacy-w');
  });

  it('turns close blockers into clear recovery instructions', () => {
    expect(shiftActionErrorMessage(
      new Error('cannot close shift with 2 unfinished order(s)'),
      'close',
    )).toContain('Open Operations, finish or void every open bill');

    expect(shiftActionErrorMessage(
      new Error('cannot close shift with 1 running gaming session(s)'),
      'close',
    )).toContain('Open Gaming, stop each active session');

    expect(shiftActionErrorMessage(
      new Error('cannot close shift with 1 stopped session(s) awaiting POS'),
      'close',
    )).toContain('send every payment-due session to POS');

    const forbidden = new Error('Forbidden') as Error & { status?: number };
    forbidden.status = 403;
    expect(shiftActionErrorMessage(forbidden, 'close'))
      .toContain('Ask the owner to restore the Shift permission');
  });

  it('treats interrupted open and close responses as ambiguous until refresh', () => {
    const networkError = new Error('Network request failed') as Error & { code?: string };
    networkError.code = 'network_error';

    const openMessage = shiftActionErrorMessage(networkError, 'open');
    expect(openMessage).toContain('could not be confirmed');
    expect(openMessage).toContain('If an open shift appears, use it');
    expect(openMessage).not.toContain('was not opened');

    const closeMessage = shiftActionErrorMessage(networkError, 'close');
    expect(closeMessage).toContain('could not be confirmed');
    expect(closeMessage).toContain('retry the same count once only if this shift still shows open');
    expect(closeMessage).not.toContain('was not closed');
    expect(isUnknownShiftMutationOutcome(networkError)).toBe(true);

    const definitiveConflict = new Error('Unfinished orders remain') as Error & { status?: number };
    definitiveConflict.status = 409;
    expect(isUnknownShiftMutationOutcome(definitiveConflict)).toBe(false);

    const gatewayTimeout = new Error('Gateway timeout') as Error & { status?: number };
    gatewayTimeout.status = 504;
    expect(isUnknownShiftMutationOutcome(gatewayTimeout)).toBe(true);
    expect(shiftActionErrorMessage(gatewayTimeout, 'close')).toContain('could not be confirmed');
    expect(shiftActionErrorMessage(gatewayTimeout, 'close')).not.toContain('shift remains open');
  });
});
