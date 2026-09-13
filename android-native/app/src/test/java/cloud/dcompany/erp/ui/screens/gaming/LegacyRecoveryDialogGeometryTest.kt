package cloud.dcompany.erp.ui.screens.gaming

import androidx.compose.ui.unit.IntRect
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyRecoveryDialogGeometryTest {

    @Test
    fun `same root size recomputes bounds after IME frame and window origin move`() {
        val rootWidth = 2_560
        val rootHeight = 1_600
        val beforeIme = legacyRecoveryVisibleBounds(
            visibleFrame = IntRect(0, 48, 2_560, 1_504),
            rootOriginX = 0,
            rootOriginY = 0,
            rootWidth = rootWidth,
            rootHeight = rootHeight,
        )
        val afterImeAndRelocation = legacyRecoveryVisibleBounds(
            visibleFrame = IntRect(0, 48, 2_560, 684),
            rootOriginX = 0,
            rootOriginY = 112,
            rootWidth = rootWidth,
            rootHeight = rootHeight,
        )

        assertEquals(IntRect(0, 48, 2_560, 1_504), beforeIme)
        assertEquals(IntRect(0, 0, 2_560, 572), afterImeAndRelocation)
        assertNotEquals(beforeIme, afterImeAndRelocation)
    }

    @Test
    fun `pre draw refresh never blocks IME traversal while tracking late geometry`() {
        val state = LegacyRecoveryBoundsState()
        val beforeIme = IntRect(0, 48, 2_560, 1_504)
        val afterImeAndRelocation = IntRect(0, 0, 2_560, 572)

        assertTrue(state.refreshBeforeDraw(null))
        assertNull(state.visibleBounds)
        assertTrue(state.refreshBeforeDraw(beforeIme))
        assertEquals(beforeIme, state.visibleBounds)
        assertTrue(state.refreshBeforeDraw(afterImeAndRelocation))
        assertEquals(afterImeAndRelocation, state.visibleBounds)
        assertTrue("Returning geometry must still allow the frame", run {
            state.refreshBeforeDraw(beforeIme)
        })
        assertEquals(beforeIme, state.visibleBounds)
    }

    @Test
    fun `non intersecting visible frame is rejected`() {
        assertNull(
            legacyRecoveryVisibleBounds(
                visibleFrame = IntRect(0, 1_700, 2_560, 1_900),
                rootOriginX = 0,
                rootOriginY = 0,
                rootWidth = 2_560,
                rootHeight = 1_600,
            ),
        )
    }

    @Test
    fun `frame refresh samples final window geometry immediately before draw`() {
        val source = read(
            projectRoot().resolve(
                "src/main/java/cloud/dcompany/erp/ui/screens/gaming/GamingScreen.kt",
            ),
        )
        val frame = source.substringAfter("private fun LegacyRecoveryDialogFrame")
            .substringBefore("internal fun LegacyPackageResolutionDialog")

        assertTrue("Every draw must resample the current screen frame and root origin", run {
            "OnPreDrawListener" in frame &&
                "boundsState.refreshBeforeDraw(sampleVisibleBounds())" in frame &&
                "view.getWindowVisibleDisplayFrame(frame)" in frame &&
                "view.getLocationOnScreen(origin)" in frame
        })
        assertTrue("The draw listener must be removed with the dialog view", run {
            "view.viewTreeObserver.addOnPreDrawListener(listener)" in frame &&
                "observer.removeOnPreDrawListener(listener)" in frame
        })
        assertFalse("Geometry refresh must never start a rejected-draw layout loop", run {
            "view.requestLayout()" in frame ||
                ".onGloballyPositioned" in frame ||
                "acknowledgeApplied" in frame
        })
        assertFalse("A stale global-layout cache must not remain authoritative", run {
            "OnGlobalLayoutListener" in frame
        })
        assertFalse("The floating-window offset correction must not become generic imePadding", run {
            ".imePadding()" in frame
        })
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
            ?: error("Could not locate Android app module from ${Paths.get("").toAbsolutePath()}")
    }

    private fun read(path: Path): String = Files.newBufferedReader(path).use { it.readText() }
}
