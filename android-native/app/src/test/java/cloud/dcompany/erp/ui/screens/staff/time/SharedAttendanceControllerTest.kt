package cloud.dcompany.erp.ui.screens.staff.time

import cloud.dcompany.erp.core.net.ApiException
import java.io.IOException
import java.lang.reflect.Proxy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class SharedAttendanceControllerTest {
    private val a = "11111111-1111-4111-8111-111111111111"
    private val company = "22222222-2222-4222-8222-222222222222"
    private val branch = "33333333-3333-4333-8333-333333333333"
    private val terminal = "44444444-4444-4444-8444-444444444444"
    private val b = "55555555-5555-4555-8555-555555555555"
    private val attendance = "66666666-6666-4666-8666-666666666666"
    private fun person(out: Boolean = false) = SharedTimePerson(b, "Synthetic B", out, attendance.takeIf { out })
    private fun receipt(out: Boolean = false, breaks: Int = 0) = SharedTimeReceipt(attendance, b, branch,
        "2026-11-05T08:00:00Z", "2026-11-05T10:00:00Z".takeIf { out }, breaks, out,
        "2026-11-05T10:00:00Z", "app", "Synthetic B")
    private class Store : StaffTimePendingStore {
        var value: PendingTimeAttempt? = null
        var failPrepare = false; var failSave = false
        var completed = 0
        var archived = 0
        override suspend fun read(scopeKey: String) = value
        override suspend fun save(scopeKey: String, attempt: PendingTimeAttempt) {
            if (failSave) throw IOException("disk")
            check(value == null || value == attempt); value = attempt
        }
        override suspend fun prepareForDispatch(scopeKey: String, expected: PendingTimeAttempt): PendingTimeAttempt {
            if (failPrepare) throw IOException("Synthetic transition failure")
            check(value == expected)
            return expected.copy(rejectedStatus = null).also { value = it }
        }
        override suspend fun reject(scopeKey: String, key: String, status: Int): PendingTimeAttempt {
            check(value?.intent?.key == key)
            return requireNotNull(value).copy(rejectedStatus = status).also { value = it }
        }
        override suspend fun complete(scopeKey: String, key: String) { check(value?.intent?.key == key); value = null; completed++ }
        override suspend fun acknowledgeRejected(scopeKey: String, key: String) {
            check(value?.intent?.key == key && value?.rejectedStatus in DEFINITIVE_TIME_REJECTIONS); value = null; archived++
        }
    }
    private inner class Api(val store: Store) : SharedAttendanceApi {
        var page = SharedTimePeople(listOf(person()), null)
        val cursors = mutableListOf<String?>()
        val keys = mutableListOf<String>()
        var passwordSeen = ""
        var inHook: (suspend () -> SharedTimeReceipt)? = null
        var outHook: (suspend (SharedTimeClockOut) -> SharedTimeReceipt)? = null
        override suspend fun people(afterId: String?, limit: Int): SharedTimePeople { cursors += afterId; return page }
        override suspend fun clockIn(body: SharedTimeClockIn, key: String): SharedTimeReceipt {
            assertEquals(key, store.value?.intent?.key); keys += key; passwordSeen = body.password
            assertFalse(StaffTimeJson.encodeToString(requireNotNull(store.value)).contains(body.password))
            return inHook?.invoke() ?: receipt()
        }
        override suspend fun clockOut(body: SharedTimeClockOut, key: String): SharedTimeReceipt {
            assertEquals(key, store.value?.intent?.key); assertEquals(attendance, body.attendanceId)
            keys += key; passwordSeen = body.password
            return outHook?.invoke(body) ?: receipt(true, body.breakMinutes)
        }
    }
    private inner class Env(val api: Api) : StaffTimeEnvironment {
        var active = true
        var connected = true
        var canClock = true
        var actor = a
        var branchId = branch
        var proof = "a".repeat(64)
        var generation = 0
        private val primary = Proxy.newProxyInstance(StaffTimeApi::class.java.classLoader, arrayOf(StaffTimeApi::class.java)) { _, _, _ ->
            error("Shared attendance must not call ordinary attendance, login or pay APIs")
        } as StaffTimeApi
        override fun online() = connected
        override suspend fun capture(): StaffTimeAuthority {
            val captured = generation
            return StaffTimeAuthority(actor, company, branchId, terminal, proof, false,
                { active && generation == captured }, primary, api, canClock)
        }
    }
    private fun controller(env: Env, store: Store) = SharedAttendanceController(a, company, env, store)
    @Test fun successUsesOnlySharedApiAndPersistsCredentialFreeIntentBeforeWire() = runBlocking {
        val store = Store(); val api = Api(store); val env = Env(api); val c = controller(env, store)
        c.refresh(); c.select(b); c.confirm("staff-password", "")
        assertEquals(1, api.keys.size); assertEquals("staff-password", api.passwordSeen)
        assertNull(store.value); assertEquals(1, store.completed); assertEquals(a, env.actor)
        assertTrue(c.state.value.notice!!.contains("Cashier unchanged"))
    }
    @Test fun emptyFilteredPageCanAdvanceCursorWithoutLosingLaterEligiblePeople() = runBlocking {
        val store = Store(); val api = Api(store); val c = controller(Env(api), store)
        api.page = SharedTimePeople(emptyList(), a); c.refresh()
        assertTrue(c.state.value.ready); assertEquals(a, c.state.value.nextCursor)
        api.page = SharedTimePeople(listOf(person()), null); c.refresh(true)
        assertEquals(listOf(null, a), api.cursors); assertEquals(b, c.state.value.people.single().id)
    }
    @Test fun nonAdvancingCursorAndDuplicatePeopleFailClosed() = runBlocking {
        val store = Store(); val api = Api(store); val c = controller(Env(api), store)
        api.page = SharedTimePeople(emptyList(), b); c.refresh(); c.refresh(true)
        assertFalse(c.state.value.ready)
        api.page = SharedTimePeople(listOf(person(), person()), null); c.refresh(); assertFalse(c.state.value.ready)
    }
    @Test fun lostResponseSurvivesNewControllerAndExplicitSameActorReloginOnly() = runBlocking {
        val store = Store(); val api = Api(store); val env = Env(api); val c = controller(env, store)
        api.inHook = { throw IOException("lost") }
        c.refresh(); c.select(b); c.confirm("first-password", "")
        val key = store.value!!.intent.key
        c.refresh(); assertEquals(1, api.keys.size)
        env.proof = "b".repeat(64)
        val restored = controller(env, store); restored.refresh(); assertEquals(key, restored.state.value.pending?.intent?.key)
        api.inHook = null; restored.retry("fresh-password")
        assertEquals(listOf(key, key), api.keys); assertEquals("fresh-password", api.passwordSeen); assertNull(store.value)
    }
    @Test fun later401CannotTurnUnknownIntoDiscardableRejection() = runBlocking {
        val store = Store(); val api = Api(store); val c = controller(Env(api), store)
        api.inHook = { throw IOException("lost") }; c.refresh(); c.select(b); c.confirm("password", "")
        api.inHook = { throw ApiException("bad credentials", 401) }; c.refresh(); c.retry("bad")
        assertNotNull(store.value); assertNull(store.value!!.rejectedStatus)
        c.acknowledgeRejected(); assertEquals(0, store.archived)
    }
    @Test fun firstDefinitiveRejectionRequiresExplicitAcknowledgementAndRetainsEvidence() = runBlocking {
        val store = Store(); val api = Api(store); val c = controller(Env(api), store)
        api.inHook = { throw ApiException("bad", 401) }; c.refresh(); c.select(b); c.confirm("bad", "")
        assertEquals(401, store.value?.rejectedStatus)
        c.acknowledgeRejected(); assertEquals(1, store.archived); assertNull(store.value)
    }
    @Test fun offlineOrMissingLivePermissionCannotReadOrDispatchAndNeverQueues() = runBlocking {
        val store = Store(); val api = Api(store); val env = Env(api); val c = controller(env, store)
        env.canClock = false; c.refresh(); assertTrue(api.cursors.isEmpty())
        env.canClock = true; env.connected = false; c.refresh(); c.select(b); c.confirm("password", "")
        assertTrue(api.keys.isEmpty()); assertNull(store.value)
    }
    @Test fun saveFailureAndMissingActualBreakNeverReachTransport() = runBlocking {
        val store = Store(); val api = Api(store); val c = controller(Env(api), store)
        api.page = SharedTimePeople(listOf(person(true)), null)
        c.refresh(); c.select(b); c.confirm("password", "")
        assertNull(store.value); assertTrue(api.keys.isEmpty())
        store.failSave = true; c.confirm("password", "0"); assertTrue(api.keys.isEmpty())
    }
    @Test fun clockOutBindsExactAttendanceAndConfirmedActualBreak() = runBlocking {
        val store = Store(); val api = Api(store); val c = controller(Env(api), store)
        api.page = SharedTimePeople(listOf(person(true)), null)
        c.refresh(); c.select(b); c.confirm("password", "30")
        assertNull(store.value); assertEquals(1, store.completed)
    }
    @Test fun wrongReceiptCannotEraseOriginalRequest() = runBlocking {
        val store = Store(); val api = Api(store); val c = controller(Env(api), store)
        api.inHook = { receipt().copy(userId = a) }
        c.refresh(); c.select(b); c.confirm("password", "")
        assertNotNull(store.value); assertEquals(0, store.completed)
    }
    @Test fun changedWorkspaceOrActorCannotReplayRetainedIntent() = runBlocking {
        val store = Store(); val api = Api(store); val env = Env(api); val c = controller(env, store)
        api.inHook = { throw IOException("lost") }; c.refresh(); c.select(b); c.confirm("password", "")
        env.actor = b; c.refresh(); c.retry("password"); assertEquals(1, api.keys.size)
        env.actor = a; env.branchId = a; c.refresh(); c.retry("password"); assertEquals(1, api.keys.size)
        assertNotNull(store.value)
    }
    @Test fun doubleConfirmAndInvalidationDuringRequestCannotPublishOrClearLateReceipt() = runBlocking {
        val store = Store(); val api = Api(store); val env = Env(api); val c = controller(env, store)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        api.inHook = { entered.complete(Unit); release.await(); receipt() }
        c.refresh(); c.select(b)
        val work = async { c.confirm("password", "") }; entered.await(); c.confirm("password", "")
        env.generation++; c.invalidate(); release.complete(Unit); work.await()
        assertEquals(1, api.keys.size); assertNotNull(store.value); assertNull(c.state.value.notice)
    }
    @Test fun credentialDtoStringsAreRedactedAndPendingFieldsRejectPasswordInjection() {
        assertFalse(SharedTimeClockIn(b, "secret").toString().contains("secret"))
        assertFalse(SharedTimeClockOut(b, "secret", attendance, 0).toString().contains("secret"))
        assertThrows(Exception::class.java) { StaffTimeJson.decodeFromString<SharedTimeFields>("""{"user_id":"$b","password":"secret"}""") }
    }
    @Test fun rejectedRetryLostReplyBecomesUnknownBeforeWireAndCannotArchiveAfterRestart() = runBlocking {
        val store = Store(); val api = Api(store); val env = Env(api); val c = controller(env, store)
        api.inHook = { throw ApiException("Denied", 401) }; c.refresh(); c.select(b); c.confirm("bad", "")
        val rejected = requireNotNull(store.value); assertEquals(401, rejected.rejectedStatus)
        c.refresh(); api.inHook = {
            assertNull(store.value?.rejectedStatus)
            throw IOException("May have committed")
        }
        c.retry("correct-password")
        assertEquals(rejected.intent, store.value?.intent); assertNull(store.value?.rejectedStatus)
        assertEquals(listOf(rejected.intent.key, rejected.intent.key), api.keys)
        val restored = controller(env, store); restored.refresh(); restored.acknowledgeRejected()
        assertNotNull(store.value); assertEquals(0, store.archived)
    }
    @Test fun rejectedRetryCancelledAfterDispatchRetainsUnknownInRecreatedController() = runBlocking {
        val store = Store(); val api = Api(store); val env = Env(api); val c = controller(env, store)
        api.inHook = { throw ApiException("Denied", 401) }; c.refresh(); c.select(b); c.confirm("bad", "")
        val intent = store.value!!.intent
        c.refresh(); api.inHook = { throw kotlinx.coroutines.CancellationException("Process lost") }
        assertTrue(runCatching { c.retry("correct-password") }.exceptionOrNull() is kotlinx.coroutines.CancellationException)
        assertEquals(intent, store.value?.intent); assertNull(store.value?.rejectedStatus)
        val restored = controller(env, store); restored.refresh(); restored.acknowledgeRejected()
        assertEquals(0, store.archived)
    }
    @Test fun retryTransitionStorageFailureNeverResendsAndNewDefiniteDenialCanBeRetained() = runBlocking {
        val store = Store(); val api = Api(store); val c = controller(Env(api), store)
        api.inHook = { throw ApiException("Denied", 401) }; c.refresh(); c.select(b); c.confirm("bad", "")
        c.refresh(); store.failPrepare = true; c.retry("correct-password")
        assertEquals(1, api.keys.size); assertEquals(401, store.value?.rejectedStatus)
        store.failPrepare = false; c.retry("still-wrong")
        assertEquals(2, api.keys.size); assertEquals(401, store.value?.rejectedStatus)
    }
    @Test fun offlineDuringPausedRequestRetainsUnknownAndAllowsExplicitRefreshAfterDrain() = runBlocking {
        val store = Store(); val api = Api(store); val env = Env(api); val c = controller(env, store)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        api.inHook = { entered.complete(Unit); release.await(); receipt() }
        c.refresh(); c.select(b)
        val work = async { c.confirm("password", "") }; entered.await()
        val intent = store.value!!.intent; env.connected = false; c.offline()
        assertFalse(c.state.value.busy); assertFalse(c.state.value.ready)
        env.connected = true; c.refresh(); c.retry("password")
        assertEquals(1, api.keys.size) // Existing full operation still owns the mutex.
        release.complete(Unit); work.await()
        assertEquals(intent, store.value?.intent); assertFalse(c.state.value.busy)
        c.refresh(); assertTrue(c.state.value.ready); assertEquals(intent, c.state.value.pending?.intent)
        assertEquals(1, api.keys.size) // Reconnection is a directory read, never replay.
        api.inHook = null; c.retry("fresh-password")
        assertEquals(2, api.keys.size); assertNull(store.value)
    }

}
