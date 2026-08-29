package cloud.dcompany.erp.ui.screens.finance

import cloud.dcompany.erp.core.net.ApiClient
import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertEquals
import org.junit.Test

class FinanceWireContractTest {

    @Test
    fun distributableFieldsDecodeAuthoritativeSpendableAndClearingBreakdown() {
        val report = ApiClient.json.decodeFromString<DistributableProfit>(
            """
            {
              "as_of":"2026-08-28",
              "lifetime_net_profit_minor":6565600,
              "lifetime_depreciation_minor":50000,
              "lifetime_withdrawn_minor":100000,
              "reserve_months":6,
              "avg_monthly_cost_minor":250000,
              "reserve_minor":1500000,
              "liquid_cash_minor":3200000,
              "spendable_cash_bank_minor":2200000,
              "cash_position":{
                "cash_on_hand_minor":200000,
                "bank_balance_minor":2000000,
                "spendable_cash_bank_minor":2200000,
                "card_clearing_minor":300000,
                "upi_qr_clearing_minor":1000000,
                "wallet_clearing_minor":100000,
                "pos_settlement_clearing_minor":50000,
                "settlement_receivables_minor":1450000,
                "historical_funds_pending_reconciliation_minor":0,
                "unreconciled_settlement_minor":0,
                "reconciliation_only_minor":0
              },
              "profit_based_capacity_minor":4956000,
              "cash_based_capacity_minor":700000,
              "safe_to_distribute_minor":700000,
              "partners":[]
            }
            """.trimIndent(),
        )

        assertEquals(6_565_600L, report.lifetimeNetProfitMinor)
        assertEquals(3_200_000L, report.liquidCashMinor)
        assertEquals(2_200_000L, report.authoritativeSpendableCashMinor())
        assertEquals(1_450_000L, report.cashPosition?.settlementReceivablesMinor)
        assertEquals(4_956_000L, report.profitBasedCapacityMinor)
        assertEquals(700_000L, report.cashBasedCapacityMinor)
        assertEquals(700_000L, report.safeToDistributeMinor)
    }

    @Test
    fun legacyDistributableResponseDecodesButCannotAuthoriseCashCapacity() {
        val report = ApiClient.json.decodeFromString<DistributableProfit>(
            """
            {
              "as_of":"2026-08-28",
              "lifetime_net_profit_minor":6565600,
              "lifetime_depreciation_minor":50000,
              "lifetime_withdrawn_minor":100000,
              "reserve_months":6,
              "avg_monthly_cost_minor":250000,
              "reserve_minor":1500000,
              "liquid_cash_minor":3200000,
              "profit_based_capacity_minor":4956000,
              "cash_based_capacity_minor":1700000,
              "safe_to_distribute_minor":1700000,
              "partners":[]
            }
            """.trimIndent(),
        )

        assertEquals(3_200_000L, report.liquidCashMinor)
        assertEquals(null, report.authoritativeSpendableCashMinor())
    }

    @Test
    fun pnlAndBusinessMetricFieldsRetainServerPeriodSemantics() {
        val pnl = ApiClient.json.decodeFromString<ProfitAndLoss>(
            """
            {
              "accounting_basis":"operational_receipt",
              "period_start":"2026-08-01",
              "period_end":"2026-08-28",
              "revenue_minor":100000,
              "memberships_minor":10000,
              "cogs_minor":30000,
              "gross_profit_minor":70000,
              "expenses_minor":20000,
              "depreciation_minor":5000,
              "net_profit_minor":45000
            }
            """.trimIndent(),
        )
        val metrics = ApiClient.json.decodeFromString<BusinessMetrics>(
            """
            {
              "period_start":"2026-08-01",
              "period_end":"2026-08-28",
              "aov_minor":10000,
              "orders_count":10,
              "mrr_minor":0,
              "arr_minor":0,
              "active_members_count":0,
              "cac_minor":null,
              "new_customers_count":0,
              "marketing_spend_minor":0,
              "ltv_minor":25000,
              "customers_count":4,
              "burn_rate_minor":0
            }
            """.trimIndent(),
        )

        assertEquals("operational_receipt", pnl.accountingBasis)
        assertEquals(45_000L, pnl.netProfitMinor)
        assertEquals(10, metrics.ordersCount)
        assertEquals(null, metrics.cacMinor)
        assertEquals(0L, metrics.burnRateMinor)
    }
}
