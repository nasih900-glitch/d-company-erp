package cloud.dcompany.erp.core.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClientUpdateRequirementStoreTest {
    @Test
    fun persistedRequirementRoundTripsEveryVerifiedUpdateField() {
        val original = notice()
        val encoded = encodePersistedUpdateRequirement(
            installedVersionCode = 10,
            notice = original,
        )

        val restored = restorePersistedUpdateRequirement(
            storedInstalledVersionCode = 10,
            encodedNotice = encoded,
            currentInstalledVersionCode = 10,
        ) as PersistedRequirementRestore.Required

        assertEquals(original, restored.notice)
    }

    @Test
    fun sameInstalledBuildFailsClosedWhenPersistedNoticeIsCorrupt() {
        val restored = restorePersistedUpdateRequirement(
            storedInstalledVersionCode = 10,
            encodedNotice = "not-json",
            currentInstalledVersionCode = 10,
        ) as PersistedRequirementRestore.Required

        assertEquals(10, restored.notice.currentVersionCode)
        assertNull(restored.notice.updateUrl)
        assertTrue(restored.notice.message.contains("previously blocked"))
    }

    @Test
    fun inPlaceUpgradeClearsOnlyTheOlderInstalledBuildRequirement() {
        val stale = restorePersistedUpdateRequirement(
            storedInstalledVersionCode = 10,
            encodedNotice = encodePersistedUpdateRequirement(10, notice()),
            currentInstalledVersionCode = 11,
        )
        val absent = restorePersistedUpdateRequirement(
            storedInstalledVersionCode = null,
            encodedNotice = null,
            currentInstalledVersionCode = 11,
        )

        assertEquals(PersistedRequirementRestore.ClearStaleVersion, stale)
        assertEquals(PersistedRequirementRestore.None, absent)
    }

    private fun notice() = ClientUpdateNotice(
        message = "Update required",
        updateUrl = "https://updates.example.test/d-company-11.apk",
        currentVersionCode = 10,
        minimumSupportedVersionCode = 11,
        latestVersionCode = 11,
        latestVersionName = "3.1.0",
        releaseNotes = "Gaming Centre release",
        apkSha256 = "ab".repeat(32),
        apkSizeBytes = 42_000_000L,
        apkSigningCertSha256 = "12".repeat(32),
    )
}
