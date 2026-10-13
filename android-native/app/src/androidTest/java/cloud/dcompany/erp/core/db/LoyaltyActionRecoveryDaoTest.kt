package cloud.dcompany.erp.core.db

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cloud.dcompany.erp.ui.screens.genericReplayBody
import cloud.dcompany.erp.ui.screens.replayCapturedPointsActionThenLoadCanonical
import cloud.dcompany.erp.core.net.Order
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LoyaltyActionRecoveryDaoTest {
    private lateinit var db: ErpDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            ErpDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun definitiveRejectionCanRetireAfterCanonicalCheckpointButAmbiguityCannot() = runBlocking {
        val rejected = action("rejected-action", LoyaltyActionState.REJECTED)
        db.orderDao().insertOrder(
            LocalOrderEntity(
                localId = rejected.localOrderId,
                serverOrderId = rejected.orderId,
                shiftId = rejected.shiftId,
                type = "dine_in",
                estimateMinor = 1_000,
                paymentMethod = "",
                tenderedMinor = 0,
                tipMinor = 0,
                createdAtMillis = 1,
                syncState = SyncState.PREPARING,
            ),
        )
        db.loyaltyDao().insertAction(rejected)
        assertNotNull(db.loyaltyDao().unresolvedForOrder(rejected.orderId))

        db.withTransaction {
            assertEquals(
                1,
                db.orderDao().checkpointServerDraft(
                    localId = rejected.localOrderId,
                    serverOrderId = rejected.orderId,
                    serverShiftId = rejected.shiftId,
                    subtotalMinor = 1_000,
                    discountMinor = 0,
                    pointsRedeemedMinor = 0,
                    pointsRedeemed = 0,
                    taxMinor = 0,
                    roundOffMinor = 0,
                    totalMinor = 1_000,
                    dueMinor = 1_000,
                    checkoutVersion = 8,
                    updatedAtMillis = 2,
                ),
            )
            assertEquals(
                1,
                db.loyaltyDao().retireRejectedAfterCanonicalCheckpoint(
                    rejected.actionId, rejected.ownerCompanyId, rejected.ownerUserId,
                    rejected.localOrderId, rejected.orderId,
                ),
            )
        }
        assertNull(db.loyaltyDao().unresolvedForOrder(rejected.orderId))
        val checkpoint = requireNotNull(db.orderDao().withLines(rejected.localOrderId)).order
        assertEquals(0, checkpoint.serverPointsRedeemed)
        assertEquals(0L, checkpoint.serverPointsRedeemedMinor)
        assertEquals(8L, checkpoint.checkoutVersion)

        val ambiguous = action("ambiguous-action", LoyaltyActionState.AMBIGUOUS)
        db.loyaltyDao().insertAction(ambiguous)
        db.withTransaction {
            assertEquals(
                0,
                db.loyaltyDao().retireRejectedAfterCanonicalCheckpoint(
                    ambiguous.actionId, ambiguous.ownerCompanyId, ambiguous.ownerUserId,
                    ambiguous.localOrderId, ambiguous.orderId,
                ),
            )
        }
        assertEquals(
            ambiguous.actionId,
            db.loyaltyDao().unresolvedForOrder(ambiguous.orderId)?.actionId,
        )
    }

    @Test
    fun wrongScopeCannotRetireAndResolvedActionReleasesAccountAndShiftGates() = runBlocking {
        val ambiguous = action("scoped-action", LoyaltyActionState.AMBIGUOUS)
        db.loyaltyDao().insertAction(ambiguous)

        assertEquals(
            0,
            db.loyaltyDao().retireReconciledAfterCanonicalCheckpoint(
                ambiguous.actionId, "company-2", ambiguous.ownerUserId,
                ambiguous.localOrderId, ambiguous.orderId,
            ),
        )
        assertNotNull(db.loyaltyDao().unresolvedForOrder(ambiguous.orderId))
        assertEquals(1, db.outboxSafetyDao().unresolvedGroups().single {
            it.resource == "points_redemptions"
        }.count)
        assertEquals(
            1,
            db.shiftCloseSafetyDao().blockersForExactShift(
                ambiguous.shiftId, null, "terminal-1",
            ).attentionLocalCount,
        )

        assertEquals(
            1,
            db.loyaltyDao().retireReconciledAfterCanonicalCheckpoint(
                ambiguous.actionId, ambiguous.ownerCompanyId, ambiguous.ownerUserId,
                ambiguous.localOrderId, ambiguous.orderId,
            ),
        )
        assertNull(db.loyaltyDao().unresolvedForOrder(ambiguous.orderId))
        assertEquals(
            0,
            db.outboxSafetyDao().unresolvedGroups().sumOf { it.count },
        )
        assertEquals(
            0,
            db.shiftCloseSafetyDao().blockersForExactShift(
                ambiguous.shiftId, null, "terminal-1",
            ).attentionLocalCount,
        )
    }

    @Test
    fun capturedIntentSurvivesReopenAndReplaysOriginalBodyAfterCanonicalVersionAdvance() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val fileName = "loyalty-action-reopen-${System.nanoTime()}"
        db.close()
        db = Room.databaseBuilder(context, ErpDatabase::class.java, fileName)
            .allowMainThreadQueries()
            .build()
        val captured = action("reopen-action", LoyaltyActionState.AMBIGUOUS)
        db.orderDao().insertOrder(
            LocalOrderEntity(
                localId = captured.localOrderId,
                serverOrderId = captured.orderId,
                shiftId = captured.shiftId,
                type = "dine_in",
                estimateMinor = 1_000,
                paymentMethod = "",
                tenderedMinor = 0,
                tipMinor = 0,
                createdAtMillis = 1,
                syncState = SyncState.PREPARING,
            ),
        )
        db.loyaltyDao().insertAction(captured)
        db.close()

        db = Room.databaseBuilder(context, ErpDatabase::class.java, fileName)
            .allowMainThreadQueries()
            .build()
        val restored = requireNotNull(db.loyaltyDao().action(captured.actionId))
        val canonicalCheckoutVersionAfterResponseLoss = 8L
        val replay = restored.genericReplayBody()
        assertEquals(100, replay.points)
        assertEquals(7L, replay.expectedCheckoutVersion)
        assertEquals(4L, replay.expectedProgramRevision)
        assertEquals(8L, canonicalCheckoutVersionAfterResponseLoss)
        var replayCompleted = false
        val terminalEvidence = replayCapturedPointsActionThenLoadCanonical(
            action = restored,
            updatePoints = { orderId, body, key ->
                assertEquals(restored.orderId, orderId)
                assertEquals(restored.actionId, key)
                assertEquals(7L, body.expectedCheckoutVersion)
                assertEquals(4L, body.expectedProgramRevision)
                replayCompleted = true
                Order(id = orderId, status = "open", checkoutVersion = 8)
            },
            updateReward = { _, _, _ -> error("generic action must not become a reward") },
            loadCanonical = { orderId ->
                check(replayCompleted) { "terminal evidence was read before exact replay" }
                Order(id = orderId, status = "void", checkoutVersion = 9)
            },
        )
        assertEquals("void", terminalEvidence.canonical.status)
        assertNotNull(db.loyaltyDao().unresolvedForOrder(restored.orderId))
        db.withTransaction {
            db.orderDao().deleteDraft(restored.localOrderId)
            assertEquals(
                1,
                db.loyaltyDao().retireReconciledAfterCanonicalCheckpoint(
                    restored.actionId,
                    restored.ownerCompanyId,
                    restored.ownerUserId,
                    restored.localOrderId,
                    restored.orderId,
                ),
            )
        }
        assertNull(db.loyaltyDao().unresolvedForOrder(restored.orderId))
        assertEquals(0, db.outboxSafetyDao().unresolvedGroups().sumOf { it.count })
        assertEquals(
            0,
            db.shiftCloseSafetyDao().blockersForExactShift(
                restored.shiftId, null, "terminal-1",
            ).attentionLocalCount,
        )
        db.close()
        context.deleteDatabase(fileName)
        db = Room.inMemoryDatabaseBuilder(context, ErpDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @Test
    fun heldPaidAndRefundedCanonicalEvidenceRetiresOnlyExactCapturedIntent() = runBlocking {
        listOf("held", "paid", "refunded").forEachIndexed { index, status ->
            val captured = action("lifecycle-$status", LoyaltyActionState.AMBIGUOUS)
            db.orderDao().insertOrder(
                LocalOrderEntity(
                    localId = captured.localOrderId,
                    serverOrderId = captured.orderId,
                    shiftId = captured.shiftId,
                    type = "dine_in",
                    estimateMinor = 1_000,
                    paymentMethod = "",
                    tenderedMinor = 0,
                    tipMinor = 0,
                    createdAtMillis = index.toLong() + 1,
                    syncState = SyncState.PREPARING,
                ),
            )
            db.loyaltyDao().insertAction(captured)
            assertNotNull(db.loyaltyDao().unresolvedForOrder(captured.orderId))
            assertEquals(
                2,
                db.shiftCloseSafetyDao().blockersForExactShift(
                    captured.shiftId, null, "terminal-1",
                ).attentionLocalCount,
            )

            db.withTransaction {
                if (status == "held") {
                    assertEquals(
                        1,
                        db.orderDao().checkpointServerDraft(
                            localId = captured.localOrderId,
                            serverOrderId = captured.orderId,
                            serverShiftId = captured.shiftId,
                            subtotalMinor = 1_000,
                            discountMinor = 300,
                            pointsRedeemedMinor = 300,
                            pointsRedeemed = 30,
                            taxMinor = 0,
                            roundOffMinor = 0,
                            totalMinor = 700,
                            dueMinor = 700,
                            checkoutVersion = 9,
                            updatedAtMillis = 10,
                        ),
                    )
                } else {
                    db.orderDao().deleteDraft(captured.localOrderId)
                }
                assertEquals(
                    1,
                    db.loyaltyDao().retireReconciledAfterCanonicalCheckpoint(
                        captured.actionId,
                        captured.ownerCompanyId,
                        captured.ownerUserId,
                        captured.localOrderId,
                        captured.orderId,
                    ),
                )
            }

            assertNull(db.loyaltyDao().unresolvedForOrder(captured.orderId))
            val remainingAttention = db.shiftCloseSafetyDao().blockersForExactShift(
                captured.shiftId, null, "terminal-1",
            ).attentionLocalCount
            assertEquals(if (status == "held") 1 else 0, remainingAttention)
            if (status == "held") {
                val checkpoint = requireNotNull(db.orderDao().withLines(captured.localOrderId)).order
                assertEquals(9L, checkpoint.checkoutVersion)
                assertEquals(30, checkpoint.serverPointsRedeemed)
                db.orderDao().deleteDraft(captured.localOrderId)
            }
        }
        assertEquals(0, db.outboxSafetyDao().unresolvedGroups().sumOf { it.count })
    }

    private fun action(id: String, state: String) = LocalPointsRedemptionActionEntity(
        actionId = id,
        ownerCompanyId = "company-1",
        ownerUserId = "user-1",
        shiftId = "shift-1",
        orderId = "order-$id",
        localOrderId = "local-$id",
        actionKind = "generic",
        points = 100,
        rewardKey = null,
        programRevision = 4,
        expectedCheckoutVersion = 7,
        state = state,
        createdAtMillis = 1,
    )
}
