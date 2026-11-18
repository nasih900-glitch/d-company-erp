package cloud.dcompany.erp.core.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Foreground-only, single job. Offline/error backoff does not alter the stored policy. */
internal class ForegroundCompatibilityPoller(
    private val scope: CoroutineScope,
    private val online: () -> Boolean,
    private val check: suspend () -> Boolean,
    private val intervalMillis: Long = 1_000L,
    private val maximumBackoffMillis: Long = 30_000L,
) {
    private var job: Job? = null
    @Synchronized fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            var waitMillis = intervalMillis
            while (isActive) {
                val succeeded = online() && check()
                waitMillis = if (succeeded) intervalMillis else (waitMillis * 2).coerceAtMost(maximumBackoffMillis)
                delay(waitMillis)
            }
        }
    }
    @Synchronized fun stop() { job?.cancel(); job = null }
}
