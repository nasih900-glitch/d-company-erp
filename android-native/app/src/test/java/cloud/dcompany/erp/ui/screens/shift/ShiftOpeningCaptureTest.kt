package cloud.dcompany.erp.ui.screens.shift

import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShiftOpeningCaptureTest {
    @Test fun `two entry points share busy and capture one identity while the first insert is delayed`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val busy = MutableStateFlow(false)
            val insert = CompletableDeferred<Unit>()
            val identities = mutableListOf<String>()
            val persisted = mutableListOf<String>()
            val save: suspend () -> Unit = {
                val identity = UUID.randomUUID().toString()
                identities += identity
                insert.await()
                persisted += identity
            }
            val inlineOpen = { launchShiftOpeningCapture(scope, busy, save) }
            val shiftTabOpen = { launchShiftOpeningCapture(scope, busy, save) }
            assertTrue(inlineOpen())
            assertTrue(busy.value)
            assertFalse(shiftTabOpen())
            assertEquals(1, identities.size)
            assertTrue(persisted.isEmpty())
            insert.complete(Unit)
            assertEquals(identities, persisted)
            assertFalse(busy.value)
        } finally { scope.cancel() }
    }

    @Test fun `cancelled capture releases only its own shared gate`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val busy = MutableStateFlow(false)
        val insert = CompletableDeferred<Unit>()
        assertTrue(launchShiftOpeningCapture(scope, busy) { insert.await() })
        assertFalse(launchShiftOpeningCapture(scope, busy) { error("second identity must not be allocated") })
        scope.cancel()
        assertFalse(busy.value)
    }
}
