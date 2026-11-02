package cloud.dcompany.erp.ui.screens.settings

import android.app.ActivityOptions
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import cloud.dcompany.erp.DCompanyApp
import cloud.dcompany.erp.core.sync.OutboxWorkStatus
import cloud.dcompany.erp.ui.Destination
import cloud.dcompany.erp.ui.WorkspaceScaffold
import cloud.dcompany.erp.ui.components.SyncAvailabilityProblem
import cloud.dcompany.erp.ui.theme.AppearancePreference
import cloud.dcompany.erp.ui.theme.DCompanyTheme
import cloud.dcompany.erp.ui.theme.WorkspaceBackgroundPresets
import kotlin.math.abs
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val SettingsDisplayIdArgument = "dcompany.testDisplayId"

private val RequestedSettingsDisplayId: Int by lazy {
    val raw = InstrumentationRegistry.getArguments().getString(SettingsDisplayIdArgument)
        ?: error("Settings Account scroll proof requires dcompany.testDisplayId")
    raw.toIntOrNull()
        ?.takeIf { it in setOf(0, 2, 5, 6) }
        ?: error("Only verified HD displays 0, 2, 5 and 6 are supported: $raw")
}

private fun ActivityScenarioRule<ComponentActivity>.settingsActivity(): ComponentActivity {
    var activity: ComponentActivity? = null
    scenario.onActivity { activity = it }
    return checkNotNull(activity)
}

private fun createSettingsComposeRule(): AndroidComposeTestRule<
    ActivityScenarioRule<ComponentActivity>,
    ComponentActivity,
> {
    val activityRule = ActivityScenarioRule(
        ComponentActivity::class.java,
        ActivityOptions.makeBasic().setLaunchDisplayId(RequestedSettingsDisplayId).toBundle(),
    )
    return AndroidComposeTestRule(activityRule) { it.settingsActivity() }
}

@RunWith(AndroidJUnit4::class)
class SettingsAccountScrollUiTest {
    @get:Rule
    val compose = createSettingsComposeRule()

    @Test
    fun offlineAccountViewportScrollsFromStaticWorkspaceCopyAndReturns() {
        // Presentation proof owns its shell; a fresh signed-out install must
        // never be expected to expose MainActivity's authenticated workspace.
        check(!DCompanyApp.instance.tokens.hasSession()) {
            "Offline Account proof requires an isolated signed-out test installation"
        }
        val destination = mutableStateOf(Destination.Dashboard)
        compose.activityRule.scenario.onActivity {
            WindowCompat.setDecorFitsSystemWindows(it.window, false)
        }
        compose.setContent {
            DCompanyTheme(AppearancePreference.Night) {
                Surface(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                        WorkspaceScaffold(
                            destinations = listOf(Destination.Dashboard, Destination.Settings),
                            currentDestination = destination.value,
                            employeeName = "Settings presentation test",
                            locationLabel = "Isolated offline fixture",
                            connectivityProblem = SyncAvailabilityProblem.NO_NETWORK,
                            outboxWorkStatus = OutboxWorkStatus(),
                            syncing = false,
                            canChangeTill = false,
                            appearancePreference = AppearancePreference.Night,
                            onOpenSupport = {},
                            onChangeTill = {},
                            onSignOut = {},
                            onDestinationChanged = { destination.value = it },
                        ) { current, _ ->
                            if (current == Destination.Settings) {
                                // The real VM fails closed without credentials;
                                // no tokens, cache records or session are fabricated.
                                SettingsScreen(canManageSystem = false)
                            } else {
                                Text("Settings presentation fixture")
                            }
                        }
                    }
                }
            }
        }
        assertVerified1280By800Host()
        awaitRouteReadiness("test-owned Settings rail") { tagCount("workspace-rail-Settings") == 1 }
        compose.onNodeWithTag("workspace-rail-Settings", useUnmergedTree = true)
            .assertIsDisplayed()
            .performClick()

        awaitRouteReadiness("Settings rail selection") { selectedSettingsRailCount() == 1 }
        awaitRouteReadiness("Account tab") { textCount("Account") == 1 }
        awaitRouteReadiness("requested workspace glass") { tagCount("workspace-glass-ready-workspace") == 1 }
        awaitRouteReadiness("Settings background card") { tagCount("workspace-background-settings") == 1 }
        awaitRouteReadiness("offline Account fallback") {
            textCount("Could not load these settings") == 1 &&
                textCount("Try again") == 1 &&
                textCount("The account currently signed in on this tablet.") == 0
        }

        val owner = accountScrollOwner()
        compose.waitUntil(5_000L) { scrollMax(owner) > 1f }
        val initialOffset = scrollValue(owner)
        val initialGalleryBounds = compose.onNodeWithTag(
            "workspace-background-preset-gallery",
            useUnmergedTree = true,
        ).getUnclippedBoundsInRoot()

        val inertWorkspaceCopy = compose.onNodeWithText(
            "One image across Home and every workspace screen on this tablet.",
            useUnmergedTree = true,
        )
            .assertIsDisplayed()
        val ownerPixels = owner.fetchSemanticsNode().boundsInRoot
        val inertPixels = inertWorkspaceCopy.fetchSemanticsNode().boundsInRoot
        val start = inertPixels.center - ownerPixels.topLeft
        assertTrue(
            "The upward gesture must begin inside inert workspace copy: " +
                "start=$start owner=$ownerPixels inert=$inertPixels",
            start.x in 1f..(ownerPixels.width - 1f) && start.y in 1f..(ownerPixels.height - 1f),
        )
        owner.performTouchInput {
            swipe(
                start = start,
                end = Offset(start.x, 1f),
                durationMillis = 500L,
            )
        }
        compose.waitUntil(5_000L) { scrollValue(owner) > initialOffset + 1f }

        val advancedOffset = scrollValue(owner)
        val ownerBounds = owner.getUnclippedBoundsInRoot()
        val gallery = compose.onNodeWithTag(
            "workspace-background-preset-gallery",
            useUnmergedTree = true,
        ).assertIsDisplayed()
        val advancedGalleryBounds = gallery.getUnclippedBoundsInRoot()
        assertTrue(
            "The real gallery must move upward after the Profile-body swipe: before=$initialGalleryBounds after=$advancedGalleryBounds",
            advancedGalleryBounds.top < initialGalleryBounds.top - 1.dp,
        )
        assertTrue(
            "The real gallery must be fully inside the Account viewport after the swipe: gallery=$advancedGalleryBounds owner=$ownerBounds",
            advancedGalleryBounds.top >= ownerBounds.top - 0.5.dp &&
                advancedGalleryBounds.bottom <= ownerBounds.bottom + 0.5.dp,
        )
        assertTrue(
            "The real preset gallery must expose horizontal scrolling",
            gallery.fetchSemanticsNode()
                .config[SemanticsProperties.HorizontalScrollAxisRange].maxValue() > 1f,
        )
        val usableAction = visibleUnusedPresetAction(ownerBounds, gallery.getUnclippedBoundsInRoot())
        assertNotNull("A non-selected 48dp gallery action must be usable without changing it", usableAction)
        requireNotNull(usableAction)
            .assertIsDisplayed()
            .assertIsEnabled()
            .assertHeightIsAtLeast(48.dp)

        val inertBackgroundCopy = compose.onNodeWithText("Game gallery", useUnmergedTree = true)
            .assertIsDisplayed()
        val backgroundPixels = inertBackgroundCopy.fetchSemanticsNode().boundsInRoot
        val reverseStart = backgroundPixels.center - ownerPixels.topLeft
        owner.performTouchInput {
            swipe(
                start = reverseStart,
                end = Offset(reverseStart.x, height - 1f),
                durationMillis = 500L,
            )
        }
        compose.waitUntil(5_000L) { scrollValue(owner) < advancedOffset - 1f }
        assertTrue(
            "The reverse physical swipe must move Account content back toward its origin: " +
                "initial=$initialOffset advanced=$advancedOffset returned=${scrollValue(owner)}",
            scrollValue(owner) < advancedOffset - 1f,
        )
    }

    private fun accountScrollOwner(): SemanticsNodeInteraction = compose.onNode(
        SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange) and
            hasAnyDescendant(hasTestTag("workspace-background-settings")),
        useUnmergedTree = true,
    )

    private fun awaitRouteReadiness(label: String, predicate: () -> Boolean) {
        try {
            compose.waitUntil(3_000L, predicate)
        } catch (failure: Throwable) {
            val counts = runCatching(::routeReadinessCounts).getOrElse { error ->
                "countsUnavailable=${error::class.java.simpleName}"
            }
            throw AssertionError("$label was not ready; $counts").also {
                it.initCause(failure)
            }
        }
    }

    private fun tagCount(tag: String): Int = compose.onAllNodes(
        hasTestTag(tag),
        useUnmergedTree = true,
    ).fetchSemanticsNodes().size

    private fun textCount(text: String): Int = compose.onAllNodesWithText(
        text,
        useUnmergedTree = true,
    ).fetchSemanticsNodes().size

    private fun selectedSettingsRailCount(): Int = compose.onAllNodes(
        hasTestTag("workspace-rail-Settings") and
            SemanticsMatcher.expectValue(SemanticsProperties.Selected, true),
        useUnmergedTree = true,
    ).fetchSemanticsNodes().size

    private fun routeReadinessCounts(): String = listOf(
        "settingsRail=${tagCount("workspace-rail-Settings")}",
        "settingsSelected=${selectedSettingsRailCount()}",
        "accountTab=${textCount("Account")}",
        "glassReady=${tagCount("workspace-glass-ready-workspace")}",
        "backgroundSettings=${tagCount("workspace-background-settings")}",
        "profileBody=${textCount("The account currently signed in on this tablet.")}",
        "offlineSettings=${textCount("Could not load these settings")}",
        "retry=${textCount("Try again")}",
        "entrySplash=${tagCount("executive-entry-splash")}",
        "login=${tagCount("login-composition")}",
        "terminalSelection=${textCount("Select this tablet's till") + textCount("Change this tablet's till")}",
        "moreOverlay=${tagCount("workspace-more-panel")}",
        "searchOverlay=${tagCount("workspace-module-search")}",
    ).joinToString(prefix = "counts{", postfix = "}")

    private fun scrollValue(owner: SemanticsNodeInteraction): Float =
        owner.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()

    private fun scrollMax(owner: SemanticsNodeInteraction): Float =
        owner.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].maxValue()

    private fun visibleUnusedPresetAction(
        ownerBounds: androidx.compose.ui.unit.DpRect,
        galleryBounds: androidx.compose.ui.unit.DpRect,
    ): SemanticsNodeInteraction? = WorkspaceBackgroundPresets.entries.firstNotNullOfOrNull { preset ->
        val interaction = compose.onNodeWithTag(
            "workspace-background-select-${preset.id}",
            useUnmergedTree = true,
        )
        val nodes = compose.onAllNodes(
            hasTestTag("workspace-background-select-${preset.id}"),
            useUnmergedTree = true,
        ).fetchSemanticsNodes()
        val node = nodes.singleOrNull() ?: return@firstNotNullOfOrNull null
        val config = node.config
        if (
            !config.contains(SemanticsProperties.Selected) ||
            config[SemanticsProperties.Selected] ||
            config.contains(SemanticsProperties.Disabled)
        ) return@firstNotNullOfOrNull null
        val bounds = interaction.getUnclippedBoundsInRoot()
        interaction.takeIf {
            bounds.left >= galleryBounds.left - 0.5.dp &&
                bounds.right <= galleryBounds.right + 0.5.dp &&
                bounds.top >= ownerBounds.top - 0.5.dp &&
                bounds.bottom <= ownerBounds.bottom + 0.5.dp
        }
    }

    private fun assertVerified1280By800Host() {
        val activity = compose.activityRule.settingsActivity()
        assertTrue(
            "The Settings proof must run on requested display $RequestedSettingsDisplayId, actual=${activity.display?.displayId}",
            activity.display?.displayId == RequestedSettingsDisplayId,
        )
        if (RequestedSettingsDisplayId == 0) {
            val display = checkNotNull(activity.display)
            assertTrue(
                "Display 0 Settings proof requires the verified 2560x1600px, 320dpi host",
                display.mode.physicalWidth == 2_560 && display.mode.physicalHeight == 1_600 &&
                    activity.resources.displayMetrics.densityDpi == 320,
            )
        }
        val root = compose.onRoot(useUnmergedTree = true).getUnclippedBoundsInRoot()
        val rootWidth = root.right - root.left
        val rootHeight = root.bottom - root.top
        assertTrue(
            "The Settings proof requires the 1280x800dp host, actual=$root",
            abs(rootWidth.value - 1_280f) <= 1f && abs(rootHeight.value - 800f) <= 1f,
        )
    }
}
