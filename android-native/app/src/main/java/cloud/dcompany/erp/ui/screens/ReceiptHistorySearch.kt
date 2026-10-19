package cloud.dcompany.erp.ui.screens

import cloud.dcompany.erp.core.auth.CacheScopeLease
import cloud.dcompany.erp.core.db.RECEIPT_HISTORY_PAGE_SIZE
import cloud.dcompany.erp.core.net.CanonicalReceipt
import cloud.dcompany.erp.core.net.CanonicalReceiptPage
import cloud.dcompany.erp.core.net.ErpApi
import cloud.dcompany.erp.core.sync.ReceiptHistoryPageWindow
import cloud.dcompany.erp.core.sync.fetchReceiptHistoryWindow
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** A cursor always travels with this complete, normalized server-side intersection. */
data class ReceiptHistoryFilters(
    val query: String = "",
    val dateFrom: String = "",
    val dateTo: String = "",
    val stationId: String? = null,
    val amountMinor: Long? = null,
    val method: String? = null,
    val staffUserId: String? = null,
) {
    val isAdvanced: Boolean get() = dateFrom.isNotBlank() || dateTo.isNotBlank() ||
        stationId != null || amountMinor != null || method != null || staffUserId != null
    val isActive: Boolean get() = query.isNotBlank() || isAdvanced
    fun normalized() = copy(query = query.trim(), dateFrom = dateFrom.trim(), dateTo = dateTo.trim(),
        stationId = stationId?.trim()?.takeIf(String::isNotBlank)?.lowercase(),
        method = method?.trim()?.takeIf(String::isNotBlank)?.lowercase(),
        staffUserId = staffUserId?.trim()?.takeIf(String::isNotBlank)?.lowercase())
}

internal fun receiptFilterError(filters: ReceiptHistoryFilters): String? {
    if (filters.query.length > 200) return "Use at most 200 characters for the invoice/reference search."
    fun date(value: String): LocalDate? = value.takeIf(String::isNotBlank)?.let {
        require(it.length == 10) { "Use YYYY-MM-DD for invoice-issued shop dates." }
        LocalDate.parse(it)
    }
    return try {
        val from = date(filters.dateFrom)
        val to = date(filters.dateTo)
        when {
            from != null && to != null && from > to -> "From date must not be later than To date."
            filters.amountMinor != null && filters.amountMinor < 0 -> "Invoice amount must be zero or more."
            filters.method != null && filters.method !in setOf("cash", "upi", "card", "qr", "wallet") ->
                "Choose a supported effective payment method."
            else -> {
                listOfNotNull(filters.stationId, filters.staffUserId).forEach {
                    require(UUID.fromString(it).toString() == it) { "The saved station or recorder filter is invalid." }
                }
                null
            }
        }
    } catch (_: Exception) { "Use valid YYYY-MM-DD dates and a valid saved station/recorder selection." }
}

internal suspend fun ErpApi.filteredReceiptHistory(filters: ReceiptHistoryFilters, cursor: String?): CanonicalReceiptPage {
    val result = receiptHistory(cursor = cursor, limit = RECEIPT_HISTORY_PAGE_SIZE,
        query = filters.query.takeIf(String::isNotBlank), dateFrom = filters.dateFrom.takeIf(String::isNotBlank),
        dateTo = filters.dateTo.takeIf(String::isNotBlank), stationId = filters.stationId,
        amountMinor = filters.amountMinor, method = filters.method, staffUserId = filters.staffUserId)
    if (filters.isAdvanced) {
        require(result.dateFilterBasis == "invoice_issued_at_in_shop_timezone" &&
            result.shopTimezone?.let { runCatching { ZoneId.of(it) }.isSuccess } == true) {
            "This server did not confirm the filtered receipt contract. Update the server before using advanced receipt filters."
        }
    }
    return result
}

/** UI-only pages stay in session memory; they never replace the normal Room history cursor. */
internal class ReceiptHistorySearch(
    private val tasks: CoroutineScope,
    private val online: () -> Boolean,
    private val lease: () -> CacheScopeLease?,
    private val commit: suspend (CacheScopeLease, () -> Unit) -> Boolean,
    private val cached: () -> Pair<List<CanonicalReceipt>, Long?>,
    private val fetchPage: suspend (ReceiptHistoryFilters, String?) -> CanonicalReceiptPage,
    private val fetchReceipt: suspend (String) -> CanonicalReceipt,
) {
    private val mutable = MutableStateFlow(ReceiptSearchUiState())
    val state: StateFlow<ReceiptSearchUiState> = mutable.asStateFlow()
    private var generation = 0L
    private var detailGeneration = 0L
    private var request: Job? = null
    private var detailRequest: Job? = null
    private var workspaceLease: CacheScopeLease? = null

    fun verifyScope() {
        if (workspaceLease != null && workspaceLease != lease()) {
            ++generation
            ++detailGeneration
            request?.cancel()
            detailRequest?.cancel()
            mutable.value = ReceiptSearchUiState()
            workspaceLease = null
        }
    }

    fun find(raw: ReceiptHistoryFilters) {
        verifyScope()
        val filters = raw.normalized()
        val operation = ++generation
        ++detailGeneration
        request?.cancel()
        detailRequest?.cancel()
        if (!filters.isActive) { mutable.value = ReceiptSearchUiState(); return }
        val prior = mutable.value
        receiptFilterError(filters)?.let {
            mutable.value = prior.copy(criteria = filters, loading = false, detailLoading = false, error = it)
            return
        }
        val captured = lease()
        if (captured == null) {
            mutable.value = prior.copy(criteria = filters, loading = false,
                detailLoading = false, error = "This receipt workspace is not verified. Reconnect and sign in before searching.")
            return
        }
        workspaceLease = captured
        if (!online()) {
            if (filters.isAdvanced) {
                mutable.value = prior.copy(criteria = filters, loading = false,
                    detailLoading = false, error = "Advanced filters require a live server. Any previous results remain under their applied filters; no full-history search was performed.")
            } else {
                val (rows, fetchedAt) = cached()
                mutable.value = ReceiptSearchUiState(criteria = filters, appliedCriteria = filters,
                    receipts = rows.filter { matchesScope(it, captured) && it.matchesCachedReceiptQuery(filters.query) },
                    source = ReceiptSearchSource.CACHED, cachedAtMillis = fetchedAt)
            }
            return
        }
        val windowCount = if (prior.appliedCriteria == filters) prior.receipts.size else 0
        mutable.value = prior.copy(criteria = filters, loading = true, error = null, detailLoading = false)
        request = tasks.launch {
            try {
                var metadata: CanonicalReceiptPage? = null
                val cursors = mutableSetOf<String?>()
                val ids = mutableSetOf<String>()
                val window = fetchReceiptHistoryWindow(windowCount) { cursor ->
                    require(cursors.add(cursor)) { "The server repeated an earlier receipt cursor. Existing results were kept." }
                    val page = fetchPage(filters, cursor)
                    require(page.items.all { matchesScope(it, captured) }) { "The server returned a receipt from another shop." }
                    require(page.items.all { ids.add(it.orderId) }) { "The server repeated receipt identities. Existing results were kept." }
                    metadata = page
                    ReceiptHistoryPageWindow(page.items, page.nextCursor, page.hasMore)
                }
                commit(captured) {
                    if (operation == generation && mutable.value.criteria == filters) {
                        val previous = mutable.value
                        mutable.value = previous.copy(criteria = filters, appliedCriteria = filters,
                            receipts = window.items, nextCursor = window.nextCursor, hasMore = window.hasMore,
                            loading = false, source = ReceiptSearchSource.ONLINE, error = null,
                            shopTimezone = metadata?.shopTimezone, dateFilterBasis = metadata?.dateFilterBasis,
                            detail = previous.detail?.let { old -> window.items.firstOrNull { it.orderId == old.orderId } },
                            detailError = null,
                            viewportRevision = previous.viewportRevision + if (prior.appliedCriteria != filters) 1 else 0)
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { failure(captured, operation, filters, error, "Receipt search could not be refreshed.") }
        }
    }

    fun more() {
        verifyScope()
        val before = mutable.value
        val filters = before.appliedCriteria ?: return
        val cursor = before.nextCursor ?: return
        if (before.criteria != filters || before.loading || before.source != ReceiptSearchSource.ONLINE || !before.hasMore) return
        val captured = lease() ?: return
        val operation = generation
        mutable.value = before.copy(loading = true, error = null)
        request = tasks.launch {
            try {
                val page = fetchPage(filters, cursor)
                require(!page.hasMore || (!page.nextCursor.isNullOrBlank() && page.nextCursor != cursor && page.items.isNotEmpty())) {
                    "The server did not advance the receipt cursor. Existing results were kept."
                }
                require(page.items.all { matchesScope(it, captured) }) { "The server returned a receipt from another shop." }
                require(page.items.map(CanonicalReceipt::orderId).distinct().size == page.items.size &&
                    page.items.none { item -> before.receipts.any { it.orderId == item.orderId } }) {
                    "The server repeated a loaded receipt. Existing results were kept."
                }
                commit(captured) {
                    if (operation == generation && mutable.value.criteria == filters && mutable.value.appliedCriteria == filters) {
                        mutable.value = mutable.value.copy(receipts = mutable.value.receipts + page.items,
                            nextCursor = page.nextCursor, hasMore = page.hasMore, loading = false, error = null)
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { failure(captured, operation, filters, error, "Older receipt results could not load.") }
        }
    }

    fun detail(orderId: String) {
        verifyScope()
        val before = mutable.value
        val filters = before.appliedCriteria ?: return
        val captured = lease() ?: return
        if (!online()) { mutable.value = before.copy(detailError = "Reconnect to refresh this exact receipt."); return }
        val operation = generation
        val detailOperation = ++detailGeneration
        detailRequest?.cancel()
        mutable.value = before.copy(detailLoading = true, detailError = null)
        detailRequest = tasks.launch {
            try {
                val receipt = fetchReceipt(orderId)
                require(receipt.orderId == orderId && matchesScope(receipt, captured)) {
                    "The server returned a different receipt or shop. The requested bill was not replaced."
                }
                commit(captured) {
                    if (operation == generation && detailOperation == detailGeneration && mutable.value.appliedCriteria == filters) {
                        val latest = mutable.value.withRefreshedDetail(receipt)
                        mutable.value = latest.copy(receipts = latest.receipts.filter {
                            it.orderId != orderId || receiptMatchesAdvancedFilters(it, filters, latest.shopTimezone)
                        })
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                commit(captured) {
                    if (operation == generation && detailOperation == detailGeneration && mutable.value.appliedCriteria == filters) {
                        mutable.value = mutable.value.copy(detailLoading = false,
                            detailError = error.message ?: "This exact receipt could not be refreshed.")
                    }
                }
            }
        }
    }

    private suspend fun failure(captured: CacheScopeLease, operation: Long, filters: ReceiptHistoryFilters,
        error: Exception, fallback: String) {
        commit(captured) {
            if (operation == generation && mutable.value.criteria == filters) {
                mutable.value = mutable.value.copy(loading = false, error = error.message ?: fallback)
            }
        }
    }

    private fun matchesScope(receipt: CanonicalReceipt, captured: CacheScopeLease): Boolean =
        receipt.companyId == captured.scope.companyId &&
            (captured.scope.branchId == null || receipt.branchId == captured.scope.branchId)
}

/** Only retained/effective fields determine membership; method+recorder belong to one leg. */
internal fun receiptMatchesAdvancedFilters(receipt: CanonicalReceipt, filters: ReceiptHistoryFilters, zone: String?): Boolean {
    if (filters.amountMinor != null && receipt.totalMinor != filters.amountMinor) return false
    if (filters.stationId != null && receipt.gamingSessions.none { it.stationId == filters.stationId }) return false
    if ((filters.method != null || filters.staffUserId != null) && receipt.payments.none {
        (filters.method == null || it.method == filters.method) &&
            (filters.staffUserId == null || it.recordedBy == filters.staffUserId)
    }) return false
    if (filters.dateFrom.isNotBlank() || filters.dateTo.isNotBlank()) {
        val day = runCatching { java.time.Instant.parse(receipt.invoiceIssuedAt).atZone(ZoneId.of(zone)).toLocalDate() }.getOrNull()
            ?: return false
        if (filters.dateFrom.isNotBlank() && day < LocalDate.parse(filters.dateFrom)) return false
        if (filters.dateTo.isNotBlank() && day > LocalDate.parse(filters.dateTo)) return false
    }
    return true
}
