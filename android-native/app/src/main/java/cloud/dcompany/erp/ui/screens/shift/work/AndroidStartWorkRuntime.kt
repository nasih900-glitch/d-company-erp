package cloud.dcompany.erp.ui.screens.shift.work

import cloud.dcompany.erp.DCompanyApp
import cloud.dcompany.erp.core.auth.CacheScopeLease
import cloud.dcompany.erp.core.auth.EffectivePermissions
import cloud.dcompany.erp.core.auth.ErpPermission
import cloud.dcompany.erp.core.auth.SessionRefreshLease
import cloud.dcompany.erp.core.handover.OriginalOperationBinding
import cloud.dcompany.erp.core.handover.ReviewedStartWorkResolution
import cloud.dcompany.erp.core.handover.knownAttempt
import cloud.dcompany.erp.core.net.ApiClient
import cloud.dcompany.erp.core.net.MeResponse
import cloud.dcompany.erp.ui.screens.shift.ShiftApi
import cloud.dcompany.erp.ui.screens.staff.time.staffTimeAuthBinding
import java.time.OffsetDateTime
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

internal class AndroidStartWorkRuntime(private val profile: MeResponse?, private val scope: CacheScopeLease?,
    private val app: DCompanyApp = DCompanyApp.instance) : StartWorkRuntime {
    private data class Captured(val value: StartWorkAuthority, val session: SessionRefreshLease,
        val original: OriginalOperationBinding)
    private var captured: Captured? = null
    private fun sameProfile(other: MeResponse) = profile?.let { expected ->
        expected.userId == other.userId && expected.companyId == other.companyId && expected.branchId == other.branchId &&
            expected.roles.sorted() == other.roles.sorted() && expected.effectivePermissions?.sorted() == other.effectivePermissions?.sorted() &&
            expected.protectedAccess == other.protectedAccess && expected.auditAccess == other.auditAccess
    } == true
    override fun current() = app.cacheIsolation.currentLease() == scope && app.shiftCache.profile.value == profile
    override fun online() = app.connectivity.online.value
    override suspend fun capture(): StartWorkAuthority {
        check(current() && online())
        val expected = requireNotNull(profile); val lease = requireNotNull(scope)
        val actual = ApiClient.api.me()
        check(current() && sameProfile(actual))
        val permissions = EffectivePermissions.from(actual)
        check(permissions.has(ErpPermission.PosShiftOpen) && permissions.has(ErpPermission.StaffAttendanceWrite))
        val terminal = requireNotNull(app.terminalStore.activeValidatedTerminal.value)
        val branch = lease.scope.branchId ?: terminal.branchId
        check(lease.scope.terminalId == terminal.terminalId)
        val verified = ApiClient.api.terminals(branch).single { it.id == terminal.terminalId }
        check(current() && verified.branchId == branch && verified.onlineStartWorkSupported && verified.purpose == "hybrid")
        val session = requireNotNull(app.tokens.refreshLease())
        val original = requireNotNull(app.handoverOperations.captureOriginal(actual, lease))
        val binding = requireNotNull(staffTimeAuthBinding(session.accessToken, session.refreshToken, actual))
        val result = StartWorkAuthority(actual.userId, actual.companyId, branch, terminal.terminalId,
            requireNotNull(app.updateTelemetry.installation.installationId()), actual.name, binding) {
            current() && online() && app.tokens.isCurrent(session) && original.isCurrent()
        }
        captured = Captured(result, session, original)
        return result
    }
    override suspend fun attempt(authority: StartWorkAuthority, pending: PendingStartWork,
        receipt: suspend (StartWorkReceipt) -> Unit, complete: suspend (StartWorkOutcome) -> Unit) {
        val origin = requireNotNull(captured).also { check(it.value === authority && authority.current()) }
        val ref = pending.reference
        val api = ApiClient.createEphemeralAuthorityApi<StartWorkApi>(origin.session.accessToken,
            ref.terminalId, singleAttempt = true, beforeDispatch = { check(authority.current()) })
        app.handoverOperations.runKnownJournalAttempt(origin.original, ref.knownAttempt(), {
            val saved = api.start(StartWorkBody(ref.openingFloatMinor), ref.actionKey, requireNotNull(ref.installationId))
            saved.validate(ref); check(authority.current()); receipt(saved)
            hydrate(ref, saved)
        }, { observed -> check(authority.current()); complete(observed) })
    }
    private suspend fun hydrate(ref: StartWorkReference, saved: StartWorkReceipt): StartWorkOutcome {
        check(current() && online()); saved.validate(ref)
        val lease = requireNotNull(scope)
        val actual = ApiClient.api.me()
        check(current() && sameProfile(actual) && actual.companyId == ref.companyId)
        val active = requireNotNull(app.terminalStore.activeValidatedTerminal.value)
        check(active.terminalId == ref.terminalId && active.branchId == ref.branchId &&
            app.updateTelemetry.installation.installationId() == ref.installationId)
        val session = requireNotNull(app.tokens.refreshLease())
        val api = ApiClient.createEphemeralAuthorityApi<ShiftApi>(session.accessToken, ref.terminalId,
            singleAttempt = true, beforeDispatch = { check(current() && app.tokens.isCurrent(session) && online()) })
        val detail = app.sync.hydrateStartedWorkShift(
            lease = lease, shiftId = saved.shiftId, actorId = ref.actorId,
            companyId = ref.companyId, branchId = ref.branchId, terminalId = ref.terminalId,
            installationId = requireNotNull(ref.installationId), platform = ref.platform,
            openingFloatMinor = ref.openingFloatMinor,
            openedAt = OffsetDateTime.parse(saved.startedAt).toInstant(), shifts = api,
            stillCurrent = {
                current() && online() && app.tokens.isCurrent(session) &&
                    app.terminalStore.activeValidatedTerminal.value?.let {
                        it.terminalId == ref.terminalId && it.branchId == ref.branchId
                    } == true && app.updateTelemetry.installation.installationId() == ref.installationId
            },
        )
        return StartWorkOutcome("committed", saved, currentShift = StartWorkCurrentShift(detail.id,
            ref.companyId, detail.branchId, requireNotNull(detail.terminalId), detail.status, requireNotNull(detail.openingClientPlatform), detail.openingClientInstallationId)).also { it.validate(ref) }
    }
    override suspend fun observe(pending: PendingStartWork, ownerEmail: String?, password: String?, cancel: Boolean,
        receipt: suspend (StartWorkReceipt) -> Unit, complete: suspend (StartWorkOutcome) -> Unit) {
        check(current() && online()); pending.validate()
        val mode = if (ownerEmail == null) "actor_status" else if (cancel) "owner_cancel" else "owner_status"
        val permit = app.handoverOperations.beginStartWorkReview(pending.reference, mode)
        try {
            val outcome = if (ownerEmail != null) {
                val api = ApiClient.startWorkReviewApi<OwnerStartWorkApi>(permit, beforeDispatch = { check(current() && online()) })
                api.review(OwnerStartWorkReview(pending.reference, if (cancel) "cancel" else "status",
                    ownerEmail, requireNotNull(password), if (cancel) pending.cancelReason else null))
            } else {
                val token = requireNotNull(app.tokens.refreshLease())
                val api = ApiClient.startWorkReviewApi<StartWorkApi>(permit, token.accessToken,
                    pending.reference.terminalId, beforeDispatch = { check(current() && online() && app.tokens.isCurrent(token)) })
                api.status(pending.reference.actionId)
            }
            check(current()); outcome.validate(pending.reference)
            check(outcome.state != "unconfirmed") { "The server has not confirmed an outcome. Owner review can safely retire this exact saved start." }
            var proof = outcome
            if (outcome.state == "committed") {
                val saved = requireNotNull(outcome.receipt); receipt(saved)
                // A positive exact CLOSED state needs no current cashier cache.
                // OPEN state must instead hydrate through the current caller's own scoped read.
                if (outcome.currentShift?.status != "closed") proof = hydrate(pending.reference, saved)
            }
            permit.owner.checkpointStartWorkReview(permit, ReviewedStartWorkResolution(pending.reference, proof), ::current) {
                check(current()); complete(proof)
            }
        } finally { withContext(NonCancellable) { permit.owner.finishStartWorkReview(permit) } }
    }
}
