package cloud.dcompany.erp.ui

import android.content.res.Configuration
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.util.SparseIntArray
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.MutableLongState
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.core.app.FrameMetricsAggregator
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import cloud.dcompany.erp.BuildConfig
import cloud.dcompany.erp.core.sync.OutboxWorkStatus
import cloud.dcompany.erp.ui.components.SyncAvailabilityProblem
import cloud.dcompany.erp.ui.screens.gaming.GameSession
import cloud.dcompany.erp.ui.screens.gaming.GamingSavingOverlay
import cloud.dcompany.erp.ui.screens.gaming.GamingStationCard
import cloud.dcompany.erp.ui.screens.gaming.Station
import cloud.dcompany.erp.ui.theme.Brand
import cloud.dcompany.erp.ui.theme.DCompanyTheme
import cloud.dcompany.erp.ui.theme.Spacing
import java.io.File
import java.time.Instant
import kotlin.math.ceil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val PHYSICAL_PERFORMANCE_LOG_TAG = "DCompanyPhysicalPerf"

/**
 * Device-side frame evidence for the two app surfaces involved in the reported
 * flicker: the persistent workspace chrome and the ticking Gaming board.
 *
 * This is intentionally instrumentation-only. Production builds gain no
 * profiler dependency or runtime collector, while Firebase Test Lab can run
 * the exact same Compose code on physical hardware and retain the metrics,
 * screenshots and video as acceptance evidence.
 */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.N)
class PhysicalComponentFrameStressUiTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun workspaceChromeAndGamingCardsHaveBoundedFramesAndStableGeometry() {
        val connection = mutableStateOf(SyncAvailabilityProblem.NONE)
        val outbox = mutableStateOf(OutboxWorkStatus())
        val syncing = mutableStateOf(false)
        val busyStationId = mutableStateOf<String?>(null)
        val wallClock = mutableLongStateOf(Instant.parse("2026-08-31T12:00:00Z").toEpochMilli())
        val stations = physicalAuditStations()
        val sessions = physicalAuditSessions(stations, wallClock.longValue)

        compose.setContent {
            DCompanyTheme {
                // Match MainActivity's edge-to-edge contract exactly. The
                // earlier component harness omitted safeDrawing, which put the
                // saving overlay under Lenovo's tablet taskbar and made a test
                // artifact look like an app contrast/clipping defect.
                Surface(Modifier.fillMaxSize(), color = Brand.Background) {
                    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                        WorkspaceScaffold(
                            destinations = listOf(Destination.Gaming, Destination.Pos, Destination.Shift),
                            currentDestination = Destination.Gaming,
                            employeeName = "Physical-device audit",
                            locationLabel = "Gaming Centre",
                            connectivityProblem = connection.value,
                            outboxWorkStatus = outbox.value,
                            syncing = syncing.value,
                            canChangeTill = false,
                            onOpenSupport = {},
                            onChangeTill = {},
                            onSignOut = {},
                        ) { _, _ ->
                            PhysicalGamingBoard(
                                stations = stations,
                                sessions = sessions,
                                wallClock = wallClock,
                                busyStationId = busyStationId.value,
                            )
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        assertTabletLandscapeConfiguration()
        writeScreenshot("01-gaming-online.png")

        val stableWorkflowBounds = compose.onNodeWithTag("physical-gaming-board")
            .fetchSemanticsNode().boundsInRoot
        val stableStationBounds = captureStationBounds(stations.take(4))
        compose.runOnIdle {
            connection.value = SyncAvailabilityProblem.SERVER_UNREACHABLE
            outbox.value = OutboxWorkStatus(actionRequiredCount = 2)
            busyStationId.value = stations.first().id
        }
        compose.waitForIdle()
        assertEquals(
            stableWorkflowBounds,
            compose.onNodeWithTag("physical-gaming-board").fetchSemanticsNode().boundsInRoot,
        )
        assertEquals(stableStationBounds, captureStationBounds(stations.take(4)))
        writeScreenshot("02-gaming-server-issue.png")

        // Warm the debug component harness before measuring. This test is a
        // device-side regression signal, not a release-app Macrobenchmark;
        // class loading and first composition therefore stay outside the
        // frame interval.
        repeat(25) { index ->
            compose.runOnIdle {
                connection.value = SyncAvailabilityProblem.entries[index % 5]
                outbox.value = OutboxWorkStatus(retryableCount = index % 2)
                syncing.value = index % 3 == 0
                busyStationId.value = null
                wallClock.longValue += 1_000L
            }
            compose.waitForIdle()
        }

        val frameMetrics = FrameMetricsAggregator(FrameMetricsAggregator.TOTAL_DURATION)
        var collectedMetrics: SparseIntArray? = null
        frameMetrics.add(compose.activity)

        // Forty full availability cycles reproduce the old online/offline
        // oscillation under a simultaneously ticking multi-station board.
        // Busy-station churn is excluded here: a write lock changes twice per
        // real action, not 200 times in a burst, and previously dominated the
        // trace by intentionally recomposing every disabled station action.
        try {
            repeat(200) { index ->
                compose.runOnIdle {
                    connection.value = when (index % 5) {
                        0 -> SyncAvailabilityProblem.VERIFYING
                        1 -> SyncAvailabilityProblem.NO_NETWORK
                        2 -> SyncAvailabilityProblem.SERVER_UNREACHABLE
                        3 -> SyncAvailabilityProblem.RECOVERING
                        else -> SyncAvailabilityProblem.NONE
                    }
                    outbox.value = when (index % 4) {
                        0 -> OutboxWorkStatus()
                        1 -> OutboxWorkStatus(retryableCount = 1)
                        2 -> OutboxWorkStatus(actionRequiredCount = 2)
                        else -> OutboxWorkStatus(savedDraftCount = 3)
                    }
                    syncing.value = index % 3 == 0
                    busyStationId.value = null
                    wallClock.longValue += 1_000L
                }
                compose.waitForIdle()
                assertEquals(
                    stableWorkflowBounds,
                    compose.onNodeWithTag("physical-gaming-board").fetchSemanticsNode().boundsInRoot,
                )
                assertEquals(stableStationBounds, captureStationBounds(stations.take(4)))
            }
        } finally {
            // FrameMetricsAggregator posts histogram updates on its own
            // HandlerThread. Compose idleness alone does not guarantee those
            // callbacks have drained before remove() snapshots the metrics.
            compose.waitForIdle()
            SystemClock.sleep(250)
            collectedMetrics = frameMetrics.remove(compose.activity)
                ?.getOrNull(FrameMetricsAggregator.TOTAL_INDEX)
        }

        val metrics = summarizeFrameMetrics(
            collectedMetrics,
            compose.activity.display?.refreshRate ?: 60f,
        )
        writeScreenshot("03-gaming-after-connectivity-stress.png")
        writeMetrics(metrics)
        Log.i(PHYSICAL_PERFORMANCE_LOG_TAG, metrics.asLogLine())

        assertTrue(
            "The physical run captured too few frames for a useful audit: ${metrics.totalFrames}",
            metrics.totalFrames >= 100,
        )
        assertEquals("A frozen frame was detected during Gaming/connectivity stress", 0, metrics.frozenFrames)
        // Jank and percentile values are calibration evidence on the first
        // physical matrix. Cloud instrumentation, video capture and refresh
        // rate materially affect them, so we inspect and compare the pulled
        // files before establishing a device-specific release threshold.
    }

    private fun writeScreenshot(filename: String) {
        val output = auditDirectory().resolve(filename)
        output.outputStream().use { stream ->
            val bitmap = compose.onRoot()
                .captureToImage()
                .asAndroidBitmap()
            try {
                check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream)) {
                    "Could not encode physical component-audit screenshot $filename"
                }
            } finally {
                bitmap.recycle()
            }
        }
    }

    private fun writeMetrics(metrics: FrameSummary) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val display = context.resources.displayMetrics
        auditDirectory().resolve("frame-metrics.txt").writeText(
            buildString {
                appendLine(metrics.asLogLine())
                appendLine("device=${Build.MANUFACTURER} ${Build.MODEL}")
                appendLine("android=${Build.VERSION.RELEASE} api=${Build.VERSION.SDK_INT}")
                appendLine("app=${BuildConfig.VERSION_NAME} code=${BuildConfig.VERSION_CODE}")
                appendLine(
                    "display=${display.widthPixels}x${display.heightPixels} " +
                        "densityDpi=${display.densityDpi} orientation=${context.resources.configuration.orientation}",
                )
            },
        )
    }

    private fun captureStationBounds(stations: List<Station>) = stations.associate { station ->
        station.id to compose.onNodeWithTag("physical-station-${station.id}")
            .fetchSemanticsNode().boundsInRoot
    }

    private fun assertTabletLandscapeConfiguration() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val display = context.resources.displayMetrics
        assertEquals(
            "The physical tablet audit must run in landscape",
            Configuration.ORIENTATION_LANDSCAPE,
            context.resources.configuration.orientation,
        )
        assertTrue(
            "The configured audit surface is too small: ${display.widthPixels}x${display.heightPixels}",
            display.widthPixels >= 1_200 && display.heightPixels >= 700,
        )
    }

    private fun auditDirectory(): File {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return File(context.getExternalFilesDir(null), "testlab").apply {
            check(isDirectory || mkdirs()) { "Could not create Test Lab artifact directory $this" }
        }
    }
}

@androidx.compose.runtime.Composable
private fun PhysicalGamingBoard(
    stations: List<Station>,
    sessions: Map<String, GameSession>,
    wallClock: MutableLongState,
    busyStationId: String?,
) {
    Box(
        Modifier.fillMaxSize().background(Brand.Background)
            .testTag("physical-gaming-board"),
    ) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(252.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(stations, key = Station::id) { station ->
                Box(Modifier.testTag("physical-station-${station.id}")) {
                    GamingStationCard(
                        station = station,
                        session = sessions[station.id],
                        packageExtensionAction = null,
                        wallClock = wallClock,
                        actionInProgress = busyStationId != null,
                        busyHere = busyStationId == station.id,
                        focused = false,
                        canWrite = true,
                        canReconcileLegacy = false,
                        activeShiftId = "physical-shift",
                        activeShiftServerConfirmed = true,
                        packages = emptyList(),
                        hasTransferTarget = true,
                        onStart = {},
                        onStop = {},
                        onSend = {},
                        onCancelUnbilled = {},
                        onExtendTimer = {},
                        onExtendPackage = { _, _ -> },
                        onTransfer = {},
                        onReconcile = {},
                        onRepairBilling = {},
                        onResolveLegacyStart = {},
                        onDiscardPackageExtension = {},
                    )
                }
            }
        }
        busyStationId?.let { stationId ->
            Box(
                Modifier.align(Alignment.BottomCenter)
                    .padding(horizontal = Spacing.lg, vertical = Spacing.md),
            ) {
                GamingSavingOverlay(
                    stationName = stations.firstOrNull { it.id == stationId }?.name
                        ?: "Gaming station",
                )
            }
        }
    }
}

private fun physicalAuditStations(): List<Station> {
    val types = listOf("ps5", "ps5", "ps5", "ps5", "racing", "shisha", "streaming", "vr")
    return types.mapIndexed { index, type ->
        Station(
            id = "physical-station-$index",
            code = "PHYS-${index + 1}",
            name = when (type) {
                "racing" -> "Racing Simulator 1"
                "shisha" -> "Shisha Table 1"
                "streaming" -> "Streaming Booth 1"
                "vr" -> "VR Pod 1"
                else -> "PS5 Station ${index + 1}"
            },
            type = type,
            ratePerHourMinor = when (type) {
                "racing" -> 40_000L
                "shisha", "vr" -> 35_000L
                else -> 20_000L
            },
        )
    }
}

private fun physicalAuditSessions(
    stations: List<Station>,
    nowMillis: Long,
): Map<String, GameSession> = stations.mapIndexedNotNull { index, station ->
    val session = when (index) {
        1, 3, 6 -> GameSession(
            id = "physical-session-$index",
            stationId = station.id,
            shiftId = "physical-shift",
            status = "active",
            startAt = Instant.ofEpochMilli(nowMillis - (index + 4L) * 60_000L).toString(),
            timerMinutes = 90,
            timerEndsAt = Instant.ofEpochMilli(nowMillis + 60L * 60_000L).toString(),
            ratePerHourMinor = station.ratePerHourMinor,
        )
        7 -> GameSession(
            id = "physical-session-$index",
            stationId = station.id,
            shiftId = "physical-shift",
            status = "ended",
            startAt = Instant.ofEpochMilli(nowMillis - 45L * 60_000L).toString(),
            endAt = Instant.ofEpochMilli(nowMillis - 2L * 60_000L).toString(),
            billableMinutes = 43,
            amountMinor = 25_083L,
            ratePerHourMinor = station.ratePerHourMinor,
        )
        else -> null
    }
    session?.let { station.id to it }
}.toMap()

private data class FrameSummary(
    val totalFrames: Int,
    val jankyFrames: Int,
    val severelyJankyFrames: Int,
    val frozenFrames: Int,
    val p50Millis: Int,
    val p90Millis: Int,
    val p95Millis: Int,
    val p99Millis: Int,
    val refreshRate: Float,
) {
    val jankPercent: Double
        get() = if (totalFrames == 0) 100.0 else jankyFrames * 100.0 / totalFrames
    val severeJankPercent: Double
        get() = if (totalFrames == 0) 100.0 else severelyJankyFrames * 100.0 / totalFrames

    fun asLogLine(): String =
        "scenario=component-gaming-connectivity total=$totalFrames missedDeadline=$jankyFrames " +
            "missedDeadlinePercent=${"%.2f".format(jankPercent)} " +
            "severeJank=$severelyJankyFrames severeJankPercent=${"%.2f".format(severeJankPercent)} " +
            "frozen=$frozenFrames " +
            "p50=${p50Millis}ms p90=${p90Millis}ms " +
            "p95=${p95Millis}ms p99=${p99Millis}ms refresh=${"%.1f".format(refreshRate)}Hz"
}

private fun summarizeFrameMetrics(metrics: SparseIntArray?, refreshRate: Float): FrameSummary {
    val values = buildList {
        if (metrics != null) {
            for (index in 0 until metrics.size()) {
                repeat(metrics.valueAt(index)) { add(metrics.keyAt(index)) }
            }
        }
    }.sorted()
    val oneFrameThresholdMillis = ceil(1_000.0 / refreshRate.coerceAtLeast(1f)).toInt()
    val twoFrameThresholdMillis = ceil(2_000.0 / refreshRate.coerceAtLeast(1f)).toInt()
    fun percentile(percent: Double): Int {
        if (values.isEmpty()) return 0
        val index = ceil(values.size * percent).toInt().coerceIn(1, values.size) - 1
        return values[index]
    }
    return FrameSummary(
        totalFrames = values.size,
        jankyFrames = values.count { it >= oneFrameThresholdMillis },
        severelyJankyFrames = values.count { it >= twoFrameThresholdMillis },
        frozenFrames = values.count { it >= 700 },
        p50Millis = percentile(0.50),
        p90Millis = percentile(0.90),
        p95Millis = percentile(0.95),
        p99Millis = percentile(0.99),
        refreshRate = refreshRate,
    )
}
