package cloud.dcompany.erp.ui.screens.memberships

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.dcompany.erp.DCompanyApp
import cloud.dcompany.erp.core.auth.EffectivePermissions
import cloud.dcompany.erp.core.db.CustomerCacheEntity
import cloud.dcompany.erp.core.db.CustomerMembershipCacheEntity
import cloud.dcompany.erp.core.db.CustomerMembershipHistoryCacheEntity
import cloud.dcompany.erp.core.db.LocalMembershipCancellationEntity
import cloud.dcompany.erp.core.db.LocalMembershipPaymentActionEntity
import cloud.dcompany.erp.core.db.LocalMembershipRefundEntity
import cloud.dcompany.erp.core.db.LocalMembershipRefundActionEntity
import cloud.dcompany.erp.core.db.LocalSubscriptionEntity
import cloud.dcompany.erp.core.db.MembershipTierCacheEntity
import cloud.dcompany.erp.core.db.MembershipMoneyActionState
import cloud.dcompany.erp.core.db.MembershipPaymentActionKind
import cloud.dcompany.erp.core.db.MembershipPaymentTaskCacheEntity
import cloud.dcompany.erp.core.db.MembershipPaymentTaskStatus
import cloud.dcompany.erp.core.db.MembershipRefundWriteState
import cloud.dcompany.erp.core.db.MembershipRefundActionKind
import cloud.dcompany.erp.core.db.MembershipRefundAttemptCacheEntity
import cloud.dcompany.erp.core.db.MembershipRefundTaskCacheEntity
import cloud.dcompany.erp.core.db.MembershipRefundTaskStatus
import cloud.dcompany.erp.core.db.MembershipWriteState
import cloud.dcompany.erp.core.db.ShiftActor
import cloud.dcompany.erp.core.db.ShiftResolutionPolicy
import cloud.dcompany.erp.core.db.membershipPaymentActionRequiresAuditControl
import cloud.dcompany.erp.core.db.membershipRefundActionRequiresAuditControl
import cloud.dcompany.erp.core.net.ApiClient
import cloud.dcompany.erp.core.net.ApiException
import cloud.dcompany.erp.core.net.MeResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

/** Which modal is up. Owned by the ViewModel so a rotation does not lose track of which form it was. */
sealed interface MembershipsDialog {
    data class SubscribeForm(val customer: CustomerCacheEntity) : MembershipsDialog
    data class ConfirmCancel(val customer: CustomerCacheEntity, val membership: MembershipRow) : MembershipsDialog
    data class RefundForm(val customer: CustomerCacheEntity, val membership: MembershipRow) : MembershipsDialog
    data class ConfirmCashHandover(val refund: PendingMembershipRefundRow) : MembershipsDialog
    data class WithdrawCashRefund(val refund: PendingMembershipRefundRow) : MembershipsDialog
    data class CompletePayment(val task: MembershipPaymentTaskCacheEntity) : MembershipsDialog
    data class WithdrawPayment(val task: MembershipPaymentTaskCacheEntity) : MembershipsDialog
    data class CompleteRefund(val task: MembershipRefundTaskCacheEntity) : MembershipsDialog
    data class ResolveRefund(val task: MembershipRefundTaskCacheEntity) : MembershipsDialog
    data class ResolveLegacyRefund(val attempt: MembershipRefundAttemptCacheEntity) : MembershipsDialog
}

/** A synced-or-pending membership merged for display — mirrors IngredientRow's
 * "cache + any pending/rejected local write" shape, but simpler: subscribe/
 * cancel are Shape D/C (never edited), so there's no field-level merge, just
 * "which state is currently true for this customer." */
data class MembershipRow(
    val subscription: Subscription?,
    val pendingSubscribeLocalId: String? = null,
    val pendingCancelLocalId: String? = null,
    val pendingRefundLocalId: String? = null,
    val pendingPaymentTaskId: String? = null,
    val pendingRefundTaskId: String? = null,
)

data class PendingSubscriptionRow(
    val localId: String,
    val customerName: String,
    val tierName: String,
    val rejected: Boolean,
    val error: String? = null,
)

data class PendingCancellationRow(
    val localId: String,
    val customerName: String,
    val rejected: Boolean,
    val error: String? = null,
)

data class PendingMembershipRefundRow(
    val localId: String,
    val customerName: String,
    val amountMinor: Long,
    val syncState: String,
    val rejected: Boolean,
    val error: String? = null,
)

data class MembershipsUiState(
    val loading: Boolean = true,
    val tiers: List<MembershipTier> = emptyList(),
    val search: String = "",
    val customers: List<CustomerCacheEntity> = emptyList(),
    val selectedCustomer: CustomerCacheEntity? = null,
    val selectedMembership: MembershipRow? = null,
    val pendingSubscriptions: List<PendingSubscriptionRow> = emptyList(),
    val pendingCancellations: List<PendingCancellationRow> = emptyList(),
    val pendingRefunds: List<PendingMembershipRefundRow> = emptyList(),
    val paymentTasks: List<MembershipPaymentTaskCacheEntity> = emptyList(),
    val paymentActions: List<LocalMembershipPaymentActionEntity> = emptyList(),
    val refundTasks: List<MembershipRefundTaskCacheEntity> = emptyList(),
    val refundActions: List<LocalMembershipRefundActionEntity> = emptyList(),
    val legacyRefundAttempts: List<MembershipRefundAttemptCacheEntity> = emptyList(),
    val membershipHistory: List<Subscription> = emptyList(),
    val dialog: MembershipsDialog? = null,
    val busy: Boolean = false,
    val formError: String? = null,
    val notice: String? = null,
)

internal enum class MembershipMoneyOperation { PREPARE, SALE, REFUND }

/** Only the zero-value preparation may start offline. Every operation that can
 * move customer/provider/drawer value remains online-only. Returning the exact
 * policy result from a pure function keeps this boundary testable without
 * constructing Android application state. */
internal fun membershipMoneyOfflineMessage(
    online: Boolean,
    operation: MembershipMoneyOperation,
): String? {
    if (online) return null
    return when (operation) {
        // A preparation is a zero-value durable draft. The server still owns
        // price, entitlement, shift and overlap validation after reconnect,
        // and the UI never authorises money movement from this local row.
        MembershipMoneyOperation.PREPARE -> null
        MembershipMoneyOperation.SALE ->
            "Membership payments require a live ERP connection. Do not collect cash or " +
                "approve UPI/card while offline; reconnect and try again."
        MembershipMoneyOperation.REFUND ->
            "Membership refunds must start online. Do not hand over cash or complete a " +
                "provider refund until the ERP connection is available."
    }
}

internal fun canManageMembershipMoney(profile: MeResponse?): Boolean =
    profile?.let { EffectivePermissions.from(it).membershipAccess(it).canManageMoney } == true

internal fun canRecoverLegacyMembershipEvidence(profile: MeResponse?): Boolean =
    profile?.let {
        EffectivePermissions.from(it).membershipAccess(it).canRecoverLegacyEvidence
    } == true

internal const val MEMBERSHIP_AUDIT_CONTROL_MESSAGE =
    "Older-app membership recovery is read only for this account. Ask the Audit Control owner to verify the original drawer/provider evidence; do not repeat any collection or payout."

internal fun membershipPaymentStageMessage(status: String): String = when (status) {
    MembershipPaymentTaskStatus.ACCEPTED_PAYMENT_DUE ->
        "Prepared only — no money is authorised to move yet."
    MembershipPaymentTaskStatus.CASH_COLLECTION_IN_PROGRESS ->
        "Cash collection is server-authorised. Confirm the customer and amount, then collect once."
    MembershipPaymentTaskStatus.PROVIDER_ACTION_IN_PROGRESS ->
        "Provider payment is server-authorised. Complete it once and retain its reference."
    MembershipPaymentTaskStatus.PAYMENT_COMPLETED_PENDING_POSTING ->
        "Payment evidence is saved; accounting and the receipt are still posting. Do not collect again."
    MembershipPaymentTaskStatus.SETTLED -> "Settled — the membership receipt is posted."
    MembershipPaymentTaskStatus.WITHDRAWN -> "Withdrawn — no outstanding collection remains."
    else -> "Unknown payment state — do not move money; update the app and ask an owner to reconcile it."
}

internal fun membershipRefundStageMessage(status: String): String = when (status) {
    MembershipRefundTaskStatus.ACCEPTED_CASH_DUE,
    MembershipRefundTaskStatus.ACCEPTED_PROVIDER_DUE,
    -> "Refund accepted and benefits held — no payout is authorised to move yet."
    MembershipRefundTaskStatus.CASH_HANDOFF_IN_PROGRESS ->
        "Cash handover is server-authorised. Verify the customer and amount, then hand it over once."
    MembershipRefundTaskStatus.PROVIDER_ACTION_IN_PROGRESS ->
        "Provider refund is server-authorised. Complete it once and retain its reference."
    MembershipRefundTaskStatus.PAYOUT_COMPLETED_PENDING_POSTING ->
        "Payout evidence is saved; accounting and the refund receipt are still posting. Do not pay again."
    MembershipRefundTaskStatus.SETTLED -> "Refund settled — the audited receipt is posted."
    MembershipRefundTaskStatus.WITHDRAWN -> "Refund withdrawn — no outstanding payout remains."
    else -> "Unknown refund state — do not move money; update the app and ask an owner to reconcile it."
}

/**
 * Memberships — greenfield, no prior native screen. Tier browsing is a
 * read-only cache (tier create/edit stays web-only, Settings → Memberships
 * already covers it). Subscribe (Shape D, mandatory idempotency) and
 * cancel (Shape C, targets an already-synced subscription only) are real
 * durable outbox resources. Zero-value payment preparation can be queued
 * offline from a cached price snapshot; the server verifies that snapshot
 * after reconnect. Starting collection and recording value movement remain
 * online-only: unlike a menu draft, a rejected cash/provider row can leave
 * physical money requiring owner reconciliation. The stable local row protects
 * every in-flight stage if connectivity drops after submission, and shift close
 * remains blocked until that exact action resolves.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MembershipsViewModel : ViewModel() {

    private val appCtx = DCompanyApp.instance
    private val db = appCtx.db
    private val api = ApiClient.create<MembershipsApi>()

    private val pulling = MutableStateFlow(false)
    private val search = MutableStateFlow("")
    private val selectedCustomerId = MutableStateFlow<String?>(null)

    private val dialog = MutableStateFlow<MembershipsDialog?>(null)
    private val busy = MutableStateFlow(false)
    private val formError = MutableStateFlow<String?>(null)
    private val notice = MutableStateFlow<String?>(null)

    private data class FormState(
        val dialog: MembershipsDialog?,
        val busy: Boolean,
        val formError: String?,
        val notice: String?,
    )
    private data class PendingWrites(
        val subscriptions: List<LocalSubscriptionEntity>,
        val cancellations: List<LocalMembershipCancellationEntity>,
        val refunds: List<LocalMembershipRefundEntity>,
        val paymentTasks: List<MembershipPaymentTaskCacheEntity>,
        val paymentActions: List<LocalMembershipPaymentActionEntity>,
        val refundTasks: List<MembershipRefundTaskCacheEntity>,
        val refundActions: List<LocalMembershipRefundActionEntity>,
        val legacyRefundAttempts: List<MembershipRefundAttemptCacheEntity>,
    )
    private data class MembershipMoneyWrites(
        val paymentTasks: List<MembershipPaymentTaskCacheEntity>,
        val paymentActions: List<LocalMembershipPaymentActionEntity>,
        val refundTasks: List<MembershipRefundTaskCacheEntity>,
        val refundActions: List<LocalMembershipRefundActionEntity>,
        val legacyRefundAttempts: List<MembershipRefundAttemptCacheEntity>,
    )

    val state: StateFlow<MembershipsUiState> = combine(
        combine(
            db.membershipDao().observeTierCache(),
            db.customerDao().observeCache(),
            search,
        ) { tiers, customers, q -> Triple(tiers, customers, q) },
        combine(
            combine(
                db.membershipPaymentDao().observeUnresolvedTasks(),
                db.membershipPaymentDao().observeUnresolvedActions(),
                db.membershipRefundMoneyDao().observeUnresolvedTasks(),
                db.membershipRefundMoneyDao().observeUnresolvedActions(),
                db.membershipRefundMoneyDao().observeUnresolvedAttempts(),
            ) { paymentTasks, paymentActions, refundTasks, refundActions, attempts ->
                MembershipMoneyWrites(
                    paymentTasks,
                    paymentActions,
                    refundTasks,
                    refundActions,
                    attempts,
                )
            },
            db.membershipDao().observeLocalSubscriptions(),
            db.membershipDao().observeLocalCancellations(),
            db.membershipDao().observeLocalRefunds(),
        ) { money, subs, cancels, refunds ->
            PendingWrites(
                subs,
                cancels,
                refunds,
                money.paymentTasks,
                money.paymentActions,
                money.refundTasks,
                money.refundActions,
                money.legacyRefundAttempts,
            )
        },
        selectedCustomerId,
        combine(dialog, busy, formError, notice) { d, bs, fe, n -> FormState(d, bs, fe, n) },
    ) { tierAndCustomers, pendingWrites, selectedId, form ->
        val (tiers, customers, q) = tierAndCustomers
        val subs = pendingWrites.subscriptions
        val cancels = pendingWrites.cancellations
        val refunds = pendingWrites.refunds
        val filtered = if (q.isBlank()) {
            emptyList()
        } else {
            customers.filter {
                it.name.orEmpty().contains(q, ignoreCase = true) || it.phone.contains(q)
            }.take(20)
        }
        val selectedCustomer = customers.firstOrNull { it.id == selectedId }
        MembershipsUiState(
            loading = false,
            tiers = tiers.map { it.toTier() },
            search = q,
            customers = filtered,
            selectedCustomer = selectedCustomer,
            pendingSubscriptions = subs.map { it.toPendingRow(customers, tiers) },
            pendingCancellations = cancels.map { it.toPendingRow(customers) },
            pendingRefunds = refunds.map { it.toPendingRow(customers) },
            paymentTasks = pendingWrites.paymentTasks,
            paymentActions = pendingWrites.paymentActions,
            refundTasks = pendingWrites.refundTasks,
            refundActions = pendingWrites.refundActions,
            legacyRefundAttempts = pendingWrites.legacyRefundAttempts,
            dialog = form.dialog,
            busy = form.busy,
            formError = form.formError,
            notice = form.notice,
        )
    }.let { base ->
        // The selected customer's membership (cache + any pending local
        // subscribe/cancel for them) — reactive derivation, not a
        // separately-mutated field, so it never goes stale if a background
        // sync completes while this customer stays selected.
        val membershipFlow = selectedCustomerId.flatMapLatest { id ->
            if (id == null) {
                flowOf<CustomerMembershipCacheEntity?>(null)
            } else {
                db.membershipDao().observeMembershipFor(id)
            }
        }
        val historyFlow = selectedCustomerId.flatMapLatest { id ->
            if (id == null) {
                flowOf<List<CustomerMembershipHistoryCacheEntity>>(emptyList())
            } else {
                db.membershipDao().observeMembershipHistoryFor(id)
            }
        }
        val membershipAndHistory = combine(membershipFlow, historyFlow) { current, history ->
            current to history
        }
        combine(
            base,
            membershipAndHistory,
            db.membershipDao().observeLocalSubscriptions(),
            db.membershipDao().observeLocalCancellations(),
            db.membershipDao().observeLocalRefunds(),
        ) { s, membershipData, subs, cancels, refunds ->
            val (cached, history) = membershipData
            val customerId = s.selectedCustomer?.id
            val pendingSub = subs.firstOrNull { it.customerId == customerId && it.syncState != "synced" }
            val latest = cached?.toSubscription() ?: history.firstOrNull()?.toSubscription()
            val pendingCancel = latest?.let { c ->
                cancels.firstOrNull { it.subscriptionId == c.id && it.syncState != "synced" }
            }
            val pendingRefund = refunds.firstOrNull {
                it.customerId == customerId &&
                    it.syncState !in setOf(MembershipRefundWriteState.SYNCED, MembershipRefundWriteState.WITHDRAWN)
            }
            val pendingPayment = s.paymentTasks.firstOrNull { it.customerId == customerId }
            val pendingRefundTask = s.refundTasks.firstOrNull { it.customerId == customerId }
            s.copy(
                membershipHistory = history.map { it.toSubscription() },
                selectedMembership = if (customerId == null) {
                    null
                } else {
                    MembershipRow(
                        subscription = latest,
                        pendingSubscribeLocalId = pendingSub?.localId,
                        pendingCancelLocalId = pendingCancel?.localId,
                        pendingRefundLocalId = pendingRefund?.localId,
                        pendingPaymentTaskId = pendingPayment?.id,
                        pendingRefundTaskId = pendingRefundTask?.id,
                    )
                },
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MembershipsUiState())

    init {
        loadTiers()
        viewModelScope.launch {
            db.syncMetaDao().observe("memberships").drop(1).collectLatest {
                selectedCustomerId.value?.let {
                    try {
                        appCtx.sync.pullMembershipFor(it)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // Keep the last durable receipt/history cache offline.
                    }
                }
            }
        }
    }

    // -------------------------------------------------------------- loading

    fun loadTiers() {
        viewModelScope.launch {
            try {
                appCtx.sync.refresh("memberships")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Cached tiers (if any) stay showing.
            }
        }
    }

    fun refreshSelectedCustomer() {
        val id = selectedCustomerId.value ?: return
        viewModelScope.launch {
            pulling.value = true
            try {
                appCtx.sync.pullMembershipFor(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Cached membership (if any) stays showing.
            } finally {
                pulling.value = false
            }
        }
    }

    fun setSearch(q: String) {
        search.value = q
    }

    fun selectCustomer(customer: CustomerCacheEntity) {
        selectedCustomerId.value = customer.id
        search.value = ""
        refreshSelectedCustomer()
    }

    fun clearSelection() {
        selectedCustomerId.value = null
    }

    // -------------------------------------------------------------- dialogs

    fun openSubscribeForm(customer: CustomerCacheEntity) {
        dialog.value = MembershipsDialog.SubscribeForm(customer)
    }

    fun openConfirmCancel(customer: CustomerCacheEntity, membership: MembershipRow) {
        dialog.value = MembershipsDialog.ConfirmCancel(customer, membership)
    }

    fun openRefundForm(customer: CustomerCacheEntity, membership: MembershipRow) {
        dialog.value = MembershipsDialog.RefundForm(customer, membership)
    }

    fun openCashHandover(refund: PendingMembershipRefundRow) {
        dialog.value = MembershipsDialog.ConfirmCashHandover(refund)
    }

    fun openRefundWithdrawal(refund: PendingMembershipRefundRow) {
        dialog.value = MembershipsDialog.WithdrawCashRefund(refund)
    }

    fun openCompletePayment(task: MembershipPaymentTaskCacheEntity) {
        dialog.value = MembershipsDialog.CompletePayment(task)
    }

    fun openPaymentWithdrawal(task: MembershipPaymentTaskCacheEntity) {
        dialog.value = MembershipsDialog.WithdrawPayment(task)
    }

    fun openCompleteRefund(task: MembershipRefundTaskCacheEntity) {
        dialog.value = MembershipsDialog.CompleteRefund(task)
    }

    fun openRefundResolution(task: MembershipRefundTaskCacheEntity) {
        dialog.value = MembershipsDialog.ResolveRefund(task)
    }

    fun openLegacyRefundResolution(attempt: MembershipRefundAttemptCacheEntity) {
        if (!canRecoverLegacyMembershipEvidence(appCtx.shiftCache.profile.value)) {
            notice.value = MEMBERSHIP_AUDIT_CONTROL_MESSAGE
            return
        }
        dialog.value = MembershipsDialog.ResolveLegacyRefund(attempt)
    }

    fun closeDialog() {
        dialog.value = null
        formError.value = null
    }

    fun dismissNotice() {
        notice.value = null
    }

    // ---------------------------------------------------------- subscribe

    fun subscribe(customerId: String, tierId: String, billingCycle: String, paidVia: String) {
        if (busy.value) return
        val scopeLease = appCtx.cacheIsolation.currentLease() ?: return
        busy.value = true
        formError.value = null
        viewModelScope.launch {
            membershipMoneyOfflineMessage(
                appCtx.connectivity.online.value,
                MembershipMoneyOperation.PREPARE,
            )?.let { message ->
                busy.value = false
                formError.value = message
                return@launch
            }
            val profile = appCtx.shiftCache.profile.value
            if (!canManageMembershipMoney(profile)) {
                busy.value = false
                formError.value =
                    "Only a protected owner with membership administration access can prepare or collect a membership payment."
                return@launch
            }
            // Preparing is zero-value: no staff/customer money may move until
            // the server accepts this snapshot and a second begin step succeeds.
            // A cached price is safe for an offline preparation because the
            // backend rejects drift before it creates a server task.
            val cachedTier = db.membershipDao().tierById(tierId)?.toTier()
            var priceVerifiedLive = appCtx.connectivity.online.value
            val tier = if (priceVerifiedLive) {
                try {
                    api.listTiers().firstOrNull { it.id == tierId }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    priceVerifiedLive = false
                    cachedTier
                }
            } else {
                cachedTier
            }
            val expectedAmount = when (billingCycle) {
                "annual" -> tier?.annualPriceMinor ?: tier?.monthlyPriceMinor?.times(12)
                else -> tier?.monthlyPriceMinor
            }
            if (expectedAmount == null || expectedAmount <= 0L) {
                busy.value = false
                formError.value =
                    "This membership price is unavailable. Refresh tiers and reopen the form."
                return@launch
            }
            try {
                var actionId: String? = null
                var captureError: String? = null
                if (!appCtx.cacheIsolation.commitIfCurrent(scopeLease) commit@{
                        val terminalId = appCtx.terminalStore.terminalId()
                        val openShift = if (terminalId == null) null else ShiftResolutionPolicy.resolve(
                            db.shiftDao().currentForTerminal(terminalId),
                            db.shiftDao().serverOpen(terminalId),
                        )
                        if (openShift == null) {
                            captureError =
                                "Open a POS shift on this tablet before preparing a membership payment."
                            return@commit
                        }
                        val profile = appCtx.shiftCache.profile.value
                        val actor = profile?.let { ShiftActor(it.userId, it.protectedAccess) }
                        if (!openShift.canManageMoney(actor)) {
                            captureError = openShift.moneyAccessMessage(actor)
                                ?: "Only the shift opener or a protected owner can collect this payment."
                            return@commit
                        }
                        if (
                            db.membershipPaymentDao().unresolvedActionCountForCustomer(customerId) > 0 ||
                            db.membershipPaymentDao().unresolvedTaskCountForCustomer(customerId) > 0
                        ) {
                            captureError =
                                "This customer already has a membership payment task. Resolve it before preparing another."
                            return@commit
                        }
                        val root = "membership-payment:${UUID.randomUUID()}"
                        actionId = root
                        db.membershipPaymentDao().insertAction(
                            LocalMembershipPaymentActionEntity(
                                actionId = root,
                                rootClientActionId = root,
                                kind = MembershipPaymentActionKind.PREPARE,
                                customerId = customerId,
                                tierId = tierId,
                                shiftId = openShift.shiftId,
                                branchId = requireNotNull(profile).branchId,
                                terminalId = terminalId,
                                actorUserId = profile.userId,
                                expectedAmountMinor = expectedAmount,
                                billingCycle = billingCycle,
                                paidVia = paidVia,
                                createdAtMillis = System.currentTimeMillis(),
                            ),
                        )
                    }
                ) return@launch
                captureError?.let {
                    busy.value = false
                    formError.value = it
                    return@launch
                }
                val capturedActionId = requireNotNull(actionId)
                if (!priceVerifiedLive) {
                    busy.value = false
                    dialog.value = null
                    notice.value =
                        "Payment preparation saved offline. No money is authorised yet. " +
                            "After reconnect, wait for server confirmation and review the live price before collection."
                    appCtx.sync.requestSync()
                    return@launch
                }
                appCtx.sync.sync()
                when (val saved = db.membershipPaymentDao().actionById(capturedActionId)) {
                    null -> formError.value =
                        "The saved payment preparation could not be found. Do not collect money; " +
                            "ask an owner to inspect this tablet."
                    else -> when (saved.state) {
                        MembershipMoneyActionState.SYNCED -> {
                            dialog.value = null
                            notice.value =
                                "Payment prepared. No money has been recorded yet. Open the task, " +
                                    "start its cash/provider step, and wait for confirmation before collecting."
                        }
                        MembershipMoneyActionState.REJECTED -> formError.value =
                            "The server did not prepare this payment: " +
                                (saved.lastError ?: "request refused") +
                                ". No money was authorised; correct the issue before creating a new preparation."
                        else -> formError.value =
                            "Server confirmation is still pending. Do not collect cash or approve " +
                                "card/UPI. Reconnect and retry this exact saved action."
                    }
                }
                busy.value = false
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                busy.value = false
                formError.value = "Could not save this locally: ${e.message}"
            }
        }
    }

    fun retrySubscription(localId: String) {
        val scopeLease = appCtx.cacheIsolation.currentLease() ?: return
        viewModelScope.launch {
            if (!appCtx.cacheIsolation.commitIfCurrent(scopeLease) {
                    db.membershipPaymentDao().retryAction(localId)
                }
            ) return@launch
            appCtx.sync.requestSync()
        }
    }

    /**
     * Opens the server-owned physical collection window.  The returned task
     * must be durably cached before the UI tells staff to touch cash/provider.
     */
    fun beginPayment(task: MembershipPaymentTaskCacheEntity) {
        if (busy.value) return
        busy.value = true
        formError.value = null
        viewModelScope.launch {
            try {
                membershipMoneyOfflineMessage(
                    appCtx.connectivity.online.value,
                    MembershipMoneyOperation.SALE,
                )?.let { throw IllegalStateException(it) }
                val kind = if (task.paidVia == "cash") {
                    MembershipPaymentActionKind.BEGIN_CASH
                } else {
                    MembershipPaymentActionKind.BEGIN_PROVIDER
                }
                val actionId = savePaymentStage(
                    task = task,
                    kind = kind,
                    allowedStatuses = setOf(MembershipPaymentTaskStatus.ACCEPTED_PAYMENT_DUE),
                )
                runCatching { appCtx.sync.sync() }
                val saved = db.membershipPaymentDao().actionById(actionId)
                val latest = db.membershipPaymentDao().taskById(task.id)
                val expected = if (task.paidVia == "cash") {
                    MembershipPaymentTaskStatus.CASH_COLLECTION_IN_PROGRESS
                } else {
                    MembershipPaymentTaskStatus.PROVIDER_ACTION_IN_PROGRESS
                }
                when {
                    latest?.status == expected -> {
                        dialog.value = null
                        notice.value = membershipPaymentStageMessage(expected)
                    }
                    latest?.status in setOf(
                        MembershipPaymentTaskStatus.PAYMENT_COMPLETED_PENDING_POSTING,
                        MembershipPaymentTaskStatus.SETTLED,
                    ) -> {
                        dialog.value = null
                        notice.value = membershipPaymentStageMessage(requireNotNull(latest).status)
                    }
                    saved?.state == MembershipMoneyActionState.REJECTED ->
                        formError.value =
                            "The server did not open the collection step: ${saved.lastError ?: "request refused"}. No money is authorised to move."
                    else -> formError.value =
                        "The collection step could not be verified. Do not take cash or approve card/UPI. Retry this exact saved task after reconnecting."
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                formError.value = e.message ?: "Could not start this membership payment."
            } finally {
                busy.value = false
            }
        }
    }

    /**
     * Records value that has already moved.  This must remain saveable after
     * a post-begin connection drop; the durable COMPLETE action is inserted
     * before any network call and blocks shift close/sign-out until resolved.
     */
    fun completePayment(
        task: MembershipPaymentTaskCacheEntity,
        externalReference: String?,
        takeoverReason: String?,
    ) {
        if (busy.value) return
        val cleanReference = externalReference?.trim()?.takeIf(String::isNotEmpty)
        if (task.paidVia != "cash" && (cleanReference?.length ?: 0) < 3) {
            formError.value = "Enter the completed card/UPI provider reference."
            return
        }
        val profile = appCtx.shiftCache.profile.value
        val takeover = task.actionStartedBy != null && task.actionStartedBy != profile?.userId
        val cleanTakeoverReason = takeoverReason?.trim()?.takeIf(String::isNotEmpty)
        if (takeover && (cleanTakeoverReason?.length ?: 0) < 3) {
            formError.value =
                "${task.actionStartedByName ?: "Another owner"} started this action. Enter why you are taking it over."
            return
        }
        busy.value = true
        formError.value = null
        viewModelScope.launch {
            try {
                val allowed = if (task.paidVia == "cash") {
                    setOf(MembershipPaymentTaskStatus.CASH_COLLECTION_IN_PROGRESS)
                } else {
                    setOf(MembershipPaymentTaskStatus.PROVIDER_ACTION_IN_PROGRESS)
                }
                val actionId = savePaymentStage(
                    task = task,
                    kind = MembershipPaymentActionKind.COMPLETE,
                    allowedStatuses = allowed,
                    occurredAtMillis = System.currentTimeMillis(),
                    externalReference = cleanReference,
                    takeoverConfirmed = takeover,
                    takeoverReason = cleanTakeoverReason,
                )
                if (appCtx.connectivity.online.value) {
                    runCatching { appCtx.sync.sync() }
                } else {
                    appCtx.sync.requestSync()
                }
                val saved = db.membershipPaymentDao().actionById(actionId)
                val latest = db.membershipPaymentDao().taskById(task.id)
                when {
                    latest?.status == MembershipPaymentTaskStatus.SETTLED -> {
                        dialog.value = null
                        notice.value = membershipPaymentStageMessage(latest.status)
                    }
                    latest?.status == MembershipPaymentTaskStatus.PAYMENT_COMPLETED_PENDING_POSTING -> {
                        dialog.value = null
                        notice.value = membershipPaymentStageMessage(latest.status)
                    }
                    !appCtx.connectivity.online.value -> {
                        dialog.value = null
                        notice.value =
                            "Payment completion is saved on this tablet and waiting to sync. Do not collect again; this shift cannot close meanwhile."
                    }
                    saved?.state == MembershipMoneyActionState.REJECTED -> formError.value =
                        "The server refused the saved completion evidence: ${saved.lastError ?: "request refused"}. Do not collect again; a protected owner must reconcile this task."
                    else -> formError.value =
                        "The server response is unverified. Do not collect again. Keep this task open and retry the same saved completion."
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                formError.value = e.message ?: "Could not preserve this completed payment."
            } finally {
                busy.value = false
            }
        }
    }

    /** Resolve a task only from explicit, rail-specific evidence. */
    fun withdrawPayment(
        task: MembershipPaymentTaskCacheEntity,
        resolution: String,
        reason: String,
        verificationReference: String? = null,
        takeoverReason: String? = null,
    ) {
        if (busy.value) return
        val cleanReason = reason.trim()
        val cleanVerification = verificationReference?.trim()?.takeIf(String::isNotEmpty)
        if (cleanReason.length < 3) {
            formError.value = "Enter a clear reason (at least 3 characters)."
            return
        }
        val noAction = task.status == MembershipPaymentTaskStatus.ACCEPTED_PAYMENT_DUE
        val cash = task.paidVia == "cash"
        val allowedResolution = when {
            noAction -> resolution == "payment_not_collected"
            cash -> resolution in setOf("cash_not_collected", "cash_returned")
            else -> resolution in setOf("provider_not_completed", "provider_reversed")
        }
        if (!allowedResolution) {
            formError.value = "That recovery outcome does not match this payment rail and state."
            return
        }
        if (!noAction && !cash && (cleanVerification?.length ?: 0) < 3) {
            formError.value = "Enter the provider status/reversal verification reference."
            return
        }
        val profile = appCtx.shiftCache.profile.value
        val takeover = task.actionStartedBy != null && task.actionStartedBy != profile?.userId
        val cleanTakeover = takeoverReason?.trim()?.takeIf(String::isNotEmpty)
        if (takeover && (cleanTakeover?.length ?: 0) < 3) {
            formError.value =
                "${task.actionStartedByName ?: "Another owner"} started this action. Enter why you are taking it over."
            return
        }
        busy.value = true
        formError.value = null
        viewModelScope.launch {
            try {
                membershipMoneyOfflineMessage(
                    appCtx.connectivity.online.value,
                    MembershipMoneyOperation.SALE,
                )?.let { throw IllegalStateException(it) }
                val actionId = savePaymentStage(
                    task = task,
                    kind = MembershipPaymentActionKind.WITHDRAW,
                    allowedStatuses = setOf(task.status),
                    occurredAtMillis = System.currentTimeMillis(),
                    externalReference = cleanVerification.takeIf { resolution == "provider_reversed" },
                    resolution = resolution,
                    reason = cleanReason,
                    actionStateVerified = !noAction,
                    providerVerificationStatus = when (resolution) {
                        "provider_not_completed" -> "not_completed"
                        "provider_reversed" -> "reversed"
                        else -> null
                    },
                    providerVerificationReference = cleanVerification.takeIf { !cash && !noAction },
                    providerEvidenceOccurredAtMillis = System.currentTimeMillis().takeIf { !cash && !noAction },
                    cashReturnConfirmed = resolution == "cash_returned",
                    takeoverConfirmed = takeover,
                    takeoverReason = cleanTakeover,
                )
                runCatching { appCtx.sync.sync() }
                val saved = db.membershipPaymentDao().actionById(actionId)
                val latest = db.membershipPaymentDao().taskById(task.id)
                if (latest?.status == MembershipPaymentTaskStatus.WITHDRAWN) {
                    dialog.value = null
                    notice.value = membershipPaymentStageMessage(latest.status)
                } else if (saved?.state == MembershipMoneyActionState.REJECTED) {
                    formError.value =
                        "The server rejected this recovery evidence: ${saved.lastError ?: "request refused"}. The task remains unresolved; do not move money."
                } else {
                    formError.value =
                        "Recovery could not be verified. The task remains open and blocks shift close; retry this exact saved action."
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                formError.value = e.message ?: "Could not resolve this membership payment."
            } finally {
                busy.value = false
            }
        }
    }

    fun retryPaymentAction(actionId: String) {
        val scopeLease = appCtx.cacheIsolation.currentLease() ?: return
        viewModelScope.launch {
            val action = db.membershipPaymentDao().actionById(actionId)
            if (
                action != null &&
                membershipPaymentActionRequiresAuditControl(action.kind, action.state) &&
                !canRecoverLegacyMembershipEvidence(appCtx.shiftCache.profile.value)
            ) {
                notice.value = MEMBERSHIP_AUDIT_CONTROL_MESSAGE
                return@launch
            }
            if (!appCtx.cacheIsolation.commitIfCurrent(scopeLease) {
                    db.membershipPaymentDao().retryAction(actionId)
                }
            ) return@launch
            appCtx.sync.requestSync()
        }
    }

    fun discardRejectedPreparation(actionId: String) {
        val scopeLease = appCtx.cacheIsolation.currentLease() ?: return
        viewModelScope.launch {
            var discarded = false
            if (!appCtx.cacheIsolation.commitIfCurrent(scopeLease) {
                    discarded =
                        db.membershipPaymentDao().discardRejectedPreparation(actionId) == 1
                }
            ) return@launch
            notice.value = if (discarded) {
                "Rejected preparation discarded. No membership or payment had been created."
            } else {
                "This record cannot be discarded because its server or money state needs reconciliation."
            }
        }
    }

    private suspend fun savePaymentStage(
        task: MembershipPaymentTaskCacheEntity,
        kind: String,
        allowedStatuses: Set<String>,
        occurredAtMillis: Long? = null,
        externalReference: String? = null,
        resolution: String? = null,
        reason: String? = null,
        actionStateVerified: Boolean = false,
        providerVerificationStatus: String? = null,
        providerVerificationReference: String? = null,
        providerEvidenceOccurredAtMillis: Long? = null,
        cashReturnConfirmed: Boolean = false,
        takeoverConfirmed: Boolean = false,
        takeoverReason: String? = null,
    ): String {
        val scopeLease = appCtx.cacheIsolation.currentLease()
            ?: error("The signed-in account scope changed. Reopen Memberships and try again.")
        var actionId: String? = null
        var captureError: String? = null
        val committed = appCtx.cacheIsolation.commitIfCurrent(scopeLease) commit@{
            val profile = appCtx.shiftCache.profile.value
            if (!canManageMembershipMoney(profile)) {
                captureError =
                    "Only a protected owner with membership administration access can manage this payment."
                return@commit
            }
            val terminalId = appCtx.terminalStore.terminalId()
            if (
                terminalId == null || terminalId != task.terminalId ||
                profile?.branchId == null || profile.branchId != task.branchId
            ) {
                captureError =
                    "Return to the same branch, terminal, and protected account scope that owns this payment task."
                return@commit
            }
            val currentTask = db.membershipPaymentDao().taskById(task.id)
            if (currentTask == null || currentTask.status !in allowedStatuses) {
                captureError =
                    "This payment changed on another device. Refresh and review its latest state before continuing."
                return@commit
            }
            val openShift = ShiftResolutionPolicy.resolve(
                db.shiftDao().currentForTerminal(terminalId),
                db.shiftDao().serverOpen(terminalId),
            )
            val actor = profile.let { ShiftActor(it.userId, it.protectedAccess) }
            if (openShift == null || openShift.shiftId != task.shiftId) {
                captureError =
                    "This payment belongs to a different or closed shift. Return to its open terminal shift before continuing."
                return@commit
            }
            if (!openShift.canManageMoney(actor)) {
                captureError = openShift.moneyAccessMessage(actor)
                    ?: "Only the shift opener or a protected owner can manage this payment."
                return@commit
            }
            val deterministicId = when (kind) {
                MembershipPaymentActionKind.BEGIN_CASH,
                MembershipPaymentActionKind.BEGIN_PROVIDER,
                -> "membership-payment-begin:${task.id}"
                MembershipPaymentActionKind.COMPLETE -> "membership-payment-complete:${task.id}"
                MembershipPaymentActionKind.WITHDRAW -> "membership-payment-withdraw:${task.id}"
                else -> error("Unsupported membership payment stage $kind")
            }
            val row = LocalMembershipPaymentActionEntity(
                actionId = deterministicId,
                rootClientActionId = task.clientActionId,
                serverRequestId = task.id,
                kind = kind,
                customerId = task.customerId,
                tierId = task.tierId,
                shiftId = task.shiftId,
                branchId = task.branchId,
                terminalId = task.terminalId,
                actorUserId = profile.userId,
                billingCycle = task.billingCycle,
                paidVia = task.paidVia,
                expectedAmountMinor = task.amountMinor,
                occurredAtMillis = occurredAtMillis,
                externalReference = externalReference,
                resolution = resolution,
                reason = reason,
                actionStateVerified = actionStateVerified,
                providerVerificationStatus = providerVerificationStatus,
                providerVerificationReference = providerVerificationReference,
                providerEvidenceOccurredAtMillis = providerEvidenceOccurredAtMillis,
                cashReturnConfirmed = cashReturnConfirmed,
                actionTakeoverConfirmed = takeoverConfirmed,
                actionTakeoverReason = takeoverReason,
                createdAtMillis = System.currentTimeMillis(),
            )
            val inserted = db.membershipPaymentDao().insertAction(row)
            val saved = if (inserted == -1L) {
                db.membershipPaymentDao().actionForStage(task.clientActionId, kind)
            } else {
                row
            }
            if (saved == null || saved != row) {
                captureError =
                    "This stage is already saved with different immutable evidence. Do not repeat money movement; retry or reconcile the existing task."
                return@commit
            }
            actionId = saved.actionId
        }
        if (!committed) error("The signed-in account changed. No action was saved.")
        captureError?.let(::error)
        return requireNotNull(actionId)
    }

    // -------------------------------------------------------------- cancel

    fun cancelMembership(customerId: String, subscriptionId: String) {
        if (busy.value) return
        val scopeLease = appCtx.cacheIsolation.currentLease() ?: return
        busy.value = true
        viewModelScope.launch {
            var inserted = false
            if (!appCtx.cacheIsolation.commitIfCurrent(scopeLease) {
                    if (db.membershipDao().pendingCancellationForSubscription(subscriptionId) == null) {
                        db.membershipDao().insertLocalCancellation(
                            LocalMembershipCancellationEntity(
                                localId = UUID.randomUUID().toString(),
                                customerId = customerId,
                                subscriptionId = subscriptionId,
                                createdAtMillis = System.currentTimeMillis(),
                            ),
                        )
                        inserted = true
                    }
                }
            ) return@launch
            if (!inserted) {
                busy.value = false
                dialog.value = null
                return@launch
            }
            busy.value = false
            dialog.value = null
            notice.value = "Cancellation queued — will sync when back online."
            appCtx.sync.requestSync()
        }
    }

    fun retryCancellation(localId: String) {
        val scopeLease = appCtx.cacheIsolation.currentLease() ?: return
        viewModelScope.launch {
            if (!appCtx.cacheIsolation.commitIfCurrent(scopeLease) {
                    db.membershipDao().retryCancellation(localId)
                }
            ) return@launch
            appCtx.sync.requestSync()
        }
    }

    // --------------------------------------------------------------- refund

    fun refundMembership(
        customerId: String,
        subscriptionId: String,
        paymentId: String,
        expectedAmountMinor: Long,
        method: String,
        reason: String,
    ) {
        if (busy.value) return
        val cleanReason = reason.trim()
        if (cleanReason.length < 3) {
            formError.value = "Enter a clear refund reason (at least 3 characters)."
            return
        }
        val scopeLease = appCtx.cacheIsolation.currentLease() ?: return
        busy.value = true
        formError.value = null
        viewModelScope.launch {
            membershipMoneyOfflineMessage(
                appCtx.connectivity.online.value,
                MembershipMoneyOperation.REFUND,
            )?.let { message ->
                busy.value = false
                formError.value = message
                return@launch
            }
            try {
                var actionId: String? = null
                var captureError: String? = null
                if (!appCtx.cacheIsolation.commitIfCurrent(scopeLease) commit@{
                        val profile = appCtx.shiftCache.profile.value
                        if (!canManageMembershipMoney(profile)) {
                            captureError =
                                "Only a protected owner with membership administration access can accept a refund."
                            return@commit
                        }
                        val terminalId = appCtx.terminalStore.terminalId()
                        val openShift = if (terminalId == null) null else ShiftResolutionPolicy.resolve(
                            db.shiftDao().currentForTerminal(terminalId),
                            db.shiftDao().serverOpen(terminalId),
                        )
                        if (openShift == null) {
                            captureError =
                                "Open a POS shift on this tablet before recording a membership refund."
                            return@commit
                        }
                        val actor = profile?.let { ShiftActor(it.userId, it.protectedAccess) }
                        if (!openShift.canManageMoney(actor)) {
                            captureError = openShift.moneyAccessMessage(actor)
                                ?: "Only the shift opener or a protected owner can record this refund."
                            return@commit
                        }
                        if (
                            db.membershipRefundMoneyDao().unresolvedActionCountForMembership(subscriptionId) > 0 ||
                            db.membershipRefundMoneyDao().unresolvedTaskCountForMembership(subscriptionId) > 0
                        ) {
                            captureError =
                                "This membership already has a refund task. Resolve it before creating another."
                            return@commit
                        }
                        val root = "membership-refund:${UUID.randomUUID()}"
                        actionId = root
                        db.membershipRefundMoneyDao().insertAction(
                            LocalMembershipRefundActionEntity(
                                actionId = root,
                                rootClientActionId = root,
                                kind = MembershipRefundActionKind.ACCEPT,
                                customerId = customerId,
                                membershipId = subscriptionId,
                                paymentId = paymentId,
                                shiftId = openShift.shiftId,
                                branchId = profile?.branchId,
                                terminalId = terminalId,
                                actorUserId = profile?.userId,
                                paidVia = method,
                                expectedAmountMinor = expectedAmountMinor,
                                reason = cleanReason,
                                createdAtMillis = System.currentTimeMillis(),
                            ),
                        )
                    }
                ) return@launch
                captureError?.let {
                    busy.value = false
                    formError.value = it
                    return@launch
                }
                val capturedActionId = requireNotNull(actionId)
                runCatching { appCtx.sync.sync() }
                when (val saved = db.membershipRefundMoneyDao().actionById(capturedActionId)) {
                    null -> formError.value =
                        "The saved refund acceptance could not be found. No payout is authorised; " +
                            "ask an owner to inspect this tablet."
                    else -> when (saved.state) {
                        MembershipMoneyActionState.SYNCED -> {
                            dialog.value = null
                            notice.value =
                                "Refund accepted and benefits held. No cash/provider payout has happened yet. " +
                                    "Open the task and start its physical/provider step."
                        }
                        MembershipMoneyActionState.REJECTED -> formError.value =
                            "The server did not accept this refund: " +
                                (saved.lastError ?: "request refused") +
                                ". No payout is authorised; correct the cause and retry this saved action."
                        else -> formError.value =
                            "Refund acceptance is unverified. Do not hand over cash or start a provider refund. " +
                                "Reconnect and retry this exact saved action."
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                formError.value = "Could not prepare this refund: ${e.message}"
            } finally {
                busy.value = false
            }
        }
    }

    fun beginRefund(task: MembershipRefundTaskCacheEntity) {
        if (busy.value) return
        busy.value = true
        formError.value = null
        viewModelScope.launch {
            try {
                membershipMoneyOfflineMessage(
                    appCtx.connectivity.online.value,
                    MembershipMoneyOperation.REFUND,
                )?.let { throw IllegalStateException(it) }
                val kind = if (task.method == "cash") {
                    MembershipRefundActionKind.BEGIN_CASH
                } else {
                    MembershipRefundActionKind.BEGIN_PROVIDER
                }
                val expected = if (task.method == "cash") {
                    MembershipRefundTaskStatus.CASH_HANDOFF_IN_PROGRESS
                } else {
                    MembershipRefundTaskStatus.PROVIDER_ACTION_IN_PROGRESS
                }
                val actionId = saveRefundStage(
                    task,
                    kind,
                    allowedStatuses = setOf(
                        if (task.method == "cash") {
                            MembershipRefundTaskStatus.ACCEPTED_CASH_DUE
                        } else {
                            MembershipRefundTaskStatus.ACCEPTED_PROVIDER_DUE
                        },
                    ),
                )
                runCatching { appCtx.sync.sync() }
                val saved = db.membershipRefundMoneyDao().actionById(actionId)
                val latest = db.membershipRefundMoneyDao().taskById(task.id)
                when {
                    latest?.status == expected -> {
                        dialog.value = null
                        notice.value = membershipRefundStageMessage(expected)
                    }
                    saved?.state == MembershipMoneyActionState.REJECTED -> formError.value =
                        "The server did not open the payout step: ${saved.lastError ?: "request refused"}. Do not hand over cash or approve a provider refund."
                    else -> formError.value =
                        "The payout step could not be verified. Do not move money; retry this exact saved action after reconnecting."
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                formError.value = e.message ?: "Could not start this membership refund."
            } finally {
                busy.value = false
            }
        }
    }

    fun completeRefund(
        task: MembershipRefundTaskCacheEntity,
        externalReference: String?,
        takeoverReason: String?,
    ) {
        if (busy.value) return
        val cleanReference = externalReference?.trim()?.takeIf(String::isNotEmpty)
        if (task.method != "cash" && (cleanReference?.length ?: 0) < 3) {
            formError.value = "Enter the completed provider refund reference."
            return
        }
        val profile = appCtx.shiftCache.profile.value
        val takeover = task.actionStartedBy != null && task.actionStartedBy != profile?.userId
        val cleanTakeover = takeoverReason?.trim()?.takeIf(String::isNotEmpty)
        if (takeover && (cleanTakeover?.length ?: 0) < 3) {
            formError.value =
                "${task.actionStartedByName ?: "Another owner"} started this payout. Enter why you are taking it over."
            return
        }
        busy.value = true
        formError.value = null
        viewModelScope.launch {
            try {
                val expectedStatus = if (task.method == "cash") {
                    MembershipRefundTaskStatus.CASH_HANDOFF_IN_PROGRESS
                } else {
                    MembershipRefundTaskStatus.PROVIDER_ACTION_IN_PROGRESS
                }
                val actionId = saveRefundStage(
                    task = task,
                    kind = if (task.method == "cash") {
                        MembershipRefundActionKind.COMPLETE_CASH
                    } else {
                        MembershipRefundActionKind.COMPLETE_PROVIDER
                    },
                    allowedStatuses = setOf(expectedStatus),
                    occurredAtMillis = System.currentTimeMillis(),
                    externalReference = cleanReference,
                    cashHandoverConfirmed = task.method == "cash",
                    takeoverConfirmed = takeover,
                    takeoverReason = cleanTakeover,
                )
                if (appCtx.connectivity.online.value) {
                    runCatching { appCtx.sync.sync() }
                } else {
                    appCtx.sync.requestSync()
                }
                val saved = db.membershipRefundMoneyDao().actionById(actionId)
                val latest = db.membershipRefundMoneyDao().taskById(task.id)
                when {
                    latest?.status == MembershipRefundTaskStatus.SETTLED -> {
                        dialog.value = null
                        notice.value = membershipRefundStageMessage(latest.status)
                    }
                    latest?.status == MembershipRefundTaskStatus.PAYOUT_COMPLETED_PENDING_POSTING -> {
                        dialog.value = null
                        notice.value = membershipRefundStageMessage(latest.status)
                    }
                    !appCtx.connectivity.online.value -> {
                        dialog.value = null
                        notice.value =
                            "Payout completion is saved on this tablet and waiting to sync. Do not pay again; the shift cannot close meanwhile."
                    }
                    saved?.state == MembershipMoneyActionState.REJECTED -> formError.value =
                        "The server refused the saved payout evidence: ${saved.lastError ?: "request refused"}. Do not pay again; use protected recovery."
                    else -> formError.value =
                        "The server outcome is unverified. Do not pay again. Retry only this saved completion action."
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                formError.value = e.message ?: "Could not preserve this completed refund."
            } finally {
                busy.value = false
            }
        }
    }

    fun resolveRefund(
        task: MembershipRefundTaskCacheEntity,
        resolution: String,
        reason: String,
        verificationReference: String? = null,
        takeoverReason: String? = null,
    ) {
        if (busy.value) return
        val cleanReason = reason.trim()
        val cleanVerification = verificationReference?.trim()?.takeIf(String::isNotEmpty)
        if (cleanReason.length < 3) {
            formError.value = "Enter a clear reason (at least 3 characters)."
            return
        }
        val noAction = task.status in setOf(
            MembershipRefundTaskStatus.ACCEPTED_CASH_DUE,
            MembershipRefundTaskStatus.ACCEPTED_PROVIDER_DUE,
        )
        val cash = task.method == "cash"
        val allowed = when {
            noAction && cash -> resolution == "cash_not_handed_over"
            noAction -> resolution == "provider_not_completed"
            cash -> resolution in setOf("cash_not_handed_over", "cash_returned")
            else -> resolution in setOf("provider_not_completed", "provider_reversed")
        }
        if (!allowed) {
            formError.value = "That recovery outcome does not match this refund rail and state."
            return
        }
        if (!noAction && !cash && (cleanVerification?.length ?: 0) < 3) {
            formError.value = "Enter the provider status/reversal verification reference."
            return
        }
        val profile = appCtx.shiftCache.profile.value
        val takeover = task.actionStartedBy != null && task.actionStartedBy != profile?.userId
        val cleanTakeover = takeoverReason?.trim()?.takeIf(String::isNotEmpty)
        if (takeover && (cleanTakeover?.length ?: 0) < 3) {
            formError.value =
                "${task.actionStartedByName ?: "Another owner"} started this payout. Enter why you are taking it over."
            return
        }
        busy.value = true
        formError.value = null
        viewModelScope.launch {
            try {
                membershipMoneyOfflineMessage(
                    appCtx.connectivity.online.value,
                    MembershipMoneyOperation.REFUND,
                )?.let { throw IllegalStateException(it) }
                val actionId = saveRefundStage(
                    task = task,
                    kind = MembershipRefundActionKind.WITHDRAW,
                    allowedStatuses = setOf(task.status),
                    occurredAtMillis = System.currentTimeMillis(),
                    externalReference = cleanVerification.takeIf { resolution == "provider_reversed" },
                    resolution = resolution,
                    reason = cleanReason,
                    actionStateVerified = !noAction,
                    providerVerificationStatus = when (resolution) {
                        "provider_not_completed" -> "not_completed"
                        "provider_reversed" -> "reversed"
                        else -> null
                    }.takeIf { !noAction },
                    providerVerificationReference = cleanVerification.takeIf { !cash && !noAction },
                    providerEvidenceOccurredAtMillis = System.currentTimeMillis().takeIf { !cash && !noAction },
                    cashReturnConfirmed = resolution == "cash_returned",
                    takeoverConfirmed = takeover,
                    takeoverReason = cleanTakeover,
                )
                runCatching { appCtx.sync.sync() }
                val saved = db.membershipRefundMoneyDao().actionById(actionId)
                val latest = db.membershipRefundMoneyDao().taskById(task.id)
                if (latest?.status == MembershipRefundTaskStatus.WITHDRAWN) {
                    dialog.value = null
                    notice.value = membershipRefundStageMessage(latest.status)
                } else if (saved?.state == MembershipMoneyActionState.REJECTED) {
                    formError.value =
                        "The server rejected this recovery evidence: ${saved.lastError ?: "request refused"}. The payout remains unresolved."
                } else {
                    formError.value =
                        "Recovery could not be verified. Do not move money again; retry this exact saved action."
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                formError.value = e.message ?: "Could not resolve this membership refund."
            } finally {
                busy.value = false
            }
        }
    }

    fun resolveLegacyRefund(
        attempt: MembershipRefundAttemptCacheEntity,
        outcome: String,
        reason: String,
        verificationReference: String?,
    ) {
        if (busy.value) return
        val cleanReason = reason.trim()
        val cleanReference = verificationReference?.trim()?.takeIf(String::isNotEmpty)
        val cash = attempt.paidVia == "cash"
        val financial = outcome in setOf("cash_handed_over", "provider_completed")
        if (cleanReason.length < 3) {
            formError.value = "Enter a clear recovery reason."
            return
        }
        if (!cash && (cleanReference?.length ?: 0) < 3) {
            formError.value = "Enter the provider verification reference."
            return
        }
        busy.value = true
        formError.value = null
        val scopeLease = appCtx.cacheIsolation.currentLease() ?: run {
            busy.value = false
            return
        }
        viewModelScope.launch {
            try {
                membershipMoneyOfflineMessage(
                    appCtx.connectivity.online.value,
                    MembershipMoneyOperation.REFUND,
                )?.let { throw IllegalStateException(it) }
                var actionId: String? = null
                var captureError: String? = null
                if (!appCtx.cacheIsolation.commitIfCurrent(scopeLease) commit@{
                        val profile = appCtx.shiftCache.profile.value
                        if (!canRecoverLegacyMembershipEvidence(profile)) {
                            captureError = MEMBERSHIP_AUDIT_CONTROL_MESSAGE
                            return@commit
                        }
                        val terminalId = appCtx.terminalStore.terminalId()
                        if (
                            terminalId != attempt.terminalId || profile?.branchId != attempt.branchId
                        ) {
                            captureError =
                                "Return to the exact account, branch, and terminal that owns this legacy task."
                            return@commit
                        }
                        val openShift = terminalId?.let {
                            ShiftResolutionPolicy.resolve(
                                db.shiftDao().currentForTerminal(it),
                                db.shiftDao().serverOpen(it),
                            )
                        }
                        if (financial && openShift == null) {
                            captureError =
                                "Open this terminal's POS shift before posting a verified historical payout."
                            return@commit
                        }
                        val id = "membership-refund-legacy-resolve:${attempt.id}"
                        val row = LocalMembershipRefundActionEntity(
                            actionId = id,
                            rootClientActionId = attempt.originalClientActionId,
                            kind = MembershipRefundActionKind.LEGACY_RESOLVE,
                            customerId = attempt.customerId,
                            membershipId = attempt.membershipId,
                            paymentId = attempt.paymentId,
                            shiftId = attempt.sourceShiftId,
                            branchId = attempt.branchId,
                            terminalId = attempt.terminalId,
                            actorUserId = profile?.userId,
                            paidVia = attempt.paidVia,
                            expectedAmountMinor = attempt.expectedAmountMinor,
                            reason = cleanReason,
                            occurredAtMillis = System.currentTimeMillis(),
                            resolution = outcome,
                            reconciliationShiftId = openShift?.shiftId.takeIf { financial },
                            providerVerificationStatus = when (outcome) {
                                "no_payout" -> "not_completed"
                                "provider_reversed" -> "reversed"
                                "provider_completed" -> "completed"
                                else -> null
                            },
                            providerVerificationReference = cleanReference.takeIf { !cash },
                            providerEvidenceOccurredAtMillis = System.currentTimeMillis().takeIf { !cash },
                            cashHandoverConfirmed = outcome == "cash_handed_over",
                            createdAtMillis = System.currentTimeMillis(),
                        )
                        val inserted = db.membershipRefundMoneyDao().insertAction(row)
                        val saved = if (inserted == -1L) {
                            db.membershipRefundMoneyDao().actionForStage(
                                attempt.originalClientActionId,
                                MembershipRefundActionKind.LEGACY_RESOLVE,
                            )
                        } else row
                        if (saved == null || saved != row) {
                            captureError =
                                "This legacy outcome is already saved with different evidence. Retry that exact action; do not overwrite it."
                            return@commit
                        }
                        actionId = saved.actionId
                    }
                ) error("The signed-in account changed. No recovery was saved.")
                captureError?.let(::error)
                val id = requireNotNull(actionId)
                runCatching { appCtx.sync.sync() }
                val saved = db.membershipRefundMoneyDao().actionById(id)
                if (saved?.state == MembershipMoneyActionState.SYNCED) {
                    dialog.value = null
                    notice.value =
                        "Legacy refund evidence resolved and retained in the server audit trail."
                } else if (saved?.state == MembershipMoneyActionState.REJECTED) {
                    formError.value = saved.lastError ?: "The server rejected this recovery evidence."
                } else {
                    formError.value =
                        "Recovery confirmation is unknown. Do not repeat any payout; retry this exact saved action."
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                formError.value = e.message ?: "Could not resolve this legacy refund."
            } finally {
                busy.value = false
            }
        }
    }

    fun retryRefundAction(actionId: String) {
        val scopeLease = appCtx.cacheIsolation.currentLease() ?: return
        viewModelScope.launch {
            val action = db.membershipRefundMoneyDao().actionById(actionId)
            if (
                action != null &&
                membershipRefundActionRequiresAuditControl(action.kind, action.state) &&
                !canRecoverLegacyMembershipEvidence(appCtx.shiftCache.profile.value)
            ) {
                notice.value = MEMBERSHIP_AUDIT_CONTROL_MESSAGE
                return@launch
            }
            if (!appCtx.cacheIsolation.commitIfCurrent(scopeLease) {
                    db.membershipRefundMoneyDao().retryAction(actionId)
                }
            ) return@launch
            appCtx.sync.requestSync()
        }
    }

    private suspend fun saveRefundStage(
        task: MembershipRefundTaskCacheEntity,
        kind: String,
        allowedStatuses: Set<String>,
        occurredAtMillis: Long? = null,
        externalReference: String? = null,
        resolution: String? = null,
        reason: String = task.reason,
        cashHandoverConfirmed: Boolean = false,
        actionStateVerified: Boolean = false,
        providerVerificationStatus: String? = null,
        providerVerificationReference: String? = null,
        providerEvidenceOccurredAtMillis: Long? = null,
        cashReturnConfirmed: Boolean = false,
        takeoverConfirmed: Boolean = false,
        takeoverReason: String? = null,
    ): String {
        val lease = appCtx.cacheIsolation.currentLease()
            ?: error("The signed-in account scope changed. Reopen Memberships and try again.")
        var actionId: String? = null
        var captureError: String? = null
        val committed = appCtx.cacheIsolation.commitIfCurrent(lease) commit@{
            val profile = appCtx.shiftCache.profile.value
            if (!canManageMembershipMoney(profile)) {
                captureError = "Only a protected owner can manage this membership refund."
                return@commit
            }
            val terminalId = appCtx.terminalStore.terminalId()
            if (
                terminalId == null || terminalId != task.terminalId ||
                profile?.branchId != task.branchId
            ) {
                captureError =
                    "Return to the same branch, terminal, and protected account scope that owns this refund."
                return@commit
            }
            val current = db.membershipRefundMoneyDao().taskById(task.id)
            if (current == null || current.status !in allowedStatuses) {
                captureError =
                    "This refund changed on another device. Refresh and review its latest state first."
                return@commit
            }
            val legacyBlock = db.membershipRefundMoneyDao()
                .unresolvedLegacyActionForServerRefund(task.id)
            if (legacyBlock != null) {
                captureError = legacyBlock.lastError
                    ?: "This refund came from an older app and its original payout evidence is still being reconciled. Do not move money or withdraw it as unpaid."
                return@commit
            }
            val openShift = ShiftResolutionPolicy.resolve(
                db.shiftDao().currentForTerminal(terminalId),
                db.shiftDao().serverOpen(terminalId),
            )
            val actor = profile?.let { ShiftActor(it.userId, it.protectedAccess) }
            if (openShift == null || openShift.shiftId != task.shiftId) {
                captureError =
                    "This refund belongs to a different or closed shift. Return to its open terminal shift."
                return@commit
            }
            if (!openShift.canManageMoney(actor)) {
                captureError = openShift.moneyAccessMessage(actor)
                return@commit
            }
            val deterministicId = when (kind) {
                MembershipRefundActionKind.BEGIN_CASH,
                MembershipRefundActionKind.BEGIN_PROVIDER,
                -> "membership-refund-begin:${task.id}"
                MembershipRefundActionKind.COMPLETE_CASH,
                MembershipRefundActionKind.COMPLETE_PROVIDER,
                -> "membership-refund-complete:${task.id}"
                MembershipRefundActionKind.WITHDRAW -> "membership-refund-withdraw:${task.id}"
                else -> error("Unsupported membership refund stage $kind")
            }
            val row = LocalMembershipRefundActionEntity(
                actionId = deterministicId,
                rootClientActionId = "membership-refund-task:${task.id}",
                serverRefundId = task.id,
                kind = kind,
                customerId = task.customerId.orEmpty(),
                membershipId = task.membershipId,
                paymentId = task.paymentId,
                shiftId = task.shiftId,
                branchId = task.branchId,
                terminalId = task.terminalId,
                actorUserId = profile?.userId,
                paidVia = task.method,
                expectedAmountMinor = task.amountMinor,
                reason = reason,
                occurredAtMillis = occurredAtMillis,
                externalReference = externalReference,
                resolution = resolution,
                providerVerificationStatus = providerVerificationStatus,
                providerVerificationReference = providerVerificationReference,
                providerEvidenceOccurredAtMillis = providerEvidenceOccurredAtMillis,
                cashHandoverConfirmed = cashHandoverConfirmed,
                actionStateVerified = actionStateVerified,
                cashReturnConfirmed = cashReturnConfirmed,
                actionTakeoverConfirmed = takeoverConfirmed,
                actionTakeoverReason = takeoverReason,
                createdAtMillis = System.currentTimeMillis(),
            )
            val inserted = db.membershipRefundMoneyDao().insertAction(row)
            val saved = if (inserted == -1L) {
                db.membershipRefundMoneyDao().actionForStage(row.rootClientActionId, kind)
            } else row
            if (saved == null || saved != row) {
                captureError =
                    "This stage is already saved with different immutable evidence. Do not repeat a payout; retry or reconcile the existing task."
                return@commit
            }
            actionId = saved.actionId
        }
        if (!committed) error("The signed-in account changed. No action was saved.")
        captureError?.let(::error)
        return requireNotNull(actionId)
    }

    fun retryRefund(localId: String) {
        val scopeLease = appCtx.cacheIsolation.currentLease() ?: return
        viewModelScope.launch {
            if (!appCtx.cacheIsolation.commitIfCurrent(scopeLease) {
                    db.membershipDao().retryRefund(localId)
                }
            ) return@launch
            appCtx.sync.requestSync()
        }
    }

    fun confirmCashHandover(localId: String) {
        if (busy.value) return
        val scopeLease = appCtx.cacheIsolation.currentLease() ?: return
        busy.value = true
        viewModelScope.launch {
            var changed = 0
            if (!appCtx.cacheIsolation.commitIfCurrent(scopeLease) {
                    changed = db.membershipDao().confirmCashRefundHandover(
                        localId,
                        System.currentTimeMillis(),
                    )
                }
            ) return@launch
            busy.value = false
            dialog.value = null
            if (changed == 1) {
                notice.value = "Cash handover saved. Settlement will sync before this shift can close."
                appCtx.sync.requestSync()
            } else {
                notice.value = "Refund state changed. Refresh before confirming cash again."
            }
        }
    }

    fun withdrawCashRefund(localId: String, reason: String) {
        if (busy.value) return
        val clean = reason.trim()
        if (clean.length < 3) {
            formError.value = "Enter why no cash was handed over."
            return
        }
        val scopeLease = appCtx.cacheIsolation.currentLease() ?: return
        busy.value = true
        formError.value = null
        viewModelScope.launch {
            var changed = 0
            if (!appCtx.cacheIsolation.commitIfCurrent(scopeLease) {
                    changed = db.membershipDao().requestRefundWithdrawal(
                        localId = localId,
                        reason = clean,
                        withdrawalAtMillis = System.currentTimeMillis(),
                    )
                }
            ) return@launch
            busy.value = false
            dialog.value = null
            if (changed == 1) {
                notice.value = "Unpaid cash refund withdrawal queued for audit and entitlement recovery."
                appCtx.sync.requestSync()
            } else {
                notice.value = "Refund state changed. Refresh before withdrawing it."
            }
        }
    }
}

private fun MembershipTierCacheEntity.toTier(): MembershipTier = MembershipTier(
    id = id, code = code, name = name, monthlyPriceMinor = monthlyPriceMinor,
    annualPriceMinor = annualPriceMinor, foodDiscountPct = foodDiscountPct,
    gamingDiscountPct = gamingDiscountPct, hookahDiscountPct = hookahDiscountPct,
    pointMultiplier = pointMultiplier, freeGamingMinutesPerWeek = freeGamingMinutesPerWeek,
    freeHookahPerMonth = freeHookahPerMonth, priorityBooking = priorityBooking,
    description = description, sortOrder = sortOrder,
)

private fun CustomerMembershipCacheEntity.toSubscription(): Subscription = Subscription(
    id = id, customerId = customerId, tierId = tierId, tierCode = tierCode, tierName = tierName,
    billingCycle = billingCycle, startsAt = startsAt, expiresAt = expiresAt,
    cancelledAt = cancelledAt, revokedAt = revokedAt, autoRenew = autoRenew,
    amountPaidMinor = amountPaidMinor,
    paymentId = paymentId, paymentMethod = paymentMethod, paymentShiftId = paymentShiftId,
    paymentReceiptNo = paymentReceiptNo, paymentPaidAt = paymentPaidAt,
    paymentEvidenceOccurredAt = paymentEvidenceOccurredAt,
    paymentEvidenceTimeUntrusted = paymentEvidenceTimeUntrusted,
    paymentProviderEvidenceReconciled = paymentProviderEvidenceReconciled,
    refundId = refundId, refundStatus = refundStatus, refundAcceptedAt = refundAcceptedAt,
    refundedAt = refundedAt, refundMethod = refundMethod,
    refundReceiptNo = refundReceiptNo, refundExternalReference = refundExternalReference,
    refundEvidenceOccurredAt = refundEvidenceOccurredAt,
    refundEvidenceTimeUntrusted = refundEvidenceTimeUntrusted,
    refundProviderEvidenceReconciled = refundProviderEvidenceReconciled,
    refundCustomerSpendReconciled = refundCustomerSpendReconciled,
    isActive = isActive,
)

private fun CustomerMembershipHistoryCacheEntity.toSubscription(): Subscription = Subscription(
    id = id, customerId = customerId, tierId = tierId, tierCode = tierCode, tierName = tierName,
    billingCycle = billingCycle, startsAt = startsAt, expiresAt = expiresAt,
    cancelledAt = cancelledAt, revokedAt = revokedAt, autoRenew = autoRenew,
    amountPaidMinor = amountPaidMinor,
    paymentId = paymentId, paymentMethod = paymentMethod, paymentShiftId = paymentShiftId,
    paymentReceiptNo = paymentReceiptNo, paymentPaidAt = paymentPaidAt,
    paymentEvidenceOccurredAt = paymentEvidenceOccurredAt,
    paymentEvidenceTimeUntrusted = paymentEvidenceTimeUntrusted,
    paymentProviderEvidenceReconciled = paymentProviderEvidenceReconciled,
    refundId = refundId, refundStatus = refundStatus, refundAcceptedAt = refundAcceptedAt,
    refundedAt = refundedAt, refundMethod = refundMethod,
    refundReceiptNo = refundReceiptNo, refundExternalReference = refundExternalReference,
    refundEvidenceOccurredAt = refundEvidenceOccurredAt,
    refundEvidenceTimeUntrusted = refundEvidenceTimeUntrusted,
    refundProviderEvidenceReconciled = refundProviderEvidenceReconciled,
    refundCustomerSpendReconciled = refundCustomerSpendReconciled,
    isActive = isActive,
)

private fun LocalSubscriptionEntity.toPendingRow(
    customers: List<CustomerCacheEntity>,
    tiers: List<MembershipTierCacheEntity>,
): PendingSubscriptionRow =
    PendingSubscriptionRow(
        localId = localId,
        customerName = customers.firstOrNull { it.id == customerId }?.name ?: "Unknown customer",
        tierName = tiers.firstOrNull { it.id == tierId }?.name ?: "Unknown tier",
        rejected = syncState == MembershipWriteState.REJECTED,
        error = lastError,
    )

private fun LocalMembershipCancellationEntity.toPendingRow(customers: List<CustomerCacheEntity>): PendingCancellationRow =
    PendingCancellationRow(
        localId = localId,
        customerName = customers.firstOrNull { it.id == customerId }?.name ?: "Unknown customer",
        rejected = syncState == MembershipWriteState.REJECTED,
        error = lastError,
    )

private fun LocalMembershipRefundEntity.toPendingRow(customers: List<CustomerCacheEntity>): PendingMembershipRefundRow =
    PendingMembershipRefundRow(
        localId = localId,
        customerName = customers.firstOrNull { it.id == customerId }?.name ?: "Unknown customer",
        amountMinor = expectedAmountMinor,
        syncState = syncState,
        rejected = syncState in setOf(
            MembershipRefundWriteState.REQUEST_REJECTED,
            MembershipRefundWriteState.CASH_SETTLE_REJECTED,
            MembershipRefundWriteState.WITHDRAWAL_REJECTED,
        ),
        error = lastError,
    )
