package cloud.dcompany.erp.ui.screens.home

import cloud.dcompany.erp.ui.Destination
import cloud.dcompany.erp.ui.screens.analytics.AnalyticsUiState
import cloud.dcompany.erp.ui.screens.analytics.DashboardKpis
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExecutiveHomePresentationTest {
    @Test
    fun `snapshot maps only supported dashboard facts and labels unavailable customers`() {
        val originalTimeZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
        try {
            val fetchedAtMillis = 1_792_758_600_000
            val snapshot = executiveHomeSnapshot(
                state = AnalyticsUiState(
                    todayLoading = false,
                    dashboard = DashboardKpis(
                        branchId = "branch-a",
                        date = "2026-10-23",
                        revenueTotalMinor = 2_458_000,
                        ordersCount = 48,
                        openSessions = 6,
                    ),
                    dashboardFetchedAtMillis = fetchedAtMillis,
                ),
            )!!

            assertEquals("₹24,580.00", snapshot.revenue)
            assertEquals("48", snapshot.orders)
            assertEquals("6", snapshot.sessions)
            assertEquals("Unavailable", snapshot.customers)
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
        assertTrue(executiveHomeShortcuts(Destination.entries).size == 6)
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
    fun `production lounge asset and responsive cover crops remain exact`() {
        val image = projectRoot().resolve("src/main/res/drawable-nodpi/home_lounge_hd_r1.png")
        val bytes = Files.readAllBytes(image)
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { byte -> "%02x".format(byte) }
        val dimensions = ByteBuffer.wrap(bytes, 16, 8).order(ByteOrder.BIG_ENDIAN)

        assertEquals(HomeLoungeAssetSha256, digest)
        assertEquals(2172, dimensions.int)
        assertEquals(724, dimensions.int)

        val wide = homeHeroSourceCrop(2172, 724, 1100, 138)
        assertEquals(0, wide.left)
        assertEquals(2172, wide.width)
        assertTrue(wide.top <= 400)
        assertTrue(wide.top + wide.height >= 520)

        val compact = homeHeroSourceCrop(2172, 724, 568, 124)
        assertEquals(0, compact.left)
        assertEquals(2172, compact.width)
        assertTrue(compact.top < wide.top)
        assertTrue(compact.top + compact.height <= 724)
    }

    private fun projectRoot(): Path {
        val candidates = listOf(
            Paths.get("src/main/AndroidManifest.xml") to Paths.get(""),
            Paths.get("app/src/main/AndroidManifest.xml") to Paths.get("app"),
            Paths.get("android-native/app/src/main/AndroidManifest.xml") to Paths.get("android-native/app"),
        )
        return candidates.firstOrNull { Files.isRegularFile(it.first) }?.second
            ?.toAbsolutePath()?.normalize()
            ?: error("Could not locate Android app module")
    }
}
