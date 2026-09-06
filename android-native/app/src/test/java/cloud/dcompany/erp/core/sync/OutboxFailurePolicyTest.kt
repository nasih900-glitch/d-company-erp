package cloud.dcompany.erp.core.sync

import cloud.dcompany.erp.core.net.ApiException
import java.io.IOException
import kotlinx.serialization.SerializationException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OutboxFailurePolicyTest {
    @Test
    fun `timeout keeps the original payment and drawer count replayable`() {
        listOf(null, 408, 500, 502, 503, 504).forEach { status ->
            val failure = ApiException("response unavailable", status)
            assertTrue("HTTP $status must not unlock a new financial action", failure.isAmbiguous)
            assertTrue(mustReplayUnconfirmedWrite(failure))
        }
    }

    @Test
    fun `conversion and storage failures after commit do not become business refusals`() {
        listOf(
            SerializationException("response did not match the DTO"),
            IOException("response body ended early"),
            IllegalStateException("local receipt could not be committed"),
        ).forEach { failure -> assertTrue(mustReplayUnconfirmedWrite(failure)) }
    }

    @Test
    fun `only definitive server refusals permit correction and explicit retry`() {
        listOf(400, 403, 404, 409, 422).forEach { status ->
            assertFalse(mustReplayUnconfirmedWrite(ApiException("request refused", status)))
        }
        assertTrue(
            mustReplayUnconfirmedWrite(ApiException("wait", 409, "idempotency_in_progress")),
        )
        assertTrue(mustReplayUnconfirmedWrite(ApiException("update required", 426)))
    }

    @Test
    fun `uncertain result feedback prevents collecting again and explains recovery`() {
        val message = unconfirmedWriteMessage("this shift close")
        assertTrue(message.contains("may already be saved"))
        assertTrue(message.contains("original request"))
        assertTrue(message.contains("Do not repeat it or collect payment again"))
        assertTrue(message.contains("use Help"))
    }
}
