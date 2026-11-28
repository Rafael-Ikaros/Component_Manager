package com.componentvault.android.ui.screen

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable

@Composable
internal fun SettingsRouteScaffold(
    title: String,
    onBack: () -> Unit,
    caption: String? = null,
    navigationEnabled: Boolean = true,
    content: @Composable (PaddingValues) -> Unit,
) {
    val back = { if (navigationEnabled) onBack() }
    BackHandler(onBack = back)
    Scaffold(
        topBar = { VaultScreenHeader(title = title, caption = caption, onBack = back) },
        containerColor = MaterialTheme.colorScheme.background,
        content = content,
    )
}
