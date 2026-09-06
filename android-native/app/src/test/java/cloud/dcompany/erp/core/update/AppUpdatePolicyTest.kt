package cloud.dcompany.erp.core.update

import cloud.dcompany.erp.core.net.ClientUpdateNotice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdatePolicyTest {
    private val checksum = "ab".repeat(32)
    private val signer = "12".repeat(32)

    @Test
    fun completeNewerHttpsApkMetadataIsAccepted() {
        val result = validateDirectUpdateMetadata(notice(), installedVersionCode = 10)

        assertTrue(result is DirectUpdateMetadataResult.Valid)
        val descriptor = (result as DirectUpdateMetadataResult.Valid).descriptor
        assertEquals("https://updates.example.test/d-company-11.apk", descriptor.url)
        assertEquals(11, descriptor.versionCode)
        assertEquals("3.1.0", descriptor.versionName)
        assertEquals(checksum, descriptor.sha256)
        assertEquals(signer, descriptor.expectedCurrentSignerSha256)
    }

    @Test
    fun directMetadataRejectsUnsafeOrNonApkLinks() {
        listOf(
            "http://updates.example.test/app.apk",
            "https://updates.example.test/app.zip",
            "https://owner@updates.example.test/app.apk",
            "https://updates.example.test/app.apk#fragment",
        ).forEach { url ->
            val result = validateDirectUpdateMetadata(
                notice().copy(updateUrl = url),
                installedVersionCode = 10,
            )
            assertEquals(
                DirectUpdateMetadataProblem.MissingOrUnsafeApkUrl,
                (result as DirectUpdateMetadataResult.Invalid).problem,
            )
        }
    }

    @Test
    fun directMetadataRequiresExactNewerVersionIdentity() {
        listOf(
            notice().copy(latestVersionCode = 10),
            notice().copy(latestVersionCode = null),
            notice().copy(latestVersionName = null),
            notice().copy(latestVersionName = " "),
            notice().copy(latestVersionName = "3.1 release"),
            notice().copy(latestVersionName = "x".repeat(81)),
        ).forEach { invalid ->
            val result = validateDirectUpdateMetadata(invalid, installedVersionCode = 10)
            assertEquals(
                DirectUpdateMetadataProblem.MissingOrInvalidVersion,
                (result as DirectUpdateMetadataResult.Invalid).problem,
            )
        }
    }

    @Test
    fun directMetadataBoundsSizeAndRequiresSha256() {
        listOf(
            notice().copy(apkSizeBytes = 0),
            notice().copy(apkSizeBytes = 513L * 1024L * 1024L),
            notice().copy(apkSha256 = null),
            notice().copy(apkSha256 = "abcd"),
        ).forEach { invalid ->
            assertTrue(
                validateDirectUpdateMetadata(invalid, installedVersionCode = 10) is
                    DirectUpdateMetadataResult.Invalid,
            )
        }
    }

    @Test
    fun advertisedSignerIsMandatoryAndMustBeARealSha256() {
        listOf(null, "", "not-a-fingerprint").forEach { signerValue ->
            val invalid = validateDirectUpdateMetadata(
                notice().copy(apkSigningCertSha256 = signerValue),
                installedVersionCode = 10,
            ) as DirectUpdateMetadataResult.Invalid
            assertEquals(DirectUpdateMetadataProblem.InvalidExpectedSigner, invalid.problem)
        }
    }

    @Test
    fun sha256NormalizerAcceptsColonSeparatedCertificateFingerprint() {
        val colonSeparated = signer.chunked(2).joinToString(":").uppercase()
        assertEquals(signer, normalizeSha256(colonSeparated))
    }

    @Test
    fun signingRotationAcceptsCandidateWhoseHistoryContainsInstalledCurrentSigner() {
        assertTrue(
            signingLineageIsContinuous(
                installedCurrentSigners = setOf("old"),
                installedSigningHistory = setOf("old"),
                candidateCurrentSigners = setOf("new"),
                candidateSigningHistory = setOf("old", "new"),
            ),
        )
    }

    @Test
    fun unrelatedOrReverseSigningLineageIsRejected() {
        assertFalse(
            signingLineageIsContinuous(
                installedCurrentSigners = setOf("old"),
                installedSigningHistory = setOf("old"),
                candidateCurrentSigners = setOf("other"),
                candidateSigningHistory = setOf("other"),
            ),
        )
        assertFalse(
            signingLineageIsContinuous(
                installedCurrentSigners = setOf("new"),
                installedSigningHistory = setOf("old", "new"),
                candidateCurrentSigners = setOf("old"),
                candidateSigningHistory = setOf("old"),
            ),
        )
    }

    @Test
    fun multipleSignerUpdatesRequireTheExactCurrentSet() {
        assertTrue(
            signingLineageIsContinuous(
                installedCurrentSigners = setOf("a", "b"),
                installedSigningHistory = setOf("a", "b"),
                candidateCurrentSigners = setOf("b", "a"),
                candidateSigningHistory = setOf("a", "b"),
            ),
        )
        assertFalse(
            signingLineageIsContinuous(
                installedCurrentSigners = setOf("a", "b"),
                installedSigningHistory = setOf("a", "b"),
                candidateCurrentSigners = setOf("a", "c"),
                candidateSigningHistory = setOf("a", "c"),
            ),
        )
    }

    @Test
    fun preparedArtifactIsReusableOnlyForTheExactAuthoritativeDescriptor() {
        val descriptor = DirectUpdateDescriptor(
            url = "https://updates.example.test/d-company-11.apk",
            versionCode = 11,
            versionName = "3.1.0",
            sha256 = checksum,
            sizeBytes = 42L * 1024L * 1024L,
            expectedCurrentSignerSha256 = signer,
        )
        val ready = AppUpdateUiState.Ready(descriptor, "/private/verified-update.apk")

        assertTrue(ready.matchesDescriptor(descriptor.copy()))
        listOf(
            descriptor.copy(url = "https://updates.example.test/replacement-11.apk"),
            descriptor.copy(versionName = "3.1.0-rebuilt"),
            descriptor.copy(sha256 = "cd".repeat(32)),
            descriptor.copy(sizeBytes = descriptor.sizeBytes + 1),
            descriptor.copy(expectedCurrentSignerSha256 = "34".repeat(32)),
        ).forEach { corrected ->
            assertFalse(
                "A same-version metadata correction must invalidate the prepared artifact: $corrected",
                ready.matchesDescriptor(corrected),
            )
        }
    }

    private fun notice() = ClientUpdateNotice(
        message = "Update available",
        updateUrl = "https://updates.example.test/d-company-11.apk",
        currentVersionCode = 10,
        minimumSupportedVersionCode = 10,
        latestVersionCode = 11,
        latestVersionName = "3.1.0",
        releaseNotes = "Gaming Centre profile",
        apkSha256 = checksum,
        apkSizeBytes = 42L * 1024L * 1024L,
        apkSigningCertSha256 = signer,
    )
}
