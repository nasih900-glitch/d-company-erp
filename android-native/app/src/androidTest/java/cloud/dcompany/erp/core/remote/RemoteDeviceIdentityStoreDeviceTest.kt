package cloud.dcompany.erp.core.remote

import android.content.Context
import android.content.SharedPreferences
import java.util.UUID
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RemoteDeviceIdentityStoreDeviceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val fixtureName = "remote_device_identity_fixture_${UUID.randomUUID()}"
    private val fixturePreferences: SharedPreferences = context.getSharedPreferences(fixtureName, Context.MODE_PRIVATE)

    @Before
    fun clearBefore() = assertTrue(fixturePreferences.all.isEmpty())

    @After
    fun clearAfter() = clear()

    @Test
    fun activeAndPendingRemainSeparateUntilAtomicPromotion() {
        val store = RemoteDeviceIdentityStore(fixturePreferences)
        val active = identity(KEY_ACTIVE, ENROLLMENT_ACTIVE, RemoteDeviceKeyStatus.ACTIVE)
        val pending = identity(KEY_PENDING, ENROLLMENT_PENDING, RemoteDeviceKeyStatus.PENDING)

        assertTrue(store.put(active))
        assertTrue(store.put(pending))
        assertEquals(KEY_ACTIVE, store.activeIdentity(COMPANY_ID, INSTALLATION_ID)?.keyId)
        assertEquals(KEY_PENDING, store.enrollmentCandidate(COMPANY_ID, INSTALLATION_ID)?.keyId)
        assertEquals(
            KEY_PENDING,
            store.identityForKey(COMPANY_ID, INSTALLATION_ID, KEY_PENDING)?.keyId,
        )

        val approved = pending.copy(
            status = RemoteDeviceKeyStatus.ACTIVE.storedValue,
            pairingCode = null,
            approvedAt = "2026-08-30T10:02:00Z",
        )
        assertTrue(store.promote(approved))

        assertEquals(KEY_PENDING, store.activeIdentity(COMPANY_ID, INSTALLATION_ID)?.keyId)
        assertNull(store.enrollmentCandidate(COMPANY_ID, INSTALLATION_ID))
        assertEquals(listOf(KEY_ACTIVE), store.retiredKeyIds())
        assertTrue(store.acknowledgeRetiredKeyDeleted(KEY_ACTIVE))
        assertTrue(store.retiredKeyIds().isEmpty())
    }

    @Test
    fun expectedCheckpointPromotesOnlyUnchangedOwnedCandidateOnDisk() {
        val store = RemoteDeviceIdentityStore(fixturePreferences)
        val pending = identity(KEY_PENDING, ENROLLMENT_PENDING, RemoteDeviceKeyStatus.PENDING)
        assertTrue(store.put(pending))
        val expected = requireNotNull(store.expectedState(pending))
        val active = pending.copy(status = "active", pairingCode = null, approvedAt = "2026-08-30T10:00:30Z")
        assertTrue(store.compareAndSet(expected, active))
        assertEquals(active, RemoteDeviceIdentityStore(fixturePreferences).activeIdentity(COMPANY_ID, INSTALLATION_ID))
        org.junit.Assert.assertFalse(store.compareAndSet(expected, active))
    }

    @Test
    fun replacementCandidateRetainedBytesCannotBeReplacedByOldCheckpoint() {
        val store = RemoteDeviceIdentityStore(fixturePreferences)
        val pending = identity(KEY_PENDING, ENROLLMENT_PENDING, RemoteDeviceKeyStatus.PENDING)
        assertTrue(store.put(pending))
        val expected = requireNotNull(store.expectedState(pending))
        assertTrue(RemoteDeviceIdentityStore(fixturePreferences).put(pending.copy(enrollmentId = ENROLLMENT_ACTIVE)))
        val prefs = fixturePreferences
        val before = prefs.getString("state", null)
        org.junit.Assert.assertFalse(store.compareAndSet(expected, pending.copy(status = "active", pairingCode = null, approvedAt = "2026-08-30T10:00:30Z")))
        assertEquals(before, prefs.getString("state", null))
    }

    @Test
    fun malformedRetainedBytesCannotBeAnEnrollmentCheckpoint() {
        val store = RemoteDeviceIdentityStore(fixturePreferences)
        val pending = identity(KEY_PENDING, ENROLLMENT_PENDING, RemoteDeviceKeyStatus.PENDING)
        assertTrue(store.put(pending)); val expected = requireNotNull(store.expectedState(pending))
        val prefs = fixturePreferences
        assertTrue(prefs.edit().putString("state", "{malformed").commit())
        org.junit.Assert.assertFalse(store.compareAndSet(expected, pending))
        org.junit.Assert.assertThrows(RemoteDeviceIdentityUnavailable::class.java) { store.expectedState(pending) }; assertEquals("{malformed", prefs.getString("state", null))
    }

    @Test
    fun invalidPresentIdentityCannotBeReplacedByOrdinaryEnrollmentCreation() {
        val store = RemoteDeviceIdentityStore(fixturePreferences)
        val pending = identity(KEY_PENDING, ENROLLMENT_PENDING, RemoteDeviceKeyStatus.PENDING)
        val prefs = fixturePreferences
        for (invalid in listOf("", " ", "{malformed")) {
            assertTrue(prefs.edit().putString("state", invalid).commit())
            org.junit.Assert.assertThrows(RemoteDeviceIdentityUnavailable::class.java) { store.enrollmentCandidate(COMPANY_ID, INSTALLATION_ID) }
            org.junit.Assert.assertThrows(RemoteDeviceIdentityUnavailable::class.java) { store.put(pending) }
            assertEquals(invalid, prefs.getString("state", null))
        }
    }

    private fun identity(
        keyId: String,
        enrollmentId: String,
        status: RemoteDeviceKeyStatus,
    ) = PersistedRemoteDeviceIdentity(
        companyId = COMPANY_ID,
        installationId = INSTALLATION_ID,
        keyId = keyId,
        enrollmentId = enrollmentId,
        status = status.storedValue,
        fingerprintSha256 = "a".repeat(64),
        pairingCode = if (status == RemoteDeviceKeyStatus.PENDING) "01AB2CDE3FGH" else null,
        serverTime = "2026-08-30T10:01:00Z",
        enrolledAt = "2026-08-30T10:00:00Z",
        pendingExpiresAt = "2026-08-30T10:10:00Z",
        approvedAt = if (status == RemoteDeviceKeyStatus.ACTIVE) "2026-08-30T10:00:30Z" else null,
    )

    private fun clear() {
        assertTrue(context.deleteSharedPreferences(fixtureName))
    }

    private companion object {
        const val COMPANY_ID = "11111111-1111-4111-8111-111111111111"
        const val INSTALLATION_ID = "22222222-2222-4222-8222-222222222222"
        const val KEY_ACTIVE = "33333333-3333-4333-8333-333333333333"
        const val KEY_PENDING = "44444444-4444-4444-8444-444444444444"
        const val ENROLLMENT_ACTIVE = "55555555-5555-4555-8555-555555555555"
        const val ENROLLMENT_PENDING = "66666666-6666-4666-8666-666666666666"
    }
}
