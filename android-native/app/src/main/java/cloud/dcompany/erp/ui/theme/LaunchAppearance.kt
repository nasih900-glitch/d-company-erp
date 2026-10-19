package cloud.dcompany.erp.ui.theme

import android.app.Activity
import android.content.res.Resources
import android.os.Build
import android.util.Log
import cloud.dcompany.erp.R

/** App-only persisted starting surface; Compose remains the appearance authority.
 * API26–30 keep their manifest/System starting window. The already displayed first
 * upgrade frame cannot be recoloured; this setting affects subsequent launches.
 */
internal fun Activity.syncLaunchAppearance(preference: AppearancePreference) {
    val themeId = splashThemeOverride(preference, Build.VERSION.SDK_INT) ?: return
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (!tryApplySplashTheme(themeId) { splashScreen.setSplashScreenTheme(it) }) {
            // Appearance is cosmetic: an OEM starting-window failure must not
            // interrupt login or a saved workspace. No account data is logged.
            Log.w("LaunchAppearance", "App starting theme was not synchronized; manifest fallback remains.")
        }
    }
}

internal fun splashThemeOverride(preference: AppearancePreference, sdkInt: Int): Int? {
    if (sdkInt < Build.VERSION_CODES.S) return null
    return when (preference) {
        AppearancePreference.Day -> R.style.Theme_DCompany_Start_Day
        AppearancePreference.Night -> R.style.Theme_DCompany_Start_Night
        // The public API's explicit reset preserves real OS-following semantics.
        AppearancePreference.System -> Resources.ID_NULL
    }
}

internal fun tryApplySplashTheme(themeId: Int, apply: (Int) -> Unit): Boolean = try {
    apply(themeId)
    true
} catch (_: RuntimeException) {
    false
}
