package cloud.dcompany.erp.ui.screens

import cloud.dcompany.erp.core.net.ApiException

internal enum class PasswordRecoveryPhase { REQUEST, CONFIRM }

internal class PasswordRecoveryOperationGate {
    private var generation = 0L
    private var inFlight = false

    fun begin(): Long? {
        if (inFlight) return null
        inFlight = true
        generation += 1
        return generation
    }

    fun isCurrent(operation: Long): Boolean = inFlight && generation == operation

    fun finish(operation: Long): Boolean {
        if (!isCurrent(operation)) return false
        inFlight = false
        return true
    }

    fun invalidate() {
        generation += 1
        inFlight = false
    }
}

internal fun filteredApprovalCode(value: String): String =
    value.filter(Char::isDigit).take(6)

/** Keep one over the server limit so validation can explain the 256 cap. */
internal fun boundedRecoveryPassword(value: String): String = value.take(257)

internal fun normalizedRecoveryEmail(value: String): String = value.trim().lowercase()

internal fun recoveryEmailError(value: String): String? {
    val email = normalizedRecoveryEmail(value)
    if (
        email.length !in 3..254 ||
        email.count { it == '@' } != 1 ||
        email.any(Char::isWhitespace)
    ) return "Enter a valid login email."
    val (local, domain) = email.split('@', limit = 2)
    return if (local.isBlank() || domain.isBlank() || '.' !in domain) {
        "Enter a valid login email."
    } else {
        null
    }
}

internal fun passwordRecoveryValidation(
    code: String,
    newPassword: String,
    confirmPassword: String,
): String? = when {
    !Regex("^\\d{6}$").matches(code) -> "Enter the 6-digit approval code."
    newPassword.length < 10 -> "New password must be at least 10 characters."
    newPassword.length > 256 -> "New password must be no more than 256 characters."
    newPassword != confirmPassword -> "New passwords do not match."
    else -> null
}

internal fun approvalExpiryLabel(seconds: Int): String {
    val safeSeconds = seconds.coerceAtLeast(0)
    if (safeSeconds == 0) return "The approval code may already be expired."
    val minutes = (safeSeconds + 59) / 60
    return "The approval code expires in $minutes ${if (minutes == 1) "minute" else "minutes"}."
}

internal fun passwordRecoveryError(
    error: Throwable,
    phase: PasswordRecoveryPhase,
): String {
    val api = error as? ApiException
    return when {
        api?.status == 429 ->
            "Too many approval-code requests. Wait 10 minutes before trying again."
        api?.status == null ->
            "The server could not be reached. Check the connection and try again."
        api.status >= 500 ->
            "Password recovery is temporarily unavailable. Try again later or ask the protected owner."
        phase == PasswordRecoveryPhase.CONFIRM && api.status in setOf(400, 404, 409, 422) ->
            "That approval code is incorrect or expired. Request a new code and try again."
        phase == PasswordRecoveryPhase.REQUEST && api.status == 422 ->
            "Enter a valid login email."
        else -> if (phase == PasswordRecoveryPhase.CONFIRM) {
            "The password could not be updated. Request a new code and try again."
        } else {
            "The approval request could not be sent. Try again later."
        }
    }
}
