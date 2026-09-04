package cloud.dcompany.erp.core.db

import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ShiftCloseSafetyDaoTest {

    private lateinit var db: ErpDatabase
    private lateinit var safety: ShiftCloseSafetyDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            ErpDatabase::class.java,
        ).addCallback(SHIFT_CLOSING_WRITE_GUARD_CALLBACK).build()
        safety = db.shiftCloseSafetyDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun pendingExactShiftWorkCanCaptureCloseButMustDrainBeforeServerPost() = runBlocking {
        db.shiftDao().insert(openShift("shift-a"))
        db.orderDao().capture(order("sale-a", "shift-a", SyncState.PENDING), emptyList())

        val result = safety.captureExistingClose(
            localId = "shift-a",
            terminalId = TERMINAL,
            countedMinor = 12_500,
            closedAtMillis = 2_000,
        )

        assertEquals(ShiftCloseCaptureStatus.CAPTURED, result.status)
        assertEquals(ShiftState.CLOSE_PENDING, safety.localShift("shift-a")?.state)
        val blockers = safety.blockersForExactShift("shift-a", SERVER_SHIFT, TERMINAL)
        assertEquals(1, blockers.pendingLocalCount)
        assertEquals(0, blockers.captureBlockerCount)
        assertNull(blockers.captureMessage())
        assertNotNull(blockers.serverPostMessage())
    }

    @Test
    fun cacheOnlyCrossDeviceGamingWorkBlocksExactShiftCloseCapture() = runBlocking {
        db.shiftDao().insert(openShift("shift-cache-gaming"))
        db.gamingDao().upsertSessionCache(
            listOf(
                gamingCache("remote-active", SERVER_SHIFT, "active"),
                gamingCache("remote-paused", SERVER_SHIFT, "paused"),
                gamingCache("remote-unbilled", SERVER_SHIFT, "ended"),
                gamingCache("remote-billed", SERVER_SHIFT, "ended", orderId = "order-billed"),
                gamingCache("another-shift", "server-other", "active"),
            ),
        )

        val blockers = safety.blockersForExactShift(
            "shift-cache-gaming",
            SERVER_SHIFT,
            TERMINAL,
        )

        assertEquals(3, blockers.serverGamingSessionCount)
        assertEquals(0, blockers.serverHeldOrderCount)
        assertEquals(3, blockers.captureBlockerCount)
        assertTrue(blockers.captureMessage().orEmpty().contains("stopped-but-unbilled Gaming"))
        val result = safety.captureExistingClose(
            "shift-cache-gaming",
            TERMINAL,
            countedMinor = 0,
            closedAtMillis = 2_000,
        )
        assertEquals(ShiftCloseCaptureStatus.BLOCKED, result.status)
        assertEquals(ShiftState.OPEN_SYNCED, safety.localShift("shift-cache-gaming")?.state)
    }

    @Test
    fun cachedGamingRowWithCapturedLocalStopUsesPendingDrainPath() = runBlocking {
        db.shiftDao().insert(openShift("shift-captured-stop"))
        db.gamingDao().upsertSessionCache(
            listOf(gamingCache("server-captured-stop", SERVER_SHIFT, "active")),
        )
        db.gamingDao().insertLocalSession(
            LocalGamingSessionEntity(
                localId = "local-captured-stop",
                serverId = "server-captured-stop",
                stationId = "station-captured-stop",
                shiftId = "shift-captured-stop",
                startedAtMillis = 1_000,
                state = GamingSessionState.STOP_PENDING,
                endAtMillis = 1_500,
            ),
        )

        val blockers = safety.blockersForExactShift(
            "shift-captured-stop",
            SERVER_SHIFT,
            TERMINAL,
        )

        assertEquals(1, blockers.pendingLocalCount)
        assertEquals(0, blockers.serverGamingSessionCount)
        assertNull(blockers.captureMessage())
        assertEquals(
            ShiftCloseCaptureStatus.CAPTURED,
            safety.captureExistingClose("shift-captured-stop", TERMINAL, 0, 2_000).status,
        )
    }

    @Test
    fun terminalHeldCacheBlocksCloseButItsCapturedPaymentUsesPendingDrainPath() = runBlocking {
        db.shiftDao().insert(openShift("shift-cache-held"))
        db.heldOrderDao().upsertAll(
            listOf(
                heldOrder("order-unclaimed"),
                heldOrder("order-payment-captured"),
            ),
        )
        db.heldOrderDao().insertPayment(
            heldPayment("payment-captured", "shift-cache-held", TERMINAL).copy(
                targetOrderId = "order-payment-captured",
            ),
        )

        val blocked = safety.blockersForExactShift(
            "shift-cache-held",
            SERVER_SHIFT,
            TERMINAL,
        )
        assertEquals(1, blocked.pendingLocalCount)
        assertEquals(1, blocked.serverHeldOrderCount)
        assertTrue(blocked.captureMessage().orEmpty().contains("1 held POS order(s)"))

        db.heldOrderDao().replace(listOf(heldOrder("order-payment-captured")))
        val capturedPaymentOnly = safety.blockersForExactShift(
            "shift-cache-held",
            SERVER_SHIFT,
            TERMINAL,
        )
        assertEquals(1, capturedPaymentOnly.pendingLocalCount)
        assertEquals(0, capturedPaymentOnly.serverHeldOrderCount)
        assertNull(capturedPaymentOnly.captureMessage())
        assertEquals(
            ShiftCloseCaptureStatus.CAPTURED,
            safety.captureExistingClose("shift-cache-held", TERMINAL, 0, 2_000).status,
        )
    }

    @Test
    fun negativeDrawerCountIsRejectedBeforeCloseCapture() = runBlocking {
        db.shiftDao().insert(openShift("shift-negative-capture"))

        val result = safety.captureExistingClose(
            localId = "shift-negative-capture",
            terminalId = TERMINAL,
            countedMinor = -1,
            closedAtMillis = 2_000,
        )

        assertEquals(ShiftCloseCaptureStatus.BLOCKED, result.status)
        assertTrue(result.message.orEmpty().contains("negative drawer count"))
        val unchanged = safety.localShift("shift-negative-capture")
        assertEquals(ShiftState.OPEN_SYNCED, unchanged?.state)
        assertNull(unchanged?.countedMinor)
        assertNull(unchanged?.closedAtMillis)
    }

    @Test
    fun invalidPendingCloseIsMovedToVisibleRejectedRecoveryState() = runBlocking {
        db.shiftDao().insert(
            openShift(
                localId = "shift-missing-count",
                state = ShiftState.CLOSE_PENDING,
            ).copy(countedMinor = null, closedAtMillis = 2_000),
        )
        val invalid = ShiftCloseCountPolicy.validate(null) as ShiftCloseCountValidation.Invalid

        assertEquals(1, safety.markInvalidCloseCount("shift-missing-count", invalid.message))

        val rejected = safety.localShift("shift-missing-count")
        assertEquals(ShiftState.CLOSE_REJECTED, rejected?.state)
        assertEquals(invalid.message, rejected?.lastError)
        assertTrue(rejected?.lastError.orEmpty().contains("was not sent to the server"))
    }

    @Test
    fun preNetworkPreflightRejectsUnsyncedInvalidClosesBeforeTheyBecomePushableOpens() = runBlocking {
        val missing = openShift(
            localId = "unsynced-missing-count",
            state = ShiftState.CLOSE_PENDING,
        ).copy(serverShiftId = null, countedMinor = null, closedAtMillis = 2_000)
        val negative = openShift(
            localId = "unsynced-negative-count",
            state = ShiftState.CLOSE_PENDING,
        ).copy(serverShiftId = null, countedMinor = -1, closedAtMillis = 2_000)
        val zero = openShift(
            localId = "unsynced-zero-count",
            state = ShiftState.CLOSE_PENDING,
        ).copy(serverShiftId = null, countedMinor = 0, closedAtMillis = 2_000)
        db.shiftDao().insert(missing)
        db.shiftDao().insert(negative)
        db.shiftDao().insert(zero)
        assertEquals(3, db.shiftDao().pushableOpens().size)

        val rejected = safety.rejectInvalidCloseIntentsBeforeNetwork()

        assertEquals(
            setOf("unsynced-missing-count", "unsynced-negative-count"),
            rejected.map { it.localId }.toSet(),
        )
        assertEquals(ShiftState.CLOSE_REJECTED, safety.localShift(missing.localId)?.state)
        assertEquals(ShiftState.CLOSE_REJECTED, safety.localShift(negative.localId)?.state)
        assertTrue(safety.localShift(missing.localId)?.lastError.orEmpty().contains("Continue shift"))
        assertTrue(safety.localShift(negative.localId)?.lastError.orEmpty().contains("Continue shift"))
        assertEquals(ShiftState.CLOSE_PENDING, safety.localShift(zero.localId)?.state)
        assertEquals(0L, safety.localShift(zero.localId)?.countedMinor)
        assertEquals(listOf(zero.localId), db.shiftDao().pushableOpens().map { it.localId })

        assertEquals(1, db.shiftDao().cancelRejectedClose(missing.localId))
        val continued = safety.localShift(missing.localId)
        assertEquals(ShiftState.OPEN_PENDING, continued?.state)
        assertNull(continued?.countedMinor)
        assertNull(continued?.closedAtMillis)
        assertEquals(
            setOf(missing.localId, zero.localId),
            db.shiftDao().pushableOpens().map { it.localId }.toSet(),
        )
    }

    @Test
    fun rejectedCloseCannotRetryMissingOrNegativeCountButZeroCanRetry() = runBlocking {
        db.shiftDao().insert(
            openShift("shift-null", state = ShiftState.CLOSE_REJECTED)
                .copy(countedMinor = null, closedAtMillis = 2_000),
        )
        db.shiftDao().insert(
            openShift("shift-negative", serverShiftId = "server-negative", state = ShiftState.CLOSE_REJECTED)
                .copy(countedMinor = -1, closedAtMillis = 2_000),
        )
        db.shiftDao().insert(
            openShift("shift-zero", serverShiftId = "server-zero", state = ShiftState.CLOSE_REJECTED)
                .copy(countedMinor = 0, closedAtMillis = 2_000),
        )

        val missing = safety.retryRejectedClose("shift-null", TERMINAL)
        val negative = safety.retryRejectedClose("shift-negative", TERMINAL)
        val zero = safety.retryRejectedClose("shift-zero", TERMINAL)

        assertEquals(ShiftCloseCaptureStatus.BLOCKED, missing.status)
        assertEquals(ShiftCloseCaptureStatus.BLOCKED, negative.status)
        assertTrue(missing.message.orEmpty().contains("missing its drawer count"))
        assertTrue(negative.message.orEmpty().contains("negative drawer count"))
        assertEquals(ShiftState.CLOSE_REJECTED, safety.localShift("shift-null")?.state)
        assertEquals(ShiftState.CLOSE_REJECTED, safety.localShift("shift-negative")?.state)
        assertEquals(ShiftCloseCaptureStatus.CAPTURED, zero.status)
        assertEquals(ShiftState.CLOSE_PENDING, safety.localShift("shift-zero")?.state)
        assertEquals(0L, safety.localShift("shift-zero")?.countedMinor)
    }

    @Test
    fun exactRejectedWorkBlocksCaptureButAnotherShiftDoesNot() = runBlocking {
        db.shiftDao().insert(openShift("shift-a"))
        db.shiftDao().insert(openShift("shift-b", serverShiftId = "server-b"))
        db.orderDao().capture(order("sale-b", "shift-b", SyncState.REJECTED), emptyList())

        val unrelated = safety.captureExistingClose(
            localId = "shift-a",
            terminalId = TERMINAL,
            countedMinor = 0,
            closedAtMillis = 2_000,
        )
        val exact = safety.captureExistingClose(
            localId = "shift-b",
            terminalId = TERMINAL,
            countedMinor = 0,
            closedAtMillis = 2_000,
        )

        assertEquals(ShiftCloseCaptureStatus.CAPTURED, unrelated.status)
        assertEquals(ShiftCloseCaptureStatus.BLOCKED, exact.status)
        assertTrue(exact.message.orEmpty().contains("need recovery"))
        assertEquals(ShiftState.OPEN_SYNCED, safety.localShift("shift-b")?.state)
    }

    @Test
    fun packageExtensionOutboxUsesExactShiftCloseSemantics() = runBlocking {
        db.shiftDao().insert(openShift("shift-a"))
        val exact = packageExtension(
            actionId = "33333333-3333-4333-8333-333333333333",
            serverSessionId = "session-exact",
            shiftId = "shift-a",
        )
        assertTrue(db.gamingDao().capturePackageExtension(exact))

        val pending = safety.blockersForExactShift("shift-a", SERVER_SHIFT, TERMINAL)
        assertEquals(1, pending.pendingLocalCount)
        assertEquals(0, pending.captureBlockerCount)
        assertNull(pending.captureMessage())
        assertNotNull(pending.serverPostMessage())

        assertEquals(1, db.gamingDao().markPackageExtensionAmbiguous(exact.actionId, "response unknown"))
        val ambiguous = safety.blockersForExactShift("shift-a", SERVER_SHIFT, TERMINAL)
        assertEquals(1, ambiguous.attentionLocalCount)
        assertNotNull(ambiguous.captureMessage())
        assertEquals(
            ShiftCloseCaptureStatus.BLOCKED,
            safety.captureExistingClose("shift-a", TERMINAL, 0, 2_000).status,
        )
        assertEquals(1, db.gamingDao().markPackageExtensionRejected(exact.actionId, "price changed"))
        assertEquals(
            1,
            db.gamingDao().discardRejectedPackageExtension(
                exact.actionId,
                "Staff refreshed the session",
                resolvedAtMillis = 2_100,
            ),
        )
        val resolved = safety.blockersForExactShift("shift-a", SERVER_SHIFT, TERMINAL)
        assertEquals(0, resolved.pendingLocalCount)
        assertEquals(0, resolved.attentionLocalCount)
        assertNull(resolved.captureMessage())

        // Only an imported/legacy row can be unscoped; normal capture rejects
        // it. The close gate still fails closed if such a row exists.
        db.gamingDao().insertPackageExtensionAction(
            packageExtension(
                actionId = "44444444-4444-4444-8444-444444444444",
                serverSessionId = "session-unscoped",
                shiftId = null,
            ),
        )
        val unscoped = safety.blockersForExactShift("shift-a", SERVER_SHIFT, TERMINAL)
        assertEquals(1, unscoped.unscopedAttentionCount)
        assertEquals(
            "gaming_package_extensions",
            db.outboxSafetyDao().unresolvedGroups().single().resource,
        )
    }

    @Test
    fun closeIntentGuardsNewSalesAndExistingGamingTransitions() = runBlocking {
        db.shiftDao().insert(openShift("shift-a"))
        assertEquals(
            ShiftCloseCaptureStatus.CAPTURED,
            safety.captureExistingClose("shift-a", TERMINAL, 0, 2_000).status,
        )

        val saleFailure = assertThrows(SQLiteConstraintException::class.java) {
            runBlocking {
                db.orderDao().capture(order("late-sale", "shift-a", SyncState.PENDING), emptyList())
            }
        }
        assertTrue(saleFailure.message.orEmpty().contains(SHIFT_CLOSING_WRITE_GUARD))

        db.gamingDao().insertLocalSession(
            LocalGamingSessionEntity(
                localId = "gaming-a",
                serverId = "server-gaming-a",
                stationId = "station-a",
                shiftId = "shift-a",
                startedAtMillis = 1_500,
                state = GamingSessionState.START_SYNCED,
            ),
        )
        val gamingFailure = assertThrows(SQLiteConstraintException::class.java) {
            runBlocking {
                db.gamingDao().requestSessionStop(
                    "gaming-a",
                    stoppedAtMillis = 2_100,
                    resolvedShiftId = "shift-a",
                )
            }
        }
        assertTrue(gamingFailure.message.orEmpty().contains(SHIFT_CLOSING_WRITE_GUARD))

        val extensionFailure = assertThrows(SQLiteConstraintException::class.java) {
            runBlocking {
                db.gamingDao().capturePackageExtension(
                    packageExtension(
                        actionId = "55555555-5555-4555-8555-555555555555",
                        serverSessionId = "session-late-extension",
                        shiftId = "shift-a",
                    ),
                )
            }
        }
        assertTrue(extensionFailure.message.orEmpty().contains(SHIFT_CLOSING_WRITE_GUARD))
    }

    @Test
    fun heldPaymentUsesExactProvenanceAndLegacyUnknownProvenanceBlocksCapture() = runBlocking {
        db.shiftDao().insert(openShift("shift-a"))
        db.heldOrderDao().insertPayment(heldPayment("known", "shift-a", TERMINAL))

        val known = safety.blockersForExactShift("shift-a", SERVER_SHIFT, TERMINAL)
        assertEquals(1, known.pendingLocalCount)
        assertEquals(0, known.unscopedAttentionCount)

        db.heldOrderDao().insertPayment(heldPayment("legacy", null, null))
        val withLegacy = safety.blockersForExactShift("shift-a", SERVER_SHIFT, TERMINAL)
        assertEquals(1, withLegacy.unscopedAttentionCount)
        assertNotNull(withLegacy.captureMessage())
    }

    @Test
    fun cafeActionsAreScopedToTheirShiftAndCannotAppearAfterCloseCapture() = runBlocking {
        db.shiftDao().insert(openShift("shift-a"))
        val pendingBill = cafeBill("bill-pending", "table-pending", "shift-a")
        assertTrue(
            db.cafeOrderDao().captureNewBill(
                pendingBill,
                cafeAction("round-pending", pendingBill.localBillId),
            ),
        )

        val blockers = safety.blockersForExactShift("shift-a", SERVER_SHIFT, TERMINAL)
        assertEquals(1, blockers.pendingLocalCount)
        assertNull(blockers.captureMessage())
        assertNotNull(blockers.serverPostMessage())

        // Capture may coexist with already-saved work so the ordered sync can
        // drain it, but no stale Tables screen may add another round later.
        assertEquals(
            ShiftCloseCaptureStatus.CAPTURED,
            safety.captureExistingClose("shift-a", TERMINAL, 0, 2_000).status,
        )
        val newBill = cafeBill("bill-late", "table-late", "shift-a")
        db.cafeOrderDao().insertLocalBill(newBill)
        val failure = assertThrows(SQLiteConstraintException::class.java) {
            runBlocking {
                db.cafeOrderDao().captureAction(
                    newBill,
                    cafeAction("round-late", newBill.localBillId),
                )
            }
        }
        assertTrue(failure.message.orEmpty().contains(SHIFT_CLOSING_WRITE_GUARD))
    }

    @Test
    fun conflictedCafeActionBlocksShiftCloseCapture() = runBlocking {
        db.shiftDao().insert(openShift("shift-a"))
        val bill = cafeBill("bill-conflict", "table-conflict", "shift-a")
        val action = cafeAction("send-conflict", bill.localBillId).copy(
            kind = CafeActionKind.SEND_TO_POS,
        )
        assertTrue(db.cafeOrderDao().captureNewBill(bill, action))
        db.cafeOrderDao().setActionFailure(
            action.actionId,
            CafeActionState.CONFLICT,
            "Another terminal changed this bill",
        )

        val result = safety.captureExistingClose("shift-a", TERMINAL, 0, 2_000)
        assertEquals(ShiftCloseCaptureStatus.BLOCKED, result.status)
        assertTrue(result.message.orEmpty().contains("need recovery"))
    }

    @Test
    fun adoptedServerShiftCapturesOneCloseWithoutTreatingItselfAsAConflict() = runBlocking {
        val result = safety.captureAdoptedClose(
            openShift(
                localId = "adopted-close",
                serverShiftId = "server-adopted",
                state = ShiftState.CLOSE_PENDING,
            ).copy(countedMinor = 100, closedAtMillis = 2_000),
        )

        assertEquals(ShiftCloseCaptureStatus.CAPTURED, result.status)
        assertEquals(ShiftState.CLOSE_PENDING, safety.localShift("adopted-close")?.state)
        assertEquals(
            0,
            safety.blockersForExactShift("adopted-close", "server-adopted", TERMINAL)
                .serverPostBlockerCount,
        )
    }

    private fun openShift(
        localId: String,
        serverShiftId: String = SERVER_SHIFT,
        state: String = ShiftState.OPEN_SYNCED,
    ) = LocalShiftEntity(
        localId = localId,
        serverShiftId = serverShiftId,
        terminalId = TERMINAL,
        branchId = "branch-a",
        openingFloatMinor = 5_000,
        openedAtMillis = 1_000,
        state = state,
    )

    private fun order(localId: String, shiftId: String, state: String) = LocalOrderEntity(
        localId = localId,
        shiftId = shiftId,
        type = "dine_in",
        estimateMinor = 1_000,
        paymentMethod = "cash",
        tenderedMinor = 1_000,
        createdAtMillis = 1_500,
        syncState = state,
    )

    private fun heldPayment(localId: String, shiftId: String?, terminalId: String?) =
        LocalHeldOrderPaymentEntity(
            localId = localId,
            targetOrderId = "order-$localId",
            method = "upi",
            amountMinor = 1_000,
            tenderedMinor = null,
            expectedTotalMinor = 1_000,
            expectedDueMinor = 1_000,
            shiftId = shiftId,
            terminalId = terminalId,
            createdAtMillis = 1_500,
        )

    private fun gamingCache(
        id: String,
        shiftId: String,
        status: String,
        orderId: String? = null,
    ) = GamingSessionCacheEntity(
        id = id,
        stationId = "station-$id",
        shiftId = shiftId,
        status = status,
        startAtMillis = 1_000,
        endAtMillis = if (status == "ended") 1_500 else null,
        orderId = orderId,
    )

    private fun heldOrder(id: String) = HeldOrderCacheEntity(
        id = id,
        invoiceNo = null,
        type = "dine_in",
        sourceLabel = "Gaming station",
        totalMinor = 1_000,
        paidMinor = 0,
        itemsCount = 1,
        customerName = null,
        createdAt = "2026-09-03T10:00:00Z",
        heldAt = "2026-09-03T10:30:00Z",
    )

    private fun cafeBill(localId: String, tableId: String, shiftId: String) =
        LocalCafeBillEntity(
            localBillId = localId,
            tableId = tableId,
            tableCode = tableId,
            shiftId = shiftId,
            createdAtMillis = 1_500,
        )

    private fun cafeAction(actionId: String, billId: String) = LocalCafeActionEntity(
        actionId = actionId,
        localBillId = billId,
        kind = CafeActionKind.CREATE_ROUND,
        payload = CafeActionPayload(
            lines = listOf(
                CafeActionLine(
                    clientLineId = "line-$actionId",
                    menuItemId = "menu-1",
                    name = "Coffee",
                    qty = 1,
                    estimateUnitMinor = 1_000,
                ),
            ),
        ),
        dedupeKey = "dedupe-$actionId",
        createdAtMillis = 1_500,
    )

    private fun packageExtension(
        actionId: String,
        serverSessionId: String,
        shiftId: String?,
    ) = LocalGamingPackageExtensionEntity(
        actionId = actionId,
        serverSessionId = serverSessionId,
        localSessionId = "local-$serverSessionId",
        shiftId = shiftId,
        packageId = "extend-30",
        expectedPackagePriceMinor = 7_500,
        expectedPackageDurationMinutes = 30,
        expectedPackageVariant = "solo",
        expectedSessionTimerMinutes = 60,
        expectedSessionAmountMinor = 15_000,
        createdAtMillis = 1_750,
    )

    private companion object {
        const val TERMINAL = "terminal-a"
        const val SERVER_SHIFT = "server-shift-a"
    }
}
