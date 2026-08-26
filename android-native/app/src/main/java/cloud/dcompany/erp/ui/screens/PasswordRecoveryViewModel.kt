package cloud.dcompany.erp.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.dcompany.erp.core.net.AccountActionResponse
import cloud.dcompany.erp.core.net.ApiClient
import cloud.dcompany.erp.core.net.ApiException
import cloud.dcompany.erp.core.net.PasswordResetChallenge
import cloud.dcompany.erp.core.net.PasswordResetConfirmRequest
import cloud.dcompany.erp.core.net.PasswordResetRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal enum class PasswordRecoveryStage { REQUEST, CONFIRM }

/** Secrets live only in this in-memory state and are redacted from diagnostics. */
internal data class PasswordRecoveryState(
    val open: Boolean = false,
    val stage: PasswordRecoveryStage = PasswordRecoveryStage.REQUEST,
    val email: String = "",
    val challenge: PasswordResetChallenge? = null,
    val code: String = "",
    val newPassword: String = "",
    val confirmPassword: String = "",
    val busy: Boolean = false,
    val retrySecondsRemaining: Int = 0,
    val error: String? = null,
    val successNotice: String? = null,
    val successfulEmail: String? = null,
) {
    override fun toString(): String =
        "PasswordRecoveryState(open=$open, stage=$stage, email=<redacted>, " +
            "challenge=${challenge != null}, code=<redacted>, newPassword=<redacted>, " +
            "confirmPassword=<redacted>, busy=$busy, retrySecondsRemaining=$retrySecondsRemaining, " +
            "error=${error != null}, successNotice=${successNotice != null})"
}

class PasswordRecoveryViewModel internal constructor(
    private val requestReset: suspend (PasswordResetRequest) -> PasswordResetChallenge,
    private val confirmReset: suspend (PasswordResetConfirmRequest) -> AccountActionResponse,
) : ViewModel() {

    constructor() : this(
        requestReset = ApiClient.api::requestPasswordReset,
        confirmReset = ApiClient.api::confirmPasswordReset,
    )

    private val _state = MutableStateFlow(PasswordRecoveryState())
    internal val state: StateFlow<PasswordRecoveryState> = _state.asStateFlow()
    private val operationGate = PasswordRecoveryOperationGate()
    private var operationJob: Job? = null
    private var cooldownJob: Job? = null

    fun open(prefilledEmail: String) {
        clearActiveWork()
        _state.value = PasswordRecoveryState(
            open = true,
            email = prefilledEmail.take(254),
        )
    }

    fun emailChanged(value: String) {
        val current = _state.value
        if (!current.open || current.stage != PasswordRecoveryStage.REQUEST || current.busy) return
        _state.value = current.copy(email = value.take(254), error = null)
    }

    fun codeChanged(value: String) {
        val current = _state.value
        if (!current.open || current.stage != PasswordRecoveryStage.CONFIRM || current.busy) return
        _state.value = current.copy(code = filteredApprovalCode(value), error = null)
    }

    fun newPasswordChanged(value: String) {
        val current = _state.value
        if (!current.open || current.stage != PasswordRecoveryStage.CONFIRM || current.busy) return
        _state.value = current.copy(newPassword = boundedRecoveryPassword(value), error = null)
    }

    fun confirmPasswordChanged(value: String) {
        val current = _state.value
        if (!current.open || current.stage != PasswordRecoveryStage.CONFIRM || current.busy) return
        _state.value = current.copy(confirmPassword = boundedRecoveryPassword(value), error = null)
    }

    fun requestCode() {
        val current = _state.value
        if (!current.open || current.busy) return
        if (current.retrySecondsRemaining > 0) {
            _state.value = current.copy(
                error = "Wait ${current.retrySecondsRemaining} seconds before requesting another code.",
            )
            return
        }
        val emailError = recoveryEmailError(current.email)
        if (emailError != null) {
            _state.value = current.copy(error = emailError)
            return
        }
        val operation = operationGate.begin() ?: return
        val normalizedEmail = normalizedRecoveryEmail(current.email)
        // A resend immediately invalidates the local challenge and secrets.
        // If the response is lost, staff must request again instead of trying
        // a code whose server validity can no longer be known safely.
        _state.value = current.copy(
            stage = PasswordRecoveryStage.REQUEST,
            email = normalizedEmail,
            challenge = null,
            code = "",
            newPassword = "",
            confirmPassword = "",
            busy = true,
            error = null,
        )
        operationJob = viewModelScope.launch {
            try {
                val challenge = requestReset(PasswordResetRequest(normalizedEmail))
                if (!operationGate.isCurrent(operation) || !_state.value.open) return@launch
                _state.value = _state.value.copy(
                    stage = PasswordRecoveryStage.CONFIRM,
                    challenge = challenge,
                    retrySecondsRemaining = RESEND_COOLDOWN_SECONDS,
                )
                startCooldown(RESEND_COOLDOWN_SECONDS)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (!operationGate.isCurrent(operation) || !_state.value.open) return@launch
                val waitSeconds = if ((error as? ApiException)?.status == 429) {
                    RATE_LIMIT_SECONDS
                } else {
                    0
                }
                _state.value = _state.value.copy(
                    error = passwordRecoveryError(error, PasswordRecoveryPhase.REQUEST),
                    retrySecondsRemaining = waitSeconds,
                )
                if (waitSeconds > 0) startCooldown(waitSeconds)
            } finally {
                if (operationGate.finish(operation)) {
                    _state.value = _state.value.copy(busy = false)
                }
            }
        }
    }

    fun confirm() {
        val current = _state.value
        val challenge = current.challenge
        if (!current.open || current.busy || current.stage != PasswordRecoveryStage.CONFIRM || challenge == null) {
            return
        }
        val validation = passwordRecoveryValidation(
            code = current.code,
            newPassword = current.newPassword,
            confirmPassword = current.confirmPassword,
        )
        if (validation != null) {
            _state.value = current.copy(error = validation)
            return
        }
        val operation = operationGate.begin() ?: return
        val submittedEmail = current.email
        _state.value = current.copy(busy = true, error = null)
        operationJob = viewModelScope.launch {
            try {
                confirmReset(
                    PasswordResetConfirmRequest(
                        challengeId = challenge.challengeId,
                        code = current.code,
                        newPassword = current.newPassword,
                    ),
                )
                if (!operationGate.isCurrent(operation) || !_state.value.open) return@launch
                cooldownJob?.cancel()
                _state.value = PasswordRecoveryState(
                    successNotice = "Password updated. You can sign in with the new password now.",
                    successfulEmail = submittedEmail,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (!operationGate.isCurrent(operation) || !_state.value.open) return@launch
                _state.value = _state.value.copy(
                    code = "",
                    error = passwordRecoveryError(error, PasswordRecoveryPhase.CONFIRM),
                )
            } finally {
                if (operationGate.finish(operation)) {
                    _state.value = _state.value.copy(busy = false)
                }
            }
        }
    }

    fun useDifferentEmail() {
        val current = _state.value
        if (!current.open || current.busy) return
        _state.value = current.copy(
            stage = PasswordRecoveryStage.REQUEST,
            challenge = null,
            code = "",
            newPassword = "",
            confirmPassword = "",
            error = null,
        )
    }

    fun cancel() {
        clearActiveWork()
        _state.value = PasswordRecoveryState()
    }

    fun dismissSuccess() {
        _state.value = _state.value.copy(successNotice = null, successfulEmail = null)
    }

    private fun startCooldown(seconds: Int) {
        cooldownJob?.cancel()
        cooldownJob = viewModelScope.launch {
            repeat(seconds) {
                delay(1_000)
                val current = _state.value
                if (!current.open) return@launch
                _state.value = current.copy(
                    retrySecondsRemaining = (current.retrySecondsRemaining - 1).coerceAtLeast(0),
                )
            }
        }
    }

    private fun clearActiveWork() {
        operationGate.invalidate()
        operationJob?.cancel()
        operationJob = null
        cooldownJob?.cancel()
        cooldownJob = null
    }

    override fun onCleared() {
        clearActiveWork()
        // Best effort overwrite of every in-memory secret before disposal.
        _state.value = PasswordRecoveryState()
        super.onCleared()
    }

    private companion object {
        const val RESEND_COOLDOWN_SECONDS = 30
        const val RATE_LIMIT_SECONDS = 10 * 60
    }
}
