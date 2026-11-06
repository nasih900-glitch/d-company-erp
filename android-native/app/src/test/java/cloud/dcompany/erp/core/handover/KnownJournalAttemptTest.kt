package cloud.dcompany.erp.core.handover

import org.junit.Assert.*
import org.junit.Test

class KnownJournalAttemptTest {
    private val identity = KnownJournalAttempt(KnownJournalDomain.SHARED_ATTENDANCE,
        HandoverScope("11111111-1111-4111-8111-111111111111", "22222222-2222-4222-8222-222222222222", null, null),
        "stored-key", "stored-target", "stored-kind", "a".repeat(64))
    @Test fun identityIsImmutableComparableAndDebugOutputIsRedacted() {
        identity.validate(); assertEquals(identity, identity.copy())
        assertEquals("KnownJournalAttempt(<redacted>)", identity.toString())
        assertFalse(identity.toString().contains(identity.actionKey)); assertFalse(identity.toString().contains(identity.storedIntentSha256))
    }
    @Test fun unboundedMalformedOrUnscopedIdentityCannotBeAdmitted() {
        for (bad in listOf(identity.copy(actionKey = ""), identity.copy(kind = "bad kind"), identity.copy(target = "x".repeat(201)),
            identity.copy(storedIntentSha256 = "A".repeat(64)), identity.copy(scope = identity.scope.copy(actorId = "invalid")))) {
            assertThrows(IllegalArgumentException::class.java) { bad.validate() }
        }
    }
}
