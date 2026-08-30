package cloud.dcompany.erp.core.errors

import cloud.dcompany.erp.core.net.ApiException

/**
 * The safety consequence of retrying an operation after an incomplete result.
 *
 * UI code must select the risk from the operation being performed, not infer it
 * from the exception. In particular, a timeout after a financial POST demands
 * the opposite advice from a timeout while refreshing a read-only dashboard.
 */
internal enum class RecoveryRisk {
    READ,
    IDEMPOTENT_WRITE,
    FINANCIAL_WRITE,
    LOCAL_CAPTURE,
    AUTH,
}

internal data class RecoveryGuidance(
    val message: String,
    /** Safe for a deliberate user retry of the same operation/request. */
    val retrySameOperation: Boolean,
    /** The original result may exist and must be verified before a new action. */
    val verifyBeforeNewAction: Boolean,
    /** The caller should retain its durable local request/checkpoint. */
    val preserveLocalWork: Boolean,
)

/**
 * Produces operational, non-technical recovery copy without erasing the
 * distinction between reads, local saves and money-affecting requests.
 *
 * This is a fallback policy. A feature-specific message with stronger facts
 * (invoice id, captured amount, exact outbox state, etc.) remains preferable.
 */
internal fun recoveryGuidance(
    risk: RecoveryRisk,
    failure: Throwable?,
    subject: String,
    localStatePreserved: Boolean = false,
): RecoveryGuidance {
    val label = subject.trim().trimEnd('.').ifBlank { "this action" }
    val api = failure as? ApiException
    val status = api?.status
    val ambiguous = failure == null || api?.isAmbiguous == true || failure !is ApiException
    val reason = safeServerReason(api)

    if (status == 426) {
        val saved = localStatePreserved
        return RecoveryGuidance(
            message = if (saved) {
                "Update the app before continuing. The same saved $label is retained; do not create it again."
            } else {
                "Update the app before trying $label again."
            },
            retrySameOperation = false,
            verifyBeforeNewAction = saved,
            preserveLocalWork = saved,
        )
    }

    return when (risk) {
        RecoveryRisk.AUTH -> when {
            status == 401 -> RecoveryGuidance(
                "Your sign-in is no longer valid. Sign in again to continue.",
                retrySameOperation = false,
                verifyBeforeNewAction = false,
                preserveLocalWork = true,
            )
            status == 403 -> RecoveryGuidance(
                "This account is not allowed to complete $label. Ask an owner to check access.",
                retrySameOperation = false,
                verifyBeforeNewAction = false,
                preserveLocalWork = true,
            )
            status == 429 -> RecoveryGuidance(
                "Too many attempts. Wait a moment, then try again.",
                retrySameOperation = true,
                verifyBeforeNewAction = false,
                preserveLocalWork = true,
            )
            ambiguous -> RecoveryGuidance(
                "The server could not be reached. Check the connection and try again.",
                retrySameOperation = true,
                verifyBeforeNewAction = false,
                preserveLocalWork = true,
            )
            else -> RecoveryGuidance(
                reason ?: "Sign-in could not be completed. Check the details and try again.",
                retrySameOperation = true,
                verifyBeforeNewAction = false,
                preserveLocalWork = true,
            )
        }

        RecoveryRisk.READ -> when {
            status == 401 -> RecoveryGuidance(
                "Your sign-in expired while loading $label. Sign in again; saved data was not changed.",
                retrySameOperation = false,
                verifyBeforeNewAction = false,
                preserveLocalWork = localStatePreserved,
            )
            status == 403 -> RecoveryGuidance(
                "This account cannot view $label. Ask an owner to check access.",
                retrySameOperation = false,
                verifyBeforeNewAction = false,
                preserveLocalWork = localStatePreserved,
            )
            ambiguous -> RecoveryGuidance(
                message = if (localStatePreserved) {
                    "Could not refresh $label. Saved data remains available; check the connection and try again."
                } else {
                    "Could not load $label. Check the connection and try again."
                },
                retrySameOperation = true,
                verifyBeforeNewAction = false,
                preserveLocalWork = localStatePreserved,
            )
            else -> RecoveryGuidance(
                message = buildString {
                    append("Could not load ").append(label).append('.')
                    reason?.let { append(' ').append(it) }
                    append(" Check access or refresh and try again.")
                },
                retrySameOperation = true,
                verifyBeforeNewAction = false,
                preserveLocalWork = localStatePreserved,
            )
        }

        RecoveryRisk.IDEMPOTENT_WRITE -> when {
            status == 401 || status == 403 -> RecoveryGuidance(
                message = if (localStatePreserved) {
                    "Access changed before $label was confirmed. Keep the saved request, sign in with the same account, then retry that exact request."
                } else {
                    "Access changed and the server did not accept $label. Sign in again before retrying the same action."
                },
                retrySameOperation = false,
                verifyBeforeNewAction = localStatePreserved,
                preserveLocalWork = localStatePreserved,
            )
            ambiguous -> RecoveryGuidance(
                message = if (localStatePreserved) {
                    "$label may already have reached the server. Do not create a new one; verify the result, then retry only the exact saved request."
                } else {
                    "$label may already have reached the server. Do not repeat it until the server record has been verified."
                },
                retrySameOperation = localStatePreserved,
                verifyBeforeNewAction = true,
                preserveLocalWork = localStatePreserved,
            )
            else -> RecoveryGuidance(
                buildString {
                    append("The server did not accept ").append(label).append('.')
                    reason?.let { append(' ').append(it) }
                    append(" Correct the cause, then retry the same request.")
                },
                retrySameOperation = true,
                verifyBeforeNewAction = false,
                preserveLocalWork = true,
            )
        }

        RecoveryRisk.FINANCIAL_WRITE -> when {
            status == 401 || status == 403 -> RecoveryGuidance(
                message = if (localStatePreserved) {
                    "Access changed before the result of $label was confirmed. Do not enter, collect or pay it again. Sign in with the same account and verify the saved request."
                } else {
                    "Access changed and the server did not accept $label. Sign in again and recheck the details before trying it again."
                },
                retrySameOperation = false,
                verifyBeforeNewAction = localStatePreserved,
                preserveLocalWork = localStatePreserved,
            )
            ambiguous -> RecoveryGuidance(
                message = if (localStatePreserved) {
                    "The server result for $label could not be confirmed. Do not enter, collect or pay it again. Verify the result, then retry only the exact saved request."
                } else {
                    "The server result for $label could not be confirmed. Do not enter, collect or pay it again until the server record has been verified."
                },
                retrySameOperation = localStatePreserved,
                verifyBeforeNewAction = true,
                preserveLocalWork = localStatePreserved,
            )
            else -> RecoveryGuidance(
                buildString {
                    append("The server refused ").append(label).append("; no new result was confirmed.")
                    reason?.let { append(' ').append(it) }
                    append(" Correct the details before trying again.")
                },
                retrySameOperation = true,
                verifyBeforeNewAction = false,
                preserveLocalWork = false,
            )
        }

        RecoveryRisk.LOCAL_CAPTURE -> RecoveryGuidance(
            "Could not save $label on this tablet. Nothing was queued. Keep this screen open, check tablet storage, and try again.",
            retrySameOperation = true,
            verifyBeforeNewAction = false,
            preserveLocalWork = false,
        )
    }
}

/** Only definitive, human-readable 4xx reasons are safe to surface. */
private fun safeServerReason(error: ApiException?): String? {
    val status = error?.status ?: return null
    if (status !in 400..499 || status in setOf(401, 403, 426, 429)) return null
    val message = error.message?.trim()?.takeIf(String::isNotEmpty) ?: return null
    val technical = message.startsWith("Request failed (HTTP", ignoreCase = true) ||
        message.equals("Internal server error", ignoreCase = true) ||
        message.equals("An unexpected error occurred.", ignoreCase = true) ||
        message.contains("sqlstate", ignoreCase = true) ||
        message.contains("database", ignoreCase = true) ||
        message.contains("stack trace", ignoreCase = true) ||
        message.contains("exception", ignoreCase = true) ||
        message.contains("traceback", ignoreCase = true)
    return message.takeUnless { technical }
}
