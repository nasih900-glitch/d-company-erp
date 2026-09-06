import type { ApiError } from '@/lib/api';
import type {
  DistributablePartnerShareDTO,
  DistributableProfitReportDTO,
  PartnerPLReportDTO,
  PartnerProfitShareDTO,
} from '@/lib/erp-api';

export interface OptionalPartnerAllocation<T> {
  value: T | null;
  unavailableReason: string | null;
}

/** Only this known configuration rejection is optional; auth and transport still fail. */
export async function optionalPartnerAllocation<T>(
  fetch: () => Promise<T>,
): Promise<OptionalPartnerAllocation<T>> {
  try {
    return { value: await fetch(), unavailableReason: null };
  } catch (failure) {
    const error = failure as ApiError;
    if (
      error?.status !== 422 || error.code !== 'business_rule'
      || !error.message?.startsWith('Partner ownership shares total ')
      || !error.message.includes('%, not 100%. Owner reconciliation is required')
    ) throw failure;
    return { value: null, unavailableReason: error.message };
  }
}

/** Legacy cash includes unsettled provider funds; require the named server contract. */
export function verifiedSpendableCash(report: DistributableProfitReportDTO): number | null {
  const amount = report.spendable_cash_bank_minor;
  return typeof amount === 'number' && Number.isSafeInteger(amount)
    && report.cash_position?.spendable_cash_bank_minor === amount ? amount : null;
}

export function allocationIsAuthoritative(
  report: PartnerPLReportDTO | DistributableProfitReportDTO,
): boolean {
  const confidence = report.costing_confidence;
  return report.allocation_status === 'authoritative'
    && !report.allocation_unavailable_reason
    && confidence?.status === 'authoritative'
    && confidence.unresolved_order_count === 0
    && Number.isSafeInteger(confidence.inventory_orders_checked)
    && confidence.inventory_orders_checked >= 0
    && Number.isSafeInteger(confidence.inventory_lines_checked)
    && confidence.inventory_lines_checked >= confidence.inventory_orders_checked;
}

/** Trust only the new nullable value and require agreement with the legacy field. */
export function verifiedDistributionCap(report: DistributableProfitReportDTO): number | null {
  const amount = report.authoritative_safe_to_distribute_minor;
  return allocationIsAuthoritative(report)
    && verifiedSpendableCash(report) !== null
    && typeof amount === 'number'
    && Number.isSafeInteger(amount)
    && amount === report.safe_to_distribute_minor
    ? amount
    : null;
}

export function verifiedPartnerDistribution(
  report: DistributableProfitReportDTO,
  share: DistributablePartnerShareDTO,
): number | null {
  const amount = share.authoritative_distributable_share_minor;
  return verifiedDistributionCap(report) !== null
    && typeof amount === 'number'
    && Number.isSafeInteger(amount)
    && amount === share.distributable_share_minor
    ? amount
    : null;
}

export function verifiedPartnerProfitShare(
  report: PartnerPLReportDTO,
  share: PartnerProfitShareDTO,
): number | null {
  const amount = share.authoritative_profit_share_minor;
  return allocationIsAuthoritative(report)
    && typeof amount === 'number'
    && Number.isSafeInteger(amount)
    && amount === share.profit_share_minor
    ? amount
    : null;
}

export function allocationUnavailableReason(
  report: PartnerPLReportDTO | DistributableProfitReportDTO,
): string {
  return report.allocation_unavailable_reason
    || (allocationIsAuthoritative(report)
      ? PARTNER_CASH_UNVERIFIED
      : 'Partner allocations are unavailable because the server could not verify complete historical product costing. Reconcile product recipes and stock costs, then refresh Finance.');
}

export const PARTNER_CASH_UNVERIFIED =
  'Spendable cash is unverified. Refresh after the server is updated; do not use an older cash or provider-clearing total for a partner withdrawal.';

/** Missing optional cost evidence must not erase valid revenue; access failures are not optional. */
export async function optionalCostingCoverage<T>(fetch: () => Promise<T>): Promise<T | null> {
  try { return await fetch(); }
  catch (failure) {
    const status = (failure as ApiError)?.status;
    if (status === 401 || status === 403) throw failure;
    return null;
  }
}
