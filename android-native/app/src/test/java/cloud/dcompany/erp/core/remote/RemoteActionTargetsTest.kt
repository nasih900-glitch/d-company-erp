package cloud.dcompany.erp.core.remote

import cloud.dcompany.erp.core.auth.*
import cloud.dcompany.erp.core.handover.*
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class RemoteActionTargetsTest {
    private val scope = HandoverScope("11111111-1111-4111-8111-111111111111", "22222222-2222-4222-8222-222222222222", null, null)
    private val authority = HandoverAuthority(scope, 1)
    private val process = "33333333-3333-4333-8333-333333333333"
    private val session = "44444444-4444-4444-8444-444444444444"
    private val grant = "55555555-5555-4555-8555-555555555555"
    private fun binding(current: () -> Boolean = { true }, auth: HandoverAuthority = authority) = OriginalOperationBinding(auth,
        CacheScopeLease(CacheScope(scope.actorId, scope.companyId, null, null), 1), SessionRefreshLease(StoredSession("synthetic", "synthetic"), 1), current)
    @Test fun callbacksRequireDisplayedEpochGrantSessionAndKeyAndNeverBorrowReplacement() {
        val a = binding(); val target = RemoteUiTarget(authority, RemoteGrantPrompt(grant, RemoteGrantKind.ONE_TIME, "Synthetic", Instant.EPOCH),
            RemoteActiveSession(session, grant, Instant.EPOCH, 1), process)
        val action = requireNotNull(captureRemoteUiAction(a, target, target))
        assertTrue(action.matches(a, target))
        for (different in listOf(target.copy(authority = authority.copy(epoch = 2)), target.copy(prompt = null), target.copy(session = null), target.copy(deviceKeyId = grant))) {
            assertFalse(action.matches(a, different)); assertNull(captureRemoteUiAction(a, target, different))
        }
        assertFalse(action.matches(binding(), target)); assertNull(captureRemoteUiAction(null, target, target))
        assertNull(captureRemoteUiAction(a, null, target)); assertEquals("RemoteUiActionBinding(<redacted>)", action.toString())
    }
    @Test fun oldAuthorityCannotCreateOrInvokeCallbacksAfterScopeChange() {
        var current = true; val a = binding({ current }); val target = RemoteUiTarget(authority, null, null, null)
        val action = requireNotNull(captureRemoteUiAction(a, target, target)); current = false
        assertFalse(action.matches(a, target)); assertNull(captureRemoteUiAction(a, target, target))
    }
    @Test fun revokeRequiresANonnullExactCapturedConsentGrant() {
        val a = binding()
        val withoutGrant = RemoteUiTarget(authority, null, null, null)
        val missing = requireNotNull(captureRemoteUiAction(a, withoutGrant, withoutGrant))
        assertFalse(missing.matchesConsentGrant(null))
        assertFalse(missing.matchesConsentGrant(grant))
        val target = withoutGrant.copy(consentGrantId = grant)
        val captured = requireNotNull(captureRemoteUiAction(a, target, target))
        assertFalse(captured.matchesConsentGrant(null))
        assertFalse(captured.matchesConsentGrant(session))
        assertTrue(captured.matchesConsentGrant(grant))
    }
    @Test fun notificationIdentityCannotRetargetOldAIntoNewEpochSessionOrProcess() {
        val original = RemoteStopTarget(process, authority, session, grant)
        assertEquals(original, parseRemoteStopTarget(REMOTE_STOP_ACTION, original.dataUri))
        for (other in listOf(original.copy(authority = authority.copy(epoch = 2)), original.copy(sessionId = process), original.copy(processIdentity = grant))) {
            assertNotEquals(original.dataUri, other.dataUri); assertNotEquals(original, parseRemoteStopTarget(REMOTE_STOP_ACTION, other.dataUri))
        }
        assertEquals("RemoteStopTarget(<redacted>)", original.toString())
    }
    @Test fun missingMalformedAliasedAndNoncanonicalStopTargetsRefuseWithoutGlobalFallback() {
        val original = RemoteStopTarget(process, authority, session, grant).dataUri
        for (bad in listOf(null, "", original + "?other=1", original + "/", original.replace("/1/", "/01/"), original.replace("11111111", "%31111111"))) {
            assertNull(parseRemoteStopTarget(REMOTE_STOP_ACTION, bad))
        }
        assertNull(parseRemoteStopTarget(null, original)); assertNull(parseRemoteStopTarget("other", original))
    }
}
