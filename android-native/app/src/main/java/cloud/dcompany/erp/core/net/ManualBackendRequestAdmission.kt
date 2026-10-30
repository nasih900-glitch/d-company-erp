package cloud.dcompany.erp.core.net

internal enum class ManualBackendOperation {
    MANUAL_READ,
    DIRECT_AUDITED_MUTATION,
}

/**
 * Admits only one caller-owned request after a fresh exact-loopback readiness proof.
 * It does not modify connectivity, schedule sync, drain outboxes, or create background work.
 */
internal class ManualBackendRequestAdmission(
    private val online: () -> Boolean,
    private val exactDebugLoopback: () -> Boolean = ApiClient::isExactDebugLoopback,
    private val probe: suspend () -> Boolean = ApiClient::probeManualDirectReadiness,
) {
    fun available(): Boolean = online() || exactDebugLoopback()

    suspend fun <T> execute(
        @Suppress("UNUSED_PARAMETER") operation: ManualBackendOperation,
        normal: suspend () -> T,
        direct: suspend () -> T,
    ): T {
        if (online()) return normal()
        check(exactDebugLoopback()) { "Reconnect before continuing this audited operation." }
        check(probe()) { "The local test backend is not ready. Nothing was sent." }
        return direct()
    }
}
