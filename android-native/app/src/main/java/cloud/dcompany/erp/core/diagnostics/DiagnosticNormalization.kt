package cloud.dcompany.erp.core.diagnostics

import cloud.dcompany.erp.core.auth.CacheScope
import cloud.dcompany.erp.core.auth.OutboxOwnerIdentity
import java.util.ConcurrentModificationException

internal data class ApiFailureObservation(
    val status: Int?,
    /** Read only for classification; the value is never retained or sent. */
    val serverCode: String? = null,
    /** Read only for coarse module classification; the value is never retained or sent. */
    val encodedPath: String? = null,
    val connectivity: DiagnosticConnectivity = DiagnosticConnectivity.UNKNOWN,
    val explicitlyCancelled: Boolean = false,
)

/**
 * Converts raw transport context into a closed, privacy-safe vocabulary.
 * Expected validation/business-rule refusals are intentionally ignored: they
 * are user workflow outcomes, not app-health incidents, and recording them
 * would both create noise and risk turning business semantics into telemetry.
 */
internal object ApiFailureNormalizer {
    fun normalize(observation: ApiFailureObservation, occurredAtMillis: Long): DiagnosticEvent? {
        if (observation.explicitlyCancelled || occurredAtMillis <= 0) return null
        if (observation.encodedPath?.contains("client-diagnostics", ignoreCase = true) == true) {
            // A delivery failure must not recursively generate another row.
            return null
        }

        val status = observation.status?.takeIf { it in 100..599 }
        val rawCode = observation.serverCode?.trim()?.lowercase()
        val classified = classify(status, rawCode) ?: return null
        val component = if (status == null || classified.reasonCode == "request_timeout") {
            DiagnosticComponent.NETWORK
        } else {
            componentForPath(observation.encodedPath)
        }
        val fingerprint = sha256Hex(
            listOf(
                DiagnosticEventType.API_FAILURE.wireValue,
                component.wireValue,
                classified.reasonCode,
                status?.toString().orEmpty(),
            ).joinToString("|"),
        )
        return DiagnosticEvent(
            eventType = DiagnosticEventType.API_FAILURE,
            severity = classified.severity,
            occurredAtMillis = occurredAtMillis,
            component = component,
            reasonCode = classified.reasonCode,
            failureFingerprint = fingerprint,
            httpStatus = status,
            connectivity = observation.connectivity,
        )
    }

    private fun classify(status: Int?, code: String?): ApiFailureClassification? = when {
        status == null -> ApiFailureClassification("transport_unreachable", DiagnosticSeverity.ERROR)
        status == 408 || code == "network_timeout" ->
            ApiFailureClassification("request_timeout", DiagnosticSeverity.WARNING)
        status == 401 -> ApiFailureClassification("authentication_rejected", DiagnosticSeverity.WARNING)
        status == 403 -> ApiFailureClassification("authorization_rejected", DiagnosticSeverity.WARNING)
        status == 409 -> ApiFailureClassification("server_conflict", DiagnosticSeverity.WARNING)
        status == 426 -> ApiFailureClassification("client_update_required", DiagnosticSeverity.ERROR)
        status == 429 -> ApiFailureClassification("rate_limited", DiagnosticSeverity.WARNING)
        status >= 500 -> ApiFailureClassification("server_${status}", DiagnosticSeverity.ERROR)
        // 400/404/422 and ordinary 4xx responses are expected application
        // outcomes. Their user-facing recovery belongs in the feature layer.
        else -> null
    }

    private fun componentForPath(path: String?): DiagnosticComponent {
        val segments = path.orEmpty()
            .lowercase()
            .split('/')
            .filter(String::isNotBlank)
            .toSet()
        return when {
            "auth" in segments -> DiagnosticComponent.AUTH
            "gaming" in segments -> DiagnosticComponent.GAMING
            segments.any { it in setOf("pos", "orders", "payments", "shifts", "receipts") } ->
                DiagnosticComponent.POS
            segments.any { it in setOf("finance", "reports", "analytics", "expenses", "assets") } ->
                DiagnosticComponent.FINANCE
            segments.any { it in setOf("sync", "realtime") } -> DiagnosticComponent.SYNC
            segments.any { it in setOf("client-installations", "client-compatibility", "updates") } ->
                DiagnosticComponent.UPDATES
            else -> DiagnosticComponent.APP
        }
    }
}

private data class ApiFailureClassification(
    val reasonCode: String,
    val severity: DiagnosticSeverity,
)

internal object CrashNormalizer {
    fun normalize(
        throwable: Throwable,
        occurredAtMillis: Long,
        scopeConnectivity: DiagnosticConnectivity = DiagnosticConnectivity.UNKNOWN,
    ): DiagnosticEvent? {
        if (occurredAtMillis <= 0) return null
        val reasonCode = when (throwable) {
            is OutOfMemoryError -> "out_of_memory"
            is StackOverflowError -> "stack_overflow"
            is SecurityException -> "security_exception"
            is IllegalStateException -> "illegal_state"
            is NullPointerException -> "null_pointer"
            is ConcurrentModificationException -> "concurrent_modification"
            else -> "unhandled_java_exception"
        }
        return DiagnosticEvent(
            eventType = DiagnosticEventType.CRASH,
            severity = DiagnosticSeverity.CRITICAL,
            occurredAtMillis = occurredAtMillis,
            component = DiagnosticComponent.APP,
            reasonCode = reasonCode,
            // Only a digest leaves the process. Exception messages, stack
            // traces, file paths and line numbers are never persisted.
            failureFingerprint = crashFingerprint(throwable),
            connectivity = scopeConnectivity,
        )
    }
}

internal fun crashFingerprint(throwable: Throwable): String {
    val frames = runCatching {
        throwable.stackTrace.asSequence()
            .filter { it.className.startsWith("cloud.dcompany.erp") }
            .take(12)
            .map { "${it.className}#${it.methodName}" }
            .toList()
    }.getOrDefault(emptyList())
    val safeBasis = buildList {
        add(throwable.javaClass.name)
        addAll(frames)
    }.joinToString("|")
    return sha256Hex(safeBasis)
}

internal data class SyncHealthSample(
    val pendingOutboxCount: Int,
    /** Monotonic marker supplied by SyncEngine whenever delivery progresses. */
    val progressMarker: Long?,
    val online: Boolean,
)

internal data class SyncStallSignal(
    val durationBucket: DiagnosticDurationBucket,
    val pendingOutboxCount: Int,
)

/**
 * Stateful but clock-independent detector. Offline time is not a sync stall;
 * the timer starts only once a usable connection exists. A smaller queue or a
 * newer progress marker resets the timer, while newly queued work alone does
 * not falsely count as delivery progress.
 */
internal class SyncStallDetector(
    private val stallThresholdMillis: Long = 2L * 60L * 1_000L,
    private val repeatIntervalMillis: Long = 10L * 60L * 1_000L,
) {
    init {
        require(stallThresholdMillis > 0)
        require(repeatIntervalMillis >= stallThresholdMillis)
    }

    private var pendingSinceElapsedMillis: Long? = null
    private var lastReportedElapsedMillis: Long? = null
    private var lastPendingCount: Int = 0
    private var lastProgressMarker: Long? = null

    @Synchronized
    fun evaluate(nowElapsedMillis: Long, sample: SyncHealthSample): SyncStallSignal? {
        require(nowElapsedMillis >= 0)
        require(sample.pendingOutboxCount >= 0)
        if (!sample.online || sample.pendingOutboxCount == 0) {
            reset()
            return null
        }

        val progressed =
            (lastPendingCount > 0 && sample.pendingOutboxCount < lastPendingCount) ||
                (sample.progressMarker != null &&
                    lastProgressMarker != null &&
                    sample.progressMarker > requireNotNull(lastProgressMarker))
        if (pendingSinceElapsedMillis == null || progressed) {
            pendingSinceElapsedMillis = nowElapsedMillis
            lastReportedElapsedMillis = null
        }
        lastPendingCount = sample.pendingOutboxCount
        if (sample.progressMarker != null) lastProgressMarker = sample.progressMarker

        val stalledFor = nowElapsedMillis - requireNotNull(pendingSinceElapsedMillis)
        if (stalledFor < stallThresholdMillis) return null
        val lastReport = lastReportedElapsedMillis
        if (lastReport != null && nowElapsedMillis - lastReport < repeatIntervalMillis) return null
        lastReportedElapsedMillis = nowElapsedMillis
        return SyncStallSignal(
            durationBucket = durationBucket(stalledFor),
            pendingOutboxCount = sample.pendingOutboxCount.coerceAtMost(MAX_REPORTED_PENDING_OUTBOX),
        )
    }

    @Synchronized
    private fun reset() {
        pendingSinceElapsedMillis = null
        lastReportedElapsedMillis = null
        lastPendingCount = 0
        lastProgressMarker = null
    }
}

internal fun durationBucket(durationMillis: Long): DiagnosticDurationBucket = when {
    durationMillis < 5_000L -> DiagnosticDurationBucket.UNDER_5S
    durationMillis < 30_000L -> DiagnosticDurationBucket.FIVE_TO_30S
    durationMillis < 2L * 60L * 1_000L -> DiagnosticDurationBucket.THIRTY_SECONDS_TO_2M
    durationMillis < 10L * 60L * 1_000L -> DiagnosticDurationBucket.TWO_TO_10M
    else -> DiagnosticDurationBucket.OVER_10M
}

/**
 * A parsed bearer alone is not proof that the local workspace belongs to it.
 * Bind diagnostics only when the active/persisted cache scope independently
 * proves the same company, employee and branch.
 */
internal fun verifiedDiagnosticScopeHash(
    tokenIdentity: OutboxOwnerIdentity?,
    verifiedScope: CacheScope?,
): String? {
    tokenIdentity ?: return null
    verifiedScope ?: return null
    val tokenBranch = tokenIdentity.branchId.normalizedDiagnosticScopePart()
    val scopeBranch = verifiedScope.branchId.normalizedDiagnosticScopePart()
    if (
        tokenIdentity.userId.trim() != verifiedScope.userId.trim() ||
        tokenIdentity.companyId.trim() != verifiedScope.companyId.trim() ||
        tokenBranch != scopeBranch
    ) return null
    return diagnosticScopeHash(
        companyId = verifiedScope.companyId,
        userId = verifiedScope.userId,
        branchId = scopeBranch,
    )
}

/**
 * Process-exit history is imported before the live cache lease is restored.
 * Require three matching durable/session witnesses so a stale marker can
 * never become owned by the next employee who happens to authenticate.
 */
internal fun verifiedPersistedDiagnosticScopeHash(
    tokenIdentity: OutboxOwnerIdentity?,
    persistedCacheScope: CacheScope?,
    persistedDiagnosticScopeHash: String?,
): String? {
    val resolved = verifiedDiagnosticScopeHash(tokenIdentity, persistedCacheScope) ?: return null
    return resolved.takeIf { it == persistedDiagnosticScopeHash }
}

private fun String?.normalizedDiagnosticScopePart(): String? =
    this?.trim()?.takeIf(String::isNotEmpty)

/**
 * Owns the stateful API-storm and sync-stall detectors as one scope-keyed
 * generation. A login, logout or account/company change replaces both
 * detectors atomically, so no timer or suppression window crosses identities.
 */
internal class ScopedDiagnosticState(
    private val apiMinimumIntervalMillis: Long = 60_000L,
    private val stallThresholdMillis: Long = 2L * 60L * 1_000L,
    private val stallRepeatIntervalMillis: Long = 10L * 60L * 1_000L,
) {
    init {
        require(apiMinimumIntervalMillis > 0)
        require(stallThresholdMillis > 0)
        require(stallRepeatIntervalMillis >= stallThresholdMillis)
    }

    private val lock = Any()
    private var initialised = false
    private var scopeHash: String? = null
    private var rateLimiter = newRateLimiter()
    private var stallDetector = newStallDetector()

    fun observeScope(nextScopeHash: String?): Boolean = synchronized(lock) {
        transitionLocked(nextScopeHash)
    }

    fun claimApiFailure(
        nextScopeHash: String?,
        fingerprint: String,
        nowElapsedMillis: Long,
    ): Boolean = synchronized(lock) {
        transitionLocked(nextScopeHash)
        rateLimiter.claim(fingerprint, nowElapsedMillis)
    }

    fun evaluateSync(
        nextScopeHash: String?,
        nowElapsedMillis: Long,
        sample: SyncHealthSample,
    ): SyncStallSignal? = synchronized(lock) {
        transitionLocked(nextScopeHash)
        if (nextScopeHash == null) return@synchronized null
        stallDetector.evaluate(nowElapsedMillis, sample)
    }

    private fun transitionLocked(nextScopeHash: String?): Boolean {
        require(nextScopeHash == null || DIAGNOSTIC_SCOPE_HASH.matches(nextScopeHash))
        if (initialised && scopeHash == nextScopeHash) return false
        initialised = true
        scopeHash = nextScopeHash
        rateLimiter = newRateLimiter()
        stallDetector = newStallDetector()
        return true
    }

    private fun newRateLimiter() = DiagnosticRateLimiter(apiMinimumIntervalMillis)

    private fun newStallDetector() = SyncStallDetector(
        stallThresholdMillis = stallThresholdMillis,
        repeatIntervalMillis = stallRepeatIntervalMillis,
    )

    private companion object {
        val DIAGNOSTIC_SCOPE_HASH = Regex("^[0-9a-f]{64}$")
    }
}
