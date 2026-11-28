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
}

@Composable
internal fun SettingsSection.title(strings: ComponentVaultStrings): String = when (this) {
    SettingsSection.Sync -> strings.settings.connectionTitle
    SettingsSection.ImportAndOcr -> strings.settings.importPreferencesTitle
    SettingsSection.App -> strings.settings.languageTitle
    SettingsSection.Feedback -> stringResource(R.string.feedback_title)
    SettingsSection.About -> stringResource(R.string.appearance_about_updates)
    SettingsSection.Appearance -> stringResource(R.string.appearance_title)
}

@Composable
internal fun SettingsSection.supporting(strings: ComponentVaultStrings): String = when (this) {
    SettingsSection.Sync -> strings.settings.connectionSubtitle
    SettingsSection.ImportAndOcr -> strings.settings.importPreferencesSubtitle
    SettingsSection.App -> strings.settings.languageSubtitle
    SettingsSection.Feedback -> stringResource(R.string.feedback_section_supporting)
    SettingsSection.About -> strings.settings.aboutSubtitle
    SettingsSection.Appearance -> stringResource(R.string.appearance_description)
}
