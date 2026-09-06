package cloud.dcompany.erp.core.remote

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RemoteAssistanceStoreDeviceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private var activeScope: RemoteAssistanceJournalScope? = SCOPE_A

    @Before
    fun clearBefore() {
        activeScope = SCOPE_A
        clearStore()
    }

    @After
    fun clearAfter() = clearStore()

    @Test
    fun sameGrantReplaysMutationButNewGrantRequiresFreshDecision() {
        val ids = ArrayDeque(listOf(DECISION_ONE, DECISION_TWO))
        val store = newStore { ids.removeFirst() }

        val first = store.recordDecision(
            GRANT_ONE,
            allow = true,
            grantKind = RemoteGrantKind.ONE_TIME,
            grantExpiresAt = GRANT_EXPIRY,
        )

        assertEquals(RemoteGrantDecisionWire.ACCEPTED, first?.decision)
        assertEquals(first, store.pendingDecisionForGrant(GRANT_ONE))
        assertNull(store.pendingDecisionForGrant(GRANT_TWO))
        assertNull("An unacknowledged mutation must never be overwritten", run {
            store.recordDecision(
                GRANT_TWO,
                allow = false,
                grantKind = RemoteGrantKind.ANYTIME,
                grantExpiresAt = GRANT_EXPIRY,
            )
        })
        assertTrue(store.acknowledgeDecision(GRANT_ONE, requireNotNull(first).decisionId))

        val second = store.recordDecision(
            GRANT_TWO,
            allow = false,
            grantKind = RemoteGrantKind.ANYTIME,
            grantExpiresAt = GRANT_EXPIRY,
        )
        assertEquals(RemoteGrantDecisionWire.DECLINED, second?.decision)
        assertNotEquals(first?.decisionId, second?.decisionId)
        assertEquals(second, newStore().pendingDecisionForGrant(GRANT_TWO))
    }

    @Test
    fun commandReservationExecutesOnceAndInterruptedWorkRecoversAsRejected() {
        val store = newStore()
        assertTrue(store.activateCommandSession(SESSION_ID))

        val reserved = store.reserveCommand(SESSION_ID, COMMAND_ONE, sequence = 1L)
        assertEquals(RECEIPT_RESERVED, reserved?.state)
        assertEquals(reserved, store.reserveCommand(SESSION_ID, COMMAND_ONE, sequence = 1L))
        val completed = store.completeCommand(COMMAND_ONE, outcome = "acknowledged")
        assertEquals(RECEIPT_COMPLETED, completed?.state)
        assertEquals(completed, store.reserveCommand(SESSION_ID, COMMAND_ONE, sequence = 1L))
        assertTrue(store.acknowledgeCommand(COMMAND_ONE))
        assertEquals(1L, newStore().afterSequence(SESSION_ID, initialPoll = false))

        assertNotNull(store.reserveCommand(SESSION_ID, COMMAND_TWO, sequence = 2L))
        val recovered = newStore().recoverInterruptedCommand(COMMAND_TWO)
        assertEquals("rejected", recovered?.outcome)
        assertEquals("execution_failed", recovered?.reasonCode)
    }

    @Test
    fun oneTimeGrantPersistsItsFirstSessionAndRejectsReuseOrExpiry() {
        val store = newStore { DECISION_ONE }
        requireNotNull(
            store.recordDecision(
                GRANT_ONE,
                allow = true,
                grantKind = RemoteGrantKind.ONE_TIME,
                grantExpiresAt = GRANT_EXPIRY,
            ),
        )

        assertTrue(store.authorizeSession(GRANT_ONE, SESSION_ID, SERVER_TIME))
        assertEquals(SESSION_ID, newStore().snapshot().oneTimeSessionId)
        assertTrue(newStore().authorizeSession(GRANT_ONE, SESSION_ID, SERVER_TIME))
        assertFalse(
            newStore().authorizeSession(
                GRANT_ONE,
                OTHER_SESSION_ID,
                SERVER_TIME,
            ),
        )
        assertTrue(
            store.authorizeSession(GRANT_ONE, SESSION_ID, GRANT_EXPIRY.toString()),
        )
    }

    @Test
    fun sequenceJournalRejectsGapsAndMismatchedCommandReuse() {
        val store = newStore()
        assertTrue(store.activateCommandSession(SESSION_ID))

        assertNull(store.reserveCommand(SESSION_ID, COMMAND_TWO, sequence = 2L))
        assertNotNull(store.reserveCommand(SESSION_ID, COMMAND_ONE, sequence = 1L))
        assertNull(store.reserveCommand(OTHER_SESSION_ID, COMMAND_ONE, sequence = 1L))
        assertNull(store.reserveCommand(SESSION_ID, COMMAND_ONE, sequence = 2L))
        assertNotNull(store.completeCommand(COMMAND_ONE, outcome = "acknowledged"))
        assertTrue(store.acknowledgeCommand(COMMAND_ONE))
        assertFalse(store.acknowledgeCommand(COMMAND_ONE))
    }

    @Test
    fun completedReceiptSurvivesResponseLossUntilItsContiguousAck() {
        val store = newStore()
        assertTrue(store.activateCommandSession(SESSION_ID))
        assertNotNull(store.reserveCommand(SESSION_ID, COMMAND_ONE, sequence = 1L))
        val completed = requireNotNull(
            store.completeCommand(COMMAND_ONE, outcome = "acknowledged"),
        )

        assertEquals(
            completed,
            newStore().completedReceiptAwaitingAck(SESSION_ID),
        )
        assertTrue(newStore().acknowledgeCommand(COMMAND_ONE))
        assertNull(newStore().completedReceiptAwaitingAck(SESSION_ID))
    }

    @Test
    fun revokeAndSessionEndRemainJournaledAcrossProcessRecreation() {
        val ids = ArrayDeque(listOf(DECISION_ONE, REVOCATION_ID, END_ID))
        val store = newStore { ids.removeFirst() }
        val decision = requireNotNull(
            store.recordDecision(
                GRANT_ONE,
                allow = true,
                grantKind = RemoteGrantKind.ANYTIME,
                grantExpiresAt = GRANT_EXPIRY,
            ),
        )
        assertTrue(store.acknowledgeDecision(GRANT_ONE, decision.decisionId))

        val revoke = store.recordRevocation()
        val end = store.recordSessionEnd(SESSION_ID, "app_backgrounded")
        val restored = newStore().snapshot()

        assertEquals(RemoteConsentChoice.REVOKED, restored.consentChoice)
        assertEquals(revoke, restored.pendingRevocation)
        assertEquals(end, restored.pendingSessionEnd)
        assertEquals("app_backgrounded", restored.pendingSessionEnd?.reason)
    }

    @Test
    fun logoutAndDifferentUserCannotSeeAuthorizeOrReplayAnotherUsersJournal() {
        val ids = ArrayDeque(listOf(DECISION_ONE, END_ID, REVOCATION_ID))
        val store = newStore { ids.removeFirst() }
        val decision = requireNotNull(
            store.recordDecision(
                GRANT_ONE,
                allow = true,
                grantKind = RemoteGrantKind.ANYTIME,
                grantExpiresAt = GRANT_EXPIRY,
            ),
        )

        activeScope = SCOPE_B
        assertEquals(RemoteConsentChoice.UNDECIDED, store.snapshot().consentChoice)
        assertNull(store.pendingDecisionForGrant(GRANT_ONE))
        assertFalse(store.authorizeSession(GRANT_ONE, SESSION_ID, SERVER_TIME))
        assertNull(store.recordRevocation())
        assertEquals(0L, store.afterSequence(SESSION_ID, initialPoll = false))

        activeScope = SCOPE_A
        assertEquals(decision, store.pendingDecisionForGrant(GRANT_ONE))
        assertTrue(store.acknowledgeDecision(GRANT_ONE, decision.decisionId))
        assertTrue(store.authorizeSession(GRANT_ONE, SESSION_ID, SERVER_TIME))
        assertTrue(store.activateCommandSession(SESSION_ID))
        assertNotNull(store.reserveCommand(SESSION_ID, COMMAND_ONE, sequence = 1L))
        assertNotNull(store.completeCommand(COMMAND_ONE, outcome = "acknowledged"))
        val end = requireNotNull(store.recordSessionEnd(SESSION_ID, "app_backgrounded"))
        val revoke = requireNotNull(store.recordRevocation())

        activeScope = SCOPE_B
        val userB = store.snapshot()
        assertEquals(RemoteConsentChoice.UNDECIDED, userB.consentChoice)
        assertNull(userB.pendingDecision)
        assertNull(userB.pendingRevocation)
        assertNull(userB.pendingSessionEnd)
        assertNull(store.commandReceipt(COMMAND_ONE))
        assertFalse(store.acknowledgeSessionEnd(end.sessionId, end.endId))
        assertFalse(store.acknowledgeRevocation(revoke.grantId, revoke.revocationId))

        activeScope = SCOPE_A
        val restoredA = newStore().snapshot()
        assertEquals(RemoteConsentChoice.REVOKED, restoredA.consentChoice)
        assertEquals(revoke, restoredA.pendingRevocation)
        assertEquals(end, restoredA.pendingSessionEnd)
        assertNotNull(newStore().commandReceipt(COMMAND_ONE))
    }

    @Test
    fun scopeLossJournalsEndAndSameUserReloginCannotResumeWhileOtherUserCannotReplay() {
        val ids = ArrayDeque(listOf(DECISION_ONE, END_ID))
        val store = newStore { ids.removeFirst() }
        val decision = requireNotNull(
            store.recordDecision(
                GRANT_ONE,
                allow = true,
                grantKind = RemoteGrantKind.ANYTIME,
                grantExpiresAt = GRANT_EXPIRY,
            ),
        )
        assertTrue(store.acknowledgeDecision(GRANT_ONE, decision.decisionId))
        assertTrue(store.authorizeSession(GRANT_ONE, SESSION_ID, SERVER_TIME))

        // This mirrors the coordinator's synchronous write against its retained
        // A journal scope even when CacheIsolation has already revoked A's lease.
        val end = requireNotNull(store.recordSessionEnd(SESSION_ID, "capture_stopped"))
        activeScope = null
        assertEquals(RemoteConsentChoice.UNDECIDED, store.snapshot().consentChoice)
        assertFalse(store.authorizeSession(GRANT_ONE, SESSION_ID, SERVER_TIME))

        activeScope = SCOPE_B
        assertNull(store.snapshot().pendingSessionEnd)
        assertFalse(store.acknowledgeSessionEnd(end.sessionId, end.endId))
        assertFalse(store.authorizeSession(GRANT_ONE, SESSION_ID, SERVER_TIME))

        activeScope = SCOPE_A
        assertEquals(end, newStore().snapshot().pendingSessionEnd)
        assertFalse(
            "A must reconcile its durable Stop before a repeated active session can resume",
            newStore().authorizeSession(GRANT_ONE, SESSION_ID, SERVER_TIME),
        )
        assertTrue(newStore().acknowledgeSessionEnd(end.sessionId, end.endId))
        assertNull(newStore().snapshot().pendingSessionEnd)
    }

    @Test
    fun terminalMutationReconciliationUnblocksOnlyTheOriginalUsersNextGrant() {
        val ids = ArrayDeque(listOf(DECISION_ONE, DECISION_TWO))
        val store = newStore { ids.removeFirst() }
        val offlineDecision = requireNotNull(
            store.recordDecision(
                GRANT_ONE,
                allow = true,
                grantKind = RemoteGrantKind.ONE_TIME,
                grantExpiresAt = GRANT_EXPIRY,
            ),
        )
        assertNull(
            "An offline unresolved decision must keep its immutable action ID",
            store.recordDecision(
                GRANT_TWO,
                allow = false,
                grantKind = RemoteGrantKind.ONE_TIME,
                grantExpiresAt = GRANT_EXPIRY,
            ),
        )

        activeScope = SCOPE_B
        assertFalse(store.acknowledgeDecision(GRANT_ONE, offlineDecision.decisionId))
        assertNull(store.pendingDecisionForGrant(GRANT_ONE))

        activeScope = SCOPE_A
        // The transport retires this only after the backend proves the exact
        // grant terminal with 404/410 (for example expiry or owner revoke first).
        assertTrue(store.acknowledgeDecision(GRANT_ONE, offlineDecision.decisionId))
        val freshDecision = requireNotNull(
            store.recordDecision(
                GRANT_TWO,
                allow = false,
                grantKind = RemoteGrantKind.ONE_TIME,
                grantExpiresAt = GRANT_EXPIRY,
            ),
        )
        assertEquals(DECISION_TWO, freshDecision.decisionId)
    }

    @Test
    fun legacyUnscopedPreferenceNeverAuthorizesAUser() {
        context.getSharedPreferences("dcompany_remote_assistance", Context.MODE_PRIVATE)
            .edit()
            .putString(
                "state",
                """{"format":2,"consent":"allowed","grantId":"$GRANT_ONE","grantKind":"anytime","grantExpiresAt":"${GRANT_EXPIRY}"}""",
            )
            .commit()

        val store = newStore()
        assertEquals(RemoteConsentChoice.UNDECIDED, store.snapshot().consentChoice)
        assertFalse(store.authorizeSession(GRANT_ONE, SESSION_ID, SERVER_TIME))
        assertNull(store.pendingDecisionForGrant(GRANT_ONE))
    }

    @Test
    fun seventeenthUserEvictsOnlyCompletedScopeAndNeverAnUnresolvedJournal() {
        val store = newStore()
        val unresolvedA = requireNotNull(
            store.recordDecision(
                GRANT_ONE,
                allow = true,
                grantKind = RemoteGrantKind.ANYTIME,
                grantExpiresAt = GRANT_EXPIRY,
            ),
        )

        // A plus sixteen additional employees exceeds the bounded journal set.
        // A remains because its response-loss mutation is unresolved; the
        // oldest completed journal is deterministically evicted instead.
        repeat(16) { index ->
            activeScope = employeeScope(index)
            val grantId = canonicalUuid(index + 100)
            val decision = requireNotNull(
                store.recordDecision(
                    grantId,
                    allow = false,
                    grantKind = RemoteGrantKind.ONE_TIME,
                    grantExpiresAt = GRANT_EXPIRY,
                ),
            )
            assertTrue(store.acknowledgeDecision(grantId, decision.decisionId))
        }

        activeScope = SCOPE_A
        assertEquals(unresolvedA, newStore().pendingDecisionForGrant(GRANT_ONE))
        activeScope = employeeScope(15)
        assertEquals(RemoteConsentChoice.DENIED, newStore().snapshot().consentChoice)
    }

    @Test
    fun seventeenthUserFailsClosedUntilTrustedServerTimeExpiresDepartedAllowedUsers() {
        val store = newStore()
        repeat(16) { index ->
            activeScope = employeeScope(index)
            val grantId = canonicalUuid(index + 200)
            val decision = requireNotNull(
                store.recordDecision(
                    grantId,
                    allow = true,
                    grantKind = RemoteGrantKind.ANYTIME,
                    grantExpiresAt = GRANT_EXPIRY,
                ),
            )
            assertTrue(store.acknowledgeDecision(grantId, decision.decisionId))
        }

        activeScope = employeeScope(16)
        assertNull(
            "Capacity pressure must not evict a still-authorized user's revoke authority",
            store.recordDecision(
                canonicalUuid(999),
                allow = false,
                grantKind = RemoteGrantKind.ONE_TIME,
                grantExpiresAt = GRANT_EXPIRY,
            ),
        )
        assertEquals(RemoteConsentChoice.UNDECIDED, store.snapshot().consentChoice)

        assertEquals(
            16,
            store.retireExpiredInstallationGrants(GRANT_EXPIRY.toString()),
        )
        assertNotNull(
            "Trusted same-installation server expiry makes completed old scopes safely evictable",
            store.recordDecision(
                canonicalUuid(999),
                allow = false,
                grantKind = RemoteGrantKind.ONE_TIME,
                grantExpiresAt = GRANT_EXPIRY.plusSeconds(60),
            ),
        )
    }

    @Test
    fun trustedServerExpiryRetiresAllowedAuthorityOnlyWhenNoSessionOrMutationIsOpen() {
        val store = newStore { DECISION_ONE }
        val decision = requireNotNull(
            store.recordDecision(
                GRANT_ONE,
                allow = true,
                grantKind = RemoteGrantKind.ANYTIME,
                grantExpiresAt = GRANT_EXPIRY,
            ),
        )
        assertTrue(store.acknowledgeDecision(GRANT_ONE, decision.decisionId))
        assertFalse(store.retireExpiredGrant(SERVER_TIME))
        assertEquals(RemoteConsentChoice.ALLOWED, store.snapshot().consentChoice)

        assertTrue(store.activateCommandSession(SESSION_ID))
        assertFalse(store.retireExpiredGrant(GRANT_EXPIRY.toString()))
        assertTrue(store.markCommandSessionTerminal(SESSION_ID))
        assertTrue(store.retireExpiredGrant(GRANT_EXPIRY.toString()))
        assertEquals(RemoteConsentChoice.UNDECIDED, store.snapshot().consentChoice)
        assertNull(store.snapshot().grantId)
    }

    private fun newStore(
        uuid: () -> String = { java.util.UUID.randomUUID().toString() },
    ): RemoteAssistanceStore = RemoteAssistanceStore(
        context = context,
        currentScope = { activeScope },
        uuid = uuid,
    )

    private fun clearStore() {
        context.getSharedPreferences("dcompany_remote_assistance", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    private fun employeeScope(index: Int): RemoteAssistanceJournalScope = SCOPE_A.copy(
        userId = canonicalUuid(index + 1),
    )

    private fun canonicalUuid(value: Int): String = String.format(
        "%08x-0000-4000-8000-%012x",
        value,
        value,
    )

    private companion object {
        const val GRANT_ONE = "11111111-1111-4111-8111-111111111111"
        const val GRANT_TWO = "22222222-2222-4222-8222-222222222222"
        const val SESSION_ID = "33333333-3333-4333-8333-333333333333"
        const val OTHER_SESSION_ID = "33333333-3333-4333-8333-333333333334"
        const val COMMAND_ONE = "44444444-4444-4444-8444-444444444444"
        const val COMMAND_TWO = "55555555-5555-4555-8555-555555555555"
        const val DECISION_ONE = "66666666-6666-4666-8666-666666666666"
        const val DECISION_TWO = "77777777-7777-4777-8777-777777777777"
        const val REVOCATION_ID = "88888888-8888-4888-8888-888888888888"
        const val END_ID = "99999999-9999-4999-8999-999999999999"
        const val SERVER_TIME = "2026-08-30T10:01:00Z"
        val GRANT_EXPIRY = java.time.Instant.parse("2026-08-30T10:10:00Z")
        val SCOPE_A = RemoteAssistanceJournalScope(
            companyId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
            installationId = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
            userId = "cccccccc-cccc-4ccc-8ccc-cccccccccccc",
        )
        val SCOPE_B = SCOPE_A.copy(userId = "dddddddd-dddd-4ddd-8ddd-dddddddddddd")
    }
}
