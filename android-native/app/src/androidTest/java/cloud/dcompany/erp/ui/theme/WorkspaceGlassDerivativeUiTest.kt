package cloud.dcompany.erp.ui.theme

import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.math.abs
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WorkspaceGlassDerivativeUiTest {
    @Test
    fun opaqueDerivativeKeepsRawPixelsAndPregradesAgainstTheOriginalColorOracle() = runBlocking<Unit> {
        val color = 0xFF96C8F0.toInt()
        val source = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        var derivative: WorkspaceGlassDerivative? = null
        try {
            derivative = softenWorkspaceBackground(source, 64, 64, key(64, 64))
            assertEquals("The ungraded derivative must retain its original pixel value", color, derivative.bitmap.getPixel(32, 32))
            assertFalse("The clear frame remains leased by its renderer", source.isRecycled)
            val graded = derivative.gradedBitmap
            assertNotNull(graded)
            for (y in listOf(0, 16, 28, 29, 48, 63)) {
                val expected = workspaceGlassCompleteSceneColor(
                    Color(color), WorkspaceGlassRole.PANEL, (y + .5f) / 64f, applySceneGrade = true,
                ).toArgb()
                val actual = checkNotNull(graded).getPixel(32, y)
                for (shift in listOf(0, 8, 16)) {
                    assertTrue(
                        "Pregrade must preserve the original two-scrim color at y=$y channel=$shift",
                        abs((actual ushr shift and 255) - (expected ushr shift and 255)) <= 2,
                    )
                }
            }
        } finally {
            derivative?.recycleUnpublished()
            source.recycle()
        }
    }

    @Test
    fun transparentBlurKeepsTheLegacyRawPathEvenThoughItsSafetyGridIsOpaque() = runBlocking<Unit> {
        val color = 0x8096C8F0.toInt()
        val source = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        val legacy = Bitmap.createBitmap(IntArray(32 * 32) { source.getPixel(0, 0) }, 32, 32, Bitmap.Config.ARGB_8888)
            .apply { setHasAlpha(false) }
        var derivative: WorkspaceGlassDerivative? = null
        try {
            derivative = softenWorkspaceBackground(source, 32, 32, key(32, 32))
            assertTrue(derivative.safetyGrid.isOpaque)
            assertNull("Only actual opaque blur pixels admit pregrading", derivative.gradedBitmap)
            assertEquals(legacy.getPixel(16, 16), derivative.bitmap.getPixel(16, 16))
            assertFalse(source.isRecycled)
        } finally {
            derivative?.recycleUnpublished()
            legacy.recycle()
            source.recycle()
        }
    }

    @Test
    fun unpublishedDerivativeReleasesBothOwnedBitmapsWithoutTouchingItsClearFrame() = runBlocking<Unit> {
        val source = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF123456.toInt()) }
        val derivative = softenWorkspaceBackground(source, 8, 8, key(8, 8))
        try {
            derivative.recycleUnpublished()
            assertTrue(derivative.bitmap.isRecycled)
            assertTrue(checkNotNull(derivative.gradedBitmap).isRecycled)
            assertFalse(source.isRecycled)
        } finally {
            source.recycle()
        }
    }

    private fun key(width: Int, height: Int): WorkspaceGlassDerivativeKey = WorkspaceGlassDerivativeKey(
        actualKey = WorkspaceBackgroundRenderKey(WorkspaceBackgroundSlot.WORKSPACE, WorkspaceBackgroundSource.BUNDLED, 1, "pregrade-fixture", width, height, 0),
        frameEpoch = 1,
        algorithmVersion = WorkspaceGlassAlgorithmVersion,
        width = width,
        height = height,
        blurRadius = WorkspaceGlassBlurRadius,
    )
}
