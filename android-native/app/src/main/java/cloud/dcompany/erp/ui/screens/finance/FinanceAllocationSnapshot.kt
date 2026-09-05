package cloud.dcompany.erp.ui.screens.finance

import cloud.dcompany.erp.core.net.ApiException
import kotlinx.serialization.Serializable

/** One atomic cache value: an invalid allocation replaces, never supplements,
 * a previously valid report. The backend still owns all allocation rules. */
@Serializable
internal data class FinanceAllocationSnapshot(
    val report: DistributableProfit? = null,
    val unavailableReason: String? = null,
) {
    init {
        require((report != null) != !unavailableReason.isNullOrBlank())
    }
}

/** Catch inside the async child so this specific business validation cannot
 * cancel unrelated P&L reads. Auth, transport, scope and decoder failures
 * remain refresh failures, and cancellation is never intercepted. */
internal suspend fun fetchFinanceAllocation(
    fetch: suspend () -> DistributableProfit,
): FinanceAllocationSnapshot = try {
    FinanceAllocationSnapshot(report = fetch())
} catch (failure: ApiException) {
    val message = failure.message.orEmpty()
    val ownershipNeedsReconciliation = failure.status == 422 &&
        failure.code == "business_rule" &&
        message.startsWith("Partner ownership shares total ") &&
        message.contains("%, not 100%. Owner reconciliation is required")
    if (!ownershipNeedsReconciliation) throw failure
    FinanceAllocationSnapshot(unavailableReason = message)
}

internal fun FinanceAllocationSnapshot?.reportForSummary(
    allocationFetchedAt: Long?,
    summaryFetchedAt: Long?,
): DistributableProfit? = this?.report?.takeIf {
    allocationFetchedAt != null && allocationFetchedAt == summaryFetchedAt
}

internal const val FINANCE_ALLOCATION_NOT_VERIFIED =
    "Partner allocations have not been verified. Refresh Finance before using any " +
        "profit-share or distribution figure. P&L and recorded collections are shown separately."
