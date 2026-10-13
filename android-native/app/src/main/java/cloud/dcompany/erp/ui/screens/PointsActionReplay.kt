package cloud.dcompany.erp.ui.screens

import cloud.dcompany.erp.core.db.LocalPointsRedemptionActionEntity
import cloud.dcompany.erp.core.net.OrderPointsRedemptionUpdateRequest
import cloud.dcompany.erp.core.net.OrderRewardRedemptionUpdateRequest
import cloud.dcompany.erp.core.net.Order

/** Rebuild only from the persisted intent. Canonical bill advances must never
 * rewrite an uncertain request body or its original optimistic version. */
internal fun LocalPointsRedemptionActionEntity.genericReplayBody() =
    OrderPointsRedemptionUpdateRequest(
        points = requireNotNull(points) { "Saved points request lost its exact points body." },
        expectedCheckoutVersion = expectedCheckoutVersion,
        expectedProgramRevision = programRevision,
    )

internal fun LocalPointsRedemptionActionEntity.rewardReplayBody() =
    OrderRewardRedemptionUpdateRequest(
        rewardKey = requireNotNull(rewardKey) { "Saved reward request lost its exact reward key." },
        expectedCheckoutVersion = expectedCheckoutVersion,
        expectedProgramRevision = programRevision,
    )

internal suspend fun replayCapturedPointsAction(
    action: LocalPointsRedemptionActionEntity,
    updatePoints: suspend (String, OrderPointsRedemptionUpdateRequest, String) -> Order,
    updateReward: suspend (String, OrderRewardRedemptionUpdateRequest, String) -> Order,
): Order = when (action.actionKind) {
    "generic" -> updatePoints(action.orderId, action.genericReplayBody(), action.actionId)
    "fixed_reward" -> updateReward(action.orderId, action.rewardReplayBody(), action.actionId)
    else -> error("Saved points request has an unsupported action kind.")
}

/** Reconciliation needs a fresh canonical bill obtained after exact replay;
 * the frozen idempotency response proves the request outcome but may predate
 * another accepted bill mutation. */
internal suspend fun loadCanonicalAfterReplay(
    orderId: String,
    loadCanonical: suspend (String) -> Order,
): Order = loadCanonical(orderId).also { canonical ->
    check(
        canonical.id == orderId &&
            canonical.status in setOf("open", "held", "void", "paid", "refunded"),
    ) {
        "The live bill changed while reconciling points."
    }
}

internal enum class CanonicalPointsDisposition { REVIEWABLE, VOID, SETTLED }

internal fun canonicalPointsDisposition(status: String): CanonicalPointsDisposition = when (status) {
    "open", "held" -> CanonicalPointsDisposition.REVIEWABLE
    "void" -> CanonicalPointsDisposition.VOID
    "paid", "refunded" -> CanonicalPointsDisposition.SETTLED
    else -> error("The live bill has an unsupported status while reconciling points.")
}

internal data class CanonicalPointsReplayEvidence(
    val accepted: Order,
    val canonical: Order,
)

internal class CanonicalPointsEvidenceException(cause: Throwable) :
    IllegalStateException("The exact points request replayed, but the live bill could not be verified.", cause)

/** Keeps the ordering explicit and testable: terminal evidence is never
 * consulted until the exact persisted request has produced a result. */
internal suspend fun replayCapturedPointsActionThenLoadCanonical(
    action: LocalPointsRedemptionActionEntity,
    updatePoints: suspend (String, OrderPointsRedemptionUpdateRequest, String) -> Order,
    updateReward: suspend (String, OrderRewardRedemptionUpdateRequest, String) -> Order,
    loadCanonical: suspend (String) -> Order,
): CanonicalPointsReplayEvidence {
    val accepted = replayCapturedPointsAction(action, updatePoints, updateReward)
    val canonical = try {
        loadCanonicalAfterReplay(action.orderId, loadCanonical)
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        throw CanonicalPointsEvidenceException(error)
    }
    return CanonicalPointsReplayEvidence(accepted = accepted, canonical = canonical)
}
