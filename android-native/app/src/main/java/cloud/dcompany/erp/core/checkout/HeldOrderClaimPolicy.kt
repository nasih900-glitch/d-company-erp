package cloud.dcompany.erp.core.checkout

import cloud.dcompany.erp.core.db.HeldOrderCacheEntity
import cloud.dcompany.erp.core.db.LocalHeldOrderPaymentEntity
import cloud.dcompany.erp.core.db.SyncState
import cloud.dcompany.erp.core.net.CheckoutClaimResult
import cloud.dcompany.erp.core.net.Order
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A held-bill lease must be bound to one installed ERP client, not merely to
 * a user who may be signed in on several tablets.  The installation store is
 * the authority for this value; this policy only validates and canonicalises
 * it before any claim request can leave the process.
 */
object CheckoutClientInstancePolicy {
    const val UNAVAILABLE_MESSAGE =
        "This tablet could not verify its checkout identity. Restart the app before collecting payment."

    fun requireStable(value: String?): String {
        val candidate = value?.trim()?.takeIf(String::isNotEmpty)
            ?: throw CheckoutClientInstanceUnavailableException()
        val parsed = runCatching { UUID.fromString(candidate) }.getOrNull()
            ?: throw CheckoutClientInstanceUnavailableException()
        if (parsed.version() != 4 || parsed.toString() != candidate.lowercase()) {
            throw CheckoutClientInstanceUnavailableException()
        }
        return parsed.toString()
    }
}

class CheckoutClientInstanceUnavailableException :
    IllegalStateException(CheckoutClientInstancePolicy.UNAVAILABLE_MESSAGE)

/** Pure checkout invariants shared by the UI confirmation and sync recovery paths. */
object HeldOrderClaimPolicy {
    /** Do not let staff begin collecting against a lease about to expire. */
    const val CONFIRMATION_GUARD_MILLIS = 30_000L

    private val recoverablePaymentClaimCodes = setOf(
        "checkout_claim_required",
        "checkout_claim_expired",
        "checkout_claim_invalid",
        "checkout_claim_stale",
    )

    fun claimExpiryMillis(claim: CheckoutClaimResult): Long? =
        runCatching { Instant.parse(claim.expiresAt).toEpochMilli() }.getOrNull()

    /**
     * Before money is confirmed, any displayed bill change requires the user
     * to review the refreshed order again — even a same-value metadata change.
     */
    fun matchesDisplayedBill(
        order: HeldOrderCacheEntity,
        claim: CheckoutClaimResult,
    ): Boolean =
        claim.orderId == order.id &&
            claim.orderTotalMinor == order.totalMinor &&
            claim.paidMinor == order.paidMinor &&
            claim.dueMinor == order.dueMinor &&
            claim.orderVersion == order.checkoutVersion

    /**
     * After staff have confirmed money, recovery may take a newer server
     * version only when the exact total and remaining balance are unchanged.
     * A version-only change (for example customer name) must not strand money;
     * an amount change must never be silently accepted.
     */
    fun matchesConfirmedSettlement(
        payment: LocalHeldOrderPaymentEntity,
        claim: CheckoutClaimResult,
    ): Boolean =
        claim.orderId == payment.targetOrderId &&
            claim.orderTotalMinor == payment.expectedTotalMinor &&
            claim.dueMinor == payment.expectedDueMinor &&
            payment.amountMinor == payment.expectedDueMinor

    fun hasConfirmationWindow(expiresAtMillis: Long, nowMillis: Long): Boolean =
        expiresAtMillis - nowMillis > CONFIRMATION_GUARD_MILLIS

    fun shouldReacquireAfterPaymentError(code: String?): Boolean =
        code in recoverablePaymentClaimCodes

    /**
     * The server may have committed these requests even though this device has
     * no definitive answer. Keep the durable row pending and replay the exact
     * same body/key/token; never turn uncertainty into a second collection.
     * Claim conflict is also temporary because another active lease can end.
     */
    fun shouldReplayConfirmedPayment(status: Int?, code: String?): Boolean =
        status == null ||
            status >= 500 ||
            code == "idempotency_in_progress" ||
            code == "checkout_claim_conflict"

    fun paymentIdempotencyKey(localId: String): String = "held-payment:$localId"

    /**
     * A zero due amount alone is not enough: a positive order that has already
     * been paid also has zero due, but must never enter the member-benefit
     * finalizer. The server enforces the same exact-zero, never-paid contract.
     */
    fun isExactZeroTotal(
        totalMinor: Long,
        paidMinor: Long,
        dueMinor: Long,
    ): Boolean = totalMinor == 0L && paidMinor == 0L && dueMinor == 0L

    /** Stable across response-loss retries, while a changed bill gets a new key. */
    fun zeroFinalizationIdempotencyKey(orderId: String, orderVersion: Long): String =
        "held-zero:$orderId:v$orderVersion"
}

/**
 * Invariants for turning a private direct cart into an immutable payable bill.
 * Publication is the only allowed boundary between editable pricing and money.
 */
object DirectOrderPublishPolicy {
    fun idempotencyKey(localId: String): String {
        require(localId.isNotBlank()) { "A local order identity is required." }
        return "direct-publish:$localId"
    }

    /**
     * PREPARING stores the version immediately before publication. Once the
     * claim is durable, AWAITING_PAYMENT stores the server's single post-
     * publication bump. This lets process-death recovery reconstruct the exact
     * request without a new Room column or a guessed current version.
     */
    fun expectedVersionForReplay(
        syncState: String,
        storedCheckoutVersion: Long,
        hasDurableClaim: Boolean = syncState == SyncState.AWAITING_PAYMENT,
    ): Long {
        require(storedCheckoutVersion >= 1L) { "The saved bill version is invalid." }
        return when (syncState) {
            SyncState.PREPARING -> storedCheckoutVersion
            SyncState.PENDING, SyncState.AWAITING_PAYMENT -> if (hasDurableClaim) {
                require(storedCheckoutVersion > 1L) {
                    "The saved published bill version cannot be replayed safely."
                }
                storedCheckoutVersion - 1L
            } else {
                storedCheckoutVersion
            }
            else -> error("This local order is not at a checkout publication boundary.")
        }
    }

    fun matchesPricedOrder(
        order: Order,
        expectedPrePublishVersion: Long,
        claim: CheckoutClaimResult,
    ): Boolean {
        val orderVersionMatches = when (order.status) {
            "open" -> order.checkoutVersion == expectedPrePublishVersion
            "held" -> order.checkoutVersion == expectedPrePublishVersion + 1L
            else -> false
        }
        return order.id.isNotBlank() &&
            order.status in setOf("open", "held") &&
            orderVersionMatches &&
            claim.orderId == order.id &&
            claim.claimToken.isNotBlank() &&
            claim.orderVersion == expectedPrePublishVersion + 1L &&
            claim.orderTotalMinor == order.totalMinor &&
            claim.paidMinor == order.paidMinor &&
            claim.dueMinor == order.dueMinor &&
            order.paidMinor == 0L
    }

    /** Legacy direct outbox rows had no claim; new published direct rows do. */
    fun paymentNeedsClaim(payment: LocalHeldOrderPaymentEntity): Boolean =
        payment.requiresCheckoutClaim || payment.claimToken != null
}

enum class PreparedHeldCheckoutAction {
    NO_SELECTION,
    KEEP_EXACT_ORDER,
    CLOSE_AND_RELEASE_CLAIM,
    CLOSE_PAYMENT_OWNS_CLAIM,
}

/**
 * Pure identity rules for the held-order payment dialog. List position is
 * intentionally absent: reordering can never change which order is selected.
 */
object HeldCheckoutInteractionPolicy {
    /** Long enough to absorb the second pointer-up in a normal rapid double tap. */
    const val POST_CONFIRM_TAP_GUARD_MILLIS = 750L

    fun reconcilePreparedSelection(
        selectedOrderId: String?,
        cachedOrderIds: Set<String>,
        locallyConfirmedOrderIds: Set<String>,
        confirmingOrderId: String?,
    ): PreparedHeldCheckoutAction = when {
        selectedOrderId == null -> PreparedHeldCheckoutAction.NO_SELECTION
        selectedOrderId in locallyConfirmedOrderIds || selectedOrderId == confirmingOrderId ->
            PreparedHeldCheckoutAction.CLOSE_PAYMENT_OWNS_CLAIM
        selectedOrderId in cachedOrderIds -> PreparedHeldCheckoutAction.KEEP_EXACT_ORDER
        else -> PreparedHeldCheckoutAction.CLOSE_AND_RELEASE_CLAIM
    }

    fun confirmationTargetsPreparedOrder(
        callbackOrderId: String,
        preparedOrderId: String?,
    ): Boolean = callbackOrderId == preparedOrderId
}

/**
 * The Compose button also becomes visually disabled, but this atomic gate is
 * the correctness boundary if two checkout callbacks were already queued in
 * one frame. It is shared by money payments and exact-zero finalization.
 */
class OneShotHeldPaymentConfirmation(private val orderId: String) {
    private val consumed = AtomicBoolean(false)

    val isConsumed: Boolean get() = consumed.get()

    fun tryConsume(callbackOrderId: String): Boolean =
        callbackOrderId == orderId && consumed.compareAndSet(false, true)
}
