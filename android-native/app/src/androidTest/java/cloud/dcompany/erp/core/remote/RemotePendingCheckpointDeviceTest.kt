package cloud.dcompany.erp.core.remote

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Instant
import java.util.UUID
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real private SharedPreferences/commit, isolated from the application's actual Remote journal. */
@RunWith(AndroidJUnit4::class)
class RemotePendingCheckpointDeviceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var name: String
    private lateinit var prefs: SharedPreferences
    private val scope = RemoteAssistanceJournalScope("11111111-1111-4111-8111-111111111111",
        "22222222-2222-4222-8222-222222222222", "33333333-3333-4333-8333-333333333333")
    private var active: RemoteAssistanceJournalScope? = scope
    private val grant = "44444444-4444-4444-8444-444444444444"
    private val decisionId = "55555555-5555-4555-8555-555555555555"
    private val revokeId = "66666666-6666-4666-8666-666666666666"
    private val endId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    @Before fun setup() { name = "remote_pending_checkpoint_" + UUID.randomUUID(); prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE); active = scope }
    @After fun cleanup() { prefs.edit().clear().commit(); context.deleteSharedPreferences(name) }
    private fun store(): RemoteAssistanceStore {
        val ids = ArrayDeque(listOf(decisionId, revokeId, endId))
        return RemoteAssistanceStore(prefs, { active }, { ids.removeFirst() })
    }
    private fun pendingBoth(): RemoteAssistanceStore = store().also {
        assertNotNull(it.recordDecision(grant, true, RemoteGrantKind.ONE_TIME, Instant.parse("2030-11-05T10:10:00Z")))
        assertNotNull(it.recordRevocation())
        assertNotNull(it.snapshot().pendingDecision); assertNotNull(it.snapshot().pendingRevocation)
        assertEquals("revoked", it.snapshot().consent)
    }
    @Test fun bothCompletionOrdersPreserveTheSiblingAcrossRestartAndNeverReviveConsent() {
        for (decisionFirst in listOf(true, false)) {
            prefs.edit().clear().commit()
            val s = pendingBoth()
            val original = s.snapshot()
            val decision = RemotePendingAction.decision(requireNotNull(original.pendingDecision))
            val revoke = RemotePendingAction.revoke(requireNotNull(original.pendingRevocation))
            val first = if (decisionFirst) decision else revoke
            val second = if (decisionFirst) revoke else decision
            fun result(a: RemotePendingAction) = RemotePendingResolution(a, RemoteResolutionKind.MATCHED_ACTION_ACK,
                if (a.kind == RemotePendingKind.REVOKE) "revoked" else "active")
            assertTrue(s.checkpointPending(scope, original, first, result(first)))
            val reopened = store()
            assertNotNull(validRemoteAssistanceState(reopened.snapshot()))
            assertTrue(second.matches(reopened.snapshot()))
            assertEquals("revoked", reopened.snapshot().consent)
            assertFalse(s.checkpointPending(scope, original, second, result(second))) // stale full snapshot
            assertTrue(reopened.checkpointPending(scope, reopened.snapshot(), second, result(second)))
            assertNull(store().snapshot().pendingDecision); assertNull(store().snapshot().pendingRevocation)
            assertEquals("revoked", store().snapshot().consent)
            assertEquals(result(second), store().snapshot().lastMutationResolution)
        }
    }
    @Test fun unresolvedRevocationCannotBeReplacedByNewConsentAndUnrelatedPendingCannotBeCleared() {
        val s = pendingBoth(); val expected = s.snapshot()
        val action = RemotePendingAction.decision(requireNotNull(expected.pendingDecision))
        assertNull(s.recordDecision("77777777-7777-4777-8777-777777777777", true, RemoteGrantKind.ONE_TIME, Instant.parse("2030-11-05T10:10:00Z")))
        val end = s.recordSessionEnd("88888888-8888-4888-8888-888888888888", "user_ended")
        assertNotNull(end)
        assertFalse(s.checkpointPending(scope, expected, action, RemotePendingResolution(action, RemoteResolutionKind.MATCHED_ACTION_ACK, "active")))
        assertNotNull(s.snapshot().pendingDecision); assertNotNull(s.snapshot().pendingRevocation)
        assertEquals(end, s.snapshot().pendingSessionEnd)
        assertNull(s.snapshot().lastMutationResolution)
    }
    @Test fun malformedAndWrongScopeNeverCheckpointOrReplaceRetainedBytes() {
        val s = pendingBoth(); val expected = s.snapshot()
        val action = RemotePendingAction.decision(requireNotNull(expected.pendingDecision))
        val resolution = RemotePendingResolution(action, RemoteResolutionKind.MATCHED_ACTION_ACK, "active")
        val key = requireNotNull(prefs.all.keys.singleOrNull())
        val valid = requireNotNull(prefs.getString(key, null))
        active = scope.copy(userId = "99999999-9999-4999-8999-999999999999")
        assertFalse(s.checkpointPending(scope, expected, action, resolution))
        assertNull(s.recordDecision(grant, true, RemoteGrantKind.ONE_TIME, Instant.parse("2030-11-05T10:10:00Z"), expectedScope = scope))
        assertNull(s.recordRevocation(scope, grant))
        assertNull(s.recordSessionEnd("88888888-8888-4888-8888-888888888888", "user_ended", scope))
        assertEquals(valid, prefs.getString(key, null))
        active = scope
        val malformed = "{retained malformed action"
        assertTrue(prefs.edit().putString(key, malformed).commit())
        assertFalse(s.inspectHandoverWork().verified)
        assertFalse(s.checkpointPending(scope, expected, action, resolution))
        assertNull(s.recordDecision(grant, true, RemoteGrantKind.ONE_TIME, Instant.parse("2030-11-05T10:10:00Z")))
        assertEquals(malformed, prefs.getString(key, null))
    }
    @Test fun supersededReceiptSurvivesRestartWithoutClaimingAnActionAckOrGrantingAuthority() {
        val s = store()
        val d = requireNotNull(s.recordDecision(grant, true, RemoteGrantKind.ONE_TIME, Instant.parse("2030-11-05T10:10:00Z")))
        val action = RemotePendingAction.decision(d)
        val superseded = RemotePendingResolution(action, RemoteResolutionKind.TERMINAL_SUPERSEDED, "expired")
        assertTrue(s.checkpointPending(scope, s.snapshot(), action, superseded))
        val restored = store().snapshot()
        assertEquals(superseded, restored.lastMutationResolution)
        assertNull(restored.pendingDecision)
        assertEquals("revoked", restored.consent)
        assertFalse(s.authorizeSession(grant, "88888888-8888-4888-8888-888888888888", "2026-11-05T10:00:00Z"))
    }
}
