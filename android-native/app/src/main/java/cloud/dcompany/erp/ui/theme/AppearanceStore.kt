package cloud.dcompany.erp.ui.theme

import android.content.Context

internal class AppearanceStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "dcompany_appearance",
        Context.MODE_PRIVATE,
    )

    fun load(): AppearancePreference = appearancePreferenceFromPersistedValue(
        preferences.getString(KEY, null),
    )

    fun save(preference: AppearancePreference) {
        preferences.edit().putString(KEY, preference.persistedValue()).apply()
    }

    private companion object {
        const val KEY = "preference"
    }
}

internal fun AppearancePreference.persistedValue(): String = when (this) {
    AppearancePreference.System -> "system"
    AppearancePreference.Day -> "day"
    AppearancePreference.Night -> "night"
}

internal fun appearancePreferenceFromPersistedValue(value: String?): AppearancePreference = when (value) {
    "system" -> AppearancePreference.System
    "day" -> AppearancePreference.Day
    "night" -> AppearancePreference.Night
    // Older builds represented both a fresh install and an explicit System choice by deleting
    // this key, so those historical states cannot be distinguished. The approved executive
    // default is Night; future explicit System choices are persisted explicitly.
    else -> AppearancePreference.Night
}

internal fun AppearancePreference.next(): AppearancePreference = when (this) {
    AppearancePreference.System -> AppearancePreference.Day
    AppearancePreference.Day -> AppearancePreference.Night
    AppearancePreference.Night -> AppearancePreference.System
}

