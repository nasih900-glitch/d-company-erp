package cloud.dcompany.erp.core.sync

import cloud.dcompany.erp.core.db.LocalKitchenAdvanceEntity
import cloud.dcompany.erp.core.net.ApiException
import cloud.dcompany.erp.ui.screens.kitchen.KitchenOrder
import cloud.dcompany.erp.ui.screens.kitchen.KitchenState
import kotlinx.coroutines.CancellationException
import java.util.UUID

internal enum class KitchenAdvanceDisposition {
    SATISFIED,
    KEEP_PENDING,
    NEEDS_ATTENTION,
}

internal data class KitchenAdvanceDecision(
    val disposition: KitchenAdvanceDisposition,
    val message: String? = null,
)

/**
 * Resolves one durable KDS advance against current server truth.
 *
 * Another tablet may legally move a ticket beyond this tablet's saved target,
 * which makes a replay look like a backwards transition. Likewise, a served or
 * cancelled ticket is absent from the active queue. Both outcomes satisfy the
 * saved intent; neither belongs in the rejected outbox.
 */
internal class KitchenAdvanceReconciler(
    private val setState: suspend (LocalKitchenAdvanceEntity) -> KitchenOrder,
    private val activeQueue: suspend () -> List<KitchenOrder>,
) {
    suspend fun reconcile(row: LocalKitchenAdvanceEntity): KitchenAdvanceDecision {
        validate(row)?.let { return it }

        return try {
            val response = setState(row)
            classifyObserved(row, response, source = "update response")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (updateFailure: Exception) {
            // HTTP 426 is an app-wide compatibility stop. The interceptor has
            // already published the update notice; preserve the row and let
            // SyncEngine abort the pass instead of hiding it as KDS recovery.
            if (updateFailure is ApiException && updateFailure.status == 426) {
                throw updateFailure
            }
            reconcileAfterFailure(row, updateFailure)
        }
    }

    private suspend fun reconcileAfterFailure(
        row: LocalKitchenAdvanceEntity,
        updateFailure: Exception,
    ): KitchenAdvanceDecision {
        val queue = try {
            activeQueue()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return if (updateFailure.isDefinitiveRefusal()) {
                needsAttention(
                    "The server refused this kitchen update: " +
                        updateFailure.readableMessage("request refused") +
                        ". Check the ticket, then retry or remove the saved update.",
                )
            } else {
                KitchenAdvanceDecision(
                    KitchenAdvanceDisposition.KEEP_PENDING,
                    "Could not confirm whether this kitchen update reached the server. " +
                        "Keep it saved, reconnect, and check again.",
                )
            }
        }

        val matches = queue.filter { it.id == row.orderId }
        if (matches.isEmpty()) {
            // The authoritative active queue is unpaginated and branch-scoped.
            // A valid saved ticket that is absent has already been served,
            // cancelled, voided, or otherwise left active kitchen work.
            return KitchenAdvanceDecision(KitchenAdvanceDisposition.SATISFIED)
        }
        if (matches.size > 1) {
            return needsAttention(
                "The server returned this ticket more than once, so its kitchen state " +
                    "could not be verified. Ask an owner to check the ticket before removing the saved update.",
            )
        }

        return classifyObserved(row, matches.single(), source = "active queue").let { decision ->
            if (decision.disposition == KitchenAdvanceDisposition.NEEDS_ATTENTION) {
                decision.copy(
                    message = decision.message ?: updateFailure.readableMessage("request refused"),
                )
            } else {
                decision
            }
        }
    }

    private fun validate(row: LocalKitchenAdvanceEntity): KitchenAdvanceDecision? {
        if (!row.localId.isUuid()) {
            return needsAttention(
                "This saved kitchen update has a damaged local reference. Check the ticket on " +
                    "another terminal, then remove this saved update.",
            )
        }
        if (!row.orderId.isUuid()) {
            return needsAttention(
                "This saved kitchen update has a damaged ticket reference. Check the kitchen " +
                    "queue, then remove this saved update.",
            )
        }
        if (KitchenState.from(row.targetState) == null) {
            return needsAttention(
                "This saved kitchen update uses an unknown target state. Update the app or " +
                    "check the ticket on another terminal, then remove this saved update.",
            )
        }
        if (row.requestedAtMillis <= 0L) {
            return needsAttention(
                "This saved kitchen update has invalid timing information. Check the ticket on " +
                    "another terminal, then remove this saved update.",
            )
        }
        return null
    }

    private fun classifyObserved(
        row: LocalKitchenAdvanceEntity,
        observed: KitchenOrder,
        source: String,
    ): KitchenAdvanceDecision {
        if (observed.id != row.orderId) {
            return needsAttention(
                "The server $source returned a different ticket. The saved kitchen update was " +
                    "kept for review.",
            )
        }
        val target = KitchenState.from(row.targetState)
            ?: return needsAttention("The saved kitchen target state is not supported by this app.")
        val current = KitchenState.from(observed.kitchenState)
            ?: return needsAttention(
                "The server reports an unfamiliar kitchen state (${observed.kitchenState}). " +
                    "Update the app or ask an owner to check the ticket.",
            )

        if (current.stage >= target.stage) {
            return KitchenAdvanceDecision(KitchenAdvanceDisposition.SATISFIED)
        }

        return needsAttention(
            "The ticket is ${current.label.lowercase()} on the server, so the saved change to " +
                "${target.label.lowercase()} could not be confirmed. Check the ticket, then retry " +
                "or remove this saved update.",
        )
    }

    private fun needsAttention(message: String) = KitchenAdvanceDecision(
        KitchenAdvanceDisposition.NEEDS_ATTENTION,
        message,
    )
}

private val KitchenState.stage: Int
    get() = when (this) {
        KitchenState.RECEIVED -> 0
        KitchenState.PREPARING -> 1
        KitchenState.READY -> 2
        KitchenState.SERVED -> 3
    }

private fun String.isUuid(): Boolean = runCatching { UUID.fromString(this) }.isSuccess

private fun Exception.isDefinitiveRefusal(): Boolean =
    this is ApiException && !mustPreserveOutbox

private fun Exception.readableMessage(fallback: String): String =
    message?.trim()?.takeIf(String::isNotEmpty) ?: fallback
