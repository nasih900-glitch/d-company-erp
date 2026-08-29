import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';

import type { PosRefundRequestDTO } from '@/lib/erp-api';
import { RefundTaskControls } from './RefundTaskControls';
import {
  allowedRefundActions,
  canAccessRefunds,
  refundRailPolicy,
  refundStatusPresentation,
  type RefundActionContext,
} from './refund-policy';

const task: PosRefundRequestDTO = {
  id: 'refund-1',
  order_id: 'order-1',
  shift_id: 'shift-1',
  branch_id: 'branch-1',
  terminal_id: 'terminal-1',
  amount_minor: 2_500,
  reason_code: 'customer_unhappy',
  mode: 'cash',
  settlement_method: 'cash',
  status: 'accepted_cash_due',
  accepted_at: '2026-08-28T10:00:00Z',
  accepted_by: 'manager-1',
  accepted_by_name: 'Manager',
  handoff_started_at: null,
  handoff_started_by: null,
  handoff_started_by_name: null,
  cash_handed_over_at: null,
  cash_handed_over_recorded_at: null,
  cash_handed_over_by: null,
  cash_handed_over_by_name: null,
  provider_payout_started_at: null,
  provider_payout_started_by: null,
  provider_payout_started_by_name: null,
  provider_completed_at: null,
  provider_completion_recorded_at: null,
  provider_completed_by: null,
  provider_completed_by_name: null,
  settled_at: null,
  settled_by: null,
  settled_by_name: null,
  client_occurred_at: null,
  captured_time_reconciled: null,
  provider_evidence_reconciled: null,
  withdrawn_at: null,
  withdrawn_by: null,
  withdrawn_by_name: null,
  provider_verification_status: null,
  provider_verification_reference: null,
  provider_verified_at: null,
  external_reference: null,
  receipt_no: null,
  refund_id: null,
  client_action_id: 'refund-action-1',
  customer_spend_reconciled: null,
  loyalty_reconciliation_state: null,
  note: null,
};

const context: RefundActionContext = {
  userId: 'manager-1',
  protectedAccess: false,
  adminSystemAccess: false,
  currentShiftId: 'shift-1',
  canManageCurrentShift: true,
  online: true,
};

describe('web refund permission and state policy', () => {
  it('uses exact pos.refund and keeps read-only POS users out', () => {
    expect(canAccessRefunds({ effective_permissions: ['pos.read', 'pos.refund'] })).toBe(true);
    expect(canAccessRefunds({ effective_permissions: ['pos.read'], protected_access: true })).toBe(false);
    expect(canAccessRefunds({ roles: ['manager'] })).toBe(true);
    expect(canAccessRefunds({ roles: ['cashier'] })).toBe(false);
  });

  it('never guesses an original payout rail and forces mixed payments to cash', () => {
    expect(refundRailPolicy([]).requestReady).toBe(false);
    expect(refundRailPolicy(['legacy-bank']).requestReady).toBe(false);
    expect(refundRailPolicy(['upi'])).toMatchObject({
      kind: 'single_provider', defaultMode: 'original', requestReady: true,
    });
    expect(refundRailPolicy(['cash', 'upi'])).toMatchObject({
      kind: 'mixed', defaultMode: 'cash', requestReady: true,
    });
  });

  it('requires exact shift ownership and blocks every money action offline', () => {
    expect(allowedRefundActions(task, { ...context, online: false })).toEqual([]);
    expect(allowedRefundActions(task, { ...context, currentShiftId: 'shift-2' })).toEqual([]);
    expect(allowedRefundActions(task, { ...context, canManageCurrentShift: false })).toEqual([]);
    expect(allowedRefundActions(task, { ...context, outcomeUncertain: true })).toEqual([]);
  });

  it('keeps provider failure resolution admin.system-only', () => {
    const providerTask = {
      ...task,
      settlement_method: 'upi' as const,
      status: 'provider_payout_in_progress' as const,
      provider_payout_started_by: 'manager-1',
    };
    expect(allowedRefundActions(providerTask, {
      ...context, protectedAccess: true, adminSystemAccess: false,
    })).toEqual(['settle_provider']);
    expect(allowedRefundActions(providerTask, {
      ...context, protectedAccess: true, adminSystemAccess: true,
    })).toEqual(['settle_provider', 'resolve_provider']);
  });

  it('labels post-money accounting states with an explicit do-not-pay-again warning', () => {
    const presentation = refundStatusPresentation('cash_handed_over_pending_accounting');
    expect(presentation.moneyMoved).toBe(true);
    expect(presentation.detail).toContain('Do not pay again');
  });

  it('renders only policy-approved controls', () => {
    const denied = renderToStaticMarkup(
      <RefundTaskControls
        actions={[]}
        busy={false}
        disabledReason="Reconnect before moving money."
        onAction={vi.fn()}
      />,
    );
    expect(denied).toContain('Reconnect before moving money.');
    expect(denied).not.toContain('<button');

    const approved = renderToStaticMarkup(
      <RefundTaskControls
        actions={['settle_provider']}
        busy={false}
        onAction={vi.fn()}
      />,
    );
    expect(approved).toContain('Record provider completion');
    expect(approved).not.toContain('Resolve failed provider payout');
  });
});
