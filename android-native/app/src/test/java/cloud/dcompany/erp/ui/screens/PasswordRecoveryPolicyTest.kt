package cloud.dcompany.erp.ui.screens

import cloud.dcompany.erp.core.net.ApiClient
import cloud.dcompany.erp.core.net.ApiException
import cloud.dcompany.erp.core.net.PasswordResetChallenge
import cloud.dcompany.erp.core.net.PasswordResetConfirmRequest
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PasswordRecoveryPolicyTest {

    @Test
    fun `email is normalised without testing whether account exists`() {
        assertEquals("employee@example.com", normalizedRecoveryEmail(" Employee@Example.COM "))
        assertNull(recoveryEmailError("employee@example.com"))
        assertEquals("Enter a valid login email.", recoveryEmailError("employee"))
    }

    @Test
    fun `approval code keeps only first six digits`() {
        assertEquals("123456", filteredApprovalCode("1a2 3-456789"))
        assertEquals(257, boundedRecoveryPassword("x".repeat(20_000)).length)
    }

    @Test
    fun `new password validation enforces code length bounds and equality`() {
        assertEquals(
            "Enter the 6-digit approval code.",
            passwordRecoveryValidation("123", "1234567890", "1234567890"),
        )
        assertEquals(
            "New password must be at least 10 characters.",
            passwordRecoveryValidation("123456", "short", "short"),
        )
        assertEquals(
            "New password must be no more than 256 characters.",
            passwordRecoveryValidation("123456", "x".repeat(257), "x".repeat(257)),
        )
        assertEquals(
            "New passwords do not match.",
            passwordRecoveryValidation("123456", "long-enough", "different-one"),
        )
        assertNull(passwordRecoveryValidation("123456", "long-enough", "long-enough"))
    }

    @Test
    fun `wrong expired rate limited and offline failures are actionable and sanitized`() {
        assertEquals(
            "That approval code is incorrect or expired. Request a new code and try again.",
            passwordRecoveryError(
                ApiException("database details", status = 422, code = "business_rule"),
                PasswordRecoveryPhase.CONFIRM,
            ),
        )
        assertEquals(
            "Too many approval-code requests. Wait 10 minutes before trying again.",
            passwordRecoveryError(
                ApiException("internal limiter key", status = 429, code = "rate_limited"),
                PasswordRecoveryPhase.REQUEST,
            ),
        )
        assertEquals(
            "The server could not be reached. Check the connection and try again.",
            passwordRecoveryError(ApiException("socket trace"), PasswordRecoveryPhase.REQUEST),
        )
        assertEquals(
            "Password recovery is temporarily unavailable. Try again later or ask the protected owner.",
            passwordRecoveryError(
                ApiException("SMTP host secret", status = 503, code = "service_unavailable"),
                PasswordRecoveryPhase.REQUEST,
            ),
        )
    }

    @Test
    fun `rapid taps dedupe and cancellation rejects late results`() {
        val gate = PasswordRecoveryOperationGate()
        val first = checkNotNull(gate.begin())

        assertNull(gate.begin())
        assertTrue(gate.isCurrent(first))
        gate.invalidate()
        assertFalse(gate.isCurrent(first))
        assertFalse(gate.finish(first))
        assertTrue(gate.begin() != null)
    }

    @Test
    fun `wire payload uses backend snake case contract`() {
        val encoded = ApiClient.json.encodeToString(
            PasswordResetConfirmRequest(
                challengeId = "challenge-id",
                code = "123456",
                newPassword = "new-password",
            ),
        )
        val challenge = ApiClient.json.decodeFromString(
            PasswordResetChallenge.serializer(),
            """{"challenge_id":"id","expires_in":600,"destination":"o***@example.com"}""",
        )

        assertTrue(encoded.contains("\"challenge_id\""))
        assertTrue(encoded.contains("\"new_password\""))
        assertEquals(600, challenge.expiresIn)
    }

    @Test
    fun `state diagnostics never expose approval code or passwords`() {
        val rendered = PasswordRecoveryState(
            open = true,
            code = "123456",
            newPassword = "very-secret-password",
            confirmPassword = "very-secret-password",
        ).toString()

        assertFalse(rendered.contains("123456"))
        assertFalse(rendered.contains("very-secret-password"))
        assertTrue(rendered.contains("<redacted>"))
    }

    @Test
    fun `backend expiry is presented in whole minutes`() {
        assertEquals("The approval code expires in 1 minute.", approvalExpiryLabel(1))
        assertEquals("The approval code expires in 2 minutes.", approvalExpiryLabel(61))
    }
}
