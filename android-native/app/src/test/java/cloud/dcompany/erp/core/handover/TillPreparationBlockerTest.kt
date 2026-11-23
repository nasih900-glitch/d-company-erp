package cloud.dcompany.erp.core.handover

import org.junit.Assert.*
import org.junit.Test

class TillPreparationBlockerTest {
    private fun blocked(stage: TillPreparationStage, work: LocalHandoverWork? = null,
        claims: Int? = null, current: Boolean = true, storage: Boolean = false) =
        TillPreparationBlocker(stage, current, storage, 2, 1, claims, work)

    @Test fun verifiedSavedWorkNeverCallsAllWritesSales() {
        val text = blocked(TillPreparationStage.LOCAL_BEFORE_SERVER,
            LocalHandoverWork(true, setOf(LocalHandoverWorkKind.ROOM, LocalHandoverWorkKind.STAFF_TIME), roomPendingCount = 3)).guidance
        assertTrue(text.contains("3 saved tablet write(s)")); assertTrue(text.contains("attendance"))
        assertTrue(text.contains("not a sales count")); assertTrue(text.contains("Keep the current cashier"))
    }
    @Test fun partialReadCannotClaimZeroOrExactSales() {
        val text = blocked(TillPreparationStage.LOCAL_AFTER_SERVER,
            LocalHandoverWork(false, emptySet(), setOf(LocalHandoverWorkKind.ROOM))).guidance
        assertTrue(text.contains("unknown")); assertFalse(text.contains("0 saved"))
    }
    @Test fun stageSpecificActionsCoverCheckoutWorkersServerAndDurableEvidence() {
        assertTrue(blocked(TillPreparationStage.LOCAL_CHECKOUT, claims = 1).guidance.contains("Return to POS"))
        assertTrue(blocked(TillPreparationStage.WORKER_JOIN).guidance.contains("Background work"))
        assertTrue(blocked(TillPreparationStage.SERVER_CHECKOUT, claims = 0).guidance.contains("not been verified"))
        assertTrue(blocked(TillPreparationStage.SERVER_SHIFT).guidance.contains("Refresh Shift"))
        assertTrue(blocked(TillPreparationStage.DURABLE_SAVE).guidance.contains("retained handover recovery"))
    }
    @Test fun staleAuthorityAndStorageFaultOverrideOptimisticStageCounts() {
        assertTrue(blocked(TillPreparationStage.LOCAL_CHECKOUT, current = false).guidance.contains("workspace changed"))
        assertTrue(blocked(TillPreparationStage.OPERATION_DRAIN, storage = true).guidance.contains("do not erase"))
    }
    @Test fun traceSnapshotClearsPriorStageCountsAndDoesNotUseDebugText() {
        val trace = TillPreparationTrace()
        trace.local(LocalHandoverWork(true, setOf(LocalHandoverWorkKind.ROOM), roomPendingCount = 9))
        trace.at(TillPreparationStage.WORKER_JOIN)
        val error = trace.blocked(true, HandoverCoordinatorState())
        assertNull(error.blocker.local); assertNull(error.blocker.checkoutCount)
        assertTrue(error.message!!.contains("Background work"))
    }
}
