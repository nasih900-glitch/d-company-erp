package cloud.dcompany.erp.core.remote

import cloud.dcompany.erp.core.auth.*
import cloud.dcompany.erp.core.handover.*
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class RemoteLocalSharingGuardTest {
    private val actor = "11111111-1111-4111-8111-111111111111"
    private val company = "22222222-2222-4222-8222-222222222222"
    private val grant = "33333333-3333-4333-8333-333333333333"
    private val session = "44444444-4444-4444-8444-444444444444"
    private val other = "55555555-5555-4555-8555-555555555555"
    private val decision = "66666666-6666-4666-8666-666666666666"
    private fun origin(epoch: Long = 1) = OriginalOperationBinding(HandoverAuthority(HandoverScope(actor, company, null, null), epoch),
        CacheScopeLease(CacheScope(actor, company, null, null), epoch), SessionRefreshLease(StoredSession("synthetic", "synthetic"), epoch)) { true }

    @Test fun firstActiveAndNullOrRequestedThenActiveNeverRenewRetainedConsentAfterRestart() {
        val a = origin()
        for (first in listOf<String?>(null, session)) {
            val process = RemoteLocalSharingGuard { it === a }
            process.observe(a, first?.let { grant }, first)
            assertFalse(process.allows(a, grant, session))
            process.observe(a, grant, session)
            assertFalse(process.allows(a, grant, session))
            assertTrue(process.resume(a, grant, session, pendingIsClear = true))
            assertTrue(process.allows(a, grant, session))
            val restarted = RemoteLocalSharingGuard { it === a }
            restarted.observe(a, grant, session)
            assertFalse(restarted.allows(a, grant, session))
        }
    }
    @Test fun freshAllowRequiresItsExactDurableDecisionAckAndReplayCannotManufactureIt() {
        val a = origin(); val guard = RemoteLocalSharingGuard { it === a }
        val action = RemotePendingAction.decision(PendingRemoteGrantDecision(grant, RemoteGrantDecisionWire.ACCEPTED, decision))
        val ack = RemotePendingResolution(action, RemoteResolutionKind.MATCHED_ACTION_ACK, "active")
        guard.observe(a, grant, session)
        assertFalse(guard.acceptFreshAllow(a, action, ack, true))
        guard.noteFreshAllow(a, grant, session, decision)
        assertFalse(guard.acceptFreshAllow(a, action.copy(actionId = other), ack, true))
        assertFalse(guard.acceptFreshAllow(a, action, ack, false))
        assertFalse(guard.acceptFreshAllow(a, action, ack.copy(kind = RemoteResolutionKind.TERMINAL_SUPERSEDED, observedStatus = "expired"), true))
        assertTrue(guard.acceptFreshAllow(a, action, ack, true))
        assertTrue(guard.allows(a, grant, session))
        assertFalse(guard.allows(a, grant, other))
    }
    @Test fun uncertainPostFailureDiscardsFreshAllowAndLaterAutomaticAckDoesNotResume() {
        val a = origin(); val guard = RemoteLocalSharingGuard { it === a }
        val action = RemotePendingAction.decision(PendingRemoteGrantDecision(grant, RemoteGrantDecisionWire.ACCEPTED, decision))
        guard.observe(a, grant, session); guard.noteFreshAllow(a, grant, session, decision)
        guard.discardFreshAllow(a, decision)
        assertFalse(guard.acceptFreshAllow(a, action, RemotePendingResolution(action, RemoteResolutionKind.MATCHED_ACTION_ACK, "active"), true))
        assertFalse(guard.allows(a, grant, session))
        assertTrue(guard.resume(a, grant, session, true))
    }
    @Test fun authorityGrantSessionAndNullReplacementInvalidateWitnessEvenIfAnOldTargetReturns() {
        val a = origin(); val b = origin(2); var current = a
        val guard = RemoteLocalSharingGuard { it === current }
        guard.observe(a, grant, session); assertTrue(guard.resume(a, grant, session, true))
        guard.observe(a, grant, other); assertFalse(guard.allows(a, grant, other))
        guard.observe(a, grant, session); assertFalse(guard.allows(a, grant, session))
        assertTrue(guard.resume(a, grant, session, true))
        guard.observe(a, other, session); assertFalse(guard.allows(a, grant, session))
        guard.observe(a, null, null); guard.observe(a, grant, session); assertFalse(guard.allows(a, grant, session))
        current = b; guard.authorityChanged(); guard.observe(b, grant, session)
        assertFalse(guard.resume(a, grant, session, true)); assertFalse(guard.allows(b, grant, session))
        assertTrue(guard.resume(b, grant, session, true)); guard.pause(a)
        assertTrue(guard.allows(b, grant, session))
    }
    @Test fun localStopRunsBeforeThrowingRecordAndExactExplicitRetryIsUsableWhileQuarantined() {
        val a = origin(); val guard = RemoteLocalSharingGuard { it === a }; val events = mutableListOf<String>()
        val action = RemoteLocalPrivacyAction(a, RemotePendingKind.END, session, "user_ended")
        guard.observe(a, grant, session); assertTrue(guard.resume(a, grant, session, true))
        val saved = guard.privacyRecord<String>(action, true, stop = { events += "stop" },
            record = { events += "record"; throw IOException("synthetic private failure") }, matches = { true })
        assertNull(saved); assertEquals(listOf("stop", "record"), events)
        assertFalse(guard.allows(a, grant, session)); assertTrue(guard.hasWriteHold())
        assertFalse(guard.resume(a, grant, session, true))
        assertEquals("user_ended", guard.heldEndReason(a, session))
        assertEquals("saved", guard.privacyRecord(action, true, stop = { events += "stop-retry" }, record = { "saved" }, matches = { it == "saved" }))
        assertFalse(guard.hasWriteHold()); assertFalse(guard.allows(a, grant, session))
        assertFalse(guard.resume(a, grant, session, false)) // durable pending End still needs ACK
        assertTrue(guard.resume(a, grant, session, true))
    }
    @Test fun ordinarySuccessOtherTargetAndAutomaticRetryCannotClearAnExactWriteHold() {
        val a = origin(); val guard = RemoteLocalSharingGuard { it === a }
        val action = RemoteLocalPrivacyAction(a, RemotePendingKind.REVOKE, grant)
        guard.observe(a, grant, session)
        guard.privacyRecord<String>(action, true, stop = {}, record = { null }, matches = { true })
        guard.privacyRecord(action, false, stop = {}, record = { "saved" }, matches = { true })
        assertTrue(guard.hasWriteHold())
        guard.privacyRecord(action.copy(targetId = other), true, stop = {}, record = { "saved" }, matches = { true })
        assertTrue(guard.hasWriteHold())
        guard.privacyRecord(action, true, stop = {}, record = { "wrong" }, matches = { false })
        assertTrue(guard.hasWriteHold())
        guard.privacyRecord(action, true, stop = {}, record = { "saved" }, matches = { true })
        assertFalse(guard.hasWriteHold()); assertFalse(guard.needsRevokeRetry(a, grant))
    }
    @Test fun staleARecordCannotStopBOrClearItsNewHoldAndMidRecordDriftCannotPublishAsB() {
        val a = origin(); val b = origin(2); var current = a; var stopped = 0
        val guard = RemoteLocalSharingGuard { it === current }
        val old = RemoteLocalPrivacyAction(a, RemotePendingKind.END, session, "user_ended")
        current = b; guard.authorityChanged(); guard.observe(b, grant, session)
        val fresh = old.copy(original = b)
        guard.privacyRecord<String>(fresh, true, stop = { stopped++ }, record = { null }, matches = { true })
        assertEquals(1, stopped)
        assertNull(guard.privacyRecord(old, true, stop = { stopped++ }, record = { "saved" }, matches = { true }))
        assertEquals(1, stopped); assertTrue(guard.hasWriteHold())
        guard.privacyRecord(fresh, true, stop = { stopped++ }, record = { current = a; "saved" }, matches = { true })
        assertTrue(guard.hasWriteHold())
        current = b
        guard.privacyRecord(fresh, true, stop = { stopped++ }, record = { "saved" }, matches = { true })
        assertFalse(guard.hasWriteHold())
    }
    @Test fun targetReplacementAndPauseDiscardUnfinishedFreshConfirmation() {
        val a = origin(); val guard = RemoteLocalSharingGuard { it === a }
        val action = RemotePendingAction.decision(PendingRemoteGrantDecision(grant, RemoteGrantDecisionWire.ACCEPTED, decision))
        val ack = RemotePendingResolution(action, RemoteResolutionKind.MATCHED_ACTION_ACK, "active")
        guard.observe(a, grant, session); guard.noteFreshAllow(a, grant, session, decision)
        guard.observe(a, grant, other); assertFalse(guard.acceptFreshAllow(a, action, ack, true))
        guard.observe(a, grant, session); guard.noteFreshAllow(a, grant, session, decision)
        guard.pause(a); assertFalse(guard.acceptFreshAllow(a, action, ack, true))
    }
}
