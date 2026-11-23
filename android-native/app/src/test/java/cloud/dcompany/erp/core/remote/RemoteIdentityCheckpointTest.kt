package cloud.dcompany.erp.core.remote

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

class RemoteIdentityCheckpointTest {
    private class Preferences {
        var raw: String? = null; var commitSucceeds = true; var readFails = false
        private var staged: String? = null
        private lateinit var editor: SharedPreferences.Editor
        val value: SharedPreferences
        init {
            editor = Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader, arrayOf(SharedPreferences.Editor::class.java)) { _, method, args ->
                when (method.name) {
                    "putString" -> { staged = args!![1] as String?; editor }
                    "commit" -> commitSucceeds.also { if (it) raw = staged }
                    "toString" -> "SyntheticEditor"
                    else -> error("Unexpected preferences editor method")
                }
            } as SharedPreferences.Editor
            value = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader, arrayOf(SharedPreferences::class.java)) { _, method, args ->
                when (method.name) {
                    "getString" -> { if (readFails) throw IllegalStateException("synthetic-private-detail"); raw ?: args!![1] }
                    "edit" -> editor
                    "toString" -> "SyntheticPreferences"
                    else -> error("Unexpected preferences method")
                }
            } as SharedPreferences
        }
    }
    private val company = "11111111-1111-4111-8111-111111111111"
    private val installation = "22222222-2222-4222-8222-222222222222"
    private val created = PersistedRemoteDeviceIdentity(company, installation, "33333333-3333-4333-8333-333333333333", "44444444-4444-4444-8444-444444444444", "created", "a".repeat(64))
    private fun active() = requireNotNull(validatedRemoteDeviceStatus(created,
        RemoteDeviceKeyStatusResponse("2026-11-05T10:02:00Z", created.keyId, installation, "active", created.fingerprintSha256,
            "2026-11-05T10:00:00Z", "2026-11-05T10:10:00Z", "2026-11-05T10:01:00Z")))
    @Test fun actualStoreExactExpectedStatePromotesAndCannotReplayOldSnapshot() {
        val p = Preferences(); val store = RemoteDeviceIdentityStore(p.value); assertTrue(store.put(created))
        val expected = requireNotNull(store.expectedState(created)); assertEquals("ExpectedRemoteDeviceState(<redacted>)", expected.toString())
        assertTrue(store.compareAndSet(expected, active())); val committed = p.raw
        assertFalse(store.compareAndSet(expected, active())); assertEquals(committed, p.raw)
        assertEquals(active(), store.activeIdentity(company, installation))
    }
    @Test fun actualStoreReplacementOrForeignIdentityCannotBeOverwritten() {
        val p = Preferences(); val store = RemoteDeviceIdentityStore(p.value); assertTrue(store.put(created))
        val expected = requireNotNull(store.expectedState(created)); val replacement = created.copy(enrollmentId = "55555555-5555-4555-8555-555555555555")
        assertTrue(RemoteDeviceIdentityStore(p.value).put(replacement)); val bytes = p.raw
        assertFalse(store.compareAndSet(expected, active())); assertEquals(bytes, p.raw)
        val fresh = requireNotNull(store.expectedState(replacement))
        assertFalse(store.compareAndSet(fresh, active())); assertEquals(bytes, p.raw)
        assertFalse(store.compareAndSet(fresh, active().copy(companyId = replacement.enrollmentId))); assertEquals(bytes, p.raw)
    }
    @Test fun failedCommitOrMalformedChangedBytesRemainHeldByteExact() {
        val p = Preferences(); val store = RemoteDeviceIdentityStore(p.value); assertTrue(store.put(created))
        val expected = requireNotNull(store.expectedState(created)); val bytes = p.raw
        p.commitSucceeds = false; assertFalse(store.compareAndSet(expected, active())); assertEquals(bytes, p.raw)
        p.commitSucceeds = true; p.raw = "{malformed"; val bad = p.raw
        assertFalse(store.compareAndSet(expected, active())); assertThrows(RemoteDeviceIdentityUnavailable::class.java) { store.expectedState(created) }; assertEquals(bad, p.raw)
    }
    @Test fun invalidPresentStateCannotLookEmptyOrBeReplacedByOrdinaryCreatePutPromote() {
        for (bad in listOf("", " ", "{malformed", "{\"format\":99}")) {
            val p = Preferences(); p.raw = bad; val store = RemoteDeviceIdentityStore(p.value)
            val read = assertThrows(RemoteDeviceIdentityUnavailable::class.java) { store.identities(company, installation) }
            assertNull(read.cause); assertFalse(read.message.orEmpty().contains(bad).takeIf { bad.isNotBlank() } ?: false)
            assertThrows(RemoteDeviceIdentityUnavailable::class.java) { store.put(created) }
            assertThrows(RemoteDeviceIdentityUnavailable::class.java) { store.promote(active()) }
            assertEquals(bad, p.raw)
        }
    }
    @Test fun missingOrValidEmptyStateIsLegitimateButReadFailureIsSanitized() {
        val p = Preferences(); val store = RemoteDeviceIdentityStore(p.value)
        assertTrue(store.identities(company, installation).isEmpty())
        p.raw = "{\"format\":1,\"identities\":[],\"retiredKeyIds\":[]}"
        assertTrue(store.put(created)); val before = p.raw
        p.readFails = true
        val failure = assertThrows(RemoteDeviceIdentityUnavailable::class.java) { store.enrollmentCandidate(company, installation) }
        assertNull(failure.cause); assertFalse(failure.message.orEmpty().contains("synthetic-private-detail")); assertEquals(before, p.raw)
    }

    @Test fun promotionRetiresOnlyExpectedSupersededAliasesAndKeepsOtherInstallation() {
        val p = Preferences(); val store = RemoteDeviceIdentityStore(p.value)
        val previousActive = active().copy(keyId = "55555555-5555-4555-8555-555555555555", enrollmentId = "66666666-6666-4666-8666-666666666666")
        val elsewhere = created.copy(installationId = "77777777-7777-4777-8777-777777777777", keyId = "88888888-8888-4888-8888-888888888888", enrollmentId = "99999999-9999-4999-8999-999999999999")
        assertTrue(store.put(previousActive)); assertTrue(store.put(created)); assertTrue(store.put(elsewhere))
        val expected = requireNotNull(store.expectedState(created)); assertTrue(store.compareAndSet(expected, active()))
        assertEquals(listOf(previousActive.keyId), store.retiredKeyIds()); assertEquals(elsewhere, store.enrollmentCandidate(company, elsewhere.installationId))
    }
}
