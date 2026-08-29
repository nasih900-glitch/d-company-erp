package cloud.dcompany.erp.core.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * The full customer list, wholesale-replaced on every pull — same shape as
 * [MenuItemEntity]. Unlike Menu, this is capped at whatever the backend's
 * `GET /customers` `limit` allows in one call (500, no pagination today), so
 * a company with more distinct phone numbers than that will not see every
 * customer in local search until the backend grows a paged pull. Realistic
 * for a single cafe's customer base for the foreseeable future, not a hidden
 * assumption — see [cloud.dcompany.erp.core.sync.SyncEngine.pullCustomers].
 */
@Entity(tableName = "customer_cache")
data class CustomerCacheEntity(
    @PrimaryKey val id: String,
    val name: String?,
    val phone: String,
    val email: String?,
    val birthday: String?,
    val visitCount: Int,
    val totalSpentMinor: Long,
    val loyaltyPoints: Int,
    val lifetimeGamingPointsEarned: Int,
    val gamingRank: String,
    val gamingRankFloor: Int,
    val nextGamingRank: String?,
    val nextGamingRankFloor: Int?,
    val pointsToNextGamingRank: Int?,
    val lastVisitAt: String?,
    val notes: String?,
)

/**
 * Recent server-authoritative purchases for one stable customer id. This is a
 * read cache, not an accounting ledger: the backend remains authoritative and
 * a scoped refresh replaces this customer's complete bounded result set.
 */
@Entity(
    tableName = "customer_order_history_cache",
    indices = [Index("customerId"), Index("createdAt")],
)
data class CustomerOrderHistoryEntity(
    @PrimaryKey val id: String,
    val customerId: String,
    val invoiceNo: String?,
    val status: String,
    val type: String,
    val sourceLabel: String?,
    val totalMinor: Long,
    val paidMinor: Long,
    val refundedMinor: Long,
    val pointsRedeemedMinor: Long,
    val itemsCount: Int,
    /** Comma-separated normalized payment rails; values contain no commas. */
    val paymentMethods: String,
    val createdAt: String,
    val invoiceIssuedAt: String?,
)

object CustomerWriteState {
    const val PENDING = "pending"
    const val SYNCED = "synced"
    const val REJECTED = "rejected"
}

/**
 * One row per in-flight customer write, covering both create (upsert-by-
 * phone) and edit (absolute-PATCH) with a single shape — deliberately
 * simpler than Shift/Gaming's two-leg dependency chain, because neither
 * customer write depends on anything else this app creates offline:
 *
 * - `serverId == null` means this row is a **create**: `phone` is always
 *   present (required by the backend), the other fields are whatever the
 *   form held. Pushed via `POST /customers`.
 * - `serverId != null` means this row is an **edit** against an
 *   already-cached, already-synced customer: `phone` is null unless the
 *   user deliberately unlocked and changed it (mirrors the pre-offline
 *   app's "only send phone if changed" rule — see CustomersViewModel.save).
 *   Pushed via `PATCH /customers/{serverId}`.
 *
 * Editing a customer a *second* time before the first write has synced does
 * not create a second row: CustomersViewModel.save() finds and amends the
 * existing pending row for the same identity (by `localId` for a still-
 * local create, by `serverId` for a pending edit) instead — so there is
 * never more than one pending write in flight per customer, and never an
 * id-resolution dependency to wait on the way Shift/Gaming's start-then-stop
 * shape needs. Deleted once `state = synced` and the next cache pull has
 * confirmed it (see SyncEngine.pushCustomerOne) — a synced row has nothing
 * left to say that the cache doesn't already say better.
 *
 * `version` guards the one race that amend-in-place shape opens up: a save()
 * amending this exact row while SyncEngine has *already* read an earlier
 * snapshot of it and is mid-flight on the network call for that snapshot.
 * Without this, the in-flight push's completion would blindly mark the row
 * (now holding the newer edit) synced and delete it — discarding the newer
 * edit's content, which was never actually sent. save() increments this on
 * every amend; SyncEngine.pushCustomerOne's markSynced only applies if the
 * version is still what it pushed (see CustomerDao.markSynced) — if a newer
 * edit landed in the meantime, the row simply stays pending with the newer
 * content for the next sync() pass to pick up and actually send.
 */
@Entity(tableName = "local_customers", indices = [Index("state"), Index("serverId")])
data class LocalCustomerEntity(
    @PrimaryKey val localId: String,
    val serverId: String? = null,
    val phone: String? = null,
    val name: String? = null,
    val email: String? = null,
    val birthday: String? = null,
    val notes: String? = null,
    val createdAtMillis: Long,
    val state: String = CustomerWriteState.PENDING,
    val lastError: String? = null,
    val version: Long = 0,
)
