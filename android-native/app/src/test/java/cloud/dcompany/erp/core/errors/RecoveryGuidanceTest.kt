package cloud.dcompany.erp.core.errors

import cloud.dcompany.erp.core.net.ApiException
import java.io.IOException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryGuidanceTest {

    @Test
    fun `read timeout preserves saved data and allows a normal retry`() {
        val guidance = recoveryGuidance(
            RecoveryRisk.READ,
            ApiException("timeout"),
            subject = "Gaming",
            localStatePreserved = true,
        )

        assertTrue(guidance.message.contains("Saved data remains available"))
        assertTrue(guidance.retrySameOperation)
        assertFalse(guidance.verifyBeforeNewAction)
        assertTrue(guidance.preserveLocalWork)
    }

    @Test
    fun `first read failure never pretends a saved snapshot exists`() {
        val guidance = recoveryGuidance(
            RecoveryRisk.READ,
            IOException("socket closed"),
            subject = "Finance",
            localStatePreserved = false,
        )

        assertTrue(guidance.message.startsWith("Could not load Finance"))
        assertFalse(guidance.message.contains("remains available"))
        assertFalse(guidance.preserveLocalWork)
    }

    @Test
    fun `read permission refusal sends the employee to an owner instead of retry loop`() {
        val guidance = recoveryGuidance(
            RecoveryRisk.READ,
            ApiException("forbidden", status = 403),
            subject = "Finance",
        )

        assertTrue(guidance.message.contains("Ask an owner"))
        assertFalse(guidance.retrySameOperation)
    }

    @Test
    fun `ambiguous idempotent write forbids inventing another request`() {
        val guidance = recoveryGuidance(
            RecoveryRisk.IDEMPOTENT_WRITE,
            ApiException("gateway timeout", status = 504),
            subject = "Gaming extension",
            localStatePreserved = true,
        )

        assertTrue(guidance.message.contains("may already have reached the server"))
        assertTrue(guidance.message.contains("exact saved request"))
        assertTrue(guidance.retrySameOperation)
        assertTrue(guidance.verifyBeforeNewAction)
        assertTrue(guidance.preserveLocalWork)
    }

    @Test
    fun `ambiguous financial write explicitly blocks another collection or payment`() {
        val guidance = recoveryGuidance(
            RecoveryRisk.FINANCIAL_WRITE,
            ApiException("network", status = null, code = "network_error"),
            subject = "the POS payment",
            localStatePreserved = true,
        )

        assertTrue(guidance.message.contains("Do not enter, collect or pay it again"))
        assertTrue(guidance.message.contains("exact saved request"))
        assertTrue(guidance.verifyBeforeNewAction)
        assertTrue(guidance.preserveLocalWork)
    }

    @Test
    fun `definitive financial business refusal keeps useful reason without claiming ambiguity`() {
        val guidance = recoveryGuidance(
            RecoveryRisk.FINANCIAL_WRITE,
            ApiException(
                "Amount exceeds the live balance.",
                status = 422,
                code = "business_rule",
            ),
            subject = "the tip payout",
        )

        assertTrue(guidance.message.contains("Amount exceeds the live balance"))
        assertFalse(guidance.verifyBeforeNewAction)
        assertFalse(guidance.preserveLocalWork)
    }

    @Test
    fun `technical server text is not exposed to staff`() {
        val guidance = recoveryGuidance(
            RecoveryRisk.FINANCIAL_WRITE,
            ApiException("Internal server error", status = 422),
            subject = "the Finance entry",
        )

        assertFalse(guidance.message.contains("Internal server error"))
        assertTrue(guidance.message.contains("Correct the details"))
    }

    @Test
    fun `local capture failure states that nothing was queued`() {
        val guidance = recoveryGuidance(
            RecoveryRisk.LOCAL_CAPTURE,
            IOException("disk full"),
            subject = "this order",
        )

        assertTrue(guidance.message.contains("Nothing was queued"))
        assertTrue(guidance.message.contains("tablet storage"))
        assertFalse(guidance.preserveLocalWork)
    }

    @Test
    fun `auth transport failure gives the existing safe connection action`() {
        val guidance = recoveryGuidance(
            RecoveryRisk.AUTH,
            ApiException("raw socket detail"),
            subject = "sign-in",
        )

        assertTrue(guidance.message == "The server could not be reached. Check the connection and try again.")
        assertTrue(guidance.retrySameOperation)
    }

    @Test
    fun `auth rejection asks for a fresh sign in without automatic retry`() {
        val guidance = recoveryGuidance(
            RecoveryRisk.AUTH,
            ApiException("auth_version mismatch", status = 401),
            subject = "session verification",
        )

        assertTrue(guidance.message.contains("Sign in again"))
        assertFalse(guidance.retrySameOperation)
        assertFalse(guidance.message.contains("auth_version"))
    }

    @Test
    fun `definitive financial auth refusal never invents a saved checkpoint`() {
        val guidance = recoveryGuidance(
            RecoveryRisk.FINANCIAL_WRITE,
            ApiException("session expired", status = 401),
            subject = "the tip payout",
            localStatePreserved = false,
        )

        assertTrue(guidance.message.contains("server did not accept"))
        assertFalse(guidance.message.contains("saved request"))
        assertFalse(guidance.preserveLocalWork)
        assertFalse(guidance.verifyBeforeNewAction)
    }

    @Test
    fun `mandatory update retains money write recovery checkpoint`() {
        val guidance = recoveryGuidance(
            RecoveryRisk.FINANCIAL_WRITE,
            ApiException("client update", status = 426),
            subject = "Finance request",
            localStatePreserved = true,
        )

        assertTrue(guidance.message.contains("Update the app"))
        assertTrue(guidance.message.contains("retained"))
        assertTrue(guidance.preserveLocalWork)
        assertTrue(guidance.verifyBeforeNewAction)
        assertFalse(guidance.retrySameOperation)
    }
}
