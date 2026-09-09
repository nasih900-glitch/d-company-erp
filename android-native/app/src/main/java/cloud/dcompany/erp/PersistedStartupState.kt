package cloud.dcompany.erp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

internal enum class PersistedStartupFailure {
    TIMEOUT,
    STORAGE,
}

internal sealed interface PersistedStartupStateResult {
    data object Ready : PersistedStartupStateResult
    data class Unavailable(val failure: PersistedStartupFailure) : PersistedStartupStateResult
}

/**
 * WorkManager's first run shares the process-owned startup restore. After that
 * run returns retry, the next run must replace a completed transient failure
 * instead of awaiting the same failed Deferred forever.
 */
internal fun retryFailedPersistedStartupForWorkAttempt(runAttemptCount: Int): Boolean {
    require(runAttemptCount >= 0)
    return runAttemptCount > 0
}

/**
 * Loads the independent encrypted stores concurrently and turns both a stalled
 * provider and an I/O failure into an explicit, fail-closed result.
 */
internal suspend fun runBoundedPersistedStartupRestore(
    timeoutMillis: Long,
    loaders: List<suspend () -> Unit>,
): PersistedStartupStateResult {
    require(timeoutMillis > 0L)
    return try {
        withTimeout(timeoutMillis) {
            coroutineScope {
                loaders.map { loader -> async(Dispatchers.IO) { loader() } }.awaitAll()
            }
        }
        PersistedStartupStateResult.Ready
    } catch (_: TimeoutCancellationException) {
        PersistedStartupStateResult.Unavailable(PersistedStartupFailure.TIMEOUT)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        PersistedStartupStateResult.Unavailable(PersistedStartupFailure.STORAGE)
    }
}

/**
 * Process-owned restoration starts asynchronously from Application.onCreate.
 * Every authority-sensitive consumer awaits the same result; a UI waiter can
 * time out without cancelling the process-owned load or blocking the main
 * thread. A completed failure may be retried explicitly by login and durable
 * background recovery entry points.
 */
internal class PersistedStartupStateRestorer(
    private val scope: CoroutineScope,
    private val timeoutMillis: Long,
    private val loaders: List<suspend () -> Unit>,
) {
    private val lock = Any()
    private var current: Deferred<PersistedStartupStateResult>? = null
    @Volatile
    private var completed: PersistedStartupStateResult? = null

    fun start(retryFailed: Boolean = false): Deferred<PersistedStartupStateResult> =
        synchronized(lock) {
            val previous = current
            if (
                retryFailed &&
                previous?.isCompleted == true &&
                completed is PersistedStartupStateResult.Unavailable
            ) {
                current = null
                completed = null
            }
            current ?: scope.async(Dispatchers.IO) {
                runBoundedPersistedStartupRestore(timeoutMillis, loaders).also { result ->
                    completed = result
                }
            }.also { current = it }
        }

    suspend fun await(retryFailed: Boolean = false): PersistedStartupStateResult =
        withTimeoutOrNull(timeoutMillis + AWAIT_HEADROOM_MILLIS) {
            start(retryFailed).await()
        } ?: PersistedStartupStateResult.Unavailable(PersistedStartupFailure.TIMEOUT)

    private companion object {
        const val AWAIT_HEADROOM_MILLIS = 250L
    }
}
