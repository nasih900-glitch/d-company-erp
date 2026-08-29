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
            .withAndroidClientIdentity(42)

        assertEquals("android", request.header(CLIENT_PLATFORM_HEADER))
        assertEquals("42", request.header(CLIENT_VERSION_CODE_HEADER))
    }

    @Test
    fun explicitContractDistinguishesOptionalAndRequiredUpdates() = runBlocking {
        val optional = ClientCompatibilityGate(
            checkCompatibility = { compatibility("update_available") },
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
        val gate = ClientCompatibilityGate(
            checkCompatibility = { compatibility("update_available", latestVersionCode = 2) },
        )
        gate.checkAtStartup()
        gate.dismissOptionalUpdate()

        gate.recheckNonBlocking()

        assertEquals(ClientCompatibilityState.Supported, gate.state.value)
    }

    @Test
    fun newerOptionalReleaseIsShownAfterEarlierReleaseWasDismissed() = runBlocking {
        var latest = 2
        val gate = ClientCompatibilityGate(
            checkCompatibility = {
                compatibility("update_available", latestVersionCode = latest)
            },
        )
        gate.checkAtStartup()
        gate.dismissOptionalUpdate()

        latest = 3
        gate.recheckNonBlocking()

        val state = gate.state.value as ClientCompatibilityState.UpdateAvailable
        assertEquals(3, state.notice.latestVersionCode)
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
            checkCompatibility = { compatibility("supported") },
        )
        gate.requireUpdate(
            ClientUpdateNotice("Update now", "https://updates.example.test/app.apk", 1, 2, 3),
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
        )
        val gate = ClientCompatibilityGate(
            checkCompatibility = { compatibility("supported") },
            initialRequiredNotice = restored,
        )

        assertEquals(ClientCompatibilityState.UpdateRequired(restored), gate.state.value)
        gate.checkAtStartup()
        assertEquals(ClientCompatibilityState.UpdateRequired(restored), gate.state.value)
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
    ) = ClientCompatibilityResponse(
        platform = "android",
        currentVersionCode = 1,
        minimumSupportedVersionCode = 1,
        latestVersionCode = latestVersionCode,
        status = status,
        updateUrl = "https://updates.example.test/app.apk",
        message = "Compatibility message",
        checkedAt = "2026-08-25T12:00:00Z",
    )
}
