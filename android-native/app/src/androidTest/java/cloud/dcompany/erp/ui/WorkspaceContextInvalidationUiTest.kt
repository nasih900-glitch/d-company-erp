package cloud.dcompany.erp.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import cloud.dcompany.erp.ui.screens.home.ExecutiveHomeShellLayout
import cloud.dcompany.erp.ui.screens.home.LocalExecutiveHomeShellLayout
import cloud.dcompany.erp.ui.theme.DisplayedSceneFrame
import cloud.dcompany.erp.ui.theme.DisplayedSceneState
import cloud.dcompany.erp.ui.theme.LocalWorkspaceGlassScene
import cloud.dcompany.erp.ui.theme.WorkspaceBackgroundRenderKey
import cloud.dcompany.erp.ui.theme.WorkspaceBackgroundSlot
import cloud.dcompany.erp.ui.theme.WorkspaceBackgroundSource
import cloud.dcompany.erp.ui.theme.WorkspaceGlassScene
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class WorkspaceContextInvalidationUiTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun insetChangesUpdateHomeReaderWithoutRecomposingUnrelatedContent() {
        val layout = mutableStateOf(ExecutiveHomeShellLayout(enabled = true, utilityHeight = 56.dp))
        val unrelatedCompositions = AtomicInteger()
        compose.setContent {
            val children: @Composable () -> Unit = remember {
                {
                    SideEffect { unrelatedCompositions.incrementAndGet() }
                    HomeInsetsReader()
                }
            }
            CompositionLocalProvider(LocalExecutiveHomeShellLayout provides layout.value, content = children)
        }
        compose.onNodeWithText("Home inset 56.0").assertIsDisplayed()
        val initial = unrelatedCompositions.get()

        compose.runOnIdle {
            layout.value = layout.value.copy(noticeHeight = 48.dp)
        }

        compose.onNodeWithText("Home inset 104.0").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals("A notice change must only invalidate inset readers", initial, unrelatedCompositions.get())
        }
    }

    @Test
    fun sceneChangesUpdateMaterialReaderWithoutRecomposingUnrelatedContent() {
        val scene = mutableStateOf<WorkspaceGlassScene?>(null)
        val coordinates = AtomicReference<LayoutCoordinates?>()
        val unrelatedCompositions = AtomicInteger()
        val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        try {
            compose.setContent {
                val children: @Composable () -> Unit = remember {
                    {
                        SideEffect { unrelatedCompositions.incrementAndGet() }
                        Box(Modifier.fillMaxSize().onGloballyPositioned(coordinates::set)) {
                            MaterialFrameReader()
                        }
                    }
                }
                CompositionLocalProvider(LocalWorkspaceGlassScene provides scene.value, content = children)
            }
            compose.onNodeWithText("Material frame unavailable").assertIsDisplayed()
            val initial = unrelatedCompositions.get()
            val key = WorkspaceBackgroundRenderKey(
                slot = WorkspaceBackgroundSlot.WORKSPACE,
                source = WorkspaceBackgroundSource.BUNDLED,
                generation = 1,
                contentId = "context-invalidation-fixture",
                viewportWidth = 1,
                viewportHeight = 1,
                decoderIdentity = 1,
            )
            compose.runOnIdle {
                scene.value = WorkspaceGlassScene(
                    frame = DisplayedSceneFrame(key, key, DisplayedSceneState.REQUESTED_READY, bitmap, 1),
                    derivative = null,
                    rootCoordinates = checkNotNull(coordinates.get()),
                    rootBoundsInRoot = Rect(0f, 0f, 1f, 1f),
                    layoutEpoch = 1,
                )
            }
            compose.onNodeWithText("Material frame 1").assertIsDisplayed()
            compose.runOnIdle {
                val displayed = checkNotNull(scene.value)
                scene.value = displayed.copy(frame = displayed.frame.copy(epoch = 2), layoutEpoch = 2)
            }
            compose.onNodeWithText("Material frame 2").assertIsDisplayed()
            compose.runOnIdle {
                assertEquals("A frame change must only invalidate material readers", initial, unrelatedCompositions.get())
            }
        } finally {
            bitmap.recycle()
        }
    }
}

@Composable
private fun HomeInsetsReader() {
    val layout = LocalExecutiveHomeShellLayout.current
    Text("Home inset ${(layout.utilityHeight + layout.noticeHeight).value}")
}

@Composable
private fun MaterialFrameReader() {
    val epoch = LocalWorkspaceGlassScene.current?.frame?.epoch
    Text("Material frame ${epoch ?: "unavailable"}")
}
