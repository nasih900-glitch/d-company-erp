package cloud.dcompany.erp.ui.screens.shift

import cloud.dcompany.erp.core.db.ResolvedOpenShift
import cloud.dcompany.erp.core.db.ShiftHistoryRow
import cloud.dcompany.erp.core.db.ShiftHistorySource
import cloud.dcompany.erp.core.db.ShiftSource
import java.lang.Math.addExact
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val SHIFT_BUSINESS_ZONE: ZoneId = ZoneId.of("Asia/Kolkata")
private val SHIFT_BUSINESS_DATE_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("EEE, d MMM yyyy", Locale.ENGLISH)
private val SHIFT_BUSINESS_TIME_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("h:mm a 'IST'", Locale.ENGLISH)

/**
 * One commercial day for staff, backed by every immutable drawer lifecycle
 * that was opened on that IST date. Closed source rows are never rewritten;
 * the current open shift is added only as a transient presentation row and is
 * suppressed when the same shift has already reached immutable history.
 */
internal data class ShiftBusinessDaySummary(
    val businessDate: LocalDate,
    val segments: List<ShiftHistoryRow>,
    val firstOpenedAtMillis: Long,
    val finalClosedAtMillis: Long?,
    val hasOpenSegment: Boolean,
    val firstOpenerLabel: String,
    val finalCloserLabel: String,
    val grossCollectionsMinor: Long?,
    val totalRefundsMinor: Long?,
    val netCollectionsMinor: Long?,
) {
    val stableId: String = "business-day:$businessDate"
    val dateLabel: String = businessDate.format(SHIFT_BUSINESS_DATE_FORMAT)
}

/**
 * Group history by the shop's financial timezone. Totals remain unavailable
 * if any source segment lacks an authoritative amount, because treating a
 * missing value as zero would understate that day's collection.
 */
internal fun groupShiftHistoryByBusinessDay(
    history: List<ShiftHistoryRow>,
    currentOpen: ResolvedOpenShift? = null,
    businessZone: ZoneId = SHIFT_BUSINESS_ZONE,
): List<ShiftBusinessDaySummary> {
    val openProjection = currentOpen
        ?.takeUnless { history.containsShiftIdentity(it) }
        ?.toOpenHistoryProjection()
    val openProjectionId = openProjection?.stableId

    return (history + listOfNotNull(openProjection))
        .groupBy { row ->
            Instant.ofEpochMilli(row.openedAtMillis).atZone(businessZone).toLocalDate()
        }
        .map { (businessDate, source) ->
            val segments = source.sortedBy(ShiftHistoryRow::openedAtMillis)
            val first = segments.first()
            val hasOpenSegment = openProjectionId != null &&
                segments.any { it.stableId == openProjectionId }
            // History queries return only closed rows, but old/damaged rows may
            // still lack closedAt. Keep that distinct from a real current shift:
            // its final close needs review without claiming the drawer is open.
            val hasIncompleteClosedSegment = segments.any {
                it.stableId != openProjectionId && it.closedAtMillis == null
            }
            val finalClosed = if (hasOpenSegment || hasIncompleteClosedSegment) {
                null
            } else {
                segments.maxByOrNull { it.closedAtMillis ?: Long.MIN_VALUE }
            }
            ShiftBusinessDaySummary(
                businessDate = businessDate,
                segments = segments,
                firstOpenedAtMillis = first.openedAtMillis,
                finalClosedAtMillis = finalClosed?.closedAtMillis,
                hasOpenSegment = hasOpenSegment,
                firstOpenerLabel = shiftHistoryOpenerLabel(first),
                finalCloserLabel = finalClosed?.let(::shiftHistoryCloserLabel)
                    ?: "close time unavailable",
                grossCollectionsMinor = segments.completeSum(ShiftHistoryRow::grossCollectionsMinor),
                totalRefundsMinor = segments.completeSum(ShiftHistoryRow::totalRefundsMinor),
                netCollectionsMinor = segments.completeSum(ShiftHistoryRow::netCollectionsMinor),
            )
        }
        .sortedByDescending(ShiftBusinessDaySummary::businessDate)
}

/** Match server identity first; an unsynced local shift falls back to its durable local id. */
private fun List<ShiftHistoryRow>.containsShiftIdentity(open: ResolvedOpenShift): Boolean {
    val serverShiftId = open.server?.serverShiftId
        ?: open.local?.serverShiftId
        ?: open.shiftId.takeIf { open.source == ShiftSource.SERVER_CACHE }
    val localShiftId = open.local?.localId
        ?: open.shiftId.takeIf { open.source == ShiftSource.LOCAL_OUTBOX && serverShiftId == null }
    return any { row ->
        (serverShiftId != null && row.serverShiftId == serverShiftId) ||
            (localShiftId != null && row.stableId == "local:$localShiftId")
    }
}

/** Presentation-only row for live collections; it is never written back to Room. */
private fun ResolvedOpenShift.toOpenHistoryProjection(): ShiftHistoryRow {
    val serverShiftId = server?.serverShiftId
        ?: local?.serverShiftId
        ?: shiftId.takeIf { source == ShiftSource.SERVER_CACHE }
    val localShiftId = local?.localId
        ?: shiftId.takeIf { source == ShiftSource.LOCAL_OUTBOX && serverShiftId == null }
    val identity = serverShiftId?.let { "server:$it" }
        ?: "local:${localShiftId ?: shiftId}"
    return ShiftHistoryRow(
        stableId = "current-open:$identity",
        serverShiftId = serverShiftId,
        source = if (source == ShiftSource.SERVER_CACHE) {
            ShiftHistorySource.SERVER
        } else {
            ShiftHistorySource.LOCAL
        },
        openedAtMillis = openedAtMillis,
        closedAtMillis = null,
        openingFloatMinor = openingFloatMinor,
        expectedMinor = expectedMinor,
        countedMinor = null,
        varianceMinor = null,
        grossCollectionsMinor = grossCollectionsMinor,
        cashCollectionsMinor = cashCollectionsMinor,
        cardCollectionsMinor = cardCollectionsMinor,
        upiCollectionsMinor = upiCollectionsMinor,
        otherCollectionsMinor = otherCollectionsMinor,
        totalRefundsMinor = totalRefundsMinor,
        netCollectionsMinor = netCollectionsMinor,
        openedByUserId = openedByUserId,
        openedByName = openedByName,
        openedByEmail = openedByEmail,
        closedByUserId = null,
        closedByName = null,
        closedByEmail = null,
    )
}

internal fun formatShiftBusinessTime(epochMillis: Long): String = runCatching {
    SHIFT_BUSINESS_TIME_FORMAT.format(Instant.ofEpochMilli(epochMillis).atZone(SHIFT_BUSINESS_ZONE))
}.getOrDefault("Time unavailable")

internal fun shiftHistoryOpenerLabel(shift: ShiftHistoryRow): String =
    shift.openedByName?.takeIf(String::isNotBlank)
        ?: shift.openedByEmail?.takeIf(String::isNotBlank)
        ?: "not provided by the server"

internal fun shiftHistoryCloserLabel(shift: ShiftHistoryRow): String =
    shift.closedByName?.takeIf(String::isNotBlank)
        ?: shift.closedByEmail?.takeIf(String::isNotBlank)
        ?: if (shift.source == ShiftHistorySource.LOCAL) {
            "closer attribution pending"
        } else {
            "not provided by the server"
        }

private fun List<ShiftHistoryRow>.completeSum(
    selector: (ShiftHistoryRow) -> Long?,
): Long? {
    var total = 0L
    for (row in this) {
        val amount = selector(row) ?: return null
        total = runCatching { addExact(total, amount) }.getOrNull() ?: return null
    }
    return total
}
