import { describe, expect, it } from 'vitest';
import type { ApiError } from '@/lib/api';
import type { DistributableProfitReportDTO, PartnerPLReportDTO } from '@/lib/erp-api';
import {
  allocationIsAuthoritative,
  allocationUnavailableReason,
  optionalCostingCoverage,
  optionalPartnerAllocation,
  verifiedDistributionCap,
  verifiedPartnerDistribution,
  verifiedPartnerProfitShare,
  verifiedSpendableCash,
} from './partner-allocation';

const message = 'Partner ownership shares total 0%, not 100%. Owner reconciliation is required before profit or distribution allocations are authoritative.';
function error(status: number, code = 'business_rule', text = message): ApiError {
  return Object.assign(new Error(text), { status, code });
}
const report: DistributableProfitReportDTO = {
  as_of: '2026-09-05', lifetime_net_profit_minor: 100, lifetime_depreciation_minor: 0,
  lifetime_withdrawn_minor: 0, reserve_months: 6, avg_monthly_cost_minor: 0,
  reserve_minor: 0, liquid_cash_minor: 99999, profit_based_capacity_minor: 100,
  cash_based_capacity_minor: 100, safe_to_distribute_minor: 100, partners: [],
};
const authoritativeReport: DistributableProfitReportDTO = {
  ...report,
  spendable_cash_bank_minor: 100,
  cash_position: {
    cash_on_hand_minor: 100,
    bank_balance_minor: 0,
    spendable_cash_bank_minor: 100,
    settlement_receivables_minor: 0,
  },
  authoritative_safe_to_distribute_minor: 100,
  allocation_status: 'authoritative',
  allocation_unavailable_reason: null,
  costing_confidence: {
    status: 'authoritative', inventory_orders_checked: 2,
    inventory_lines_checked: 3, unresolved_order_count: 0, reason: null,
  },
  partners: [{
    partner_id: 'partner-1', name: 'Owner', share_pct: 100,
    capital_balance_minor: 0, lifetime_withdrawn_minor: 0,
    distributable_share_minor: 100,
    authoritative_distributable_share_minor: 100,
  }],
};

describe('optional partner allocations', () => {
  it('keeps valid partner/capital reads when only ownership allocation is unconfigured', async () => {
    const [partners, allocation] = await Promise.all([
      Promise.resolve([{ name: 'Test Partner', capital_balance_minor: 50000 }]),
      optionalPartnerAllocation(async () => { throw error(422); }),
    ]);
    expect(partners).toHaveLength(1);
    expect(allocation).toEqual({ value: null, unavailableReason: message });
  });

  it('returns only the new server result after successful reconciliation', async () => {
    expect(await optionalPartnerAllocation(async () => report)).toEqual({ value: report, unavailableReason: null });
  });

  it('does not swallow authentication, scope, other validation or transport failures', async () => {
    for (const failure of [error(401), error(403), error(500), error(422, 'validation_error'), error(422, 'business_rule', 'Branch mismatch'), new Error('Disconnected')]) {
      await expect(optionalPartnerAllocation(async () => { throw failure; })).rejects.toBe(failure);
    }
  });

  it('never treats the legacy aggregate as spendable cash', () => {
    expect(verifiedSpendableCash(report)).toBeNull();
    expect(verifiedSpendableCash({ ...report, spendable_cash_bank_minor: 100 })).toBeNull();
  });

  it('accepts verified zero and negative balances, and rejects inconsistent fields', () => {
    for (const amount of [0, -100, 50000]) {
      const cash_position = { cash_on_hand_minor: amount, bank_balance_minor: 0, spendable_cash_bank_minor: amount, settlement_receivables_minor: 0 };
      expect(verifiedSpendableCash({ ...report, spendable_cash_bank_minor: amount, cash_position })).toBe(amount);
      expect(verifiedSpendableCash({ ...report, spendable_cash_bank_minor: amount + 1, cash_position })).toBeNull();
    }
  });

  it('shows distribution and partner amounts only when the embedded historical costing contract agrees', () => {
    expect(allocationIsAuthoritative(authoritativeReport)).toBe(true);
    expect(verifiedDistributionCap(authoritativeReport)).toBe(100);
    expect(verifiedPartnerDistribution(authoritativeReport, authoritativeReport.partners[0])).toBe(100);

    const incomplete: DistributableProfitReportDTO = {
      ...authoritativeReport,
      allocation_status: 'costing_incomplete',
      allocation_unavailable_reason: 'One historical drink sale needs costing.',
      costing_confidence: {
        status: 'costing_incomplete', inventory_orders_checked: 1,
        inventory_lines_checked: 1, unresolved_order_count: 1,
        reason: 'One historical drink sale needs costing.',
      },
      // Even a malformed/stale server retaining non-zero legacy values cannot
      // make them look withdrawable in a current client.
      authoritative_safe_to_distribute_minor: null,
      partners: [{
        ...authoritativeReport.partners[0],
        authoritative_distributable_share_minor: null,
      }],
    };
    expect(allocationIsAuthoritative(incomplete)).toBe(false);
    expect(verifiedDistributionCap(incomplete)).toBeNull();
    expect(verifiedPartnerDistribution(incomplete, incomplete.partners[0])).toBeNull();
    expect(allocationUnavailableReason(incomplete)).toContain('historical drink sale');

    expect(verifiedDistributionCap(report)).toBeNull();
    expect(verifiedDistributionCap({
      ...authoritativeReport,
      authoritative_safe_to_distribute_minor: 99,
    })).toBeNull();
  });

  it('hides period partner profit shares from old, incomplete, or contradictory contracts', () => {
    const period: PartnerPLReportDTO = {
      period_start: '2026-09-01', period_end: '2026-09-05', net_profit_minor: 100,
      allocation_status: 'authoritative', allocation_unavailable_reason: null,
      costing_confidence: {
        status: 'authoritative', inventory_orders_checked: 0,
        inventory_lines_checked: 0, unresolved_order_count: 0, reason: null,
      },
      partners: [{
        partner_id: 'partner-1', name: 'Owner', share_pct: 100,
        capital_balance_minor: 0, profit_share_minor: 100,
        authoritative_profit_share_minor: 100,
      }],
    };
    expect(verifiedPartnerProfitShare(period, period.partners[0])).toBe(100);
    expect(verifiedPartnerProfitShare({
      ...period,
      allocation_status: 'costing_unavailable',
      allocation_unavailable_reason: 'Refresh Finance.',
      costing_confidence: undefined,
    }, period.partners[0])).toBeNull();
    expect(verifiedPartnerProfitShare(period, {
      ...period.partners[0], authoritative_profit_share_minor: 99,
    })).toBeNull();
  });

  it('lets missing costing remain unknown without hiding revenue or suppressing access failures', async () => {
    expect(await optionalCostingCoverage(async () => { throw error(503); })).toBeNull();
    for (const failure of [error(401), error(403)]) {
      await expect(optionalCostingCoverage(async () => { throw failure; })).rejects.toBe(failure);
    }
  });
});
