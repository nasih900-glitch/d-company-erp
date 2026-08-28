import type { PosRefundRequestDTO, PosRefundStatus } from '@/lib/erp-api';

export type RefundRailKind = 'unknown' | 'cash' | 'single_provider' | 'mixed';

export interface RefundRailPolicy {
  kind: RefundRailKind;
  methods: PosRefundRequestDTO['settlement_method'][];
  defaultMode: 'cash' | 'original';
  requestReady: boolean;
}

const PAYMENT_METHODS = new Set(['cash', 'card', 'upi', 'qr', 'wallet']);
const LEGACY_REFUND_ROLES = new Set(['super_owner', 'co_owner', 'owner', 'manager']);

export interface RefundIdentity {
  roles?: string[];
  protected_access?: boolean | null;
  audit_access?: boolean | null;
  effective_permissions?: string[];
}

/** Exact server permission first; old-server fallback mirrors role defaults. */
export function canAccessRefunds(identity: RefundIdentity | null | undefined): boolean {
  if (!identity) return false;
  if (identity.effective_permissions !== undefined) {
    return identity.effective_permissions.includes('pos.refund');
  }
  if (identity.protected_access) return true;
  return identity.roles?.some((role) => LEGACY_REFUND_ROLES.has(role)) ?? false;
}

/** Never guess a provider rail from an amount, label, or prior selection. */
export function refundRailPolicy(rawMethods: readonly string[]): RefundRailPolicy {
  const methods = rawMethods
    .map((method) => method.trim().toLowerCase())
    .filter((method, index, all) => Boolean(method) && all.indexOf(method) === index);
  if (!methods.length || methods.some((method) => !PAYMENT_METHODS.has(method))) {
    return { kind: 'unknown', methods: [], defaultMode: 'cash', requestReady: false };
  }
  const typed = methods as PosRefundRequestDTO['settlement_method'][];
  if (typed.length > 1) {
    return { kind: 'mixed', methods: typed, defaultMode: 'cash', requestReady: true };
  }
  if (typed[0] === 'cash') {
    return { kind: 'cash', methods: typed, defaultMode: 'cash', requestReady: true };
  }
  return { kind: 'single_provider', methods: typed, defaultMode: 'original', requestReady: true };
}

export function refundModeAllowed(
  policy: RefundRailPolicy,
  mode: 'cash' | 'original',
): boolean {
  if (!policy.requestReady) return false;
  if (policy.kind === 'single_provider') return true;
  return mode === 'cash';
}

export function paymentMethodLabel(method: string): string {
  switch (method) {
    case 'cash': return 'Cash';
    case 'card': return 'Card';
    case 'upi': return 'UPI';
    case 'qr': return 'QR';
    case 'wallet': return 'Wallet';
    default: return 'Unknown';
  }
}

export type RefundTaskAction =
  | 'begin_cash'
  | 'settle_cash'
  | 'finalize_cash'
  | 'withdraw_cash'
  | 'resolve_cash'
  | 'begin_provider'
  | 'settle_provider'
  | 'finalize_provider'
  | 'withdraw_provider'
  | 'resolve_provider';

export interface RefundActionContext {
  userId: string | null;
  protectedAccess: boolean;
  adminSystemAccess: boolean;
  currentShiftId: string | null;
  canManageCurrentShift: boolean;
  online: boolean;
  outcomeUncertain?: boolean;
}

/**
 * UI action ceiling for the authoritative server state. The backend remains
 * the final authority, but impossible or actor-owned transitions are not shown.
 */
export function allowedRefundActions(
  task: PosRefundRequestDTO,
  context: RefundActionContext,
): RefundTaskAction[] {
  if (
    !context.online
    || context.outcomeUncertain
    || !context.canManageCurrentShift
    || context.currentShiftId !== task.shift_id
  ) return [];

  const handoffActor = task.handoff_started_by;
  const providerActor = task.provider_payout_started_by;
  const cashRecorder = task.cash_handed_over_by;
  const providerRecorder = task.provider_completed_by;
  const mayTakeOver = context.protectedAccess;

  switch (task.status) {
    case 'accepted_cash_due':
      return [
        'begin_cash',
        ...(context.protectedAccess ? ['withdraw_cash' as const] : []),
      ];
    case 'cash_handoff_in_progress':
      if (!mayTakeOver && handoffActor !== context.userId) return [];
      return [
        'settle_cash',
        ...(context.protectedAccess ? ['resolve_cash' as const] : []),
      ];
    case 'cash_handed_over_pending_accounting':
      return mayTakeOver || cashRecorder === context.userId ? ['finalize_cash'] : [];
    case 'accepted_provider_due':
      return [
        'begin_provider',
        ...(context.protectedAccess ? ['withdraw_provider' as const] : []),
      ];
    case 'provider_payout_in_progress':
      if (!mayTakeOver && providerActor !== context.userId) return [];
      return [
        'settle_provider',
        // This is intentionally admin.system, not general protected_access.
        ...(context.adminSystemAccess ? ['resolve_provider' as const] : []),
      ];
    case 'provider_completed_pending_accounting':
      return mayTakeOver || providerRecorder === context.userId ? ['finalize_provider'] : [];
    case 'settled':
    case 'withdrawn':
      return [];
  }
}

export interface RefundStatusPresentation {
  label: string;
  title: string;
  detail: string;
  tone: 'neutral' | 'info' | 'warning' | 'danger' | 'success';
  moneyMoved: boolean;
}

const STATUS_PRESENTATION: Record<PosRefundStatus, RefundStatusPresentation> = {
  accepted_cash_due: {
    label: 'Cash due',
    title: 'Refund reserved — no cash moved',
    detail: 'Verify the customer and amount, then open the cash handover. Do not pay before that step.',
    tone: 'warning',
    moneyMoved: false,
  },
  accepted_provider_due: {
    label: 'Provider due',
    title: 'Refund reserved — provider not started',
    detail: 'Open the provider payout from this task before using Card, UPI, QR or Wallet.',
    tone: 'warning',
    moneyMoved: false,
  },
  cash_handoff_in_progress: {
    label: 'Handover active',
    title: 'Cash handover started',
    detail: 'Verify the customer and drawer. Record cash once, or use the guarded no-cash resolution.',
    tone: 'danger',
    moneyMoved: false,
  },
  cash_handed_over_pending_accounting: {
    label: 'Accounting pending',
    title: 'Cash was recorded as handed over',
    detail: 'Do not pay again. Only finish the accounting record for this same handover.',
    tone: 'danger',
    moneyMoved: true,
  },
  provider_payout_in_progress: {
    label: 'Provider active',
    title: 'Provider payout started',
    detail: 'Check the provider outcome before acting. Never start a second provider refund.',
    tone: 'danger',
    moneyMoved: false,
  },
  provider_completed_pending_accounting: {
    label: 'Accounting pending',
    title: 'Provider completion was recorded',
    detail: 'Do not refund again. Only finish accounting for the recorded provider reference.',
    tone: 'danger',
    moneyMoved: true,
  },
  settled: {
    label: 'Settled',
    title: 'Refund settled',
    detail: 'The server recorded the refund, receipt, drawer/provider facts, and accounting effects.',
    tone: 'success',
    moneyMoved: true,
  },
  withdrawn: {
    label: 'Withdrawn',
    title: 'Refund withdrawn — no payout recorded',
    detail: 'The server preserved the request and recorded the verified no-payout outcome.',
    tone: 'neutral',
    moneyMoved: false,
  },
};

export function refundStatusPresentation(status: PosRefundStatus): RefundStatusPresentation {
  return STATUS_PRESENTATION[status];
}

export function makeRefundActionId(operation: string): string {
  const random = globalThis.crypto?.randomUUID?.()
    ?? `${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}`;
  return `web-refund:${operation}:${random}`;
}
