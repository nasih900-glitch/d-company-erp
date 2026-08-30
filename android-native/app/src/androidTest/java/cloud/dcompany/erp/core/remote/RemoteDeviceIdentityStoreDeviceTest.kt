package cloud.dcompany.erp.core.remote

import android.content.Context
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

    @Before
    fun clearBefore() = clear()

    @After
    fun clearAfter() = clear()

    @Test
    fun activeAndPendingRemainSeparateUntilAtomicPromotion() {
        val store = RemoteDeviceIdentityStore(context)
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
        context.getSharedPreferences("dcompany_remote_device_identity", Context.MODE_PRIVATE)
            .edit().clear().commit()
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
