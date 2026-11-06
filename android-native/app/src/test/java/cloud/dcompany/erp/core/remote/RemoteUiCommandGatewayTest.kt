package cloud.dcompany.erp.core.remote

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class RemoteUiCommandGatewayTest {
    private fun host(refresh: suspend () -> Boolean) = RemoteUiCommandHost({ "help" }, { emptySet() }, { true }, {}, refresh)
    @Test fun fullRefreshIsAwaitedAndOriginalSessionRecheckedAfterCompletion() = runBlocking<Unit> {
        val gateway = RemoteUiCommandGateway(Dispatchers.Unconfined)
        val gate = CompletableDeferred<Unit>(); var current = true
        gateway.attach(host { gate.await(); true })
        val request = async(Dispatchers.Unconfined) { gateway.refreshCurrent { current } }
        assertFalse(request.isCompleted)
        current = false; gate.complete(Unit)
        assertEquals(RemoteUiCommandResult(false, "session_inactive"), request.await())
    }
    @Test fun replacedHostDoesNotTurnOldRefreshIntoCurrentSuccessOrRefreshB() = runBlocking<Unit> {
        val gateway = RemoteUiCommandGateway(Dispatchers.Unconfined)
        val gate = CompletableDeferred<Unit>(); var bReads = 0
        val a = host { gate.await(); true }; val b = host { bReads++; true }
        gateway.attach(a)
        val request = async(Dispatchers.Unconfined) { gateway.refreshCurrent { true } }
        assertFalse(request.isCompleted); gateway.attach(b); gate.complete(Unit)
        assertEquals(RemoteUiCommandResult(false, "not_in_foreground"), request.await()); assertEquals(0, bReads)
        gateway.detach(a); assertTrue(gateway.refreshCurrent { true }.succeeded); assertEquals(1, bReads)
    }
    @Test fun cancelledRefreshNeverReturnsSuccessfulAcknowledgement() = runBlocking<Unit> {
        val gateway = RemoteUiCommandGateway(Dispatchers.Unconfined); val gate = CompletableDeferred<Unit>()
        var finished = false
        gateway.attach(host { try { gate.await(); true } finally { finished = true } })
        val request = async(Dispatchers.Unconfined) { gateway.refreshCurrent { true } }
        request.cancelAndJoin(); assertTrue(request.isCancelled); assertTrue(finished)
    }
}
