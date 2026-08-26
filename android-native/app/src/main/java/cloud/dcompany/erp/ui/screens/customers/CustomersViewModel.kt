package cloud.dcompany.erp.ui.screens.customers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.dcompany.erp.DCompanyApp
import cloud.dcompany.erp.core.auth.CustomersAccess
import cloud.dcompany.erp.core.auth.VIEW_ONLY_MESSAGE
import cloud.dcompany.erp.core.auth.authorizeAction
import cloud.dcompany.erp.core.db.CustomerCacheEntity
import cloud.dcompany.erp.core.db.CustomerWriteState
import cloud.dcompany.erp.core.db.LocalCustomerEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.util.UUID

/** Prefix marking a [Customer.id] as a still-unsynced local create, not a real server id. */
private const val LOCAL_PREFIX = "local:"

/** Draft of the add/edit form. `id == null` means "new customer". */
data class CustomerEditor(
    val id: String? = null,
    val originalPhone: String = "",
    val phone: String = "",
    /**
     * The phone number *is* the loyalty identity, so it stays locked while
     * editing until the user deliberately unlocks it. Editing it by accident
     * moves someone's points onto a number they do not own.
     */
    val phoneUnlocked: Boolean = false,
    val name: String = "",
    val email: String = "",
    val birthday: LocalDate? = null,
    val notes: String = "",
) {
    /** Strictly "no id at all" — used by save() to decide whether to insert a brand-new row. */
    val isNew: Boolean get() = id == null

    /**
     * True for a brand-new draft AND for a still-unsynced local create being
     * re-opened (`id` starts with [LOCAL_PREFIX]) — neither has an
     * established server identity yet, so the phone stays freely editable
     * and the "moves their loyalty history" warning (which is only true of
     * an already-synced customer) does not apply. Distinct from [isNew]
     * because save()'s branch dispatch still needs to tell "insert a new
     * row" apart from "amend the existing local draft row" — only the UI's
     * phone-lock/warning logic should treat the two draft states alike.
     */
    val isUnsyncedDraft: Boolean get() = id == null || id.startsWith(LOCAL_PREFIX)
    val phoneChanged: Boolean get() = phone.trim() != originalPhone
    /** Backend requires 4..20 characters; catching it here saves a round trip. */
    val phoneValid: Boolean get() = phone.trim().length in 4..20
}

data class CustomersUiState(
    val everSynced: Boolean = false,
    val syncing: Boolean = false,
    val rows: List<Customer> = emptyList(),
    val query: String = "",
    val selectedId: String? = null,
    val editor: CustomerEditor? = null,
    val saving: Boolean = false,
    val saveError: String? = null,
    val notice: String? = null,
) {
    val selected: Customer? get() = rows.firstOrNull { it.id == selectedId }

    val totalVisits: Long get() = rows.sumOf { it.visitCount.toLong() }
    val totalSpentMinor: Long get() = rows.sumOf { it.totalSpentMinor }
    val totalPoints: Long get() = rows.sumOf { it.loyaltyPoints.toLong() }
    val searching: Boolean get() = query.isNotBlank()

    /** No cache yet and a first sync is genuinely in flight. */
    val loading: Boolean get() = !everSynced && rows.isEmpty() && syncing

    /** No cache, and nothing is currently trying to fill it — needs a manual nudge. */
    val couldNotLoad: Boolean get() = !everSynced && rows.isEmpty() && !syncing
}

/**
 * Room-backed via [cloud.dcompany.erp.core.db.CustomerCacheEntity] (wholesale-
 * replaced read cache, same shape as Menu) merged at read time with
 * [LocalCustomerEntity] (this device's own not-yet-synced writes) — the
 * two-table master-data pattern, first use in this app. Search runs entirely
 * over the local cache now instead of a per-keystroke network call: since the
 * cache is a (bounded, see CustomerEntities.kt) wholesale pull, this is both
 * faster and works offline, at the cost of not finding a customer past the
 * pull's cap if the company ever has that many.
 */
class CustomersViewModel : ViewModel() {

    private val appCtx = DCompanyApp.instance
    private val db = appCtx.db

    private val query = MutableStateFlow("")

    /**
     * Selection is tracked by phone, not by [Customer.id] — a create's id
     * transitions from a "local:<uuid>" pseudo-id to the real server id the
     * instant it syncs (see SyncEngine.pushCustomerOne), and phone is this
     * app's actual stable identity for a customer (documented repeatedly
     * across this screen already). Tracking by id would drop the selection
     * on every single successful create; tracking by phone survives it for
     * free, at the cost of dropping selection across a deliberate phone
     * *change* (the rare "Fix typo" path) — an acceptable, much smaller gap.
     */
    private val selectedPhone = MutableStateFlow<String?>(null)
    private val editor = MutableStateFlow<CustomerEditor?>(null)
    private val saving = MutableStateFlow(false)
    private val saveError = MutableStateFlow<String?>(null)
    private val notice = MutableStateFlow<String?>(null)

    /**
     * Deliberately separate from [cloud.dcompany.erp.core.sync.SyncEngine.syncing]:
     * that flag only covers `sync()` (the outbox drain + menu pull), not the
     * independent `refresh("customers")` call `retry()` also fires — on a
     * cold cache, `sync()` can finish (flipping its flag false) before the
     * customers GET does, which briefly made `couldNotLoad` true — a false
     * "could not load, no connection" message while the load was still
     * genuinely in progress. This flag tracks only the customers pull itself.
     */
    private val pullingCustomers = MutableStateFlow(false)
    @Volatile private var access = CustomersAccess()

    val state: StateFlow<CustomersUiState> = combine(
        combine(db.customerDao().observeCache(), db.customerDao().observeLocal(), ::Pair),
        combine(db.syncMetaDao().observe("customers"), pullingCustomers, ::Pair),
        combine(query, selectedPhone, editor, ::Triple),
        combine(saving, saveError, notice, ::Triple),
    ) { cacheAndLocal, metaAndSyncing, uiA, uiB ->
        val (cache, local) = cacheAndLocal
        val (meta, isSyncing) = metaAndSyncing
        val (q, selPhone, ed) = uiA
        val (isSaving, saveErr, noticeText) = uiB
        val filtered = filterCustomers(mergeCustomers(cache, local), q)
        CustomersUiState(
            everSynced = meta != null,
            syncing = isSyncing,
            rows = filtered,
            query = q,
            selectedId = filtered.firstOrNull { it.phone == selPhone }?.id,
            editor = ed,
            saving = isSaving,
            saveError = saveErr,
            notice = noticeText,
        )
    }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CustomersUiState())

    init {
        retry()
    }

    // -------------------------------------------------------------- loading

    fun retry() {
        appCtx.sync.requestSync()
        // Set synchronously, before the launch — a value flipped only inside
        // the launched coroutine can lose the race with this combine's very
        // first collection, which is exactly the false-negative this flag
        // exists to avoid.
        pullingCustomers.value = true
        viewModelScope.launch {
            try {
                appCtx.sync.refresh("customers")
            } finally {
                pullingCustomers.value = false
            }
        }
    }

    fun search(text: String) {
        query.value = text
    }

    fun clearSearch() {
        query.value = ""
    }

    // ------------------------------------------------------------ selection

    fun select(customer: Customer) {
        selectedPhone.value = customer.phone
    }

    fun clearSelection() {
        selectedPhone.value = null
    }

    fun dismissNotice() {
        notice.value = null
    }

    fun updateAccess(next: CustomersAccess) {
        access = next
    }

    private fun requireWrite(): Boolean = authorizeAction(access.canManageCustomers) {
        notice.value = VIEW_ONLY_MESSAGE
    }

    /**
     * A rejected write is parked, not retried automatically (see
     * SyncEngine.pushable — only `state = pending` rows are drained). Flip
     * it back to pending so the next sync picks it up again; the server's
     * refusal reason stays visible until then in case the retry fails the
     * same way.
     *
     * Reads [Customer.localWriteId], not [Customer.pendingLocalId] — the
     * latter is deliberately null on exactly the rejected rows this function
     * exists to retry (see CustomersModels.kt), which made every real call
     * to this function a silent no-op.
     */
    fun retrySync(customer: Customer) {
        if (!requireWrite()) return
        val localId = customer.localWriteId ?: return
        val scopeLease = appCtx.cacheIsolation.currentLease() ?: return
        viewModelScope.launch {
            var queued = false
            if (!appCtx.cacheIsolation.commitIfCurrent(scopeLease) {
                    db.customerDao().getLocal(localId)?.let { row ->
                        db.customerDao().upsertLocal(
                            row.copy(
                                state = CustomerWriteState.PENDING,
                                lastError = null,
                                version = row.version + 1,
                            ),
                        )
                        queued = true
                    }
                }
            ) return@launch
            if (queued) appCtx.sync.requestSync()
        }
    }

    // --------------------------------------------------------------- editor

    fun startCreate() {
        if (!requireWrite()) return
        editor.value = CustomerEditor(
            // A search for a phone number that found nothing is almost
            // always about to become that customer, so seed the form.
            phone = query.value.trim().filter(Char::isDigit),
        )
        saveError.value = null
        notice.value = null
    }

    fun startEdit(customer: Customer) {
        if (!requireWrite()) return
        editor.value = CustomerEditor(
            id = customer.id,
            originalPhone = customer.phone,
            phone = customer.phone,
            name = customer.name.orEmpty(),
            email = customer.email.orEmpty(),
            birthday = isoDay(customer.birthday),
            notes = customer.notes.orEmpty(),
        )
        saveError.value = null
        notice.value = null
    }

    fun editorChanged(next: CustomerEditor) {
        editor.value = next
    }

    fun cancelEdit() {
        editor.value = null
        saveError.value = null
    }

    /**
     * Always writes locally first, then asks for a sync — never a direct
     * network call. This is what makes the save button work identically
     * online or offline: the outbox drains the instant a connection exists,
     * whether that's now or the next time this tablet comes back online.
     */
    fun save() {
        if (!requireWrite()) return
        val ed = editor.value ?: return
        if (saving.value || !ed.phoneValid) return
        val scopeLease = appCtx.cacheIsolation.currentLease() ?: return
        saving.value = true
        saveError.value = null

        viewModelScope.launch {
            try {
                val dao = db.customerDao()
                val now = System.currentTimeMillis()
                var selectedAfterSave: String? = null
                var missingDraft = false
                if (!appCtx.cacheIsolation.commitIfCurrent(scopeLease) {
                        when {
                            ed.isNew -> {
                                val local = LocalCustomerEntity(
                                    localId = UUID.randomUUID().toString(),
                                    serverId = null,
                                    phone = ed.phone.trim(),
                                    name = ed.name.trim().ifBlank { null },
                                    email = ed.email.trim().ifBlank { null },
                                    birthday = ed.birthday?.asUtcInstantString(),
                                    notes = ed.notes.trim().ifBlank { null },
                                    createdAtMillis = now,
                                )
                                dao.upsertLocal(local)
                                selectedAfterSave = local.phone
                            }
                            ed.id!!.startsWith(LOCAL_PREFIX) -> {
                                val localId = ed.id.removePrefix(LOCAL_PREFIX)
                                val existing = dao.getLocal(localId)
                                if (existing == null) {
                                    missingDraft = true
                                } else {
                                    val amended = existing.copy(
                                        phone = ed.phone.trim(),
                                        name = ed.name.trim().ifBlank { null },
                                        email = ed.email.trim().ifBlank { null },
                                        birthday = ed.birthday?.asUtcInstantString(),
                                        notes = ed.notes.trim().ifBlank { null },
                                        state = CustomerWriteState.PENDING,
                                        lastError = null,
                                        version = existing.version + 1,
                                    )
                                    dao.upsertLocal(amended)
                                    selectedAfterSave = amended.phone
                                }
                            }
                            else -> {
                                val serverId = ed.id
                                val existingPending = dao.pendingLocalForServerId(serverId)
                                val phoneToSend = ed.phone.trim().takeIf {
                                    ed.phoneUnlocked && it.isNotBlank() && it != ed.originalPhone
                                }
                                val local = (existingPending ?: LocalCustomerEntity(
                                    localId = UUID.randomUUID().toString(),
                                    serverId = serverId,
                                    createdAtMillis = now,
                                )).copy(
                                    phone = phoneToSend ?: existingPending?.phone,
                                    name = ed.name.trim().ifBlank { null },
                                    email = ed.email.trim().ifBlank { null },
                                    birthday = ed.birthday?.asUtcInstantString(),
                                    notes = ed.notes.trim().ifBlank { null },
                                    state = CustomerWriteState.PENDING,
                                    lastError = null,
                                    version = (existingPending?.version ?: -1) + 1,
                                )
                                dao.upsertLocal(local)
                                selectedAfterSave = phoneToSend ?: ed.originalPhone
                            }
                        }
                    }
                ) return@launch
                if (missingDraft) {
                    saveError.value = "This customer's local draft is gone — try again."
                    saving.value = false
                    return@launch
                }
                selectedPhone.value = selectedAfterSave
                editor.value = null
                saving.value = false
                // Deliberately not "was already on file" feedback like the
                // pre-offline version had for a fresh create — that depended
                // on the server's immediate response, which an offline queue
                // does not have. The dialog's own static copy already sets
                // that expectation, and the merge will show the real record
                // (with its true history) the moment this syncs.
                notice.value = "Saved — will sync when back online."
                appCtx.sync.requestSync()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Stay in the dialog with the typed values intact: a failed
                // save must never cost the user the record they just entered.
                saving.value = false
                saveError.value = e.message?.takeIf { it.isNotBlank() } ?: "Could not save this customer."
            }
        }
    }
}

/**
 * Local edits take visual precedence over the cache until a fresh pull
 * confirms them — a null field on a pending row means "not touched by this
 * edit" (see [LocalCustomerEntity]'s doc comment) so it falls back to the
 * cached value; a non-null field is the pending override.
 *
 * A pending row's real-history lookup is by serverId when known, falling
 * back to phone when not (a still-unsynced create) — this is also what lets
 * a cache row be suppressed by phone, not just by serverId: without it, a
 * "new" customer whose phone the till already created (or another manual
 * add already exists for) would show up as a second, phantom 0-point row
 * alongside the real one until the create's upsert reconciled them on sync.
 */
private fun mergeCustomers(
    cache: List<CustomerCacheEntity>,
    local: List<LocalCustomerEntity>,
): List<Customer> {
    val cacheById = cache.associateBy { it.id }
    val cacheByPhone = cache.associateBy { it.phone }
    val overriddenServerIds = local.mapNotNull { it.serverId }.toSet()
    val suppressedPhones = local.filter { it.serverId == null }.mapNotNull { it.phone }.toSet()
    val result = ArrayList<Customer>(cache.size + local.size)
    for (row in local) {
        val matchedCache = row.serverId?.let { cacheById[it] } ?: row.phone?.let { cacheByPhone[it] }
        result += row.toDisplay(matchedCache)
    }
    for (c in cache) {
        if (c.id in overriddenServerIds) continue
        if (c.phone in suppressedPhones) continue
        result += c.toDisplay()
    }
    return result
}

private fun filterCustomers(rows: List<Customer>, query: String): List<Customer> {
    val q = query.trim()
    if (q.isEmpty()) return rows
    return rows.filter {
        it.phone.contains(q, ignoreCase = true) || it.displayName.contains(q, ignoreCase = true)
    }
}

private fun LocalCustomerEntity.toDisplay(cache: CustomerCacheEntity?): Customer = Customer(
    id = serverId ?: "$LOCAL_PREFIX$localId",
    name = name ?: cache?.name,
    phone = phone ?: cache?.phone ?: "",
    email = email ?: cache?.email,
    birthday = birthday ?: cache?.birthday,
    visitCount = cache?.visitCount ?: 0,
    totalSpentMinor = cache?.totalSpentMinor ?: 0,
    loyaltyPoints = cache?.loyaltyPoints ?: 0,
    lifetimeGamingPointsEarned = cache?.lifetimeGamingPointsEarned ?: 0,
    gamingRank = cache?.gamingRank ?: "Rookie",
    gamingRankFloor = cache?.gamingRankFloor ?: 0,
    nextGamingRank = cache?.nextGamingRank,
    nextGamingRankFloor = cache?.nextGamingRankFloor,
    pointsToNextGamingRank = cache?.pointsToNextGamingRank,
    lastVisitAt = cache?.lastVisitAt,
    notes = notes ?: cache?.notes,
    pendingLocalId = if (state != CustomerWriteState.REJECTED) localId else null,
    rejectedError = if (state == CustomerWriteState.REJECTED) lastError else null,
    localWriteId = localId,
)

private fun CustomerCacheEntity.toDisplay(): Customer = Customer(
    id = id,
    name = name,
    phone = phone,
    email = email,
    birthday = birthday,
    visitCount = visitCount,
    totalSpentMinor = totalSpentMinor,
    loyaltyPoints = loyaltyPoints,
    lifetimeGamingPointsEarned = lifetimeGamingPointsEarned,
    gamingRank = gamingRank,
    gamingRankFloor = gamingRankFloor,
    nextGamingRank = nextGamingRank,
    nextGamingRankFloor = nextGamingRankFloor,
    pointsToNextGamingRank = pointsToNextGamingRank,
    lastVisitAt = lastVisitAt,
    notes = notes,
)
