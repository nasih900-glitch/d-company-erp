package cloud.dcompany.erp.core.db

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ShiftRejectedOpenRecoveryDaoTest {

    private lateinit var db: ErpDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            ErpDatabase::class.java,
        ).addCallback(SHIFT_CLOSING_WRITE_GUARD_CALLBACK).build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun retryReusesExactLocalIdentityAndCapturedFacts() = runBlocking {
        db.shiftDao().insert(rejectedShift())

        val result = db.shiftDao().retryRejectedOpen(LOCAL_ID, TERMINAL, BRANCH)
        val row = db.shiftDao().byLocalId(LOCAL_ID)

        assertEquals(RejectedOpenRecoveryStatus.APPLIED, result.status)
        assertEquals(LOCAL_ID, row?.localId)
        assertEquals(50_000L, row?.openingFloatMinor)
        assertEquals(1_000L, row?.openedAtMillis)
        assertEquals(ShiftState.OPEN_PENDING, row?.state)
        assertNull(row?.lastError)
    }

    @Test
    fun verifiedClearIsRefusedWhileCapturedWorkReferencesStableShiftId() = runBlocking {
        db.shiftDao().insert(rejectedShift())
        db.syncMetaDao().put(SyncMetaEntity("shifts", 2_000))
        db.orderDao().capture(
            LocalOrderEntity(
                localId = "sale-a",
                shiftId = LOCAL_ID,
                type = "dine_in",
                estimateMinor = 1_000,
                paymentMethod = "cash",
                tenderedMinor = 1_000,
                createdAtMillis = 1_500,
                syncState = SyncState.PENDING,
            ),
            emptyList(),
        )

        val result = db.shiftDao().discardVerifiedRejectedOpen(
            LOCAL_ID,
            TERMINAL,
            BRANCH,
            verifiedAtMillis = 2_000,
        )

        assertEquals(RejectedOpenRecoveryStatus.DEPENDENT_WORK, result.status)
        assertEquals(ShiftState.OPEN_REJECTED, db.shiftDao().byLocalId(LOCAL_ID)?.state)
    }

    @Test
    fun verifiedClearRetainsRecoveryRecordInsteadOfDeletingIt() = runBlocking {
        db.shiftDao().insert(rejectedShift())
        db.syncMetaDao().put(SyncMetaEntity("shifts", 2_000))

        val result = db.shiftDao().discardVerifiedRejectedOpen(
            LOCAL_ID,
            TERMINAL,
            BRANCH,
            verifiedAtMillis = 2_000,
        )
        val row = db.shiftDao().byLocalId(LOCAL_ID)

        assertEquals(RejectedOpenRecoveryStatus.APPLIED, result.status)
        assertEquals(ShiftState.OPEN_DISCARDED, row?.state)
        assertEquals(LOCAL_ID, row?.localId)
    }

    @Test
    fun clearWithoutMatchingLiveVerificationReceiptIsRefused() = runBlocking {
        db.shiftDao().insert(rejectedShift())
        db.syncMetaDao().put(SyncMetaEntity("shifts", 1_999))

        val result = db.shiftDao().discardVerifiedRejectedOpen(
            LOCAL_ID,
            TERMINAL,
            BRANCH,
            verifiedAtMillis = 2_000,
        )

        assertEquals(RejectedOpenRecoveryStatus.LIVE_VERIFICATION_REQUIRED, result.status)
        assertEquals(ShiftState.OPEN_REJECTED, db.shiftDao().byLocalId(LOCAL_ID)?.state)
    }

    @Test
    fun closeResultAcknowledgementPersistsWithoutChangingHistoryState() = runBlocking {
        db.shiftDao().insert(
            rejectedShift().copy(
                state = ShiftState.CLOSED,
                serverShiftId = "server-shift",
                closedAtMillis = 2_000,
                lastError = null,
                closeResultPending = true,
            ),
        )

        assertEquals(1, db.shiftDao().acknowledgeCloseResult(LOCAL_ID))
        val row = db.shiftDao().byLocalId(LOCAL_ID)
        assertEquals(ShiftState.CLOSED, row?.state)
        assertEquals(false, row?.closeResultPending)
    }

    @Test
    fun genericServerPullDoesNotAbsorbHistoricalRejectedAttempt() = runBlocking {
        db.shiftDao().insert(rejectedShift())
        val later = serverShift(
            id = "later-server-shift",
            openedAtMillis = 1_000 + REJECTED_OPEN_RACE_TOLERANCE_MILLIS + 1,
        )

        db.shiftDao().reconcileServerOpen(TERMINAL, later, observedAtMillis = later.openedAtMillis)

        val row = db.shiftDao().byLocalId(LOCAL_ID)
        assertEquals(ShiftState.OPEN_REJECTED, row?.state)
        assertNull(row?.serverShiftId)
    }

    @Test
    fun explicitCausalRecoveryLinksOnlySelectedRejectedAttempt() = runBlocking {
        db.shiftDao().insert(rejectedShift())
        db.shiftDao().insert(
            rejectedShift().copy(
                localId = "other-rejected",
                openedAtMillis = 1_100,
            ),
        )
        val raced = serverShift(id = "race-winner", openedAtMillis = 1_500)

        val result = db.shiftDao().reconcileSelectedRejectedOpen(
            localId = LOCAL_ID,
            terminalId = TERMINAL,
            branchId = BRANCH,
            serverShiftId = raced.serverShiftId,
            serverOpenedByUserId = raced.openedByUserId,
            serverOpeningFloatMinor = raced.openingFloatMinor,
            serverOpenedAtMillis = raced.openedAtMillis,
        )

        assertEquals(RejectedOpenRecoveryStatus.APPLIED, result.status)
        assertEquals(ShiftState.OPEN_SUPERSEDED, db.shiftDao().byLocalId(LOCAL_ID)?.state)
        assertEquals("race-winner", db.shiftDao().byLocalId(LOCAL_ID)?.serverShiftId)
        assertEquals(
            true,
            db.shiftDao().byLocalId(LOCAL_ID)?.lastError.orEmpty()
                .contains("Server refused this shift open."),
        )
        assertEquals(ShiftState.OPEN_REJECTED, db.shiftDao().byLocalId("other-rejected")?.state)
        assertNull(db.shiftDao().byLocalId("other-rejected")?.serverShiftId)
    }

    @Test
    fun explicitRecoveryDoesNotLinkDifferentStaffOnSameTill() = runBlocking {
        db.shiftDao().insert(rejectedShift())

        val result = db.shiftDao().reconcileSelectedRejectedOpen(
            localId = LOCAL_ID,
            terminalId = TERMINAL,
            branchId = BRANCH,
            serverShiftId = "other-staff-shift",
            serverOpenedByUserId = "user-b",
            serverOpeningFloatMinor = 50_000,
            serverOpenedAtMillis = 1_000,
        )

        assertEquals(RejectedOpenRecoveryStatus.UNRELATED_SERVER_SHIFT, result.status)
        assertEquals(ShiftState.OPEN_REJECTED, db.shiftDao().byLocalId(LOCAL_ID)?.state)
        assertNull(db.shiftDao().byLocalId(LOCAL_ID)?.serverShiftId)
    }

    @Test
    fun explicitRecoveryDoesNotLinkDifferentFloatOrOutOfWindowClock() = runBlocking {
        db.shiftDao().insert(rejectedShift())

        val differentFloat = db.shiftDao().reconcileSelectedRejectedOpen(
            localId = LOCAL_ID,
            terminalId = TERMINAL,
            branchId = BRANCH,
            serverShiftId = "different-float",
            serverOpenedByUserId = "user-a",
            serverOpeningFloatMinor = 50_001,
            serverOpenedAtMillis = 1_000,
        )
        val tooEarly = db.shiftDao().reconcileSelectedRejectedOpen(
            localId = LOCAL_ID,
            terminalId = TERMINAL,
            branchId = BRANCH,
            serverShiftId = "clock-skewed",
            serverOpenedByUserId = "user-a",
            serverOpeningFloatMinor = 50_000,
            serverOpenedAtMillis = 1_000 - REJECTED_OPEN_RACE_TOLERANCE_MILLIS - 1,
        )

        assertEquals(RejectedOpenRecoveryStatus.UNRELATED_SERVER_SHIFT, differentFloat.status)
        assertEquals(RejectedOpenRecoveryStatus.UNRELATED_SERVER_SHIFT, tooEarly.status)
        assertEquals(ShiftState.OPEN_REJECTED, db.shiftDao().byLocalId(LOCAL_ID)?.state)
        assertNull(db.shiftDao().byLocalId(LOCAL_ID)?.serverShiftId)
    }

    @Test
    fun sqlCasIndependentlyGuardsOpenerFloatAndTwoSidedTimeWindow() = runBlocking {
        db.shiftDao().insert(rejectedShift())
        val window = rejectedOpenTimestampWindow(1_000)

        suspend fun attempt(
            serverShiftId: String,
            opener: String,
            openingFloatMinor: Long,
            openedAtMillis: Long,
        ): Int = db.shiftDao().linkSelectedRejectedOpenIfCausal(
            localId = LOCAL_ID,
            terminalId = TERMINAL,
            branchId = BRANCH,
            expectedLocalOpenedAtMillis = 1_000,
            serverShiftId = serverShiftId,
            serverOpenedByUserId = opener,
            serverOpeningFloatMinor = openingFloatMinor,
            serverOpenedAtMillis = openedAtMillis,
            earliestServerOpenedAtMillis = window.earliestMillis,
            latestServerOpenedAtMillis = window.latestMillis,
            note = "test reconciliation",
        )

        assertEquals(0, attempt("wrong-opener", "user-b", 50_000, 1_000))
        assertEquals(0, attempt("wrong-float", "user-a", 50_001, 1_000))
        assertEquals(0, attempt("too-early", "user-a", 50_000, window.earliestMillis - 1))
        assertEquals(0, attempt("too-late", "user-a", 50_000, window.latestMillis + 1))
        assertEquals(ShiftState.OPEN_REJECTED, db.shiftDao().byLocalId(LOCAL_ID)?.state)
        assertNull(db.shiftDao().byLocalId(LOCAL_ID)?.serverShiftId)
    }

    @Test
    fun verifiedUnrelatedEmptyAttemptIsDiscardedWithoutChangingCurrentShift() = runBlocking {
        db.shiftDao().insert(rejectedShift())
        val current = serverShift(
            id = "later-current-shift",
            openedAtMillis = 1_000 + REJECTED_OPEN_RACE_TOLERANCE_MILLIS + 1,
        ).copy(verifiedAtMillis = 1_000_000)
        db.shiftDao().reconcileServerOpen(TERMINAL, current, observedAtMillis = 1_000_000)
        db.syncMetaDao().put(SyncMetaEntity("shifts", 1_000_000))

        val result = db.shiftDao().resolveRejectedOpenAgainstVerifiedServer(
            localId = LOCAL_ID,
            terminalId = TERMINAL,
            branchId = BRANCH,
            serverShiftId = current.serverShiftId,
            serverOpenedByUserId = current.openedByUserId,
            serverOpeningFloatMinor = current.openingFloatMinor,
            serverOpenedAtMillis = current.openedAtMillis,
            verifiedAtMillis = 1_000_000,
        )

        assertEquals(RejectedOpenRecoveryStatus.DISCARDED, result.status)
        assertEquals(ShiftState.OPEN_DISCARDED, db.shiftDao().byLocalId(LOCAL_ID)?.state)
        assertEquals(
            true,
            db.shiftDao().byLocalId(LOCAL_ID)?.lastError.orEmpty()
                .contains("Server refused this shift open."),
        )
        assertEquals(current.serverShiftId, db.shiftDao().serverOpen(TERMINAL)?.serverShiftId)
    }

    @Test
    fun verifiedUnrelatedAttemptWithDependentsRemainsBlocked() = runBlocking {
        db.shiftDao().insert(rejectedShift())
        db.orderDao().capture(
            LocalOrderEntity(
                localId = "captured-sale",
                shiftId = LOCAL_ID,
                type = "dine_in",
                estimateMinor = 1_000,
                paymentMethod = "cash",
                tenderedMinor = 1_000,
                createdAtMillis = 1_500,
                syncState = SyncState.PENDING,
            ),
            emptyList(),
        )
        val current = serverShift(
            id = "later-current-shift",
            openedAtMillis = 1_000 + REJECTED_OPEN_RACE_TOLERANCE_MILLIS + 1,
        ).copy(verifiedAtMillis = 1_000_000)
        db.shiftDao().reconcileServerOpen(TERMINAL, current, observedAtMillis = 1_000_000)
        db.syncMetaDao().put(SyncMetaEntity("shifts", 1_000_000))

        val result = db.shiftDao().resolveRejectedOpenAgainstVerifiedServer(
            localId = LOCAL_ID,
            terminalId = TERMINAL,
            branchId = BRANCH,
            serverShiftId = current.serverShiftId,
            serverOpenedByUserId = current.openedByUserId,
            serverOpeningFloatMinor = current.openingFloatMinor,
            serverOpenedAtMillis = current.openedAtMillis,
            verifiedAtMillis = 1_000_000,
        )

        assertEquals(RejectedOpenRecoveryStatus.DEPENDENT_WORK, result.status)
        assertEquals(ShiftState.OPEN_REJECTED, db.shiftDao().byLocalId(LOCAL_ID)?.state)
        assertEquals(1, db.shiftDao().exactDependentRecordCount(LOCAL_ID))
        assertEquals(current.serverShiftId, db.shiftDao().serverOpen(TERMINAL)?.serverShiftId)
    }

    private fun rejectedShift() = LocalShiftEntity(
        localId = LOCAL_ID,
        terminalId = TERMINAL,
        branchId = BRANCH,
        openingFloatMinor = 50_000,
        openedAtMillis = 1_000,
        openedByUserId = "user-a",
        openedByName = "Rafi",
        state = ShiftState.OPEN_REJECTED,
        lastError = "Server refused this shift open.",
    )

    private fun serverShift(
        id: String,
        openedAtMillis: Long,
        openedByUserId: String = "user-a",
    ) = ServerOpenShiftEntity(
        terminalId = TERMINAL,
        serverShiftId = id,
        branchId = BRANCH,
        status = "open",
        openingFloatMinor = 50_000,
        openedAtMillis = openedAtMillis,
        openedByUserId = openedByUserId,
        openedByName = "Rafi",
        verifiedAtMillis = openedAtMillis,
    )

    private companion object {
        const val LOCAL_ID = "stable-local-id"
        const val TERMINAL = "terminal-a"
        const val BRANCH = "branch-a"
    }
}
