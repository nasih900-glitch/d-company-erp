package cloud.dcompany.erp.ui.screens.gaming

import android.util.Log
import androidx.room.withTransaction
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.dcompany.erp.DCompanyApp
import cloud.dcompany.erp.core.auth.GAMING_SHIFT_ACCESS_REQUIRED_MESSAGE
import cloud.dcompany.erp.core.auth.GamingAccess
import cloud.dcompany.erp.core.auth.CacheScopeLease
import cloud.dcompany.erp.core.auth.EffectivePermissions
import cloud.dcompany.erp.core.auth.ErpPermission
import cloud.dcompany.erp.core.auth.TerminalPurpose
import cloud.dcompany.erp.core.auth.ValidatedTerminalDisplay
import cloud.dcompany.erp.core.auth.VIEW_ONLY_MESSAGE
import cloud.dcompany.erp.core.auth.authorizeAction
import cloud.dcompany.erp.core.alarm.GamingAlarmReconciler
import cloud.dcompany.erp.core.db.GamingSessionCacheEntity
import cloud.dcompany.erp.core.db.GamingSessionAddonActionState
import cloud.dcompany.erp.core.db.GamingSessionAddonActionType
import cloud.dcompany.erp.core.db.GamingSessionAddonCacheEntity
import cloud.dcompany.erp.core.db.GamingPackageCacheEntity
import cloud.dcompany.erp.core.db.GamingPackageExtensionState
import cloud.dcompany.erp.core.db.GamingLegacyResolution
import cloud.dcompany.erp.core.db.GamingLegacyResolutionAttemptState
import cloud.dcompany.erp.core.db.GamingSessionState
import cloud.dcompany.erp.core.db.GamingStationEntity
import cloud.dcompany.erp.core.db.LocalGamingSessionEntity
import cloud.dcompany.erp.core.db.LocalGamingPackageExtensionEntity
import cloud.dcompany.erp.core.db.LocalGamingSessionAddonActionEntity
import cloud.dcompany.erp.core.db.MenuCategoryEntity
import cloud.dcompany.erp.core.db.MenuItemEntity
import cloud.dcompany.erp.core.errors.RecoveryRisk
import cloud.dcompany.erp.core.errors.recoveryGuidance
import cloud.dcompany.erp.core.db.MenuModifierEntity
import cloud.dcompany.erp.core.db.MenuModifierGroupEntity
import cloud.dcompany.erp.core.db.MenuVariantEntity
import cloud.dcompany.erp.core.db.LocalModifierSelectionSnapshot
import cloud.dcompany.erp.core.db.encodeModifierSelections
import cloud.dcompany.erp.core.db.RecoveredLegacyServerDisposition
import cloud.dcompany.erp.core.db.observeResolvedOpenShift
import cloud.dcompany.erp.core.net.ApiClient
import cloud.dcompany.erp.core.net.ApiException
import cloud.dcompany.erp.core.net.ErpApi
import cloud.dcompany.erp.core.net.LoginRequest
import cloud.dcompany.erp.core.net.MeResponse
import cloud.dcompany.erp.core.net.asRupees
import cloud.dcompany.erp.core.net.outboxProvenanceHeaders
import cloud.dcompany.erp.core.sync.ResourceRefreshResult
import cloud.dcompany.erp.ui.screens.CartModifierSelection
import cloud.dcompany.erp.ui.screens.configuredUnitPriceMinor
import cloud.dcompany.erp.ui.WorkspaceFeatureProfiles
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.json.JsonNull

data class GamingUiState(
    val stations: List<Station> = emptyList(),
    val packages: List<GamingPackage> = emptyList(),
    val sessions: List<GameSession> = emptyList(),
    val busyStationId: String? = null,
    val error: String? = null,
    val notice: String? = null,
    /** A gaming pull has completed at least once on this device. */
    val everSynced: Boolean = false,
    /** The station/session pull currently owns the screen's refresh affordance. */
    val refreshing: Boolean = true,
    /** Recoverable read failure, kept separate from station-action errors. */
    val refreshError: String? = null,
    /** Same Room-derived id PosViewModel uses — null means no shift open. */
    val activeShiftId: String? = null,
    /** Gaming starts are backdated, so a locally queued shift is not sufficient authority yet. */
    val activeShiftServerConfirmed: Boolean = false,
    /** Effective ERP connectivity: validated network plus a reachable backend. */
    val online: Boolean = false,
    /** Durable paid extensions which still require confirmation or staff review. */
    val packageExtensionActions: List<PackageExtensionActionUi> = emptyList(),
    val addonCatalog: List<MenuItemEntity> = emptyList(),
    val addonVariants: List<MenuVariantEntity> = emptyList(),
    val addonModifierGroups: List<MenuModifierGroupEntity> = emptyList(),
    val addonModifiers: List<MenuModifierEntity> = emptyList(),
    /** Server receipts merged with durable local Add/Void overlays. */
    val sessionAddons: List<GamingSessionAddonUi> = emptyList(),
    val sessionAddonActions: List<SessionAddonActionUi> = emptyList(),
) {
    val initialLoading: Boolean
        get() = !everSynced && stations.isEmpty() && refreshing

    val initialLoadFailed: Boolean
        get() = !everSynced && stations.isEmpty() && !refreshing && !refreshError.isNullOrBlank()

    fun activeFor(stationId: String): GameSession? =
        sessions.firstOrNull {
            it.stationId == stationId && (
                it.status in setOf("starting", "start_failed", "active", "paused", "stopping") ||
                    it.isUnbilledEnded()
                )
        }

    val readyForPos: List<GameSession>
        get() = sessions.filter { session ->
            session.canSendToPos(hasActiveAddons(session))
        }

    val needsCancellation: List<GameSession>
        get() = sessions.filter {
            it.canCancelUnbilled() && it.amountMinor == 0L && !hasActiveAddons(it)
        }

    fun packageExtensionFor(serverSessionId: String): PackageExtensionActionUi? =
        packageExtensionActions.firstOrNull { it.serverSessionId == serverSessionId }

    fun addonsFor(session: GameSession): List<GamingSessionAddonUi> = sessionAddons.filter {
        it.serverSessionId == session.id || it.localSessionId == session.id
    }

    fun hasActiveAddons(session: GameSession): Boolean = addonsFor(session).any {
        !it.voided && !it.isRejectedLocalAdd()
    }

    /**
     * Best locally available combined-bill snapshot before POS creates the
     * authoritative rounded order. Keeping this calculation next to the
     * add-on merge policy prevents payment surfaces from silently displaying
     * only the play charge while consumed items remain payable.
     */
    fun pendingBillSnapshotMinor(session: GameSession): Long =
        Math.addExact(
            session.amountMinor ?: 0L,
            gamingSessionAddonBillableTotalMinor(addonsFor(session)),
        )

    fun unresolvedAddonsFor(session: GameSession): List<SessionAddonActionUi> =
        sessionAddonActions.filter {
            it.serverSessionId == session.id || it.localSessionId == session.id
        }
}

data class GamingSessionAddonUi(
    val id: String,
    val serverAddonId: String? = null,
    val serverSessionId: String? = null,
    val localSessionId: String? = null,
    val clientLineId: String,
    val menuItemId: String,
    val menuItemName: String,
    val menuItemType: String,
    val variantId: String? = null,
    val modifierSelectionsJson: String = "[]",
    val qty: Int,
    val unitPriceMinor: Long,
    val lineTotalMinor: Long,
    val note: String? = null,
    val voided: Boolean = false,
    val voidReason: String? = null,
    val localState: String? = null,
    val localActionType: String? = null,
    val actionId: String? = null,
    val lastError: String? = null,
)

/**
 * A definitive Add rejection means the server did not accept this local item.
 * Keep the row for staff review, but never present it as part of the bill. A
 * rejected Void is deliberately different: its server item remains billable.
 */
internal fun GamingSessionAddonUi.isRejectedLocalAdd(): Boolean =
    serverAddonId == null &&
        localActionType == GamingSessionAddonActionType.ADD &&
        localState == GamingSessionAddonActionState.REJECTED

internal fun gamingSessionAddonBillableTotalMinor(addons: List<GamingSessionAddonUi>): Long =
    addons.asSequence()
        .filterNot(GamingSessionAddonUi::voided)
        .filterNot(GamingSessionAddonUi::isRejectedLocalAdd)
        .sumOf(GamingSessionAddonUi::lineTotalMinor)

data class SessionAddonActionUi(
    val actionId: String,
    val actionType: String,
    val serverSessionId: String?,
    val localSessionId: String?,
    val clientLineId: String,
    val state: String,
    val lastError: String?,
)

internal fun mergeGamingSessionAddons(
    cache: List<GamingSessionAddonCacheEntity>,
    actions: List<LocalGamingSessionAddonActionEntity>,
): List<GamingSessionAddonUi> {
    val unresolved = actions.filter {
        it.state !in setOf(
            GamingSessionAddonActionState.CONFIRMED,
            GamingSessionAddonActionState.DISCARDED,
        )
    }
    val cachedKeys = cache.map { it.gamingSessionId to it.clientLineId }.toSet()
    val serverRows = cache.map { row ->
        val action = unresolved
            .filter { it.serverSessionId == row.gamingSessionId && it.clientLineId == row.clientLineId }
            .maxByOrNull { if (it.actionType == GamingSessionAddonActionType.VOID) 1 else 0 }
        GamingSessionAddonUi(
            id = row.id,
            serverAddonId = row.id,
            serverSessionId = row.gamingSessionId,
            clientLineId = row.clientLineId,
            menuItemId = row.menuItemId,
            menuItemName = row.menuItemName,
            menuItemType = row.menuItemType,
            variantId = row.variantId,
            modifierSelectionsJson = row.modifiersJson,
            qty = row.qty,
            unitPriceMinor = row.unitPriceMinor,
            lineTotalMinor = row.lineTotalMinor,
            note = row.note,
            voided = row.voidedAtMillis != null,
            voidReason = row.voidReason,
            localState = action?.state,
            localActionType = action?.actionType,
            actionId = action?.actionId,
            lastError = action?.lastError,
        )
    }
    val localRows = unresolved
        .filter { it.actionType == GamingSessionAddonActionType.ADD }
        .filter { action ->
            action.serverSessionId?.let { it to action.clientLineId } !in cachedKeys
        }
        .map { add ->
            val void = unresolved.firstOrNull {
                it.actionType == GamingSessionAddonActionType.VOID &&
                    it.clientLineId == add.clientLineId &&
                    it.localSessionId == add.localSessionId &&
                    it.serverSessionId == add.serverSessionId
            }
            GamingSessionAddonUi(
                id = add.actionId,
                serverAddonId = add.serverAddonId,
                serverSessionId = add.serverSessionId,
                localSessionId = add.localSessionId,
                clientLineId = add.clientLineId,
                menuItemId = add.menuItemId,
                menuItemName = add.menuItemName,
                menuItemType = add.menuItemType,
                variantId = add.variantId,
                modifierSelectionsJson = add.modifierSelectionsJson,
                qty = add.qty,
                unitPriceMinor = add.expectedUnitPriceMinor,
                lineTotalMinor = add.expectedUnitPriceMinor * add.qty,
                note = add.note,
                voided = false,
                localState = void?.state ?: add.state,
                localActionType = void?.actionType ?: add.actionType,
                actionId = void?.actionId ?: add.actionId,
                lastError = void?.lastError ?: add.lastError,
            )
        }
    return (serverRows + localRows).sortedWith(
        compareBy<GamingSessionAddonUi> { it.serverSessionId ?: it.localSessionId.orEmpty() }
            .thenBy { it.menuItemName }
            .thenBy { it.clientLineId },
    )
}

data class PackageExtensionActionUi(
    val actionId: String,
    val serverSessionId: String,
    val shiftId: String? = null,
    val state: String,
    val lastError: String?,
)

internal fun GamingUiState.orphanedPackageExtensionActions(): List<PackageExtensionActionUi> {
    val renderedServerSessionIds = sessions.map(GameSession::id).toSet()
    return packageExtensionActions.filter { it.serverSessionId !in renderedServerSessionIds }
}

internal fun canResolveRejectedExtensionForShift(
    actionShiftId: String?,
    canWrite: Boolean,
): Boolean = canWrite && !actionShiftId.isNullOrBlank()

/**
 * A delegated legacy-recovery approval is valid only inside the already
 * active staff workspace. Global protected owners may have no branch on
 * `/auth/me`; a non-null approver branch must match exactly. The backend still
 * performs the final protected-owner and terminal-scoped authorization.
 */
internal fun legacyRecoveryApproverError(
    currentStaff: MeResponse,
    approver: MeResponse,
    terminal: ValidatedTerminalDisplay,
): String? {
    val originalBranchId = currentStaff.branchId?.trim()?.takeIf(String::isNotEmpty)
        ?: return "The current workspace has no verified branch. Keep the evidence and contact support."
    if (terminal.branchId != originalBranchId) {
        return "The current terminal no longer matches this workspace branch. No approval was recorded."
    }
    if (approver.companyId != currentStaff.companyId) {
        return "That protected account belongs to a different company. No approval was recorded."
    }
    val approverBranchId = approver.branchId?.trim()?.takeIf(String::isNotEmpty)
    if (approverBranchId != null && approverBranchId != originalBranchId) {
        return "That protected account belongs to a different branch. No approval was recorded."
    }
    val permissions = EffectivePermissions.from(approver)
    if (
        !approver.protectedAccess || !approver.auditAccess ||
        !permissions.has(ErpPermission.AdminAuditRead)
    ) {
        return "That account is not a protected owner with Audit Log access. No approval was recorded."
    }
    return null
}

internal fun legacyResolutionApproverMatches(
    capturedApproverUserId: String?,
    presentedApproverUserId: String,
): Boolean = !capturedApproverUserId.isNullOrBlank() &&
    capturedApproverUserId == presentedApproverUserId

internal fun legacyResolutionFailureState(failure: ApiException): String =
    if (
        failure.isBusinessRule ||
        failure.code in setOf(
            "gaming_legacy_server_session_not_found",
            "gaming_legacy_stop_owner_review_required",
        )
    ) {
        // BusinessRuleError is transaction-rolled-back and therefore proves
        // that this audit endpoint neither retained an idempotency reservation
        // nor wrote a receipt. The two dedicated recovery codes carry that
        // same route-level guarantee. The owner may correct the decision body.
        GamingLegacyResolutionAttemptState.REJECTED
    } else {
        // Transport/5xx, idempotency conflicts, auth changes, generic 4xx and
        // server-session conflicts do not prove the original owner's request
        // absent. Freeze the exact body and original approver.
        GamingLegacyResolutionAttemptState.AMBIGUOUS
    }

/**
 * A v27 start may retain the local shift UUID even though its network request
 * used the mapped server UUID. Never place a known-local UUID on the recovery
 * wire unless its open leg has a confirmed server mapping.
 */
internal fun resolvedLegacyCapturedServerShiftId(
    capturedShiftId: String?,
    localMappingFound: Boolean,
    mappedServerShiftId: String?,
): String? {
    val captured = capturedShiftId?.trim()?.takeIf(String::isNotEmpty) ?: return null
    val candidate = if (localMappingFound) {
        mappedServerShiftId?.trim()?.takeIf(String::isNotEmpty) ?: return null
    } else {
        captured
    }
    return runCatching { UUID.fromString(candidate).toString() }.getOrNull()
}

/**
 * Validate the protected-owner receipt before it is allowed to release the
 * local outbox blocker. The receipt's station is the immutable original
 * station; the nested session station may differ after a legitimate transfer.
 */
internal fun legacyResolutionReceiptError(
    body: LegacyGamingOutboxResolutionBody,
    receipt: LegacyGamingOutboxResolutionReceipt,
    expectedBranchId: String,
    expectedTerminalId: String,
): String? {
    if (
        (body.packageId == null && body.expectedRatePerHourMinor == null) ||
        (body.packageId != null && body.expectedRatePerHourMinor != null)
    ) {
        return "The retained billing discriminator is incomplete or contradictory."
    }
    if (
        receipt.receiptId <= 0L || receipt.localActionId != body.localActionId ||
        receipt.stationId != body.stationId || receipt.branchId != expectedBranchId ||
        receipt.terminalId != expectedTerminalId || receipt.packageId != body.packageId
    ) {
        return "The audit receipt does not match the retained action scope."
    }
    val server = receipt.serverSession
    if (receipt.resolution != GamingLegacyResolution.SERVER_SESSION_RECOVERED) {
        if (
            receipt.resolution != body.resolution ||
            receipt.referenceOrderId != body.referenceOrderId || server != null
        ) {
            return "The audit receipt does not match the retained owner decision."
        }
        return null
    }
    if (server == null) return "The recovered audit receipt omitted the authoritative session."
    if (body.shiftId.isNullOrBlank() || server.shiftId != body.shiftId) {
        return "The recovered session does not match the retained server shift."
    }
    if (server.id.isBlank() || server.stationId.isBlank()) {
        return "The recovered session identity is incomplete."
    }
    if (server.status !in setOf("active", "paused", "ended", "cancelled")) {
        return "The recovered session has an unsupported server state."
    }
    if (
        body.resolution == GamingLegacyResolution.CONFIRMED_NO_PLAY &&
        server.status in setOf("active", "paused")
    ) {
        // The backend must atomically cancel an eligible running session for
        // a no-play attestation, or reject the request. Accepting a still-
        // running row here could turn the owner's no-play decision into a
        // queued financial Stop after a malformed/stale response.
        return "The recovered session is still running and does not match the confirmed no-play decision."
    }
    if (body.packageId == null) {
        if (server.billingMode != "hourly" || server.packageId != null) {
            return "The recovered server row does not prove hourly billing."
        }
    } else {
        if (server.billingMode != "package") {
            return "The recovered server row does not prove package billing."
        }
        if (server.packageId != null && server.packageId != body.packageId) {
            return "The recovered server row belongs to a different package."
        }
    }
    if (!server.hasSafeRecoveredLegacyBillingEvidence()) {
        return "The recovered billing amount, rate, or immutable snapshots are incomplete."
    }
    if (body.referenceOrderId != null) {
        if (
            receipt.referenceOrderId != body.referenceOrderId ||
            server.orderId != body.referenceOrderId
        ) {
            return "The recovered session was not linked to the exact verified POS order."
        }
    } else if (receipt.referenceOrderId != null) {
        return "The recovered receipt unexpectedly linked a POS order."
    }
    return null
}

internal fun GameSession.hasSafeRecoveredLegacyBillingEvidence(): Boolean {
    if (billingMode == "hourly") {
        return packageId == null && ratePerHourMinor != null && ratePerHourMinor >= 0L &&
            packagePriceMinorSnapshot == null && packageDurationMinutesSnapshot == null &&
            packageVariantSnapshot == null && packageStationTypeSnapshot == null
    }
    if (billingMode != "package" || amountMinor == null || amountMinor < 0L) return false
    val allSnapshotsMissing = packagePriceMinorSnapshot == null &&
        packageDurationMinutesSnapshot == null && packageVariantSnapshot == null &&
        packageStationTypeSnapshot == null
    val allSnapshotsComplete = packagePriceMinorSnapshot != null && packagePriceMinorSnapshot >= 0L &&
        packageDurationMinutesSnapshot != null && packageDurationMinutesSnapshot > 0 &&
        !packageVariantSnapshot.isNullOrBlank() && !packageStationTypeSnapshot.isNullOrBlank()
    return allSnapshotsMissing || allSnapshotsComplete
}

internal fun recoveredLegacyServerDisposition(
    server: GameSession,
    originalCapturedStopAtMillis: Long?,
    retainedRatePerHourMinor: Long?,
    verifiedReferenceOrderId: String?,
): RecoveredLegacyServerDisposition {
    if (server.status == "cancelled") return RecoveredLegacyServerDisposition.RESOLVE_LOCAL
    if (
        server.billingMode == "hourly" &&
        retainedRatePerHourMinor != server.ratePerHourMinor
    ) return RecoveredLegacyServerDisposition.RETAIN_BILLING_REVIEW
    if (server.status !in setOf("active", "paused")) {
        return RecoveredLegacyServerDisposition.RESOLVE_LOCAL
    }
    val capturedStop = originalCapturedStopAtMillis
        ?: return RecoveredLegacyServerDisposition.RESOLVE_LOCAL
    val authoritativeStart = runCatching { Instant.parse(server.startAt).toEpochMilli() }
        .getOrNull() ?: return RecoveredLegacyServerDisposition.RETAIN_BILLING_REVIEW
    return when {
        server.billingMode == "package" ->
            RecoveredLegacyServerDisposition.RESTORE_CAPTURED_STOP
        server.billingMode == "hourly" && capturedStop >= authoritativeStart ->
            RecoveredLegacyServerDisposition.RESTORE_CAPTURED_STOP
        server.billingMode == "hourly" && verifiedReferenceOrderId != null &&
            server.orderId == verifiedReferenceOrderId ->
            RecoveredLegacyServerDisposition.RESTORE_CAPTURED_STOP
        else -> RecoveredLegacyServerDisposition.RETAIN_BILLING_REVIEW
    }
}

private const val LEGACY_RECOVERED_BILLING_REVIEW_ERROR =
    "The server Start was recovered, but the retained offline Stop cannot be replayed without changing the bill. " +
        "Ordinary Stop and Send to POS remain locked. Keep this owner audit receipt and contact support for audited billing resolution."

private data class LegacyRecoveryAuthority(
    val actorUserId: String,
    val api: GamingApi,
)

internal fun GameSession.requiresProtectedStartReview(): Boolean =
    localState == GamingSessionState.START_REJECTED && status == "start_failed"

internal fun GameSession.canResolveRejectedStart(): Boolean =
    localState == GamingSessionState.START_REJECTED && status == "start_failed"

internal fun GameSession.hasCapturedLegacyPlayEvidence(): Boolean =
    requiresProtectedStartReview() && endAt != null

internal fun legacyResolutionInputError(
    resolution: String,
    referenceOrderId: String?,
    reason: String,
): String? {
    val normalizedReason = reason.trim()
    if (normalizedReason.length !in 3..500) {
        return "Enter a resolution reason between 3 and 500 characters."
    }
    return when (resolution) {
        GamingLegacyResolution.MANUAL_BILL_RECORDED -> {
            val normalizedReference = referenceOrderId?.trim().orEmpty()
            if (runCatching { UUID.fromString(normalizedReference) }.isFailure) {
                "Enter the valid POS order ID that records the manual bill."
            } else {
                null
            }
        }
        GamingLegacyResolution.CONFIRMED_NO_PLAY -> if (referenceOrderId != null) {
            "Confirmed no play cannot be linked to a POS order."
        } else {
            null
        }
        GamingLegacyResolution.SERVER_SESSION_RECOVERED -> if (referenceOrderId != null) {
            "Recover accepted server start cannot be linked to a POS order."
        } else {
            null
        }
        else -> "Choose whether to recover an accepted Start, link a manual bill, or confirm no play."
    }
}

internal fun legacyResolutionRejectedMessage(failure: ApiException): String = when (failure.code) {
    "gaming_legacy_server_session_not_found" ->
        "The server found no exact accepted Start for this retained action. No audit receipt was created. Review the evidence, then choose a verified manual bill or confirm no play."
    "gaming_legacy_stop_owner_review_required" ->
        "The server found the accepted hourly Start, but its captured Stop predates the authoritative Start time. No audit receipt was created. Link the exact paid POS order or confirm no play."
    else ->
        "The server rejected this resolution: ${failure.message ?: "review the order reference and reason."} Review and submit a corrected decision."
}

internal fun legacyResolutionIdempotencyKey(localActionId: String): String =
    "gaming-legacy-outbox-resolution:$localActionId"

/** Lock transfer intent to the station shown on the employee's exact session snapshot. */
internal fun GameSession.transferBody(targetStationId: String): SessionTransferBody =
    SessionTransferBody(
        targetStationId = targetStationId,
        expectedSourceStationId = stationId,
    )

/** Only the server's ledger-checked outcome proves the immutable request did not charge. */
internal fun ApiException.provesPackageExtensionUncharged(): Boolean =
    !mustPreserveOutbox && code == "gaming_extension_not_applied"

internal fun packageExtensionFailureMessageForRecovery(failure: ApiException): String =
    failure.message ?: "The paid extension response could not be confirmed."

internal fun GameSession.canRequestStop(): Boolean =
    (
        status in setOf("active", "paused") ||
            (status == "starting" && localState == GamingSessionState.START_PENDING)
        ) && localState != GamingSessionState.START_REJECTED

internal fun gamingStartShiftBlockMessage(
    activeShiftId: String?,
    activeShiftServerConfirmed: Boolean,
): String? = when {
    activeShiftId == null ->
        "No shift is open. Open or refresh Shift before starting a session."
    !activeShiftServerConfirmed ->
        "Shift is saved offline. Reconnect and let it confirm before starting Gaming."
    else -> null
}

internal enum class GamingPosRoute { LOCAL, CROSS_TERMINAL, BLOCKED }

/** Purpose is authoritative; neither a terminal count nor an editable name may route money. */
internal fun gamingPosRoute(terminalPurpose: String?): GamingPosRoute = when (terminalPurpose) {
    TerminalPurpose.CAFE_POS,
    TerminalPurpose.HYBRID,
    -> GamingPosRoute.LOCAL
    TerminalPurpose.GAMING -> GamingPosRoute.CROSS_TERMINAL
    else -> GamingPosRoute.BLOCKED
}

internal fun gamingStartTerminalBlockMessage(terminalPurpose: String?): String? = when (terminalPurpose) {
    TerminalPurpose.CAFE_POS ->
        "This tablet is set to POS only. Use Change terminal and select Gaming before starting a session."
    TerminalPurpose.GAMING,
    TerminalPurpose.HYBRID,
    -> null
    else ->
        "This tablet's terminal purpose is not recognised. Reconnect and ask an owner to verify Terminal settings."
}

data class PosTargetSelectionUi(
    val session: GameSession,
    val serverSessionId: String,
    val sourceTerminalId: String,
    val targets: List<PosTargetShift>,
)

internal fun posTargetListError(
    targets: List<PosTargetShift>,
    sourceTerminalId: String,
): String? = when {
    targets.isEmpty() ->
        "No eligible receiving POS shift is open. Ask its cashier to open a shift, then retry. " +
            "This ended bill remains saved in Gaming."
    targets.any {
        it.shiftId.isBlank() || it.terminalId.isBlank() || it.terminalId == sourceTerminalId ||
            it.terminalName.isBlank() || it.openedByName.isBlank()
    } ->
        "The receiving till list could not be verified. This ended bill remains saved in Gaming; refresh and try again."
    targets.distinctBy(PosTargetShift::shiftId).size != targets.size ->
        "The receiving till list contained duplicate shifts. This ended bill remains saved in Gaming; refresh and try again."
    else -> null
}

internal fun canConfirmPosTargetSelection(
    selectedShiftId: String?,
    targets: List<PosTargetShift>,
): Boolean = !selectedShiftId.isNullOrBlank() && targets.any { it.shiftId == selectedShiftId }

internal fun posHandoffResponseError(
    session: GameSession,
    sourceTerminalId: String,
    target: PosTargetShift,
    result: SessionPosHandoffResult,
): String? = when {
    result.orderId.isBlank() || result.amountMinor < 0L ->
        "The server did not return a complete held-order receipt."
    session.shiftId.isNullOrBlank() || result.sourceShiftId != session.shiftId ->
        "The server receipt did not preserve the gaming session's source shift."
    result.sourceTerminalId != sourceTerminalId ->
        "The server receipt did not match this Gaming Area terminal."
    result.targetShiftId != target.shiftId || result.targetTerminalId != target.terminalId ->
        "The server receipt did not match the selected POS shift."
    else -> null
}

internal fun posTargetLoadFailureMessage(failure: ApiException): String = when {
    failure.status == null ->
        "Reconnect to load open POS shifts. This ended bill remains saved in Gaming."
    failure.status == 401 || failure.status == 403 ->
        "This account cannot choose a POS shift. Ask an owner to verify Gaming and POS access. " +
            "This ended bill remains saved in Gaming."
    else ->
        "Could not load POS shifts: ${failure.message ?: "refresh Gaming and try again."} " +
            "This ended bill remains saved in Gaming."
}

internal fun posHandoffFailureMessage(
    failure: ApiException,
    terminalName: String,
): String = if (failure.isAmbiguous) {
    "The response from $terminalName was lost. This bill remains visible in Gaming. " +
        "Retry the same shift; the server will return the linked order without creating a duplicate."
} else {
    "Could not send to $terminalName: ${failure.message ?: "the selected shift is no longer eligible."} " +
        "This ended bill remains saved in Gaming; reopen Send to POS to refresh the shift list."
}

internal fun calculateCapturedTimerEndsAtMillis(startedAtMillis: Long, timerMinutes: Int?): Long? {
    if (timerMinutes == null) return null
    return runCatching {
        Math.addExact(startedAtMillis, Math.multiplyExact(timerMinutes.toLong(), 60_000L))
    }.getOrNull()
}

internal fun GameSession.canSendToPos(hasActiveAddons: Boolean = false): Boolean =
    isUnbilledEnded() && amountMinor != null && (amountMinor > 0L || hasActiveAddons) &&
        localState !in setOf(GamingSessionState.SEND_PENDING, GamingSessionState.SENT)

internal fun GameSession.canCancelUnbilled(): Boolean =
    isUnbilledEnded() && amountMinor != null &&
        localState !in setOf(GamingSessionState.SEND_PENDING, GamingSessionState.SENT)

/**
 * Backend cancellation deliberately refuses a session while any add-on still
 * contributes to its bill. Explain that constraint before staff enter a
 * whole-session void reason; the item ledger must be reason-voided first.
 */
internal fun sessionCancellationAddonBlockMessage(
    addons: List<GamingSessionAddonUi>,
): String? {
    val active = addons.filterNot { it.voided }
    if (active.isEmpty()) return null
    return when {
        active.any {
            it.localActionType == GamingSessionAddonActionType.VOID &&
                it.localState == GamingSessionAddonActionState.REJECTED
        } -> "An item void was refused. Tap Review on that item, then void it again before voiding the whole session."
        active.any {
            it.localActionType == GamingSessionAddonActionType.VOID &&
                it.localState in setOf(
                    GamingSessionAddonActionState.PENDING,
                    GamingSessionAddonActionState.AMBIGUOUS,
                )
        } -> "An item void is awaiting server confirmation. Keep this session here until Sync finishes."
        active.any {
            it.localActionType == GamingSessionAddonActionType.ADD &&
                it.localState == GamingSessionAddonActionState.REJECTED
        } -> "A saved item Add was refused. Tap Review on that item before voiding the whole session."
        active.any {
            it.localActionType == GamingSessionAddonActionType.ADD &&
                it.localState in setOf(
                    GamingSessionAddonActionState.PENDING,
                    GamingSessionAddonActionState.AMBIGUOUS,
                )
        } -> "Saved items are awaiting server confirmation. After Sync, void each item with a reason before voiding the whole session."
        else -> "Void ${active.size} active Gaming item${if (active.size == 1) "" else "s"} above with a reason before voiding the whole session."
    }
}

/** Fail before durable capture if the customisation dialog became stale or was bypassed. */
internal fun gamingAddonSelectionError(
    item: MenuItemEntity,
    selectedVariant: MenuVariantEntity?,
    selections: List<CartModifierSelection>,
    currentVariants: List<MenuVariantEntity>,
    currentGroups: List<MenuModifierGroupEntity>,
    currentModifiers: List<MenuModifierEntity>,
): String? {
    val variantById = currentVariants.associateBy(MenuVariantEntity::id)
    if (selectedVariant != null) {
        val current = variantById[selectedVariant.id]
        if (current == null || current != selectedVariant || !current.isActive || current.menuItemId != item.id) {
            return "That item variant changed or is no longer available. Choose the item again."
        }
    }
    if (selections.map { it.modifier.id }.distinct().size != selections.size) {
        return "An item option was selected more than once. Review the customisation."
    }
    val groups = currentGroups.filter { it.menuItemId == item.id && it.isActive }
    if (groups.any { it.minSelect < 0 || it.maxSelect < it.minSelect }) {
        return "This item's option rules are invalid. Ask a manager to review the menu."
    }
    val groupById = groups.associateBy(MenuModifierGroupEntity::id)
    val modifierById = currentModifiers.associateBy(MenuModifierEntity::id)
    val selectedByGroup = mutableMapOf<String, Int>()
    selections.forEach { selection ->
        val current = modifierById[selection.modifier.id]
        val group = current?.modifierGroupId?.let(groupById::get)
        if (
            current == null || current != selection.modifier || !current.isActive ||
            current.menuItemId != item.id || group == null || group.menuItemId != item.id ||
            selection.qty !in 1..current.maxQuantity
        ) {
            return "One of the selected item options changed or is no longer available. Choose the item again."
        }
        selectedByGroup[group.id] = (selectedByGroup[group.id] ?: 0) + selection.qty
    }
    groups.forEach { group ->
        val count = selectedByGroup[group.id] ?: 0
        if (count !in group.minSelect..group.maxSelect) {
            return "${group.name} requires ${group.minSelect}–${group.maxSelect} selections. Review the item."
        }
    }
    return null
}

private fun GameSession.isUnbilledEnded(): Boolean = status == "ended" && orderId == null

internal fun GameSession.isPackageBilling(): Boolean =
    billingMode in setOf("package", "legacy_ambiguous") ||
        (billingMode == null && packageId != null)

internal fun GameSession.hasUnverifiedLegacyBillingMode(): Boolean =
    billingMode == "legacy_ambiguous"

internal fun GameSession.hasLockedPackageExtensionSnapshot(): Boolean =
    isPackageBilling() && timerMinutes != null && amountMinor != null &&
        packagePriceMinorSnapshot != null && packageDurationMinutesSnapshot != null &&
        !packageVariantSnapshot.isNullOrBlank() && !packageStationTypeSnapshot.isNullOrBlank()

internal enum class GamingSessionAuthority { CURRENT_SHIFT, OTHER_SHIFT, NO_OPEN_SHIFT, UNKNOWN }

internal fun GameSession.authority(activeShiftId: String?): GamingSessionAuthority = when {
    activeShiftId == null -> GamingSessionAuthority.NO_OPEN_SHIFT
    shiftId == null -> GamingSessionAuthority.UNKNOWN
    shiftId == activeShiftId -> GamingSessionAuthority.CURRENT_SHIFT
    else -> GamingSessionAuthority.OTHER_SHIFT
}

/**
 * Resolve the shift that may own a normal Stop capture.
 *
 * Older deployed servers omitted `SessionRead.shift_id`. That omission must
 * not strand an otherwise authoritative session, but it also must not turn a
 * cross-terminal card into writable work. The compatibility path is therefore
 * limited to a server-known session plus this tablet's server-confirmed open
 * shift; the backend still verifies the real session/shift/terminal before it
 * accepts the queued Stop. A known different shift always remains blocked.
 */
internal fun GameSession.resolvedStopShiftId(
    activeShiftId: String?,
    activeShiftServerConfirmed: Boolean,
    online: Boolean,
): String? = when (authority(activeShiftId)) {
    GamingSessionAuthority.CURRENT_SHIFT -> shiftId
    GamingSessionAuthority.UNKNOWN -> activeShiftId?.takeIf {
        online && activeShiftServerConfirmed && localState in setOf(
            null,
            GamingSessionState.START_SYNCED,
            GamingSessionState.STOP_REJECTED,
        )
    }
    GamingSessionAuthority.OTHER_SHIFT,
    GamingSessionAuthority.NO_OPEN_SHIFT,
    -> null
}

/**
 * The legacy repair is best-effort startup hygiene; it must never prevent the
 * authoritative gaming pull from running. Cancellation is different: it means
 * the ViewModel is being disposed and must retain structured-concurrency
 * semantics instead of continuing work for a dead screen/session.
 */
internal suspend fun recoverGamingThenRefresh(
    recover: suspend () -> Unit,
    refresh: suspend () -> Unit,
    onRecoveryFailure: (Exception) -> Unit = {},
) {
    try {
        recover()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        onRecoveryFailure(failure)
    }
    refresh()
}

/**
 * Room-backed, offline-first — same shape as PosViewModel/ShiftViewModel.
 * Stations and sessions both read from Room, never the network directly.
 *
 * Sessions are the one resource so far that's simultaneously a shared,
 * cross-terminal read (any tablet can start a session at any station, so
 * this device needs to see what every other terminal is doing) and a local
 * write outbox (this device can safely queue the intent while offline). The two
 * live in separate tables — [GamingSessionCacheEntity] wholesale-replaced
 * from the server like the menu, [LocalGamingSessionEntity] for this
 * device's in-flight actions — and are merged at read time below, never
 * written into each other.
 */
class GamingViewModel : ViewModel() {

    private val appCtx = DCompanyApp.instance
    private val db = appCtx.db
    private val gamingApi = ApiClient.create<GamingApi>()
    /** Validated, persisted purpose is part of the same terminal authority as X-Terminal-Id. */
    val activeTerminal: StateFlow<ValidatedTerminalDisplay?> =
        appCtx.terminalStore.activeValidatedTerminal
    private val confirmedTerminalId = activeTerminal
        .map { it?.terminalId }
        .distinctUntilChanged()
    private val resolvedShift = db.shiftDao().observeResolvedOpenShift(confirmedTerminalId)

    private val busyStationId = MutableStateFlow<String?>(null)
    private val error = MutableStateFlow<String?>(null)
    private val notice = MutableStateFlow<String?>(null)
    private val refreshing = MutableStateFlow(true)
    private val refreshError = MutableStateFlow<String?>(null)
    private val _posTargetSelection = MutableStateFlow<PosTargetSelectionUi?>(null)
    val posTargetSelection: StateFlow<PosTargetSelectionUi?> = _posTargetSelection
    @Volatile private var access = GamingAccess()

    private data class ScreenState(
        val busyStationId: String?,
        val actionError: String?,
        val notice: String?,
        val refreshing: Boolean,
        val refreshError: String?,
    )

    private data class ReferenceState(
        val stations: List<GamingStationEntity>,
        val packages: List<GamingPackageCacheEntity>,
        val categories: List<MenuCategoryEntity>,
        val items: List<MenuItemEntity>,
        val variants: List<MenuVariantEntity>,
        val modifierGroups: List<MenuModifierGroupEntity>,
        val modifiers: List<MenuModifierEntity>,
    )

    private data class AddonState(
        val packageExtensions: List<LocalGamingPackageExtensionEntity>,
        val cache: List<GamingSessionAddonCacheEntity>,
        val actions: List<LocalGamingSessionAddonActionEntity>,
    )

    private data class AddonCatalogState(
        val categories: List<MenuCategoryEntity>,
        val items: List<MenuItemEntity>,
        val variants: List<MenuVariantEntity>,
        val modifierGroups: List<MenuModifierGroupEntity>,
        val modifiers: List<MenuModifierEntity>,
    )

    private val referenceState = combine(
        combine(db.gamingDao().observeStations(), db.gamingDao().observePackages(), ::Pair),
        combine(
            db.menuDao().observeCategories(),
            db.menuDao().observeItems(),
            db.menuDao().observeVariants(),
            db.menuDao().observeModifierGroups(),
            db.menuDao().observeModifiers(),
        ) { categories, items, variants, groups, modifiers ->
            AddonCatalogState(categories, items, variants, groups, modifiers)
        },
    ) { gaming, menu ->
        ReferenceState(
            stations = gaming.first,
            packages = gaming.second,
            categories = menu.categories,
            items = menu.items,
            variants = menu.variants,
            modifierGroups = menu.modifierGroups,
            modifiers = menu.modifiers,
        )
    }

    val state: StateFlow<GamingUiState> = combine(
        referenceState,
        db.gamingDao().observeSessionCache(),
        db.gamingDao().observeActiveLocalSessions(),
        (combine(
            combine(
                combine(busyStationId, error, notice) { busy, actionError, actionNotice ->
                    Triple(busy, actionError, actionNotice)
                },
                refreshing,
                refreshError,
                appCtx.sync.resourceRefreshErrors,
            ) { action, isRefreshing, localRefreshError, resourceErrors ->
                ScreenState(
                    busyStationId = action.first,
                    actionError = action.second,
                    notice = action.third,
                    refreshing = isRefreshing,
                    // A realtime refresh can fail after the initial/manual
                    // pull. Observe SyncEngine's resource-scoped feedback so
                    // a populated board never becomes silently stale.
                    refreshError = localRefreshError ?: resourceErrors["gaming"],
                )
            },
            resolvedShift,
            combine(
                db.gamingDao().observeUnresolvedPackageExtensions(),
                db.gamingDao().observeSessionAddonCache(),
                db.gamingDao().observeUnresolvedSessionAddonActions(),
            ) { packageExtensions, addonCache, addonActions ->
                AddonState(packageExtensions, addonCache, addonActions)
            },
        ) { actionState, currentShift, addons ->
            Triple(actionState, currentShift, addons)
        }),
        combine(db.syncMetaDao().observe("gaming"), appCtx.connectivity.online, ::Pair),
    ) { references, cache, local, ui, syncState ->
        val (actionState, currentShift, addons) = ui
        val (meta, online) = syncState
        val categoryNameById = references.categories.associate { it.id to it.name }
        // Overlay an in-flight local stop/send on the older server cache row;
        // otherwise a successfully stopped session still renders "active"
        // and its ENDED_UNBILLED handoff disappears until another pull.
        val localByServerId = local.mapNotNull { row -> row.serverId?.let { it to row } }.toMap()
        val cacheSessions = cache.map { cached ->
            localByServerId[cached.id]?.toGameSession() ?: cached.toGameSession()
        }
        val cachedServerIds = cache.map { it.id }.toSet()
        // A local row already visible via the cache (its action synced and a
        // pull landed) would otherwise show twice — only this device's still
        // -pending or not-yet-pulled sessions belong here on top of it.
        val localOnly = local
            .filter { it.serverId == null || it.serverId !in cachedServerIds }
            .map { it.toGameSession() }
        GamingUiState(
            stations = references.stations.map { it.toStation() },
            packages = references.packages.map { it.toGamingPackage() },
            sessions = cacheSessions + localOnly,
            busyStationId = actionState.busyStationId,
            error = actionState.actionError,
            notice = actionState.notice,
            everSynced = meta != null,
            refreshing = actionState.refreshing,
            refreshError = actionState.refreshError,
            // Starting/operating a session is shared terminal work; opener
            // ownership gates only POS collection and shift close.
            activeShiftId = currentShift?.shiftId,
            activeShiftServerConfirmed = currentShift?.let { shift ->
                shift.server != null || shift.local?.serverShiftId != null
            } == true,
            online = online,
            packageExtensionActions = addons.packageExtensions.map {
                PackageExtensionActionUi(
                    actionId = it.actionId,
                    serverSessionId = it.serverSessionId,
                    shiftId = it.shiftId,
                    state = it.state,
                    lastError = it.lastError,
                )
            },
            addonCatalog = references.items.filter { item ->
                WorkspaceFeatureProfiles.Active.operationalCatalogPolicy.allows(
                    categoryName = categoryNameById[item.categoryId],
                    itemType = item.type,
                    isAvailable = item.isAvailable,
                )
            },
            addonVariants = references.variants.filter { it.isActive },
            addonModifierGroups = references.modifierGroups.filter { it.isActive },
            addonModifiers = references.modifiers.filter { it.isActive },
            sessionAddons = mergeGamingSessionAddons(addons.cache, addons.actions),
            sessionAddonActions = addons.actions.map {
                SessionAddonActionUi(
                    actionId = it.actionId,
                    actionType = it.actionType,
                    serverSessionId = it.serverSessionId,
                    localSessionId = it.localSessionId,
                    clientLineId = it.clientLineId,
                    state = it.state,
                    lastError = it.lastError,
                )
            },
        )
    }
        // Room rows, local overlays and add-on catalogue policy are UI
        // projections. Keep their list mapping/indexing away from Main while
        // preserving the source flow's emission order before StateFlow sharing.
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GamingUiState())

    init {
        val recoveryLease = appCtx.cacheIsolation.currentLease()
        viewModelScope.launch {
            recoverGamingThenRefresh(
                recover = {
                    // MIGRATION_16_17 handles normal upgrades. This idempotent
                    // call also repairs an imported backup that was already
                    // stamped with a newer Room version before the user can
                    // choose a recovery action.
                    if (recoveryLease != null) {
                        appCtx.cacheIsolation.commitIfCurrent(recoveryLease) {
                            db.gamingDao().recoverLegacyRejectedSessions()
                            db.gamingDao().quarantineUnverifiableLegacyPackageStarts()
                        }
                    }
                },
                refresh = ::refreshGaming,
                onRecoveryFailure = { failure ->
                    Log.w(
                        "GamingViewModel",
                        "Legacy gaming-session recovery failed; continuing with refresh",
                        failure,
                    )
                }
            )
        }
        // DCompanyApp is the single reactive alarm owner. It observes the
        // minimal Room session/station flows even when this feature has never
        // been opened. Collecting the full screen state here would keep every
        // Gaming projection active after navigation and duplicate that work.
    }

    fun load() {
        if (refreshing.value) return
        refreshing.value = true
        refreshError.value = null
        viewModelScope.launch { refreshGaming() }
    }

    private suspend fun refreshGaming() {
        try {
            // Keep setup inside the protected region: requestSync can touch
            // storage/work scheduling, so a setup failure must still release
            // the screen's refresh state in finally.
            appCtx.sync.requestSync()
            refreshError.value = when (appCtx.sync.refresh("gaming")) {
                is ResourceRefreshResult.Refreshed,
                is ResourceRefreshResult.Failed,
                -> null // SyncEngine publishes/clears the shared resource error.
                is ResourceRefreshResult.Skipped ->
                    "Gaming stations are unavailable for this account. Ask a manager to check Gaming access."
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            refreshError.value = recoveryGuidance(
                risk = RecoveryRisk.READ,
                failure = failure,
                subject = "gaming stations",
                localStatePreserved = state.value.stations.isNotEmpty(),
            ).message
        } finally {
            refreshing.value = false
        }
    }

    fun updateAccess(next: GamingAccess) {
        access = next
    }

    private fun requireWrite(): Boolean = authorizeAction(access.canManageSessions) {
        error.value = if (access.writeBlockedByMissingShiftRead) {
            GAMING_SHIFT_ACCESS_REQUIRED_MESSAGE
        } else {
            VIEW_ONLY_MESSAGE
        }
    }

    private fun requireIdle(): Boolean {
        if (busyStationId.value == null) return true
        error.value = "Another gaming action is still being saved. Wait for it to finish, then try again."
        return false
    }

    private fun requireNoPackageExtension(session: GameSession, action: String): Boolean {
        val extension = state.value.packageExtensionFor(session.id) ?: return true
        error.value = when (extension.state) {
            GamingPackageExtensionState.PENDING ->
                "A paid extension is saved and waiting for the server. Wait for confirmation before $action."
            GamingPackageExtensionState.AMBIGUOUS ->
                "A paid extension may already be charged. Keep this session unchanged while the app safely confirms the original request."
            GamingPackageExtensionState.REJECTED ->
                extension.lastError?.takeIf(String::isNotBlank)?.let {
                    "The saved paid extension was refused: $it Review and resolve that rejected attempt before $action."
                } ?: "The saved paid extension was refused. Review and resolve it before $action."
            else -> "This session has an unresolved paid extension. Wait for confirmation before $action."
        }
        return false
    }

    private fun requireNoSessionAddonActions(session: GameSession, action: String): Boolean {
        val unresolved = state.value.unresolvedAddonsFor(session)
        if (unresolved.isEmpty()) return true
        val rejected = unresolved.firstOrNull { it.state == GamingSessionAddonActionState.REJECTED }
        error.value = when {
            rejected != null ->
                "A saved Gaming item action was refused: ${rejected.lastError ?: "review it in this session."} " +
                    "Resolve the retained item before $action."
            unresolved.any { it.state == GamingSessionAddonActionState.AMBIGUOUS } ->
                "A Gaming item may already be saved or voided on the server. Wait for exact replay before $action."
            else ->
                "${unresolved.size} Gaming item action(s) are waiting for server confirmation. " +
                    "Keep the bill here until Sync finishes before $action."
        }
        return false
    }

    private fun requireNoActiveSessionAddons(session: GameSession): Boolean {
        val message = sessionCancellationAddonBlockMessage(state.value.addonsFor(session)) ?: return true
        error.value = message
        return false
    }

    private fun requireCurrentShiftSession(session: GameSession, action: String): Boolean {
        val message = when (session.authority(state.value.activeShiftId)) {
            GamingSessionAuthority.CURRENT_SHIFT -> return true
            GamingSessionAuthority.NO_OPEN_SHIFT ->
                "Open a shift before $action."
            GamingSessionAuthority.OTHER_SHIFT ->
                "This session belongs to another POS shift or terminal. Use the terminal that started it, " +
                    "or ask a protected owner to reconcile it after the session ends."
            GamingSessionAuthority.UNKNOWN ->
                "The session shift could not be verified. Refresh Gaming before $action."
        }
        error.value = message
        return false
    }

    fun start(
        station: Station,
        phone: String?,
        timerMinutes: Int?,
        packageId: String? = null,
        extraControllers: Int = 0,
    ) {
        if (!requireWrite()) return
        if (!requireIdle()) return
        if (!station.isActive) {
            error.value = "${station.name} is disabled and cannot start a session. Ask a manager to enable it."
            return
        }
        if (state.value.activeFor(station.id) != null) {
            error.value = "This station already has a session to finish, send to POS, or cancel first."
            return
        }
        gamingStartTerminalBlockMessage(activeTerminal.value?.purpose)?.let { message ->
            error.value = message
            return
        }
        val currentState = state.value
        gamingStartShiftBlockMessage(
            activeShiftId = currentState.activeShiftId,
            activeShiftServerConfirmed = currentState.activeShiftServerConfirmed,
        )?.let { message ->
            error.value = message
            return
        }
        val shift = requireNotNull(currentState.activeShiftId)
        val selectedPackage = packageId?.let { id -> state.value.packages.firstOrNull { it.id == id } }
        if (packageId != null && (
                selectedPackage == null || selectedPackage.kind != "base" ||
                    selectedPackage.stationType != station.type
                )
        ) {
            error.value = "That package is no longer available for ${station.name}. Refresh Gaming and choose again."
            return
        }
        if (extraControllers !in 0..8 || (selectedPackage == null && extraControllers != 0)) {
            error.value = "Extra controllers can only be added to a package session (maximum 8)."
            return
        }
        val capturedAtMillis = System.currentTimeMillis()
        val capturedTimerMinutes = selectedPackage?.durationMinutes ?: timerMinutes
        if (capturedTimerMinutes != null && capturedTimerMinutes !in 1..1_440) {
            error.value = "Booked gaming time must be between 1 minute and 24 hours."
            return
        }
        val capturedTimerEndsAtMillis = calculateCapturedTimerEndsAtMillis(
            startedAtMillis = capturedAtMillis,
            timerMinutes = capturedTimerMinutes,
        )
        if (capturedTimerMinutes != null && capturedTimerEndsAtMillis == null) {
            error.value = "This session timer is outside the supported range. Refresh Gaming and choose again."
            return
        }
        val capturedPackageTotalMinor = selectedPackage?.let { option ->
            runCatching {
                Math.addExact(
                    option.priceMinor,
                    extraControllerSurchargeMinor(extraControllers, option.durationMinutes),
                )
            }.getOrNull()
        }
        if (selectedPackage != null && capturedPackageTotalMinor == null) {
            error.value = "This package total is outside the supported range. Refresh Gaming and choose again."
            return
        }
        val scopeLease = appCtx.cacheIsolation.currentLease() ?: return
        busyStationId.value = station.id
        error.value = null
        notice.value = null
        viewModelScope.launch {
            try {
                var inserted = false
                if (!appCtx.cacheIsolation.commitIfCurrent(scopeLease) {
                        inserted = db.gamingDao().insertStartIfStationAvailable(
                            LocalGamingSessionEntity(
                                localId = UUID.randomUUID().toString(),
                                stationId = station.id,
                                shiftId = shift,
                                customerPhone = phone?.trim()?.takeIf { it.isNotEmpty() },
                                timerMinutes = capturedTimerMinutes,
                                ratePerHourMinor = station.ratePerHourMinor,
                                packageId = selectedPackage?.id,
                                packagePriceMinor = selectedPackage?.priceMinor,
                                packageDurationMinutes = selectedPackage?.durationMinutes,
                                packageVariant = selectedPackage?.variant,
                                billingMode = if (selectedPackage == null) "hourly" else "package",
                                packageStationTypeSnapshot = selectedPackage?.let { station.type },
                                extraControllers = extraControllers,
                                // The captured tap is the operational start time even
                                // while offline. The backend validates and preserves it,
                                // so a local Start -> Stop remains the same chronology.
                                startedAtMillis = capturedAtMillis,
                                state = GamingSessionState.START_PENDING,
                                status = "starting",
                                timerEndsAtMillis = capturedTimerEndsAtMillis,
                                amountMinor = capturedPackageTotalMinor,
                            ),
                        )
                    }
                ) return@launch
                if (!inserted) {
                    error.value =
                        "This station already has a saved session action. Finish or clear it before starting again."
                    return@launch
                }
                appCtx.sync.requestSync()
            } catch (_: Exception) {
                error.value = "The session was not saved on this tablet. Check storage and try again."
            } finally {
                busyStationId.value = null
            }
        }
    }

    fun stop(session: GameSession) {
        if (!requireWrite()) return
        if (!session.canRequestStop()) return
        if (!requireIdle()) return
        if (!requireNoPackageExtension(session, "stopping the session")) return
        val currentState = state.value
        val resolvedStopShiftId = session.resolvedStopShiftId(
            activeShiftId = currentState.activeShiftId,
            activeShiftServerConfirmed = currentState.activeShiftServerConfirmed,
            online = currentState.online,
        )
        if (resolvedStopShiftId == null) {
            if (
                session.authority(currentState.activeShiftId) == GamingSessionAuthority.UNKNOWN &&
                currentState.activeShiftServerConfirmed &&
                !currentState.online
            ) {
                error.value =
                    "The session shift could not be verified while offline. Reconnect and refresh Gaming before stopping it."
                return
            }
            requireCurrentShiftSession(session, "stopping it")
            return
        }
        val scopeLease = appCtx.cacheIsolation.currentLease() ?: return
        // Capture exactly once at the employee's tap. If the network returns
        // ten minutes later, billing still ends at this timestamp.
        val stoppedAtMillis = System.currentTimeMillis()
        busyStationId.value = session.stationId
        error.value = null
        notice.value = null
        viewModelScope.launch {
            try {
                var changed = true
                if (!appCtx.cacheIsolation.commitIfCurrent(scopeLease) {
                        val dao = db.gamingDao()
                        val existing = dao.localSessionByEitherId(session.id)
                        if (existing != null) {
                            // Already an outbox row for this session (this device started
                            // it, possibly still unsynced) — just flag the stop.
                            changed = dao.requestSessionStop(
                                existing.localId,
                                stoppedAtMillis,
                                resolvedStopShiftId,
                            ) != 0
                        } else {
                            // A session this device only ever saw via the cache (started
                            // on another terminal). serverId is already known, so
                            // pushGamingSessionOne skips straight to the stop leg.
                            dao.insertLocalSession(
                                LocalGamingSessionEntity(
                                    localId = UUID.randomUUID().toString(),
                                    serverId = session.id,
                                    stationId = session.stationId,
                                    startedAtMillis = runCatching { Instant.parse(session.startAt).toEpochMilli() }
                                        .getOrDefault(System.currentTimeMillis()),
                                    state = GamingSessionState.STOP_PENDING,
                                    status = "stopping",
                                    endAtMillis = stoppedAtMillis,
                                    timerMinutes = session.timerMinutes,
                                    timerEndsAtMillis = session.timerEndsAt?.let { value ->
                                        runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()
                                    },
                                    billableMinutes = session.billableMinutes,
                                    amountMinor = session.amountMinor,
                                    // Legacy APIs may omit the response field.
                                    // The compatibility gate above allows this
                                    // fallback only for a server-known session
                                    // and server-confirmed current shift.
                                    shiftId = resolvedStopShiftId,
                                    customerPhone = session.customerPhone,
                                    ratePerHourMinor = session.ratePerHourMinor,
                                    packageId = session.packageId,
                                    packagePriceMinor = session.packagePriceMinorSnapshot,
                                    packageDurationMinutes = session.packageDurationMinutesSnapshot,
                                    packageVariant = session.packageVariantSnapshot,
                                    billingMode = session.billingMode,
                                    packageStationTypeSnapshot = session.packageStationTypeSnapshot,
                                    extraControllers = session.extraControllers,
                                    orderId = session.orderId,
                                ),
                            )
                        }
                    }
                ) return@launch
                if (!changed) {
                    error.value = "This session already changed state. Refresh Gaming before stopping it again."
                    return@launch
                }
                appCtx.sync.requestSync()
            } catch (_: Exception) {
                error.value = "The stop request was not saved. The session is still running; try again."
            } finally {
                busyStationId.value = null
            }
        }
    }

    fun addSessionAddon(
        session: GameSession,
        item: MenuItemEntity,
        variant: MenuVariantEntity?,
        modifiers: List<CartModifierSelection>,
        note: String?,
        qty: Int = 1,
    ) {
        if (!requireWrite() || !requireIdle()) return
        if (
            session.orderId != null ||
            !(session.status in setOf("active", "paused") ||
                (session.status == "starting" && session.localState == GamingSessionState.START_PENDING))
        ) {
            error.value = "Items can only be added while this Gaming session is running."
            return
        }
        if (!requireCurrentShiftSession(session, "adding an item")) return
        val catalog = state.value
        val currentItem = catalog.addonCatalog.firstOrNull { it.id == item.id }
        if (
            currentItem == null || currentItem != item
        ) {
            error.value = "${item.name} is not available for session add-ons. Refresh the menu."
            return
        }
        if (qty !in 1..100) {
            error.value = "Choose an item quantity between 1 and 100."
            return
        }
        gamingAddonSelectionError(
            item = currentItem,
            selectedVariant = variant,
            selections = modifiers,
            currentVariants = catalog.addonVariants,
            currentGroups = catalog.addonModifierGroups,
            currentModifiers = catalog.addonModifiers,
        )?.let {
            error.value = it
            return
        }
        val unitPriceMinor = runCatching { configuredUnitPriceMinor(currentItem, variant, modifiers) }.getOrNull()
        if (unitPriceMinor == null || unitPriceMinor !in 0L..9_999_999_999L) {
            error.value = "This item price is outside the supported range. Refresh the menu."
            return
        }
        val normalizedNote = note?.trim()?.takeIf(String::isNotEmpty)
        if ((normalizedNote?.length ?: 0) > 500) {
            error.value = "Keep the item note within 500 characters."
            return
        }
        val scopeLease = appCtx.cacheIsolation.currentLease() ?: return
        val scope = scopeLease.scope
        val branchId = scope.branchId ?: run {
            error.value = "This workspace has no verified branch. Reconnect before adding an item."
            return
        }
        val terminalId = scope.terminalId ?: run {
            error.value = "This workspace has no verified terminal. Reconnect before adding an item."
            return
        }
        val actionId = UUID.randomUUID().toString()
        val clientLineId = UUID.randomUUID().toString()
        busyStationId.value = session.stationId
        error.value = null
        notice.value = null
        viewModelScope.launch {
            try {
                var captured = false
                if (!appCtx.cacheIsolation.commitIfCurrent(scopeLease) {
                        val dao = db.gamingDao()
                        val local = dao.localSessionByEitherId(session.id)
                        val shiftId = local?.shiftId ?: session.shiftId
                        if (!shiftId.isNullOrBlank()) {
                            captured = dao.captureSessionAddonAction(
                                LocalGamingSessionAddonActionEntity(
                                    actionId = actionId,
                                    actionType = GamingSessionAddonActionType.ADD,
                                    ownerCompanyId = scope.companyId,
                                    ownerUserId = scope.userId,
                                    branchId = branchId,
                                    terminalId = terminalId,
                                    localSessionId = local?.localId,
                                    serverSessionId = local?.serverId ?: session.id.takeIf { local == null },
                                    shiftId = shiftId,
                                    clientLineId = clientLineId,
                                    menuItemId = currentItem.id,
                                    menuItemName = currentItem.name,
                                    menuItemType = currentItem.type.lowercase(),
                                    variantId = variant?.id,
                                    modifierSelectionsJson = encodeModifierSelections(
                                        modifiers.map {
                                            LocalModifierSelectionSnapshot(
                                                modifierId = it.modifier.id,
                                                modifierGroupId = it.modifier.modifierGroupId,
                                                name = it.modifier.name,
                                                priceDeltaMinor = it.modifier.priceDeltaMinor,
                                                qty = it.qty,
                                            )
                                        },
                                    ),
                                    qty = qty,
                                    expectedUnitPriceMinor = unitPriceMinor,
                                    note = normalizedNote,
                                    createdAtMillis = System.currentTimeMillis(),
                                ),
                            )
                        }
                    }
                ) return@launch
                if (!captured) {
                    error.value = "The item was not queued because the session or shift changed. Refresh Gaming and try again."
                    return@launch
                }
                notice.value = "${currentItem.name} ×$qty saved. It will join this session's single POS bill after Sync."
                appCtx.sync.requestSync()
            } catch (failure: Exception) {
                error.value = failure.message?.takeIf { it.contains("already captured", ignoreCase = true) }
                    ?: "The item was not saved on this tablet. No bill line was queued; try again."
            } finally {
                busyStationId.value = null
            }
        }
    }

    fun voidSessionAddon(session: GameSession, addon: GamingSessionAddonUi, reason: String) {
        if (!requireWrite() || !requireIdle()) return
        if (
            session.orderId != null ||
            session.status !in setOf("starting", "active", "paused", "stopping", "ended") ||
            (session.status == "ended" && !session.isUnbilledEnded())
        ) {
            error.value = "This item can only be voided before the Gaming bill is sent to POS."
            return
        }
        if (!requireCurrentShiftSession(session, "voiding this item")) return
        if (addon.voided) {
            error.value = "This item is already voided. Its original line remains visible for audit."
            return
        }
        if (addon.localState == GamingSessionAddonActionState.REJECTED) {
            error.value = "Resolve the rejected saved item action before creating a void."
            return
        }
        val normalizedReason = reason.trim()
        if (normalizedReason.length !in 3..500) {
            error.value = "Enter a void reason between 3 and 500 characters."
            return
        }
        val scopeLease = appCtx.cacheIsolation.currentLease() ?: return
        val scope = scopeLease.scope
        val branchId = scope.branchId ?: return
        val terminalId = scope.terminalId ?: return
        val actionId = UUID.randomUUID().toString()
        busyStationId.value = session.stationId
        error.value = null
        notice.value = null
        viewModelScope.launch {
            try {
                var captured = false
                if (!appCtx.cacheIsolation.commitIfCurrent(scopeLease) {
                        val dao = db.gamingDao()
                        val local = dao.localSessionByEitherId(session.id)
                        val shiftId = local?.shiftId ?: session.shiftId
                        if (!shiftId.isNullOrBlank()) {
                            captured = dao.captureSessionAddonAction(
                                LocalGamingSessionAddonActionEntity(
                                    actionId = actionId,
                                    actionType = GamingSessionAddonActionType.VOID,
                                    ownerCompanyId = scope.companyId,
                                    ownerUserId = scope.userId,
                                    branchId = branchId,
                                    terminalId = terminalId,
                                    localSessionId = local?.localId ?: addon.localSessionId,
                                    serverSessionId = local?.serverId ?: addon.serverSessionId,
                                    shiftId = shiftId,
                                    clientLineId = addon.clientLineId,
                                    serverAddonId = addon.serverAddonId,
                                    menuItemId = addon.menuItemId,
                                    menuItemName = addon.menuItemName,
                                    menuItemType = addon.menuItemType,
                                    variantId = addon.variantId,
                                    modifierSelectionsJson = addon.modifierSelectionsJson,
                                    qty = addon.qty,
                                    expectedUnitPriceMinor = addon.unitPriceMinor,
                                    note = addon.note,
                                    voidReason = normalizedReason,
                                    createdAtMillis = System.currentTimeMillis(),
                                ),
                            )
                        }
                    }
                ) return@launch
                if (!captured) {
                    error.value = "This item already has a saved void, or the session changed. Refresh Gaming."
                    return@launch
                }
                notice.value = "Void saved with its reason. The original item remains visible until Sync confirms it."
                appCtx.sync.requestSync()
            } catch (_: Exception) {
                error.value = "The item void was not saved. The item remains billable; try again."
            } finally {
                busyStationId.value = null
            }
        }
    }

    fun discardRejectedSessionAddonAction(actionId: String, reason: String) {
        if (!requireWrite() || !requireIdle()) return
        val normalizedReason = reason.trim()
        if (normalizedReason.length !in 3..500) {
            error.value = "Enter a review reason between 3 and 500 characters."
            return
        }
        val action = state.value.sessionAddonActions.firstOrNull { it.actionId == actionId }
        if (action?.state != GamingSessionAddonActionState.REJECTED) {
            error.value = "Only a definitively rejected Gaming item action can be dismissed."
            return
        }
        val scopeLease = appCtx.cacheIsolation.currentLease() ?: return
        val actionOwner = state.value.sessions.firstOrNull { session ->
            session.id == action.serverSessionId || session.id == action.localSessionId
        }?.stationId ?: "addon-review:$actionId"
        busyStationId.value = actionOwner
        error.value = null
        notice.value = null
        viewModelScope.launch {
            try {
                val changed = appCtx.cacheIsolation.commitResultIfCurrent(scopeLease) {
                    db.gamingDao().discardRejectedSessionAddonAction(
                        actionId,
                        normalizedReason,
                        System.currentTimeMillis(),
                    )
                }
                if (
                    changed is cloud.dcompany.erp.core.auth.ScopedCommitResult.Committed &&
                    changed.value == 1
                ) {
                    notice.value = if (action.actionType == GamingSessionAddonActionType.VOID) {
                        "Rejected item Void acknowledged. The item remains billable; void it again if removal is still required."
                    } else {
                        "Rejected item Add acknowledged. The server did not add it, and no request was rewritten or replayed."
                    }
                    appCtx.sync.requestSync()
                } else {
                    error.value =
                        "The rejected action changed state. Refresh Gaming before reviewing it again."
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                error.value =
                    "The rejected item review was not saved. Nothing was removed; try again."
            } finally {
                if (busyStationId.value == actionOwner) busyStationId.value = null
            }
        }
    }

    /**
     * Resolve a definitively rejected start without replaying an action that
     * may already have represented customer play. The exact owner decision is
     * persisted before the protected audit request leaves the tablet. If the
     * response is lost, the same body and local-action id are retried; the
     * station/shift remain blocked until an authoritative receipt is stored.
     */
    fun resolveLegacyPackageStart(
        session: GameSession,
        resolution: String,
        referenceOrderId: String?,
        reason: String,
        ownerEmail: String? = null,
        ownerPassword: String? = null,
    ) {
        if (!session.canResolveRejectedStart()) return
        val profile = appCtx.shiftCache.profile.value ?: run {
            error.value = "The signed-in staff identity is unavailable. Keep the evidence and sign in again with the same account."
            return
        }
        val currentIsProtectedApprover =
            access.canReconcileLegacySessions && profile.protectedAccess && profile.auditAccess
        if (!currentIsProtectedApprover && !access.canManageSessions) {
            error.value = "A gaming staff account must keep this workspace open while a protected owner approves the recovery."
            return
        }
        val normalizedOwnerEmail = ownerEmail?.trim()?.lowercase().orEmpty()
        val delegatedOwnerPassword = ownerPassword.orEmpty()
        if (
            !currentIsProtectedApprover &&
            (normalizedOwnerEmail.isBlank() || delegatedOwnerPassword.isBlank())
        ) {
            error.value = "Enter the protected owner's email and password to record this audited resolution."
            return
        }
        if (!requireIdle()) return
        if (!appCtx.connectivity.online.value) {
            error.value =
                "Reconnect before resolving this rejected gaming start. The station stays blocked until the audit receipt is confirmed."
            return
        }
        val normalizedReason = reason.trim()
        val normalizedReference = referenceOrderId?.trim()?.takeIf(String::isNotEmpty)
        legacyResolutionInputError(resolution, normalizedReference, normalizedReason)?.let { message ->
            error.value = message
            return
        }
        val scopeLease = appCtx.cacheIsolation.currentLease() ?: return
        val activeTerminal = appCtx.terminalStore.activeValidatedTerminal.value ?: run {
            error.value = "This tablet has no verified terminal. The retained evidence was not changed."
            return
        }
        if (runCatching { UUID.fromString(session.id) }.isFailure) {
            error.value =
                "This legacy action has an invalid local reference and cannot be audited automatically. Contact support; the evidence was kept."
            return
        }

        busyStationId.value = session.stationId
        error.value = null
        notice.value = null
        viewModelScope.launch {
            val authority = if (currentIsProtectedApprover) {
                LegacyRecoveryAuthority(profile.userId, gamingApi)
            } else {
                try {
                    // Bootstrap and owner clients are isolated from the staff
                    // TokenStore/AuthInterceptor. Neither token is installed,
                    // refreshed, cached, or allowed to change the Room lease.
                    val bootstrap = ApiClient.createEphemeralAuthorityApi<ErpApi>()
                    val pair = bootstrap.login(
                        LoginRequest(
                            email = normalizedOwnerEmail,
                            password = delegatedOwnerPassword,
                        ),
                    )
                    val ownerIdentityApi = ApiClient.createEphemeralAuthorityApi<ErpApi>(
                        accessToken = pair.accessToken,
                    )
                    val approver = ownerIdentityApi.me()
                    legacyRecoveryApproverError(profile, approver, activeTerminal)?.let { message ->
                        error.value = message
                        busyStationId.value = null
                        return@launch
                    }
                    LegacyRecoveryAuthority(
                        actorUserId = approver.userId,
                        api = ApiClient.createEphemeralAuthorityApi<GamingApi>(
                            accessToken = pair.accessToken,
                            terminalId = activeTerminal.terminalId,
                        ),
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: ApiException) {
                    error.value = when (failure.status) {
                        401 -> "Protected-owner email or password is incorrect. The retained evidence was not changed."
                        429 -> "Too many owner approval attempts. Wait a moment; the retained evidence stays blocked."
                        else -> failure.message
                            ?: "The protected owner could not be verified. The retained evidence was not changed."
                    }
                    busyStationId.value = null
                    return@launch
                } catch (_: Exception) {
                    error.value =
                        "The protected owner could not be verified. Check the connection; the retained evidence was not changed."
                    busyStationId.value = null
                    return@launch
                }
            }
            var localActionId: String? = null
            try {
                var captured: LocalGamingSessionEntity? = null
                if (!appCtx.cacheIsolation.commitIfCurrent(scopeLease) {
                        val dao = db.gamingDao()
                        val row = dao.localSessionByEitherId(session.id)
                        if (
                            row != null && row.serverId == null &&
                            row.state == GamingSessionState.START_REJECTED
                        ) {
                            localActionId = row.localId
                            val attemptState = row.legacyResolutionAttemptState
                            if (
                                attemptState == null ||
                                attemptState == GamingLegacyResolutionAttemptState.REJECTED
                            ) {
                                dao.captureLegacyPackageResolution(
                                    localId = row.localId,
                                    resolution = resolution,
                                    reason = normalizedReason,
                                    referenceOrderId = normalizedReference,
                                    actorUserId = authority.actorUserId,
                                    capturedAtMillis = System.currentTimeMillis(),
                                )
                            }
                            captured = dao.localSessionById(row.localId)
                        }
                    }
                ) return@launch

                val action = captured
                if (
                    action == null ||
                    action.legacyResolutionAttemptState !in setOf(
                        GamingLegacyResolutionAttemptState.PENDING,
                        GamingLegacyResolutionAttemptState.AMBIGUOUS,
                    ) ||
                    action.legacyResolution == null || action.legacyResolutionReason == null
                ) {
                    error.value =
                        "This rejected-start resolution changed before it was saved. Refresh Gaming and review the retained evidence."
                    return@launch
                }
                if (
                    !legacyResolutionApproverMatches(
                        capturedApproverUserId = action.legacyResolvedByUserId,
                        presentedApproverUserId = authority.actorUserId,
                    )
                ) {
                    error.value = if (action.legacyResolvedByUserId.isNullOrBlank()) {
                        "This saved approval has no trustworthy owner identity. It was kept unchanged; contact support."
                    } else {
                        "Use the same protected owner who approved the first attempt. The exact request was kept and was not sent with this account."
                    }
                    return@launch
                }

                val actionId = action.localId
                localActionId = actionId
                val capturedShift = action.shiftId
                val localShift = capturedShift?.let { db.shiftDao().byLocalId(it) }
                val resolvedServerShiftId = resolvedLegacyCapturedServerShiftId(
                    capturedShiftId = capturedShift,
                    localMappingFound = localShift != null,
                    mappedServerShiftId = localShift?.serverShiftId,
                )
                if (resolvedServerShiftId == null) {
                    error.value =
                        "The retained gaming start has no confirmed server shift mapping. Its evidence and owner decision remain locked; contact support."
                    return@launch
                }
                val originalStartedAtMillis =
                    action.legacyOriginalCapturedStartAtMillis ?: action.startedAtMillis
                val originalStoppedAtMillis =
                    action.legacyOriginalCapturedStopAtMillis ?: action.endAtMillis
                if (action.packageId == null && action.ratePerHourMinor == null) {
                    error.value =
                        "The retained hourly Start has no trustworthy displayed rate. Its evidence and owner decision remain locked; contact support."
                    return@launch
                }
                val body = LegacyGamingOutboxResolutionBody(
                    localActionId = actionId,
                    stationId = action.stationId,
                    shiftId = resolvedServerShiftId,
                    capturedStartedAt = Instant.ofEpochMilli(originalStartedAtMillis).toString(),
                    capturedStoppedAt = originalStoppedAtMillis
                        ?.let { Instant.ofEpochMilli(it).toString() },
                    packageId = action.packageId,
                    expectedRatePerHourMinor = action.ratePerHourMinor
                        .takeIf { action.packageId == null },
                    resolution = action.legacyResolution,
                    referenceOrderId = action.legacyResolutionReferenceOrderId,
                    reason = action.legacyResolutionReason,
                )
                val receipt = authority.api.resolveLegacyOutbox(
                    body = body,
                    key = legacyResolutionIdempotencyKey(actionId),
                    provenance = outboxProvenanceHeaders(
                        action.legacyResolutionCapturedAtMillis,
                        actionId,
                    ),
                )
                val resolvedAtMillis = runCatching { Instant.parse(receipt.resolvedAt).toEpochMilli() }
                    .getOrNull()
                legacyResolutionReceiptError(
                    body = body,
                    receipt = receipt,
                    expectedBranchId = activeTerminal.branchId,
                    expectedTerminalId = activeTerminal.terminalId,
                )?.let { throw IllegalStateException(it) }
                if (resolvedAtMillis == null) {
                    throw IllegalStateException("Legacy resolution receipt had an invalid timestamp")
                }

                var committed = false
                val recovered = receipt.serverSession
                val authoritative = recovered?.toCacheEntity()
                val recoveredDisposition = recovered?.let {
                    recoveredLegacyServerDisposition(
                        server = it,
                        originalCapturedStopAtMillis = originalStoppedAtMillis,
                        retainedRatePerHourMinor = action.ratePerHourMinor,
                        verifiedReferenceOrderId = body.referenceOrderId,
                    )
                }
                val restoreCapturedStop = recoveredDisposition ==
                    RecoveredLegacyServerDisposition.RESTORE_CAPTURED_STOP
                val retainedBillingReview = recoveredDisposition ==
                    RecoveredLegacyServerDisposition.RETAIN_BILLING_REVIEW
                if (!appCtx.cacheIsolation.commitIfCurrent(scopeLease) {
                        committed = if (authoritative != null) {
                            db.gamingDao().confirmRecoveredLegacyServerSession(
                                localId = actionId,
                                capturedResolution = body.resolution,
                                reason = body.reason,
                                referenceOrderId = body.referenceOrderId,
                                actorUserId = authority.actorUserId,
                                receiptId = receipt.receiptId,
                                resolvedAtMillis = resolvedAtMillis,
                                authoritative = authoritative,
                                disposition = requireNotNull(recoveredDisposition),
                                billingReviewError = LEGACY_RECOVERED_BILLING_REVIEW_ERROR
                                    .takeIf { retainedBillingReview },
                            )
                        } else {
                            db.gamingDao().confirmLegacyPackageResolution(
                                localId = actionId,
                                resolution = body.resolution,
                                reason = body.reason,
                                referenceOrderId = body.referenceOrderId,
                                actorUserId = authority.actorUserId,
                                receiptId = receipt.receiptId,
                                resolvedAtMillis = resolvedAtMillis,
                            ) != 0
                        }
                    }
                ) return@launch
                if (!committed) {
                    throw IllegalStateException("Legacy resolution receipt could not be committed")
                }
                if (restoreCapturedStop) appCtx.sync.requestSync()
                GamingAlarmReconciler.reconcile(appCtx)
                notice.value = when {
                    recovered != null && retainedBillingReview ->
                        "The server Start was recovered, but its original offline Stop cannot be converted into a safe bill. Audit receipt ${receipt.receiptId} is retained; ordinary Stop and POS handoff remain locked for owner/support review."
                    recovered != null && restoreCapturedStop &&
                        originalStoppedAtMillis!! < authoritative!!.startAtMillis ->
                        "The server proved the original package Start. The earlier offline Stop remains in audit receipt ${receipt.receiptId}; replay was adjusted to the authoritative Start time and queued."
                    recovered != null && restoreCapturedStop ->
                        "The server proved the original package Start. Its captured Stop was restored with the same action ID and queued; audit receipt ${receipt.receiptId} was retained."
                    recovered?.orderId != null ->
                        "The server session was restored and is already linked to POS order ${recovered.orderId}. Audit receipt ${receipt.receiptId} was retained; no second handoff is needed."
                    recovered != null && recovered.status in setOf("active", "paused") ->
                        "The server proved and restored the original package session. It is still ${recovered.status}; Stop it normally when play finishes. Audit receipt ${receipt.receiptId} was retained."
                    recovered != null ->
                        "The authoritative ${recovered.status} package session was restored. Audit receipt ${receipt.receiptId} was retained."
                    body.resolution == GamingLegacyResolution.MANUAL_BILL_RECORDED ->
                        "Rejected gaming start resolved against the verified POS order. Audit receipt ${receipt.receiptId} was retained."
                    else ->
                        "No play confirmed for the rejected gaming start. Audit receipt ${receipt.receiptId} was retained."
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: ApiException) {
                val attemptState = legacyResolutionFailureState(failure)
                localActionId?.let { actionId ->
                    appCtx.cacheIsolation.commitIfCurrent(scopeLease) {
                        db.gamingDao().markLegacyPackageResolutionAttempt(
                            actionId,
                            attemptState,
                            failure.message ?: "The server did not confirm this resolution.",
                        )
                    }
                }
                error.value = if (attemptState == GamingLegacyResolutionAttemptState.AMBIGUOUS) {
                    if (failure.code == "idempotency_conflict") {
                        "The saved approval belongs to the first protected owner. Use that same owner to retry; the exact decision remains locked."
                    } else {
                        "The audit response was not safely refused. The exact decision and approving owner are retained; use the same owner to retry."
                    }
                } else {
                    legacyResolutionRejectedMessage(failure)
                }
            } catch (_: Exception) {
                localActionId?.let { actionId ->
                    appCtx.cacheIsolation.commitIfCurrent(scopeLease) {
                        db.gamingDao().markLegacyPackageResolutionAttempt(
                            actionId,
                            GamingLegacyResolutionAttemptState.AMBIGUOUS,
                            "The audit response could not be verified.",
                        )
                    }
                }
                error.value =
                    "The audit response could not be verified. The exact decision is retained and the station stays blocked; retry it when online."
            } finally {
                busyStationId.value = null
            }
        }
    }

    /**
     * Explicit second leg: stopping computes the bill; terminal purpose decides
     * whether the bill stays on this drawer or needs an explicit Cafe POS shift.
     */
    fun sendToPos(session: GameSession) {
        if (!requireWrite()) return
        if (!session.canSendToPos(state.value.hasActiveAddons(session))) return
        if (!requireIdle()) return
        if (!requireNoPackageExtension(session, "sending the bill to POS")) return
        if (!requireNoSessionAddonActions(session, "sending the bill to POS")) return
        if (!requireCurrentShiftSession(session, "sending it to POS")) return
        val terminal = activeTerminal.value ?: run {
            error.value =
                "This tablet has no verified terminal purpose. Reconnect and confirm its Terminal setting; " +
                    "the ended bill remains saved in Gaming."
            return
        }
        _posTargetSelection.value = null
        when (gamingPosRoute(terminal.purpose)) {
            GamingPosRoute.LOCAL -> queueLocalPosSend(session)
            GamingPosRoute.CROSS_TERMINAL -> prepareCrossTerminalPosSend(session, terminal)
            GamingPosRoute.BLOCKED -> {
                error.value =
                    "This tablet's terminal purpose is not recognised. Ask an owner to correct it in Settings. " +
                        "The ended bill remains saved in Gaming."
            }
        }
    }

    /** Preserve the established durable/offline outbox for Cafe POS and Hybrid terminals. */
    private fun queueLocalPosSend(session: GameSession) {
        val scopeLease = appCtx.cacheIsolation.currentLease() ?: return
        busyStationId.value = session.stationId
        error.value = null
        notice.value = null
        viewModelScope.launch {
            try {
                var changed = true
                if (!appCtx.cacheIsolation.commitIfCurrent(scopeLease) {
                        db.withTransaction {
                            val dao = db.gamingDao()
                            val existing = dao.localSessionByEitherId(session.id)
                            if (
                                dao.unresolvedSessionAddonActionCount(
                                    existing?.localId,
                                    existing?.serverId ?: session.id,
                                ) > 0
                            ) {
                                changed = false
                            } else if (existing != null) {
                                changed = dao.requestSessionSend(existing.localId) != 0
                            } else {
                                dao.insertLocalSession(
                                LocalGamingSessionEntity(
                                    localId = UUID.randomUUID().toString(),
                                    serverId = session.id,
                                    stationId = session.stationId,
                                    customerPhone = session.customerPhone,
                                    startedAtMillis = runCatching { Instant.parse(session.startAt).toEpochMilli() }
                                        .getOrDefault(System.currentTimeMillis()),
                                    state = GamingSessionState.SEND_PENDING,
                                    status = session.status,
                                    endAtMillis = session.endAt?.let { value ->
                                        runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()
                                    },
                                    billableMinutes = session.billableMinutes,
                                    amountMinor = session.amountMinor,
                                    shiftId = session.shiftId,
                                    ratePerHourMinor = session.ratePerHourMinor,
                                    packageId = session.packageId,
                                    packagePriceMinor = session.packagePriceMinorSnapshot,
                                    packageDurationMinutes = session.packageDurationMinutesSnapshot,
                                    packageVariant = session.packageVariantSnapshot,
                                    billingMode = session.billingMode,
                                    packageStationTypeSnapshot = session.packageStationTypeSnapshot,
                                    extraControllers = session.extraControllers,
                                ),
                            )
                            }
                        }
                    }
                ) return@launch
                if (!changed) {
                    error.value = "This session is not ready to send. Refresh Gaming and try again."
                    return@launch
                }
                appCtx.sync.requestSync()
            } catch (_: Exception) {
                error.value = "The POS handoff was not saved on this tablet. Try Send to POS again."
            } finally {
                busyStationId.value = null
            }
        }
    }

    /** Gaming-only terminals must choose a live backend-eligible receiving shift. */
    private fun prepareCrossTerminalPosSend(
        session: GameSession,
        terminal: ValidatedTerminalDisplay,
    ) {
        if (!state.value.online) {
            error.value =
                "Reconnect to load open POS shifts. This ended bill remains saved in Gaming."
            return
        }
        val scopeLease = appCtx.cacheIsolation.currentLease() ?: return
        busyStationId.value = session.stationId
        error.value = null
        notice.value = null
        viewModelScope.launch {
            try {
                val local = db.gamingDao().localSessionByEitherId(session.id)
                val serverSessionId = local?.serverId ?: session.id.takeIf { local == null }
                if (serverSessionId == null) {
                    error.value =
                        "This stopped session is still waiting for server confirmation. Keep it in Gaming, " +
                            "wait for Sync, then try Send to POS again."
                    return@launch
                }
                val targets = gamingApi.posTargetShifts(serverSessionId)
                posTargetListError(targets, terminal.terminalId)?.let { message ->
                    error.value = message
                    return@launch
                }
                if (!appCtx.cacheIsolation.commitIfCurrent(scopeLease) {
                        _posTargetSelection.value = PosTargetSelectionUi(
                            session = session,
                            serverSessionId = serverSessionId,
                            sourceTerminalId = terminal.terminalId,
                            targets = targets.sortedWith(
                                compareBy(String.CASE_INSENSITIVE_ORDER, PosTargetShift::terminalName)
                                    .thenBy(PosTargetShift::openedAt)
                                    .thenBy(PosTargetShift::shiftId),
                            ),
                        )
                    }
                ) return@launch
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: ApiException) {
                error.value = posTargetLoadFailureMessage(failure)
                // Another terminal may already have completed the same bill.
                // A refresh removes it only when the authoritative order link exists.
                if (failure.message?.contains("already sent", ignoreCase = true) == true) {
                    try {
                        appCtx.sync.refresh("gaming")
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (refreshFailure: Exception) {
                        Log.w(
                            "GamingViewModel",
                            "Could not refresh an already-linked Gaming handoff",
                            refreshFailure,
                        )
                    }
                }
            } catch (_: Exception) {
                error.value =
                    "Could not verify open POS shifts. This ended bill remains saved in Gaming; " +
                        "check the connection and try again."
            } finally {
                busyStationId.value = null
            }
        }
    }

    /**
     * Confirm exactly one target from the current picker. The ended row is not
     * moved to SEND_PENDING: only a validated server receipt hides it, so every
     * timeout/refusal remains visible and safely retryable.
     */
    fun handoffToPos(targetShiftId: String) {
        val selection = _posTargetSelection.value ?: return
        val target = selection.targets.firstOrNull { it.shiftId == targetShiftId } ?: run {
            error.value = "Choose one of the open POS shifts shown in this dialog."
            return
        }
        val session = selection.session
        if (!requireWrite()) return
        if (!session.canSendToPos(state.value.hasActiveAddons(session))) return
        if (!requireIdle()) return
        if (!requireNoPackageExtension(session, "sending the bill to POS")) return
        if (!requireNoSessionAddonActions(session, "sending the bill to POS")) return
        if (!requireCurrentShiftSession(session, "sending it to POS")) return
        val active = activeTerminal.value
        if (
            active == null || active.terminalId != selection.sourceTerminalId ||
            gamingPosRoute(active.purpose) != GamingPosRoute.CROSS_TERMINAL
        ) {
            _posTargetSelection.value = null
            error.value =
                "This tablet's verified terminal changed. The bill remains in Gaming; reopen Send to POS " +
                    "after confirming the correct Gaming Area terminal."
            return
        }
        if (!state.value.online) {
            error.value =
                "Reconnect before sending this bill to ${target.terminalName}. It remains saved in Gaming."
            return
        }
        val scopeLease = appCtx.cacheIsolation.currentLease() ?: return
        busyStationId.value = session.stationId
        error.value = null
        notice.value = null
        viewModelScope.launch {
            try {
                val result = gamingApi.handoffToPos(
                    id = selection.serverSessionId,
                    body = SessionPosHandoffBody(targetShiftId = target.shiftId),
                )
                posHandoffResponseError(
                    session = session,
                    sourceTerminalId = selection.sourceTerminalId,
                    target = target,
                    result = result,
                )?.let { mismatch ->
                    error.value =
                        "$mismatch The ended bill remains visible; refresh Gaming and contact support before retrying."
                    return@launch
                }
                if (!appCtx.cacheIsolation.commitIfCurrent(scopeLease) {
                        val dao = db.gamingDao()
                        db.withTransaction {
                            dao.localSessionByEitherId(session.id)?.let { local ->
                                dao.markSessionSent(local.localId, result.orderId, result.amountMinor)
                            }
                            dao.markCachedSessionSent(
                                selection.serverSessionId,
                                result.orderId,
                                result.amountMinor,
                            )
                        }
                        _posTargetSelection.value = null
                        GamingAlarmReconciler.reconcile(appCtx)
                    }
                ) return@launch
                notice.value = if (result.alreadyLinked) {
                    "This bill was already waiting at ${target.terminalName}; no duplicate order was created."
                } else {
                    "Bill sent to ${target.terminalName}. ${target.openedByName}'s open shift will receive it in POS."
                }
                try {
                    appCtx.sync.refresh("gaming")
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (refreshFailure: Exception) {
                    Log.w(
                        "GamingViewModel",
                        "Cross-terminal POS handoff committed; deferred Gaming refresh failed",
                        refreshFailure,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: ApiException) {
                error.value = posHandoffFailureMessage(failure, target.terminalName)
                // Ambiguous responses keep the exact selected shift visible so
                // retry exercises backend natural idempotency. A definitive
                // refusal reloads eligibility on the next Send tap.
                if (!failure.isAmbiguous) _posTargetSelection.value = null
            } catch (_: Exception) {
                error.value =
                    "The result from ${target.terminalName} could not be verified. This bill remains visible " +
                        "in Gaming; retry this same shift when connected so no duplicate can be created."
            } finally {
                busyStationId.value = null
            }
        }
    }

    fun dismissPosTargetSelection() {
        if (busyStationId.value == null) _posTargetSelection.value = null
    }

    /** Reminder-only +time for non-package sessions; final billing stays elapsed-time based. */
    fun extendTimer(session: GameSession, additionalMinutes: Int = 30) {
        if (session.orderId != null) {
            error.value =
                "This session is already linked to a paid POS order. Stop it when play ends; do not add more billable time."
            return
        }
        if (session.isPackageBilling()) {
            error.value = "Package sessions must use a priced extension package."
            return
        }
        if (additionalMinutes <= 0) {
            error.value = "Extension time must be greater than zero."
            return
        }
        if ((session.timerMinutes ?: 0) >= 1_440) {
            error.value = "A gaming timer cannot exceed 24 hours."
            return
        }
        runDirectSessionMutation(
            session = session,
            action = "adding time",
            successMessage = "$additionalMinutes minutes added to this session.",
        ) {
            gamingApi.extendTimer(
                id = session.id,
                body = SessionTimerExtendBody(
                    expectedTimerMinutes = session.timerMinutes,
                    additionalMinutes = additionalMinutes,
                ),
                // Reusing the same snapshot/action tuple after response loss
                // returns the original idempotent response. Once the refreshed
                // timer changes, the next intentional +30 receives a new key.
                key = "gaming-timer:${session.id}:${session.timerMinutes ?: "open"}:+$additionalMinutes",
            )
        }
    }

    /** Paid extensions are captured durably; the immutable action UUID is their replay key. */
    fun extendWithPackage(session: GameSession, extension: GamingPackage) {
        if (session.orderId != null) {
            error.value =
                "This session is already linked to a paid POS order. Stop it when play ends; do not add another paid extension."
            return
        }
        val station = state.value.stations.firstOrNull { it.id == session.stationId }
        if (!session.hasLockedPackageExtensionSnapshot()) {
            error.value =
                "This legacy package session has no locked timer, total, variant, or station type. Refresh Gaming; if it stays missing, ask the protected owner to review billing."
            return
        }
        if (
            extension.kind != "extension" || station == null || extension.stationType != station.type ||
            extension.stationType != session.packageStationTypeSnapshot ||
            extension.variant != session.packageVariantSnapshot
        ) {
            error.value =
                "That paid extension does not match this session's locked package variant. Refresh Gaming and choose again."
            return
        }
        val expectedTimerMinutes = requireNotNull(session.timerMinutes)
        val expectedAmountMinor = requireNotNull(session.amountMinor)
        if (!requireWrite() || !requireIdle()) return
        if (session.status !in setOf("active", "paused")) {
            error.value = "This session is no longer running. Refresh Gaming before adding paid time."
            return
        }
        if (!requireCurrentShiftSession(session, "adding paid time")) return
        if (!requireNoPackageExtension(session, "adding another paid extension")) return
        val scopeLease = appCtx.cacheIsolation.currentLease() ?: return
        val actionId = UUID.randomUUID().toString()
        val totalMinor = extension.priceMinor + extraControllerSurchargeMinor(
            session.extraControllers,
            extension.durationMinutes,
        )
        busyStationId.value = session.stationId
        error.value = null
        notice.value = null
        viewModelScope.launch {
            try {
                var captured = false
                if (!appCtx.cacheIsolation.commitIfCurrent(scopeLease) {
                        val dao = db.gamingDao()
                        val local = dao.localSessionByEitherId(session.id)
                        val serverSessionId = local?.serverId ?: session.id.takeIf { local == null }
                        if (serverSessionId != null) {
                            captured = dao.capturePackageExtension(
                                LocalGamingPackageExtensionEntity(
                                    actionId = actionId,
                                    serverSessionId = serverSessionId,
                                    localSessionId = local?.localId,
                                    shiftId = session.shiftId,
                                    packageId = extension.id,
                                    expectedPackagePriceMinor = extension.priceMinor,
                                    expectedPackageDurationMinutes = extension.durationMinutes,
                                    expectedPackageVariant = extension.variant,
                                    expectedSessionTimerMinutes = expectedTimerMinutes,
                                    expectedSessionAmountMinor = expectedAmountMinor,
                                    createdAtMillis = System.currentTimeMillis(),
                                ),
                            )
                        }
                    }
                ) return@launch
                if (!captured) {
                    error.value =
                        "This session already has an unresolved paid extension, or its start is not server-confirmed. Refresh Gaming before trying again."
                    return@launch
                }
                notice.value =
                    "${extension.name} (${totalMinor.asRupees()}) saved. Keep the session unchanged until the server confirms the charge."
                appCtx.sync.requestSync()
            } catch (_: Exception) {
                error.value = "The paid extension was not saved on this tablet. No charge was queued; try again."
            } finally {
                busyStationId.value = null
            }
        }
    }

    fun discardRejectedPackageExtension(actionId: String, reason: String) {
        if (!requireWrite() || !requireIdle()) return
        val action = state.value.packageExtensionActions.firstOrNull { it.actionId == actionId }
        if (action == null || action.state != GamingPackageExtensionState.REJECTED) {
            error.value = "This extension is not a definitively rejected attempt. It remains blocked for safe confirmation."
            return
        }
        if (
            !canResolveRejectedExtensionForShift(
                actionShiftId = action.shiftId,
                canWrite = access.canManageSessions,
            )
        ) {
            error.value =
                "This saved extension has no exact shift identity, so it cannot be replayed safely. Keep this tablet signed in and contact support; the retained transaction was not removed."
            return
        }
        val normalizedReason = reason.trim()
        if (normalizedReason.length !in 3..500) {
            error.value = "Enter a resolution reason between 3 and 500 characters."
            return
        }
        if (!appCtx.connectivity.online.value) {
            error.value =
                "Reconnect before resolving the rejected extension. The original charge must be replayed and verified first."
            return
        }
        val scopeLease = appCtx.cacheIsolation.currentLease() ?: return
        busyStationId.value = action.serverSessionId
        error.value = null
        notice.value = null
        viewModelScope.launch {
            try {
                var retainedSnapshot: LocalGamingPackageExtensionEntity? = null
                if (!appCtx.cacheIsolation.commitIfCurrent(scopeLease) {
                        retainedSnapshot = db.gamingDao().packageExtensionAction(actionId)
                    }
                ) return@launch
                val retained = retainedSnapshot
                if (
                    retained == null || retained.state != GamingPackageExtensionState.REJECTED ||
                    retained.serverSessionId != action.serverSessionId || retained.shiftId != action.shiftId
                ) {
                    error.value = "The saved extension changed state and was kept. Refresh Gaming before continuing."
                    return@launch
                }

                try {
                    val replayed = gamingApi.extendWithPackage(
                        id = retained.serverSessionId,
                        body = retained.toPackageExtendBody(),
                        key = retained.actionId,
                        provenance = outboxProvenanceHeaders(
                            retained.createdAtMillis,
                            retained.actionId,
                        ),
                    )
                    if (replayed.id != retained.serverSessionId || replayed.shiftId != retained.shiftId) {
                        throw IllegalStateException("Paid-extension replay returned a different session")
                    }
                    var confirmed = false
                    if (!appCtx.cacheIsolation.commitIfCurrent(scopeLease) {
                            confirmed = db.gamingDao()
                                .markPackageExtensionConfirmed(retained.actionId) == 1
                        }
                    ) return@launch
                    if (!confirmed) {
                        error.value =
                            "The original charge was confirmed, but this tablet could not store the receipt state. Do not discard it; refresh Gaming."
                        return@launch
                    }
                    notice.value =
                        "The original paid extension was confirmed by the server. It was not discarded or charged again."
                    try {
                        appCtx.sync.refresh("gaming")
                        GamingAlarmReconciler.reconcile(appCtx)
                    } catch (_: Exception) {
                        // Confirmation is already durable. The normal realtime
                        // or screen refresh can repair presentation later.
                    }
                } catch (failure: ApiException) {
                    if (failure.mustPreserveOutbox) {
                        appCtx.cacheIsolation.commitIfCurrent(scopeLease) {
                            db.gamingDao().markPackageExtensionAmbiguous(
                                retained.actionId,
                                packageExtensionFailureMessageForRecovery(failure),
                            )
                        }
                        error.value =
                            "The original charge may have committed. It is retained for exact replay and cannot be discarded."
                        return@launch
                    }
                    if (!failure.provesPackageExtensionUncharged()) {
                        error.value =
                            "The server did not provide safe proof that this charge was refused. Nothing was discarded: ${failure.message ?: "refresh your access and try again."}"
                        return@launch
                    }

                    val authoritative = try {
                        gamingApi.session(retained.serverSessionId)
                    } catch (lookupFailure: Exception) {
                        error.value =
                            "The original request was refused, but the exact server session could not be verified. The blocker was kept."
                        return@launch
                    }
                    if (
                        authoritative.id != retained.serverSessionId ||
                        authoritative.shiftId != retained.shiftId
                    ) {
                        error.value =
                            "The exact server session did not match this saved extension. The blocker was kept for manager review."
                        return@launch
                    }
                    var discarded = false
                    if (!appCtx.cacheIsolation.commitIfCurrent(scopeLease) {
                            discarded = db.gamingDao().discardRejectedPackageExtension(
                                actionId = retained.actionId,
                                reason = normalizedReason,
                                resolvedAtMillis = System.currentTimeMillis(),
                            ) == 1
                        }
                    ) return@launch
                    if (!discarded) {
                        error.value =
                            "The verified rejected extension changed state and was kept. Refresh Gaming before continuing."
                        return@launch
                    }
                    notice.value =
                        "The server definitively refused the original extension. No charge was added; the resolution reason was retained."
                    try {
                        appCtx.sync.refresh("gaming")
                    } catch (_: Exception) {
                        // The retained terminal state is authoritative for the
                        // local blocker; a later refresh repairs the board.
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                appCtx.cacheIsolation.commitIfCurrent(scopeLease) {
                    db.gamingDao().markPackageExtensionAmbiguous(
                        actionId,
                        "The original extension replay ended without a verifiable response.",
                    )
                }
                error.value =
                    "The original charge could not be verified. It is retained for exact replay and cannot be discarded."
            } finally {
                busyStationId.value = null
            }
        }
    }

    fun transfer(session: GameSession, target: Station) {
        val source = state.value.stations.firstOrNull { it.id == session.stationId }
        if (
            source == null || !target.isActive || source.id == target.id || source.type != target.type ||
            state.value.activeFor(target.id) != null
        ) {
            error.value = "That station is not an available transfer target. Refresh Gaming and choose again."
            return
        }
        // A route tuple is not an action identity: A→B, B→A, then A→B is a
        // legitimate third transfer. Give every employee tap its own replay key.
        val actionId = UUID.randomUUID().toString()
        runDirectSessionMutation(
            session = session,
            action = "transferring it",
            successMessage = "Session transferred to ${target.name} without changing its locked price.",
        ) {
            gamingApi.transfer(
                id = session.id,
                body = session.transferBody(target.id),
                key = "gaming-transfer:$actionId",
            )
        }
    }

    fun repairMissingBilling(session: GameSession, amountMinor: Long, reason: String) {
        if (!access.canReconcileLegacySessions) {
            error.value = "Only the protected owner can repair a missing gaming bill."
            return
        }
        if (!requireIdle()) return
        if (!requireNoPackageExtension(session, "repairing billing")) return
        if (!requireNoSessionAddonActions(session, "repairing billing")) return
        if (session.status != "ended" || session.orderId != null || session.amountMinor != null) {
            error.value = "This session no longer has missing billing. Refresh Gaming before repairing it."
            return
        }
        if (amountMinor < 0L) {
            error.value = "The verified bill amount cannot be negative."
            return
        }
        val normalizedReason = reason.trim()
        if (normalizedReason.length !in 3..500) {
            error.value = "Enter a billing repair reason between 3 and 500 characters."
            return
        }
        if (!appCtx.connectivity.online.value) {
            error.value = "Billing repair needs an internet connection. Reconnect, verify the record, then try again."
            return
        }
        val scopeLease = appCtx.cacheIsolation.currentLease() ?: return
        val actionId = UUID.randomUUID().toString()
        busyStationId.value = session.stationId
        error.value = null
        notice.value = null
        viewModelScope.launch {
            try {
                val repaired = gamingApi.repairBilling(
                    id = session.id,
                    body = SessionBillingRepairBody(
                        expectedAmountMinor = JsonNull,
                        amountMinor = amountMinor,
                        reason = normalizedReason,
                    ),
                    key = "gaming-billing-repair:$actionId",
                )
                if (appCtx.cacheIsolation.commitIfCurrent(scopeLease) {
                        val cache = repaired.toCacheEntity()
                        db.gamingDao().upsertAuthoritativeSession(cache)
                    }
                ) {
                    notice.value = "Missing billing repaired to ${amountMinor.asRupees()}. Review it before sending to POS."
                }
            } catch (e: ApiException) {
                error.value = if (e.isAmbiguous) {
                    "The repair response was lost. Do not enter another amount; refresh Gaming to confirm the audited result."
                } else {
                    e.message ?: "The server refused this billing repair. Refresh and verify the session record."
                }
            } catch (_: Exception) {
                error.value = "Billing repair could not be confirmed. Do not retry until Gaming has been refreshed."
            } finally {
                busyStationId.value = null
            }
        }
    }

    private fun runDirectSessionMutation(
        session: GameSession,
        action: String,
        successMessage: String,
        request: suspend () -> GameSession,
    ) {
        if (!requireWrite() || !requireIdle()) return
        if (session.status !in setOf("active", "paused")) {
            error.value = "This session is no longer running. Refresh Gaming before $action."
            return
        }
        if (!requireNoPackageExtension(session, action)) return
        if (!requireCurrentShiftSession(session, action)) return
        if (!appCtx.connectivity.online.value) {
            error.value =
                "This change needs an internet connection to prevent conflicts with another tablet. Reconnect, then try again."
            return
        }
        val scopeLease = appCtx.cacheIsolation.currentLease() ?: run {
            error.value = "This account session changed. Sign in again before $action."
            return
        }
        busyStationId.value = session.stationId
        error.value = null
        notice.value = null
        viewModelScope.launch {
            try {
                val updated = request()
                if (storeRunningResponse(scopeLease, updated)) {
                    notice.value = successMessage
                }
            } catch (e: ApiException) {
                val baseMessage = if (e.isAmbiguous) {
                    "The server response was lost, so this change is not confirmed. Refresh Gaming before trying again."
                } else {
                    e.message ?: "The server refused this gaming change. Refresh and try again."
                }
                error.value = if (e.status == 409) {
                    when (val refreshed = appCtx.sync.refresh("gaming")) {
                        is ResourceRefreshResult.Refreshed ->
                            "$baseMessage Gaming was refreshed; review the current station before acting again."
                        is ResourceRefreshResult.Failed ->
                            "$baseMessage The automatic refresh also failed: ${refreshed.userMessage}"
                        is ResourceRefreshResult.Skipped ->
                            "$baseMessage This account could not refresh Gaming automatically."
                    }
                } else {
                    baseMessage
                }
            } catch (_: Exception) {
                error.value = "This gaming change could not be completed. Check the connection, refresh, and try again."
            } finally {
                busyStationId.value = null
            }
        }
    }

    private suspend fun storeRunningResponse(
        scopeLease: CacheScopeLease,
        updated: GameSession,
    ): Boolean = appCtx.cacheIsolation.commitIfCurrent(scopeLease) {
        val cache = updated.toCacheEntity()
        db.withTransaction {
            db.gamingDao().upsertSessionCache(listOf(cache))
            db.gamingDao().updateRunningLocalSnapshot(
                serverId = updated.id,
                stationId = updated.stationId,
                shiftId = updated.shiftId,
                status = updated.status,
                timerMinutes = updated.timerMinutes,
                timerEndsAtMillis = cache.timerEndsAtMillis,
                amountMinor = updated.amountMinor,
                ratePerHourMinor = updated.ratePerHourMinor,
                packageId = updated.packageId,
                billingMode = updated.billingMode,
                packagePriceMinor = updated.packagePriceMinorSnapshot,
                packageDurationMinutes = updated.packageDurationMinutesSnapshot,
                packageVariant = updated.packageVariantSnapshot,
                packageStationTypeSnapshot = updated.packageStationTypeSnapshot,
                extraControllers = updated.extraControllers,
            )
        }
        GamingAlarmReconciler.reconcile(appCtx)
    }

    fun reconcileToPos(session: GameSession, reason: String) {
        if (!access.canReconcileLegacySessions) {
            error.value = "Only a protected owner can reconcile a session from a closed shift."
            return
        }
        if (!session.canSendToPos(state.value.hasActiveAddons(session)) || !requireIdle()) return
        if (!requireNoPackageExtension(session, "reconciling the bill to POS")) return
        if (!requireNoSessionAddonActions(session, "reconciling the bill to POS")) return
        val targetShiftId = state.value.activeShiftId
        if (targetShiftId == null) {
            error.value = "Open the receiving POS shift before reconciling this session."
            return
        }
        val normalizedReason = reason.trim()
        if (normalizedReason.length !in 3..500) {
            error.value = "Enter a reconciliation reason between 3 and 500 characters."
            return
        }
        if (!appCtx.connectivity.online.value) {
            error.value = "Reconciliation needs an internet connection. Reconnect, then try again."
            return
        }
        val scopeLease = appCtx.cacheIsolation.currentLease() ?: return
        busyStationId.value = session.stationId
        error.value = null
        notice.value = null
        viewModelScope.launch {
            try {
                val result = gamingApi.reconcileToPos(
                    session.id,
                    SessionReconcileBody(targetShiftId, normalizedReason),
                )
                if (appCtx.cacheIsolation.commitIfCurrent(scopeLease) {
                        db.withTransaction {
                            db.gamingDao().localSessionByEitherId(session.id)?.let { local ->
                                db.gamingDao().markSessionSent(
                                    local.localId,
                                    result.orderId,
                                    result.amountMinor,
                                )
                            }
                            db.gamingDao().markCachedSessionSent(
                                session.id,
                                result.orderId,
                                result.amountMinor,
                            )
                        }
                    }
                ) {
                    notice.value = if (result.alreadyLinked) {
                        "This session was already reconciled and is available in POS."
                    } else {
                        "Session reconciled to the current shift and sent to POS."
                    }
                    appCtx.sync.requestSync()
                }
            } catch (e: ApiException) {
                error.value = if (e.isAmbiguous) {
                    "The reconciliation response was lost. Refresh Gaming and POS before retrying; the server will not create a duplicate."
                } else {
                    e.message ?: "The server refused this reconciliation."
                }
            } catch (_: Exception) {
                error.value = "Reconciliation could not be completed. Refresh Gaming and POS, then try again."
            } finally {
                busyStationId.value = null
            }
        }
    }

    /**
     * Gives staff an audited escape for mistaken stopped sessions, including the
     * mandatory path for a zero-value session that cannot become a POS order.
     * Cancellation is deliberately online-only: the server records who cancelled
     * it and the required reason, and response-loss replay is naturally safe
     * because cancelled is terminal.
     */
    fun cancelUnbilled(session: GameSession, reason: String) {
        if (!requireWrite()) return
        if (!session.canCancelUnbilled() || !requireIdle()) return
        if (!requireNoPackageExtension(session, "voiding the session")) return
        if (!requireNoSessionAddonActions(session, "voiding the whole session")) return
        if (!requireNoActiveSessionAddons(session)) return
        if (
            session.authority(state.value.activeShiftId) != GamingSessionAuthority.CURRENT_SHIFT &&
            !access.canReconcileLegacySessions
        ) {
            requireCurrentShiftSession(session, "voiding it")
            return
        }
        val normalizedReason = reason.trim()
        if (normalizedReason.isEmpty()) {
            error.value = "Enter a reason before cancelling this session."
            return
        }
        if (normalizedReason.length > 500) {
            error.value = "Cancellation reason must be 500 characters or fewer."
            return
        }
        if (!appCtx.connectivity.online.value) {
            error.value =
                "Cancellation needs an internet connection so the reason is recorded. Reconnect, then try again."
            return
        }
        val scopeLease = appCtx.cacheIsolation.currentLease() ?: return

        busyStationId.value = session.stationId
        error.value = null
        notice.value = null
        viewModelScope.launch {
            try {
                val dao = db.gamingDao()
                var serverId: String? = null
                if (!appCtx.cacheIsolation.commitIfCurrent(scopeLease) {
                        val local = dao.localSessionByEitherId(session.id)
                        serverId = local?.serverId ?: session.id.takeIf { local == null }
                    }
                ) return@launch
                if (serverId == null) {
                    error.value =
                        "This session has not been confirmed by the server yet. Refresh Gaming before cancelling it."
                    return@launch
                }
                val cancelled = gamingApi.cancel(
                    id = requireNotNull(serverId),
                    body = SessionCancelBody(normalizedReason),
                )
                if (appCtx.cacheIsolation.commitIfCurrent(scopeLease) {
                        dao.upsertAuthoritativeSession(cancelled.toCacheEntity())
                        GamingAlarmReconciler.reconcile(appCtx)
                    }
                ) {
                    notice.value = "Session voided with its reason preserved in the audit trail."
                }
            } catch (e: ApiException) {
                error.value = if (e.isAmbiguous) {
                    "The server response was lost, so cancellation is not confirmed. This session remains blocked " +
                        "from POS and new play. Reconnect and try Cancel again; do not create a replacement session."
                } else {
                    "Cancellation was refused: ${e.message ?: "check the shift, branch, terminal, and session state."}"
                }
            } catch (_: Exception) {
                error.value =
                    "Cancellation could not be recorded. The session remains blocked; refresh and try again."
            } finally {
                busyStationId.value = null
            }
        }
    }

    fun dismissError() { error.value = null }
    /** A cancelled older snackbar must never clear a newer action result. */
    fun dismissNotice(expected: String) {
        notice.compareAndSet(expected, null)
    }
}

private fun GamingStationEntity.toStation() = Station(
    id = id,
    code = code,
    name = name,
    type = type,
    ratePerHourMinor = ratePerHourMinor,
    isActive = isActive,
)

private fun GamingPackageCacheEntity.toGamingPackage() = GamingPackage(
    id = id,
    stationType = stationType,
    variant = variant,
    kind = kind,
    name = name,
    durationMinutes = durationMinutes,
    priceMinor = priceMinor,
)

private fun GamingSessionCacheEntity.toGameSession() = GameSession(
    id = id,
    stationId = stationId,
    shiftId = shiftId,
    status = status,
    startAt = Instant.ofEpochMilli(startAtMillis).toString(),
    endAt = endAtMillis?.let { Instant.ofEpochMilli(it).toString() },
    timerMinutes = timerMinutes,
    timerEndsAt = timerEndsAtMillis?.let { Instant.ofEpochMilli(it).toString() },
    billableMinutes = billableMinutes,
    amountMinor = amountMinor,
    ratePerHourMinor = ratePerHourMinor,
    packageId = packageId,
    billingMode = billingMode,
    packagePriceMinorSnapshot = packagePriceMinorSnapshot,
    packageDurationMinutesSnapshot = packageDurationMinutesSnapshot,
    packageVariantSnapshot = packageVariantSnapshot,
    packageStationTypeSnapshot = packageStationTypeSnapshot,
    extraControllers = extraControllers,
    customerName = customerName,
    customerPhone = customerPhone,
    orderId = orderId,
)

internal fun GameSession.toCacheEntity() = GamingSessionCacheEntity(
    id = id,
    stationId = stationId,
    shiftId = shiftId,
    status = status,
    startAtMillis = Instant.parse(startAt).toEpochMilli(),
    endAtMillis = endAt?.let { Instant.parse(it).toEpochMilli() },
    timerMinutes = timerMinutes,
    timerEndsAtMillis = timerEndsAt?.let { Instant.parse(it).toEpochMilli() },
    billableMinutes = billableMinutes,
    amountMinor = amountMinor,
    ratePerHourMinor = ratePerHourMinor,
    packageId = packageId,
    billingMode = billingMode,
    packagePriceMinorSnapshot = packagePriceMinorSnapshot,
    packageDurationMinutesSnapshot = packageDurationMinutesSnapshot,
    packageVariantSnapshot = packageVariantSnapshot,
    packageStationTypeSnapshot = packageStationTypeSnapshot,
    extraControllers = extraControllers,
    customerName = customerName,
    customerPhone = customerPhone,
    orderId = orderId,
)

private fun LocalGamingSessionEntity.toGameSession() = GameSession(
    id = serverId ?: localId,
    stationId = stationId,
    shiftId = shiftId,
    status = status,
    startAt = Instant.ofEpochMilli(startedAtMillis).toString(),
    endAt = endAtMillis?.let { Instant.ofEpochMilli(it).toString() },
    timerMinutes = timerMinutes,
    timerEndsAt = timerEndsAtMillis?.let { Instant.ofEpochMilli(it).toString() },
    billableMinutes = billableMinutes,
    amountMinor = amountMinor,
    ratePerHourMinor = ratePerHourMinor,
    packageId = packageId,
    billingMode = billingMode,
    packagePriceMinorSnapshot = packagePriceMinor,
    packageDurationMinutesSnapshot = packageDurationMinutes,
    packageVariantSnapshot = packageVariant,
    packageStationTypeSnapshot = packageStationTypeSnapshot,
    extraControllers = extraControllers,
    customerName = null,
    customerPhone = customerPhone,
    orderId = orderId,
    localState = state,
    lastError = lastError,
    legacyOriginalCapturedStartAt = legacyOriginalCapturedStartAtMillis
        ?.let { Instant.ofEpochMilli(it).toString() },
    legacyOriginalCapturedStopAt = legacyOriginalCapturedStopAtMillis
        ?.let { Instant.ofEpochMilli(it).toString() },
    legacyResolution = legacyResolution,
    legacyResolutionReason = legacyResolutionReason,
    legacyResolutionReferenceOrderId = legacyResolutionReferenceOrderId,
    legacyResolutionAttemptState = legacyResolutionAttemptState,
    legacyResolutionError = legacyResolutionError,
)
