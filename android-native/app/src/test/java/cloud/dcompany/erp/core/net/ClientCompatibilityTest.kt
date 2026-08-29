package cloud.dcompany.erp.core.net

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClientCompatibilityTest {
    @Test
    fun clientIdentityUsesCentralAndroidAndNumericBuildHeaders() {
        val request = Request.Builder().url("https://example.test/api/v1/auth/me").build()
            .withAndroidClientIdentity(42, "direct")

        assertEquals("android", request.header(CLIENT_PLATFORM_HEADER))
        assertEquals("42", request.header(CLIENT_VERSION_CODE_HEADER))
        assertEquals("direct", request.header(CLIENT_DISTRIBUTION_CHANNEL_HEADER))
    }

    @Test
    fun requiredPolicyRevisionUsesMonotonicBodyOrHeaderAuthority() {
        assertEquals(12, resolvedCompatibilityPolicyRevision(12, "11"))
        assertEquals(12, resolvedCompatibilityPolicyRevision(11, "12"))
        assertEquals(12, resolvedCompatibilityPolicyRevision(null, " 12 "))
        assertEquals(11, resolvedCompatibilityPolicyRevision(11, "invalid"))
        assertEquals(0, resolvedCompatibilityPolicyRevision(null, "0"))
        assertEquals(0, resolvedCompatibilityPolicyRevision(null, "2147483648"))
    }

    @Test
    fun explicitContractDistinguishesOptionalAndRequiredUpdates() = runBlocking {
        val optional = ClientCompatibilityGate(
            checkCompatibility = { compatibility("update_available") },
            elapsedRealtimeMillis = { 1_000L },
        )
        optional.checkAtStartup()
        assertTrue(optional.state.value is ClientCompatibilityState.UpdateAvailable)
        optional.dismissOptionalUpdate()
        assertEquals(ClientCompatibilityState.Supported, optional.state.value)

        val required = ClientCompatibilityGate(
            checkCompatibility = { compatibility("update_required") },
        )
        required.checkAtStartup()
        assertTrue(required.state.value is ClientCompatibilityState.UpdateRequired)
    }

    @Test
    fun compatibilityGateCarriesVerifiedReleaseMetadataToTheUpdateFlow() = runBlocking {
        val response = compatibility("update_available").copy(
            latestVersionName = "3.1.0",
            releaseNotes = "Gaming Centre profile",
            apkSha256 = "ab".repeat(32),
            apkSizeBytes = 42_000_000L,
            apkSigningCertSha256 = "12".repeat(32),
        )
        val gate = ClientCompatibilityGate(checkCompatibility = { response })

        gate.checkAtStartup()

        val notice = (gate.state.value as ClientCompatibilityState.UpdateAvailable).notice
        assertEquals("3.1.0", notice.latestVersionName)
        assertEquals("Gaming Centre profile", notice.releaseNotes)
        assertEquals("ab".repeat(32), notice.apkSha256)
        assertEquals(42_000_000L, notice.apkSizeBytes)
        assertEquals("12".repeat(32), notice.apkSigningCertSha256)
        assertEquals(1, notice.policyRevision)
    }

    @Test
    fun transientStartupFailureDoesNotBrickOfflineApp() = runBlocking {
        val gate = ClientCompatibilityGate(
            checkCompatibility = { throw ApiException("offline", status = null) },
        )

        gate.checkAtStartup()

        assertEquals(ClientCompatibilityState.Supported, gate.state.value)
    }

    @Test
    fun boundedStartupCheckReleasesSavedWorkspaceWhenEndpointHangs() = runBlocking {
        val gate = ClientCompatibilityGate(
            checkCompatibility = { awaitCancellation() },
            startupTimeoutMillis = 25L,
        )

        gate.checkAtStartup()

        assertEquals(ClientCompatibilityState.Supported, gate.state.value)
    }

    @Test
    fun foregroundRecheckNeverReturnsWorkspaceToChecking() = runBlocking {
        val response = CompletableDeferred<ClientCompatibilityResponse>()
        var calls = 0
        val gate = ClientCompatibilityGate(
            checkCompatibility = {
                calls += 1
                if (calls == 1) compatibility("supported") else response.await()
            },
        )
        gate.checkAtStartup()

        val recheck = launch { gate.recheckNonBlocking() }

        assertEquals(ClientCompatibilityState.Supported, gate.state.value)
        response.complete(compatibility("update_available"))
        recheck.join()
        assertTrue(gate.state.value is ClientCompatibilityState.UpdateAvailable)
    }

    @Test
    fun uncertainForegroundRecheckPreservesCurrentDecision() = runBlocking {
        var fail = false
        val gate = ClientCompatibilityGate(
            checkCompatibility = {
                if (fail) throw ApiException("offline", status = null)
                compatibility("update_available")
            },
        )
        gate.checkAtStartup()
        fail = true

        gate.recheckNonBlocking()

        assertTrue(gate.state.value is ClientCompatibilityState.UpdateAvailable)
    }

    @Test
    fun dismissedOptionalReleaseDoesNotReappearOnForegroundRefresh() = runBlocking {
        var now = 1_000L
        val gate = ClientCompatibilityGate(
            checkCompatibility = { compatibility("update_available", latestVersionCode = 2) },
            optionalUpdateSnoozeMillis = 4_000L,
            elapsedRealtimeMillis = { now },
        )
        gate.checkAtStartup()
        gate.dismissOptionalUpdate()

        now = 4_999L
        gate.recheckNonBlocking()

        assertEquals(ClientCompatibilityState.Supported, gate.state.value)
    }

    @Test
    fun dismissedOptionalReleaseReappearsExactlyAtSnoozeExpiry() = runBlocking {
        var now = 1_000L
        val gate = ClientCompatibilityGate(
            checkCompatibility = { compatibility("update_available", latestVersionCode = 2) },
            optionalUpdateSnoozeMillis = 4_000L,
            elapsedRealtimeMillis = { now },
        )
        gate.checkAtStartup()
        gate.dismissOptionalUpdate()

        now = 5_000L
        gate.recheckNonBlocking()

        assertTrue(gate.state.value is ClientCompatibilityState.UpdateAvailable)
    }

    @Test
    fun elapsedClockResetExpiresOptionalSnoozeFailOpen() = runBlocking {
        var now = 10_000L
        val gate = ClientCompatibilityGate(
            checkCompatibility = { compatibility("update_available", latestVersionCode = 2) },
            elapsedRealtimeMillis = { now },
        )
        gate.checkAtStartup()
        gate.dismissOptionalUpdate()

        now = 1_000L
        gate.recheckNonBlocking()

        assertTrue(gate.state.value is ClientCompatibilityState.UpdateAvailable)
    }

    @Test
    fun newerOptionalReleaseIsShownAfterEarlierReleaseWasDismissed() = runBlocking {
        var latest = 2
        val gate = ClientCompatibilityGate(
            checkCompatibility = {
                compatibility("update_available", latestVersionCode = latest)
            },
            elapsedRealtimeMillis = { 1_000L },
        )
        gate.checkAtStartup()
        gate.dismissOptionalUpdate()

        latest = 3
        gate.recheckNonBlocking()

        val state = gate.state.value as ClientCompatibilityState.UpdateAvailable
        assertEquals(3, state.notice.latestVersionCode)
    }

    @Test
    fun requiredUpdateBypassesOptionalSnoozeForTheSameVersion() = runBlocking {
        var status = "update_available"
        val gate = ClientCompatibilityGate(
            checkCompatibility = { compatibility(status, latestVersionCode = 2) },
            elapsedRealtimeMillis = { 1_000L },
        )
        gate.checkAtStartup()
        gate.dismissOptionalUpdate()

        status = "update_required"
        gate.recheckNonBlocking()

        assertTrue(gate.state.value is ClientCompatibilityState.UpdateRequired)
    }

    @Test
    fun updateRequiredPreservesQueuedWorkForTheUpdatedApp() {
        val failure = ApiException(
            "Update required",
            status = 426,
            code = "client_update_required",
        )

        assertTrue(failure.mustPreserveOutbox)
        assertTrue(!failure.isAmbiguous)
    }

    @Test
    fun definitiveRequiredSignalCannotBeDowngradedByLateStartupResponse() = runBlocking {
        val gate = ClientCompatibilityGate(
            checkCompatibility = { compatibility("supported", policyRevision = 4) },
            installedVersionCode = 1,
        )
        gate.requireUpdate(
            ClientUpdateNotice(
                "Update now",
                "https://updates.example.test/app.apk",
                1,
                2,
                3,
                policyRevision = 4,
            ),
        )

        gate.checkAtStartup()

        assertTrue(gate.state.value is ClientCompatibilityState.UpdateRequired)
    }

    @Test
    fun requiredUpdateCanRefreshCorrectedVerifiedDownloadMetadataWithoutUnblocking() = runBlocking {
        var metadataReady = false
        val persisted = mutableListOf<ClientUpdateNotice>()
        val gate = ClientCompatibilityGate(
            checkCompatibility = {
                compatibility("update_required", latestVersionCode = 3).copy(
                    updateUrl = if (metadataReady) {
                        "https://updates.example.test/d-company-3.apk"
                    } else {
                        null
                    },
                    latestVersionName = if (metadataReady) "3.1.0" else null,
                    apkSha256 = if (metadataReady) "ab".repeat(32) else null,
                    apkSizeBytes = if (metadataReady) 42_000_000L else null,
                    apkSigningCertSha256 = if (metadataReady) "12".repeat(32) else null,
                )
            },
            persistRequiredNotice = persisted::add,
        )
        gate.checkAtStartup()
        val initial = gate.state.value as ClientCompatibilityState.UpdateRequired
        assertNull(initial.notice.updateUrl)

        metadataReady = true
        gate.recheckNonBlocking()

        val refreshed = gate.state.value as ClientCompatibilityState.UpdateRequired
        assertEquals("https://updates.example.test/d-company-3.apk", refreshed.notice.updateUrl)
        assertEquals("3.1.0", refreshed.notice.latestVersionName)
        assertEquals("ab".repeat(32), refreshed.notice.apkSha256)
        assertEquals(42_000_000L, refreshed.notice.apkSizeBytes)
        assertEquals("12".repeat(32), refreshed.notice.apkSigningCertSha256)
        assertEquals(listOf(initial.notice, refreshed.notice), persisted)
    }

    @Test
    fun restoredRequiredStateSurvivesSupportedResponseOnTheSameInstalledBuild() = runBlocking {
        val restored = ClientUpdateNotice(
            message = "Previously required",
            updateUrl = "https://updates.example.test/app.apk",
            currentVersionCode = 1,
            minimumSupportedVersionCode = 2,
            latestVersionCode = 2,
            policyRevision = 7,
        )
        val gate = ClientCompatibilityGate(
            checkCompatibility = { compatibility("supported", policyRevision = 7) },
            installedVersionCode = 1,
            initialRequiredNotice = restored,
        )

        assertEquals(ClientCompatibilityState.UpdateRequired(restored), gate.state.value)
        gate.checkAtStartup()
        assertEquals(ClientCompatibilityState.UpdateRequired(restored), gate.state.value)
    }

    @Test
    fun newerSupportedPolicyClearsPersistedRequiredBlockAfterMinimumRollback() = runBlocking {
        val required = ClientUpdateNotice(
            message = "Update required",
            updateUrl = null,
            currentVersionCode = 14,
            minimumSupportedVersionCode = 15,
            latestVersionCode = 15,
            policyRevision = 9,
        )
        val cleared = mutableListOf<Int>()
        val gate = ClientCompatibilityGate(
            checkCompatibility = {
                compatibility(
                    status = "supported",
                    latestVersionCode = 14,
                    minimumVersionCode = 8,
                    currentVersionCode = 14,
                    policyRevision = 10,
                )
            },
            installedVersionCode = 14,
            initialRequiredNotice = required,
            clearRequiredNotice = { revision ->
                cleared += revision
                true
            },
        )

        gate.checkAtStartup()

        assertEquals(ClientCompatibilityState.Supported, gate.state.value)
        assertEquals(listOf(9), cleared)
    }

    @Test
    fun newerSupportedPolicyCannotClearWhenInstalledBuildIsStillBelowMinimum() = runBlocking {
        val required = requiredNotice(policyRevision = 9)
        var clearCalls = 0
        val gate = ClientCompatibilityGate(
            checkCompatibility = {
                compatibility(
                    status = "supported",
                    minimumVersionCode = 15,
                    latestVersionCode = 15,
                    currentVersionCode = 14,
                    policyRevision = 10,
                )
            },
            installedVersionCode = 14,
            initialRequiredNotice = required,
            clearRequiredNotice = {
                clearCalls += 1
                true
            },
        )

        gate.checkAtStartup()

        assertEquals(ClientCompatibilityState.UpdateRequired(required), gate.state.value)
        assertEquals(0, clearCalls)
    }

    @Test
    fun newerOptionalPolicyCannotClearRequiredBlock() = runBlocking {
        val required = requiredNotice(policyRevision = 9)
        var clearCalls = 0
        val gate = ClientCompatibilityGate(
            checkCompatibility = {
                compatibility(
                    status = "update_available",
                    minimumVersionCode = 8,
                    currentVersionCode = 14,
                    policyRevision = 10,
                )
            },
            installedVersionCode = 14,
            initialRequiredNotice = required,
            clearRequiredNotice = {
                clearCalls += 1
                true
            },
        )

        gate.checkAtStartup()

        assertEquals(ClientCompatibilityState.UpdateRequired(required), gate.state.value)
        assertEquals(0, clearCalls)
    }

    @Test
    fun staleSupportedResponseCannotClearNewerConcurrent426() = runBlocking {
        val response = CompletableDeferred<ClientCompatibilityResponse>()
        val cleared = mutableListOf<Int>()
        val gate = ClientCompatibilityGate(
            checkCompatibility = { response.await() },
            installedVersionCode = 14,
            clearRequiredNotice = { revision ->
                cleared += revision
                true
            },
        )
        val startup = launch { gate.checkAtStartup() }
        val newerRequired = requiredNotice(policyRevision = 12)
        gate.requireUpdate(newerRequired)

        response.complete(
            compatibility(
                status = "supported",
                latestVersionCode = 14,
                minimumVersionCode = 8,
                currentVersionCode = 14,
                policyRevision = 11,
            ),
        )
        startup.join()

        assertEquals(ClientCompatibilityState.UpdateRequired(newerRequired), gate.state.value)
        assertTrue(cleared.isEmpty())
    }

    @Test
    fun failedCompareAndClearKeepsRequiredState() = runBlocking {
        val required = requiredNotice(policyRevision = 9)
        val gate = ClientCompatibilityGate(
            checkCompatibility = {
                compatibility(
                    status = "supported",
                    latestVersionCode = 14,
                    minimumVersionCode = 8,
                    currentVersionCode = 14,
                    policyRevision = 10,
                )
            },
            installedVersionCode = 14,
            initialRequiredNotice = required,
            clearRequiredNotice = { false },
        )

        gate.checkAtStartup()

        assertEquals(ClientCompatibilityState.UpdateRequired(required), gate.state.value)
    }

    @Test
    fun directRequiredSignalPersistsBeforePublishingTheBlockingState() {
        val persisted = mutableListOf<ClientUpdateNotice>()
        val gate = ClientCompatibilityGate(
            checkCompatibility = { compatibility("supported") },
            persistRequiredNotice = persisted::add,
        )
        val required = ClientUpdateNotice(
            message = "Update now",
            updateUrl = null,
            currentVersionCode = 1,
            minimumSupportedVersionCode = 2,
            latestVersionCode = 2,
        )

        gate.requireUpdate(required)

        assertEquals(listOf(required), persisted)
        assertEquals(ClientCompatibilityState.UpdateRequired(required), gate.state.value)
    }

    @Test
    fun updateActionAcceptsOnlyUnambiguousHttpsUrls() {
        assertEquals(
            "https://updates.example.test/app.apk",
            safeHttpsUpdateUrl(" https://updates.example.test/app.apk "),
        )
        assertNull(safeHttpsUpdateUrl("http://updates.example.test/app.apk"))
        assertNull(safeHttpsUpdateUrl("javascript:alert(1)"))
        assertNull(safeHttpsUpdateUrl("https://owner@updates.example.test/app.apk"))
        assertNull(safeHttpsUpdateUrl("not a url"))
    }

    private fun compatibility(
        status: String,
        latestVersionCode: Int = 2,
        minimumVersionCode: Int = 1,
        currentVersionCode: Int = 1,
        policyRevision: Int = 1,
    ) = ClientCompatibilityResponse(
        platform = "android",
        currentVersionCode = currentVersionCode,
        minimumSupportedVersionCode = minimumVersionCode,
        latestVersionCode = latestVersionCode,
        policyRevision = policyRevision,
        status = status,
        updateUrl = "https://updates.example.test/app.apk",
        message = "Compatibility message",
        checkedAt = "2026-08-25T12:00:00Z",
    )

    private fun requiredNotice(policyRevision: Int) = ClientUpdateNotice(
        message = "Update required",
        updateUrl = null,
        currentVersionCode = 14,
        minimumSupportedVersionCode = 15,
        latestVersionCode = 15,
        policyRevision = policyRevision,
    )
}
