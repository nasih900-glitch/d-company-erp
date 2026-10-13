package cloud.dcompany.erp.ui.theme

import android.content.Context

internal class AppearanceStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "dcompany_appearance",
        Context.MODE_PRIVATE,
    )

    fun load(): AppearancePreference = when (preferences.getString(KEY, null)) {
        "day" -> AppearancePreference.Day
        "night" -> AppearancePreference.Night
        else -> AppearancePreference.System
    }

    fun save(preference: AppearancePreference) {
        val editor = preferences.edit()
        when (preference) {
            AppearancePreference.System -> editor.remove(KEY)
            AppearancePreference.Day -> editor.putString(KEY, "day")
            AppearancePreference.Night -> editor.putString(KEY, "night")
        }
        editor.apply()
    }

    private companion object {
        const val KEY = "preference"
    }
}

internal fun AppearancePreference.next(): AppearancePreference = when (this) {
    AppearancePreference.System -> AppearancePreference.Day
    AppearancePreference.Day -> AppearancePreference.Night
    AppearancePreference.Night -> AppearancePreference.System
}

