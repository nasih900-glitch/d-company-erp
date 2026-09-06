package cloud.dcompany.erp.core.diagnostics

import kotlinx.coroutines.CancellationException

/** Optional telemetry storage/scheduling must never become an application failure. */
internal inline fun <T> isolateDiagnosticFailure(
    reportFailure: (Exception) -> Unit,
    block: () -> T,
): T? = try {
    block()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: Exception) {
    try {
        reportFailure(failure)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        // A broken diagnostic logger cannot escalate an optional failure.
    }
    null
}
