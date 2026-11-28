package com.componentvault.android.data

import android.content.Context
import android.content.SharedPreferences

enum class AppearanceMode { System, Light, Dark }

enum class AppearanceAccent { Teal, Blue, Sand }

data class AppearancePreferences(
    val mode: AppearanceMode = AppearanceMode.System,
    val accent: AppearanceAccent = AppearanceAccent.Teal,
)

/** Local appearance only; never shares an editor with connection or import settings. */
class AppearancePreferenceStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "component_vault_appearance",
        Context.MODE_PRIVATE,
    )

    fun read(): AppearancePreferences {
        val mode = readString("mode")
        val accent = readString("accent")
        return AppearancePreferences(
            mode = AppearanceMode.entries.firstOrNull { it.name == mode } ?: AppearanceMode.System,
            accent = AppearanceAccent.entries.firstOrNull { it.name == accent } ?: AppearanceAccent.Teal,
        )
    }

    fun save(value: AppearancePreferences) {
        preferences.edit()
            .putString("mode", value.mode.name)
            .putString("accent", value.accent.name)
            .apply()
    }

    /** Keep the registration alive until its Activity composition is disposed. */
    fun observe(onChange: (AppearancePreferences) -> Unit): AutoCloseable {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == "mode" || key == "accent") onChange(read())
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        onChange(read())
        return AutoCloseable { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    private fun readString(key: String): String? = try {
        preferences.getString(key, null)
    } catch (_: ClassCastException) {
        null
    }
}
