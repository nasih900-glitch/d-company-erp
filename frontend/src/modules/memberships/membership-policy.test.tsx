import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';

import { MembershipTaskControls } from './MembershipTaskControls';
import {
  canManageMemberships,
  canViewMemberships,
  paymentTaskActions,
  refundTaskActions,
} from './membership-policy';

describe('web membership permission and financial-state policy', () => {
  it('allows pos.read viewing but requires protected membership management for money', () => {
    expect(canViewMemberships({ effective_permissions: ['pos.read'] })).toBe(true);
    expect(canViewMemberships({ effective_permissions: ['kitchen.read'] })).toBe(false);
    expect(canManageMemberships({
      protected_access: true,
      effective_permissions: ['memberships.manage'],
    })).toBe(true);
    expect(canManageMemberships({
      protected_access: false,
      effective_permissions: ['memberships.manage'],
    })).toBe(false);
    expect(canManageMemberships({
      protected_access: true,
      effective_permissions: ['pos.read'],
    })).toBe(false);
  });

  it('shows only transitions valid for the authoritative task state and exact shift', () => {
    const context = { online: true, currentShiftId: 'shift-1', uncertain: false };
    expect(paymentTaskActions({ status: 'accepted_payment_due', shift_id: 'shift-1' }, context))
      .toEqual(['begin', 'withdraw']);
    expect(paymentTaskActions({ status: 'payment_completed_pending_posting', shift_id: 'shift-1' }, context))
      .toEqual(['finalize', 'withdraw']);
    expect(refundTaskActions({ status: 'cash_handoff_in_progress', shift_id: 'shift-1' }, context))
      .toEqual(['complete', 'withdraw']);
    expect(refundTaskActions({ status: 'accepted_provider_due', shift_id: 'shift-2' }, context))
      .toEqual([]);
    expect(paymentTaskActions(
      { status: 'cash_collection_in_progress', shift_id: 'shift-1' },
      { ...context, uncertain: true },
    )).toEqual([]);
  });

  it('renders touch actions with rail-specific language and a visible blocked reason', () => {
    const active = renderToStaticMarkup(createElement(MembershipTaskControls, {
      actions: ['begin', 'withdraw'],
      rail: 'upi',
      kind: 'refund',
      busy: false,
      onAction: () => {},
    }));
    expect(active).toContain('Start UPI refund');
    expect(active).toContain('Resolve without posting');

    const blocked = renderToStaticMarkup(createElement(MembershipTaskControls, {
      actions: [],
      rail: 'cash',
      kind: 'payment',
      busy: false,
      disabledReason: 'Refresh server state first.',
      onAction: () => {},
    }));
    expect(blocked).toContain('Refresh server state first.');
    expect(blocked).not.toContain('<button');
  });
});
