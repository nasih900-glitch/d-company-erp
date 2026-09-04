package cloud.dcompany.erp.ui.screens.shift

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.dcompany.erp.DCompanyApp
import cloud.dcompany.erp.core.auth.ShiftAccess
import cloud.dcompany.erp.core.auth.authorizeAction
import cloud.dcompany.erp.core.db.LocalShiftEntity
import cloud.dcompany.erp.core.db.RejectedOpenRecoveryResult
import cloud.dcompany.erp.core.db.RejectedOpenRecoveryStatus
import cloud.dcompany.erp.core.db.ResolvedOpenShift
import cloud.dcompany.erp.core.db.ShiftCloseCaptureResult
import cloud.dcompany.erp.core.db.ShiftCloseCaptureStatus
import cloud.dcompany.erp.core.db.ShiftHistoryMergePolicy
import cloud.dcompany.erp.core.db.ShiftHistoryRow
import cloud.dcompany.erp.core.db.ShiftState
import cloud.dcompany.erp.core.db.observeShiftForManagement
import cloud.dcompany.erp.core.net.MeResponse
import cloud.dcompany.erp.core.sync.RejectedShiftOpenVerificationStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

/** Indian cash denominations, largest first — the order staff count in. */
val DENOMINATIONS = listOf(500L, 200L, 100L, 50L, 20L, 10L, 5L, 2L, 1L)

data class ShiftUiState(
    val open: ResolvedOpenShift? = null,
    val history: List<ShiftHistoryRow> = emptyList(),
    val historyLastSyncMillis: Long? = null,
    val historyMessage: String? = null,
    val historyRefreshing: Boolean = false,
    val online: Boolean = false,
    val syncing: Boolean = false,
    /** True only for the brief moment between tapping Open/Close and the local write landing — guards against a double-tap creating two rows. */
    val busy: Boolean = false,
    val operationError: String? = null,
    val operationNotice: String? = null,
    val closedResult: LocalShiftEntity? = null,
    /**
     * Live, server-computed "cash that should be in the drawer right now" —
     * unlike everything else on this screen, this genuinely cannot be known
     * offline (it depends on every sale attributed to this shift, most of
     * which this one tablet may not have seen). Null means "not available",
     * not zero — closing still works without it; the real variance shows up
     * once the close syncs.
     */
    val expectedMinor: Long? = null,
    /** The verified one-shop workspace shown in conflict and handover guidance. */
    val workspaceLabel: String? = null,
    /** Used only to explain whether this employee or somebody else opened the shift. */
    val currentUserId: String? = null,
    val canClose: Boolean = false,
    /**
     * A refused open/close attempt, not yet dismissed. Previously invisible:
     * a rejected row was excluded from both `open` and `history`, so the
     * screen just reset to "no shift open" with zero explanation.
     */
    val rejectedShift: LocalShiftEntity? = null,
)

internal data class RejectedOpenRecoveryActions(
    val retryEnabled: Boolean,
    val verifyEnabled: Boolean,
    val guidance: String,
)

/** Pure action policy shared by Compose and focused JVM tests. */
internal fun rejectedOpenRecoveryActions(
    hasCurrentShift: Boolean,
    online: Boolean,
    canRecover: Boolean,
    busy: Boolean,
): RejectedOpenRecoveryActions {
    val guidance = if (hasCurrentShift) {
        "A current shift is already open, so Retry is unavailable. Verify can link this saved attempt only when its opener, opening float, and opening time match the current shift. If it is unrelated, Verify clears only an empty attempt with no captured work; otherwise it stays blocked and staff must resolve or close the current shift before retrying."
    } else {
        "Retry reuses the original saved identity. Verify performs a live server check and clears the attempt only when no captured work would be orphaned."
    }
    return RejectedOpenRecoveryActions(
        retryEnabled = canRecover && !busy && !hasCurrentShift,
        verifyEnabled = canRecover && !busy && online,
        guidance = guidance,
    )
}

/** Resource-specific presentation.  It intentionally has no global sync-error input. */
internal fun shiftHistoryStatusMessage(
    online: Boolean,
    refreshing: Boolean,
    lastSyncMillis: Long?,
    historyError: String?,
    hasRows: Boolean,
    nowMillis: Long = System.currentTimeMillis(),
): String? {
    val age = lastSyncMillis?.let { historyAgeLabel((nowMillis - it).coerceAtLeast(0L)) }
    return when {
        refreshing && lastSyncMillis == null -> "Downloading shift history…"
        refreshing -> "Refreshing shift history — saved history was updated $age."
        historyError != null && hasRows ->
            "Could not refresh shift history — showing saved history updated ${age ?: "earlier"}. $historyError"
        historyError != null -> "Could not download shift history. $historyError"
        !online && lastSyncMillis == null ->
            "Offline — shift history has never been downloaded for this account and device."
        !online -> "Offline — showing saved shift history updated ${age ?: "earlier"}."
        lastSyncMillis == null -> "Shift history has not been downloaded yet."
        else -> "Shift history updated $age."
    }
}

private fun historyAgeLabel(ageMillis: Long): String = when {
    ageMillis < 60_000L -> "just now"
    ageMillis < 3_600_000L -> "${ageMillis / 60_000L} min ago"
    ageMillis < 86_400_000L -> "${ageMillis / 3_600_000L} hr ago"
    else -> "${ageMillis / 86_400_000L} day${if (ageMillis / 86_400_000L == 1L) "" else "s"} ago"
}

/**
 * Room-backed, offline-first — the UI reads the local shift row, never the
 * network, for the part that has to keep working through a dropped link.
 * Opening or closing writes the row and returns immediately; SyncEngine sends
 * it whenever a connection exists, and the row picks up its real
 * `serverShiftId` and `varianceMinor` once that happens. This is the same
 * guarantee the till already has for orders.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ShiftViewModel : ViewModel() {

    private val app = DCompanyApp.instance
    private val db = app.db
    private val access = MutableStateFlow(ShiftAccess())
    private val confirmedTerminalId = app.terminalStore.activeValidatedTerminal
        .map { it?.terminalId }
        .distinctUntilChanged()
    private val resolvedShift = db.shiftDao().observeShiftForManagement(
        confirmedTerminalId,
    )
    private data class HistoryState(
        val rows: List<ShiftHistoryRow>,
        val latestLocalClosed: LocalShiftEntity?,
        val lastSyncMillis: Long?,
    )
    private data class SessionState(
        val online: Boolean,
        val syncing: Boolean,
        val profile: MeResponse?,
        val terminalName: String?,
        val historyRefreshing: Boolean,
        val historyError: String?,
    )

    private data class InteractionState(
        val busy: Boolean,
        val operationError: String?,
        val operationNotice: String?,
        val dismissedClosedId: String?,
        val access: ShiftAccess,
    )

    private val terminalHistory = confirmedTerminalId
        .flatMapLatest { terminalId ->
            if (terminalId == null) {
                flowOf(HistoryState(emptyList(), null, null))
            } else {
                combine(
                    db.shiftDao().observeServerHistoryForTerminal(terminalId),
                    db.shiftDao().observeLocalHistoryForTerminal(terminalId),
                    db.syncMetaDao().observe("shift_history"),
                ) { server, local, meta ->
                    HistoryState(
                        rows = ShiftHistoryMergePolicy.merge(server, local),
                        // v26 explicitly records a result that still needs an
                        // acknowledgement. Historical closed rows migrate as
                        // false and therefore never appear as a fresh success
                        // merely because the process was restarted.
                        latestLocalClosed = local.firstOrNull { it.closeResultPending },
                        lastSyncMillis = meta?.lastSyncMillis,
                    )
                }
            }
        }

    private val latestRejected = confirmedTerminalId
        .flatMapLatest { terminalId ->
            if (terminalId == null) flowOf(null)
            else db.shiftDao().observeLatestRejectedForTerminal(terminalId)
        }

    // These are observable because changing an ordinary var does not cause the
    // combined UI state to emit. Without that emission, tapping OK/Close on a
    // result dialog appears to do nothing until some unrelated database or
    // connectivity update happens.
    private val dismissedClosedId = MutableStateFlow<String?>(null)
    private val busy = MutableStateFlow(false)
    private val operationError = MutableStateFlow<String?>(null)
    private val operationNotice = MutableStateFlow<String?>(null)

    val state: StateFlow<ShiftUiState> = combine(
        resolvedShift,
        terminalHistory,
        combine(
            app.connectivity.online,
            app.sync.syncing,
            combine(
                app.shiftCache.profile,
                app.terminalStore.activeValidatedTerminal,
                ::Pair,
            ),
            combine(app.sync.shiftHistoryRefreshing, app.sync.shiftHistoryError, ::Pair),
        ) { online, syncing, scope, historySync ->
            SessionState(
                online,
                syncing,
                scope.first,
                scope.second?.terminalName,
                historySync.first,
                historySync.second,
            )
        },
        latestRejected,
        combine(
            busy,
            operationError,
            operationNotice,
            dismissedClosedId,
            access,
        ) { isBusy, error, notice, closedId, currentAccess ->
            InteractionState(isBusy, error, notice, closedId, currentAccess)
        },
    ) { current, historyState, session, latestRejected, interaction ->
        val online = session.online
        val syncing = session.syncing
        val profile = session.profile
        val justClosed = historyState.latestLocalClosed?.takeIf {
            shouldShowShiftResult(
                itemId = it.localId,
                resultPending = it.closeResultPending,
                dismissedId = interaction.dismissedClosedId,
            )
        }
        ShiftUiState(
            open = current,
            history = historyState.rows,
            historyLastSyncMillis = historyState.lastSyncMillis,
            historyMessage = shiftHistoryStatusMessage(
                online = online,
                refreshing = session.historyRefreshing,
                lastSyncMillis = historyState.lastSyncMillis,
                historyError = session.historyError,
                hasRows = historyState.rows.isNotEmpty(),
            ),
            historyRefreshing = session.historyRefreshing,
            online = online,
            syncing = syncing,
            busy = interaction.busy,
            operationError = interaction.operationError,
            operationNotice = interaction.operationNotice,
            // A remotely closed local shift may be replaced immediately by a
            // new server shift on the same terminal. Its durable result still
            // needs acknowledgement instead of being hidden by `current`.
            closedResult = justClosed,
            expectedMinor = current?.expectedMinor,
            workspaceLabel = shiftWorkspaceLabel(profile?.branchName, session.terminalName),
            currentUserId = profile?.userId,
            // Shift-close authority comes from the authenticated permission.
            // The opener remains attribution, not an extra ownership lock.
            canClose = current != null && interaction.access.canClose,
            rejectedShift = latestRejected,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ShiftUiState())

    init {
        app.sync.requestSync()
    }

    fun load() {
        app.sync.requestSync()
        viewModelScope.launch { app.sync.refresh("shifts") }
    }

    fun updateAccess(next: ShiftAccess) {
        access.value = next
    }

    private fun requireOpenPermission(): Boolean = authorizeAction(access.value.canOpen) {
        operationError.value =
            "This account cannot open shifts. Ask a staff member with Shift open access. Nothing was changed."
    }

    private fun requireClosePermission(): Boolean = authorizeAction(access.value.canClose) {
        operationError.value =
            "This account cannot close shifts. Ask a staff member with Shift close access. Nothing was changed."
    }

    fun openShift(floatMinor: Long) {
        if (!requireOpenPermission()) return
        if (busy.value) {
            operationNotice.value = "Shift opening is already being saved. Wait for the current result."
            return
        }
        state.value.open?.let { current ->
            operationError.value = shiftAlreadyOpenMessage(current, state.value.workspaceLabel)
            return
        }
        if (state.value.rejectedShift != null) {
            operationError.value =
                "Recover the saved rejected shift attempt before creating another shift identity."
            return
        }
        val profile = app.shiftCache.profile.value
        if (profile == null) {
            operationError.value = "The signed-in profile is unavailable. Reconnect before opening a shift."
            return
        }
        val assignedTerminalId = app.terminalStore.confirmedTerminalId()
        if (assignedTerminalId == null) {
            operationError.value =
                "This tablet's workspace is not verified. Reconnect and sign in before opening a shift."
            return
        }
        val assignedBranchId = profile.branchId
        if (assignedBranchId == null) {
            operationError.value =
                "This account is not assigned to a branch. A shift cannot be opened safely."
            return
        }
        val scopeLease = app.cacheIsolation.currentLease() ?: run {
            operationError.value =
                "The signed-in account is still being verified. Wait a moment, then open the shift again."
            return
        }
        busy.value = true
        viewModelScope.launch {
            try {
                val localId = UUID.randomUUID().toString()
                if (!app.cacheIsolation.commitIfCurrent(scopeLease) {
                        db.shiftDao().insert(
                            LocalShiftEntity(
                                localId = localId,
                                terminalId = assignedTerminalId,
                                branchId = assignedBranchId,
                                openingFloatMinor = floatMinor,
                                openedAtMillis = System.currentTimeMillis(),
                                openedByUserId = profile.userId,
                                openedByName = profile.name,
                                openedByEmail = profile.email,
                                state = ShiftState.OPEN_PENDING,
                            ),
                        )
                    }
                ) {
                    operationError.value =
                        "The signed-in account changed before the shift was saved. Nothing was opened; review Shift again."
                    return@launch
                }
                operationError.value = null
                operationNotice.value = if (state.value.online) {
                    "Shift opened on this tablet. Server confirmation is in progress; billing can begin."
                } else {
                    "Shift opened safely on this tablet. Billing can begin, and it will sync automatically when the connection returns."
                }
                app.sync.requestSync()
            } catch (_: Exception) {
                operationError.value =
                    "The shift could not be saved on this tablet. Try again; if it continues, ask an owner for help."
            } finally {
                busy.value = false
            }
        }
    }

    fun closeShift(countedMinor: Long) {
        if (!requireClosePermission()) return
        val shift = state.value.open ?: run {
            operationError.value = "There is no open shift to close. Refresh Shift before trying again."
            return
        }
        if (busy.value) {
            operationNotice.value = "A shift action is already being saved. Wait for the current result."
            return
        }
        val local = shift.local
        if (local != null && local.state !in setOf(ShiftState.OPEN_PENDING, ShiftState.OPEN_SYNCED)) {
            operationError.value = when (local.state) {
                ShiftState.CLOSE_PENDING ->
                    "This close is already saved and waiting for server confirmation. Do not submit it again."
                ShiftState.CLOSE_REJECTED ->
                    "The previous close was rejected. Use Retry saved close or Continue shift so the original drawer count is handled safely."
                else -> "This shift is no longer open. Refresh Shift before trying again."
            }
            return
        }
        val terminalId = app.terminalStore.confirmedTerminalId()
        if (terminalId == null) {
            operationError.value =
                "This tablet's workspace is not verified. Reconnect and sign in before closing the shift."
            return
        }
        val scopeLease = app.cacheIsolation.currentLease() ?: run {
            operationError.value =
                "The signed-in account is still being verified. Wait a moment, then count the drawer again."
            return
        }
        busy.value = true
        viewModelScope.launch {
            try {
                val closedAt = System.currentTimeMillis()
                var captureResult: ShiftCloseCaptureResult? = null
                if (!app.cacheIsolation.commitIfCurrent(scopeLease) {
                        if (local != null) {
                            captureResult = db.shiftCloseSafetyDao().captureExistingClose(
                                localId = local.localId,
                                terminalId = terminalId,
                                countedMinor = countedMinor,
                                closedAtMillis = closedAt,
                            )
                        } else {
                            // A shift opened on another account/device becomes a local
                            // close outbox only when an authorised person requests the
                            // close. The server cache itself remains read-only.
                            captureResult = db.shiftCloseSafetyDao().captureAdoptedClose(
                                LocalShiftEntity(
                                    localId = UUID.randomUUID().toString(),
                                    serverShiftId = shift.shiftId,
                                    terminalId = terminalId,
                                    branchId = shift.server?.branchId,
                                    openingFloatMinor = shift.openingFloatMinor,
                                    openedAtMillis = shift.openedAtMillis,
                                    openedByUserId = shift.openedByUserId,
                                    openedByName = shift.openedByName,
                                    openedByEmail = shift.openedByEmail,
                                    state = ShiftState.CLOSE_PENDING,
                                    countedMinor = countedMinor,
                                    closedAtMillis = closedAt,
                                    closeResultPending = true,
                                ),
                            )
                        }
                    }
                ) {
                    operationError.value =
                        "The signed-in account changed before the drawer count was saved. Nothing was closed; review Shift again."
                    return@launch
                }
                val result = captureResult ?: ShiftCloseCaptureResult(
                    ShiftCloseCaptureStatus.CHANGED,
                    "The account changed before the drawer count was saved. Sign in and review the shift again.",
                )
                if (result.status != ShiftCloseCaptureStatus.CAPTURED) {
                    operationError.value = result.message
                    return@launch
                }
                operationError.value = null
                app.sync.requestSync()
            } catch (_: Exception) {
                operationError.value =
                    "The drawer count could not be saved. The shift is still open; review the count and try again."
            } finally {
                busy.value = false
            }
        }
    }

    fun retryClose() {
        if (!requireClosePermission()) return
        val resolved = state.value.open ?: run {
            operationError.value = "There is no open shift with a rejected close to retry. Refresh Shift."
            return
        }
        val shift = resolved.local?.takeIf { it.state == ShiftState.CLOSE_REJECTED } ?: run {
            operationError.value = "This shift has no rejected close to retry. Review its current status first."
            return
        }
        if (busy.value) {
            operationNotice.value = "The saved close is already being processed. Wait for the current result."
            return
        }
        val terminalId = app.terminalStore.confirmedTerminalId()
        if (terminalId == null) {
            operationError.value =
                "This tablet's workspace is not verified. Reconnect and sign in before retrying the close."
            return
        }
        val scopeLease = app.cacheIsolation.currentLease() ?: run {
            operationError.value =
                "The signed-in account is still being verified. Wait a moment, then retry the saved close."
            return
        }
        busy.value = true
        viewModelScope.launch {
            try {
                var captureResult: ShiftCloseCaptureResult? = null
                if (!app.cacheIsolation.commitIfCurrent(scopeLease) {
                        captureResult = db.shiftCloseSafetyDao().retryRejectedClose(
                            localId = shift.localId,
                            terminalId = terminalId,
                        )
                    }
                ) {
                    operationError.value =
                        "The signed-in account changed before retry was saved. Nothing was resubmitted; review Shift again."
                    return@launch
                }
                val result = captureResult ?: ShiftCloseCaptureResult(
                    ShiftCloseCaptureStatus.CHANGED,
                    "The account changed before retry was saved. Sign in and review the shift again.",
                )
                if (result.status != ShiftCloseCaptureStatus.CAPTURED) {
                    operationError.value = result.message
                    return@launch
                }
                operationError.value = null
                app.sync.requestSync()
            } catch (_: Exception) {
                operationError.value =
                    "The close could not be queued again. The shift remains open; wait a moment and retry."
            } finally {
                busy.value = false
            }
        }
    }

    fun continueShift() {
        if (!requireClosePermission()) return
        val resolved = state.value.open ?: run {
            operationError.value = "There is no open shift to continue. Refresh Shift."
            return
        }
        val shift = resolved.local?.takeIf { it.state == ShiftState.CLOSE_REJECTED } ?: run {
            operationError.value = "This shift has no rejected close to remove. Review its current status first."
            return
        }
        if (busy.value) {
            operationNotice.value = "A shift action is already being saved. Wait for the current result."
            return
        }
        val scopeLease = app.cacheIsolation.currentLease() ?: run {
            operationError.value =
                "The signed-in account is still being verified. Wait a moment, then continue the shift again."
            return
        }
        busy.value = true
        viewModelScope.launch {
            try {
                if (!app.cacheIsolation.commitIfCurrent(scopeLease) {
                        db.shiftDao().cancelRejectedClose(shift.localId)
                    }
                ) {
                    operationError.value =
                        "The signed-in account changed before the rejected close was removed. Nothing was changed; review Shift again."
                    return@launch
                }
                operationError.value = null
                operationNotice.value =
                    "The rejected close request was removed. The shift remains open; resolve the stated blocker and count the drawer again when ready."
                app.sync.requestSync()
            } catch (_: Exception) {
                operationError.value =
                    "The shift could not be continued on this tablet. Refresh the shift before taking more payments."
            } finally {
                busy.value = false
            }
        }
    }

    fun dismissOperationError() {
        operationError.value = null
    }

    fun dismissOperationNotice() {
        operationNotice.value = null
    }

    fun retryRejectedOpen() {
        if (!requireOpenPermission()) return
        val shift = state.value.rejectedShift ?: run {
            operationError.value = "There is no rejected shift opening to retry. Refresh Shift."
            return
        }
        if (busy.value) {
            operationNotice.value = "A shift action is already being saved. Wait for the current result."
            return
        }
        state.value.open?.let { current ->
            operationError.value = shiftAlreadyOpenMessage(current, state.value.workspaceLabel)
            return
        }
        val terminalId = app.terminalStore.confirmedTerminalId()
        val branchId = app.shiftCache.profile.value?.branchId
        if (terminalId == null || branchId == null) {
            operationError.value =
                "Reconnect and sign in to the verified shop workspace before retrying this shift."
            return
        }
        val scopeLease = app.cacheIsolation.currentLease() ?: run {
            operationError.value = "The signed-in account is still being verified. Wait a moment and try again."
            return
        }
        busy.value = true
        viewModelScope.launch {
            try {
                var result: RejectedOpenRecoveryResult? = null
                if (!app.cacheIsolation.commitIfCurrent(scopeLease) {
                        result = db.shiftDao().retryRejectedOpen(
                            localId = shift.localId,
                            terminalId = terminalId,
                            branchId = branchId,
                        )
                    }
                ) {
                    operationError.value =
                        "The signed-in account changed before retry was saved. Nothing was resubmitted; review Shift again."
                    return@launch
                }
                val resolved = result ?: RejectedOpenRecoveryResult(
                    RejectedOpenRecoveryStatus.CHANGED,
                    "The account changed before retry was saved. Sign in and review the shift again.",
                )
                if (resolved.status == RejectedOpenRecoveryStatus.APPLIED) {
                    operationError.value = null
                    operationNotice.value =
                        "The original saved shift opening was queued again. Wait for server confirmation; no duplicate shift was created."
                    // pushShiftOpen reuses shift-open:<same localId>; no new
                    // shift row or idempotency identity is manufactured.
                    app.sync.requestSync()
                } else {
                    operationError.value = resolved.message
                }
            } catch (_: Exception) {
                operationError.value =
                    "The original shift attempt could not be queued again. It remains saved; try again."
            } finally {
                busy.value = false
            }
        }
    }

    fun verifyAndClearRejectedOpen() {
        if (!requireOpenPermission()) return
        val shift = state.value.rejectedShift ?: run {
            operationError.value = "There is no rejected shift opening to verify. Refresh Shift."
            return
        }
        if (busy.value) {
            operationNotice.value = "A shift action is already being saved. Wait for the current result."
            return
        }
        if (!state.value.online) {
            operationError.value =
                "A live connection is required to verify this saved shift attempt. Nothing was changed."
            return
        }
        busy.value = true
        viewModelScope.launch {
            try {
                val result = app.sync.verifyAndClearRejectedShiftOpen(shift.localId)
                if (result.succeeded) {
                    operationError.value = null
                    operationNotice.value = result.message
                    if (result.status == RejectedShiftOpenVerificationStatus.SERVER_SHIFT_RECONCILED) {
                        app.sync.requestSync()
                    }
                } else {
                    operationError.value = result.message
                }
            } catch (_: Exception) {
                operationError.value =
                    "The saved shift opening could not be verified. Nothing was deleted; check the connection and try again."
            } finally {
                busy.value = false
            }
        }
    }

    fun dismissResult() {
        val localId = state.value.closedResult?.localId ?: return
        dismissedClosedId.value = localId
        viewModelScope.launch {
            try {
                db.shiftDao().acknowledgeCloseResult(localId)
            } catch (_: Exception) {
                operationError.value =
                    "The acknowledgement could not be saved. The close result may appear again after restart."
            }
        }
    }

}

internal fun shiftWorkspaceLabel(branchName: String?, terminalName: String?): String? =
    listOfNotNull(
        branchName?.trim()?.takeIf(String::isNotEmpty),
        terminalName?.trim()?.takeIf(String::isNotEmpty),
    ).distinct().takeIf { it.isNotEmpty() }?.joinToString(" · ")

internal fun shiftAlreadyOpenMessage(
    shift: ResolvedOpenShift,
    workspaceLabel: String?,
): String {
    val opener = shift.openedByName?.trim()?.takeIf(String::isNotEmpty)
        ?: shift.openedByEmail?.trim()?.takeIf(String::isNotEmpty)
        ?: "another staff member"
    val workspace = workspaceLabel?.let { " in $it" }.orEmpty()
    return "A shift is already open$workspace. It was opened by $opener at " +
        "${formatShiftFeedbackDate(shift.openedAtMillis)}. Use that shift for billing, or count the drawer " +
        "and close it from Shift if the working day has ended. Do not open a duplicate."
}

private fun formatShiftFeedbackDate(epochMillis: Long): String =
    java.text.SimpleDateFormat("dd MMM, HH:mm", java.util.Locale.getDefault())
        .format(java.util.Date(epochMillis))

internal fun shouldShowShiftResult(
    itemId: String,
    resultPending: Boolean,
    dismissedId: String?,
): Boolean = resultPending && itemId != dismissedId

internal enum class ShiftCloseResultKind { LOCAL_CONFIRMED, REMOTE_RECONCILED }

internal fun shiftCloseResultKind(shift: LocalShiftEntity): ShiftCloseResultKind =
    if (shift.lastError.isNullOrBlank()) {
        ShiftCloseResultKind.LOCAL_CONFIRMED
    } else {
        ShiftCloseResultKind.REMOTE_RECONCILED
    }
