package cloud.dcompany.erp.core.net

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class ForegroundCompatibilityPollerTest {
    @Test fun repeatedForegroundStartIsSingleflightAndBackgroundCancelsRequest()=runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val entered=CompletableDeferred<Unit>();val cancelled=CompletableDeferred<Unit>();val calls=AtomicInteger()
        try {
            val poller=ForegroundCompatibilityPoller(scope,{true},{calls.incrementAndGet();entered.complete(Unit)
                try{awaitCancellation()}finally{cancelled.complete(Unit)}},intervalMillis=10)
            poller.start();poller.start();withTimeout(3000){entered.await()}
            assertEquals(1,calls.get());poller.stop();withTimeout(3000){cancelled.await()}
            delay(30);assertEquals(1,calls.get())
        }finally{scope.cancel()}
    }
    @Test fun failuresBackOffAndSuccessReturnsToForegroundCadence()=runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val times=java.util.concurrent.CopyOnWriteArrayList<Long>()
        val completed=CompletableDeferred<Unit>()
        try {
            val poller=ForegroundCompatibilityPoller(scope,{true},{times.add(System.nanoTime())
                if(times.size==4)completed.complete(Unit);times.size>=3},intervalMillis=20,maximumBackoffMillis=80)
            poller.start();withTimeout(3000){completed.await()};poller.stop()
            val gaps=times.zipWithNext{a,b->(b-a)/1_000_000}
            assertTrue(gaps[0]>=35);assertTrue(gaps[1]>=70);assertTrue(gaps[2]>=15)
        }finally{scope.cancel()}
    }
}
