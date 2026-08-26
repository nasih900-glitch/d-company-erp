package cloud.dcompany.erp.ui.screens.gaming

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class GamingStationPresentationTest {
    private val station = Station(
        id = "station-1",
        code = "PS5-1",
        name = "PS5 Station 1",
        type = "ps5",
        ratePerHourMinor = 15_000,
    )
    private val now = Instant.parse("2026-08-26T18:00:00Z").toEpochMilli()

    @Test
    fun `active session stays active before its authoritative timer end`() {
        val result = stationPresentation(
            station,
            session(status = "active", timerEndsAt = "2026-08-26T18:30:00Z"),
            now,
        )

        assertEquals(StationVisualState.Active, result.state)
        assertEquals("Active", result.statusLabel)
    }

    @Test
    fun `active session becomes overtime only after its authoritative timer end`() {
        val result = stationPresentation(
            station,
            session(status = "active", timerEndsAt = "2026-08-26T17:59:59Z"),
            now,
        )

        assertEquals(StationVisualState.Overtime, result.state)
        assertEquals("Overtime", result.statusLabel)
    }

    @Test
    fun `ended billable session is payment due until POS accepts it`() {
        val result = stationPresentation(
            station,
            session(status = "ended", amountMinor = 15_750, billableMinutes = 63),
            now,
        )

        assertEquals(StationVisualState.PaymentDue, result.state)
        assertEquals("Payment due", result.statusLabel)
    }

    @Test
    fun `zero value ended session requires an audited cancellation`() {
        val result = stationPresentation(
            station,
            session(status = "ended", amountMinor = 0),
            now,
        )

        assertEquals(StationVisualState.CancellationRequired, result.state)
        assertEquals("Needs review", result.statusLabel)
    }

    @Test
    fun `local POS handoff states remain visible and distinct`() {
        val pending = stationPresentation(
            station,
            session(status = "ended", amountMinor = 15_750, localState = "send_pending"),
            now,
        )
        val rejected = stationPresentation(
            station,
            session(status = "ended", amountMinor = 15_750, localState = "send_rejected"),
            now,
        )

        assertEquals(StationVisualState.SendPending, pending.state)
        assertEquals(StationVisualState.SendRejected, rejected.state)
    }

    @Test
    fun `rejected stop remains visible with an explicit retry state`() {
        val result = stationPresentation(
            station,
            session(status = "active", localState = "stop_rejected"),
            now,
        )

        assertEquals(StationVisualState.StopFailed, result.state)
        assertEquals("Stop failed", result.statusLabel)
    }

    @Test
    fun `rejected POS handoff explains the actual cause before retry`() {
        val rejected = session(
            status = "ended",
            amountMinor = 15_750,
            localState = "send_rejected",
            lastError = "Open a POS shift on Main Terminal, then retry.",
        )

        assertEquals(
            "Open a POS shift on Main Terminal, then retry.",
            unbilledSessionDetail(StationVisualState.SendRejected, rejected),
        )
        assertEquals(
            "POS refused the handoff. Check the shift and connection, then retry.",
            unbilledSessionDetail(
                StationVisualState.SendRejected,
                rejected.copy(lastError = "  "),
            ),
        )
    }

    @Test
    fun `disabled station never hides a session that still needs operational action`() {
        val disabledStation = station.copy(isActive = false)

        assertEquals(
            StationVisualState.Active,
            stationPresentation(disabledStation, session(status = "active"), now).state,
        )
        assertEquals(
            StationVisualState.PaymentDue,
            stationPresentation(
                disabledStation,
                session(status = "ended", amountMinor = 15_750, billableMinutes = 63),
                now,
            ).state,
        )
    }

    @Test
    fun `customer identity remains available on active cards and payment queue rows`() {
        assertEquals(
            "Asha · 9876543210",
            sessionCustomerLabel(
                session(status = "active", customerName = " Asha ", customerPhone = " 9876543210 "),
            ),
        )
        assertEquals(
            "9876543210",
            sessionCustomerLabel(session(status = "active", customerPhone = "9876543210")),
        )
        assertEquals(null, sessionCustomerLabel(session(status = "active")))
    }

    @Test
    fun `disabled and available stations never expose active actions`() {
        assertEquals(
            StationVisualState.Disabled,
            stationPresentation(station.copy(isActive = false), null, now).state,
        )
        assertEquals(
            StationVisualState.Available,
            stationPresentation(station, null, now).state,
        )
    }

    private fun session(
        status: String,
        timerEndsAt: String? = null,
        amountMinor: Long? = null,
        billableMinutes: Int? = null,
        localState: String? = null,
        lastError: String? = null,
        customerName: String? = null,
        customerPhone: String? = null,
    ) = GameSession(
        id = "session-1",
        stationId = station.id,
        status = status,
        startAt = "2026-08-26T17:00:00Z",
        timerEndsAt = timerEndsAt,
        amountMinor = amountMinor,
        billableMinutes = billableMinutes,
        localState = localState,
        lastError = lastError,
        customerName = customerName,
        customerPhone = customerPhone,
    )
}
