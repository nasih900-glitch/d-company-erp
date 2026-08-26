package cloud.dcompany.erp.core.sync

import cloud.dcompany.erp.core.db.CafeActionKind
import cloud.dcompany.erp.core.db.CafeActionState
import cloud.dcompany.erp.core.db.CafeBillCacheEntity
import cloud.dcompany.erp.core.db.CafeBillLineSnapshot
import cloud.dcompany.erp.core.db.CafeOrderDao
import cloud.dcompany.erp.core.db.ErpDatabase
import cloud.dcompany.erp.core.db.LocalCafeActionEntity
import cloud.dcompany.erp.core.db.LocalCafeBillEntity
import cloud.dcompany.erp.core.net.ApiException
import cloud.dcompany.erp.core.net.outboxProvenanceHeaders
import cloud.dcompany.erp.ui.screens.tables.OrderLineBody
import cloud.dcompany.erp.ui.screens.tables.OrderLinesAppendBody
import cloud.dcompany.erp.ui.screens.tables.SendToPosBody
import cloud.dcompany.erp.ui.screens.tables.TableOrder
import cloud.dcompany.erp.ui.screens.tables.TableOrderCreateBody
import cloud.dcompany.erp.ui.screens.tables.TableOrderLine
import cloud.dcompany.erp.ui.screens.tables.TablesApi
import cloud.dcompany.erp.ui.screens.tables.VoidOrderLineBody
import cloud.dcompany.erp.ui.screens.tables.VoidOrderBody
import kotlinx.coroutines.CancellationException
import java.nio.charset.StandardCharsets
import java.util.UUID

data class CafeOrderPushResult(
    val stoppedOnAmbiguousFailure: Boolean = false,
    val lastError: String? = null,
    val changedHeldQueue: Boolean = false,
    /** A confirmed terminal void removed a bill and may have released its table. */
    val changedActiveTableBills: Boolean = false,
)

private sealed interface CafeActionConfirmation {
    data class BillSnapshot(val order: TableOrder) : CafeActionConfirmation
    data class VoidedOrder(val serverOrderId: String) : CafeActionConfirmation
}

/**
 * Durable Tables mutation driver kept separate from the already-large global
 * SyncEngine. It processes exactly one ordered action at a time per bill,
 * persists each authoritative response before advancing, and never guesses a
 * checkout version.
 */
class CafeOrderSyncCoordinator(
    private val db: ErpDatabase,
    private val dao: CafeOrderDao,
    private val api: TablesApi,
) {
    suspend fun fetchActiveBills(): List<CafeBillCacheEntity> =
        api.activeOrders().map(TableOrder::toCafeBillCache)

    suspend fun push(): CafeOrderPushResult {
        var lastError: String? = null
        var changedHeld = false
        var changedActiveTableBills = false
        for (localBillId in dao.billIdsWithActions()) {
            while (true) {
                val action = dao.firstAction(localBillId) ?: break
                // A blocked predecessor deliberately holds every later action.
                if (action.state != CafeActionState.PENDING) break
                val bill = dao.localBill(localBillId)
                if (bill == null) {
                    dao.setActionFailure(
                        action.actionId,
                        CafeActionState.REJECTED,
                        "This saved table action lost its local bill identity. Ask support to " +
                            "repair the tablet; do not recreate the order blindly.",
                    )
                    break
                }
                if (action.kind == CafeActionKind.CREATE_ROUND ||
                    action.kind == CafeActionKind.LEGACY_CREATE_AND_SEND
                ) {
                    val localShift = db.shiftDao().byLocalId(bill.shiftId)
                    if (localShift != null && localShift.serverShiftId == null) break
                }
                try {
                    when (val confirmation = pushOne(bill, action)) {
                        is CafeActionConfirmation.BillSnapshot -> {
                            changedHeld = changedHeld || confirmation.order.status == "held"
                            dao.confirmAction(
                                localBillId = bill.localBillId,
                                actionId = action.actionId,
                                server = confirmation.order.toCafeBillCache(),
                            )
                        }

                        is CafeActionConfirmation.VoidedOrder -> {
                            dao.confirmVoidOrderAction(
                                localBillId = bill.localBillId,
                                actionId = action.actionId,
                                serverOrderId = confirmation.serverOrderId,
                            )
                            changedActiveTableBills = true
                        }
                    }
                    if (action.kind == CafeActionKind.LEGACY_CREATE_AND_SEND) {
                        dao.deleteLegacyOrder(action.actionId)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    val message = plainCafeSyncFailure(failure)
                    lastError = message
                    if (failure is ApiException && failure.status == 426) throw failure
                    if (failure is CafeActionPreparationException) {
                        // This failed before the mutation request was sent, so
                        // the outcome is definitive and needs a visible human
                        // recovery action instead of an endless auto-retry.
                        dao.setActionFailure(
                            action.actionId,
                            CafeActionState.REJECTED,
                            message,
                        )
                        break
                    }
                    if (failure is ApiException && !failure.mustPreserveOutbox) {
                        dao.setActionFailure(
                            action.actionId,
                            if (failure.isCheckoutConflict()) {
                                CafeActionState.CONFLICT
                            } else {
                                CafeActionState.REJECTED
                            },
                            message,
                        )
                        break
                    }

                    // Transport failures and decode/Room failures can happen
                    // after the server committed. Keep the exact action and
                    // idempotency identity replayable; never invite a duplicate.
                    dao.notePendingFailure(action.actionId, message)
                    return CafeOrderPushResult(
                        stoppedOnAmbiguousFailure = true,
                        lastError = message,
                        changedHeldQueue = changedHeld,
                        changedActiveTableBills = changedActiveTableBills,
                    )
                }
            }
        }
        return CafeOrderPushResult(
            lastError = lastError,
            changedHeldQueue = changedHeld,
            changedActiveTableBills = changedActiveTableBills,
        )
    }

    private suspend fun pushOne(
        bill: LocalCafeBillEntity,
        action: LocalCafeActionEntity,
    ): CafeActionConfirmation = when (action.kind) {
        CafeActionKind.CREATE_ROUND -> CafeActionConfirmation.BillSnapshot(
            run {
            requireCafePreparation(
                action.payload.lines.isNotEmpty(),
                "Saved first round has no lines. Discard it after checking the table, then take the round again.",
            )
            val resolvedShiftId = db.shiftDao().byLocalId(bill.shiftId)?.serverShiftId ?: bill.shiftId
            api.createOrder(
                TableOrderCreateBody(
                    type = "dine_in",
                    shiftId = resolvedShiftId,
                    tableId = bill.tableId,
                    lines = action.payload.lines.map { line ->
                        OrderLineBody(
                            clientLineId = line.clientLineId,
                            menuItemId = line.menuItemId,
                            qty = line.qty,
                            note = line.note,
                        )
                    },
                ),
                key = "cafe-action:${action.actionId}",
                provenance = action.provenance("cafe-create"),
            )
            },
        )

        CafeActionKind.APPEND_ROUND -> CafeActionConfirmation.BillSnapshot(
            run {
            val orderId = requireServerOrderId(bill)
            val version = requireExpectedVersion(bill, action)
            requireCafePreparation(
                action.payload.lines.isNotEmpty(),
                "Saved service round has no lines. Discard it after checking the confirmed bill.",
            )
            api.appendRound(
                id = orderId,
                body = OrderLinesAppendBody(
                    expectedCheckoutVersion = version,
                    lines = action.payload.lines.map { line ->
                        OrderLineBody(
                            clientLineId = line.clientLineId,
                            menuItemId = line.menuItemId,
                            qty = line.qty,
                            note = line.note,
                        )
                    },
                ),
                key = "cafe-action:${action.actionId}",
                provenance = action.provenance("cafe-append"),
            )
            },
        )

        CafeActionKind.VOID_LINE -> CafeActionConfirmation.BillSnapshot(
            run {
            val orderId = requireServerOrderId(bill)
            val version = requireExpectedVersion(bill, action)
            val cache = dao.billCacheByOrderId(orderId) ?: api.order(orderId)
                .toCafeBillCache()
                .also { dao.upsertBillCache(it) }
            val lineId = action.payload.targetServerLineId
                ?: cache.lines.firstOrNull {
                    it.clientLineId == action.payload.targetClientLineId
                }?.id
                ?: throw CafeActionPreparationException(
                    "The item to cancel is not in the latest confirmed bill. Refresh Tables " +
                        "before retrying.",
                )
            val reason = action.payload.reason?.trim().orEmpty()
            requireCafePreparation(
                reason.isNotEmpty(),
                "This saved cancellation has no reason. Discard it after checking the bill, then cancel again with a reason.",
            )
            api.voidLine(
                orderId = orderId,
                lineId = lineId,
                body = VoidOrderLineBody(
                    expectedCheckoutVersion = version,
                    reason = reason,
                ),
                key = "cafe-action:${action.actionId}",
                provenance = action.provenance("cafe-void"),
            )
            },
        )

        CafeActionKind.VOID_ORDER -> {
            val orderId = requireServerOrderId(bill)
            val reason = action.payload.reason?.trim().orEmpty()
            requireCafePreparation(
                reason.isNotEmpty(),
                "This saved whole-bill void has no reason. Discard it after checking the bill, then void again with a reason.",
            )
            requireCafePreparation(
                reason.length <= 500,
                "This saved whole-bill void reason is longer than 500 characters. Discard it after checking the bill, then enter a shorter reason.",
            )
            api.voidOrder(
                id = orderId,
                body = VoidOrderBody(reason),
                key = "cafe-action:${action.actionId}",
                provenance = action.provenance("cafe-order-void"),
            )
            CafeActionConfirmation.VoidedOrder(orderId)
        }

        CafeActionKind.SEND_TO_POS -> CafeActionConfirmation.BillSnapshot(
            run {
            val orderId = requireServerOrderId(bill)
            api.sendToPos(
                id = orderId,
                body = SendToPosBody(requireExpectedVersion(bill, action)),
                key = "cafe-action:${action.actionId}",
                provenance = action.provenance("cafe-send"),
            )
            },
        )

        CafeActionKind.LEGACY_CREATE_AND_SEND -> CafeActionConfirmation.BillSnapshot(
            pushLegacyCreateAndSend(bill, action),
        )

        else -> throw CafeActionPreparationException(
            "This saved table action was created by an unsupported app version. Update the app or ask support to recover it.",
        )
    }

    private suspend fun pushLegacyCreateAndSend(
        bill: LocalCafeBillEntity,
        action: LocalCafeActionEntity,
    ): TableOrder {
        var server = bill.serverOrderId?.let { api.order(it) }
        if (server == null) {
            val resolvedShiftId = db.shiftDao().byLocalId(bill.shiftId)?.serverShiftId ?: bill.shiftId
            requireCafePreparation(
                action.payload.legacyLines.isNotEmpty(),
                "Legacy saved table order has no recoverable lines. Check the server bill, then discard this unusable local action.",
            )
            server = api.createOrder(
                TableOrderCreateBody(
                    type = "dine_in",
                    shiftId = resolvedShiftId,
                    tableId = bill.tableId,
                    lines = action.payload.legacyLines.mapIndexed { index, line ->
                        OrderLineBody(
                            clientLineId = deterministicLegacyLineId(action.actionId, index),
                            menuItemId = line.menuItemId,
                            qty = line.qty,
                        )
                    },
                ),
                key = "table-order:${action.actionId}",
                provenance = action.provenance("legacy-cafe-create"),
            )
        }
        if (server.status == "held") return server
        return api.sendToPos(
            id = server.id,
            body = SendToPosBody(server.checkoutVersion),
            key = "table-send:${action.actionId}",
            provenance = action.provenance("legacy-cafe-send"),
        )
    }

    private fun requireServerOrderId(bill: LocalCafeBillEntity): String =
        bill.serverOrderId ?: throw CafeActionPreparationException(
            "A later table action has no confirmed server bill. Keep the earlier round saved and ask support to repair this action.",
        )

    private fun requireExpectedVersion(
        bill: LocalCafeBillEntity,
        action: LocalCafeActionEntity,
    ): Long = action.capturedCheckoutVersion ?: bill.confirmedCheckoutVersion
        ?: throw CafeActionPreparationException(
            "The saved table action has no confirmed checkout version. Refresh Tables before retrying.",
        )
}

internal class CafeActionPreparationException(message: String) : IllegalStateException(message)

private fun requireCafePreparation(condition: Boolean, message: String) {
    if (!condition) throw CafeActionPreparationException(message)
}

private fun LocalCafeActionEntity.provenance(kind: String): Map<String, String> =
    outboxProvenanceHeaders(createdAtMillis, "$kind:$actionId")

private fun deterministicLegacyLineId(actionId: String, index: Int): String =
    UUID.nameUUIDFromBytes(
        "dcompany:cafe-legacy:$actionId:$index".toByteArray(StandardCharsets.UTF_8),
    ).toString()

private fun ApiException.isCheckoutConflict(): Boolean {
    val text = message.orEmpty().lowercase()
    return status == 409 && (
        "version" in text || "another device" in text || "reload" in text ||
            "already has an unfinished order" in text
        )
}

internal fun plainCafeSyncFailure(failure: Exception): String {
    val detail = failure.message.orEmpty().trim().replace(Regex("\\s+"), " ")
    return when {
        failure is CafeActionPreparationException ->
            detail.ifBlank { "This saved table action is incomplete and needs review." }
        failure is ApiException && failure.isCheckoutConflict() ->
            "This table bill changed on another device. Refresh it, review the latest items, " +
                "then retry or discard this saved action."
        failure is ApiException && failure.mustPreserveOutbox ->
            "The server did not confirm this saved table action. It will retry automatically " +
                "with the same identity; do not repeat the order."
        failure !is ApiException ->
            "The tablet could not verify this saved table action (app error: " +
                "${detail.ifBlank { "unknown" }}). It remains queued; do not repeat it."
        detail.isBlank() -> "The server refused this table action without an explanation."
        else -> detail
    }
}

internal fun TableOrder.toCafeBillCache(): CafeBillCacheEntity {
    val safeTableId = requireNotNull(tableId) {
        "The active Tables endpoint returned a non-table order ($id)."
    }
    return CafeBillCacheEntity(
        orderId = id,
        tableId = safeTableId,
        status = status,
        type = type,
        sourceLabel = sourceLabel,
        subtotalMinor = subtotalMinor,
        taxMinor = taxMinor,
        totalMinor = totalMinor,
        openedAt = openedAt,
        heldAt = heldAt,
        checkoutVersion = checkoutVersion,
        lines = lines.map(TableOrderLine::toCafeLineSnapshot),
        voidedLines = voidedLines.map(TableOrderLine::toCafeLineSnapshot),
    )
}

private fun TableOrderLine.toCafeLineSnapshot(): CafeBillLineSnapshot = CafeBillLineSnapshot(
    id = id,
    clientLineId = clientLineId,
    menuItemId = menuItemId,
    name = name,
    qty = qty,
    unitPriceMinor = unitPriceMinor,
    lineTotalMinor = lineTotalMinor,
    note = note,
    kitchenStatus = kitchenStatus,
    kitchenReleasedAt = kitchenReleasedAt,
    kitchenRoundNo = kitchenRoundNo,
    voidedAt = voidedAt,
    voidReason = voidReason,
    kitchenVoidAcknowledgedAt = kitchenVoidAcknowledgedAt,
)
