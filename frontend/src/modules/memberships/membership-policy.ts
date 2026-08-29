import type {
  MembershipPaymentStatus,
  MembershipPaymentTaskDTO,
  MembershipRefundDTO,
  MembershipRefundStatus,
} from '@/lib/erp-api';

export interface MembershipIdentity {
  roles?: string[];
  protected_access?: boolean | null;
  effective_permissions?: string[];
}

const LEGACY_POS_READ_ROLES = new Set([
  'super_owner',
  'co_owner',
  'owner',
  'partner',
  'manager',
  'cashier',
  'gaming_supervisor',
  'auditor',
  'staff',
]);

/** Match the backend's pos.read gate, while failing closed for unknown old roles. */
export function canViewMemberships(identity: MembershipIdentity | null | undefined): boolean {
  if (!identity) return false;
  if (identity.effective_permissions !== undefined) {
    return identity.effective_permissions.includes('pos.read');
  }
  if (identity.protected_access) return true;
  return identity.roles?.some((role) => LEGACY_POS_READ_ROLES.has(role)) ?? false;
}

/** Membership money routes require both memberships.manage and protected_access. */
export function canManageMemberships(identity: MembershipIdentity | null | undefined): boolean {
  if (!identity?.protected_access) return false;
  if (identity.effective_permissions !== undefined) {
    return identity.effective_permissions.includes('memberships.manage');
  }
  return true;
}

export type MembershipMoneyAction = 'begin' | 'complete' | 'finalize' | 'withdraw';

export function paymentTaskActions(
  task: Pick<MembershipPaymentTaskDTO, 'status' | 'shift_id'>,
  context: { online: boolean; currentShiftId: string | null; uncertain: boolean },
): MembershipMoneyAction[] {
  if (!context.online || context.uncertain || context.currentShiftId !== task.shift_id) return [];
  switch (task.status) {
    case 'accepted_payment_due': return ['begin', 'withdraw'];
    case 'cash_collection_in_progress':
    case 'provider_action_in_progress': return ['complete', 'withdraw'];
    case 'payment_completed_pending_posting': return ['finalize', 'withdraw'];
    case 'settled':
    case 'withdrawn': return [];
  }
}

export function refundTaskActions(
  task: Pick<MembershipRefundDTO, 'status' | 'shift_id'>,
  context: { online: boolean; currentShiftId: string | null; uncertain: boolean },
): MembershipMoneyAction[] {
  if (!context.online || context.uncertain || context.currentShiftId !== task.shift_id) return [];
  switch (task.status) {
    case 'accepted_cash_due':
    case 'accepted_provider_due': return ['begin', 'withdraw'];
    case 'cash_handoff_in_progress':
    case 'provider_action_in_progress': return ['complete', 'withdraw'];
    case 'payout_completed_pending_posting': return ['finalize', 'withdraw'];
    case 'settled':
    case 'withdrawn': return [];
  }
}

export const PAYMENT_STATUS: Record<MembershipPaymentStatus, {
  label: string;
  detail: string;
  tone: 'warning' | 'danger' | 'info' | 'success' | 'neutral';
}> = {
  accepted_payment_due: {
    label: 'Payment prepared',
    detail: 'No money is authorised to move until collection is started from this task.',
    tone: 'warning',
  },
  cash_collection_in_progress: {
    label: 'Cash collection active',
    detail: 'Collect once, then record the result here. Do not start another payment.',
    tone: 'danger',
  },
  provider_action_in_progress: {
    label: 'Provider payment active',
    detail: 'Complete Card or UPI once and retain its provider reference.',
    tone: 'danger',
  },
  payment_completed_pending_posting: {
    label: 'Receipt posting pending',
    detail: 'Payment evidence is already saved. Do not collect again; finish accounting only.',
    tone: 'info',
  },
  settled: {
    label: 'Settled',
    detail: 'The membership and receipt are posted.',
    tone: 'success',
  },
  withdrawn: {
    label: 'Withdrawn',
    detail: 'The unfinished collection was resolved without an outstanding payment.',
    tone: 'neutral',
  },
};

export const REFUND_STATUS: Record<MembershipRefundStatus, {
  label: string;
  detail: string;
  tone: 'warning' | 'danger' | 'info' | 'success' | 'neutral';
}> = {
  accepted_cash_due: {
    label: 'Cash refund reserved',
    detail: 'Benefits are held, but no cash is authorised to leave until handover is started.',
    tone: 'warning',
  },
  accepted_provider_due: {
    label: 'Provider refund reserved',
    detail: 'Benefits are held, but no Card or UPI refund is authorised until it is started here.',
    tone: 'warning',
  },
  cash_handoff_in_progress: {
    label: 'Cash handover active',
    detail: 'Hand over cash once, then record the result. Do not repeat the payout.',
    tone: 'danger',
  },
  provider_action_in_progress: {
    label: 'Provider refund active',
    detail: 'Complete the provider refund once and retain its reference.',
    tone: 'danger',
  },
  payout_completed_pending_posting: {
    label: 'Refund posting pending',
    detail: 'Payout evidence is already saved. Do not pay again; finish accounting only.',
    tone: 'info',
  },
  settled: {
    label: 'Refund settled',
    detail: 'The audited refund receipt and customer spend reversal are posted.',
    tone: 'success',
  },
  withdrawn: {
    label: 'Refund withdrawn',
    detail: 'The unfinished payout was resolved without an outstanding refund.',
    tone: 'neutral',
  },
};

export function isDefiniteMembershipRejection(error: unknown): boolean {
  const status = (error as { status?: number } | null)?.status;
  return status !== undefined && status >= 400 && status < 500;
}

export function makeMembershipActionId(kind: string): string {
  const uuid = globalThis.crypto?.randomUUID?.();
  const suffix = uuid ?? `${Date.now()}-${Math.random().toString(16).slice(2)}`;
  return `membership-${kind}:${suffix}`;
}
