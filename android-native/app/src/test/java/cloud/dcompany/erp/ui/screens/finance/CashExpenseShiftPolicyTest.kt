package cloud.dcompany.erp.ui.screens.finance

import cloud.dcompany.erp.core.db.LocalExpenseEntity
import cloud.dcompany.erp.core.db.ServerOpenShiftEntity
import cloud.dcompany.erp.core.db.ShiftResolutionPolicy
import cloud.dcompany.erp.core.db.SyncState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CashExpenseShiftPolicyTest {

    private val thisInstallation = "b7ee1c68-77e2-48e8-82ac-0d4623606ec1"
    private val otherInstallation = "4bcf3e35-76f5-4850-a3af-c04854581b7f"

    private val serverShift = ServerOpenShiftEntity(
        terminalId = "terminal-1",
        serverShiftId = "6d330c0a-d038-40d8-befb-b9fd21898fec",
        branchId = "branch-1",
        status = "open",
        openingFloatMinor = 10_000,
        expectedMinor = 12_500,
        openedAtMillis = 1_000,
        openedByUserId = "another-user",
        openedByName = "Rafi",
        openedByEmail = "rafi@example.com",
        openingClientPlatform = "android",
        openingClientInstallationId = thisInstallation,
        verifiedAtMillis = 2_000,
    )

    @Test
    fun `same installation colleague shift remains selectable and shows drawer evidence`() {
        val resolved = ShiftResolutionPolicy.resolve(null, serverShift)
        val option = cashExpenseShiftOption(
            resolved,
            "branch-1",
            emptyList(),
            currentInstallationId = thisInstallation,
        )

        assertNotNull(option)
        assertEquals(serverShift.serverShiftId, option?.id)
        assertEquals("Rafi", option?.openedBy)
        assertEquals(12_500L, option?.expectedMinor)
        assertEquals(12_500L, option?.availableMinor)
    }

    @Test
    fun `offline cash allows another user on originating tablet`() {
        val option = cashExpenseShiftOption(
            ShiftResolutionPolicy.resolve(null, serverShift),
            "branch-1",
            emptyList(),
            currentInstallationId = thisInstallation,
        )

        assertNotNull(option)
        assertEquals("Rafi", option?.openedBy)
    }

    @Test
    fun `cash refuses Web other-installation and unknown origins regardless of connectivity`() {
        val variants = listOf(
            serverShift.copy(
                openingClientPlatform = "web",
                openingClientInstallationId = null,
            ),
            serverShift.copy(openingClientInstallationId = otherInstallation),
            serverShift.copy(
                openingClientPlatform = null,
                openingClientInstallationId = null,
            ),
        )

        variants.forEach { cached ->
            assertNull(
                cashExpenseShiftOption(
                    ShiftResolutionPolicy.resolve(null, cached),
                    "branch-1",
                    emptyList(),
                    currentInstallationId = thisInstallation,
                ),
            )
        }
        assertNull(
            cashExpenseShiftOption(
                ShiftResolutionPolicy.resolve(null, serverShift),
                "branch-1",
                emptyList(),
                currentInstallationId = null,
            ),
        )
    }

    @Test
    fun `pending and not-yet-observed synced cash stay reserved locally`() {
        val pending = expense(
            localId = "pending",
            shiftId = serverShift.serverShiftId,
            amountMinor = 2_000,
        )
        val rejected = expense(
            localId = "rejected",
            shiftId = serverShift.serverShiftId,
            amountMinor = 7_000,
            state = SyncState.REJECTED,
        )
        val otherShift = expense(
            localId = "other",
            shiftId = "another-shift",
            amountMinor = 4_000,
        )
        val recentlySynced = expense(
            localId = "synced-new",
            shiftId = serverShift.serverShiftId,
            amountMinor = 1_000,
            state = SyncState.SYNCED,
            createdAtMillis = serverShift.verifiedAtMillis,
        )
        val observedSynced = expense(
            localId = "synced-old",
            shiftId = serverShift.serverShiftId,
            amountMinor = 5_000,
            state = SyncState.SYNCED,
            createdAtMillis = serverShift.verifiedAtMillis - 1,
        )
        val option = requireNotNull(
            cashExpenseShiftOption(
                ShiftResolutionPolicy.resolve(null, serverShift),
                "branch-1",
                listOf(pending, rejected, otherShift, recentlySynced, observedSynced),
                currentInstallationId = thisInstallation,
            ),
        )

        assertEquals(3_000L, option.queuedMinor)
        assertEquals(9_500L, option.availableMinor)
    }

    @Test
    fun `unconfirmed local shift and wrong branch are never offered`() {
        assertNull(
            cashExpenseShiftOption(
                null,
                "branch-1",
                emptyList(),
                currentInstallationId = thisInstallation,
            ),
        )
        assertNull(
            cashExpenseShiftOption(
                ShiftResolutionPolicy.resolve(null, serverShift),
                "branch-2",
                emptyList(),
                currentInstallationId = thisInstallation,
            ),
        )
    }

    @Test
    fun `cash requires explicit same branch shift with enough available drawer cash`() {
        val option = requireNotNull(
            cashExpenseShiftOption(
                ShiftResolutionPolicy.resolve(null, serverShift),
                "branch-1",
                emptyList(),
                currentInstallationId = thisInstallation,
            ),
        )
        assertTrue(
            cashExpenseSelectionError("cash", "branch-1", null, 500, listOf(option))!!
                .contains("Select"),
        )
        assertTrue(
            cashExpenseSelectionError("cash", "branch-2", option.id, 500, listOf(option))!!
                .contains("Select"),
        )
        assertTrue(
            cashExpenseSelectionError("cash", "branch-1", option.id, 12_501, listOf(option))!!
                .contains("exceeds"),
        )
        assertNull(cashExpenseSelectionError("cash", "branch-1", option.id, 12_500, listOf(option)))
        assertNull(cashExpenseSelectionError("upi", "branch-1", null, 20_000, emptyList()))
    }

    @Test
    fun `offline replay body and canonical duplicate key remain identical`() {
        val localId = "123e4567-e89b-12d3-a456-426614174000"
        val row = expense(
            localId = localId,
            shiftId = serverShift.serverShiftId,
            amountMinor = 1_500,
        )

        assertEquals(row.toExpenseCreate(), row.toExpenseCreate())
        assertEquals(serverShift.serverShiftId, row.toExpenseCreate().shiftId)
        assertEquals("expense:$localId", expenseIdempotencyKey(localId))
    }

    @Test
    fun `only a migration marked shiftless Code21 cash row sends recovery origin`() {
        val legacy = expense(
            localId = "123e4567-e89b-12d3-a456-426614174001",
            shiftId = serverShift.serverShiftId,
            amountMinor = 1_500,
        ).copy(
            shiftId = null,
            legacyOriginVersionCode = CODE21_CASH_EXPENSE_ORIGIN_VERSION,
        )
        assertEquals(
            mapOf(SAVED_CLIENT_VERSION_CODE_HEADER to "21"),
            legacy.legacyCashExpenseRecoveryHeaders(),
        )
        assertTrue(
            legacy.copy(legacyOriginVersionCode = null)
                .legacyCashExpenseRecoveryHeaders().isEmpty(),
        )
        assertThrows(IllegalArgumentException::class.java) {
            legacy.copy(shiftId = serverShift.serverShiftId)
                .legacyCashExpenseRecoveryHeaders()
        }
        assertThrows(IllegalArgumentException::class.java) {
            legacy.copy(legacyOriginVersionCode = 36)
                .legacyCashExpenseRecoveryHeaders()
        }
    }

    private fun expense(
        localId: String,
        shiftId: String,
        amountMinor: Long,
        state: String = SyncState.PENDING,
        createdAtMillis: Long = 1_000,
    ) = LocalExpenseEntity(
        localId = localId,
        branchId = "branch-1",
        categoryId = "category-1",
        supplierId = null,
        amountMinor = amountMinor,
        paidVia = "cash",
        paidAt = "2026-09-19T12:00:00Z",
        vendorName = "Vendor",
        invoiceNo = "INV-1",
        note = "Cash paid-out",
        createdAtMillis = createdAtMillis,
        shiftId = shiftId,
        syncState = state,
    )
}
