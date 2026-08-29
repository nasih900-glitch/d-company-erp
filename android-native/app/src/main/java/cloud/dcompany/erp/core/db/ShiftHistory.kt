package cloud.dcompany.erp.core.db

enum class ShiftHistorySource { SERVER, LOCAL }

/** Presentation row after authoritative server history is overlaid with local recovery rows. */
data class ShiftHistoryRow(
    val stableId: String,
    val serverShiftId: String?,
    val source: ShiftHistorySource,
    val openedAtMillis: Long,
    val closedAtMillis: Long?,
    val openingFloatMinor: Long,
    val expectedMinor: Long?,
    val countedMinor: Long?,
    val varianceMinor: Long?,
    val grossCollectionsMinor: Long?,
    val cashCollectionsMinor: Long?,
    val cardCollectionsMinor: Long?,
    val upiCollectionsMinor: Long?,
    val otherCollectionsMinor: Long?,
    val totalRefundsMinor: Long?,
    val netCollectionsMinor: Long?,
    val openedByUserId: String?,
    val openedByName: String?,
    val openedByEmail: String?,
)

/**
 * Server rows win when both sources describe the same shift because their
 * totals and opener join are authoritative. Local-only rows remain visible
 * after offline recovery until the next successful history pull includes
 * them.
 */
object ShiftHistoryMergePolicy {
    fun merge(
        server: List<ShiftHistoryCacheEntity>,
        local: List<LocalShiftEntity>,
        limit: Int = 200,
    ): List<ShiftHistoryRow> {
        val serverIds = server.mapTo(mutableSetOf()) { it.id }
        val rows = buildList {
            server.forEach { row ->
                add(
                    ShiftHistoryRow(
                        stableId = "server:${row.id}",
                        serverShiftId = row.id,
                        source = ShiftHistorySource.SERVER,
                        openedAtMillis = row.openedAtMillis,
                        closedAtMillis = row.closedAtMillis,
                        openingFloatMinor = row.openingFloatMinor,
                        expectedMinor = row.expectedMinor,
                        countedMinor = row.countedMinor,
                        varianceMinor = row.varianceMinor,
                        grossCollectionsMinor = row.grossCollectionsMinor,
                        cashCollectionsMinor = row.cashCollectionsMinor,
                        cardCollectionsMinor = row.cardCollectionsMinor,
                        upiCollectionsMinor = row.upiCollectionsMinor,
                        otherCollectionsMinor = row.otherCollectionsMinor,
                        totalRefundsMinor = row.totalRefundsMinor,
                        netCollectionsMinor = row.netCollectionsMinor,
                        openedByUserId = row.openedByUserId,
                        openedByName = row.openedByName,
                        openedByEmail = row.openedByEmail,
                    ),
                )
            }
            local.filter { it.serverShiftId == null || it.serverShiftId !in serverIds }
                .forEach { row ->
                    add(
                        ShiftHistoryRow(
                            stableId = "local:${row.localId}",
                            serverShiftId = row.serverShiftId,
                            source = ShiftHistorySource.LOCAL,
                            openedAtMillis = row.openedAtMillis,
                            closedAtMillis = row.closedAtMillis,
                            openingFloatMinor = row.openingFloatMinor,
                            expectedMinor = null,
                            countedMinor = row.countedMinor,
                            varianceMinor = row.varianceMinor,
                            grossCollectionsMinor = null,
                            cashCollectionsMinor = null,
                            cardCollectionsMinor = null,
                            upiCollectionsMinor = null,
                            otherCollectionsMinor = null,
                            totalRefundsMinor = null,
                            netCollectionsMinor = null,
                            openedByUserId = row.openedByUserId,
                            openedByName = row.openedByName,
                            openedByEmail = row.openedByEmail,
                        ),
                    )
                }
        }
        return rows.sortedByDescending { it.openedAtMillis }.take(limit)
    }
}
