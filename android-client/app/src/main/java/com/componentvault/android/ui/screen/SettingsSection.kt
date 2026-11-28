package com.componentvault.android.ui.screen

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.componentvault.android.R

internal enum class SettingsSection {
    Sync,
    ImportAndOcr,
    App,
    Feedback,
    About,
    Appearance,
    Theme,
    Accent,
    ;

    val parent: SettingsSection?
        get() = when (this) {
            Theme, Accent -> Appearance
            else -> null
        }
}

@Composable
internal fun SettingsSection.title(strings: ComponentVaultStrings): String = when (this) {
    SettingsSection.Sync -> strings.settings.connectionTitle
    SettingsSection.ImportAndOcr -> strings.settings.importPreferencesTitle
    SettingsSection.App -> strings.settings.languageTitle
    SettingsSection.Feedback -> stringResource(R.string.feedback_title)
    SettingsSection.About -> stringResource(R.string.appearance_about_updates)
    SettingsSection.Appearance -> stringResource(R.string.appearance_title)
    SettingsSection.Theme -> stringResource(R.string.appearance_theme)
    SettingsSection.Accent -> stringResource(R.string.appearance_accent)
}

@Composable
internal fun SettingsSection.listTitle(strings: ComponentVaultStrings): String = when (this) {
    SettingsSection.Sync -> stringResource(R.string.appearance_sync_title)
    SettingsSection.ImportAndOcr -> stringResource(R.string.appearance_import_title)
    else -> title(strings)
}

@Composable
internal fun SettingsSection.listSupporting(): String = stringResource(
    when (this) {
        SettingsSection.Sync -> R.string.appearance_sync_hint
        SettingsSection.ImportAndOcr -> R.string.appearance_import_hint
        SettingsSection.App -> R.string.appearance_language_hint
        SettingsSection.Feedback -> R.string.appearance_feedback_hint
        SettingsSection.About -> R.string.appearance_about_hint
        SettingsSection.Appearance -> R.string.appearance_description
        SettingsSection.Theme -> R.string.appearance_theme_hint
        SettingsSection.Accent -> R.string.appearance_accent_hint
    },
)
