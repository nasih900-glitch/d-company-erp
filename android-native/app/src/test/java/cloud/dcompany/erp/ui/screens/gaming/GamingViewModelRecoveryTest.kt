package cloud.dcompany.erp.ui.screens.gaming

import cloud.dcompany.erp.core.auth.TerminalPurpose
import cloud.dcompany.erp.core.auth.ValidatedTerminalDisplay
import cloud.dcompany.erp.core.db.GamingLegacyResolution
import cloud.dcompany.erp.core.db.GamingLegacyResolutionAttemptState
import cloud.dcompany.erp.core.db.GamingSessionState
import cloud.dcompany.erp.core.db.RecoveredLegacyServerDisposition
import cloud.dcompany.erp.core.net.ApiException
import cloud.dcompany.erp.core.net.MeResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class GamingViewModelRecoveryTest {

    @Test
    fun delegatedLegacyRecoveryAcceptsOnlySameScopeProtectedAuditOwner() {
        val staff = profile(
            userId = "staff-user",
            companyId = "company-a",
            branchId = "branch-a",
            protected = false,
        )
        val terminal = ValidatedTerminalDisplay(
            "terminal-a",
            "Main POS",
            "branch-a",
            TerminalPurpose.HYBRID,
        )
        val sameBranchOwner = profile(
            userId = "owner-same-branch",
            companyId = "company-a",
            branchId = "branch-a",
            protected = true,
        )
        val globalOwner = profile(
            userId = "owner-global",
            companyId = "company-a",
            branchId = null,
            protected = true,
        )

        assertEquals(null, legacyRecoveryApproverError(staff, sameBranchOwner, terminal))
        assertEquals(null, legacyRecoveryApproverError(staff, globalOwner, terminal))
        // The actor retained with the local resolution is the validated
        // transient owner, never the staff member keeping the workspace open.
        assertEquals("owner-same-branch", sameBranchOwner.userId)
        assertFalse(sameBranchOwner.userId == staff.userId)

        assertTrue(
            legacyRecoveryApproverError(
                staff,
                profile("not-owner", "company-a", "branch-a", protected = false),
                terminal,
            ).orEmpty().contains("not a protected owner"),
        )
        assertTrue(
            legacyRecoveryApproverError(
                staff,
                profile("other-company", "company-b", "branch-a", protected = true),
                terminal,
            ).orEmpty().contains("different company"),
        )
        assertTrue(
            legacyRecoveryApproverError(
                staff,
                profile("other-branch", "company-a", "branch-b", protected = true),
                terminal,
            ).orEmpty().contains("different branch"),
        )
        assertTrue(
            legacyRecoveryApproverError(
                staff,
                sameBranchOwner,
                terminal.copy(branchId = "branch-b"),
            ).orEmpty().contains("terminal no longer matches"),
        )
    }

    @Test
    fun ambiguousLegacyRecoveryCanOnlyReplayWithItsCapturedApprover() {
        assertTrue(
            legacyResolutionApproverMatches(
                capturedApproverUserId = "owner-1",
                presentedApproverUserId = "owner-1",
            ),
        )
        assertFalse(
            legacyResolutionApproverMatches(
                capturedApproverUserId = "owner-1",
                presentedApproverUserId = "owner-2",
            ),
        )
        assertFalse(
            legacyResolutionApproverMatches(
                capturedApproverUserId = null,
                presentedApproverUserId = "owner-1",
            ),
        )
        assertFalse(
            legacyResolutionApproverMatches(
                capturedApproverUserId = "   ",
                presentedApproverUserId = "owner-1",
            ),
        )
    }

    @Test
    fun onlyTypedRolledBackBusinessRefusalUnlocksLegacyDecisionEditing() {
        listOf(
            ApiException("paid order is invalid", status = 422, code = "business_rule"),
            ApiException(
                "no exact accepted start",
                status = 422,
                code = "gaming_legacy_server_session_not_found",
            ),
            ApiException(
                "captured stop needs owner review",
                status = 422,
                code = "gaming_legacy_stop_owner_review_required",
            ),
        ).forEach { failure ->
            assertEquals(
                GamingLegacyResolutionAttemptState.REJECTED,
                legacyResolutionFailureState(failure),
            )
        }
        listOf(
            ApiException("different owner", status = 409, code = "idempotency_conflict"),
            ApiException("still processing", status = 409, code = "idempotency_in_progress"),
            ApiException("session conflict", status = 409, code = "conflict"),
            ApiException("owner no longer authorised", status = 403, code = "forbidden"),
            ApiException("response lost"),
        ).forEach { failure ->
            assertEquals(
                GamingLegacyResolutionAttemptState.AMBIGUOUS,
                legacyResolutionFailureState(failure),
            )
        }
    }

    @Test
    fun safeRecoveryProbeRefusalExplainsThatOwnerMayChooseAnotherResolution() {
        assertTrue(
            legacyResolutionRejectedMessage(
                ApiException(
                    "not found",
                    status = 422,
                    code = "gaming_legacy_server_session_not_found",
                ),
            ).contains("No audit receipt was created"),
        )
        assertTrue(
            legacyResolutionRejectedMessage(
                ApiException(
                    "chronology review",
                    status = 422,
                    code = "gaming_legacy_stop_owner_review_required",
                ),
            ).contains("Link the exact paid POS order or confirm no play"),
        )
    }

    @Test
    fun legacyRecoveryUsesConfirmedServerMappingForAnOfflineOpenedShift() {
        val localShiftId = "11111111-1111-4111-8111-111111111111"
        val serverShiftId = "22222222-2222-4222-8222-222222222222"

        assertEquals(
            serverShiftId,
            resolvedLegacyCapturedServerShiftId(
                capturedShiftId = localShiftId,
                localMappingFound = true,
                mappedServerShiftId = serverShiftId,
            ),
        )
        assertEquals(
            serverShiftId,
            resolvedLegacyCapturedServerShiftId(
                capturedShiftId = serverShiftId,
                localMappingFound = false,
                mappedServerShiftId = null,
            ),
        )
        assertEquals(
            null,
            resolvedLegacyCapturedServerShiftId(
                capturedShiftId = localShiftId,
                localMappingFound = true,
                mappedServerShiftId = null,
            ),
        )
        assertEquals(
            null,
            resolvedLegacyCapturedServerShiftId(
                capturedShiftId = "not-a-uuid",
                localMappingFound = false,
                mappedServerShiftId = null,
            ),
        )
        assertEquals(
            null,
            resolvedLegacyCapturedServerShiftId(
                capturedShiftId = null,
                localMappingFound = false,
                mappedServerShiftId = null,
            ),
        )
    }

    @Test
    fun recoveredLegacyReceiptAcceptsTransferredOrRetiredPackageButRejectsUnsafeEvidence() {
        val body = legacyBody(referenceOrderId = null).copy(
            resolution = GamingLegacyResolution.SERVER_SESSION_RECOVERED,
        )
        val retiredTransferred = recoveredReceipt(
            body = body,
            server = recoveredSession(
                stationId = "99999999-9999-4999-8999-999999999999",
                packageId = null,
                snapshotsComplete = false,
            ),
        )
        assertEquals(
            null,
            legacyResolutionReceiptError(body, retiredTransferred, BRANCH_ID, TERMINAL_ID),
        )

        val partial = retiredTransferred.copy(
            serverSession = retiredTransferred.serverSession!!.copy(
                packagePriceMinorSnapshot = 15_000,
            ),
        )
        assertTrue(
            legacyResolutionReceiptError(body, partial, BRANCH_ID, TERMINAL_ID)
                .orEmpty().contains("snapshots are incomplete"),
        )
        assertTrue(
            legacyResolutionReceiptError(
                body,
                retiredTransferred.copy(stationId = "wrong-original-station"),
                BRANCH_ID,
                TERMINAL_ID,
            ).orEmpty().contains("scope"),
        )
        assertTrue(
            legacyResolutionReceiptError(
                body,
                retiredTransferred.copy(
                    serverSession = retiredTransferred.serverSession!!.copy(shiftId = "wrong-shift"),
                ),
                BRANCH_ID,
                TERMINAL_ID,
            ).orEmpty().contains("server shift"),
        )
    }

    @Test
    fun recoveredLegacyManualBillMustCarryExactLinkedOrder() {
        val orderId = "44444444-4444-4444-8444-444444444444"
        val body = legacyBody(referenceOrderId = orderId)
        val exact = recoveredReceipt(
            body = body,
            referenceOrderId = orderId,
            server = recoveredSession(orderId = orderId),
        )
        assertEquals(null, legacyResolutionReceiptError(body, exact, BRANCH_ID, TERMINAL_ID))
        assertTrue(
            legacyResolutionReceiptError(
                body,
                exact.copy(serverSession = exact.serverSession!!.copy(orderId = null)),
                BRANCH_ID,
                TERMINAL_ID,
            ).orEmpty().contains("exact verified POS order"),
        )
    }

    @Test
    fun recoveredHourlyReceiptAndStopChronologyFailClosedWithoutASettledOrder() {
        val body = legacyBody(referenceOrderId = null).copy(
            packageId = null,
            expectedRatePerHourMinor = 15_000,
            resolution = GamingLegacyResolution.SERVER_SESSION_RECOVERED,
        )
        val server = GameSession(
            id = "66666666-6666-4666-8666-666666666666",
            stationId = ORIGINAL_STATION_ID,
            shiftId = SHIFT_ID,
            status = "active",
            startAt = "2026-08-25T10:30:00Z",
            ratePerHourMinor = 15_000,
            billingMode = "hourly",
        )
        val receipt = recoveredReceipt(body = body, server = server)
        assertEquals(null, legacyResolutionReceiptError(body, receipt, BRANCH_ID, TERMINAL_ID))
        assertEquals(
            RecoveredLegacyServerDisposition.RESTORE_CAPTURED_STOP,
            recoveredLegacyServerDisposition(
                server = server,
                originalCapturedStopAtMillis = Instant.parse("2026-08-25T10:45:00Z").toEpochMilli(),
                retainedRatePerHourMinor = 15_000,
                verifiedReferenceOrderId = null,
            ),
        )
        assertEquals(
            RecoveredLegacyServerDisposition.RETAIN_BILLING_REVIEW,
            recoveredLegacyServerDisposition(
                server = server,
                originalCapturedStopAtMillis = Instant.parse("2026-08-25T10:15:00Z").toEpochMilli(),
                retainedRatePerHourMinor = 15_000,
                verifiedReferenceOrderId = null,
            ),
        )
        assertEquals(
            RecoveredLegacyServerDisposition.RETAIN_BILLING_REVIEW,
            recoveredLegacyServerDisposition(
                server = server,
                originalCapturedStopAtMillis = null,
                retainedRatePerHourMinor = 12_000,
                verifiedReferenceOrderId = null,
            ),
        )
    }

    @Test
    fun confirmedNoPlayReceiptRejectsRunningRecoveryButAcceptsAuthoritativeCancellation() {
        val body = legacyBody(referenceOrderId = null)
        val active = recoveredReceipt(
            body = body,
            server = recoveredSession().copy(status = "active"),
        )

        assertTrue(
            legacyResolutionReceiptError(body, active, BRANCH_ID, TERMINAL_ID)
                .orEmpty().contains("still running"),
        )
        assertEquals(
            null,
            legacyResolutionReceiptError(
                body,
                active.copy(serverSession = active.serverSession!!.copy(status = "cancelled")),
                BRANCH_ID,
                TERMINAL_ID,
            ),
        )
    }

    @Test
    fun verifiedManualOrderMakesPreServerHourlyStopTerminalisationSafe() {
        val orderId = "44444444-4444-4444-8444-444444444444"
        val server = GameSession(
            id = "66666666-6666-4666-8666-666666666666",
            stationId = ORIGINAL_STATION_ID,
            shiftId = SHIFT_ID,
            status = "active",
            startAt = "2026-08-25T10:30:00Z",
            ratePerHourMinor = 15_000,
            billingMode = "hourly",
            orderId = orderId,
        )
        assertEquals(
            RecoveredLegacyServerDisposition.RESTORE_CAPTURED_STOP,
            recoveredLegacyServerDisposition(
                server = server,
                originalCapturedStopAtMillis = Instant.parse("2026-08-25T10:15:00Z").toEpochMilli(),
                retainedRatePerHourMinor = 15_000,
                verifiedReferenceOrderId = orderId,
            ),
        )
    }

    @Test
    fun onlyLedgerCheckedExtensionRefusalProvesNoCharge() {
        assertFalse(ApiException("conflict", status = 409).provesPackageExtensionUncharged())
        assertFalse(
            ApiException(
                "idempotency body mismatch",
                status = 409,
                code = "idempotency_conflict",
            ).provesPackageExtensionUncharged(),
        )
        assertFalse(ApiException("validation", status = 422).provesPackageExtensionUncharged())
        assertTrue(
            ApiException(
                "ledger checked; no extension applied",
                status = 409,
                code = "gaming_extension_not_applied",
            ).provesPackageExtensionUncharged(),
        )
    }

    @Test
    fun `authenticated feature owner can construct gaming view model without application extras`() {
        val constructor = GamingViewModel::class.java.getDeclaredConstructor()

        assertEquals(0, constructor.parameterCount)
    }

    @Test
    fun recoveredLegsExposeOnlyTheirSafeAction() {
        val failedStart = session(
            id = "start",
            status = "start_failed",
            localState = GamingSessionState.START_REJECTED,
        )
        val failedStop = session(
            id = "stop",
            status = "active",
            localState = GamingSessionState.STOP_REJECTED,
        )
        val failedSend = session(
            id = "send",
            status = "ended",
            localState = GamingSessionState.SEND_REJECTED,
        )
        val sendPending = session(
            id = "send-pending",
            status = "ended",
            localState = GamingSessionState.SEND_PENDING,
        )
        val zeroValue = session(
            id = "zero",
            status = "ended",
            localState = GamingSessionState.SEND_REJECTED,
            amountMinor = 0,
        )
        val paused = session(
            id = "paused",
            status = "paused",
            localState = GamingSessionState.START_SYNCED,
        )

        assertTrue(failedStart.requiresProtectedStartReview())
        assertTrue(failedStart.canResolveRejectedStart())
        assertFalse(failedStart.canRequestStop())
        assertFalse(failedStart.canSendToPos())

        assertTrue(failedStop.canRequestStop())
        assertFalse(failedStop.canSendToPos())

        assertFalse(failedSend.canRequestStop())
        assertTrue(failedSend.canSendToPos())
        assertFalse(sendPending.canSendToPos())
        assertFalse(zeroValue.canSendToPos())
        assertTrue(zeroValue.canCancelUnbilled())
        assertTrue(failedSend.canCancelUnbilled())
        assertFalse(sendPending.canCancelUnbilled())
        assertTrue(paused.canRequestStop())

        val ui = GamingUiState(
            sessions = listOf(failedStart, failedStop, failedSend, sendPending, zeroValue, paused),
        )
        assertEquals("start", ui.activeFor("station-start")?.id)
        assertEquals("stop", ui.activeFor("station-stop")?.id)
        assertEquals("send", ui.activeFor("station-send")?.id)
        assertEquals("zero", ui.activeFor("station-zero")?.id)
        assertEquals("paused", ui.activeFor("station-paused")?.id)
        assertEquals(listOf("send"), ui.readyForPos.map { it.id })
        assertEquals(listOf("zero"), ui.needsCancellation.map { it.id })
    }

    @Test
    fun rejectedExtensionRemainsReachableAfterItsPaidSessionLeavesTheBoard() {
        val orphan = PackageExtensionActionUi(
            actionId = "action-orphan",
            serverSessionId = "session-paid-on-another-device",
            shiftId = "shift-1",
            state = "rejected",
            lastError = "ledger checked; no extension applied",
        )
        val attached = PackageExtensionActionUi(
            actionId = "action-attached",
            serverSessionId = "session-visible",
            shiftId = "shift-1",
            state = "ambiguous",
            lastError = "response lost",
        )
        val ui = GamingUiState(
            sessions = listOf(
                session(
                    id = "session-visible",
                    status = "active",
                    localState = GamingSessionState.START_SYNCED,
                ),
            ),
            packageExtensionActions = listOf(orphan, attached),
        )

        assertEquals(listOf("action-orphan"), ui.orphanedPackageExtensionActions().map { it.actionId })
        assertTrue(
            canResolveRejectedExtensionForShift(
                actionShiftId = "closed-shift",
                canWrite = true,
            ),
        )
        assertFalse(
            canResolveRejectedExtensionForShift(
                actionShiftId = null,
                canWrite = true,
            ),
        )
        assertFalse(
            canResolveRejectedExtensionForShift(
                actionShiftId = "closed-shift",
                canWrite = false,
            ),
        )
    }

    @Test
    fun `cold gaming board distinguishes loading from recoverable failure`() {
        val loading = GamingUiState(refreshing = true)
        val failed = GamingUiState(
            refreshing = false,
            refreshError = "Could not reach the ERP.",
        )
        val syncedEmpty = GamingUiState(everSynced = true, refreshing = false)

        assertTrue(loading.initialLoading)
        assertFalse(loading.initialLoadFailed)

        assertFalse(failed.initialLoading)
        assertTrue(failed.initialLoadFailed)

        val betweenRefreshAndMeta = GamingUiState(refreshing = false, refreshError = null)
        assertFalse(betweenRefreshAndMeta.initialLoadFailed)

        assertFalse(syncedEmpty.initialLoading)
        assertFalse(syncedEmpty.initialLoadFailed)
    }

    @Test
    fun `legacy recovery failure does not prevent authoritative refresh`() = runBlocking {
        val recoveryFailure = IllegalStateException("backup repair failed")
        var observedFailure: Exception? = null
        var refreshed = false

        recoverGamingThenRefresh(
            recover = { throw recoveryFailure },
            refresh = { refreshed = true },
            onRecoveryFailure = { observedFailure = it },
        )

        assertSame(recoveryFailure, observedFailure)
        assertTrue(refreshed)
    }

    @Test
    fun `legacy recovery cancellation is preserved and does not start refresh`() {
        val cancellation = CancellationException("view model cleared")
        var refreshed = false

        val thrown = assertThrows(CancellationException::class.java) {
            runBlocking {
                recoverGamingThenRefresh(
                    recover = { throw cancellation },
                    refresh = { refreshed = true },
                )
            }
        }

        assertSame(cancellation, thrown)
        assertFalse(refreshed)
    }

    private fun session(
        id: String,
        status: String,
        localState: String,
        amountMinor: Long = 37_500,
    ) = GameSession(
        id = id,
        stationId = "station-$id",
        status = status,
        startAt = "2026-08-25T10:00:00Z",
        endAt = if (status == "ended") "2026-08-25T10:15:00Z" else null,
        billableMinutes = if (status == "ended") 15 else null,
        amountMinor = if (status == "ended") amountMinor else null,
        localState = localState,
        lastError = "legacy failure",
    )

    private fun legacyBody(referenceOrderId: String?) = LegacyGamingOutboxResolutionBody(
        localActionId = ACTION_ID,
        stationId = ORIGINAL_STATION_ID,
        shiftId = SHIFT_ID,
        capturedStartedAt = "2026-08-25T10:00:00Z",
        capturedStoppedAt = "2026-08-25T10:30:00Z",
        packageId = PACKAGE_ID,
        resolution = if (referenceOrderId == null) {
            GamingLegacyResolution.CONFIRMED_NO_PLAY
        } else {
            GamingLegacyResolution.MANUAL_BILL_RECORDED
        },
        referenceOrderId = referenceOrderId,
        reason = "Owner verified retained evidence",
    )

    private fun recoveredReceipt(
        body: LegacyGamingOutboxResolutionBody,
        referenceOrderId: String? = null,
        server: GameSession,
    ) = LegacyGamingOutboxResolutionReceipt(
        receiptId = 123,
        localActionId = body.localActionId,
        stationId = body.stationId,
        branchId = BRANCH_ID,
        terminalId = TERMINAL_ID,
        packageId = body.packageId,
        resolution = GamingLegacyResolution.SERVER_SESSION_RECOVERED,
        referenceOrderId = referenceOrderId,
        serverSession = server,
        resolvedAt = "2026-08-25T11:00:00Z",
    )

    private fun recoveredSession(
        stationId: String = ORIGINAL_STATION_ID,
        packageId: String? = PACKAGE_ID,
        snapshotsComplete: Boolean = true,
        orderId: String? = null,
    ) = GameSession(
        id = "66666666-6666-4666-8666-666666666666",
        stationId = stationId,
        shiftId = SHIFT_ID,
        status = "active",
        startAt = "2026-08-25T10:00:00Z",
        amountMinor = 15_000,
        packageId = packageId,
        billingMode = "package",
        packagePriceMinorSnapshot = 15_000L.takeIf { snapshotsComplete },
        packageDurationMinutesSnapshot = 60.takeIf { snapshotsComplete },
        packageVariantSnapshot = "solo".takeIf { snapshotsComplete },
        packageStationTypeSnapshot = "ps5".takeIf { snapshotsComplete },
        orderId = orderId,
    )

    private companion object {
        const val ACTION_ID = "11111111-1111-4111-8111-111111111111"
        const val ORIGINAL_STATION_ID = "22222222-2222-4222-8222-222222222222"
        const val SHIFT_ID = "33333333-3333-4333-8333-333333333333"
        const val PACKAGE_ID = "55555555-5555-4555-8555-555555555555"
        const val BRANCH_ID = "77777777-7777-4777-8777-777777777777"
        const val TERMINAL_ID = "88888888-8888-4888-8888-888888888888"
    }

    private fun profile(
        userId: String,
        companyId: String,
        branchId: String?,
        protected: Boolean,
    ) = MeResponse(
        userId = userId,
        email = "$userId@example.test",
        name = userId,
        roles = if (protected) listOf("owner") else listOf("staff"),
        protectedAccess = protected,
        auditAccess = protected,
        companyId = companyId,
        branchId = branchId,
        effectivePermissions = if (protected) listOf("admin.audit.read") else listOf("gaming.write"),
    )
}
