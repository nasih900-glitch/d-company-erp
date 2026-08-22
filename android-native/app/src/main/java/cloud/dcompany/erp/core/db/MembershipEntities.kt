package cloud.dcompany.erp.core.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Tier create/edit stays web-only — Settings → Memberships already has a
 * working, shipped UI for it, so this screen only ever reads tiers, same
 * "don't rebuild what already exists elsewhere" reasoning that kept Events'
 * ticket-refund path out of scope. Wholesale-replaced read cache, same
 * shape as IngredientCacheEntity.
 */
@Entity(tableName = "membership_tier_cache")
data class MembershipTierCacheEntity(
    @PrimaryKey val id: String,
    val code: String,
    val name: String,
    val monthlyPriceMinor: Long,
    val annualPriceMinor: Long?,
    val foodDiscountPct: Double,
    val gamingDiscountPct: Double,
    val hookahDiscountPct: Double,
    val pointMultiplier: Double,
    val freeGamingMinutesPerWeek: Int,
    val freeHookahPerMonth: Int,
    val priorityBooking: Boolean,
    val description: String?,
    val sortOrder: Int,
)

/**
 * Per-customer cache, wholesale-replaced per parent — same shape as
 * EventTicketCacheEntity/CapitalEntryCacheEntity. Holds at most the single
 * row the backend returns (GET /memberships/customer/{id} only ever
 * returns the current *active* subscription, or none at all).
 */
@Entity(tableName = "customer_membership_cache", indices = [Index("customerId")])
data class CustomerMembershipCacheEntity(
    @PrimaryKey val id: String,
    val customerId: String,
    val tierId: String,
    val tierCode: String,
    val tierName: String,
    val billingCycle: String,
    val startsAt: String,
    val expiresAt: String,
    val cancelledAt: String?,
    val autoRenew: Boolean,
    val amountPaidMinor: Long,
    val isActive: Boolean,
)

object MembershipWriteState {
    const val PENDING = "pending"
    const val SYNCED = "synced"
    const val REJECTED = "rejected"
}

/**
 * Shape D — insert-only, one row per subscribe action. Mirrors
 * LocalTicketSaleEntity: no serverId-null-vs-set duality since a subscribe
 * is never edited after capture, only ever queued once.
 */
@Entity(tableName = "local_subscriptions")
data class LocalSubscriptionEntity(
    @PrimaryKey val localId: String,
    val customerId: String,
    val tierId: String,
    val billingCycle: String,
    val paidVia: String,
    val createdAtMillis: Long,
    val syncState: String = MembershipWriteState.PENDING,
    val lastError: String? = null,
)

/**
 * Shape C — no local create leg, always targets a subscription already
 * pulled from the server (same reasoning as LocalCheckInEntity: cancelling
 * a still-unsynced offline subscribe has no server id to target yet, so
 * cancel is only offered against an already-synced subscription row).
 */
@Entity(tableName = "local_membership_cancellations")
data class LocalMembershipCancellationEntity(
    @PrimaryKey val localId: String,
    val customerId: String,
    val subscriptionId: String,
    val createdAtMillis: Long,
    val syncState: String = MembershipWriteState.PENDING,
    val lastError: String? = null,
)
