package cloud.dcompany.erp.ui.screens.refunds

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.dcompany.erp.DCompanyApp
import cloud.dcompany.erp.core.auth.EffectivePermissions
import cloud.dcompany.erp.core.auth.ErpPermission
import cloud.dcompany.erp.core.net.MeResponse
import cloud.dcompany.erp.core.db.LocalRefundEntity
import cloud.dcompany.erp.core.db.RefundOrderCacheEntity
import cloud.dcompany.erp.core.db.RefundState
import cloud.dcompany.erp.core.db.ResolvedOpenShift
import cloud.dcompany.erp.core.db.ShiftActor
import cloud.dcompany.erp.core.db.ShiftResolutionPolicy
import cloud.dcompany.erp.core.db.observeResolvedOpenShift
import cloud.dcompany.erp.core.net.Order
import cloud.dcompany.erp.core.net.asRupees
import cloud.dcompany.erp.core.sync.ResourceRefreshResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.Locale
import java.util.UUID

/** The reasons an owner actually gives; free text goes in the note. */
val REFUND_REASONS = listOf(
    "customer_unhappy" to "Customer unhappy",
    "wrong_item" to "Wrong item",
    "order_cancelled" to "Order cancelled",
    "billing_error" to "Billing error",
    "other" to "Other",
)

internal enum class RefundRailKind { UNKNOWN, CASH, SINGLE_PROVIDER, MIXED }

internal data class RefundRailPolicy(
    val kind: RefundRailKind,
    val methods: List<String>,
) {
    val requestReady: Boolean get() = kind != RefundRailKind.UNKNOWN
    val defaultMode: String get() =
        if (kind == RefundRailKind.SINGLE_PROVIDER) "original" else "cash"

    fun allows(mode: String): Boolean = when (kind) {
        RefundRailKind.UNKNOWN -> false
        RefundRailKind.CASH, RefundRailKind.MIXED -> mode == "cash"
        RefundRailKind.SINGLE_PROVIDER -> mode == "cash" || mode == "original"
    }
}

private val REFUND_PAYMENT_METHODS = setOf("cash", "card", "upi", "qr", "wallet")

/** Never guesses a payout rail from totals, labels or prior UI state. */
internal fun refundRailPolicy(rawMethods: List<String>): RefundRailPolicy {
    val methods = rawMethods.map { it.trim().lowercase(Locale.ROOT) }
        .filter(String::isNotEmpty)
        .distinct()
    if (methods.isEmpty() || methods.any { it !in REFUND_PAYMENT_METHODS }) {
        return RefundRailPolicy(RefundRailKind.UNKNOWN, methods)
    }
    return RefundRailPolicy(
        kind = when {
            methods.size > 1 -> RefundRailKind.MIXED
            methods.single() == "cash" -> RefundRailKind.CASH
            else -> RefundRailKind.SINGLE_PROVIDER
        },
        methods = methods,
    )
}

internal fun refundPaymentMethodLabel(method: String): String = when (method) {
    "cash" -> "Cash"
    "card" -> "Card"
    "upi" -> "UPI"
    "qr" -> "QR"
    "wallet" -> "Wallet"
    else -> "Unknown"
}

data class RefundTask(
    val localId: String,
    val orderId: String,
    val invoiceNo: String?,
    val amountMinor: Long,
    val reasonCode: String,
    val createdAtMillis: Long,
    val state: String,
    val mode: String?,
    val settlementMethod: String?,
    val serverRequestId: String?,
    val acceptedAtMillis: Long?,
    val handoffStartedAtMillis: Long?,
    val settledAtMillis: Long?,
    val localPayoutAtMillis: Long?,
    val withdrawalAtMillis: Long?,
    val receiptNo: String?,
    val externalReference: String?,
    val acceptedByUserId: String?,
    val acceptedByName: String?,
    val moneyStartedByUserId: String?,
    val moneyStartedByName: String?,
    val moneyCompletedByUserId: String?,
    val moneyCompletedByName: String?,
    val settledByUserId: String?,
    val settledByName: String?,
    val withdrawnByUserId: String?,
    val withdrawnByName: String?,
    val providerVerificationStatus: String?,
    val providerVerificationReference: String?,
    val customerSpendReconciled: Boolean?,
    val loyaltyReconciliationState: String?,
    val capturedTimeReconciled: Boolean?,
    val providerEvidenceReconciled: Boolean?,
    val payoutConflict: Boolean,
    val error: String?,
    /** Both captured identities are retained so recovery never guesses a shift. */
    val shiftId: String? = null,
    val serverShiftId: String? = null,
)

private data class RefundOperationalContext(
    val everSynced: Boolean,
    val online: Boolean,
    val shift: ResolvedOpenShift?,
    val actor: ShiftActor?,
    val protectedAccess: Boolean,
    val canReconcileRefunds: Boolean,
)

data class RefundsUiState(
    val orders: List<Order> = emptyList(),
    val query: String = "",
    val selected: Order? = null,
    val busy: Boolean = false,
    val refreshing: Boolean = false,
    val refreshError: String? = null,
    val notice: String? = null,
    val everSynced: Boolean = false,
    val online: Boolean = false,
    val tasks: List<RefundTask> = emptyList(),
    val recentTasks: List<RefundTask> = emptyList(),
    val canManageMoney: Boolean = false,
    val protectedAccess: Boolean = false,
    val canReconcileRefunds: Boolean = false,
    val currentShiftIds: Set<String> = emptySet(),
    val moneyAccessMessage: String? = null,
) {
    val visible: List<Order>
        get() {
            val q = query.trim().lowercase()
            return if (q.isEmpty()) orders
            else orders.filter { (it.invoiceNo ?: "").lowercase().contains(q) }
        }
}

internal fun refundRefreshError(result: ResourceRefreshResult, online: Boolean): String? = when (result) {
    is ResourceRefreshResult.Refreshed -> null
    is ResourceRefreshResult.Failed -> result.userMessage
    is ResourceRefreshResult.Skipped -> if (online) {
        "Refund records could not be verified for this account. Check access and refresh again."
    } else {
        "Reconnect and refresh to verify current refundable balances. Saved tasks remain on this tablet."
    }
}

internal data class RefundRefreshFailure(val message: String, val raisedAtMillis: Long)

internal fun canReconcilePosRefund(profile: MeResponse?): Boolean = profile?.let {
    EffectivePermissions.from(it).has(ErpPermission.PosRefundReconcile)
} == true

/**
 * Reconciliation records verified proof that a provider payout did not move
 * money. It may cross staff ownership, but never terminal/shift identity,
 * connectivity, state, or the dedicated server permission.
 */
internal fun canResolveFailedProviderPayout(
    task: RefundTask,
    online: Boolean,
    canReconcileRefunds: Boolean,
    currentShiftIds: Set<String>,
): Boolean = online &&
    canReconcileRefunds &&
    task.state == RefundState.PROVIDER_PAYOUT_IN_PROGRESS &&
    setOfNotNull(task.shiftId, task.serverShiftId).any(currentShiftIds::contains)

internal fun refundRefreshFailureAfterCache(
    failure: RefundRefreshFailure?,
    lastSuccessfulSyncMillis: Long?,
): String? = failure?.takeUnless {
    lastSuccessfulSyncMillis != null && lastSuccessfulSyncMillis >= it.raisedAtMillis
}?.message

/**
 * POS refunds are four distinct facts, never one optimistic button:
 *
 * 1. capture a shift-bound request and obtain server acceptance;
 * 2. obtain a live, server-persisted cash-handover window;
 * 3. physically pay once and durably queue settlement;
 * 4. receive authoritative settlement/receipt, or have a protected owner
 *    withdraw the accepted request only when no cash was handed over.
 */
class RefundsViewModel : ViewModel() {

    private val appCtx = DCompanyApp.instance
    private val db = appCtx.db

    private val query = MutableStateFlow("")
    private val selectedId = MutableStateFlow<String?>(null)
    private val busy = MutableStateFlow(false)
    private val notice = MutableStateFlow<String?>(null)
    private val refreshing = MutableStateFlow(false)
    private val localRefreshError = MutableStateFlow<RefundRefreshFailure?>(null)
    private val refreshError = combine(
        localRefreshError,
        appCtx.sync.resourceRefreshErrors.map { it["orders"] }.distinctUntilChanged(),
        db.syncMetaDao().observe("orders"),
    ) { local, resource, meta ->
        refundRefreshFailureAfterCache(local, meta?.lastSyncMillis) ?: resource
    }

    private data class InteractionState(
        val busy: Boolean,
        val notice: String?,
        val refreshing: Boolean,
        val refreshError: String?,
    )
    private val resolvedShift = db.shiftDao().observeResolvedOpenShift(
        appCtx.terminalStore.terminalIdFlow,
    )

    val state: StateFlow<RefundsUiState> = combine(
        db.refundDao().observeRefundableOrders(),
        combine(
            db.refundDao().observeUnresolvedRefunds(),
            db.refundDao().observeRecentCompletedRefunds(),
            ::Pair,
        ),
        combine(query, selectedId, ::Pair),
        combine(busy, notice, refreshing, refreshError, ::InteractionState),
        combine(
            db.syncMetaDao().observe("orders"),
            appCtx.connectivity.online,
            resolvedShift,
            appCtx.shiftCache.profile,
        ) { meta, online, shift, profile ->
            val actor = profile?.let { ShiftActor(it.userId, it.protectedAccess) }
            RefundOperationalContext(
                everSynced = meta != null,
                online = online,
                shift = shift,
                actor = actor,
                protectedAccess = profile?.protectedAccess == true,
                canReconcileRefunds = canReconcilePosRefund(profile),
            )
        },
    ) { cache, refundRows, qs, ui, context ->
        val (unresolved, recent) = refundRows
        val (q, selId) = qs
        val unresolvedOrderIds = unresolved.mapTo(mutableSetOf()) { it.orderId }
        val orders = cache
            .asSequence()
            .filter { it.refundableMinor > 0 && it.id !in unresolvedOrderIds }
            .map(RefundOrderCacheEntity::toOrder)
            .toList()
        val canManageMoney = context.shift?.canManageMoney(context.actor) == true
        val currentShiftIds = context.shift?.takeIf { shift ->
            shift.server?.serverShiftId != null || shift.local?.serverShiftId != null
        }?.let { shift ->
            setOfNotNull(
                shift.shiftId,
                shift.local?.localId,
                shift.local?.serverShiftId,
                shift.server?.serverShiftId,
            )
        }.orEmpty()
        RefundsUiState(
            orders = orders,
            query = q,
            selected = orders.firstOrNull { it.id == selId },
            busy = ui.busy,
            notice = ui.notice,
            refreshing = ui.refreshing,
            refreshError = ui.refreshError,
            everSynced = context.everSynced,
            online = context.online,
            tasks = unresolved.map(LocalRefundEntity::toTask),
            recentTasks = recent.map(LocalRefundEntity::toTask),
            canManageMoney = canManageMoney,
            protectedAccess = context.protectedAccess,
            canReconcileRefunds = context.canReconcileRefunds,
            currentShiftIds = currentShiftIds,
            moneyAccessMessage = when {
                context.shift == null ->
                    "Open this tablet's POS shift before requesting or paying a refund."
                !canManageMoney -> context.shift.moneyAccessMessage(context.actor)?.let { message ->
                    if (context.canReconcileRefunds) {
                        "$message Refund reconciliation access can still resolve a verified failed " +
                            "provider payout on this exact open shift."
                    } else message
                }
                else -> null
            },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RefundsUiState())

    init {
        refreshRecords(showConfirmation = false)
    }

    fun load() = refreshRecords(showConfirmation = true)

    private fun refreshRecords(showConfirmation: Boolean) {
        if (refreshing.value) return
        refreshing.value = true
        localRefreshError.value = null
        appCtx.sync.requestSync()
        viewModelScope.launch {
            try {
                val result = withTimeout(45_000L) { appCtx.sync.refresh("orders") }
                localRefreshError.value = refundRefreshError(result, appCtx.connectivity.online.value)?.let {
                    RefundRefreshFailure(it, System.currentTimeMillis())
                }
                if (showConfirmation && result is ResourceRefreshResult.Refreshed) {
                    notice.value = "Refund records refreshed. Review the current balance before requesting a payout."
                }
            } catch (_: TimeoutCancellationException) {
                localRefreshError.value = RefundRefreshFailure(
                    "Refreshing refund records took too long. Saved tasks remain safe; check the connection and try again.",
                    System.currentTimeMillis(),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                localRefreshError.value = RefundRefreshFailure(
                    "Refund records could not be refreshed. Saved tasks remain safe; check the connection and try again.",
                    System.currentTimeMillis(),
                )
            } finally {
                refreshing.value = false
            }
        }
    }

    fun search(q: String) { query.value = q }

    fun select(order: Order?) {
        if (order != null && !state.value.canManageMoney) {
            notice.value = state.value.moneyAccessMessage
                ?: "Only the shift opener or a protected owner may request a refund."
            return
        }
        selectedId.value = order?.id
    }

    fun dismissNotice() { notice.value = null }

    fun refund(
        order: Order,
        amountMinor: Long,
        reasonCode: String,
        mode: String,
        note: String?,
    ) {
        if (busy.value) return
        val validation = when {
            amountMinor <= 0 -> "Refund amount must be greater than ₹0."
            amountMinor > order.refundableMinor ->
                "The amount is above this tablet's latest refundable balance. Refresh and try again."
            REFUND_REASONS.none { it.first == reasonCode } -> "Choose a valid refund reason."
            !refundRailPolicy(order.paymentMethods).requestReady ->
                "The server did not provide this order's original payment rail. Refresh before requesting a refund."
            !refundRailPolicy(order.paymentMethods).allows(mode) ->
                "That payout rail does not match the order's authoritative payment history. Refresh and review it."
            else -> null
        }
        if (validation != null) {
            notice.value = validation
            return
        }
        val scopeLease = appCtx.cacheIsolation.currentLease() ?: return
        val terminalId = appCtx.terminalStore.terminalId()
        val profile = appCtx.shiftCache.profile.value

        busy.value = true
        viewModelScope.launch {
            try {
                val verifiedTerminalId = terminalId
                    ?: error("This tablet has no verified POS terminal. Reconnect first.")
                val verifiedProfile = profile
                    ?: error("The signed-in employee could not be verified. Reconnect first.")
                val branchId = verifiedProfile.branchId
                    ?: error("This employee has no branch assignment. Ask an owner to correct access.")
                val localId = UUID.randomUUID().toString()
                val capturedAt = System.currentTimeMillis()
                var captured = false
                if (!appCtx.cacheIsolation.commitIfCurrent(scopeLease) {
                        val openShift = ShiftResolutionPolicy.resolve(
                            db.shiftDao().currentForTerminal(verifiedTerminalId),
                            db.shiftDao().serverOpen(verifiedTerminalId),
                        ) ?: error("Open this tablet's POS shift before requesting a refund.")
                        val actor = ShiftActor(verifiedProfile.userId, verifiedProfile.protectedAccess)
                        require(openShift.canManageMoney(actor)) {
                            openShift.moneyAccessMessage(actor)
                                ?: "Only the shift opener or a protected owner may request this refund."
                        }
                        val shiftBranch = openShift.server?.branchId ?: openShift.local?.branchId
                        require(shiftBranch == branchId) {
                            "The open shift does not match this employee's branch. Refresh before refunding."
                        }
                        captured = db.refundDao().captureIfNoUnresolved(
                            LocalRefundEntity(
                                localId = localId,
                                clientActionId = localId,
                                orderId = order.id,
                                invoiceNo = order.invoiceNo,
                                shiftId = openShift.shiftId,
                                serverShiftId = openShift.server?.serverShiftId ?: openShift.local?.serverShiftId,
                                branchId = branchId,
                                terminalId = verifiedTerminalId,
                                capturedByUserId = verifiedProfile.userId,
                                reasonCode = reasonCode,
                                amountMinor = amountMinor,
                                expectedPaidMinor = order.paidMinor,
                                expectedRefundableMinor = order.refundableMinor,
                                mode = mode,
                                note = note?.trim()?.takeIf(String::isNotEmpty)?.take(500),
                                // Money must never move before the server accepts
                                // the request and opens a guarded handover/payout.
                                externalReference = null,
                                providerSettledAtMillis = null,
                                createdAtMillis = capturedAt,
                            ),
                        )
                    }
                ) return@launch
                if (!captured) {
                    notice.value =
                        "This order already has an unresolved refund. Finish that exact task first."
                    return@launch
                }
                selectedId.value = null
                if (!appCtx.connectivity.online.value) {
                    notice.value = "Refund request saved offline. No cash or provider payout is authorised " +
                        "until the server accepts it after reconnection."
                    appCtx.sync.requestSync()
                    return@launch
                }

                appCtx.sync.sync()
                val saved = db.refundDao().refundById(localId)
                notice.value = when (saved?.state) {
                    RefundState.ACCEPTED_CASH_DUE ->
                        "Server accepted ${saved.amountMinor.asRupees()}. Start the server-confirmed handover before touching cash."
                    RefundState.ACCEPTED_PROVIDER_DUE ->
                        "Server accepted ${saved.amountMinor.asRupees()}. Start the server-confirmed provider payout before opening the provider app."
                    RefundState.SETTLED ->
                        "Refund settled on ${saved.settlementMethod ?: "the original rail"}. " +
                            (saved.receiptNo?.let { "Receipt $it." } ?: "The server receipt is recorded.")
                    RefundState.REQUEST_REJECTED -> {
                        "Server refused the request: ${saved.lastError ?: "review required"}. No cash is authorised."
                    }
                    else ->
                        "Server confirmation is still pending. Do not repeat the refund or hand over cash; reconnect and refresh."
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                notice.value = "Refund request could not be completed: ${e.message ?: "unknown error"}"
            } finally {
                busy.value = false
            }
        }
    }

    fun retryRejected(localId: String) = guardedAction { scopeLease ->
        var queued = false
        if (!appCtx.cacheIsolation.commitIfCurrent(scopeLease) {
                val row = db.refundDao().refundById(localId)
                if (
                    row?.state == RefundState.REQUEST_REJECTED &&
                    row.mode == "original" &&
                    (!row.externalReference.isNullOrBlank() || row.providerSettledAtMillis != null)
                ) {
                    notice.value = "This older task captured provider evidence before server acceptance. " +
                        "Do not retry or repeat the payout; a protected owner must reconcile it."
                } else {
                    queued = db.refundDao().retryRejected(localId) == 1
                }
            }
        ) return@guardedAction
        if (queued) {
            notice.value =
                "The same refund action was queued again. Do not repeat any cash or provider payout while it syncs."
            appCtx.sync.requestSync()
        }
    }

    fun cancelRejected(localId: String) = guardedAction { scopeLease ->
        var mode: String? = null
        var cancelled = false
        if (!appCtx.cacheIsolation.commitIfCurrent(scopeLease) {
                val row = db.refundDao().refundById(localId)
                mode = row?.mode
                if (row?.externalReference.isNullOrBlank() && row?.providerSettledAtMillis == null) {
                    cancelled = db.refundDao().cancelRejectedRequest(localId) == 1
                }
            }
        ) return@guardedAction
        if (!cancelled && mode != null) {
            notice.value =
                "This refused request contains older payout evidence and cannot be discarded. Ask a protected owner to reconcile it."
        } else if (cancelled) {
            notice.value = "Refused ${if (mode == "original") "provider" else "cash"} request cancelled. " +
                "The server authorised no payout."
        }
    }

    fun beginCashHandoff(localId: String) {
        if (busy.value) return
        if (!appCtx.connectivity.online.value) {
            notice.value =
                "Starting a cash handover requires a live server confirmation. Reconnect; do not touch cash yet."
            return
        }
        val scopeLease = appCtx.cacheIsolation.currentLease() ?: return
        busy.value = true
        viewModelScope.launch {
            try {
                val result = appCtx.sync.beginPosRefundCashHandoff(localId)
                notice.value = if (result.status == RefundState.CASH_HANDOFF_IN_PROGRESS) {
                    "Server handover opened for ${result.amountMinor.asRupees()}. Verify the customer, hand over that amount once, then confirm immediately."
                } else {
                    "Refund is already ${result.status}. Refresh before touching cash."
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                appCtx.cacheIsolation.commitIfCurrent(scopeLease) {
                    db.refundDao().noteAmbiguousServerResult(
                        localId,
                        "Could not verify whether handover started: ${e.message ?: "connection lost"}",
                    )
                }
                notice.value =
                    "Could not verify the server handover. Do not give cash. Refresh first; a retry uses the same reference."
                appCtx.sync.requestSync()
            } finally {
                busy.value = false
            }
        }
    }

    fun beginProviderPayout(localId: String) {
        if (busy.value) return
        if (!appCtx.connectivity.online.value) {
            notice.value =
                "Starting a provider payout requires live server confirmation. Reconnect; do not open the provider app yet."
            return
        }
        val scopeLease = appCtx.cacheIsolation.currentLease() ?: return
        busy.value = true
        viewModelScope.launch {
            try {
                val result = appCtx.sync.beginPosRefundProviderPayout(localId)
                notice.value = if (result.status == RefundState.PROVIDER_PAYOUT_IN_PROGRESS) {
                    "Server opened the provider payout for ${result.amountMinor.asRupees()}. Complete it once, then record the successful reference."
                } else {
                    "Provider refund is already ${result.status}. Refresh before moving money."
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                appCtx.cacheIsolation.commitIfCurrent(scopeLease) {
                    db.refundDao().noteAmbiguousServerResult(
                        localId,
                        "Could not verify whether the provider payout started: ${e.message ?: "connection lost"}",
                    )
                }
                notice.value = "Could not verify the provider start. Do not run the payout. Refresh first; retries use the same reference."
                appCtx.sync.requestSync()
            } finally {
                busy.value = false
            }
        }
    }

    fun confirmProviderCompleted(localId: String, externalReference: String) = guardedAction { scopeLease ->
        val cleanReference = externalReference.trim()
        if (cleanReference.isEmpty()) {
            notice.value = "Enter the successful card/UPI/QR/wallet refund reference."
            return@guardedAction
        }
        val completedAt = System.currentTimeMillis()
        var changed = false
        if (!appCtx.cacheIsolation.commitIfCurrent(scopeLease) {
                changed = db.refundDao().confirmProviderCompleted(
                    localId = localId,
                    externalReference = cleanReference.take(200),
                    providerSettledAtMillis = completedAt,
                ) == 1
            }
        ) return@guardedAction
        if (!changed) {
            notice.value = "Provider completion was not queued because the server task changed. " +
                "Do not repeat the payout; refresh and verify the existing task."
            return@guardedAction
        }
        if (appCtx.connectivity.online.value) appCtx.sync.sync() else appCtx.sync.requestSync()
        val saved = db.refundDao().refundById(localId)
        notice.value = if (saved?.state == RefundState.SETTLED) {
            "Provider refund settled." + (saved.receiptNo?.let { " Receipt $it." } ?: "")
        } else {
            "Provider completion is saved. Do not run it again; keep this shift open until accounting finishes."
        }
    }

    fun confirmCashHandedOver(localId: String) = guardedAction { scopeLease ->
        val paidAt = System.currentTimeMillis()
        var changed = false
        if (!appCtx.cacheIsolation.commitIfCurrent(scopeLease) {
                changed = db.refundDao().confirmCashHandedOver(localId, paidAt) == 1
            }
        ) return@guardedAction
        if (!changed) {
            notice.value =
                "Cash settlement was not queued because the server handover state changed. " +
                    "Do not pay again; refresh and verify the task."
            return@guardedAction
        }
        if (appCtx.connectivity.online.value) appCtx.sync.sync() else appCtx.sync.requestSync()
        val saved = db.refundDao().refundById(localId)
        notice.value = if (saved?.state == RefundState.SETTLED) {
            "Cash refund settled." + (saved.receiptNo?.let { " Receipt $it." } ?: "")
        } else {
            "Cash handover is saved and waiting for server settlement. Do not pay this customer again after a restart."
        }
    }

    fun withdrawCashRefund(localId: String, reason: String) = guardedAction { scopeLease ->
        if (appCtx.shiftCache.profile.value?.protectedAccess != true) {
            notice.value = "Only a protected owner may withdraw an accepted cash refund."
            return@guardedAction
        }
        val cleanReason = reason.trim()
        if (cleanReason.length < 3) {
            notice.value = "Enter why no cash was handed over (at least 3 characters)."
            return@guardedAction
        }
        var changed = false
        if (!appCtx.cacheIsolation.commitIfCurrent(scopeLease) {
                changed = db.refundDao().requestWithdrawal(
                    localId,
                    cleanReason.take(500),
                    System.currentTimeMillis(),
                ) == 1
            }
        ) return@guardedAction
        if (!changed) {
            notice.value =
                "This refund is no longer eligible for withdrawal. Refresh; if cash was given, never withdraw it."
            return@guardedAction
        }
        if (appCtx.connectivity.online.value) appCtx.sync.sync() else appCtx.sync.requestSync()
        val saved = db.refundDao().refundById(localId)
        notice.value = if (saved?.state == RefundState.WITHDRAWN) {
            "Unpaid cash refund withdrawn. No drawer or customer balance was changed."
        } else {
            "Withdrawal saved. No cash may be handed over while server confirmation is pending."
        }
    }

    fun resolveStartedCashHandoff(localId: String, reason: String) {
        val cleanReason = reason.trim()
        runOnlineAuthorisedRecovery(
            invalidMessage = if (cleanReason.length < 3) {
                "Enter why the started cash handover did not pay the customer."
            } else null,
        ) {
            val result = appCtx.sync.resolvePosRefundCashHandoff(localId, cleanReason)
            if (result.status == RefundState.WITHDRAWN) {
                "Started cash handover resolved. The server recorded that no cash left the drawer."
            } else {
                "Cash handover is ${result.status}. Refresh and verify the drawer before any other action."
            }
        }
    }

    fun withdrawProviderRefund(localId: String, reason: String) {
        val cleanReason = reason.trim()
        runOnlineAuthorisedRecovery(
            invalidMessage = if (cleanReason.length < 3) {
                "Enter why the accepted provider payout will not be started."
            } else null,
        ) {
            val result = appCtx.sync.withdrawPosRefundProvider(localId, cleanReason)
            if (result.status == RefundState.WITHDRAWN) {
                "Provider refund withdrawn. The server recorded that no provider payout started."
            } else {
                "Provider refund is ${result.status}. Refresh before using the provider."
            }
        }
    }

    fun resolveStartedProviderPayout(
        localId: String,
        providerStatus: String,
        verificationReference: String,
        reason: String,
    ) {
        val cleanReference = verificationReference.trim()
        val cleanReason = reason.trim()
        val invalid = when {
            providerStatus !in setOf(
                "no_matching_transaction", "provider_declined", "provider_reversed",
            ) -> "Choose the verified provider outcome."
            cleanReference.length < 3 ->
                "Enter the provider search, case, reversal, or transaction reference."
            cleanReason.length < 3 -> "Enter why no provider payout completed."
            else -> null
        }
        runOnlineAuthorisedRecovery(invalidMessage = invalid, requiresFinancialReconciliation = true) {
            val result = appCtx.sync.resolvePosRefundProviderPayout(
                localId = localId,
                providerStatus = providerStatus,
                verificationReference = cleanReference,
                reason = cleanReason,
            )
            if (result.status == RefundState.WITHDRAWN) {
                "Provider payout resolved. The server recorded the verified no-payout outcome."
            } else {
                "Provider payout is ${result.status}. Refresh and verify the provider before any other action."
            }
        }
    }

    private fun runOnlineAuthorisedRecovery(
        invalidMessage: String?,
        requiresFinancialReconciliation: Boolean = false,
        action: suspend () -> String,
    ) {
        if (busy.value) return
        if (invalidMessage != null) {
            notice.value = invalidMessage
            return
        }
        val permitted = if (requiresFinancialReconciliation) {
            canReconcilePosRefund(appCtx.shiftCache.profile.value)
        } else appCtx.shiftCache.profile.value?.protectedAccess == true
        if (!permitted) {
            notice.value = if (requiresFinancialReconciliation) {
                "This account needs Refund reconciliation access to verify a failed provider payout."
            } else "Only a protected owner may resolve this payout state."
            return
        }
        if (!appCtx.connectivity.online.value) {
            notice.value = "Reconnect before recovery. The server must verify this exact payout state."
            return
        }
        busy.value = true
        viewModelScope.launch {
            try {
                notice.value = action()
                // Direct authorised recovery can end the refund without
                // entering the queued outbox. Let the sync engine refresh the
                // invalidated order/drawer/customer/finance projections.
                appCtx.sync.requestSync()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                notice.value = "Could not verify the recovery: ${e.message ?: "connection lost"}. " +
                    "Do not repeat or change the payout; refresh this exact task."
                appCtx.sync.requestSync()
            } finally {
                busy.value = false
            }
        }
    }

    private fun guardedAction(action: suspend (cloud.dcompany.erp.core.auth.CacheScopeLease) -> Unit) {
        if (busy.value) return
        val scopeLease = appCtx.cacheIsolation.currentLease() ?: return
        busy.value = true
        viewModelScope.launch {
            try {
                action(scopeLease)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                notice.value = "Refund task could not be updated: ${e.message ?: "local storage error"}."
            } finally {
                busy.value = false
            }
        }
    }
}

private fun RefundOrderCacheEntity.toOrder(): Order = Order(
    id = id,
    invoiceNo = invoiceNo,
    status = status,
    type = type,
    totalMinor = totalMinor,
    paidMinor = paidMinor,
    refundableMinor = refundableMinor,
    pendingRefundMinor = pendingRefundMinor,
    paymentMethods = paymentMethodsCsv.split(',').map(String::trim).filter(String::isNotEmpty),
)

private fun LocalRefundEntity.toTask() = RefundTask(
    localId = localId,
    orderId = orderId,
    invoiceNo = invoiceNo,
    amountMinor = amountMinor,
    reasonCode = reasonCode,
    createdAtMillis = createdAtMillis,
    state = state,
    mode = mode,
    settlementMethod = settlementMethod,
    serverRequestId = serverRequestId,
    acceptedAtMillis = acceptedAtMillis,
    handoffStartedAtMillis = cashHandoffStartedAtMillis,
    settledAtMillis = settledAtMillis,
    localPayoutAtMillis = if (settlementMethod == "cash") {
        settledAtMillis
    } else {
        providerSettledAtMillis
    },
    withdrawalAtMillis = withdrawalAtMillis,
    receiptNo = receiptNo,
    externalReference = externalReference,
    acceptedByUserId = acceptedByUserId,
    acceptedByName = acceptedByName,
    moneyStartedByUserId = if (settlementMethod == "cash") {
        cashHandoffStartedByUserId
    } else {
        providerPayoutStartedByUserId
    },
    moneyStartedByName = if (settlementMethod == "cash") {
        cashHandoffStartedByName
    } else {
        providerPayoutStartedByName
    },
    moneyCompletedByUserId = if (settlementMethod == "cash") {
        cashHandedOverByUserId
    } else {
        providerCompletedByUserId
    },
    moneyCompletedByName = if (settlementMethod == "cash") {
        cashHandedOverByName
    } else {
        providerCompletedByName
    },
    settledByUserId = settledByUserId,
    settledByName = settledByName,
    withdrawnByUserId = withdrawnByUserId,
    withdrawnByName = withdrawnByName,
    providerVerificationStatus = providerVerificationStatus,
    providerVerificationReference = providerVerificationReference,
    customerSpendReconciled = customerSpendReconciled,
    loyaltyReconciliationState = loyaltyReconciliationState,
    capturedTimeReconciled = capturedTimeReconciled,
    providerEvidenceReconciled = providerEvidenceReconciled,
    payoutConflict = payoutConflict,
    error = lastError,
    shiftId = shiftId,
    serverShiftId = serverShiftId,
)
