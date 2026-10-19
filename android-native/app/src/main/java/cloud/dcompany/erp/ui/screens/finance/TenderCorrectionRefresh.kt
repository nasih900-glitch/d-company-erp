package cloud.dcompany.erp.ui.screens.finance

import cloud.dcompany.erp.DCompanyApp
import cloud.dcompany.erp.core.sync.ResourceRefreshResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

internal data class TenderCorrectionNotice(val scope: TenderScope, val receiptId: String, val resources: Set<String>)

/** Hints contain scope and identities only; they never carry a command or authority. */
internal object TenderCorrectionNotices {
    private val mutable = MutableSharedFlow<TenderCorrectionNotice>(extraBufferCapacity = 32)
    val changes = mutable.asSharedFlow()
    fun publish(scope: TenderScope, receipt: TenderReceipt) {
        mutable.tryEmit(TenderCorrectionNotice(scope, receipt.id, tenderAffectedResources(receipt)))
    }
}

internal fun TenderCorrectionNotice.matchesOwnerKey(ownerKey: String?): Boolean {
    val parts = ownerKey?.split('|') ?: return false
    return parts.size == 3 && parts[0] == scope.companyId && parts[1] == scope.ownerId &&
        (parts[2].isEmpty() || parts[2] == scope.branchId)
}

internal fun tenderAffectedResources(receipt: TenderReceipt): Set<String> =
    if (receipt.operation == "payment_method_corrected") linkedSetOf("receipts", "orders", "shifts", "finance", "customers", "reports", "audit")
    else linkedSetOf("finance", "reports", "audit")

/** Read-side refresh after a matching accepted receipt. Never called as write retry. */
internal suspend fun refreshTenderReaders(scope: TenderScope, receipt: TenderReceipt): List<String> {
    val app = DCompanyApp.instance
    val failed = mutableListOf<String>()
    for (resource in tenderAffectedResources(receipt).filterNot { it == "audit" || it == "reports" }) {
        if (app.shiftCache.profile.value?.tenderScope(scope.branchId) != scope) {
            failed += "Workspace changed; remaining views were not refreshed."
            break
        }
        try {
            when (val result = app.sync.refresh(resource)) {
                is ResourceRefreshResult.Refreshed -> Unit
                is ResourceRefreshResult.Failed -> failed += "$resource: ${result.userMessage}"
                is ResourceRefreshResult.Skipped -> failed += "$resource was not refreshed with the current access."
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { failed += "$resource refresh failed; its cached values may be stale." }
    }
    return failed
}
