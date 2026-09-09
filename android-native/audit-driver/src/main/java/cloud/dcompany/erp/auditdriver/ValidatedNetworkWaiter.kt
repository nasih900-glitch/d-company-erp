package cloud.dcompany.erp.auditdriver

internal const val MAX_VALIDATED_NETWORK_WAIT_MILLIS = 60_000L
internal const val VALIDATED_NETWORK_POLL_MILLIS = 250L

internal enum class NetworkCapabilityState(val evidenceValue: String) {
    MISSING("missing"),
    WITHOUT_INTERNET("without_internet"),
    INTERNET_ONLY("internet_only"),
    VALIDATED("validated"),
    UNKNOWN_ERROR("unknown_error"),
}

internal enum class ValidatedNetworkWaitResult(val evidenceValue: String) {
    VALIDATED("validated"),
    TIMED_OUT("timed_out"),
    ERROR("error"),
}

internal data class ValidatedNetworkWaitOutcome(
    val result: ValidatedNetworkWaitResult,
    val elapsedMillis: Long,
    val capabilityState: NetworkCapabilityState,
)

/**
 * Separates Android's network-acquisition budget from the ERP's recovery
 * assertion. A capability sample that completes at or after the deadline is
 * rejected even if it reports validation.
 */
internal fun awaitValidatedNetwork(
    timeoutMillis: Long = MAX_VALIDATED_NETWORK_WAIT_MILLIS,
    pollIntervalMillis: Long = VALIDATED_NETWORK_POLL_MILLIS,
    monotonicMillis: () -> Long,
    pollCapabilities: () -> NetworkCapabilityState,
    sleepMillis: (Long) -> Unit,
): ValidatedNetworkWaitOutcome {
    require(timeoutMillis in 1L..MAX_VALIDATED_NETWORK_WAIT_MILLIS)
    require(pollIntervalMillis > 0L)

    var lastElapsedMillis = 0L
    var lastCapabilityState = NetworkCapabilityState.MISSING
    try {
        val startedAtMillis = monotonicMillis()
        while (true) {
            lastElapsedMillis = elapsedSince(startedAtMillis, monotonicMillis())
            if (lastElapsedMillis >= timeoutMillis) {
                return ValidatedNetworkWaitOutcome(
                    ValidatedNetworkWaitResult.TIMED_OUT,
                    lastElapsedMillis,
                    lastCapabilityState,
                )
            }

            lastCapabilityState = pollCapabilities()
            lastElapsedMillis = elapsedSince(startedAtMillis, monotonicMillis())
            if (lastElapsedMillis >= timeoutMillis) {
                return ValidatedNetworkWaitOutcome(
                    ValidatedNetworkWaitResult.TIMED_OUT,
                    lastElapsedMillis,
                    lastCapabilityState,
                )
            }
            if (lastCapabilityState == NetworkCapabilityState.VALIDATED) {
                return ValidatedNetworkWaitOutcome(
                    ValidatedNetworkWaitResult.VALIDATED,
                    lastElapsedMillis,
                    lastCapabilityState,
                )
            }

            sleepMillis(minOf(pollIntervalMillis, timeoutMillis - lastElapsedMillis))
        }
    } catch (_: Exception) {
        return ValidatedNetworkWaitOutcome(
            ValidatedNetworkWaitResult.ERROR,
            lastElapsedMillis,
            NetworkCapabilityState.UNKNOWN_ERROR,
        )
    }
}

private fun elapsedSince(startedAtMillis: Long, currentMillis: Long): Long =
    (currentMillis - startedAtMillis).coerceAtLeast(0L)
