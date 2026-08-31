package org.archuser.onmyway.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class AppSettings(
    val materialYouEnabled: Boolean = false,
    val darkThemeEnabled: Boolean = false,
    val customSoundEnabled: Boolean = false,
    val customSoundUri: String? = null,
)

class AppSettingsStore(context: Context) {
    private val preferences = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
    private val mutableSettings = MutableStateFlow(read())
    val settings: StateFlow<AppSettings> = mutableSettings

    fun update(transform: (AppSettings) -> AppSettings) = replace(transform(mutableSettings.value))

    fun replace(value: AppSettings) {
        preferences.edit {
            putBoolean(MATERIAL_YOU, value.materialYouEnabled)
            putBoolean(DARK_THEME, value.darkThemeEnabled)
            putBoolean(CUSTOM_SOUND_ENABLED, value.customSoundEnabled)
            putString(CUSTOM_SOUND_URI, value.customSoundUri)
        }
        mutableSettings.value = value
    }

    private fun read() = AppSettings(
        materialYouEnabled = preferences.getBoolean(MATERIAL_YOU, false),
        darkThemeEnabled = preferences.getBoolean(DARK_THEME, false),
        customSoundEnabled = preferences.getBoolean(CUSTOM_SOUND_ENABLED, false),
        customSoundUri = preferences.getString(CUSTOM_SOUND_URI, null),
    )

    private companion object {
        const val MATERIAL_YOU = "material_you_enabled"
        const val DARK_THEME = "dark_theme_enabled"
        const val CUSTOM_SOUND_ENABLED = "custom_notification_sound_enabled"
        const val CUSTOM_SOUND_URI = "custom_notification_sound_uri"
    }
}
