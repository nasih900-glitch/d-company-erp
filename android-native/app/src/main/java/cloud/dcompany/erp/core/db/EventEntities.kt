package cloud.dcompany.erp.core.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Event create/edit/delete are online-only, direct writes — create always
 * needs a fresh X-Pricing-Token (base_ticket_price_minor is a required
 * create field, so there is no way to create an event without one), and
 * edits are rare/high-scrutiny, matching Finance's capital-entry-void
 * precedent (Phase 10) rather than being queued through an offline outbox.
 * So there is no local-outbox sibling for the event header row itself —
 * only a wholesale-replaced read cache, same shape as IngredientCacheEntity.
 */
@Entity(tableName = "event_cache")
data class EventCacheEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String?,
    val eventType: String,
    val screen: String,
    val startsAt: String,
    val endsAt: String?,
    val capacity: Int,
    val sold: Int,
    val remaining: Int,
    val baseTicketPriceMinor: Long,
    val sacCode: String,
    val taxRate: Double,
    val status: String,
    val posterUrl: String?,
)

/**
 * Per-event cache, wholesale-replaced per parent — same shape as
 * BatchCacheEntity/CapitalEntryCacheEntity, pulled on demand when an
 * event's ticket list is opened.
 */
@Entity(tableName = "event_ticket_cache", indices = [Index("eventId")])
data class EventTicketCacheEntity(
    @PrimaryKey val id: String,
    val eventId: String,
    val ticketNo: String,
    val eventName: String,
    val customerName: String?,
    val customerPhone: String?,
    val seat: String?,
    val pricePaidMinor: Long,
    val status: String,
    val checkedInAt: String?,
)

object TicketSaleState {
    const val PENDING = "pending"
    const val SYNCED = "synced"
    const val REJECTED = "rejected"
}

/**
 * Shape D — insert-only, one row per "sell qty N tickets" action. Mirrors
 * LocalGrnEntity/LocalAdjustmentEntity: no serverId-null-vs-set duality is
 * needed since a ticket sale is never edited after capture, only ever
 * queued once and later synced.
 */
@Entity(tableName = "local_ticket_sales")
data class LocalTicketSaleEntity(
    @PrimaryKey val localId: String,
    val eventId: String,
    val customerName: String,
    val customerPhone: String?,
    val seat: String?,
    val qty: Int,
    val note: String?,
    val createdAtMillis: Long,
    val syncState: String = TicketSaleState.PENDING,
    val lastError: String? = null,
)

/**
 * Shape C — no local create leg, always targets a ticket already pulled
 * from the server (same reasoning as Refunds: a check-in can only ever
 * target a real, already-synced ticket id — a ticket sold offline and not
 * yet itself synced has no server id to check in against yet, so the
 * check-in action is only offered for already-synced ticket rows).
 */
@Entity(tableName = "local_check_ins")
data class LocalCheckInEntity(
    @PrimaryKey val localId: String,
    val eventId: String,
    val ticketId: String,
    val createdAtMillis: Long,
    val syncState: String = TicketSaleState.PENDING,
    val lastError: String? = null,
)
