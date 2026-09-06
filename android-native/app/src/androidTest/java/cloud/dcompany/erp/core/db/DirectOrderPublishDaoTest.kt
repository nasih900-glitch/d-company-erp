package cloud.dcompany.erp.core.db

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DirectOrderPublishDaoTest {
    private lateinit var db: ErpDatabase
    private lateinit var dao: OrderDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            ErpDatabase::class.java,
        ).build()
        dao = db.orderDao()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun onlineClaimIsDurableBeforePaymentProjectionAndExactRemovalIsGuarded() = runBlocking {
        dao.capture(order(SyncState.PREPARING), listOf(line()))

        assertEquals(
            1,
            dao.markDraftPrepared(
                localId = LOCAL_ID,
                serverOrderId = SERVER_ID,
                subtotalMinor = 12_000,
                discountMinor = 1_000,
                pointsRedeemedMinor = 500,
                pointsRedeemed = 50,
                taxMinor = 0,
                roundOffMinor = 0,
                totalMinor = 11_000,
                dueMinor = 11_000,
                claimToken = "claim-1",
                claimExpiresAtMillis = 99_000,
                checkoutVersion = 8,
                updatedAtMillis = 2_000,
            ),
        )
        val prepared = dao.withLines(LOCAL_ID)!!.order
        assertEquals(SyncState.AWAITING_PAYMENT, prepared.syncState)
        assertEquals("claim-1", prepared.checkoutClaimToken)
        assertEquals(8L, prepared.checkoutVersion)

        assertFalse(dao.removeExactPreparedDirect(LOCAL_ID, SERVER_ID, "wrong-claim"))
        assertEquals("claim-1", dao.withLines(LOCAL_ID)?.order?.checkoutClaimToken)
        assertTrue(dao.removeExactPreparedDirect(LOCAL_ID, SERVER_ID, "claim-1"))
        assertNull(dao.withLines(LOCAL_ID))
        assertTrue(dao.linesFor(LOCAL_ID).isEmpty())
    }

    @Test
    fun offlineCanonicalVersionAndClaimSurviveEachCrashBoundary() = runBlocking {
        dao.capture(order(SyncState.PENDING), listOf(line()))

        assertEquals(
            1,
            dao.checkpointPendingServerOrder(
                localId = LOCAL_ID,
                serverOrderId = SERVER_ID,
                serverShiftId = "server-shift",
                subtotalMinor = 12_000,
                discountMinor = 0,
                pointsRedeemedMinor = 0,
                pointsRedeemed = 0,
                taxMinor = 0,
                roundOffMinor = 0,
                totalMinor = 12_000,
                dueMinor = 12_000,
                checkoutVersion = 7,
                updatedAtMillis = 2_000,
            ),
        )
        val beforePublish = dao.withLines(LOCAL_ID)!!.order
        assertEquals(SyncState.PENDING, beforePublish.syncState)
        assertEquals(7L, beforePublish.checkoutVersion)
        assertNull(beforePublish.checkoutClaimToken)

        assertEquals(
            1,
            dao.savePendingDirectClaim(
                localId = LOCAL_ID,
                serverOrderId = SERVER_ID,
                expectedTotalMinor = 12_000,
                expectedDueMinor = 12_000,
                claimToken = "claim-1",
                claimExpiresAtMillis = 99_000,
                claimOrderVersion = 8,
                updatedAtMillis = 3_000,
            ),
        )
        val afterPublish = dao.withLines(LOCAL_ID)!!.order
        assertEquals(SyncState.PENDING, afterPublish.syncState)
        assertEquals(8L, afterPublish.checkoutVersion)
        assertEquals("claim-1", afterPublish.checkoutClaimToken)

        // A same-installation response-loss replay may rotate the bearer but
        // must retain the exact post-publication order version and amount.
        assertEquals(
            1,
            dao.savePendingDirectClaim(
                localId = LOCAL_ID,
                serverOrderId = SERVER_ID,
                expectedTotalMinor = 12_000,
                expectedDueMinor = 12_000,
                claimToken = "claim-2",
                claimExpiresAtMillis = 199_000,
                claimOrderVersion = 8,
                updatedAtMillis = 4_000,
            ),
        )
        assertEquals("claim-2", dao.withLines(LOCAL_ID)?.order?.checkoutClaimToken)
        assertEquals(
            0,
            dao.savePendingDirectClaim(
                localId = LOCAL_ID,
                serverOrderId = SERVER_ID,
                expectedTotalMinor = 12_001,
                expectedDueMinor = 12_000,
                claimToken = "mismatch",
                claimExpiresAtMillis = 299_000,
                claimOrderVersion = 8,
                updatedAtMillis = 5_000,
            ),
        )
    }

    @Test
    fun definitiveDiscountRefusalAdoptsCanonicalValueAndRecoveryVersion() = runBlocking {
        dao.capture(
            order(SyncState.PREPARING).copy(
                serverOrderId = SERVER_ID,
                manualDiscountMinor = 2_000,
                discountRequestVersion = 7,
                checkoutVersion = 7,
            ),
            listOf(line()),
        )

        assertEquals(
            1,
            dao.adoptCanonicalDiscountAfterRefusal(
                localId = LOCAL_ID,
                serverOrderId = SERVER_ID,
                canonicalManualDiscountMinor = 0,
                updatedAtMillis = 2_000,
            ),
        )
        val recovered = dao.withLines(LOCAL_ID)!!.order
        assertEquals(SyncState.PREPARING, recovered.syncState)
        assertEquals(0, recovered.manualDiscountMinor)
        assertNull(recovered.discountRequestVersion)
        assertEquals(2, recovered.revision)

        assertEquals(
            1,
            dao.markDraftPrepared(
                localId = LOCAL_ID,
                serverOrderId = SERVER_ID,
                subtotalMinor = 12_000,
                discountMinor = 0,
                pointsRedeemedMinor = 0,
                pointsRedeemed = 0,
                taxMinor = 0,
                roundOffMinor = 0,
                totalMinor = 12_000,
                dueMinor = 12_000,
                claimToken = "claim-1",
                claimExpiresAtMillis = 99_000,
                checkoutVersion = 8,
                updatedAtMillis = 3_000,
            ),
        )
        assertEquals(
            1,
            dao.returnPublishedDirectToRecovery(
                localId = LOCAL_ID,
                serverOrderId = SERVER_ID,
                prePublishVersion = 7,
                error = "finalization refused",
                updatedAtMillis = 4_000,
            ),
        )
        val replay = dao.withLines(LOCAL_ID)!!.order
        assertEquals(SyncState.PREPARING, replay.syncState)
        assertEquals(7L, replay.checkoutVersion)
        assertNull(replay.checkoutClaimToken)
        assertNull(replay.checkoutClaimExpiresAtMillis)
    }

    @Test
    fun acceptedZeroFinalizationIsDurableBeforeReceiptHydration() = runBlocking {
        dao.capture(order(SyncState.PREPARING), listOf(line()))
        assertEquals(
            1,
            dao.markDraftPrepared(
                localId = LOCAL_ID,
                serverOrderId = SERVER_ID,
                subtotalMinor = 12_000,
                discountMinor = 12_000,
                pointsRedeemedMinor = 12_000,
                pointsRedeemed = 1_200,
                taxMinor = 0,
                roundOffMinor = 0,
                totalMinor = 0,
                dueMinor = 0,
                claimToken = "claim-1",
                claimExpiresAtMillis = 99_000,
                checkoutVersion = 8,
                updatedAtMillis = 2_000,
            ),
        )

        assertEquals(
            0,
            dao.markExactZeroDirectFinalized(
                localId = LOCAL_ID,
                serverOrderId = SERVER_ID,
                claimToken = "wrong-claim",
                invoiceNo = "INV-1",
                updatedAtMillis = 3_000,
            ),
        )
        assertEquals(SyncState.AWAITING_PAYMENT, dao.withLines(LOCAL_ID)?.order?.syncState)

        assertEquals(
            1,
            dao.markExactZeroDirectFinalized(
                localId = LOCAL_ID,
                serverOrderId = SERVER_ID,
                claimToken = "claim-1",
                invoiceNo = "INV-1",
                updatedAtMillis = 4_000,
            ),
        )
        val accepted = dao.withLines(LOCAL_ID)!!.order
        assertEquals(SyncState.SYNCED, accepted.syncState)
        assertEquals("INV-1", accepted.invoiceNo)
        assertNull(accepted.checkoutClaimToken)
        assertNull(accepted.checkoutClaimExpiresAtMillis)
        assertEquals(1, dao.linesFor(LOCAL_ID).size)
    }

    private fun order(state: String) = LocalOrderEntity(
        localId = LOCAL_ID,
        shiftId = "local-shift",
        type = "takeaway",
        revision = 1,
        estimateMinor = 12_000,
        createdAtMillis = 1_000,
        syncState = state,
    )

    private fun line() = LocalOrderLineEntity(
        orderLocalId = LOCAL_ID,
        menuItemId = "item-1",
        clientLineId = "line-1",
        name = "Drink",
        qty = 1,
        unitPriceMinor = 12_000,
    )

    private companion object {
        const val LOCAL_ID = "local-order-1"
        const val SERVER_ID = "server-order-1"
    }
}
