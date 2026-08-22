package cloud.dcompany.erp.ui.screens.memberships

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Field names copied verbatim from backend/app/api/v1/memberships/router.py
 * (TierRead, SubscriptionRead, SubscribeRequest) and cross-checked against
 * the already-shipped web app's MembershipTierDTO/SubscriptionDTO
 * (frontend/src/lib/erp-api.ts) and the already-shipped iOS native app's
 * MembershipTierDTO/SubscribeRequest structs, which matched exactly.
 * `*_minor` fields are paise and stay a Long the whole way; discount/
 * multiplier fields are genuine fractions (0.10 = 10% off), so those stay
 * Double, matching Ingredient's own qty-vs-money split.
 */

@Serializable
data class MembershipTier(
    val id: String,
    val code: String,
    val name: String,
    @SerialName("monthly_price_minor") val monthlyPriceMinor: Long,
    @SerialName("annual_price_minor") val annualPriceMinor: Long? = null,
    @SerialName("food_discount_pct") val foodDiscountPct: Double = 0.0,
    @SerialName("gaming_discount_pct") val gamingDiscountPct: Double = 0.0,
    @SerialName("hookah_discount_pct") val hookahDiscountPct: Double = 0.0,
    @SerialName("point_multiplier") val pointMultiplier: Double = 1.0,
    @SerialName("free_gaming_minutes_per_week") val freeGamingMinutesPerWeek: Int = 0,
    @SerialName("free_hookah_per_month") val freeHookahPerMonth: Int = 0,
    @SerialName("priority_booking") val priorityBooking: Boolean = false,
    val description: String? = null,
    @SerialName("sort_order") val sortOrder: Int = 0,
)

@Serializable
data class Subscription(
    val id: String,
    @SerialName("customer_id") val customerId: String,
    @SerialName("tier_id") val tierId: String,
    @SerialName("tier_code") val tierCode: String,
    @SerialName("tier_name") val tierName: String,
    @SerialName("billing_cycle") val billingCycle: String,
    @SerialName("starts_at") val startsAt: String,
    @SerialName("expires_at") val expiresAt: String,
    @SerialName("cancelled_at") val cancelledAt: String? = null,
    @SerialName("auto_renew") val autoRenew: Boolean,
    @SerialName("amount_paid_minor") val amountPaidMinor: Long,
    @SerialName("is_active") val isActive: Boolean,
)

@Serializable
data class SubscribeRequest(
    @SerialName("customer_id") val customerId: String,
    @SerialName("tier_id") val tierId: String,
    @SerialName("billing_cycle") val billingCycle: String = "monthly",
    @SerialName("paid_via") val paidVia: String = "cash",
)

fun billingCycleLabel(cycle: String): String = if (cycle == "annual") "Annual" else "Monthly"

/** "10%", "10.5%" — trailing zeros trimmed. Mirrors Finance's Double.asSharePct(). */
fun Double.asDiscountPct(): String {
    val pct = this * 100
    val text = String.format(java.util.Locale.UK, "%.2f", pct).trimEnd('0').trimEnd('.')
    return "$text%"
}
