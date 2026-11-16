package com.componentvault.android.data

import android.content.Context

internal object UpdatePreferences {
    private const val preferenceName = "component_vault_update"
    private const val startupPromptKey = "show_startup_update_prompt"

    fun readChannel(context: Context): UpdateChannel =
        if (preferences(context).getString("channel", "stable") == "dev"
        ) UpdateChannel.Dev else UpdateChannel.Stable

    fun writeChannel(context: Context, channel: UpdateChannel) {
        preferences(context).edit()
            .putString("channel", if (channel == UpdateChannel.Dev) "dev" else "stable")
            .apply()
    }

    fun showStartupPrompt(context: Context): Boolean =
        preferences(context).getBoolean(startupPromptKey, true)

    fun setShowStartupPrompt(context: Context, enabled: Boolean) {
        preferences(context).edit().putBoolean(startupPromptKey, enabled).apply()
    }

    private fun preferences(context: Context) =
        context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
}
