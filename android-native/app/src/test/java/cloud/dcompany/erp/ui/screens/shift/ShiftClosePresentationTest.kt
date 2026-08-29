package cloud.dcompany.erp.ui.screens.shift

import androidx.compose.ui.unit.dp
import cloud.dcompany.erp.core.db.LocalShiftEntity
import cloud.dcompany.erp.core.db.ResolvedOpenShift
import cloud.dcompany.erp.core.db.ShiftSource
import cloud.dcompany.erp.core.db.ShiftState
import cloud.dcompany.erp.core.db.ShiftAccountingBreakdown
import cloud.dcompany.erp.ui.WorkspaceFeatureProfiles
import cloud.dcompany.erp.ui.presentationPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShiftClosePresentationTest {

    @Test
    fun `gaming centre hides zero legacy rows but preserves nonzero legacy money`() {
        val presentation = WorkspaceFeatureProfiles.GamingCentre.presentationPolicy()
        val zero = accounting()
        val nonzero = accounting(membershipCollectionsMinor = 4_500, membershipRefundsMinor = 600)

        assertTrue(shiftLegacyMoneyRows(zero, presentation).isEmpty())
        val rows = shiftLegacyMoneyRows(nonzero, presentation)
        assertEquals(5_100L, rows.sumOf { it.amountMinor })
        assertTrue(rows.all { !it.label.contains("membership", ignoreCase = true) })
        assertTrue(nonzero.hasLegacyPrepaidMoney())
    }

    @Test
    fun `denomination columns preserve editable stepper width on compact screens`() {
        assertEquals(1, denominationColumnCount(320.dp))
        assertEquals(1, denominationColumnCount(479.dp))
        assertEquals(2, denominationColumnCount(480.dp))
        assertEquals(2, denominationColumnCount(719.dp))
        assertEquals(3, denominationColumnCount(720.dp))
        assertEquals(3, denominationColumnCount(872.dp))
    }

    @Test
    fun `drawer total is derived only from supported denomination counts`() {
        assertEquals(
            50_200L,
            drawerCountedMinor(
                mapOf(
                    500L to "1",
                    1L to "2",
                    999L to "99",
                ),
            ),
        )
    }

    @Test
    fun `pending close displays durable saved count instead of empty draft`() {
        val presentation = shiftClosePresentation(
            localState = ShiftState.CLOSE_PENDING,
            savedCountedMinor = 108_300,
            draftCountedMinor = 0,
            canEdit = true,
        )

        assertEquals(108_300L, presentation.displayedCountedMinor)
        assertTrue(presentation.usesSavedCount)
        assertFalse(presentation.canEditCount)
        assertFalse(presentation.canRetrySavedClose)
        assertFalse(presentation.canContinueShift)
    }

    @Test
    fun `rejected close retries only the durable saved count`() {
        val presentation = shiftClosePresentation(
            localState = ShiftState.CLOSE_REJECTED,
            savedCountedMinor = 27_450,
            draftCountedMinor = 999_999,
            canEdit = true,
        )

        assertEquals(27_450L, presentation.displayedCountedMinor)
        assertTrue(presentation.usesSavedCount)
        assertFalse(presentation.canEditCount)
        assertTrue(presentation.canRetrySavedClose)
        assertTrue(presentation.canContinueShift)
    }

    @Test
    fun `rejected close without a valid durable count cannot be retried`() {
        listOf<Long?>(null, -1).forEach { invalidSavedCount ->
            val presentation = shiftClosePresentation(
                localState = ShiftState.CLOSE_REJECTED,
                savedCountedMinor = invalidSavedCount,
                draftCountedMinor = 0,
                canEdit = true,
            )

            assertNull(presentation.displayedCountedMinor)
            assertTrue(presentation.usesSavedCount)
            assertFalse(presentation.canEditCount)
            assertFalse(presentation.canRetrySavedClose)
            assertTrue(presentation.canContinueShift)
        }
    }

    @Test
    fun `durably saved zero is a valid empty drawer count`() {
        val presentation = shiftClosePresentation(
            localState = ShiftState.CLOSE_REJECTED,
            savedCountedMinor = 0,
            draftCountedMinor = 99_900,
            canEdit = true,
        )

        assertEquals(0L, presentation.displayedCountedMinor)
        assertTrue(presentation.canRetrySavedClose)
        assertTrue(presentation.canContinueShift)
        assertTrue(
            presentation.savedCountMessage(
                valid = "Saved %s",
                missing = "missing",
            ).startsWith("Saved ₹0.00"),
        )
    }

    @Test
    fun `open shift presents the current denomination draft`() {
        val presentation = shiftClosePresentation(
            localState = ShiftState.OPEN_SYNCED,
            savedCountedMinor = 77_700,
            draftCountedMinor = 12_300,
            canEdit = true,
        )

        assertEquals(12_300L, presentation.displayedCountedMinor)
        assertFalse(presentation.usesSavedCount)
        assertTrue(presentation.canEditCount)
        assertFalse(presentation.canRetrySavedClose)
        assertFalse(presentation.canContinueShift)
    }

    @Test
    fun `local UI identity survives server id assignment but differs across shifts`() {
        val beforeSync = resolvedShift(localId = "local-a", shiftId = "local-a")
        val afterSync = resolvedShift(localId = "local-a", shiftId = "server-a")
        val nextShift = resolvedShift(localId = "local-b", shiftId = "server-b")

        assertEquals(shiftCloseUiIdentity(beforeSync), shiftCloseUiIdentity(afterSync))
        assertFalse(shiftCloseUiIdentity(beforeSync) == shiftCloseUiIdentity(nextShift))
    }

    @Test
    fun `local and server identity namespaces cannot collide`() {
        val local = resolvedShift(localId = "same-id", shiftId = "same-id")
        val serverOnly = resolvedShift(localId = null, shiftId = "same-id")

        assertEquals("local:same-id", shiftCloseUiIdentity(local))
        assertEquals("server:same-id", shiftCloseUiIdentity(serverOnly))
        assertFalse(shiftCloseUiIdentity(local) == shiftCloseUiIdentity(serverOnly))
    }

    @Test
    fun `confirmation can only be consumed by its exact shift`() {
        val confirmation = ShiftCloseConfirmation(
            shiftIdentity = "local:shift-a",
            countedMinor = 42_000,
        )

        assertTrue(confirmation.isFor("local:shift-a"))
        assertFalse(confirmation.isFor("local:shift-b"))
        assertFalse(confirmation.isFor(null))
    }

    private fun resolvedShift(localId: String?, shiftId: String): ResolvedOpenShift =
        ResolvedOpenShift(
            shiftId = shiftId,
            source = if (localId == null) ShiftSource.SERVER_CACHE else ShiftSource.LOCAL_OUTBOX,
            local = localId?.let {
                LocalShiftEntity(
                    localId = it,
                    serverShiftId = shiftId.takeIf { id -> id != it },
                    terminalId = "terminal-a",
                    branchId = "branch-a",
                    openingFloatMinor = 5_000,
                    openedAtMillis = 1_000,
                    state = ShiftState.OPEN_SYNCED,
                )
            },
            openedAtMillis = 1_000,
            openingFloatMinor = 5_000,
            expectedMinor = 5_000,
            openedByUserId = "user-a",
            openedByName = "Rafi",
            openedByEmail = "rafi@example.test",
        )

    private fun accounting(
        membershipCollectionsMinor: Long = 0,
        membershipRefundsMinor: Long = 0,
    ) = ShiftAccountingBreakdown(
        posCollectionsMinor = 10_000,
        membershipCollectionsMinor = membershipCollectionsMinor,
        grossCollectionsMinor = 10_000 + membershipCollectionsMinor,
        cashCollectionsMinor = 10_000,
        cardCollectionsMinor = 0,
        upiCollectionsMinor = membershipCollectionsMinor,
        otherCollectionsMinor = 0,
        settledPosRefundsMinor = 0,
        settledMembershipRefundsMinor = membershipRefundsMinor,
        totalRefundsMinor = membershipRefundsMinor,
        netCollectionsMinor = 10_000 + membershipCollectionsMinor - membershipRefundsMinor,
    )
}
