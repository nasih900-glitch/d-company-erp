package cloud.dcompany.erp.core.diagnostics

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticFailureIsolationTest {
    @Test
    fun failedCrashImportRetainsMarkerAndDoesNotEscapeToFatalHandler() = runBlocking {
        val failure = IOException("diagnostic storage unavailable")
        var acknowledged = false
        var reported: Exception? = null
        val result = isolateDiagnosticFailure({ reported = it }) {
            suspendCapture { throw failure }
            acknowledged = true
        }
        assertNull(result)
        assertSame(failure, reported)
        assertFalse(acknowledged)
    }

    @Test
    fun captureCanRetryAfterStorageRecoversAndOnlyThenAcknowledge() = runBlocking {
        var attempts = 0
        var acknowledged = false
        repeat(2) {
            isolateDiagnosticFailure({}) {
                suspendCapture {
                    attempts += 1
                    if (attempts == 1) throw IOException("storage unavailable")
                }
                acknowledged = true
            }
        }
        assertEquals(2, attempts)
        assertTrue(acknowledged)
    }

    @Test
    fun schedulerOrFailureLoggerExceptionDoesNotCrashCaller() {
        assertNull(
            isolateDiagnosticFailure({ throw IllegalStateException("logger unavailable") }) {
                throw IllegalStateException("scheduler unavailable")
            },
        )
    }

    @Test
    fun cancellationRemainsControlFlowAndNeverAcknowledgesMarker() {
        val cancelled = CancellationException("scope ended")
        var reported = false
        val actual = assertThrows(CancellationException::class.java) {
            isolateDiagnosticFailure({ reported = true }) { throw cancelled }
        }
        assertSame(cancelled, actual)
        assertFalse(reported)
    }

    private suspend fun suspendCapture(store: () -> Unit) = store()
}
