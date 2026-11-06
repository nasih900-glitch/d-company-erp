package cloud.dcompany.erp.core.remote

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

/** Fault model of API35 memory-first commit: false can still change the in-process getter. */
internal class MemoryFirstRemotePreferences {
    var memory: String? = null
    var disk: String? = null
    var succeeds = true
    var throwsBeforeMemory = false
    private var staged: String? = null
    private lateinit var editor: SharedPreferences.Editor
    val value: SharedPreferences
    init {
        editor = Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader, arrayOf(SharedPreferences.Editor::class.java)) { _, method, args ->
            when (method.name) {
                "putString" -> { staged = args!![1] as String?; editor }
                "commit" -> { if (throwsBeforeMemory) throw IllegalStateException("synthetic commit unavailable"); memory = staged; if (succeeds) disk = staged; succeeds }
                "toString" -> "SyntheticMemoryFirstEditor"
                else -> error("Unexpected editor operation")
            }
        } as SharedPreferences.Editor
        value = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader, arrayOf(SharedPreferences::class.java)) { proxy, method, args ->
            when (method.name) {
                "getString" -> memory ?: args!![1]
                "edit" -> editor
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args!![0]
                "toString" -> "SyntheticMemoryFirstPreferences"
                else -> error("Unexpected preference operation")
            }
        } as SharedPreferences
    }
}
class RemotePendingStoreDurabilityTest {
    private val scope = RemoteAssistanceJournalScope("11111111-1111-4111-8111-111111111111",
        "22222222-2222-4222-8222-222222222222", "33333333-3333-4333-8333-333333333333")
    private val grant = "44444444-4444-4444-8444-444444444444"
    private val id = "55555555-5555-4555-8555-555555555555"
    private val expiry = Instant.parse("2030-11-05T10:10:00Z")
    @Test fun eachReplayRecordMustRecommitEvenWhenFailedCommitAlreadyChangedMemory() {
        for (kind in RemotePendingKind.entries) {
            val p = MemoryFirstRemotePreferences(); val store = RemoteAssistanceStore(p.value, { scope }, { id })
            if (kind == RemotePendingKind.REVOKE) assertNotNull(store.recordDecision(grant, true, RemoteGrantKind.ONE_TIME, expiry))
            p.succeeds = false
            fun record(): Any? = when (kind) {
                RemotePendingKind.DECISION -> store.recordDecision(grant, true, RemoteGrantKind.ONE_TIME, expiry)
                RemotePendingKind.REVOKE -> store.recordRevocation()
                RemotePendingKind.END -> store.recordSessionEnd(grant, "user_ended")
            }
            assertNull(record()); val memory = p.memory
            assertNotEquals(memory, p.disk); assertNull(record()); assertEquals(memory, p.memory)
            assertFalse(store.inspectHandoverWork().verified)
            p.succeeds = true
            assertNotNull(record()); assertEquals(memory, p.disk)
            assertTrue(store.inspectHandoverWork().verified)
        }
    }
    @Test fun throwingCommitBeforeMemoryStillRetainsExactAttemptAndAllowsAnExplicitDurableRetry() {
        val p = MemoryFirstRemotePreferences(); var ids = 0
        val store = RemoteAssistanceStore(p.value, { scope }, { ids++; id })
        p.throwsBeforeMemory = true
        assertNull(store.recordDecision(grant, true, RemoteGrantKind.ONE_TIME, expiry))
        assertNull(p.memory); assertEquals(id, store.snapshot().pendingDecision?.decisionId)
        assertFalse(store.inspectHandoverWork().verified)
        p.throwsBeforeMemory = false
        assertEquals(id, store.recordDecision(grant, true, RemoteGrantKind.ONE_TIME, expiry)?.decisionId)
        assertEquals(1, ids); assertEquals(p.memory, p.disk); assertTrue(store.inspectHandoverWork().verified)
    }
    @Test fun failedRemovalRetainsOldPendingAndRefusesNewerOrForeignRawReplacement() {
        val p = MemoryFirstRemotePreferences(); val store = RemoteAssistanceStore(p.value, { scope }, { id })
        val pending = requireNotNull(store.recordDecision(grant, true, RemoteGrantKind.ONE_TIME, expiry))
        val action = RemotePendingAction.decision(pending); val before = store.snapshot(); val disk = p.disk
        val ack = RemotePendingResolution(action, RemoteResolutionKind.MATCHED_ACTION_ACK, "active")
        p.succeeds = false
        assertFalse(store.checkpointPending(scope, before, action, ack))
        assertNotEquals(p.memory, p.disk); assertEquals(disk, p.disk)
        val reopened = RemoteAssistanceStore(p.value, { scope })
        assertEquals(before, reopened.snapshot()); assertFalse(reopened.inspectHandoverWork().verified)
        assertFalse(reopened.reassertPending(scope, before, action))
        assertEquals(0, reopened.retireExpiredInstallationGrants("2040-11-05T10:00:00Z"))
        val newer = "{newer externally changed private bytes}"
        p.memory = newer; p.succeeds = true
        assertFalse(reopened.reassertPending(scope, before, action))
        assertEquals(newer, p.memory); assertEquals(disk, p.disk)
    }
}
