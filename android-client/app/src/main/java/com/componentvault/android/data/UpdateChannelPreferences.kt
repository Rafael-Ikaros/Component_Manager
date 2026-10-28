package com.componentvault.android.data

import android.content.Context

internal object UpdateChannelPreferences {
    private const val preferenceName = "component_vault_update"

    fun read(context: Context): UpdateChannel =
        if (context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
                .getString("channel", "stable") == "dev"
        ) UpdateChannel.Dev else UpdateChannel.Stable

    fun write(context: Context, channel: UpdateChannel) {
        context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE).edit()
            .putString("channel", if (channel == UpdateChannel.Dev) "dev" else "stable")
            .apply()
    }
}
