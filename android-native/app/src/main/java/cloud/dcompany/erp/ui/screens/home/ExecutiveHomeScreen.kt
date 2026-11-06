package cloud.dcompany.erp.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.PointOfSale
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.WatchLater
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cloud.dcompany.erp.core.net.asRupees
import cloud.dcompany.erp.ui.Destination
import cloud.dcompany.erp.ui.components.UiTone
import cloud.dcompany.erp.ui.screens.analytics.CachedDataPresentation
import cloud.dcompany.erp.ui.screens.analytics.cachedDataPresentation
import cloud.dcompany.erp.ui.theme.Radius
import cloud.dcompany.erp.ui.theme.LocalWorkspaceGlassScene
import cloud.dcompany.erp.ui.theme.WorkspaceGlassRole
import cloud.dcompany.erp.ui.theme.WorkspacePanelShape
import cloud.dcompany.erp.ui.theme.workspaceGlass
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.math.BigInteger
import java.util.Locale
import kotlin.math.roundToInt

private val HomeHeroOnDarkError = Color(0xFFFFB4AB)

@Composable
private fun Modifier.homeGlass(role: WorkspaceGlassRole, shape: Shape): Modifier = workspaceGlass(
    scene = LocalWorkspaceGlassScene.current,
    role = role,
    day = MaterialTheme.colorScheme.background.luminance() > 0.5f,
    shape = shape,
)

internal fun homeHeroStatusGlassRole(presentation: CachedDataPresentation): WorkspaceGlassRole =
    if (presentation == CachedDataPresentation.BLOCKING_ERROR) {
        WorkspaceGlassRole.ERROR
    } else {
        WorkspaceGlassRole.CONTROL
    }

internal data class ExecutiveHomeSnapshot(
    val revenue: String,
    val revenueComparison: String?,
    val orders: String,
    val ordersComparison: String?,
    val openSessions: Long,
    val activeStationCapacity: Long,
    val customers: String,
    val snapshotDate: String,
    val sourceTimestamp: String?,
    val timezone: String,
    val hourly: List<HomeHourPoint>,
    val profileRevenueMinor: Long,
)

internal data class ExecutiveHomeShortcut(
    val destination: Destination,
    val action: String,
)

internal data class HomeWideGeometry(
    val heroHeight: androidx.compose.ui.unit.Dp,
    val kpiTop: androidx.compose.ui.unit.Dp,
    val kpiHeight: androidx.compose.ui.unit.Dp,
    val shortcutTop: androidx.compose.ui.unit.Dp,
    val shortcutHeight: androidx.compose.ui.unit.Dp,
    val lowerTop: androidx.compose.ui.unit.Dp,
    val lowerHeight: androidx.compose.ui.unit.Dp,
    val bottomSafety: androidx.compose.ui.unit.Dp,
)

internal data class HomeSalesProfilePlot(
    val yFractions: List<Float>,
    val zeroFraction: Float,
)

internal data class HomeCapacityPresentation(
    val value: String,
    val detail: String,
    val fillFraction: Float?,
    val accessibility: String,
)

internal fun homeMetricComparison(metric: HomeAggregateMetric): String? =
    if (metric.comparisonStatus == "available") {
        metric.deltaPct?.let { delta ->
            val normalized = if (kotlin.math.abs(delta) < 0.005) 0.0 else delta
            val pattern = if (normalized > 0.0) "+%.2f%% vs previous day" else "%.2f%% vs previous day"
            String.format(Locale.ENGLISH, pattern, normalized)
        }
    } else {
        null
    }

internal fun homeCapacityPresentation(openSessions: Long, activeStationCapacity: Long): HomeCapacityPresentation {
    if (activeStationCapacity <= 0L) {
        return HomeCapacityPresentation(
            value = "$openSessions / 0",
            detail = "0 active stations · active and paused",
            fillFraction = null,
            accessibility = "$openSessions active or paused sessions; 0 active stations",
        )
    }
    val percent = openSessions.toDouble() * 100.0 / activeStationCapacity.toDouble()
    val overCapacity = openSessions > activeStationCapacity
    return HomeCapacityPresentation(
        value = "$openSessions / $activeStationCapacity",
        detail = if (overCapacity) {
            String.format(Locale.ENGLISH, "%.1f%% · over capacity · active and paused", percent)
        } else {
            String.format(Locale.ENGLISH, "%.1f%% · active and paused", percent)
        },
        fillFraction = (openSessions.toDouble() / activeStationCapacity.toDouble()).coerceIn(0.0, 1.0).toFloat(),
        accessibility = if (overCapacity) {
            "$openSessions active or paused sessions; $activeStationCapacity active station capacity; over capacity"
        } else {
            "$openSessions active or paused sessions out of $activeStationCapacity active station capacity"
        },
    )
}

internal fun homeActivityVisibleTime(occurredAt: Instant, timezone: String): String =
    occurredAt.atZone(ZoneId.of(timezone))
        .format(DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH))

internal fun homeActivityAccessibleTime(occurredAt: Instant, timezone: String): String =
    occurredAt.atZone(ZoneId.of(timezone))
        .format(DateTimeFormatter.ofPattern("d MMM uuuu, h:mm a z", Locale.ENGLISH))

/**
 * Maps signed minor-unit facts onto one zero-aware vertical axis. Conversion
 * happens before range math so Long.MIN_VALUE is never passed through abs().
 */
internal fun homeSalesProfilePlot(hourly: List<HomeHourPoint>): HomeSalesProfilePlot {
    require(hourly.size == 24 && hourly.map(HomeHourPoint::hour) == (0..23).toList())
    val values = hourly.map { it.revenueMinor.toDouble() }
    val lower = minOf(0.0, values.minOrNull() ?: 0.0)
    val upper = maxOf(0.0, values.maxOrNull() ?: 0.0)
    if (lower == upper) {
        return HomeSalesProfilePlot(List(24) { 0.5f }, zeroFraction = 0.5f)
    }
    val range = upper - lower
    fun y(value: Double): Float = ((upper - value) / range).toFloat().coerceIn(0f, 1f)
    return HomeSalesProfilePlot(
        yFractions = values.map(::y),
        zeroFraction = y(0.0),
    )
}

internal fun homeSalesProfileAccessibility(hourly: List<HomeHourPoint>): String =
    buildString {
        append("POS sales by local hour. ")
        hourly.forEachIndexed { index, point ->
            if (index > 0) append("; ")
            append(homeHourLabel(point.hour))
            append(' ')
            append(homeMinorAsRupees(point.revenueMinor))
        }
    }

internal fun homeSalesProfileRange(hourly: List<HomeHourPoint>): String {
    require(hourly.size == 24)
    val minimum = hourly.minOf(HomeHourPoint::revenueMinor)
    val maximum = hourly.maxOf(HomeHourPoint::revenueMinor)
    return "Range ${homeMinorAsRupees(minimum)} to ${homeMinorAsRupees(maximum)}"
}

internal fun homeMinorAsRupees(value: Long): String {
    val hundred = BigInteger.valueOf(100L)
    val (wholeValue, fractionValue) = BigInteger.valueOf(value).abs().divideAndRemainder(hundred)
    val digits = wholeValue.toString()
    val grouped = if (digits.length <= 3) {
        digits
    } else {
        val head = digits.dropLast(3)
        val tail = digits.takeLast(3)
        buildList {
            var remaining = head
            while (remaining.length > 2) {
                add(0, remaining.takeLast(2))
                remaining = remaining.dropLast(2)
            }
            if (remaining.isNotEmpty()) add(0, remaining)
        }.joinToString(",") + "," + tail
    }
    return buildString {
        if (value < 0) append('-')
        append(grouped)
        append('.')
        append(fractionValue.toString().padStart(2, '0'))
    }
}

private fun homeHourLabel(hour: Int): String = when (hour) {
    0 -> "12am"
    in 1..11 -> "${hour}am"
    12 -> "12pm"
    else -> "${hour - 12}pm"
}

internal fun homeWideGeometry(
    availableHeight: androidx.compose.ui.unit.Dp,
    fontScale: Float = 1f,
    noticeHeight: androidx.compose.ui.unit.Dp = 0.dp,
): HomeWideGeometry {
    val contentHeight = (availableHeight - noticeHeight).coerceAtLeast(640.dp)
    val compression = ((784f - contentHeight.value) / 144f).coerceIn(0f, 1f)
    fun scaled(standard: Float, compact: Float) = (standard + (compact - standard) * compression).dp
    val bottom = 16.dp
    val lowerTop = scaled(492f, 420f) + noticeHeight
    val lower = availableHeight - lowerTop - bottom
    val minimumLowerHeight = if (fontScale > 1.15f) 220.dp else 204.dp
    return HomeWideGeometry(
        heroHeight = scaled(310f, 260f) + noticeHeight,
        kpiTop = scaled(260f, 218f) + noticeHeight,
        kpiHeight = scaled(96f, 86f),
        shortcutTop = scaled(372f, 316f) + noticeHeight,
        shortcutHeight = scaled(104f, 92f),
        lowerTop = lowerTop,
        lowerHeight = lower.coerceAtLeast(minimumLowerHeight),
        bottomSafety = bottom,
    )
}

internal fun homeWideContentHeight(
    availableHeight: androidx.compose.ui.unit.Dp,
    fontScale: Float,
    noticeHeight: androidx.compose.ui.unit.Dp = 0.dp,
): androidx.compose.ui.unit.Dp = when {
    fontScale > 1.15f -> maxOf(availableHeight, 820.dp + noticeHeight)
    // The full five-row activity cohort and the 100dp sales chart need a
    // 249dp lower band. Short wide windows scroll this complete composition
    // instead of clipping or silently dropping operational facts.
    else -> maxOf(availableHeight, 730.dp + noticeHeight)
}

internal fun homeHeroContentBottomReserve(
    heroHeight: androidx.compose.ui.unit.Dp,
    kpiTop: androidx.compose.ui.unit.Dp,
    fontScale: Float,
): androidx.compose.ui.unit.Dp {
    val scaledTextReserve = ((fontScale - 1f).coerceAtLeast(0f) * 24f).dp
    return (heroHeight - kpiTop).coerceAtLeast(0.dp) + 16.dp + scaledTextReserve
}

private val HomeShortcutOrder = listOf(
    ExecutiveHomeShortcut(Destination.Gaming, "Manage stations"),
    ExecutiveHomeShortcut(Destination.Pos, "Take orders"),
    ExecutiveHomeShortcut(Destination.Shift, "Open or close"),
    ExecutiveHomeShortcut(Destination.Customers, "Manage customers"),
    ExecutiveHomeShortcut(Destination.Inventory, "Stock and products"),
    ExecutiveHomeShortcut(Destination.Reports, "View reports"),
)

internal fun executiveHomeShortcuts(allowed: Collection<Destination>): List<ExecutiveHomeShortcut> =
    HomeShortcutOrder.filter { it.destination in allowed }

internal fun homeShortcutVisibleLabel(destination: Destination): String =
    if (destination == Destination.Inventory) "Inventory" else destination.label

internal fun homeWideShortcutSlots(
    shortcuts: List<ExecutiveHomeShortcut>,
): List<ExecutiveHomeShortcut?> = HomeShortcutOrder.map { ordered ->
    shortcuts.firstOrNull { it.destination == ordered.destination }
}

internal fun homeHeroStatusMaxWidth(availableWidth: androidx.compose.ui.unit.Dp): androidx.compose.ui.unit.Dp =
    (availableWidth * 0.70f - 124.dp).coerceIn(320.dp, 560.dp)

internal fun homeHeroStatusWidth(
    availableWidth: androidx.compose.ui.unit.Dp,
): androidx.compose.ui.unit.Dp = minOf(440.dp, homeHeroStatusMaxWidth(availableWidth))

internal fun executiveHomeSnapshot(state: HomeUiState): ExecutiveHomeSnapshot? = state.aggregate?.let { dashboard ->
    ExecutiveHomeSnapshot(
        revenue = dashboard.revenue.current.asRupees(),
        revenueComparison = homeMetricComparison(dashboard.revenue),
        orders = dashboard.orders.current.toString(),
        ordersComparison = homeMetricComparison(dashboard.orders),
        openSessions = dashboard.openSessions,
        activeStationCapacity = dashboard.activeStationCapacity,
        customers = dashboard.customerCount.toString(),
        snapshotDate = homeSnapshotDate(dashboard.businessDate),
        sourceTimestamp = state.fetchedAtMillis?.let { homeSourceTimestamp(it, dashboard.timezone) },
        timezone = dashboard.timezone,
        hourly = dashboard.hourly,
        profileRevenueMinor = dashboard.profileRevenueMinor,
    )
}

internal fun executiveGreeting(employeeName: String, hour: Int): String {
    val firstName = employeeName.trim().substringBefore(' ').takeIf(String::isNotBlank) ?: "there"
    val greeting = when (hour) {
        in 5..11 -> "Good morning"
        in 12..16 -> "Good afternoon"
        else -> "Good evening"
    }
    return "$greeting, $firstName"
}

private fun homeSnapshotDate(isoDate: String): String = runCatching {
    LocalDate.parse(isoDate).format(DateTimeFormatter.ofPattern("d MMM uuuu", Locale.ENGLISH))
}.getOrElse { isoDate.ifBlank { "Date unavailable" } }

private fun homeSourceTimestamp(fetchedAtMillis: Long, timezone: String): String {
    val zone = runCatching { ZoneId.of(timezone) }.getOrElse { ZoneId.of("UTC") }
    return Instant.ofEpochMilli(fetchedAtMillis).atZone(zone)
        .format(DateTimeFormatter.ofPattern("d MMM uuuu, h:mm a z", Locale.ENGLISH))
}

@Composable
internal fun ExecutiveHomeScreen(
    employeeName: String,
    locationLabel: String,
    destinations: List<Destination>,
    navigateTo: (Destination) -> Unit,
    homeViewModel: HomeViewModel = viewModel(),
) {
    val storedState by homeViewModel.state.collectAsStateWithLifecycle()
    val signaledLease by homeViewModel.activeLease.collectAsStateWithLifecycle()
    val state = visibleHomeState(storedState, signaledLease, homeViewModel.currentLease())
    ExecutiveHomeWorkspace(
        employeeName = employeeName,
        locationLabel = locationLabel,
        state = state,
        destinations = destinations,
        navigateTo = navigateTo,
        onRetry = homeViewModel::retry,
    )
}

private val StaffHomeShortcutOrder = listOf(
    ExecutiveHomeShortcut(Destination.Gaming, "View stations"),
    ExecutiveHomeShortcut(Destination.Pos, "Open POS"),
    ExecutiveHomeShortcut(Destination.Shift, "Review shift"),
    ExecutiveHomeShortcut(Destination.Customers, "Find customers"),
    ExecutiveHomeShortcut(Destination.Staff, "Attendance"),
)

@Composable
internal fun StaffHomeScreen(
    employeeName: String,
    locationLabel: String,
    destinations: List<Destination>,
    navigateTo: (Destination) -> Unit,
) {
    val shell = LocalExecutiveHomeShellLayout.current
    val topInset = if (shell.enabled) shell.utilityHeight + shell.noticeHeight else 0.dp
    val shortcuts = StaffHomeShortcutOrder.filter { it.destination in destinations }
    BoxWithConstraints(Modifier.fillMaxSize().testTag("staff-home-workspace")) {
        val wide = maxWidth >= 900.dp
        Column(
            Modifier.fillMaxSize().padding(top = topInset).verticalScroll(rememberScrollState())
                .padding(horizontal = if (wide) 24.dp else 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            HomeHero(
                greeting = executiveGreeting(employeeName, java.time.LocalTime.now().hour),
                locationLabel = locationLabel,
                compact = !wide,
            )
            if (shortcuts.isNotEmpty()) HomeShortcuts(shortcuts, wide, navigateTo)
        }
    }
}

@Composable
internal fun ExecutiveHomeWorkspace(
    employeeName: String,
    locationLabel: String,
    state: HomeUiState,
    destinations: List<Destination>,
    navigateTo: (Destination) -> Unit,
    onRetry: () -> Unit,
    hour: Int? = null,
    reviewFixtureLabel: String? = null,
) {
    val shellLayout = LocalExecutiveHomeShellLayout.current
    val snapshot = executiveHomeSnapshot(state)
    val resolvedHour = hour ?: runCatching {
        java.time.ZonedDateTime.now(ZoneId.of(snapshot?.timezone ?: ZoneId.systemDefault().id)).hour
    }.getOrElse { java.time.LocalTime.now().hour }
    val presentation = cachedDataPresentation(
        hasData = snapshot != null,
        loading = state.loading,
        error = state.error,
    )
    val shortcuts = executiveHomeShortcuts(destinations)

    BoxWithConstraints(
        Modifier.fillMaxSize().testTag("executive-home-workspace"),
    ) {
        val wideComposition = maxWidth >= 900.dp
        if (wideComposition) {
            val contentHeight = homeWideContentHeight(
                maxHeight,
                LocalDensity.current.fontScale,
                shellLayout.noticeHeight,
            )
            val home: @Composable (Modifier) -> Unit = { modifier ->
                ExecutiveHomeWideWorkspace(
                    employeeName = employeeName,
                    locationLabel = locationLabel,
                    snapshot = snapshot,
                    presentation = presentation,
                    todayError = state.error,
                    refreshing = state.loading,
                    activity = state.activity,
                    capabilities = state.activityCapabilities,
                    shortcuts = shortcuts,
                    canOpenAnalytics = Destination.Analytics in destinations,
                    navigateTo = navigateTo,
                    onRetry = onRetry,
                    hour = resolvedHour,
                    geometry = homeWideGeometry(
                        availableHeight = contentHeight,
                        fontScale = LocalDensity.current.fontScale,
                        noticeHeight = shellLayout.noticeHeight,
                    ),
                    modifier = modifier,
                )
            }
            if (contentHeight > maxHeight) {
                Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    home(Modifier.fillMaxWidth().height(contentHeight))
                }
            } else {
                home(Modifier.fillMaxSize())
            }
        } else {
            val wide = maxWidth >= 840.dp
            val pagePadding = if (wide) 24.dp else 16.dp
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                    .padding(horizontal = pagePadding, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                HomeHero(
                    greeting = executiveGreeting(employeeName, resolvedHour),
                    locationLabel = locationLabel,
                    compact = !wide,
                )
                reviewFixtureLabel?.let { HomeStateBanner(it, UiTone.Warning, null) }
                when (presentation) {
                    CachedDataPresentation.INITIAL_LOADING -> HomeStateBanner(
                        "Loading the verified dashboard snapshot…",
                        UiTone.Neutral,
                        null,
                    )
                    CachedDataPresentation.BLOCKING_ERROR -> HomeStateBanner(
                        state.error ?: "Home data is unavailable.",
                        UiTone.Danger,
                        onRetry,
                    )
                    CachedDataPresentation.STALE -> HomeStateBanner(
                        "Saved aggregate · ${state.error ?: "Live refresh unavailable."}",
                        UiTone.Warning,
                        onRetry,
                    )
                    CachedDataPresentation.FRESH -> if (state.loading) {
                        HomeStateBanner("Refreshing the saved snapshot…", UiTone.Neutral, null)
                    }
                }
                HomeKpis(
                    snapshot = snapshot,
                    loading = presentation == CachedDataPresentation.INITIAL_LOADING,
                    wide = wide,
                )
                SnapshotProvenance(
                    snapshot = snapshot,
                    stale = presentation == CachedDataPresentation.STALE,
                    refreshing = state.loading,
                    onRefresh = onRetry,
                )
                HomeShortcuts(shortcuts, wide, navigateTo)
                HomeLowerPanels(
                    activity = state.activity,
                    capabilities = state.activityCapabilities,
                    timezone = snapshot?.timezone,
                    hourly = state.aggregate?.hourly.orEmpty(),
                    profileRevenueMinor = state.aggregate?.profileRevenueMinor,
                    wide = wide,
                    canOpenAnalytics = Destination.Analytics in destinations,
                    onOpenAnalytics = { navigateTo(Destination.Analytics) },
                )
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun ExecutiveHomeWideWorkspace(
    employeeName: String,
    locationLabel: String,
    snapshot: ExecutiveHomeSnapshot?,
    presentation: CachedDataPresentation,
    todayError: String?,
    refreshing: Boolean,
    activity: List<HomeActivity>,
    capabilities: HomeActivityCapabilities?,
    shortcuts: List<ExecutiveHomeShortcut>,
    canOpenAnalytics: Boolean,
    navigateTo: (Destination) -> Unit,
    onRetry: () -> Unit,
    hour: Int,
    geometry: HomeWideGeometry,
    modifier: Modifier,
) {
    val fontScale = LocalDensity.current.fontScale
    Box(modifier) {
        HomeWideHero(
            greeting = executiveGreeting(employeeName, hour),
            locationLabel = locationLabel,
            snapshot = snapshot,
            presentation = presentation,
            todayError = todayError,
            refreshing = refreshing,
            onRefresh = onRetry,
            contentBottomReserve = homeHeroContentBottomReserve(
                geometry.heroHeight,
                geometry.kpiTop,
                fontScale,
            ),
            modifier = Modifier.fillMaxWidth().height(geometry.heroHeight),
        )
        HomeWideKpis(
            snapshot = snapshot,
            loading = presentation == CachedDataPresentation.INITIAL_LOADING,
            modifier = Modifier.fillMaxWidth().height(geometry.kpiHeight)
                .padding(horizontal = 18.dp)
                .offset(y = geometry.kpiTop),
        )
        HomeWideShortcuts(
            shortcuts = shortcuts,
            navigateTo = navigateTo,
            modifier = Modifier.fillMaxWidth().height(geometry.shortcutHeight)
                .padding(horizontal = 18.dp)
                .offset(y = geometry.shortcutTop),
        )
        HomeWideLowerPanels(
            activity = activity,
            capabilities = capabilities,
            timezone = snapshot?.timezone,
            hourly = snapshot?.hourly.orEmpty(),
            profileRevenueMinor = snapshot?.profileRevenueMinor,
            canOpenAnalytics = canOpenAnalytics,
            onOpenAnalytics = { navigateTo(Destination.Analytics) },
            modifier = Modifier.fillMaxWidth().height(geometry.lowerHeight)
                .padding(horizontal = 16.dp)
                .offset(y = geometry.lowerTop),
        )
    }
}

@Composable
private fun HomeWideHero(
    greeting: String,
    locationLabel: String,
    snapshot: ExecutiveHomeSnapshot?,
    presentation: CachedDataPresentation,
    todayError: String?,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    contentBottomReserve: androidx.compose.ui.unit.Dp,
    modifier: Modifier,
) {
    BoxWithConstraints(modifier.testTag("home-hero")) {
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(
                    0f to Color.Black.copy(alpha = 0.52f),
                    0.38f to Color.Black.copy(alpha = 0.24f),
                    0.76f to Color.Black.copy(alpha = 0.06f),
                    1f to Color.Transparent,
                ),
            ),
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to ExecutiveHomeFinish.Canvas.copy(alpha = 0.20f),
                    0.44f to Color.Transparent,
                    1f to ExecutiveHomeFinish.Canvas.copy(alpha = 0.34f),
                ),
            ),
        )
        Column(
            Modifier.align(Alignment.BottomStart).widthIn(max = maxWidth * 0.46f)
                .padding(start = 28.dp, bottom = contentBottomReserve),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                greeting,
                modifier = Modifier.fillMaxWidth().testTag("home-greeting"),
                style = ExecutiveHomeType.Greeting,
                color = ExecutiveHomeFinish.Text,
                maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Clip,
            )
            Text(
                locationLabel.ifBlank { "Workspace location unavailable" },
                modifier = Modifier.fillMaxWidth(),
                style = ExecutiveHomeType.HeroMeta,
                color = ExecutiveHomeFinish.TextMuted,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
        HomeWideHeroStatus(
            snapshot = snapshot,
            presentation = presentation,
            todayError = todayError,
            refreshing = refreshing,
            onRefresh = onRefresh,
            modifier = Modifier.align(Alignment.BottomEnd)
                .padding(end = 22.dp, bottom = contentBottomReserve - 8.dp)
                .width(homeHeroStatusWidth(maxWidth)),
        )
    }
}

@Composable
private fun HomeWideHeroStatus(
    snapshot: ExecutiveHomeSnapshot?,
    presentation: CachedDataPresentation,
    todayError: String?,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier,
) {
    val finish = executiveHomePalette()
    val provenance = when {
        snapshot == null -> "Verified snapshot unavailable"
        snapshot.sourceTimestamp == null -> "Snapshot ${snapshot.snapshotDate} · source time unavailable"
        presentation == CachedDataPresentation.STALE ->
            "Saved ${snapshot.snapshotDate} · fetched ${snapshot.sourceTimestamp}"
        else -> "Snapshot ${snapshot.snapshotDate} · fetched ${snapshot.sourceTimestamp}"
    }
    val stateMessage = when (presentation) {
        CachedDataPresentation.INITIAL_LOADING -> "Loading verified figures…"
        CachedDataPresentation.BLOCKING_ERROR -> todayError ?: "Dashboard data is unavailable."
        CachedDataPresentation.STALE -> todayError ?: "Live refresh unavailable."
        CachedDataPresentation.FRESH -> if (refreshing) "Refreshing verified figures…" else null
    }
    Row(
        modifier.testTag("home-hero-status"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(
            Modifier.weight(1f)
                .clip(RoundedCornerShape(8.dp))
                .homeGlass(homeHeroStatusGlassRole(presentation), RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 3.dp)
                .testTag("home-status-glass"),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            Text(
                provenance,
                modifier = Modifier.fillMaxWidth().testTag("home-snapshot-label"),
                color = finish.textMuted,
                style = ExecutiveHomeType.ShortcutMeta,
                maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Clip,
            )
            stateMessage?.let {
                Text(
                    it,
                    modifier = Modifier.fillMaxWidth().testTag(
                        if (refreshing) "home-refresh-label" else "home-state-banner",
                    ),
                    color = if (presentation == CachedDataPresentation.BLOCKING_ERROR) {
                        finish.error
                    } else {
                        finish.text
                    },
                    style = ExecutiveHomeType.ShortcutMeta,
                    maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
        }
        Button(
            onClick = onRefresh,
            enabled = !refreshing,
            modifier = Modifier.size(48.dp).clip(RoundedCornerShape(9.dp))
                .homeGlass(
                    if (refreshing) WorkspaceGlassRole.DISABLED else WorkspaceGlassRole.CONTROL,
                    RoundedCornerShape(9.dp),
                )
                .testTag("home-refresh")
                .semantics {
                    contentDescription = when {
                        refreshing -> "Refreshing dashboard snapshot"
                        presentation == CachedDataPresentation.BLOCKING_ERROR -> "Retry dashboard snapshot"
                        else -> "Refresh dashboard snapshot"
                    }
                    if (refreshing) stateDescription = "Refresh in progress"
                },
            shape = RoundedCornerShape(9.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.Transparent,
                contentColor = finish.gold,
                disabledContainerColor = Color.Transparent,
                disabledContentColor = finish.text,
            ),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
        ) {
            if (refreshing) {
                CircularProgressIndicator(
                    Modifier.size(17.dp).testTag("home-refresh-indicator"),
                    color = finish.text,
                    strokeWidth = 2.dp,
                )
            } else {
                Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun HomeWideKpis(
    snapshot: ExecutiveHomeSnapshot?,
    loading: Boolean,
    modifier: Modifier,
) {
    val fallback = if (loading) "Loading…" else "Unavailable"
    val capacity = snapshot?.let {
        homeCapacityPresentation(it.openSessions, it.activeStationCapacity)
    }
    val rows = listOf(
        HomeWideKpiData(
            "Today's sales",
            snapshot?.revenue ?: fallback,
            snapshot?.revenueComparison
                ?: if (snapshot == null) "Revenue unavailable" else "No previous-day baseline",
            Icons.Filled.BarChart,
        ),
        HomeWideKpiData(
            "Active sessions",
            capacity?.value ?: fallback,
            capacity?.detail ?: "Sessions unavailable",
            Icons.Filled.SportsEsports,
            progress = capacity,
        ),
        HomeWideKpiData(
            "Orders",
            snapshot?.orders ?: fallback,
            snapshot?.ordersComparison
                ?: if (snapshot == null) "Orders unavailable" else "No previous-day baseline",
            Icons.Filled.ShoppingCart,
        ),
        HomeWideKpiData(
            "Customers",
            snapshot?.customers ?: "Unavailable",
            if (snapshot == null) "Customer count unavailable" else "Company live directory",
            Icons.Filled.People,
        ),
    )
    Row(
        modifier.testTag("home-kpi-row"),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        rows.forEach { row -> HomeWideKpi(row, Modifier.weight(1f).fillMaxHeight()) }
    }
}

private data class HomeWideKpiData(
    val label: String,
    val value: String,
    val detail: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val progress: HomeCapacityPresentation? = null,
)

@Composable
private fun HomeWideKpi(data: HomeWideKpiData, modifier: Modifier) {
    val finish = executiveHomePalette()
    Surface(
        modifier = modifier,
        color = Color.Transparent,
        shape = WorkspacePanelShape,
        shadowElevation = 1.dp,
    ) {
        Box(Modifier.fillMaxSize().homeGlass(WorkspaceGlassRole.PANEL, WorkspacePanelShape)) {
            Column(
                Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 9.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    data.label,
                    Modifier.fillMaxWidth(),
                    style = ExecutiveHomeType.KpiLabel,
                    color = finish.textMuted,
                    maxLines = 1,
                )
                Text(
                    data.value,
                    Modifier.fillMaxWidth(),
                    style = if (data.value.length > 12) {
                        ExecutiveHomeType.PanelTitle
                    } else {
                        ExecutiveHomeType.KpiValue
                    },
                    color = finish.text,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                Text(
                    data.detail,
                    Modifier.fillMaxWidth().padding(end = if (data.progress == null) 28.dp else 0.dp),
                    style = ExecutiveHomeType.KpiDetail,
                    color = finish.textQuiet,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                data.progress?.let { capacity ->
                    HomeCapacityTrack(capacity, Modifier.fillMaxWidth())
                }
            }
            if (data.progress == null) {
                Icon(
                    data.icon,
                    contentDescription = null,
                    tint = finish.gold.copy(alpha = 0.56f),
                    modifier = Modifier.align(Alignment.BottomEnd)
                        .padding(end = 12.dp, bottom = 9.dp).size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun HomeCapacityTrack(capacity: HomeCapacityPresentation, modifier: Modifier = Modifier) {
    val finish = executiveHomePalette()
    Box(
        modifier.height(4.dp).clip(RoundedCornerShape(2.dp))
            .background(finish.outline.copy(alpha = 0.58f))
            .testTag("home-session-capacity")
            .semantics {
                contentDescription = capacity.accessibility
                stateDescription = capacity.detail
            },
    ) {
        capacity.fillFraction?.let { fraction ->
            Box(
                Modifier.fillMaxHeight().fillMaxWidth(fraction)
                    .background(finish.gold),
            )
        }
    }
}

@Composable
private fun HomeWideShortcuts(
    shortcuts: List<ExecutiveHomeShortcut>,
    navigateTo: (Destination) -> Unit,
    modifier: Modifier,
) {
    Row(
        modifier.testTag("home-shortcut-row")
            .semantics { contentDescription = "Operational shortcuts" },
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        homeWideShortcutSlots(shortcuts).forEach { shortcut ->
            if (shortcut == null) {
                Spacer(Modifier.weight(1f).fillMaxHeight())
            } else {
                HomeWideShortcut(shortcut, Modifier.weight(1f).fillMaxHeight(), navigateTo)
            }
        }
    }
}

private fun homeShortcutIcon(destination: Destination) = when (destination) {
    Destination.Gaming -> Icons.Filled.SportsEsports
    Destination.Pos -> Icons.Filled.ShoppingCart
    Destination.Shift -> Icons.Filled.WatchLater
    Destination.Customers -> Icons.Filled.People
    Destination.Inventory -> Icons.Filled.Inventory2
    Destination.Reports -> Icons.Filled.BarChart
    else -> destination.icon
}

@Composable
private fun HomeWideShortcut(
    shortcut: ExecutiveHomeShortcut,
    modifier: Modifier,
    navigateTo: (Destination) -> Unit,
) {
    val finish = executiveHomePalette()
    val gaming = shortcut.destination == Destination.Gaming
    val accent = if (gaming) finish.gold else finish.lavender
    val visibleLabel = homeShortcutVisibleLabel(shortcut.destination)
    Surface(
        modifier = modifier.clickable(role = Role.Button) { navigateTo(shortcut.destination) }
            .testTag("home-shortcut-${shortcut.destination.name}")
            .semantics { role = Role.Button },
        color = Color.Transparent,
        shape = RoundedCornerShape(10.dp),
        shadowElevation = if (gaming) 2.dp else 0.dp,
    ) {
        Box(
            Modifier.fillMaxSize().homeGlass(
                if (gaming) WorkspaceGlassRole.SELECTED_OUTLINE else WorkspaceGlassRole.PANEL,
                RoundedCornerShape(10.dp),
            ),
        ) {
            Column(
                Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                    if (gaming) {
                        Box(
                            Modifier.fillMaxSize().background(
                                Brush.radialGradient(
                                    listOf(finish.gold.copy(alpha = 0.15f), Color.Transparent),
                                ),
                            ),
                        )
                    }
                    Icon(
                        homeShortcutIcon(shortcut.destination),
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(30.dp),
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    visibleLabel,
                    Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                    style = ExecutiveHomeType.Shortcut,
                    color = finish.text,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                if (gaming) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            shortcut.action,
                            style = ExecutiveHomeType.ShortcutMeta,
                            color = finish.textQuiet,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                        Icon(
                            Icons.Filled.ChevronRight,
                            contentDescription = null,
                            tint = finish.gold,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                } else {
                    Text(
                        shortcut.action,
                        Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                        style = ExecutiveHomeType.ShortcutMeta,
                        color = finish.textQuiet,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun HomeWideLowerPanels(
    activity: List<HomeActivity>,
    capabilities: HomeActivityCapabilities?,
    timezone: String?,
    hourly: List<HomeHourPoint>,
    profileRevenueMinor: Long?,
    canOpenAnalytics: Boolean,
    onOpenAnalytics: () -> Unit,
    modifier: Modifier,
) {
    Row(
        modifier.testTag("home-lower-row"),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        HomeActivityPanel(
            activity = activity,
            capabilities = capabilities,
            timezone = timezone,
            modifier = Modifier.weight(0.49f).fillMaxHeight(),
            wide = true,
        )
        HomeSalesProfilePanel(
            hourly = hourly,
            profileRevenueMinor = profileRevenueMinor,
            modifier = Modifier.weight(0.51f).fillMaxHeight(),
            wide = true,
            action = if (canOpenAnalytics) "Open Analytics" else null,
            onAction = if (canOpenAnalytics) onOpenAnalytics else null,
        )
    }
}

@Composable
private fun HomePanelShell(
    tagPrefix: String,
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    val finish = executiveHomePalette()
    Surface(
        modifier = modifier.testTag(tagPrefix),
        color = Color.Transparent,
        shape = WorkspacePanelShape,
        shadowElevation = 1.dp,
    ) {
        Column(
            Modifier.fillMaxSize().homeGlass(WorkspaceGlassRole.PANEL, WorkspacePanelShape)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = finish.gold,
                    modifier = Modifier.size(19.dp).testTag("$tagPrefix-header-icon"),
                )
                Text(title, Modifier.weight(1f), style = ExecutiveHomeType.PanelTitle, color = finish.text, maxLines = 1)
            }
            Spacer(Modifier.height(14.dp))
            content()
        }
    }
}

@Composable
private fun HomeActivityPanel(
    activity: List<HomeActivity>,
    capabilities: HomeActivityCapabilities?,
    timezone: String?,
    modifier: Modifier,
    wide: Boolean,
) {
    val finish = executiveHomePalette()
    HomePanelShell("home-recent-activity", "Recent activity", Icons.Filled.History, modifier) {
        if (activity.isEmpty()) {
            val detail = when {
                capabilities == null -> "Live activity is unavailable offline"
                capabilities.pos != "available" && capabilities.gaming != "available" -> "Your role cannot view recent operational activity"
                else -> "No recent activity in this workspace"
            }
            Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.Center) {
                Text(detail, Modifier.fillMaxWidth().testTag("home-activity-empty"), textAlign = TextAlign.Center, style = ExecutiveHomeType.PanelBody, color = finish.textMuted, maxLines = 3)
            }
        } else {
            activity.take(if (wide) 5 else 4).forEachIndexed { index, row ->
                val localTime = timezone?.let { homeActivityVisibleTime(row.occurredAt, it) }
                val accessibleTime = timezone?.let { homeActivityAccessibleTime(row.occurredAt, it) }
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 38.dp)
                        .padding(vertical = 2.dp)
                        .testTag("home-activity-$index")
                        .semantics(mergeDescendants = true) {
                            contentDescription = buildString {
                                append(row.title)
                                append(", ")
                                append(row.displayReference)
                                accessibleTime?.let {
                                    append(", occurred ")
                                    append(it)
                                }
                                row.amountMinor?.let {
                                    append(", ")
                                    append(homeMinorAsRupees(it))
                                }
                            }
                        },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        if (row.kind == "gaming_session_started") Icons.Filled.SportsEsports else Icons.Filled.PointOfSale,
                        contentDescription = null,
                        tint = finish.lavender,
                        modifier = Modifier.size(18.dp),
                    )
                    Column(Modifier.weight(1f)) {
                        Text(
                            row.title,
                            modifier = Modifier.testTag("home-activity-title-$index"),
                            style = ExecutiveHomeType.PanelBody,
                            color = finish.text,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                        Text(
                            buildString {
                                append(row.displayReference)
                                localTime?.let { append(" · ").append(it) }
                            },
                            modifier = Modifier.testTag("home-activity-time-$index"),
                            style = ExecutiveHomeType.ShortcutMeta,
                            color = finish.textQuiet,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                    }
                    row.amountMinor?.let { Text(it.asRupees(), style = ExecutiveHomeType.ShortcutMeta, color = finish.textMuted, maxLines = 1) }
                }
            }
        }
    }
}

@Composable
private fun HomeSalesProfilePanel(
    hourly: List<HomeHourPoint>,
    profileRevenueMinor: Long?,
    modifier: Modifier,
    wide: Boolean,
    action: String?,
    onAction: (() -> Unit)?,
) {
    val finish = executiveHomePalette()
    HomePanelShell("home-sales-trend", "POS sales by local hour", Icons.AutoMirrored.Filled.ShowChart, modifier) {
        if (hourly.size != 24) {
            Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.Center) {
                Text("Hourly POS profile unavailable", Modifier.fillMaxWidth().testTag("home-profile-empty"), textAlign = TextAlign.Center, style = ExecutiveHomeType.PanelBody, color = finish.textMuted)
            }
        } else {
            val plot = homeSalesProfilePlot(hourly)
            val chartModifier = if (wide) {
                Modifier.weight(1f).heightIn(min = 100.dp)
            } else {
                Modifier.height(72.dp)
            }
            Canvas(
                chartModifier.fillMaxWidth()
                    .testTag("home-hour-profile")
                    .semantics {
                        contentDescription = homeSalesProfileAccessibility(hourly)
                    },
            ) {
                val horizontalInset = 2.dp.toPx()
                val verticalInset = 3.dp.toPx()
                val plotWidth = (size.width - horizontalInset * 2f).coerceAtLeast(1f)
                val plotHeight = (size.height - verticalInset * 2f).coerceAtLeast(1f)
                fun x(index: Int): Float =
                    horizontalInset + plotWidth * index.toFloat() / (plot.yFractions.lastIndex.coerceAtLeast(1))
                fun y(fraction: Float): Float = verticalInset + plotHeight * fraction

                listOf(0, 6, 12, 18, 23).forEach { hour ->
                    drawLine(
                        color = finish.outline.copy(alpha = 0.34f),
                        start = androidx.compose.ui.geometry.Offset(x(hour), verticalInset),
                        end = androidx.compose.ui.geometry.Offset(x(hour), verticalInset + plotHeight),
                        strokeWidth = 0.75.dp.toPx(),
                    )
                }
                val zeroY = y(plot.zeroFraction)
                drawLine(
                    color = finish.textQuiet.copy(alpha = 0.44f),
                    start = androidx.compose.ui.geometry.Offset(horizontalInset, zeroY),
                    end = androidx.compose.ui.geometry.Offset(horizontalInset + plotWidth, zeroY),
                    strokeWidth = 0.85.dp.toPx(),
                )

                val area = Path().apply {
                    moveTo(x(0), zeroY)
                    lineTo(x(0), y(plot.yFractions.first()))
                    plot.yFractions.drop(1).forEachIndexed { offset, fraction ->
                        lineTo(x(offset + 1), y(fraction))
                    }
                    lineTo(x(plot.yFractions.lastIndex), zeroY)
                    close()
                }
                drawPath(area, color = finish.gold.copy(alpha = 0.16f))

                val line = Path().apply {
                    moveTo(x(0), y(plot.yFractions.first()))
                    plot.yFractions.drop(1).forEachIndexed { offset, fraction ->
                        lineTo(x(offset + 1), y(fraction))
                    }
                }
                drawPath(
                    path = line,
                    color = finish.gold,
                    style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round),
                )
            }
            HomeHourAxis(Modifier.fillMaxWidth().padding(horizontal = 2.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "POS ${profileRevenueMinor?.let(::homeMinorAsRupees) ?: "Unavailable"}",
                    Modifier.testTag("home-profile-total"),
                    style = ExecutiveHomeType.ShortcutMeta,
                    color = finish.textMuted,
                    maxLines = 1,
                )
                Text(
                    homeSalesProfileRange(hourly),
                    Modifier.testTag("home-profile-range"),
                    style = ExecutiveHomeType.ShortcutMeta,
                    color = finish.textQuiet,
                    maxLines = 1,
                )
            }
        }
        if (action != null && onAction != null) {
            Row(
                Modifier.align(Alignment.End).heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onAction)
                    .testTag("home-open-analytics").padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    action,
                    modifier = Modifier.testTag("home-open-analytics-label"),
                    color = finish.text,
                    style = ExecutiveHomeType.Shortcut,
                )
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = finish.gold)
            }
        }
    }
}

@Composable
private fun HomeHourAxis(modifier: Modifier = Modifier) {
    val finish = executiveHomePalette()
    val hours = listOf(0, 6, 12, 18, 23)
    Layout(
        modifier = modifier.heightIn(min = 16.dp).testTag("home-hour-axis"),
        content = {
            hours.forEach { hour ->
                Text(
                    homeHourLabel(hour),
                    Modifier.testTag("home-hour-label-$hour"),
                    style = ExecutiveHomeType.ShortcutMeta,
                    color = finish.textQuiet,
                    maxLines = 1,
                )
            }
        },
    ) { measurables, constraints ->
        val placeables = measurables.map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
        val height = (placeables.maxOfOrNull { it.height } ?: constraints.minHeight)
            .coerceIn(constraints.minHeight, constraints.maxHeight)
        layout(constraints.maxWidth, height) {
            placeables.forEachIndexed { index, placeable ->
                val hour = hours[index]
                val anchor = constraints.maxWidth.toFloat() * hour.toFloat() / 23f
                val x = when (hour) {
                    0 -> 0
                    23 -> constraints.maxWidth - placeable.width
                    else -> (anchor - placeable.width / 2f).roundToInt()
                }.coerceIn(0, (constraints.maxWidth - placeable.width).coerceAtLeast(0))
                placeable.placeRelative(x, 0)
            }
        }
    }
}

@Composable
private fun HomeHero(greeting: String, locationLabel: String, compact: Boolean) {
    val largeText = androidx.compose.ui.platform.LocalDensity.current.fontScale > 1.15f
    val heroHeight = when {
        largeText && compact -> 156.dp
        largeText -> 168.dp
        compact -> 124.dp
        else -> 138.dp
    }
    Box(
        Modifier.fillMaxWidth().height(heroHeight)
            .clip(RoundedCornerShape(16.dp)).testTag("home-hero"),
    ) {
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(
                    0f to Color.Black.copy(alpha = 0.78f),
                    0.58f to Color.Black.copy(alpha = 0.26f),
                    1f to Color.Transparent,
                ),
            ),
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to Color.Transparent,
                    1f to Color.Black.copy(alpha = 0.38f),
                ),
            ),
        )
        Column(
            Modifier.align(Alignment.BottomStart).fillMaxWidth()
                .padding(
                    start = if (compact) 16.dp else 22.dp,
                    end = if (compact) 16.dp else 22.dp,
                    top = 12.dp,
                    bottom = 16.dp,
                ),
        ) {
            Text(
                greeting,
                modifier = Modifier.fillMaxWidth(),
                style = if (compact) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )
            Text(
                locationLabel.ifBlank { "Workspace location unavailable" },
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.82f),
            )
        }
    }
}

@Composable
private fun HomeStateBanner(message: String, tone: UiTone, onRetry: (() -> Unit)?) {
    val container = when (tone) {
        UiTone.Danger -> MaterialTheme.colorScheme.errorContainer
        UiTone.Warning -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceContainerHigh
    }
    Surface(
        modifier = Modifier.fillMaxWidth().clip(Radius.shapeMd).homeGlass(
            if (tone == UiTone.Danger) WorkspaceGlassRole.ERROR else WorkspaceGlassRole.WARNING,
            Radius.shapeMd,
        ).testTag("home-state-banner"),
        color = Color.Transparent,
        shape = Radius.shapeMd,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(message, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            if (onRetry != null) {
                Button(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp).testTag("home-retry")) {
                    Icon(Icons.Filled.Refresh, contentDescription = null)
                    Text("Retry", Modifier.padding(start = 6.dp))
                }
            }
        }
    }
}

@Composable
private fun HomeKpis(snapshot: ExecutiveHomeSnapshot?, loading: Boolean, wide: Boolean) {
    val fallback = if (loading) "Loading…" else "Unavailable"
    val capacity = snapshot?.let {
        homeCapacityPresentation(it.openSessions, it.activeStationCapacity)
    }
    val rows = listOf(
        HomeWideKpiData(
            "Gross revenue",
            snapshot?.revenue ?: fallback,
            snapshot?.revenueComparison ?: if (snapshot == null) "Revenue unavailable" else "No previous-day baseline",
            Icons.Filled.BarChart,
        ),
        HomeWideKpiData(
            "Active sessions",
            capacity?.value ?: fallback,
            capacity?.detail ?: "Sessions unavailable",
            Icons.Filled.SportsEsports,
            progress = capacity,
        ),
        HomeWideKpiData(
            "Orders",
            snapshot?.orders ?: fallback,
            snapshot?.ordersComparison ?: if (snapshot == null) "Orders unavailable" else "No previous-day baseline",
            Icons.Filled.PointOfSale,
        ),
        HomeWideKpiData(
            "Customers",
            snapshot?.customers ?: "Unavailable",
            if (snapshot == null) "Customer count unavailable" else "Company live directory",
            Icons.Filled.People,
        ),
    )
    if (wide) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            rows.forEach { data -> HomeKpi(data, Modifier.weight(1f)) }
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            rows.chunked(2).forEach { pair ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    pair.forEach { data -> HomeKpi(data, Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun HomeKpi(data: HomeWideKpiData, modifier: Modifier) {
    Surface(
        modifier = modifier.heightIn(min = 112.dp).clip(WorkspacePanelShape)
            .homeGlass(WorkspaceGlassRole.PANEL, WorkspacePanelShape),
        color = Color.Transparent,
        shape = WorkspacePanelShape,
    ) {
        Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(data.label, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Icon(data.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            }
            Text(data.value, Modifier.fillMaxWidth(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                data.detail,
                Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )
            data.progress?.let { HomeCapacityTrack(it, Modifier.fillMaxWidth()) }
        }
    }
}

@Composable
private fun SnapshotProvenance(
    snapshot: ExecutiveHomeSnapshot?,
    stale: Boolean,
    refreshing: Boolean,
    onRefresh: () -> Unit,
) {
    val label = when {
        snapshot == null -> "No verified dashboard snapshot"
        snapshot.sourceTimestamp == null -> "Snapshot ${snapshot.snapshotDate} · source time unavailable"
        stale -> "Saved snapshot ${snapshot.snapshotDate} · fetched ${snapshot.sourceTimestamp}"
        else -> "Snapshot ${snapshot.snapshotDate} · fetched ${snapshot.sourceTimestamp}"
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            Modifier.weight(1f).testTag("home-snapshot-label"),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(
            onClick = onRefresh,
            enabled = !refreshing,
            modifier = Modifier.heightIn(min = 48.dp).testTag("home-refresh"),
            colors = ButtonDefaults.buttonColors(
                disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                disabledContentColor = MaterialTheme.colorScheme.onSurface,
            ),
        ) {
            if (refreshing) {
                CircularProgressIndicator(
                    Modifier.size(16.dp).testTag("home-refresh-indicator"),
                    color = MaterialTheme.colorScheme.onSurface,
                    strokeWidth = 2.dp,
                )
            } else {
                Icon(Icons.Filled.Refresh, contentDescription = null)
            }
            Text(
                if (refreshing) "Refreshing…" else "Refresh",
                Modifier.padding(start = 6.dp).testTag("home-refresh-label"),
            )
        }
    }
}

@Composable
private fun HomeShortcuts(shortcuts: List<ExecutiveHomeShortcut>, wide: Boolean, navigateTo: (Destination) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Quick access", Modifier.fillMaxWidth(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        if (wide) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                shortcuts.forEach { shortcut -> HomeShortcut(shortcut, Modifier.weight(1f), navigateTo) }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                shortcuts.chunked(2).forEach { pair ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        pair.forEach { shortcut -> HomeShortcut(shortcut, Modifier.weight(1f), navigateTo) }
                        repeat(2 - pair.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeShortcut(shortcut: ExecutiveHomeShortcut, modifier: Modifier, navigateTo: (Destination) -> Unit) {
    Surface(
        modifier = modifier.heightIn(min = 84.dp).clip(Radius.shapeMd)
            .clickable(role = Role.Button) { navigateTo(shortcut.destination) }
            .homeGlass(
                if (shortcut.destination == Destination.Gaming) WorkspaceGlassRole.SELECTED_OUTLINE
                else WorkspaceGlassRole.PANEL,
                Radius.shapeMd,
            )
            .testTag("home-shortcut-${shortcut.destination.name}")
            .semantics { role = Role.Button },
        color = Color.Transparent,
        shape = Radius.shapeMd,
    ) {
        Column(
            Modifier.fillMaxSize().padding(10.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                shortcut.destination.icon,
                contentDescription = null,
                tint = if (shortcut.destination == Destination.Gaming) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Text(shortcut.destination.label, Modifier.fillMaxWidth(), textAlign = TextAlign.Center, fontWeight = FontWeight.SemiBold)
            Text(shortcut.action, Modifier.fillMaxWidth(), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun HomeLowerPanels(
    activity: List<HomeActivity>,
    capabilities: HomeActivityCapabilities?,
    timezone: String?,
    hourly: List<HomeHourPoint>,
    profileRevenueMinor: Long?,
    wide: Boolean,
    canOpenAnalytics: Boolean,
    onOpenAnalytics: () -> Unit,
) {
    if (wide) {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            HomeActivityPanel(activity, capabilities, timezone, Modifier.weight(1f).heightIn(min = 180.dp), wide = false)
            HomeSalesProfilePanel(
                hourly,
                profileRevenueMinor,
                Modifier.weight(1f).heightIn(min = 180.dp),
                wide = false,
                action = if (canOpenAnalytics) "Open Analytics" else null,
                onAction = if (canOpenAnalytics) onOpenAnalytics else null,
            )
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            HomeActivityPanel(activity, capabilities, timezone, Modifier.fillMaxWidth().heightIn(min = 180.dp), wide = false)
            HomeSalesProfilePanel(
                hourly,
                profileRevenueMinor,
                Modifier.fillMaxWidth().heightIn(min = 180.dp),
                wide = false,
                action = if (canOpenAnalytics) "Open Analytics" else null,
                onAction = if (canOpenAnalytics) onOpenAnalytics else null,
            )
        }
    }
}
