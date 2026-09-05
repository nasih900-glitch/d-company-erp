package cloud.dcompany.erp.ui.screens.gaming

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guards the dispatcher and indexing contract of the Room-backed Gaming projection. */
class GamingProjectionPerformanceContractTest {

    @Test
    fun `station action card observes only structural status while body owns the clock`() {
        val source = Files.newBufferedReader(
            projectRoot().resolve("src/main/java/cloud/dcompany/erp/ui/screens/gaming/GamingScreen.kt"),
        ).use { it.readText() }
        val card = source.between("internal fun GamingStationCard(", "internal fun OrphanPackageExtensionBanner(")
        val body = source.between("private fun StationBody(", "internal fun stationPresentation(")

        assertTrue("Outer status must retain structural equality", "derivedStateOf(structuralEqualityPolicy())" in card)
        assertFalse("Do not read each tick into the entire action card", "val nowMillis =" in card)
        assertTrue("Body receives the State, not a read timestamp", "wallClock = wallClock" in card)
        assertTrue("Visible timers must still observe the clock", "val nowMillis = if (shouldTick) wallClock.value else frozenMillis" in body)
        assertTrue("Timer text must continue rendering actual elapsed time", "value = formatElapsed(elapsed)" in body)
        assertTrue("Static date parsing must be keyed to source fields", "remember(session?.startAt, session?.timerMinutes)" in body)
    }

    @Test
    fun `Gaming projection mapping is dispatched away from Main`() {
        val projection = readGamingViewModel().between(
            "val state: StateFlow<GamingUiState>",
            "\n\n    init {",
        )

        val mapping = projection.indexOf("GamingUiState(")
        val defaultDispatcher = projection.indexOf(".flowOn(Dispatchers.Default)")
        val sharedState = projection.indexOf(".stateIn(viewModelScope")

        assertTrue("The Gaming UI mapping must remain in the projection", mapping >= 0)
        assertTrue(
            "The Gaming UI projection must cross a Default dispatcher boundary after mapping",
            defaultDispatcher > mapping,
        )
        assertTrue(
            "The dispatcher boundary must be applied before Main-owned StateFlow sharing",
            sharedState > defaultDispatcher,
        )
    }

    @Test
    fun `Gaming addon categories are indexed once per projection`() {
        val projection = readGamingViewModel().between(
            "val state: StateFlow<GamingUiState>",
            "\n\n    init {",
        )

        assertTrue(
            "Category names must be indexed before filtering Gaming add-ons",
            "val categoryNameById = references.categories.associate" in projection,
        )
        assertTrue(
            "The add-on policy must use the category index",
            "categoryName = categoryNameById[item.categoryId]" in projection,
        )
        assertFalse(
            "Do not scan every category again for every menu item",
            Regex("references\\.categories\\s*\\.firstOrNull\\s*\\{")
                .containsMatchIn(projection),
        )
    }

    private fun readGamingViewModel(): String = Files.newBufferedReader(
        projectRoot().resolve("src/main/java/cloud/dcompany/erp/ui/screens/gaming/GamingViewModel.kt"),
    ).use { it.readText() }

    private fun String.between(startMarker: String, endMarker: String): String {
        val start = indexOf(startMarker)
        require(start >= 0) { "Missing source marker: $startMarker" }
        val end = indexOf(endMarker, start + startMarker.length)
        require(end >= 0) { "Missing source marker: $endMarker" }
        return substring(start, end)
    }

    private fun projectRoot(): Path {
        val candidates = listOf(
            Paths.get("src/main/AndroidManifest.xml") to Paths.get(""),
            Paths.get("app/src/main/AndroidManifest.xml") to Paths.get("app"),
            Paths.get("android-native/app/src/main/AndroidManifest.xml") to
                Paths.get("android-native/app"),
        )
        return candidates.firstOrNull { Files.isRegularFile(it.first) }?.second
            ?.toAbsolutePath()?.normalize()
            ?: error("Could not locate the Android app module from ${Paths.get("").toAbsolutePath()}")
    }
}
