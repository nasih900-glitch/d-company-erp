package cloud.dcompany.erp.ui.screens.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.PointOfSale
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cloud.dcompany.erp.R
import cloud.dcompany.erp.core.net.asRupees
import cloud.dcompany.erp.ui.Destination
import cloud.dcompany.erp.ui.components.UiTone
import cloud.dcompany.erp.ui.screens.analytics.AnalyticsUiState
import cloud.dcompany.erp.ui.screens.analytics.AnalyticsViewModel
import cloud.dcompany.erp.ui.screens.analytics.CachedDataPresentation
import cloud.dcompany.erp.ui.screens.analytics.cachedDataPresentation
import cloud.dcompany.erp.ui.theme.Radius
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

internal const val HomeLoungeAssetSha256 =
    "b1dfa002e84fe40756636921ad0e10f353b74037e749dbe8082b8865fc965f38"
private const val HomeHeroFocalY = 0.62f
internal val HomeBusinessZone: ZoneId = ZoneId.of("Asia/Kolkata")

internal data class HomeHeroSourceCrop(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
)

internal fun homeHeroSourceCrop(
    sourceWidth: Int,
    sourceHeight: Int,
    destinationWidth: Int,
    destinationHeight: Int,
): HomeHeroSourceCrop {
    require(sourceWidth > 0 && sourceHeight > 0 && destinationWidth > 0 && destinationHeight > 0)
    val sourceRatio = sourceWidth.toFloat() / sourceHeight
    val destinationRatio = destinationWidth.toFloat() / destinationHeight
    return if (destinationRatio > sourceRatio) {
        val height = (sourceWidth / destinationRatio).roundToInt().coerceIn(1, sourceHeight)
        val focalCenter = (sourceHeight * HomeHeroFocalY).roundToInt()
        val top = (focalCenter - height / 2).coerceIn(0, sourceHeight - height)
        HomeHeroSourceCrop(0, top, sourceWidth, height)
    } else {
        val width = (sourceHeight * destinationRatio).roundToInt().coerceIn(1, sourceWidth)
        HomeHeroSourceCrop((sourceWidth - width) / 2, 0, width, sourceHeight)
    }
}

internal data class ExecutiveHomeSnapshot(
    val revenue: String,
    val orders: String,
    val sessions: String,
    val customers: String,
    val snapshotDate: String,
    val sourceTimestamp: String?,
)

internal data class ExecutiveHomeShortcut(
    val destination: Destination,
    val action: String,
)

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

internal fun executiveHomeSnapshot(
    state: AnalyticsUiState,
): ExecutiveHomeSnapshot? = state.dashboard?.let { dashboard ->
    ExecutiveHomeSnapshot(
        revenue = dashboard.revenueTotalMinor.asRupees(),
        orders = dashboard.ordersCount.toString(),
        sessions = dashboard.openSessions.toString(),
        customers = "Unavailable",
        snapshotDate = homeSnapshotDate(dashboard.date),
        sourceTimestamp = state.dashboardFetchedAtMillis?.let(::homeSourceTimestamp),
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

private fun homeSourceTimestamp(fetchedAtMillis: Long): String =
    Instant.ofEpochMilli(fetchedAtMillis).atZone(HomeBusinessZone)
        .format(DateTimeFormatter.ofPattern("d MMM uuuu, h:mm a 'IST'", Locale.ENGLISH))

@Composable
fun ExecutiveHomeScreen(
    employeeName: String,
    locationLabel: String,
    destinations: List<Destination>,
    navigateTo: (Destination) -> Unit,
    analyticsViewModel: AnalyticsViewModel = viewModel(),
) {
    val state by analyticsViewModel.state.collectAsStateWithLifecycle()
    ExecutiveHomeWorkspace(
        employeeName = employeeName,
        locationLabel = locationLabel,
        state = state,
        destinations = destinations,
        navigateTo = navigateTo,
        onRetry = analyticsViewModel::retryToday,
    )
}

@Composable
fun ExecutiveHomeWorkspace(
    employeeName: String,
    locationLabel: String,
    state: AnalyticsUiState,
    destinations: List<Destination>,
    navigateTo: (Destination) -> Unit,
    onRetry: () -> Unit,
    hour: Int = java.time.LocalTime.now().hour,
    reviewFixtureLabel: String? = null,
) {
    val snapshot = executiveHomeSnapshot(state)
    val presentation = cachedDataPresentation(
        hasData = snapshot != null,
        loading = state.todayLoading,
        error = state.todayError,
    )
    val shortcuts = executiveHomeShortcuts(destinations)

    BoxWithConstraints(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .testTag("executive-home-workspace"),
    ) {
        val wide = maxWidth >= 840.dp
        val pagePadding = if (wide) 24.dp else 16.dp
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = pagePadding, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            HomeHero(
                greeting = executiveGreeting(employeeName, hour),
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
                    state.todayError ?: "Dashboard data is unavailable.",
                    UiTone.Danger,
                    onRetry,
                )
                CachedDataPresentation.STALE -> HomeStateBanner(
                    "Saved snapshot · ${state.todayError ?: "Live refresh unavailable."}",
                    UiTone.Warning,
                    onRetry,
                )
                CachedDataPresentation.FRESH -> if (state.todayLoading) {
                    HomeStateBanner("Refreshing the saved snapshot…", UiTone.Neutral, null)
                }
            }
            HomeKpis(snapshot = snapshot, loading = presentation == CachedDataPresentation.INITIAL_LOADING, wide = wide)
            SnapshotProvenance(
                snapshot = snapshot,
                stale = presentation == CachedDataPresentation.STALE,
                refreshing = state.todayLoading,
                onRefresh = onRetry,
            )
            HomeShortcuts(shortcuts, wide, navigateTo)
            HomeLowerPanels(
                wide = wide,
                canOpenAnalytics = Destination.Analytics in destinations,
                onOpenAnalytics = { navigateTo(Destination.Analytics) },
            )
            Spacer(Modifier.height(12.dp))
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
    BoxWithConstraints(
        Modifier.fillMaxWidth().height(heroHeight)
            .clip(RoundedCornerShape(16.dp)).testTag("home-hero"),
    ) {
        HomeLoungeHero(
            Modifier.fillMaxSize(),
            contentDescription = "D Company lounge dashboard artwork",
        )
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
        if (!compact && !largeText) {
            Column(
                Modifier.align(Alignment.TopStart).offset(x = maxWidth * 0.72f - 66.dp)
                    .width(132.dp).padding(top = 12.dp).testTag("home-hero-brand"),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Image(
                    painter = painterResource(R.drawable.login_logo_d),
                    contentDescription = null,
                    modifier = Modifier.size(34.dp),
                )
                Text(
                    "D COMPANY",
                    Modifier.fillMaxWidth(),
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.5.sp,
                    textAlign = TextAlign.Center,
                )
                Text(
                    "GAMING LOUNGE",
                    Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelSmall,
                    letterSpacing = 0.7.sp,
                    textAlign = TextAlign.Center,
                )
            }
        }
        Column(
            Modifier.align(Alignment.BottomStart).fillMaxWidth()
                .padding(
                    start = if (compact) 16.dp else 22.dp,
                    end = if (!compact && !largeText) 188.dp else if (compact) 16.dp else 22.dp,
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
private fun HomeLoungeHero(modifier: Modifier, contentDescription: String) {
    val bitmap = ImageBitmap.imageResource(R.drawable.home_lounge_hd_r1)
    Canvas(modifier.semantics { this.contentDescription = contentDescription }) {
        drawHomeLoungeHero(bitmap)
    }
}

private fun DrawScope.drawHomeLoungeHero(bitmap: ImageBitmap) {
    val width = size.width.roundToInt()
    val height = size.height.roundToInt()
    if (width <= 0 || height <= 0) return
    val crop = homeHeroSourceCrop(bitmap.width, bitmap.height, width, height)
    drawImage(
        image = bitmap,
        srcOffset = IntOffset(crop.left, crop.top),
        srcSize = IntSize(crop.width, crop.height),
        dstOffset = IntOffset.Zero,
        dstSize = IntSize(width, height),
    )
}

@Composable
private fun HomeStateBanner(message: String, tone: UiTone, onRetry: (() -> Unit)?) {
    val container = when (tone) {
        UiTone.Danger -> MaterialTheme.colorScheme.errorContainer
        UiTone.Warning -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceContainerHigh
    }
    Surface(
        modifier = Modifier.fillMaxWidth().testTag("home-state-banner"),
        color = container,
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
    val rows = listOf(
        Triple("Gross revenue", snapshot?.revenue ?: if (loading) "Loading…" else "Unavailable", Icons.Filled.Analytics),
        Triple("Active sessions", snapshot?.sessions ?: if (loading) "Loading…" else "Unavailable", Icons.Filled.SportsEsports),
        Triple("Orders", snapshot?.orders ?: if (loading) "Loading…" else "Unavailable", Icons.Filled.PointOfSale),
        Triple("Customers", snapshot?.customers ?: "Unavailable", Icons.Filled.People),
    )
    if (wide) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            rows.forEach { (label, value, icon) -> HomeKpi(label, value, icon, Modifier.weight(1f)) }
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            rows.chunked(2).forEach { pair ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    pair.forEach { (label, value, icon) -> HomeKpi(label, value, icon, Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun HomeKpi(label: String, value: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier) {
    Surface(
        modifier = modifier.heightIn(min = 92.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = Radius.shapeMd,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)),
    ) {
        Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(label, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            }
            Text(value, Modifier.fillMaxWidth(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
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
        modifier = modifier.heightIn(min = 84.dp).clickable(role = Role.Button) { navigateTo(shortcut.destination) }
            .testTag("home-shortcut-${shortcut.destination.name}")
            .semantics { role = Role.Button },
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = Radius.shapeMd,
        border = BorderStroke(
            1.dp,
            if (shortcut.destination == Destination.Gaming) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.72f)
            } else {
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
            },
        ),
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
private fun HomeLowerPanels(wide: Boolean, canOpenAnalytics: Boolean, onOpenAnalytics: () -> Unit) {
    val content: @Composable (Boolean) -> Unit = { activity ->
        HomeUnavailablePanel(
            title = if (activity) "Recent activity" else "Sales trend",
            detail = if (activity) {
                "Recent activity isn’t available in this view"
            } else {
                "Open Analytics for period comparisons"
            },
            modifier = Modifier.testTag(if (activity) "home-recent-activity" else "home-sales-trend"),
            equalizeHeight = wide,
            action = if (!activity && canOpenAnalytics) "Open Analytics" else null,
            onAction = if (!activity && canOpenAnalytics) onOpenAnalytics else null,
        )
    }
    if (wide) {
        Row(
            Modifier.fillMaxWidth().height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.weight(1f).fillMaxHeight()) { content(true) }
            Box(Modifier.weight(1f).fillMaxHeight()) { content(false) }
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            content(true)
            content(false)
        }
    }
}

@Composable
private fun HomeUnavailablePanel(
    title: String,
    detail: String,
    modifier: Modifier,
    equalizeHeight: Boolean,
    action: String?,
    onAction: (() -> Unit)?,
) {
    val panelModifier = modifier.fillMaxWidth().heightIn(min = 132.dp).let {
        if (equalizeHeight) it.fillMaxHeight() else it
    }
    Surface(
        panelModifier,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = Radius.shapeMd,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)),
    ) {
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, Modifier.fillMaxWidth(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text(
                "Unavailable",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold,
            )
            Text(detail, Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (action != null && onAction != null) {
                Spacer(Modifier.weight(1f))
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onAction)
                        .testTag("home-open-analytics"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.End,
                ) {
                    Text(action, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}
