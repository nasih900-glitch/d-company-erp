package cloud.dcompany.erp.ui.theme

import android.content.res.Resources
import cloud.dcompany.erp.R
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LaunchAppearanceTest {
    @Test
    fun `manual choices persist separate starting themes and System explicitly resets`() {
        for (sdk in listOf(31, 33, 35)) {
            assertEquals(R.style.Theme_DCompany_Start_Day, splashThemeOverride(AppearancePreference.Day, sdk))
            assertEquals(R.style.Theme_DCompany_Start_Night, splashThemeOverride(AppearancePreference.Night, sdk))
            assertEquals(Resources.ID_NULL, splashThemeOverride(AppearancePreference.System, sdk))
        }
        for (sdk in 26..30) for (preference in AppearancePreference.entries) {
            assertNull("Pre31 must retain the manifest starting-window fallback", splashThemeOverride(preference, sdk))
        }
    }

    @Test
    fun `starting styles preserve neutral palette and original manifest icon`() {
        val styles = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(root().resolve("src/main/res/values-v31/launch_themes.xml").toFile())
            .getElementsByTagName("style")
        assertEquals(2, styles.length)
        for (index in 0 until styles.length) {
            val style = styles.item(index) as org.w3c.dom.Element
            assertEquals("Theme.DCompany", style.getAttribute("parent"))
            val night = style.getAttribute("name").endsWith("Night")
            val items = style.getElementsByTagName("item")
            val values = (0 until items.length).associate {
                val item = items.item(it) as org.w3c.dom.Element
                item.getAttribute("name") to item.textContent.trim()
            }
            for (name in listOf("windowSplashScreenBackground", "windowBackground", "statusBarColor", "navigationBarColor")) {
                assertEquals(if (night) "#050505" else "#FAFAFA", values["android:$name"])
            }
            assertEquals((!night).toString(), values["android:windowLightStatusBar"])
            assertEquals((!night).toString(), values["android:windowLightNavigationBar"])
            assertFalse("Do not replace the original launcher artwork", values.keys.any { "Icon" in it })
        }
    }

    @Test
    fun `startup and selection sync only the application splash override`() {
        val activity = Files.newBufferedReader(root().resolve("src/main/java/cloud/dcompany/erp/MainActivity.kt")).use { it.readText() }
        assertTrue(activity.indexOf("syncLaunchAppearance(initialAppearance)") < activity.indexOf("setContent {"))
        assertTrue("appearanceStore.save(next)\n                                    syncLaunchAppearance(next)" in activity)
        val helper = Files.newBufferedReader(root().resolve("src/main/java/cloud/dcompany/erp/ui/theme/LaunchAppearance.kt")).use { it.readText() }
        assertTrue("splashScreen.setSplashScreenTheme(it)" in helper)
        assertTrue("Build.VERSION.SDK_INT >= Build.VERSION_CODES.S" in helper)
        assertFalse("setApplicationNightMode" in helper)
        assertFalse("setNightMode" in helper)
        assertFalse("recreate()" in helper)
    }

    @Test
    fun `an OEM splash failure cannot prevent the app from continuing`() {
        assertFalse(tryApplySplashTheme(R.style.Theme_DCompany_Start_Night) {
            throw IllegalStateException("Platform starting theme unavailable")
        })
        var applied: Int? = null
        assertTrue(tryApplySplashTheme(Resources.ID_NULL) { applied = it })
        assertEquals(Resources.ID_NULL, applied)
    }

    private fun root(): Path = listOf(Paths.get(""), Paths.get("app"), Paths.get("android-native/app"))
        .first { Files.isRegularFile(it.resolve("src/main/AndroidManifest.xml")) }.toAbsolutePath()
}
