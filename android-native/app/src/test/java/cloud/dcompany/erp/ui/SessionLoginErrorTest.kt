package cloud.dcompany.erp.ui

import cloud.dcompany.erp.core.auth.OutboxGateResult
import cloud.dcompany.erp.core.net.ApiException
import cloud.dcompany.erp.core.net.MeResponse
import java.util.Base64
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionLoginErrorTest {

    @Test
    fun `invalid credentials are explained without exposing backend wording`() {
        assertEquals(
            "Email or password is incorrect. Check both fields and try again.",
            loginErrorMessage(ApiException("invalid credentials", status = 401, code = "auth_error")),
        )
    }

    @Test
    fun `locked account tells employee what to do next`() {
        assertEquals(
            "This account is temporarily locked after several failed attempts. " +
                "Wait, then try again or ask an owner.",
            loginErrorMessage(ApiException("account temporarily locked", status = 401, code = "auth_error")),
        )
    }

    @Test
    fun `transport failure gives a connection action`() {
        assertEquals(
            "The server could not be reached. Check the connection and try again.",
            loginErrorMessage(ApiException("timeout")),
        )
    }

    @Test
    fun `forced logout reason is plain language and contains no server detail`() {
        assertEquals(
            "Your access changed or this sign-in expired. Sign in again. Ask a manager if this was unexpected.",
            FORCED_LOGOUT_MESSAGE,
        )
        assertFalse(FORCED_LOGOUT_MESSAGE.contains("auth_version", ignoreCase = true))
        assertFalse(FORCED_LOGOUT_MESSAGE.contains("401"))
    }

    @Test
    fun `blocked sign out stays operational feedback rather than a login error`() {
        assertNull(
            loginErrorAfterSignOutDecision(
                currentLoginError = null,
                decision = OutboxGateResult.Blocked("Finish the gaming handoff."),
            ),
        )
    }

    @Test
    fun `successful sign out clears stale feedback before next employee login`() {
        assertNull(
            loginErrorAfterSignOutDecision(
                currentLoginError = "Sign-out blocked: finish the gaming handoff.",
                decision = OutboxGateResult.Allowed,
            ),
        )
    }

    @Test
    fun `cached authenticated employee is published before server refresh can finish`() = runBlocking {
        val published = CompletableDeferred<Unit>()
        val remoteStarted = CompletableDeferred<Unit>()
        val releaseRemote = CompletableDeferred<Unit>()

        val restore = launch(Dispatchers.Default) {
            restoreCachedBeforeRemote(
                cached = "cached-employee",
                activateCached = { true },
                publishCached = { published.complete(Unit) },
                refreshRemote = { cachedSessionActive ->
                    if (cachedSessionActive) remoteStarted.complete(Unit)
                    releaseRemote.await()
                },
            )
        }

        try {
            withTimeout(1_000) {
                published.await()
                remoteStarted.await()
            }
            assertFalse("remote validation must still be blocked", restore.isCompleted)
        } finally {
            releaseRemote.complete(Unit)
            restore.join()
        }
    }

    @Test
    fun `offline restore keeps cached identity and does not request sign out`() {
        val action = restoreFailureAction(
            cachedSessionActive = true,
            error = ApiException("server unreachable", status = null, code = "network_error"),
        )

        assertEquals(RestoreFailureAction.KEEP_CACHED_SESSION, action)
        assertFalse(action == RestoreFailureAction.SIGN_OUT)
    }

    @Test
    fun `cacheless offline restore gives retry screen instead of endless loading`() {
        assertEquals(
            RestoreFailureAction.SHOW_UNREACHABLE,
            restoreFailureAction(
                cachedSessionActive = false,
                error = ApiException("server unreachable", status = null, code = "network_error"),
            ),
        )
    }

    @Test
    fun `only definitive authentication rejection signs out a restored session`() {
        assertEquals(
            RestoreFailureAction.SIGN_OUT,
            restoreFailureAction(
                cachedSessionActive = true,
                error = ApiException("expired", status = 401, code = "auth_error"),
            ),
        )
        assertEquals(
            RestoreFailureAction.SIGN_OUT,
            restoreFailureAction(
                cachedSessionActive = true,
                error = ApiException("forbidden", status = 403, code = "auth_error"),
            ),
        )
    }

    @Test
    fun `cached employee must match the encrypted token identity`() {
        val profile = MeResponse(
            userId = "user-a",
            email = "employee@example.com",
            name = "Employee",
            companyId = "company-1",
            branchId = "branch-1",
        )
        val matching = jwt(
            """{"sub":"user-a","company_id":"company-1","branch_id":"branch-1"}""",
        )
        val differentUser = jwt(
            """{"sub":"user-b","company_id":"company-1","branch_id":"branch-1"}""",
        )

        assertTrue(cachedProfileMatchesToken(matching, profile))
        assertFalse(cachedProfileMatchesToken(differentUser, profile))
        assertFalse(cachedProfileMatchesToken("not-a-jwt", profile))
    }

    private fun jwt(payload: String): String {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        return listOf("{}", payload, "signature")
            .joinToString(".") { encoder.encodeToString(it.encodeToByteArray()) }
    }
}
