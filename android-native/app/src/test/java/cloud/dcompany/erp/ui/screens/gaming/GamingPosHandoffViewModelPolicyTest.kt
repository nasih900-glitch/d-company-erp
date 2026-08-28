package cloud.dcompany.erp.ui.screens.gaming

import cloud.dcompany.erp.core.auth.TerminalPurpose
import cloud.dcompany.erp.core.net.ApiException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GamingPosHandoffViewModelPolicyTest {
    private val target = PosTargetShift(
        shiftId = "cafe-shift",
        terminalId = "cafe-terminal",
        terminalName = "Front till",
        openedBy = "cashier-1",
        openedByName = "Rafi",
        openedAt = "2026-08-28T12:00:00Z",
    )
    private val session = GameSession(
        id = "session-1",
        stationId = "station-1",
        shiftId = "gaming-shift",
        status = "ended",
        startAt = "2026-08-28T11:00:00Z",
        endAt = "2026-08-28T12:00:00Z",
        amountMinor = 15_750,
    )

    @Test
    fun `terminal purpose alone routes the bill without name or count inference`() {
        assertEquals(GamingPosRoute.CROSS_TERMINAL, gamingPosRoute(TerminalPurpose.GAMING))
        assertEquals(GamingPosRoute.LOCAL, gamingPosRoute(TerminalPurpose.CAFE_POS))
        assertEquals(GamingPosRoute.LOCAL, gamingPosRoute(TerminalPurpose.HYBRID))
        assertEquals(GamingPosRoute.BLOCKED, gamingPosRoute("future-purpose"))
        assertEquals(GamingPosRoute.BLOCKED, gamingPosRoute(null))
    }

    @Test
    fun `cafe terminal cannot start gaming while gaming and hybrid can`() {
        val cafePosGuidance = gamingStartTerminalBlockMessage(TerminalPurpose.CAFE_POS).orEmpty()
        assertTrue(cafePosGuidance.contains("Cafe POS"))
        assertTrue(cafePosGuidance.contains("Change terminal"))
        assertTrue(cafePosGuidance.contains("Gaming Area"))
        assertFalse(cafePosGuidance.contains("Hybrid"))
        assertNull(gamingStartTerminalBlockMessage(TerminalPurpose.GAMING))
        assertNull(gamingStartTerminalBlockMessage(TerminalPurpose.HYBRID))
        assertTrue(gamingStartTerminalBlockMessage("unknown").orEmpty().contains("not recognised"))
    }

    @Test
    fun `gaming-only terminal never falls back when no eligible cafe shift is open`() {
        val message = posTargetListError(emptyList(), sourceTerminalId = "gaming-terminal")

        assertTrue(message.orEmpty().contains("No eligible Cafe POS shift is open"))
        assertTrue(message.orEmpty().contains("remains saved"))
    }

    @Test
    fun `malformed or source-terminal destinations fail closed`() {
        assertTrue(
            posTargetListError(
                listOf(target.copy(terminalId = "gaming-terminal")),
                sourceTerminalId = "gaming-terminal",
            ).orEmpty().contains("could not be verified"),
        )
        assertTrue(
            posTargetListError(
                listOf(target, target.copy(terminalName = "Other label")),
                sourceTerminalId = "gaming-terminal",
            ).orEmpty().contains("duplicate"),
        )
    }

    @Test
    fun `only exact source and selected target receipt releases the ended bill`() {
        val receipt = SessionPosHandoffResult(
            orderId = "order-1",
            amountMinor = 15_750,
            sourceShiftId = "gaming-shift",
            sourceTerminalId = "gaming-terminal",
            targetShiftId = "cafe-shift",
            targetTerminalId = "cafe-terminal",
            alreadyLinked = true,
        )

        assertNull(posHandoffResponseError(session, "gaming-terminal", target, receipt))
        assertTrue(
            posHandoffResponseError(
                session,
                "gaming-terminal",
                target,
                receipt.copy(targetShiftId = "different-shift"),
            ).orEmpty().contains("selected Cafe POS shift"),
        )
        assertTrue(
            posHandoffResponseError(
                session,
                "gaming-terminal",
                target,
                receipt.copy(sourceShiftId = "different-source"),
            ).orEmpty().contains("source shift"),
        )
    }

    @Test
    fun `ambiguous retry guidance requires the same target for natural idempotency`() {
        val message = posHandoffFailureMessage(
            ApiException("response lost"),
            terminalName = "Front till",
        )

        assertTrue(message.contains("remains visible"))
        assertTrue(message.contains("Retry the same shift"))
        assertTrue(message.contains("without creating a duplicate"))
    }
}
