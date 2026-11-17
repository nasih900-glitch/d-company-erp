package cloud.dcompany.erp.ui.screens.shift.work

import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class StartWorkControllerTest {
    private val a = "11111111-1111-4111-8111-111111111111"
    private val c = "22222222-2222-4222-8222-222222222222"
    private val b = "33333333-3333-4333-8333-333333333333"
    private val t = "44444444-4444-4444-8444-444444444444"
    private val i = "55555555-5555-4555-8555-555555555555"
    private class Store : StartWorkStore {
        var held: PendingStartWork? = null; var savedBeforeSend = false; var failSave = false
        override suspend fun pending() = listOfNotNull(held)
        override suspend fun save(expected: PendingStartWork?, next: PendingStartWork) {
            check(held == expected); if (failSave) throw IOException("fsync")
            next.validate(); held = next; savedBeforeSend = true
        }
        override suspend fun archive(expected: PendingStartWork, outcome: StartWorkOutcome, stillCurrent: () -> Boolean) {
            check(stillCurrent() && held == expected); outcome.validate(expected.reference); held = null
        }
    }
    private inner class Runtime(val store: Store) : StartWorkRuntime {
        var live = true; var binding = "a".repeat(64); var sends = 0; var observes = 0
        var failAfterReceipt = false; var committed = false; var closed = false; var lostReply = false
        override fun current() = live
        override fun online() = true
        override suspend fun capture() = StartWorkAuthority(a,c,b,t,i,"Employee",binding) { live }
        fun receipt(p: PendingStartWork) = StartWorkReceipt(a,c,a,b,t,i,t,"open",p.reference.openingFloatMinor,
            i,"2026-11-12T00:00:00Z",true,false,"2026-11-12T00:00:00Z")
        fun result(p: PendingStartWork) = StartWorkOutcome("committed",receipt(p),currentShift = StartWorkCurrentShift(t,c,b,t,if(closed) "closed" else "open","android",i))
        override suspend fun attempt(authority: StartWorkAuthority, pending: PendingStartWork,
            receipt: suspend (StartWorkReceipt) -> Unit, complete: suspend (StartWorkOutcome) -> Unit) {
            check(store.savedBeforeSend && store.held == pending); sends++; committed = true
            if (lostReply) throw IOException("reply lost")
            receipt(receipt(pending)); check(store.held?.phase == "CONFIRMED_AWAITING_HYDRATION")
            if (failAfterReceipt) throw IOException("exact shift read failed")
            complete(result(pending))
        }
        override suspend fun observe(pending: PendingStartWork, ownerEmail: String?, password: String?, cancel: Boolean,
            receipt: suspend (StartWorkReceipt) -> Unit, complete: suspend (StartWorkOutcome) -> Unit) {
            observes++; if (!committed) throw IOException("unconfirmed")
            receipt(receipt(pending)); complete(result(pending))
        }
    }
    @Test fun journalMustBeDurableBeforeAnyWireAndIOFailurePreventsDispatch() = runBlocking {
        val store = Store(); val runtime = Runtime(store); val controller = StartWorkController(runtime,store)
        store.failSave = true; controller.start(0); assertEquals(0,runtime.sends); assertNull(store.held)
        store.failSave = false; controller.start(125); assertEquals(1,runtime.sends); assertNull(store.held)
    }
    @Test fun lostReplyUsesExactOriginalIdentityAndNewLoginOnlyObserves() = runBlocking {
        val store = Store(); val runtime = Runtime(store); val controller = StartWorkController(runtime,store)
        runtime.lostReply = true; controller.start(0); val first = requireNotNull(store.held)
        controller.start(0); assertEquals(1,runtime.sends); assertEquals(first,store.held)
        runtime.binding = "b".repeat(64); runtime.closed = true
        controller.retry(first); assertEquals(1,runtime.sends); assertEquals(1,runtime.observes); assertNull(store.held)
    }
    @Test fun savedReceiptIsNotOpenShiftAuthorityAndHydrationFailureRetainsIt() = runBlocking {
        val store = Store(); val runtime = Runtime(store); val controller = StartWorkController(runtime,store)
        runtime.failAfterReceipt = true; controller.start(0)
        val value = requireNotNull(store.held); assertEquals("CONFIRMED_AWAITING_HYDRATION",value.phase)
        runtime.failAfterReceipt = false; controller.retry(value)
        assertEquals(1,runtime.sends); assertEquals(1,runtime.observes); assertNull(store.held)
    }
    @Test fun exactClosedProofRequiredAndUnconfirmedDoesNotClear() = runBlocking {
        val store = Store(); val runtime = Runtime(store); val controller = StartWorkController(runtime,store)
        runtime.lostReply = true; controller.start(0); val pending = requireNotNull(store.held)
        runtime.binding = "b".repeat(64); runtime.committed = false; controller.retry(pending)
        assertEquals(pending,store.held)
        assertThrows(IllegalArgumentException::class.java) { StartWorkOutcome("committed", runtime.receipt(pending)).validate(pending.reference) }
        assertThrows(IllegalArgumentException::class.java) { runtime.result(pending).copy(currentShift = runtime.result(pending).currentShift!!.copy(id = i)).validate(pending.reference) }
        runtime.committed = true; runtime.closed = true; controller.retry(pending); assertNull(store.held)
    }
    @Test fun archiveResumeAllowsClosedObservationButRefusesDifferentImmutableReceipt() = runBlocking {
        val store=Store();val runtime=Runtime(store);val controller=StartWorkController(runtime,store)
        runtime.failAfterReceipt=true;controller.start(0);val pending=requireNotNull(store.held)
        val original=runtime.result(pending)
        val archived=pending.copy(phase="RESOLVED",resolution=original)
        val closed=original.copy(currentShift=original.currentShift!!.copy(status="closed"))
        validateArchivedStartWork(pending,archived,closed)
        val conflicting=closed.copy(receipt=closed.receipt!!.copy(attendanceId=java.util.UUID.randomUUID().toString()))
        assertTrue(runCatching{validateArchivedStartWork(pending,archived,conflicting)}.isFailure)
        assertTrue(runCatching{validateArchivedStartWork(pending,archived.copy(authBinding="b".repeat(64)),closed)}.isFailure)
    }

}
