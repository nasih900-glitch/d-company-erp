package cloud.dcompany.erp.core.sync

import cloud.dcompany.erp.core.handover.*
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class SyncWorkerAdmissionTest {
    private class Store : HandoverIntentStore {
        var retained: HandoverIntent? = null
        override suspend fun read() = retained
        override suspend fun begin(intent: HandoverIntent) { retained = intent }
        override suspend fun compareAndSet(expected: HandoverIntent, next: HandoverIntent): Unit = error("unused")
        override suspend fun cancelNotSent(expected: HandoverIntent): Unit = error("unused")
    }
    @Test fun broadAndKitchenReservedGateIsReleasedWhenFreezeWinsBeforeBodyEntry() = runBlocking {
        for (kind in listOf("broad", "kitchen")) {
            val gate = ConflatedSyncRequestGate(); val lease = requireNotNull(gate.request(1))
            val authority = HandoverAuthority(HandoverScope(id(), id(), id(), id()), 1)
            val coordinator = HandoverCoordinator(Store()); coordinator.initialize(authority)
            var refused = 0; var entered = false
            MockWebServer().use { server ->
                server.start(); val client = OkHttpClient()
                try {
                    val worker = launch(start = CoroutineStart.LAZY) {
                        runSessionWorkerWithAdmission(
                            admit = { body -> coordinator.runOperation(authority) { body(); OperationResult(Unit, OperationOutcome.NO_WRITE_SENT) } },
                            onRegistrationRejected = { gate.revoke(lease) },
                            onAdmissionRefused = { refused++ },
                        ) {
                            entered = true
                            client.newCall(Request.Builder().url(server.url("/$kind")).build()).execute().close()
                        }
                    }
                    coordinator.freeze(authority, HandoverAction(id(), authority, id(), id(), 0))
                    worker.start(); worker.join()
                    assertEquals(1, refused); assertFalse(entered); assertEquals(0, server.requestCount)
                    assertFalse(gate.claimPass(lease))
                    assertNotNull(gate.request(1)) // Exact old reservation is no longer wedged.
                } finally { client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
            }
        }
    }
    @Test fun lateRefusalCannotRevokeAReplacementCohort() = runBlocking {
        val gate = ConflatedSyncRequestGate(); val a = requireNotNull(gate.request(1)); val b = requireNotNull(gate.request(2))
        runSessionWorkerWithAdmission({ throw IllegalStateException("Frozen original A") }, { gate.revoke(a) }, {}) { error("No body") }
        assertTrue(gate.claimPass(b)); assertFalse(gate.claimPass(a))
    }
    @Test fun preBodyCancellationReleasesReservationButEnteredBodyErrorsAreNotSwallowed() = runBlocking {
        val gate = ConflatedSyncRequestGate(); val a = requireNotNull(gate.request(1)); val waiting = CompletableDeferred<Unit>()
        val worker = launch {
            runSessionWorkerWithAdmission({ waiting.complete(Unit); CompletableDeferred<Unit>().await() }, { gate.revoke(a) }, { error("Cancellation is not an admission error") }) { error("No body") }
        }
        waiting.await(); worker.cancel(); worker.join(); assertNotNull(gate.request(1))
        var rejected = false
        val failed = runCatching {
            runSessionWorkerWithAdmission({ it() }, { rejected = true }, { error("An entered error must propagate") }) { throw IOException("Entered body failure") }
        }.exceptionOrNull()
        assertTrue(failed is IOException); assertFalse(rejected)
    }
    companion object { private fun id() = UUID.randomUUID().toString() }
}
