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
