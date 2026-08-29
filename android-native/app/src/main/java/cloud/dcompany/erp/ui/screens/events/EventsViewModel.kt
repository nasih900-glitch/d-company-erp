package cloud.dcompany.erp.ui.screens.events

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.dcompany.erp.DCompanyApp
import cloud.dcompany.erp.core.auth.EventsAccess
import cloud.dcompany.erp.core.auth.PricingLock
import cloud.dcompany.erp.core.auth.VIEW_ONLY_MESSAGE
import cloud.dcompany.erp.core.auth.authorizeAction
import cloud.dcompany.erp.core.auth.fetchAndCommitScoped
import cloud.dcompany.erp.core.db.EventCacheEntity
import cloud.dcompany.erp.core.db.EventTicketCacheEntity
import cloud.dcompany.erp.core.db.LocalCheckInEntity
import cloud.dcompany.erp.core.db.LocalTicketSaleEntity
import cloud.dcompany.erp.core.db.TicketSaleState
import cloud.dcompany.erp.core.db.cached
import cloud.dcompany.erp.core.db.store
import cloud.dcompany.erp.core.net.ApiClient
import cloud.dcompany.erp.core.net.ApiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

private const val BRANCHES_CACHE_KEY = "events_branches"

/** Which modal is up. Owned by the ViewModel so a rotation does not lose track of which form it was. */
sealed interface EventsDialog {
    data class EventForm(val editing: Event?) : EventsDialog
    data class SellTickets(val event: Event) : EventsDialog
    data class Tickets(val event: Event) : EventsDialog
    data class ConfirmDelete(val event: Event) : EventsDialog
    data class ConfirmCancel(val event: Event) : EventsDialog
}

/** A queued-but-not-yet-synced ticket sale, with the event's name resolved for display. */
data class PendingTicketSaleRow(
    val localId: String,
    val eventName: String,
    val customerName: String,
    val qty: Int,
    val rejected: Boolean,
    val error: String? = null,
)

data class PendingCheckInRow(
    val localId: String,
    val eventName: String,
    val ticketId: String,
    val rejected: Boolean,
    val error: String? = null,
)

data class EventsUiState(
    val loading: Boolean = true,
    val couldNotLoad: String? = null,
    val everSynced: Boolean = false,
    val syncing: Boolean = false,
    val events: List<Event> = emptyList(),
    val tickets: List<EventTicket> = emptyList(),
    val branches: List<Branch> = emptyList(),
    val pendingTicketSales: List<PendingTicketSaleRow> = emptyList(),
    val pendingCheckIns: List<PendingCheckInRow> = emptyList(),
    val dialog: EventsDialog? = null,
    val busy: Boolean = false,
    val formError: String? = null,
    val notice: String? = null,
    val showPricingUnlock: Boolean = false,
) {
    val upcoming: List<Event> get() = events.filter { it.status in setOf("scheduled", "live") }
    val past: List<Event> get() = events.filter { it.status in setOf("ended", "cancelled") }
}

/**
 * Events — a hybrid of Menu's shape (CRUD header entity behind a pricing
 * lock) and Inventory's GRN shape (insert-only ticket-sale rows under a
 * parent). Event create/edit/delete are online-only direct writes (see
 * EventsApi's class doc for why); ticket sales and check-ins are real
 * offline outbox resources — a walk-up ticket sale or a door check-in are
 * exactly the "spontaneous, maybe-offline, at-the-counter" actions this
 * whole rebuild exists for, unlike an event's own planned-in-advance
 * scheduling.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EventsViewModel : ViewModel() {

    private val appCtx = DCompanyApp.instance
    private val db = appCtx.db
    private val api = ApiClient.create<EventsApi>()
    @Volatile private var access = EventsAccess()

    private val pulling = MutableStateFlow(false)
    private val loadError = MutableStateFlow<String?>(null)
    private val branchesFlow = MutableStateFlow<List<Branch>>(emptyList())
    private val selectedEventId = MutableStateFlow<String?>(null)

    private val dialog = MutableStateFlow<EventsDialog?>(null)
    private val busy = MutableStateFlow(false)
    private val formError = MutableStateFlow<String?>(null)
    private val notice = MutableStateFlow<String?>(null)
    private val showPricingUnlock = MutableStateFlow(false)
    private var pendingAfterUnlock: (() -> Unit)? = null

    private data class FormState(
        val dialog: EventsDialog?,
        val busy: Boolean,
        val formError: String?,
        val notice: String?,
        val showPricingUnlock: Boolean,
    )

    val state: StateFlow<EventsUiState> = combine(
        combine(
            db.eventDao().observeEventCache(),
            db.syncMetaDao().observe("events"),
            pulling,
        ) { cache, meta, syncing -> Triple(cache, meta, syncing) },
        combine(
            db.eventDao().observeLocalTicketSales(),
            db.eventDao().observeLocalCheckIns(),
        ) { sales, checkIns -> sales to checkIns },
        combine(branchesFlow, loadError) { b, e -> b to e },
        combine(dialog, busy, formError, notice, showPricingUnlock) { d, bs, fe, n, pu ->
            FormState(d, bs, fe, n, pu)
        },
    ) { eventData, pendingWrites, refData, form ->
        val (cache, meta, isSyncing) = eventData
        val (sales, checkIns) = pendingWrites
        val (branchList, err) = refData
        val events = cache.map { it.toEvent() }
        EventsUiState(
            loading = false,
            couldNotLoad = err,
            everSynced = meta != null,
            syncing = isSyncing,
            events = events,
            branches = branchList,
            pendingTicketSales = sales.map { it.toPendingRow(events) },
            pendingCheckIns = checkIns.map { it.toPendingRow(events) },
            dialog = form.dialog,
            busy = form.busy,
            formError = form.formError,
            notice = form.notice,
            showPricingUnlock = form.showPricingUnlock,
        )
    }.let { base ->
        val ticketsFlow = selectedEventId.flatMapLatest { id ->
            if (id == null) flowOf(emptyList()) else db.eventDao().observeTicketsFor(id)
        }
        combine(base, ticketsFlow) { s, tickets -> s.copy(tickets = tickets.map { it.toEventTicket() }) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EventsUiState())

    init {
        loadBranches()
        retry()
    }

    // -------------------------------------------------------------- loading

    fun retry() {
        appCtx.sync.requestSync()
        pulling.value = true
        viewModelScope.launch {
            try {
                appCtx.sync.refresh("events")
                loadError.value = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                loadError.value = e.message
            } finally {
                pulling.value = false
            }
        }
    }

    private fun loadBranches() {
        viewModelScope.launch {
            db.reportSnapshotDao().cached<List<Branch>>(BRANCHES_CACHE_KEY)?.let { (cached, _) ->
                branchesFlow.value = cached
            }
            try {
                lateinit var fresh: List<Branch>
                val committed = appCtx.cacheIsolation.fetchAndCommitScoped(
                    fetch = { api.branches() },
                    store = {
                        fresh = it
                        db.reportSnapshotDao().store(BRANCHES_CACHE_KEY, it)
                    },
                )
                if (!committed) return@launch
                branchesFlow.value = fresh
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Cached branches (if any) stay showing.
            }
        }
    }

    fun selectEvent(event: Event?) {
        selectedEventId.value = event?.id
        if (event != null) {
            viewModelScope.launch { appCtx.sync.pullTicketsFor(event.id) }
        }
    }

    // -------------------------------------------------------------- dialogs

    fun updateAccess(next: EventsAccess) {
        access = next
    }

    private fun requireManage(): Boolean = authorizeAction(access.canManageEvents) {
        notice.value = VIEW_ONLY_MESSAGE
    }

    private fun requireCheckIn(): Boolean = authorizeAction(access.canCheckInTickets) {
        notice.value = VIEW_ONLY_MESSAGE
    }

    /** Always online-only — see the class doc comment. Shows the unlock
     * dialog first if pricing isn't already unlocked (create always needs
     * it, since base_ticket_price_minor is a required create field). */
    fun openCreateForm() {
        if (!requireManage()) return
        val open: () -> Unit = { dialog.value = EventsDialog.EventForm(editing = null) }
        if (PricingLock.currentToken(appCtx.tokens.currentPricingSession()) != null) {
            open()
        } else {
            pendingAfterUnlock = open
            showPricingUnlock.value = true
        }
    }

    /** Editing doesn't require an unlock up front — only submitting an
     * actual price change does (checked in saveEvent). */
    fun openEditForm(event: Event) {
        if (!requireManage()) return
        dialog.value = EventsDialog.EventForm(editing = event)
    }

    fun openSellTickets(event: Event) {
        if (!requireCheckIn()) return
        dialog.value = EventsDialog.SellTickets(event)
    }

    fun openTickets(event: Event) {
        selectEvent(event)
        dialog.value = EventsDialog.Tickets(event)
    }

    fun openConfirmDelete(event: Event) {
        if (!requireManage()) return
        dialog.value = EventsDialog.ConfirmDelete(event)
    }

    fun openConfirmCancel(event: Event) {
        if (!requireManage()) return
        formError.value = null
        dialog.value = EventsDialog.ConfirmCancel(event)
    }

    fun closeDialog() {
        dialog.value = null
        formError.value = null
    }

    fun dismissNotice() {
        notice.value = null
    }

    fun dismissFormError() {
        formError.value = null
    }

    fun dismissPricingUnlock() {
        showPricingUnlock.value = false
        pendingAfterUnlock = null
    }

    fun pricingUnlocked() {
        if (!requireManage()) {
            showPricingUnlock.value = false
            pendingAfterUnlock = null
            return
        }
        val action = pendingAfterUnlock
        showPricingUnlock.value = false
        pendingAfterUnlock = null
        action?.invoke()
    }

    // ------------------------------------------------------------- events

    /**
     * [priceChanged] tells us whether to gate behind a fresh pricing unlock
     * — creating always needs it (price is a required create field);
     * editing only needs it if the price field actually moved from what
     * the event already has, matching Menu's exact "don't force a password
     * re-entry for a routine detail edit" precedent.
     */
    fun saveEvent(
        editingId: String?,
        name: String,
        description: String,
        eventType: String,
        screen: String,
        startsAt: String,
        endsAt: String?,
        capacity: Int,
        priceMinor: Long,
        posterUrl: String,
        branchId: String?,
        priceChanged: Boolean,
    ) {
        if (!requireManage()) return
        val run: () -> Unit = run@{
            if (!requireManage()) return@run
            if (!busy.value) {
                busy.value = true
                formError.value = null
                viewModelScope.launch {
                    try {
                        if (editingId == null) {
                            api.createEvent(
                                EventCreate(
                                    name = name.trim(),
                                    description = description.trim().ifBlank { null },
                                    eventType = eventType,
                                    screen = screen.trim().ifBlank { "Main Screen" },
                                    startsAt = startsAt,
                                    endsAt = endsAt,
                                    capacity = capacity,
                                    baseTicketPriceMinor = priceMinor,
                                    posterUrl = posterUrl.trim().ifBlank { null },
                                    branchId = branchId,
                                ),
                                "event-create:${UUID.randomUUID()}",
                            )
                            notice.value = "Event created."
                        } else {
                            api.updateEvent(
                                editingId,
                                EventUpdate(
                                    name = name.trim(),
                                    description = description.trim().ifBlank { null },
                                    eventType = eventType,
                                    screen = screen.trim().ifBlank { null },
                                    startsAt = startsAt,
                                    endsAt = endsAt,
                                    capacity = capacity,
                                    baseTicketPriceMinor = if (priceChanged) priceMinor else null,
                                    posterUrl = posterUrl.trim().ifBlank { null },
                                ),
                            )
                            notice.value = "Event updated."
                        }
                        busy.value = false
                        dialog.value = null
                        formError.value = null
                        appCtx.sync.refresh("events")
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        busy.value = false
                        formError.value = (e as? ApiException)?.message ?: "Could not save this event."
                    }
                }
            }
        }
        val needsUnlock = editingId == null || priceChanged
        if (!needsUnlock || PricingLock.currentToken(appCtx.tokens.currentPricingSession()) != null) {
            run()
        } else {
            pendingAfterUnlock = run
            showPricingUnlock.value = true
        }
    }

    /** Status-only transition (Mark live / Cancel event) — never touches
     * price, so never needs a pricing unlock. */
    fun setEventStatus(event: Event, status: String) {
        if (!requireManage()) return
        if (busy.value) return
        formError.value = null
        busy.value = true
        viewModelScope.launch {
            try {
                api.updateEvent(event.id, EventUpdate(status = status))
                busy.value = false
                notice.value = "Event status updated."
                if (status == "cancelled" && dialog.value is EventsDialog.ConfirmCancel) {
                    dialog.value = null
                }
                appCtx.sync.refresh("events")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                busy.value = false
                formError.value = (e as? ApiException)?.message ?: "Could not update this event."
            }
        }
    }

    fun deleteEvent(event: Event) {
        if (!requireManage()) return
        if (busy.value) return
        busy.value = true
        formError.value = null
        viewModelScope.launch {
            try {
                api.deleteEvent(event.id)
                busy.value = false
                dialog.value = null
                notice.value = "Event deleted."
                appCtx.sync.refresh("events")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                busy.value = false
                formError.value = (e as? ApiException)?.message ?: "Could not delete this event."
            }
        }
    }

    // --------------------------------------------------------- ticket sales

    fun sellTickets(
        eventId: String,
        customerName: String,
        customerPhone: String,
        seat: String,
        qty: Int,
        note: String,
    ) {
        if (!requireCheckIn()) return
        formError.value =
            "Ticket issuing is temporarily disabled until payment, invoice, shift, " +
                "and GST are recorded through POS. No ticket was created."
    }

    fun retryTicketSale(localId: String) {
        if (!requireCheckIn()) return
        val scopeLease = appCtx.cacheIsolation.currentLease() ?: return
        viewModelScope.launch {
            if (!appCtx.cacheIsolation.commitIfCurrent(scopeLease) {
                    db.eventDao().retryTicketSale(localId)
                }
            ) return@launch
            appCtx.sync.requestSync()
        }
    }

    fun discardRejectedTicketSale(localId: String) {
        if (!requireCheckIn()) return
        val scopeLease = appCtx.cacheIsolation.currentLease() ?: return
        viewModelScope.launch {
            if (!appCtx.cacheIsolation.commitIfCurrent(scopeLease) {
                    db.eventDao().discardRejectedTicketSale(localId)
                }
            ) return@launch
            notice.value = "Rejected ticket draft removed. No server ticket was issued."
        }
    }

    // -------------------------------------------------------------- check-in

    fun checkIn(eventId: String, ticket: EventTicket) {
        if (!requireCheckIn()) return
        if (busy.value) return
        val scopeLease = appCtx.cacheIsolation.currentLease() ?: return
        viewModelScope.launch {
            busy.value = true
            var inserted = false
            if (!appCtx.cacheIsolation.commitIfCurrent(scopeLease) {
                    if (db.eventDao().pendingCheckInForTicket(ticket.id) == null) {
                        db.eventDao().insertLocalCheckIn(
                            LocalCheckInEntity(
                                localId = UUID.randomUUID().toString(),
                                eventId = eventId,
                                ticketId = ticket.id,
                                createdAtMillis = System.currentTimeMillis(),
                            ),
                        )
                        inserted = true
                    }
                }
            ) return@launch
            busy.value = false
            if (!inserted) return@launch
            notice.value = "Check-in queued — will sync when back online."
            appCtx.sync.requestSync()
        }
    }

    fun retryCheckIn(localId: String) {
        if (!requireCheckIn()) return
        val scopeLease = appCtx.cacheIsolation.currentLease() ?: return
        viewModelScope.launch {
            if (!appCtx.cacheIsolation.commitIfCurrent(scopeLease) {
                    db.eventDao().retryCheckIn(localId)
                }
            ) return@launch
            appCtx.sync.requestSync()
        }
    }

    // ---------------------------------------------------------------- plumbing

    /** One local (Room) write, then the dialog closes — no network wait,
     * since the whole point is that this succeeds instantly offline too.
     * Same shape as InventoryViewModel.localMutate. */
    private fun localMutate(block: suspend () -> String) {
        if (busy.value) return
        val scopeLease = appCtx.cacheIsolation.currentLease() ?: return
        busy.value = true
        formError.value = null
        viewModelScope.launch {
            try {
                var message = ""
                if (!appCtx.cacheIsolation.commitIfCurrent(scopeLease) {
                        message = block()
                    }
                ) return@launch
                busy.value = false
                dialog.value = null
                formError.value = null
                notice.value = message
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                busy.value = false
                formError.value = "Could not save this locally: ${e.message}"
            }
        }
    }
}

private fun EventCacheEntity.toEvent(): Event = Event(
    id = id, name = name, description = description, eventType = eventType, screen = screen,
    startsAt = startsAt, endsAt = endsAt, capacity = capacity, sold = sold, remaining = remaining,
    baseTicketPriceMinor = baseTicketPriceMinor, sacCode = sacCode, taxRate = taxRate,
    status = status, posterUrl = posterUrl,
)

private fun EventTicketCacheEntity.toEventTicket(): EventTicket = EventTicket(
    id = id, ticketNo = ticketNo, eventId = eventId, eventName = eventName,
    customerName = customerName, customerPhone = customerPhone, seat = seat,
    pricePaidMinor = pricePaidMinor, status = status, checkedInAt = checkedInAt,
)

private fun LocalTicketSaleEntity.toPendingRow(events: List<Event>): PendingTicketSaleRow =
    PendingTicketSaleRow(
        localId = localId,
        eventName = events.firstOrNull { it.id == eventId }?.name ?: "Unknown event",
        customerName = customerName,
        qty = qty,
        rejected = syncState == TicketSaleState.REJECTED,
        error = lastError,
    )

private fun LocalCheckInEntity.toPendingRow(events: List<Event>): PendingCheckInRow =
    PendingCheckInRow(
        localId = localId,
        eventName = events.firstOrNull { it.id == eventId }?.name ?: "Unknown event",
        ticketId = ticketId,
        rejected = syncState == TicketSaleState.REJECTED,
        error = lastError,
    )
