package cloud.dcompany.erp.ui.screens.finance

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import cloud.dcompany.erp.core.net.ApiClient
import cloud.dcompany.erp.core.net.ApiException
import java.io.File
import java.util.UUID
import java.lang.reflect.Proxy
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Real Android SharedPreferences, under a fresh test-only name; no API or financial writes. */
class FinanceRecoveryStoreUiTest {
    private lateinit var base: Context
    private lateinit var isolated: Context
    private lateinit var preferences: SharedPreferences
    private lateinit var store: SharedPreferencesFinanceWriteRecoveryStore
    private lateinit var name: String
    private val scope = FinanceWriteScope(UUID.randomUUID().toString(), UUID.randomUUID().toString(), UUID.randomUUID().toString())
    private val key get() = "pending:${scope.userId}:${scope.companyId}:${scope.branchId.orEmpty()}"
    @Before fun setup() {
        base = ApplicationProvider.getApplicationContext()
        name = "finance-recovery-qa-${UUID.randomUUID()}"
        isolated = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(ignored: String, mode: Int): SharedPreferences = base.getSharedPreferences(name, mode)
        }
        preferences = base.getSharedPreferences(name, Context.MODE_PRIVATE)
        store = SharedPreferencesFinanceWriteRecoveryStore(isolated)
    }
    @After fun cleanup() { if (::base.isInitialized && ::name.isInitialized) base.deleteSharedPreferences(name) }
    private fun command(forScope: FinanceWriteScope = scope) = pendingManualCollectionVoid(forScope,
        UUID.randomUUID().toString(), "Synthetic fixture reason", nowMillis = 1000)
    private fun rawFile() = File(base.dataDir, "shared_prefs/$name.xml")
    private fun assertInvalidBytesStayHeld(raw: String) {
        assertTrue(preferences.edit().putString(key, raw).commit())
        val before = rawFile().readBytes()
        repeat(2) {
            val reloaded = SharedPreferencesFinanceWriteRecoveryStore(isolated)
            val failure = runCatching { reloaded.load(scope) }.exceptionOrNull()
            assertTrue(failure is FinanceRecoveryUnavailable)
            assertEquals(FINANCE_RECOVERY_BLOCKED_MESSAGE, failure?.message)
            assertNull(failure?.cause)
            assertTrue(reloaded.hasSavedWork())
            val replacement = command()
            assertFalse(reloaded.save(replacement)); assertFalse(reloaded.clear(replacement))
            assertEquals(raw, preferences.getString(key, null))
            assertArrayEquals(before, rawFile().readBytes())
        }
    }
    @Test fun malformedSavedBytesAreSanitizedAndNeverDeletedOrReplaced() {
        assertInvalidBytesStayHeld("{\"PRIVATE-SYNTHETIC-CANARY\":")
    }
    @Test fun wrongScopeSavedBytesAreSanitizedAndNeverDeletedOrReplaced() {
        val foreign = command(scope.copy(companyId = UUID.randomUUID().toString()))
        assertInvalidBytesStayHeld(ApiClient.json.encodeToString(foreign))
    }
    @Test fun validCheckpointCanOnlyBeReplayedOrClearedExactly() {
        val first = command(); assertTrue(store.save(first)); val before = rawFile().readBytes()
        assertEquals(first, store.load(scope)); assertTrue(store.hasSavedWork())
        assertFalse(store.save(command())); assertArrayEquals(before, rawFile().readBytes())
        assertTrue(store.save(first)); assertTrue(store.clear(first))
        assertNull(store.load(scope)); assertFalse(store.hasSavedWork())
    }
    private suspend fun lostReplyThenDenied(status: Int) {
        val write = pendingManualCollectionCreate(scope, ManualCollectionCreate(requireNotNull(scope.branchId),
            businessDate = "2026-11-05", method = "cash", amountMinor = 100, sourceRef = "Synthetic fixture"))
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
        assertTrue(store.save(write)); val before = rawFile().readBytes()
        val first = requireNotNull(runCatching { executor.execute(write) }.exceptionOrNull())
        assertTrue(first is ApiException); assertNull((first as ApiException).status)
        if (!preserveFinanceWriteForRetry(first, retainedBeforeAttempt = false)) store.clear(write)
        assertEquals(write, store.load(scope))
        val exactRetry = requireNotNull(store.load(scope))
        val denied = requireNotNull(runCatching { executor.execute(exactRetry) }.exceptionOrNull())
        assertTrue(denied is ApiException); assertEquals(status, (denied as ApiException).status)
        assertFalse("The old fresh-attempt policy would discard this exact typed denial", preserveFinanceWriteForRetry(denied, false))
        if (!preserveFinanceWriteForRetry(denied, retainedBeforeAttempt = true)) store.clear(exactRetry)
        assertEquals(2, calls.size); assertEquals(calls[0], calls[1])
        assertEquals(write, store.load(scope)); assertTrue(store.hasSavedWork())
        assertArrayEquals(before, rawFile().readBytes())
        assertFalse(store.save(command()))
        assertFalse(financeWriteFailureMessage(denied, true).contains("Synthetic later denial"))
        assertArrayEquals(before, rawFile().readBytes())
    }
    @Test fun executorLostReplyThen401RetainsExactPrivateBytesAndBlocksReplacement() = runBlocking { lostReplyThenDenied(401) }
    @Test fun executorLostReplyThen403RetainsExactPrivateBytesAndBlocksReplacement() = runBlocking { lostReplyThenDenied(403) }
}
