package cloud.dcompany.erp.core.update

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cloud.dcompany.erp.BuildConfig
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UpdatePersistenceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun clearBefore() = clearStores()

    @After
    fun clearAfter() = clearStores()

    @Test
    fun installationIdentityIsRandomVersionFourAndStable() {
        val store = InstallationIdentityStore(context)

        val first = store.installationId()
        val second = InstallationIdentityStore(context).installationId()

        assertNotNull(first)
        assertEquals(first, second)
        assertEquals(4, UUID.fromString(first).version())
    }

    @Test
    fun simultaneousFirstUseAcrossStoresPersistsExactlyOneIdentity() {
        val threads = Executors.newFixedThreadPool(16)
        try {
            repeat(12) {
                clearStores()
                val stores = List(16) { InstallationIdentityStore(context) }
                val ready = CountDownLatch(stores.size)
                val start = CountDownLatch(1)
                val results = stores.map { store ->
                    threads.submit<String?> {
                        ready.countDown()
                        check(start.await(10, TimeUnit.SECONDS))
                        store.installationId()
                    }
                }
                assertTrue(ready.await(10, TimeUnit.SECONDS))
                start.countDown()
                val ids = results.map { it.get(10, TimeUnit.SECONDS) }
                assertTrue(ids.all { it != null })
                assertEquals(1, ids.toSet().size)
                assertEquals(ids.first(), InstallationIdentityStore(context).installationId())
            }
        } finally {
            threads.shutdownNow()
        }
    }

    @Test
    fun identityAndPendingUpgradeSurviveSeparateStoreWriters() {
        val identityStore = InstallationIdentityStore(context)
        val first = identityStore.installationId()
        val versionStore = InstallationIdentityStore(context)
        assertTrue(versionStore.observeInstalledVersion(BuildConfig.VERSION_CODE, BuildConfig.VERSION_NAME, false))
        assertTrue(versionStore.observeInstalledVersion(BuildConfig.VERSION_CODE + 1, "3.1.4", false))
        val reloaded = InstallationIdentityStore(context)
        assertEquals(first, reloaded.installationId())
        assertEquals(BuildConfig.VERSION_CODE + 1, reloaded.pendingUpgrade()?.targetVersionCode)
        assertTrue(identityStore.clearPendingUpgrade(BuildConfig.VERSION_CODE + 1))
        assertEquals(first, reloaded.installationId())
        assertNull(reloaded.pendingUpgrade())
    }

    @Test
    fun upgradeMarkerSurvivesStoreRecreationUntilPromoted() {
        val store = InstallationIdentityStore(context)
        assertTrue(
            store.observeInstalledVersion(
                currentVersionCode = BuildConfig.VERSION_CODE,
                currentVersionName = BuildConfig.VERSION_NAME,
                installedOverExistingApp = false,
            ),
        )
        assertNull(store.pendingUpgrade())

        assertTrue(
            store.observeInstalledVersion(
                currentVersionCode = BuildConfig.VERSION_CODE + 1,
                currentVersionName = "3.1.4",
                installedOverExistingApp = false,
            ),
        )
        val restored = InstallationIdentityStore(context).pendingUpgrade()
        assertEquals(BuildConfig.VERSION_CODE + 1, restored?.targetVersionCode)
        assertEquals("3.1.4", restored?.targetVersionName)
    }

    @Test
    fun verifiedArtifactMetadataSurvivesStoreRecreation() {
        val descriptor = DirectUpdateDescriptor(
            url = "https://updates.example.test/d-company-${BuildConfig.VERSION_CODE + 1}.apk",
            versionCode = BuildConfig.VERSION_CODE + 1,
            versionName = "3.1.4",
            sha256 = "ab".repeat(32),
            sizeBytes = 42_000L,
            expectedCurrentSignerSha256 = "12".repeat(32),
        )
        val record = VerifiedUpdateArtifactRecord(
            descriptor = descriptor,
            fileName = "d-company-${descriptor.versionCode}.apk",
            verifiedAtMillis = 1_000_000L,
            telemetryBinding = UpdateTelemetryBinding("user", "company", null, "terminal"),
        )

        assertTrue(VerifiedUpdateArtifactStore(context).save(record))
        assertEquals(
            record,
            VerifiedUpdateArtifactStore(context).restore(nowMillis = 1_001_000L),
        )
    }

    private fun clearStores() {
        context.getSharedPreferences("dcompany_installation_identity", Context.MODE_PRIVATE)
            .edit().clear().commit()
        context.getSharedPreferences("dcompany_verified_update", Context.MODE_PRIVATE)
            .edit().clear().commit()
        context.getSharedPreferences("dcompany_update_events", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }
}
