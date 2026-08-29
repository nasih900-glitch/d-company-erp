package cloud.dcompany.erp.core.db

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cloud.dcompany.erp.core.sync.PosRefundEffectsRefreshGate
import cloud.dcompany.erp.core.sync.REQUIRED_POS_REFUND_EFFECT_PROJECTIONS
import cloud.dcompany.erp.core.sync.ResourceRefreshResult
import cloud.dcompany.erp.core.sync.clearDirtyMarkerIfReady
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RefundEffectsDirtyMarkerPersistenceTest {

    private lateinit var context: Context
    private val databaseName = "refund-effects-restart-test.db"

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(databaseName)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(databaseName)
    }

    @Test
    fun dirtyMarkerSurvivesDatabaseReopenUntilEveryRefreshCanClearIt() = runBlocking {
        val firstProcess = openDatabase()
        try {
            firstProcess.syncMetaDao().put(
                SyncMetaEntity(POS_REFUND_EFFECTS_DIRTY_SYNC_KEY, 1_000),
            )
            firstProcess.refundDao().insertLocalRefund(
                LocalRefundEntity(
                    localId = "refund-provider-conflict-restart",
                    clientActionId = "action-provider-conflict-restart",
                    orderId = "order-provider-conflict-restart",
                    invoiceNo = "INV-CONFLICT",
                    shiftId = "shift-1",
                    serverShiftId = "shift-1",
                    branchId = "branch-1",
                    terminalId = "terminal-1",
                    capturedByUserId = "owner-1",
                    reasonCode = "billing_error",
                    amountMinor = 12_500,
                    mode = "original",
                    externalReference = "UPI-PERSISTED-REF",
                    providerSettledAtMillis = 2_000,
                    createdAtMillis = 1_000,
                    state = RefundState.WITHDRAWN,
                    settlementMethod = "upi",
                    payoutConflict = true,
                    withdrawalAtMillis = 2_100,
                    withdrawnByName = "Other owner",
                    lastError = "Do not pay again. Owner reconciliation is required.",
                ),
            )
        } finally {
            firstProcess.close()
        }

        val restartedProcess = openDatabase()
        try {
            assertNotNull(
                restartedProcess.syncMetaDao().get(POS_REFUND_EFFECTS_DIRTY_SYNC_KEY),
            )
            val payoutConflict = restartedProcess.refundDao()
                .refundById("refund-provider-conflict-restart")!!
            assertTrue(payoutConflict.payoutConflict)
            assertEquals(RefundState.WITHDRAWN, payoutConflict.state)
            assertEquals("UPI-PERSISTED-REF", payoutConflict.externalReference)
            assertEquals(2_000L, payoutConflict.providerSettledAtMillis)
            assertEquals("Other owner", payoutConflict.withdrawnByName)
            assertEquals(1, restartedProcess.refundDao().observeUnresolvedRefunds().first().size)

            val permissionSkipped = PosRefundEffectsRefreshGate()
            REQUIRED_POS_REFUND_EFFECT_PROJECTIONS.forEach { resource ->
                permissionSkipped.recordRequired(
                    resource,
                    if (resource == "finance") {
                        ResourceRefreshResult.Skipped(resource)
                    } else {
                        ResourceRefreshResult.Refreshed(resource)
                    },
                )
            }
            assertFalse(
                permissionSkipped.clearDirtyMarkerIfReady(restartedProcess.syncMetaDao()),
            )
            assertNotNull(
                restartedProcess.syncMetaDao().get(POS_REFUND_EFFECTS_DIRTY_SYNC_KEY),
            )

            val allFresh = PosRefundEffectsRefreshGate()
            REQUIRED_POS_REFUND_EFFECT_PROJECTIONS.forEach { resource ->
                allFresh.recordRequired(resource, ResourceRefreshResult.Refreshed(resource))
            }
            assertTrue(allFresh.clearDirtyMarkerIfReady(restartedProcess.syncMetaDao()))
        } finally {
            restartedProcess.close()
        }

        val afterSuccessfulRefresh = openDatabase()
        try {
            assertNull(
                afterSuccessfulRefresh.syncMetaDao().get(POS_REFUND_EFFECTS_DIRTY_SYNC_KEY),
            )
        } finally {
            afterSuccessfulRefresh.close()
        }
    }

    private fun openDatabase(): ErpDatabase = Room.databaseBuilder(
        context,
        ErpDatabase::class.java,
        databaseName,
    ).addMigrations(*ALL_MIGRATIONS).build()
}
