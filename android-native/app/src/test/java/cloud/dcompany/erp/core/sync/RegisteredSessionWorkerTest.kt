package cloud.dcompany.erp.core.sync

import cloud.dcompany.erp.core.handover.*
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class RegisteredSessionWorkerTest {
    @Test fun cancelledParentOrCancellationDuringRegistrationNeverRunsBodyOrLeaksExactReservation() = runBlocking {
        for (before in listOf(false, true)) MockWebServer().use { server ->
            server.start(); val client = OkHttpClient()
            val uncaught = java.util.Collections.synchronizedList(mutableListOf<Throwable>())
            val parent = SupervisorJob()
            val scope = CoroutineScope(parent + Dispatchers.Default + CoroutineExceptionHandler { _, error -> uncaught += error })
            val gate = ConflatedSyncRequestGate(); val lease = requireNotNull(gate.request(1))
            val guard = Any(); val registry = mutableMapOf<kotlinx.coroutines.Job, ConflatedSyncRequestGate.WorkerLease>()
            var rejected = 0; var body = false
            if (before) parent.cancel()
            try {
                val job = launchRegisteredSessionWorker(scope,
                    register = { worker -> synchronized(guard) { registry[worker] = lease }; if (!before) parent.cancel(); true },
                    unregister = { worker -> synchronized(guard) { if (registry[worker] == lease) registry.remove(worker) } },
                    onRegistrationRejected = { rejected++; gate.revoke(lease) },
                ) { reject ->
                    runSessionWorkerWithAdmission({ it() }, reject, { error("No admission call expected") }) {
                        body = true; client.newCall(Request.Builder().url(server.url("/unexpected")).build()).execute().close()
                    }
                }
                job.join()
                assertFalse(body); assertEquals(0, server.requestCount)
                assertTrue(registry.isEmpty()); assertEquals(1, rejected)
                assertFalse(gate.claimPass(lease)); assertNotNull(gate.request(1))
                assertTrue(uncaught.isEmpty())
            } finally { scope.cancel(); client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
        }
    }
    @Test fun oldCompletionCannotRevokeReplacementCohort() = runBlocking {
        val parent = SupervisorJob(); val scope = CoroutineScope(parent + Dispatchers.Default)
        val gate = ConflatedSyncRequestGate(); val old = requireNotNull(gate.request(1))
        var replacement: ConflatedSyncRequestGate.WorkerLease? = null
        val map = mutableMapOf<kotlinx.coroutines.Job, Any>()
        val job = launchRegisteredSessionWorker(scope,
            register = { worker -> map[worker] = old; replacement = gate.request(2); parent.cancel(); true },
            unregister = { worker -> if (map[worker] === old) map.remove(worker) },
            onRegistrationRejected = { gate.revoke(old) },
        ) { error("Cancelled before start") }
        job.join(); assertTrue(map.isEmpty()); assertTrue(gate.claimPass(requireNotNull(replacement)))
    }
    @Test fun completionDoesNotEraseUnresolvedEnteredCoordinatorWork() = runBlocking {
        val store = object : HandoverIntentStore {
            override suspend fun read(): HandoverIntent? = null
            override suspend fun begin(intent: HandoverIntent): Unit = error("No handover")
            override suspend fun compareAndSet(expected: HandoverIntent, next: HandoverIntent): Unit = error("No handover")
            override suspend fun cancelNotSent(expected: HandoverIntent): Unit = error("No handover")
        }
        val id = { UUID.randomUUID().toString() }
        val a = HandoverAuthority(HandoverScope(id(), id(), id(), id()), 1)
        val c = HandoverCoordinator(store); c.initialize(a)
        val error = IOException("Synthetic entered write reply lost")
        val observed = java.util.Collections.synchronizedList(mutableListOf<Throwable>())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> observed += e })
        val map = java.util.Collections.synchronizedMap(mutableMapOf<kotlinx.coroutines.Job, Boolean>())
        var permit: HandoverCoordinator.Permit? = null
        try {
            val job = launchRegisteredSessionWorker(scope, { map[it] = true; true }, { map.remove(it) },
                onRegistrationRejected = { error("An entered failure is not rejected registration") }) { reject ->
                runSessionWorkerWithAdmission(
                    admit = { body -> c.runOperation(a) { p -> permit = p; body(); OperationResult(Unit, OperationOutcome.NO_WRITE_SENT) } },
                    onRegistrationRejected = reject,
                    onAdmissionRefused = { error("An entered failure must propagate") },
                ) { c.beforeMutationDispatch(requireNotNull(permit), a); throw error }
            }
            job.join()
            assertTrue(map.isEmpty()); assertEquals(1, observed.size)
            val propagated = observed.single()
            assertTrue(propagated is IOException); assertEquals(error.message, propagated.message)
            // Coroutine stack-trace recovery may copy the IOException, retaining its original cause.
            assertTrue(generateSequence(propagated) { it.cause }.take(8).any { it === error })
            assertTrue(c.state.value.unresolvedOperations.contains(requireNotNull(permit).id))
        } finally { scope.cancel() }
    }
}
