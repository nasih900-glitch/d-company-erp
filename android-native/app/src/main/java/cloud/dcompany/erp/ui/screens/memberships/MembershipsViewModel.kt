package cloud.dcompany.erp.ui.screens.memberships

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.dcompany.erp.DCompanyApp
import cloud.dcompany.erp.core.db.CustomerCacheEntity
import cloud.dcompany.erp.core.db.CustomerMembershipCacheEntity
import cloud.dcompany.erp.core.db.LocalMembershipCancellationEntity
import cloud.dcompany.erp.core.db.LocalSubscriptionEntity
import cloud.dcompany.erp.core.db.MembershipTierCacheEntity
import cloud.dcompany.erp.core.db.MembershipWriteState
import cloud.dcompany.erp.core.net.ApiClient
import cloud.dcompany.erp.core.net.ApiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
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
}

/** A synced-or-pending membership merged for display — mirrors IngredientRow's
 * "cache + any pending/rejected local write" shape, but simpler: subscribe/
 * cancel are Shape D/C (never edited), so there's no field-level merge, just
 * "which state is currently true for this customer." */
data class MembershipRow(
    val subscription: Subscription?,
    val pendingSubscribeLocalId: String? = null,
    val pendingCancelLocalId: String? = null,
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

data class MembershipsUiState(
    val loading: Boolean = true,
    val tiers: List<MembershipTier> = emptyList(),
    val search: String = "",
    val customers: List<CustomerCacheEntity> = emptyList(),
    val selectedCustomer: CustomerCacheEntity? = null,
    val selectedMembership: MembershipRow? = null,
    val pendingSubscriptions: List<PendingSubscriptionRow> = emptyList(),
    val pendingCancellations: List<PendingCancellationRow> = emptyList(),
    val dialog: MembershipsDialog? = null,
    val busy: Boolean = false,
    val formError: String? = null,
    val notice: String? = null,
)

/**
 * Memberships — greenfield, no prior native screen. Tier browsing is a
 * read-only cache (tier create/edit stays web-only, Settings → Memberships
 * already covers it). Subscribe (Shape D, mandatory idempotency) and
 * cancel (Shape C, targets an already-synced subscription only) are real
 * offline outbox resources — per the explicit product decision made before
 * this phase started, overriding the plan's original online-only
 * recommendation, since a membership sold at the counter is exactly the
 * kind of maybe-offline action the rest of this rebuild already treats
 * that way for tickets/GRN/adjustments.
 */
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

    val state: StateFlow<MembershipsUiState> = combine(
        combine(
            db.membershipDao().observeTierCache(),
            db.customerDao().observeCache(),
            search,
        ) { tiers, customers, q -> Triple(tiers, customers, q) },
        combine(
            db.membershipDao().observeLocalSubscriptions(),
            db.membershipDao().observeLocalCancellations(),
        ) { subs, cancels -> subs to cancels },
        selectedCustomerId,
        combine(dialog, busy, formError, notice) { d, bs, fe, n -> FormState(d, bs, fe, n) },
    ) { tierAndCustomers, pendingWrites, selectedId, form ->
        val (tiers, customers, q) = tierAndCustomers
        val (subs, cancels) = pendingWrites
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
        combine(base, membershipFlow, db.membershipDao().observeLocalSubscriptions(),
            db.membershipDao().observeLocalCancellations()) { s, cached, subs, cancels ->
            val customerId = s.selectedCustomer?.id
            val pendingSub = subs.firstOrNull { it.customerId == customerId && it.syncState != "synced" }
            val pendingCancel = cached?.let { c ->
                cancels.firstOrNull { it.subscriptionId == c.id && it.syncState != "synced" }
            }
            s.copy(
                selectedMembership = if (customerId == null) {
                    null
                } else {
                    MembershipRow(
                        subscription = cached?.toSubscription(),
                        pendingSubscribeLocalId = pendingSub?.localId,
                        pendingCancelLocalId = pendingCancel?.localId,
                    )
                },
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MembershipsUiState())

    init {
        loadTiers()
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
        busy.value = true
        formError.value = null
        viewModelScope.launch {
            if (db.membershipDao().pendingSubscriptionForCustomer(customerId) != null) {
                busy.value = false
                formError.value = "A subscription for this customer is already queued."
                return@launch
            }
            try {
                db.membershipDao().insertLocalSubscription(
                    LocalSubscriptionEntity(
                        localId = UUID.randomUUID().toString(),
                        customerId = customerId, tierId = tierId,
                        billingCycle = billingCycle, paidVia = paidVia,
                        createdAtMillis = System.currentTimeMillis(),
                    ),
                )
                busy.value = false
                dialog.value = null
                notice.value = "Subscription queued — will sync when back online."
                appCtx.sync.requestSync()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                busy.value = false
                formError.value = "Could not save this locally: ${e.message}"
            }
        }
    }

    fun retrySubscription(localId: String) {
        viewModelScope.launch {
            db.membershipDao().retrySubscription(localId)
            appCtx.sync.requestSync()
        }
    }

    // -------------------------------------------------------------- cancel

    fun cancelMembership(customerId: String, subscriptionId: String) {
        if (busy.value) return
        busy.value = true
        viewModelScope.launch {
            if (db.membershipDao().pendingCancellationForSubscription(subscriptionId) != null) {
                busy.value = false
                dialog.value = null
                return@launch
            }
            db.membershipDao().insertLocalCancellation(
                LocalMembershipCancellationEntity(
                    localId = UUID.randomUUID().toString(),
                    customerId = customerId, subscriptionId = subscriptionId,
                    createdAtMillis = System.currentTimeMillis(),
                ),
            )
            busy.value = false
            dialog.value = null
            notice.value = "Cancellation queued — will sync when back online."
            appCtx.sync.requestSync()
        }
    }

    fun retryCancellation(localId: String) {
        viewModelScope.launch {
            db.membershipDao().retryCancellation(localId)
            appCtx.sync.requestSync()
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
    cancelledAt = cancelledAt, autoRenew = autoRenew, amountPaidMinor = amountPaidMinor,
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
