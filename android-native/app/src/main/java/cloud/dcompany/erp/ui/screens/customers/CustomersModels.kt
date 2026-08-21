package cloud.dcompany.erp.ui.screens.customers

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Customers — phone-based loyalty.
 *
 * Field names are copied verbatim from `CustomerRead` in
 * backend/app/api/v1/customers/router.py. A typo here fails at runtime, not
 * compile time, so nothing in this file is invented.
 */

/**
 * 10 loyalty points = ₹1, i.e. one point is worth 10 paise. This mirrors
 * MINOR_PER_POINT in backend/app/services/pos/points.py, which is the only
 * place the rate is actually decided — the number here is for *display* of a
 * balance's worth, never for pricing a bill. The server prices redemptions.
 *
 * Earning is a separate rate: 2 points per ₹10 spent on gaming.
 */
const val MINOR_PER_POINT: Long = 10L

@Serializable
data class Customer(
    val id: String,
    val name: String? = null,
    val phone: String,
    val email: String? = null,
    /** ISO-8601 instant, e.g. "2001-04-09T00:00:00Z". */
    val birthday: String? = null,
    @SerialName("visit_count") val visitCount: Int = 0,
    @SerialName("total_spent_minor") val totalSpentMinor: Long = 0,
    /** Spendable balance. */
    @SerialName("loyalty_points") val loyaltyPoints: Int = 0,
    /** Only-ever-rises counter that drives the rank ladder. */
    @SerialName("lifetime_gaming_points_earned") val lifetimeGamingPointsEarned: Int = 0,
    @SerialName("gaming_rank") val gamingRank: String = "Rookie",
    @SerialName("gaming_rank_floor") val gamingRankFloor: Int = 0,
    @SerialName("next_gaming_rank") val nextGamingRank: String? = null,
    @SerialName("next_gaming_rank_floor") val nextGamingRankFloor: Int? = null,
    @SerialName("points_to_next_gaming_rank") val pointsToNextGamingRank: Int? = null,
    @SerialName("last_visit_at") val lastVisitAt: String? = null,
    val notes: String? = null,
    /**
     * UI-only, never present on a server response (defaults keep decode of a
     * real API payload unaffected). Non-null while this row reflects a local
     * write this device hasn't synced yet — see CustomersViewModel.mergeCustomers.
     */
    val pendingLocalId: String? = null,
    /** UI-only. The server's refusal reason, if this row's last sync attempt failed. */
    val rejectedError: String? = null,
    /**
     * UI-only. The underlying LocalCustomerEntity.localId, present whenever
     * this row originates from a local write REGARDLESS of state — unlike
     * [pendingLocalId], which is deliberately null on a rejected row (so the
     * "not synced yet" banner doesn't show alongside the "sync failed" one).
     * retrySync() needs the id precisely on a rejected row, so it reads this
     * field instead.
     */
    val localWriteId: String? = null,
) {
    /** What the spendable balance is worth, in paise. Never a Double. */
    val loyaltyValueMinor: Long get() = loyaltyPoints.toLong() * MINOR_PER_POINT

    val displayName: String get() = name?.takeIf { it.isNotBlank() } ?: "— no name —"

    val isPendingSync: Boolean get() = pendingLocalId != null
    val isRejected: Boolean get() = rejectedError != null

    /** Fill fraction (0f..1f) for the rank progress bar. */
    val rankProgress: Float
        get() {
            val ceiling = nextGamingRankFloor ?: return 1f
            val span = (ceiling - gamingRankFloor).coerceAtLeast(1)
            val done = (lifetimeGamingPointsEarned - gamingRankFloor).toFloat() / span
            return done.coerceIn(0f, 1f)
        }
}

/** POST /customers — upsert by phone: creates, or returns the existing row. */
@Serializable
data class CustomerUpsertBody(
    val phone: String,
    val name: String? = null,
    val email: String? = null,
    val birthday: String? = null,
    val notes: String? = null,
)

/**
 * PATCH /customers/{id}. The shared Json is configured with
 * `explicitNulls = false`, so a null field is omitted from the request rather
 * than sent as JSON null — which is what makes this a partial update instead
 * of a wipe of every field the form did not touch.
 */
@Serializable
data class CustomerUpdateBody(
    val name: String? = null,
    val phone: String? = null,
    val email: String? = null,
    val birthday: String? = null,
    val notes: String? = null,
)

// ------------------------------------------------------------------ dates

private val DAY_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)

private val BIRTHDAY_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("d MMMM", Locale.ENGLISH)

/**
 * The calendar day out of an ISO timestamp. Only the leading date is read:
 * the backend stores birthdays as an instant, and re-deriving the day through
 * a local time zone is how a 9 April birthday starts showing as 8 April.
 */
fun isoDay(raw: String?): LocalDate? {
    if (raw.isNullOrBlank()) return null
    return runCatching { LocalDate.parse(raw.take(10)) }.getOrNull()
}

/**
 * Midnight UTC as an ISO instant, which is what the web app sends and what
 * therefore round-trips back as the same calendar day.
 */
fun LocalDate.asUtcInstantString(): String =
    atStartOfDay(ZoneOffset.UTC).toInstant().toString()

fun LocalDate.asDayLabel(): String = format(DAY_FORMAT)

fun Customer.birthdayLabel(): String =
    isoDay(birthday)?.format(BIRTHDAY_FORMAT) ?: "—"

fun Customer.lastVisitLabel(): String =
    isoDay(lastVisitAt)?.format(DAY_FORMAT) ?: "Never"

// ------------------------------------------------------------------ counts

/**
 * Indian digit grouping for plain counts — 1,24,500 — matching how the money
 * formatter already groups, so points and rupees do not disagree on the same
 * row.
 */
fun Long.grouped(): String {
    val negative = this < 0
    val s = kotlin.math.abs(this).toString()
    val body = if (s.length <= 3) {
        s
    } else {
        val head = s.dropLast(3)
        val tail = s.takeLast(3)
        val chunks = mutableListOf<String>()
        var rest = head
        while (rest.length > 2) {
            chunks.add(0, rest.takeLast(2))
            rest = rest.dropLast(2)
        }
        if (rest.isNotEmpty()) chunks.add(0, rest)
        chunks.joinToString(",") + "," + tail
    }
    return if (negative) "-$body" else body
}

fun Int.grouped(): String = toLong().grouped()
