package cloud.dcompany.erp.core.diagnostics

import kotlinx.serialization.Serializable

internal const val APP_HEALTH_RETENTION_MS = 24 * 60 * 60 * 1_000L

/** Only fixed, non-customer labels are allowed in a local health capture. */
@Serializable
internal data class HealthFrameSummary(
    val screen: String,
    val frames: Int,
    val slowFrames: Int,
    val firstDrawFrames: Int,
    val droppedReports: Int,
    val durationBuckets: List<Int>,
    val longestFrameMs: Long,
)

@Serializable
internal data class HealthRefreshSummary(
    val resource: String,
    val success: Int,
    val failure: Int,
    val skipped: Int,
    val cancelled: Int,
    val durationBuckets: List<Int>,
    val longestRefreshMs: Long,
)

@Serializable
internal data class AppHealthCapture(
    val startedAtMillis: Long,
    val endedAtMillis: Long,
    val stopReason: String,
    val versionName: String,
    val versionCode: Int,
    val androidApiLevel: Int,
    val frames: List<HealthFrameSummary>,
    val refreshes: List<HealthRefreshSummary>,
)

internal fun recentHealthCaptures(
    records: List<AppHealthCapture>,
    nowMillis: Long,
): List<AppHealthCapture> = records
    .filter {
        it.startedAtMillis <= it.endedAtMillis &&
            it.endedAtMillis in (nowMillis - APP_HEALTH_RETENTION_MS)..nowMillis
    }
    .sortedByDescending { it.endedAtMillis }
    .take(3)

/** Pure aggregation keeps per-frame work bounded and never retains raw events. */
internal class HealthCaptureBuilder {
    private class FrameCounts {
        var frames = 0
        var slowFrames = 0
        var firstDrawFrames = 0
        var droppedReports = 0
        val buckets = IntArray(5)
        var longestMs = 0L
    }

    private class RefreshCounts {
        var success = 0
        var failure = 0
        var skipped = 0
        var cancelled = 0
        val buckets = IntArray(5)
        var longestMs = 0L
    }

    private val frames = linkedMapOf<String, FrameCounts>()
    private val refreshes = linkedMapOf<String, RefreshCounts>()

    fun frame(
        screen: String,
        totalDurationNanos: Long,
        deadlineNanos: Long,
        firstDraw: Boolean,
        droppedReports: Int,
    ) {
        if (totalDurationNanos <= 0 || deadlineNanos <= 0) return
        val row = frames.getOrPut(screen) { FrameCounts() }
        row.droppedReports += droppedReports.coerceAtLeast(0)
        if (firstDraw) {
            row.firstDrawFrames++
            return
        }
        val durationMs = (totalDurationNanos + 999_999L) / 1_000_000L
        row.frames++
        if (totalDurationNanos > deadlineNanos) row.slowFrames++
        row.buckets[bucket(durationMs, 16L, 33L, 50L, 100L)]++
        row.longestMs = maxOf(row.longestMs, durationMs)
    }

    fun refresh(resource: String, durationMillis: Long, outcome: String) {
        val row = refreshes.getOrPut(resource) { RefreshCounts() }
        when (outcome) {
            "success" -> row.success++
            "failure" -> row.failure++
            "skipped" -> row.skipped++
            "cancelled" -> row.cancelled++
            else -> return
        }
        val elapsed = durationMillis.coerceAtLeast(0)
        row.buckets[bucket(elapsed, 250L, 1_000L, 3_000L, 10_000L)]++
        row.longestMs = maxOf(row.longestMs, elapsed)
    }

    fun build(
        startedAtMillis: Long,
        endedAtMillis: Long,
        stopReason: String,
        versionName: String,
        versionCode: Int,
        androidApiLevel: Int,
    ): AppHealthCapture = AppHealthCapture(
        startedAtMillis = startedAtMillis,
        endedAtMillis = endedAtMillis,
        stopReason = stopReason,
        versionName = versionName,
        versionCode = versionCode,
        androidApiLevel = androidApiLevel,
        frames = frames.map { (screen, row) ->
            HealthFrameSummary(
                screen = screen,
                frames = row.frames,
                slowFrames = row.slowFrames,
                firstDrawFrames = row.firstDrawFrames,
                droppedReports = row.droppedReports,
                durationBuckets = row.buckets.toList(),
                longestFrameMs = row.longestMs,
            )
        },
        refreshes = refreshes.map { (resource, row) ->
            HealthRefreshSummary(
                resource = resource,
                success = row.success,
                failure = row.failure,
                skipped = row.skipped,
                cancelled = row.cancelled,
                durationBuckets = row.buckets.toList(),
                longestRefreshMs = row.longestMs,
            )
        },
    )

    private fun bucket(value: Long, a: Long, b: Long, c: Long, d: Long): Int = when {
        value <= a -> 0
        value <= b -> 1
        value <= c -> 2
        value <= d -> 3
        else -> 4
    }
}

internal fun healthCaptureInsight(capture: AppHealthCapture): String {
    val totalFrames = capture.frames.sumOf { it.frames }
    val totalSlow = capture.frames.sumOf { it.slowFrames }
    val slowestRefresh = capture.refreshes.maxByOrNull { it.longestRefreshMs }
    return when {
        totalFrames < 20 && capture.refreshes.isEmpty() ->
            "Not enough screen activity or refreshes were recorded. Use the app while recording."
        slowestRefresh != null && slowestRefresh.longestRefreshMs >= 3_000 ->
            "A ${slowestRefresh.resource} refresh took ${slowestRefresh.longestRefreshMs / 1_000.0}s. " +
                "This includes waiting, network, decoding and saving; review its sync path."
        totalFrames >= 20 && totalSlow * 10 >= totalFrames ->
            "$totalSlow of $totalFrames rendered frames missed the device's timing budget. " +
                "Review the busiest screen and compare a physical tablet run."
        else -> "No clear slowdown appeared in this short capture. A reported crash or bug still needs its own review."
    }
}
