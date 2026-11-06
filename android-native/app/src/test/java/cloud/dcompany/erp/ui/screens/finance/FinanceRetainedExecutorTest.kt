package cloud.dcompany.erp.ui.screens.finance

import cloud.dcompany.erp.core.net.ApiClient
import cloud.dcompany.erp.core.net.ApiException
import java.lang.reflect.Proxy
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import org.junit.Assert.*
import org.junit.Test

/** Real executor/DTO calls, synthetic server outcomes, and the exact decision used by the VM. */
class FinanceRetainedExecutorTest {
    private suspend fun retryDenied(status: Int) {
        val scope = FinanceWriteScope("synthetic-owner", "synthetic-company", "synthetic-branch")
        val write = pendingManualCollectionCreate(scope, ManualCollectionCreate("synthetic-branch", businessDate = "2026-11-05",
            method = "cash", amountMinor = 100, sourceRef = "Synthetic fixture"), idempotencyKey = "manual-collection:synthetic-exact")
        var retained: String? = ApiClient.json.encodeToString(write)
        val before = retained
        val calls = mutableListOf<Pair<String, ManualCollectionCreate>>()
        val api = Proxy.newProxyInstance(FinanceApi::class.java.classLoader, arrayOf(FinanceApi::class.java)) { _, method, args ->
            check(method.name == "createManualCollection")
            calls += (args[1] as String) to (args[0] as ManualCollectionCreate)
            val error = if (calls.size == 1) ApiException("Synthetic committed response lost")
                else ApiException("Synthetic later denial", status = status)
            @Suppress("UNCHECKED_CAST") val continuation = args.last() as Continuation<ManualCollection>
            continuation.resumeWith(Result.failure(error))
            COROUTINE_SUSPENDED
        } as FinanceApi
        val executor = RetrofitFinanceOnlineWriteExecutor(api)
        val lost = requireNotNull(runCatching { executor.execute(write) }.exceptionOrNull())
        assertTrue(lost is ApiException); assertNull((lost as ApiException).status)
        if (!preserveFinanceWriteForRetry(lost, retainedBeforeAttempt = false)) retained = null
        val retry = ApiClient.json.decodeFromString<PendingFinanceOnlineWrite>(requireNotNull(retained))
        val denied = requireNotNull(runCatching { executor.execute(retry) }.exceptionOrNull())
        // Both VM catch branches share this decision, even if the active UI scope changed.
        assertTrue(denied is ApiException); assertEquals(status, (denied as ApiException).status)
        assertFalse("The old fresh-attempt policy would discard this exact typed denial", preserveFinanceWriteForRetry(denied, false))
        if (!preserveFinanceWriteForRetry(denied, retainedBeforeAttempt = true)) retained = null
        assertEquals(before, retained); assertEquals(write, retry); assertEquals(2, calls.size); assertEquals(calls[0], calls[1])
        assertTrue(financeWriteFailureMessage(denied, true).contains("Do not enter or pay"))
    }
    @Test fun lostResponseThen401CannotDiscardPreviouslyRetainedFinancialRequest() = runBlocking { retryDenied(401) }
    @Test fun lostResponseThen403CannotDiscardPreviouslyRetainedFinancialRequest() = runBlocking { retryDenied(403) }
}
