package cloud.dcompany.erp.core.sync

import cloud.dcompany.erp.core.db.CafeActionKind
import cloud.dcompany.erp.core.db.CafeActionState
import cloud.dcompany.erp.core.db.CafeBillCacheEntity
import cloud.dcompany.erp.core.db.LocalCafeActionEntity
import cloud.dcompany.erp.core.db.LocalCafeBillEntity

data class CafeBillLineProjection(
    val stableKey: String,
    val serverLineId: String?,
    val clientLineId: String?,
    val menuItemId: String?,
    val name: String,
    val qty: Double,
    val unitPriceMinor: Long,
    val lineTotalMinor: Long,
    val note: String?,
    val roundNo: Int?,
    val kitchenStatus: String,
    val locallyPending: Boolean,
    val voided: Boolean,
    val voidReason: String?,
    val kitchenCancellationPending: Boolean,
)

data class CafeBillProjection(
    val localBillId: String?,
    val serverOrderId: String?,
    val tableId: String,
    val tableCode: String?,
    val status: String,
    val checkoutVersion: Long?,
    val subtotalMinor: Long,
    val taxMinor: Long,
    /** Last server-confirmed total, absent for a wholly offline first round. */
    val confirmedTotalMinor: Long?,
    /**
     * Server total plus the locally captured line delta. This remains an
     * estimate until sync because pricing/tax rules are server-authoritative.
     */
    val totalMinor: Long,
    val amountPending: Boolean,
    val lines: List<CafeBillLineProjection>,
    val pendingActionCount: Int,
    val blockedActionId: String?,
    val blockedMessage: String?,
) {
    val editable: Boolean get() = status == "open" && blockedActionId == null
    val heldOrSending: Boolean get() = status == "held" || status == "sending_to_pos"
}

/**
 * Deterministically overlays durable local intent on the last confirmed
 * server snapshot. This function has no Android/Room dependencies so restart,
 * conflict and rapid-tap cases are cheap JVM tests rather than emulator-only
 * behaviour.
 */
internal fun projectCafeBills(
    serverBills: List<CafeBillCacheEntity>,
    localBills: List<LocalCafeBillEntity>,
    actions: List<LocalCafeActionEntity>,
): List<CafeBillProjection> {
    val localByServer = localBills.mapNotNull { bill ->
        bill.serverOrderId?.let { it to bill }
    }.toMap()
    val localByTable = localBills.associateBy { it.tableId }
    val actionsByBill = actions.groupBy { it.localBillId }
        .mapValues { (_, rows) -> rows.sortedWith(compareBy({ it.sequence }, { it.actionId })) }

    val tableIds = linkedSetOf<String>().apply {
        addAll(serverBills.map { it.tableId })
        addAll(localBills.map { it.tableId })
    }
    val serverByTable = serverBills.associateBy { it.tableId }

    return tableIds.mapNotNull { tableId ->
        val server = serverByTable[tableId]
        val local = server?.let { localByServer[it.orderId] } ?: localByTable[tableId]
        if (server == null && local == null) return@mapNotNull null
        val localActions = local?.let { actionsByBill[it.localBillId].orEmpty() }.orEmpty()
        val projected = buildList {
            server?.lines?.forEach { line ->
                add(
                    CafeBillLineProjection(
                        stableKey = line.clientLineId ?: line.id,
                        serverLineId = line.id,
                        clientLineId = line.clientLineId,
                        menuItemId = line.menuItemId,
                        name = line.name,
                        qty = line.qty,
                        unitPriceMinor = line.unitPriceMinor,
                        lineTotalMinor = line.lineTotalMinor,
                        note = line.note,
                        roundNo = line.kitchenRoundNo,
                        kitchenStatus = line.kitchenStatus,
                        locallyPending = false,
                        voided = false,
                        voidReason = null,
                        kitchenCancellationPending = false,
                    ),
                )
            }
            server?.voidedLines?.forEach { line ->
                add(
                    CafeBillLineProjection(
                        stableKey = line.clientLineId ?: line.id,
                        serverLineId = line.id,
                        clientLineId = line.clientLineId,
                        menuItemId = line.menuItemId,
                        name = line.name,
                        qty = line.qty,
                        unitPriceMinor = line.unitPriceMinor,
                        lineTotalMinor = line.lineTotalMinor,
                        note = line.note,
                        roundNo = line.kitchenRoundNo,
                        kitchenStatus = line.kitchenStatus,
                        locallyPending = false,
                        voided = true,
                        voidReason = line.voidReason,
                        kitchenCancellationPending = line.kitchenReleasedAt != null &&
                            line.kitchenVoidAcknowledgedAt == null,
                    ),
                )
            }
        }.toMutableList()

        var status = server?.status ?: local?.localStatus ?: "open"
        localActions.forEach { action ->
            when (action.kind) {
                CafeActionKind.CREATE_ROUND,
                CafeActionKind.APPEND_ROUND,
                -> action.payload.lines.forEach { line ->
                    // A response may already contain this client line after an
                    // ambiguous commit. Never show the optimistic copy twice.
                    if (projected.none { it.clientLineId == line.clientLineId }) {
                        projected += CafeBillLineProjection(
                            stableKey = line.clientLineId,
                            serverLineId = null,
                            clientLineId = line.clientLineId,
                            menuItemId = line.menuItemId,
                            name = line.name,
                            qty = line.qty.toDouble(),
                            unitPriceMinor = line.estimateUnitMinor,
                            lineTotalMinor = line.estimateUnitMinor * line.qty,
                            note = line.note,
                            roundNo = null,
                            kitchenStatus = "waiting_to_sync",
                            locallyPending = true,
                            voided = false,
                            voidReason = null,
                            kitchenCancellationPending = false,
                        )
                    }
                }

                CafeActionKind.LEGACY_CREATE_AND_SEND -> {
                    action.payload.legacyLines.forEachIndexed { index, line ->
                        val stable = "legacy:${action.actionId}:$index"
                        if (projected.none { it.stableKey == stable }) {
                            projected += CafeBillLineProjection(
                                stableKey = stable,
                                serverLineId = null,
                                clientLineId = null,
                                menuItemId = line.menuItemId,
                                name = "Saved menu item",
                                qty = line.qty.toDouble(),
                                unitPriceMinor = 0,
                                lineTotalMinor = 0,
                                note = null,
                                roundNo = null,
                                kitchenStatus = "waiting_to_sync",
                                locallyPending = true,
                                voided = false,
                                voidReason = null,
                                kitchenCancellationPending = false,
                            )
                        }
                    }
                    status = "sending_to_pos"
                }

                CafeActionKind.VOID_LINE -> {
                    val index = projected.indexOfFirst { line ->
                        action.payload.targetClientLineId?.let { it == line.clientLineId } == true ||
                            action.payload.targetServerLineId?.let { it == line.serverLineId } == true
                    }
                    if (index >= 0) {
                        projected[index] = projected[index].copy(
                            locallyPending = true,
                            voided = true,
                            voidReason = action.payload.reason,
                            kitchenCancellationPending = projected[index].roundNo != null,
                        )
                    }
                }

                CafeActionKind.VOID_ORDER -> {
                    // A definitive refusal must continue to show server truth,
                    // not pretend that every line was cancelled. Pending work
                    // is optimistic and remains visibly labelled as syncing.
                    if (action.state == CafeActionState.PENDING) {
                        status = "voiding"
                        action.payload.reason?.let { reason ->
                            projected.indices.forEach { index ->
                                if (!projected[index].voided) {
                                    projected[index] = projected[index].copy(
                                        locallyPending = true,
                                        voided = true,
                                        voidReason = reason,
                                        kitchenCancellationPending = projected[index].roundNo != null,
                                    )
                                }
                            }
                        }
                    }
                }

                CafeActionKind.SEND_TO_POS -> status = "sending_to_pos"
            }
        }
        val blocked = localActions.firstOrNull {
            it.state == CafeActionState.CONFLICT || it.state == CafeActionState.REJECTED
        }
        val confirmedLineTotal = server?.lines.orEmpty().sumOf { it.lineTotalMinor }
        val projectedLineTotal = projected.filterNot { it.voided }.sumOf { it.lineTotalMinor }
        val pendingAmountDelta = projectedLineTotal - confirmedLineTotal
        val amountPending = localActions.any {
            it.state == CafeActionState.PENDING && it.kind in setOf(
                CafeActionKind.CREATE_ROUND,
                CafeActionKind.APPEND_ROUND,
                CafeActionKind.VOID_LINE,
                CafeActionKind.VOID_ORDER,
                CafeActionKind.LEGACY_CREATE_AND_SEND,
            )
        }
        val wholeBillVoidPending = localActions.any {
            it.kind == CafeActionKind.VOID_ORDER && it.state == CafeActionState.PENDING
        }
        CafeBillProjection(
            localBillId = local?.localBillId,
            serverOrderId = server?.orderId ?: local?.serverOrderId,
            tableId = tableId,
            tableCode = local?.tableCode,
            status = status,
            checkoutVersion = server?.checkoutVersion ?: local?.confirmedCheckoutVersion,
            subtotalMinor = if (wholeBillVoidPending) 0 else {
                server?.subtotalMinor ?: projected.filterNot { it.voided }.sumOf { it.lineTotalMinor }
            },
            taxMinor = if (wholeBillVoidPending) 0 else server?.taxMinor ?: 0,
            confirmedTotalMinor = server?.totalMinor,
            totalMinor = if (wholeBillVoidPending) 0 else {
                ((server?.totalMinor ?: 0L) + pendingAmountDelta).coerceAtLeast(0L)
            },
            amountPending = amountPending,
            lines = projected,
            pendingActionCount = localActions.count { it.state == CafeActionState.PENDING },
            blockedActionId = blocked?.actionId,
            blockedMessage = blocked?.lastError,
        )
    }
}
