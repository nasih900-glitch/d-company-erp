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
            storedPolicyRevision = original.policyRevision,
            encodedNotice = encoded,
            currentInstalledVersionCode = 10,
        ) as PersistedRequirementRestore.Required

        assertEquals(original, restored.notice)
    }

    @Test
    fun sameInstalledBuildFailsClosedWhenPersistedNoticeIsCorrupt() {
        val restored = restorePersistedUpdateRequirement(
            storedInstalledVersionCode = 10,
            storedPolicyRevision = 17,
            encodedNotice = "not-json",
            currentInstalledVersionCode = 10,
        ) as PersistedRequirementRestore.Required

        assertEquals(10, restored.notice.currentVersionCode)
        assertNull(restored.notice.updateUrl)
        assertEquals(17, restored.notice.policyRevision)
        assertTrue(restored.notice.message.contains("previously blocked"))
    }

    @Test
    fun inPlaceUpgradeClearsOnlyTheOlderInstalledBuildRequirement() {
        val stale = restorePersistedUpdateRequirement(
            storedInstalledVersionCode = 10,
            storedPolicyRevision = notice().policyRevision,
            encodedNotice = encodePersistedUpdateRequirement(10, notice()),
            currentInstalledVersionCode = 11,
        )
        val absent = restorePersistedUpdateRequirement(
            storedInstalledVersionCode = null,
            storedPolicyRevision = null,
            encodedNotice = null,
            currentInstalledVersionCode = 11,
        )

        assertEquals(PersistedRequirementRestore.ClearStaleVersion, stale)
        assertEquals(PersistedRequirementRestore.None, absent)
    }

    @Test
    fun persistedRevisionCannotBeLoweredByStaleNoticeJson() {
        val staleJson = encodePersistedUpdateRequirement(
            installedVersionCode = 10,
            notice = notice().copy(policyRevision = 6),
        )

        val restored = restorePersistedUpdateRequirement(
            storedInstalledVersionCode = 10,
            storedPolicyRevision = 8,
            encodedNotice = staleJson,
            currentInstalledVersionCode = 10,
        ) as PersistedRequirementRestore.Required

        assertEquals(8, restored.notice.policyRevision)
    }

    @Test
    fun legacyPersistedRequirementDefaultsToRevisionZero() {
        val legacy = encodePersistedUpdateRequirement(
            installedVersionCode = 10,
            notice = notice().copy(policyRevision = 0),
        )

        val restored = restorePersistedUpdateRequirement(
            storedInstalledVersionCode = 10,
            storedPolicyRevision = null,
            encodedNotice = legacy,
            currentInstalledVersionCode = 10,
        ) as PersistedRequirementRestore.Required

        assertEquals(0, restored.notice.policyRevision)
    }

    private fun notice() = ClientUpdateNotice(
        message = "Update required",
        updateUrl = "https://updates.example.test/d-company-11.apk",
        currentVersionCode = 10,
        minimumSupportedVersionCode = 11,
        latestVersionCode = 11,
        policyRevision = 7,
        latestVersionName = "3.1.0",
        releaseNotes = "Gaming Centre release",
        apkSha256 = "ab".repeat(32),
        apkSizeBytes = 42_000_000L,
        apkSigningCertSha256 = "12".repeat(32),
    )
}
