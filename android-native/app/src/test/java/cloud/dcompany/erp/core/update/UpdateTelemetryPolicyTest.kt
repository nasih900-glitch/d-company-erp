package cloud.dcompany.erp.core.update

import cloud.dcompany.erp.BuildConfig
import cloud.dcompany.erp.core.net.ApiClient
import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateTelemetryPolicyTest {
    private val scopeA = UpdateTelemetryBinding(
        userId = "user-a",
        companyId = "company-a",
        branchId = "branch-a",
        terminalId = "terminal-a",
    )
    private val scopeB = UpdateTelemetryBinding(
        userId = "user-b",
        companyId = "company-b",
        branchId = "branch-b",
        terminalId = "terminal-b",
    )

    @Test
    fun heartbeatBatchKeepsOnlyExactVerifiedScopeAndIsBounded() {
        val own = (1..25).map { event(scopeA, idSeed = it) }
        val foreign = (26..30).map { event(scopeB, idSeed = it) }

        val selection = selectEventsForScope(foreign + own, scopeA)

        assertEquals(25, selection.retained.size)
        assertEquals(20, selection.batch.size)
        assertEquals(5, selection.droppedCount)
        assertTrue(selection.batch.all { it.binding == scopeA })
    }

    @Test
    fun durableQueueBoundsGrowthAndKeepsStableEventIdempotencyIdentity() {
        val first = event(scopeA, idSeed = 1, dedupeKey = "upgrade:14")
        val sameLogicalUpgrade = event(scopeA, idSeed = 2, dedupeKey = "upgrade:14")

        val deduplicated = appendBoundedUpdateEvent(
            appendBoundedUpdateEvent(emptyList(), first),
            sameLogicalUpgrade,
        )
        assertEquals(listOf(first), deduplicated)

        val bounded = (1..110).fold(emptyList<QueuedUpdateEvent>()) { queue, index ->
            appendBoundedUpdateEvent(queue, event(scopeA, idSeed = index))
        }
        assertEquals(100, bounded.size)
        assertEquals(uuidFor(11), bounded.first().clientEventId)
        assertEquals(uuidFor(110), bounded.last().clientEventId)
    }

    @Test
    fun wireHeartbeatContainsOperationalFieldsButNoLocalTenantIdentity() {
        val request = ClientInstallationHeartbeatRequest(
            installationId = "22222222-2222-4222-8222-222222222222",
            platform = "android",
            distributionChannel = "direct",
            versionName = "3.1.3",
            versionCode = 14,
            pendingOutboxCount = 3,
            lastSuccessfulSyncAt = "2026-08-29T03:00:00Z",
            updateState = "failed",
            updateErrorCode = "checksum_mismatch",
            events = listOf(
                ClientUpdateEventRequest(
                    clientEventId = "33333333-3333-4333-8333-333333333333",
                    eventType = "update_failed",
                    targetVersionName = "3.1.4",
                    targetVersionCode = 15,
                    errorCode = "checksum_mismatch",
                    occurredAt = "2026-08-29T03:01:00Z",
                ),
            ),
        )

        val json = ApiClient.json.parseToJsonElement(ApiClient.json.encodeToString(request)).jsonObject

        assertEquals("android", json.getValue("platform").toString().trim('"'))
        assertEquals("direct", json.getValue("distribution_channel").toString().trim('"'))
        assertEquals("14", json.getValue("version_code").toString())
        assertFalse("company_id" in json)
        assertFalse("user_id" in json)
        assertFalse("terminal_id" in json)
        assertFalse("previous_version_code" in json)
        assertFalse("device_id" in json)
    }

    @Test
    fun freshInstallIsNotUpgradeButManualInstallOverExistingVersionIs() {
        assertFalse(shouldRecordUpgrade(0, 14, installedOverExistingApp = false))
        assertTrue(shouldRecordUpgrade(0, 14, installedOverExistingApp = true))
        assertTrue(shouldRecordUpgrade(13, 14, installedOverExistingApp = false))
        assertFalse(shouldRecordUpgrade(14, 14, installedOverExistingApp = true))
        assertFalse(shouldRecordUpgrade(15, 14, installedOverExistingApp = true))
    }

    @Test
    fun telemetryIdentityRequiresCanonicalRandomUuidVersionFour() {
        assertTrue(isCanonicalRandomUuidV4("11111111-1111-4111-8111-111111111111"))
        assertFalse(isCanonicalRandomUuidV4("11111111-1111-1111-8111-111111111111"))
        assertFalse(isCanonicalRandomUuidV4("1-1-4-8-1"))
        assertFalse(isCanonicalRandomUuidV4("not-a-uuid"))
    }

    @Test
    fun recoveredArtifactRequiresSafeFreshFilenameAndNewerExactMetadata() {
        val descriptor = descriptor()
        val now = 2_000_000L
        val valid = VerifiedUpdateArtifactRecord(
            descriptor = descriptor,
            fileName = "d-company-${descriptor.versionCode}.apk",
            verifiedAtMillis = now - 1_000L,
            telemetryBinding = scopeA,
        )

        assertTrue(verifiedArtifactRecordIsStructurallyValid(valid, now))
        assertFalse(verifiedArtifactRecordIsStructurallyValid(valid.copy(fileName = "../escape.apk"), now))
        assertFalse(verifiedArtifactRecordIsStructurallyValid(valid.copy(verifiedAtMillis = 0L), now))
        assertFalse(
            verifiedArtifactRecordIsStructurallyValid(
                valid.copy(descriptor = descriptor.copy(versionCode = BuildConfig.VERSION_CODE)),
                now,
            ),
        )
    }

    @Test
    fun recoveryDigestCheckRejectsChangedOrTruncatedApk() {
        val file = File.createTempFile("dcompany-update", ".apk")
        try {
            val bytes = "verified apk bytes".toByteArray()
            file.writeBytes(bytes)
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(bytes)
                .joinToString("") { "%02x".format(it) }
            val exact = descriptor().copy(sizeBytes = bytes.size.toLong(), sha256 = digest)

            assertTrue(verifyFileSizeAndSha256(file, exact))
            assertFalse(verifyFileSizeAndSha256(file, exact.copy(sizeBytes = bytes.size + 1L)))
            assertFalse(verifyFileSizeAndSha256(file, exact.copy(sha256 = "0".repeat(64))))
        } finally {
            file.delete()
        }
    }

    @Test
    fun onlyFailedEventsMayCarryAllowlistedErrorCode() {
        val invalidCancel = event(scopeA, 1).copy(
            eventType = UpdateEventType.UPDATE_CANCELLED.wireValue,
            errorCode = UpdateErrorCode.INSTALLER_NOT_COMPLETED.wireValue,
        )
        val failed = event(scopeA, 2).copy(
            eventType = UpdateEventType.UPDATE_FAILED.wireValue,
            errorCode = UpdateErrorCode.INSTALLER_NOT_COMPLETED.wireValue,
        )

        assertEquals(emptyList<QueuedUpdateEvent>(), appendBoundedUpdateEvent(emptyList(), invalidCancel))
        assertEquals(listOf(failed), appendBoundedUpdateEvent(emptyList(), failed))
        assertNull(failed.dedupeKey)
    }

    private fun descriptor() = DirectUpdateDescriptor(
        url = "https://updates.example.test/d-company.apk",
        versionCode = BuildConfig.VERSION_CODE + 1,
        versionName = "3.1.4",
        sha256 = "ab".repeat(32),
        sizeBytes = 42_000L,
        expectedCurrentSignerSha256 = "12".repeat(32),
    )

    private fun event(
        binding: UpdateTelemetryBinding,
        idSeed: Int,
        dedupeKey: String? = null,
    ) = QueuedUpdateEvent(
        clientEventId = uuidFor(idSeed),
        eventType = UpdateEventType.DOWNLOAD_STARTED.wireValue,
        targetVersionName = "3.1.4",
        targetVersionCode = 15,
        occurredAtMillis = 1_000L + idSeed,
        binding = binding,
        dedupeKey = dedupeKey,
    )

    private fun uuidFor(seed: Int): String =
        "00000000-0000-4000-8000-${seed.toString(16).padStart(12, '0')}"
}
