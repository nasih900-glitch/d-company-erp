package cloud.dcompany.erp.core.handover

import cloud.dcompany.erp.PersistedStartupFailure
import cloud.dcompany.erp.PersistedStartupStateResult
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal sealed interface HandoverStartupStatus {
    data object Loading : HandoverStartupStatus
    data object Open : HandoverStartupStatus
    data object Preparing : HandoverStartupStatus
    data class RecoveryRequired(val intent: HandoverIntent) : HandoverStartupStatus
    data object Unavailable : HandoverStartupStatus
}

/** Restored before any foreground/worker cached authority adoption. Empty must be positively read. */
internal class HandoverStartupFence(private val store: HandoverIntentStore) {
    private val mutex = Mutex()
    private val mutable = MutableStateFlow<HandoverStartupStatus>(HandoverStartupStatus.Loading)
    val state = mutable.asStateFlow()
    suspend fun load() = mutex.withLock {
        mutable.value = HandoverStartupStatus.Loading
        try {
            val retained = store.read()
            retained?.validate()
            mutable.value = retained?.let { HandoverStartupStatus.RecoveryRequired(it) } ?: HandoverStartupStatus.Open
        } catch (cancelled: CancellationException) {
            mutable.value = HandoverStartupStatus.Unavailable
            throw cancelled
        } catch (error: Exception) {
            mutable.value = HandoverStartupStatus.Unavailable
            throw IOException("The retained till handover could not be verified.", error)
        }
    }
    fun requireOperationalReadiness() {
        check(mutable.value == HandoverStartupStatus.Open) { "The tablet's retained handover requires verification." }
    }
}

internal fun persistedStartupWithHandoverFence(
    restored: PersistedStartupStateResult,
    handover: HandoverStartupStatus,
): PersistedStartupStateResult {
    if (restored !is PersistedStartupStateResult.Ready) return restored
    return when (handover) {
        HandoverStartupStatus.Open -> restored
        HandoverStartupStatus.Preparing, is HandoverStartupStatus.RecoveryRequired -> PersistedStartupStateResult.Unavailable(PersistedStartupFailure.HANDOVER_RECOVERY)
        HandoverStartupStatus.Loading, HandoverStartupStatus.Unavailable -> PersistedStartupStateResult.Unavailable(PersistedStartupFailure.STORAGE)
    }
}
