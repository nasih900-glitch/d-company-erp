package cloud.dcompany.erp.core.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

object MenuWriteState {
    const val PENDING = "pending"
    const val SYNCED = "synced"
    const val REJECTED = "rejected"
}

/**
 * Categories have no price fields at all, so unlike items they're fully
 * offline-capable end to end — same "one row covers create and edit" shape
 * as [LocalCustomerEntity], including the same lessons Phase 6's review
 * found the hard way, applied here from the start rather than rediscovered:
 * `version` guards a re-edit landing while an earlier snapshot of this row
 * is already mid-push (see SyncEngine.pushMenuCategoryOne), and
 * `serverId == null` means still-unsynced create / `!= null` means an edit
 * against an already-synced row, merged in `MenuViewModel.mergeCategories`.
 * `name`/`sortOrder` null on an edit row means "not touched by this edit."
 */
@Entity(tableName = "local_menu_categories", indices = [Index("state"), Index("serverId")])
data class LocalMenuCategoryEntity(
    @PrimaryKey val localId: String,
    val serverId: String? = null,
    val name: String? = null,
    val sortOrder: Int? = null,
    val createdAtMillis: Long,
    val state: String = MenuWriteState.PENDING,
    val lastError: String? = null,
    val version: Long = 0,
)

/**
 * Items are different from categories and from Customers: an item can
 * never be created offline at all — `POST /menu/items` requires
 * `base_price_minor`/`type`, both gated behind a pricing-unlock token that
 * can only be obtained online (see [cloud.dcompany.erp.core.auth.PricingLock]),
 * so item *creation* is a synchronous, online-only action with no outbox
 * involvement whatsoever. This table only ever holds a pending **edit**
 * against an item that's already in the cache — `serverId` is therefore
 * always non-null, no local-create case, no id-transition to track.
 *
 * It also only ever holds the non-price fields (name, description,
 * category, availability) — price/tax/HSN edits are a separate, always-
 * synchronous online-only action (same online-only constraint as create),
 * never queued here. `categoryId` here is always a real, already-synced
 * server category id: the item-edit form's category picker only offers
 * synced categories, specifically to avoid needing the same
 * local-category-id dependency-resolution machinery Shift/Gaming need for
 * their start-then-stop shape — a category created 30 seconds ago and not
 * yet synced isn't reassignable to an item until it has synced, which is
 * an acceptable, deliberate simplification (a brand new category doesn't
 * have any items on it yet in real usage anyway).
 */
@Entity(tableName = "local_menu_items", indices = [Index("state")])
data class LocalMenuItemEntity(
    @PrimaryKey val localId: String,
    val serverId: String,
    val categoryId: String? = null,
    val name: String? = null,
    val description: String? = null,
    val isAvailable: Boolean? = null,
    val createdAtMillis: Long,
    val state: String = MenuWriteState.PENDING,
    val lastError: String? = null,
    val version: Long = 0,
)
