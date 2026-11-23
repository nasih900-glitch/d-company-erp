package cloud.dcompany.erp.ui.theme

internal enum class BackgroundPhase {
    RULE_ENTER, OWNER_BOUND, CONTENT_READY,
    DAY_GALLERY_DONE, DAY_NORMAL_DONE, DAY_BUSY_DONE, DAY_CANCELLING_DONE, DAY_ERROR_DONE,
    NIGHT_GALLERY_DONE, NIGHT_NORMAL_DONE, NIGHT_BUSY_DONE, NIGHT_CANCELLING_DONE, NIGHT_ERROR_DONE,
    LEGACY_CUSTOM_SUBMITTED, FINAL_ASSERT_PASSED, BODY_RETURNED,
    ACTIVITY_STARTED, ACTIVITY_RESUMED, ACTIVITY_PAUSED, ACTIVITY_STOPPED,
    ACTIVITY_PRE_DESTROY, ACTIVITY_DESTROYED, RULE_RETURNED,
}
internal enum class BackgroundLifecycle { DESTROYED, INITIALIZED, CREATED, STARTED, RESUMED }
internal data class BackgroundEvent(
    val sequence: Int, val uptimeMs: Long, val phase: BackgroundPhase,
    val lifecycle: BackgroundLifecycle?, val finishing: Boolean?, val destroyed: Boolean?,
)
internal data class BackgroundSnapshot(val events: List<BackgroundEvent>, val dropped: Int)

internal fun evaluateBackgroundRuleBody(
    body: () -> Unit,
    afterSuccess: () -> Unit,
    observeFailure: (Throwable) -> Unit,
    cleanup: () -> Unit,
) {
    try {
        try {
            body()
        } catch (failure: Throwable) {
            runCatching { observeFailure(failure) }
            throw failure
        }
        runCatching(afterSuccess)
    } finally {
        runCatching(cleanup)
    }
}

internal class BackgroundLifecycleTrace(private val uptime: () -> Long) {
    private val origin = uptime()
    private val events = ArrayList<BackgroundEvent>()
    private var closed = false
    private var dropped = 0

    @Synchronized fun record(
        phase: BackgroundPhase, lifecycle: BackgroundLifecycle? = null,
        finishing: Boolean? = null, destroyed: Boolean? = null,
        publish: (BackgroundEvent) -> Unit = {},
    ): BackgroundEvent? {
        if (closed) return null
        if (events.size == 32) {
            dropped = (dropped + 1).coerceAtMost(1_000_000)
            return null
        }
        val callback = phase.name.startsWith("ACTIVITY_")
        check(if (callback) lifecycle != null && finishing != null && destroyed != null
            else lifecycle == null && finishing == null && destroyed == null)
        val elapsed = uptime() - origin
        check(elapsed in 0L..86_400_000L && elapsed >= (events.lastOrNull()?.uptimeMs ?: 0))
        return BackgroundEvent(events.size + 1, elapsed, phase, lifecycle, finishing, destroyed)
            .also { events.add(it); publish(it) }
    }

    @Synchronized fun close(): BackgroundSnapshot {
        closed = true
        return BackgroundSnapshot(events.toList(), dropped)
    }
}
