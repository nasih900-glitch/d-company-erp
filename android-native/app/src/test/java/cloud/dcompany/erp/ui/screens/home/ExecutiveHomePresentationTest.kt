package cloud.dcompany.erp.ui.screens.home

import cloud.dcompany.erp.ui.Destination
import cloud.dcompany.erp.ui.screens.analytics.CachedDataPresentation
import cloud.dcompany.erp.ui.theme.WorkspaceGlassRole
import cloud.dcompany.erp.ui.theme.workspaceGlassRoleStyle
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.TimeZone
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExecutiveHomePresentationTest {
    @Test
    fun `snapshot maps validated Home aggregate with source timezone and company customer scope`() {
        val originalTimeZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
        try {
            val fetchedAtMillis = 1_792_758_600_000
            val snapshot = executiveHomeSnapshot(
                state = HomeUiState(
                    loading = false,
                    aggregate = HomeAggregateSnapshot(
                        schemaVersion = 1,
                        ownerUserId = "user-a",
                        ownerCompanyId = "company-a",
                        branchId = "11111111-1111-1111-1111-111111111111",
                        terminalId = "22222222-2222-2222-2222-222222222222",
                        businessDate = "2026-10-23",
                        timezone = "Asia/Kolkata",
                        snapshotAtMillis = fetchedAtMillis,
                        revenue = HomeAggregateMetric(2_458_000, 0, null, "no_baseline"),
                        orders = HomeAggregateMetric(48, 0, null, "no_baseline"),
                        customerCount = 312,
                        customerScope = "company_live_directory",
                        customerComparisonStatus = "unavailable_no_immutable_history",
                        activeStationCapacity = 9,
                        openSessions = 6,
                        dayLengthMinutes = 1_440,
                        profileBasis = "pos_sales_tip_exclusive_local_hour_profile",
                        profileReconcilesToDailyGross = false,
                        profileRevenueMinor = 0,
                        profileOrdersCount = 0,
                        hourly = (0..23).map { HomeHourPoint(it, 0, 0) },
                    ),
                    fetchedAtMillis = fetchedAtMillis,
                ),
            )!!

            assertEquals("24,580.00", snapshot.revenue)
            assertEquals(null, snapshot.revenueComparison)
            assertEquals("48", snapshot.orders)
            assertEquals(null, snapshot.ordersComparison)
            assertEquals(6L, snapshot.openSessions)
            assertEquals(9L, snapshot.activeStationCapacity)
            assertEquals("312", snapshot.customers)
            assertEquals("23 Oct 2026", snapshot.snapshotDate)
            val expectedTimestamp = Instant.ofEpochMilli(fetchedAtMillis)
                .atZone(ZoneId.of("Asia/Kolkata"))
                .format(DateTimeFormatter.ofPattern("d MMM uuuu, h:mm a 'IST'", Locale.ENGLISH))
            assertEquals(expectedTimestamp, snapshot.sourceTimestamp)
            assertEquals("23 Oct 2026, 6:00 PM IST", snapshot.sourceTimestamp)
        } finally {
            TimeZone.setDefault(originalTimeZone)
        }
    }

    @Test
    fun `shortcuts retain approved order and exclude unauthorized destinations`() {
        val shortcuts = executiveHomeShortcuts(
            listOf(Destination.Dashboard, Destination.Pos, Destination.Inventory, Destination.Analytics),
        )

        assertEquals(listOf(Destination.Pos, Destination.Inventory), shortcuts.map { it.destination })
        assertFalse(shortcuts.any { it.destination == Destination.Analytics })
        assertEquals(
            listOf(null, Destination.Pos, null, null, Destination.Inventory, null),
            homeWideShortcutSlots(shortcuts).map { it?.destination },
        )
        assertTrue(executiveHomeShortcuts(Destination.entries).size == 6)
        assertEquals("Inventory", homeShortcutVisibleLabel(Destination.Inventory))
        assertEquals("Gaming", homeShortcutVisibleLabel(Destination.Gaming))
    }

    @Test
    fun `wide Home geometry remains inside the shared right body`() {
        val geometry = homeWideGeometry(784.dp)

        assertEquals(492.dp, geometry.lowerTop)
        assertEquals(276.dp, geometry.lowerHeight)
        assertTrue(geometry.lowerTop + geometry.lowerHeight + geometry.bottomSafety <= 784.dp)
        assertTrue(geometry.kpiTop < geometry.heroHeight)
        assertTrue(geometry.shortcutTop > geometry.kpiTop + geometry.kpiHeight)
        assertEquals(66.dp, homeHeroContentBottomReserve(geometry.heroHeight, geometry.kpiTop, 1f))
        assertEquals(
            73.2,
            homeHeroContentBottomReserve(geometry.heroHeight, geometry.kpiTop, 1.3f).value.toDouble(),
            0.01,
        )
        assertEquals(440.dp, homeHeroStatusWidth(1_054.dp))
        assertTrue(
            homeHeroStatusWidth(1_054.dp) + 124.dp + 16.dp <= 1_054.dp * 0.70f,
        )

        assertEquals(
            WorkspaceGlassRole.ERROR,
            homeHeroStatusGlassRole(CachedDataPresentation.BLOCKING_ERROR),
        )
        listOf(
            CachedDataPresentation.INITIAL_LOADING,
            CachedDataPresentation.STALE,
            CachedDataPresentation.FRESH,
        ).forEach { presentation ->
            assertEquals(WorkspaceGlassRole.CONTROL, homeHeroStatusGlassRole(presentation))
        }
        val errorStyle = workspaceGlassRoleStyle(WorkspaceGlassRole.ERROR, day = false)
        val errorWorstCase = errorStyle.tint.compositeOver(Color.White)
        val errorContrast = contrastRatio(ExecutiveHomeFinish.ErrorOnDark, errorWorstCase)
        assertTrue(
            "blocking error contrast over the brightest possible semantic surface is $errorContrast",
            errorContrast >= 4.5f,
        )
        val refreshWorstCase = compositeOverWhite(
            ExecutiveHomeFinish.Canvas,
            ExecutiveHomeRefreshContainerAlpha,
        )
        val indicatorContrast = contrastRatio(ExecutiveHomeFinish.Text, refreshWorstCase)
        assertTrue(
            "disabled refresh indicator contrast over the worst hero pixel is $indicatorContrast",
            indicatorContrast >= 3f,
        )
    }

    @Test
    fun `greeting uses first nonblank name and honest time band`() {
        assertEquals("Good morning, Nasih", executiveGreeting("Nasih Mohammed", 8))
        assertEquals("Good afternoon, Nasih", executiveGreeting("Nasih Mohammed", 14))
        assertEquals("Good evening, there", executiveGreeting("   ", 22))
        assertEquals(
            "Good evening, AlexandertheGreatOperationsLead",
            executiveGreeting("AlexandertheGreatOperationsLead Singh", 22),
        )
    }

    @Test
    fun `wide composition fills standard and constrained safe heights without clipping`() {
        val standard = homeWideGeometry(784.dp)
        assertEquals(310.dp, standard.heroHeight)
        assertEquals(260.dp, standard.kpiTop)
        assertEquals(96.dp, standard.kpiHeight)
        assertEquals(372.dp, standard.shortcutTop)
        assertEquals(104.dp, standard.shortcutHeight)
        assertEquals(276.dp, standard.lowerHeight)

        listOf(784.dp, 730.dp).forEach { height ->
            val geometry = homeWideGeometry(height)
            assertTrue(geometry.lowerTop + geometry.lowerHeight + geometry.bottomSafety <= height)
            assertTrue(geometry.kpiTop < geometry.heroHeight)
            assertTrue(geometry.shortcutTop >= geometry.kpiTop + geometry.kpiHeight)
            assertTrue(geometry.lowerTop >= geometry.shortcutTop + geometry.shortcutHeight)
        }
        assertEquals(730.dp, homeWideContentHeight(620.dp, fontScale = 1f))
        assertEquals(249.dp, homeWideGeometry(730.dp).lowerHeight)
        assertEquals(820.dp, homeWideContentHeight(690.dp, fontScale = 1.3f))
        assertEquals(790.dp, homeWideContentHeight(700.dp, fontScale = 1f, noticeHeight = 60.dp))
        assertEquals(312.dp, homeWideGeometry(820.dp, fontScale = 1.3f).lowerHeight)
        assertEquals(FontWeight.SemiBold, ExecutiveHomeType.KpiValue.fontWeight)
    }

    @Test
    fun `metric comparisons and station capacity remain honest at every boundary`() {
        assertEquals(
            "+17.05% vs previous day",
            homeMetricComparison(HomeAggregateMetric(2_458_000, 2_100_000, 17.05, "available")),
        )
        assertEquals(
            "-4.25% vs previous day",
            homeMetricComparison(HomeAggregateMetric(46, 48, -4.25, "available")),
        )
        assertEquals(
            "0.00% vs previous day",
            homeMetricComparison(HomeAggregateMetric(48, 48, 0.0, "available")),
        )
        assertEquals(null, homeMetricComparison(HomeAggregateMetric(0, 0, null, "no_baseline")))

        val normal = homeCapacityPresentation(openSessions = 6, activeStationCapacity = 9)
        assertEquals("6 / 9", normal.value)
        assertEquals("66.7% · active and paused", normal.detail)
        assertEquals(2f / 3f, normal.fillFraction!!, 0.0001f)
        assertTrue(normal.accessibility.contains("active or paused"))

        val zeroCapacity = homeCapacityPresentation(openSessions = 2, activeStationCapacity = 0)
        assertEquals("2 / 0", zeroCapacity.value)
        assertEquals("0 active stations · active and paused", zeroCapacity.detail)
        assertEquals(null, zeroCapacity.fillFraction)
        assertEquals("2 active or paused sessions; 0 active stations", zeroCapacity.accessibility)

        val overCapacity = homeCapacityPresentation(openSessions = 12, activeStationCapacity = 9)
        assertEquals("12 / 9", overCapacity.value)
        assertEquals("133.3% · over capacity · active and paused", overCapacity.detail)
        assertEquals(1f, overCapacity.fillFraction!!, 0f)
        assertEquals(
            "12 active or paused sessions; 9 active station capacity; over capacity",
            overCapacity.accessibility,
        )
    }

    @Test
    fun `activity time uses the aggregate timezone without relative-clock drift`() {
        val occurredAt = Instant.parse("2026-10-23T11:30:00Z")
        assertEquals("5:00 PM", homeActivityVisibleTime(occurredAt, "Asia/Kolkata"))
        assertTrue(homeActivityAccessibleTime(occurredAt, "Asia/Kolkata").startsWith("23 Oct 2026, 5:00 PM"))
    }

    @Test
    fun `analytics action uses body foreground while its gold arrow remains a meaningful icon`() {
        // Independently pinned worst admitted Night PANEL material from the
        // full-grade neutral-glass policy. Text and icon keep distinct roles.
        val brightestAdmittedPanel = Color(0xFF57585A)
        val bodyContrast = contrastRatio(ExecutiveHomeFinish.Text, brightestAdmittedPanel)
        val arrowContrast = contrastRatio(ExecutiveHomeFinish.Gold, brightestAdmittedPanel)
        assertTrue("Analytics action body contrast is $bodyContrast", bodyContrast >= 4.8f)
        assertTrue("Analytics action arrow contrast is $arrowContrast", arrowContrast >= 3.2f)
    }

    @Test
    fun `sales profile maps signed facts around zero without absolute-value overflow or fake bars`() {
        val zero = homeSalesProfilePlot((0..23).map { HomeHourPoint(it, 0L, 0L) })
        assertEquals(0.5f, zero.zeroFraction, 0f)
        assertTrue(zero.yFractions.all { it == 0.5f })

        val signed = (0..23).map { hour ->
            HomeHourPoint(
                hour = hour,
                revenueMinor = when (hour) {
                    0 -> -10_000L
                    12 -> 0L
                    23 -> 20_000L
                    else -> 0L
                },
                ordersCount = 0L,
            )
        }
        val signedPlot = homeSalesProfilePlot(signed)
        assertEquals(2f / 3f, signedPlot.zeroFraction, 0.0001f)
        assertEquals(1f, signedPlot.yFractions.first(), 0f)
        assertEquals(signedPlot.zeroFraction, signedPlot.yFractions[12], 0f)
        assertEquals(0f, signedPlot.yFractions.last(), 0f)
        assertTrue(homeSalesProfileAccessibility(signed).contains("12am -100.00"))
        assertTrue(homeSalesProfileAccessibility(signed).contains("11pm 200.00"))
        assertEquals("Range -100.00 to 200.00", homeSalesProfileRange(signed))

        val extremes = homeSalesProfilePlot(
            (0..23).map { hour ->
                HomeHourPoint(
                    hour,
                    when (hour) {
                        0 -> Long.MIN_VALUE
                        23 -> Long.MAX_VALUE
                        else -> 0L
                    },
                    0L,
                )
            },
        )
        assertTrue(extremes.yFractions.all { it.isFinite() })
        assertTrue(extremes.zeroFraction.isFinite())
        assertEquals("-92,23,37,20,36,85,47,758.08", homeMinorAsRupees(Long.MIN_VALUE))
    }

    private fun compositeOverWhite(color: Color, alpha: Float): Color = Color(
        red = color.red * alpha + (1f - alpha),
        green = color.green * alpha + (1f - alpha),
        blue = color.blue * alpha + (1f - alpha),
        alpha = 1f,
    )

    private fun contrastRatio(first: Color, second: Color): Float {
        val lighter = maxOf(first.luminance(), second.luminance())
        val darker = minOf(first.luminance(), second.luminance())
        return (lighter + 0.05f) / (darker + 0.05f)
    }
}
