package cloud.dcompany.erp.core.remote

import org.junit.Assert.*
import org.junit.Test

class RemoteHandoverReadinessTest {
    private val receipt = RemoteCommandReceipt("11111111-1111-4111-8111-111111111111",
        "22222222-2222-4222-8222-222222222222", 1, RECEIPT_COMPLETED, "acknowledged")
    @Test fun retainedAcknowledgedAuditIsClearWithoutChangingTheHistory() {
        val state = PersistedRemoteAssistanceState(lastAcknowledgedSequence = 1, receipts = listOf(receipt))
        assertNotNull(validRemoteAssistanceState(state))
        assertFalse(hasPendingRemoteHandoverWork(state))
        assertEquals(listOf(receipt), state.receipts)
    }
    @Test fun reservedReceiptAtOrBeforeAcknowledgedCursorStillBlocks() {
        val reserved = receipt.copy(state = RECEIPT_RESERVED, outcome = null)
        val state = PersistedRemoteAssistanceState(lastAcknowledgedSequence = 1, receipts = listOf(reserved))
        assertNotNull(validRemoteAssistanceState(state))
        assertTrue(hasPendingRemoteHandoverWork(state))
        assertEquals(listOf(reserved), state.receipts)
    }
    @Test fun unacknowledgedReceiptAndLiveSessionEachBlock() {
        assertTrue(hasPendingRemoteHandoverWork(PersistedRemoteAssistanceState(receipts = listOf(receipt))))
        assertTrue(hasPendingRemoteHandoverWork(PersistedRemoteAssistanceState(commandSessionId = receipt.sessionId)))
    }
    @Test fun allowedConsentAndUnacknowledgedMutationsNeverLookClean() {
        assertTrue(hasPendingRemoteHandoverWork(PersistedRemoteAssistanceState(consent = RemoteConsentChoice.ALLOWED.storedValue)))
        assertTrue(hasPendingRemoteHandoverWork(PersistedRemoteAssistanceState(pendingSessionEnd =
            PendingRemoteSessionEnd(receipt.sessionId, receipt.commandId, "capture_stopped"))))
    }
    @Test fun unreadableStorageCannotBecomeVerifiedEmpty() {
        assertFalse(RemoteJournalReadiness(verified = false).isClear)
        assertTrue(RemoteJournalReadiness(verified = true).isClear)
        assertFalse(RemoteJournalReadiness(verified = true, pending = true).isClear)
    }
}
