package com.componentvault.android.ui.screen

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.componentvault.android.R
import com.componentvault.android.data.AppearanceAccent
import com.componentvault.android.data.AppearanceMode
import com.componentvault.android.data.AppearancePreferences
import com.componentvault.android.data.UpdateChannel
import com.componentvault.android.data.UpdatePreferences
import com.componentvault.android.model.AppLanguage
import com.componentvault.android.model.AppPreferences
import com.componentvault.android.model.ImportLearningSummary
import com.componentvault.android.model.SyncConfiguration
import com.componentvault.android.ui.screen.preview.CompactPreviewLayout
import com.componentvault.android.ui.screen.preview.ExpandedPreviewLayout
import com.componentvault.android.ui.theme.LocalAppearance
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class SettingsDraftUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun groupListShowsLoadedLocalCountAndCurrentSettings() {
        val context: Application = RuntimeEnvironment.getApplication()
        val count = mutableStateOf<Int?>(null)
        val configuration = mutableStateOf(SyncConfiguration("device", "", "", false, "", ""))
        val preferences = mutableStateOf(AppPreferences(appLanguage = AppLanguage.English))
        val appearance = mutableStateOf(AppearancePreferences(AppearanceMode.System, AppearanceAccent.Teal))
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalAppearance provides appearance.value) {
                    ProvideComponentVaultStrings(runtimeComponentVaultStrings()) {
                        SettingsContent(
                            contentPadding = PaddingValues(),
                            syncConfiguration = configuration.value,
                            appPreferences = preferences.value,
                            importLearningSummary = ImportLearningSummary(),
                            isBusy = false, statusMessage = "", layoutMode = CompactPreviewLayout,
                            selectedSection = null, onSelectSection = {},
                            onSaveSyncSettings = { _, _, _, _ -> }, onSaveAppPreferences = {},
                            onTestConnection = { _, _, _ -> }, onSyncNow = {},
                            onClearImportLearningMappings = {},
                            localComponentCount = count.value,
                        )
                    }
                }
            }
        }
        compose.onNodeWithText(context.getString(R.string.appearance_local_loading)).assertIsDisplayed()
        compose.runOnIdle { count.value = 0 }
        compose.onNodeWithText(context.getString(R.string.appearance_local_components, 0)).assertIsDisplayed()
        compose.runOnIdle { count.value = 37 }
        compose.onNodeWithText(context.getString(R.string.appearance_local_components, 37)).assertIsDisplayed()
        val groups = compose.onNodeWithTag("settings_group_list")
        groups.performScrollToNode(hasTestTag("settings_group_Appearance"))
        compose.onNodeWithTag("settings_group_Appearance")
            .assertHeightIsAtLeast(77.dp)
            .assert(hasText(context.getString(
                R.string.appearance_summary,
                context.getString(R.string.appearance_system),
                context.getString(R.string.appearance_teal),
            )))
        groups.performScrollToNode(hasTestTag("settings_group_App"))
        compose.onNodeWithTag("settings_group_App").assert(hasText("English"))
        groups.performScrollToNode(hasTestTag("settings_group_Sync"))
        compose.onNodeWithTag("settings_group_Sync")
            .assert(hasText(context.getString(R.string.appearance_sync_not_configured)))
        compose.runOnIdle {
            configuration.value = configuration.value.copy(serverBaseUrl = "https://saved.example", apiToken = "saved-token")
            preferences.value = preferences.value.copy(appLanguage = AppLanguage.ZhCn)
            appearance.value = AppearancePreferences(AppearanceMode.Dark, AppearanceAccent.Blue)
        }
        compose.onNodeWithTag("settings_group_Sync")
            .assert(hasText(context.getString(R.string.appearance_sync_configured)))
        groups.performScrollToNode(hasTestTag("settings_group_App"))
        compose.onNodeWithTag("settings_group_App")
            .assert(hasText(context.getString(R.string.appearance_language_chinese)))
        groups.performScrollToNode(hasTestTag("settings_group_Appearance"))
        compose.onNodeWithTag("settings_group_Appearance").assert(hasText(context.getString(
            R.string.appearance_summary,
            context.getString(R.string.appearance_dark),
            context.getString(R.string.appearance_blue),
        )))
    }

    @Test
    fun startupUpdateSwitchPersistsAndManualCheckStaysAvailable() {
        val context: Application = RuntimeEnvironment.getApplication()
        UpdatePreferences.writeChannel(context, UpdateChannel.Dev)
        val section = mutableStateOf<SettingsSection?>(SettingsSection.About)
        compose.setContent {
            MaterialTheme {
                ProvideComponentVaultStrings(runtimeComponentVaultStrings()) {
                    SettingsContent(
                        contentPadding = PaddingValues(),
                        syncConfiguration = SyncConfiguration("device", "", "", false, "", ""),
                        appPreferences = AppPreferences(), importLearningSummary = ImportLearningSummary(),
                        isBusy = false, statusMessage = "", layoutMode = CompactPreviewLayout,
                        selectedSection = section.value, onSelectSection = { section.value = it },
                        onSaveSyncSettings = { _, _, _, _ -> }, onSaveAppPreferences = {},
                        onTestConnection = { _, _, _ -> }, onSyncNow = {},
                        onClearImportLearningMappings = {},
                    )
                }
            }
        }
        val settingsList = compose.onNodeWithTag("settings_detail_list")
        settingsList.performScrollToNode(isToggleable())
        compose.onNode(isToggleable()).assertIsOn().performClick().assertIsOff()
        compose.runOnIdle {
            assertFalse(UpdatePreferences.showStartupPrompt(context))
            assertEquals(UpdateChannel.Dev, UpdatePreferences.readChannel(context))
            section.value = null
        }
        compose.waitForIdle()
        compose.onNodeWithTag("settings_group_list")
            .performScrollToNode(hasTestTag("settings_group_About"))
        compose.onNodeWithTag("settings_group_About")
            .assert(hasText(context.getString(R.string.appearance_channel_dev)))
        compose.runOnIdle { section.value = SettingsSection.About }
        settingsList.performScrollToNode(isToggleable())
        compose.onNode(isToggleable()).assertIsOff()
        val checkLabel = context.getString(R.string.settings_update_check_action)
        settingsList.performScrollToNode(hasText(checkLabel))
        compose.onNodeWithText(checkLabel).assertIsEnabled()
        val stableLabel = context.getString(R.string.settings_update_channel_stable)
        settingsList.performScrollToNode(hasText(stableLabel))
        compose.onNodeWithText(stableLabel).performClick()
        compose.runOnIdle {
            assertEquals(UpdateChannel.Stable, UpdatePreferences.readChannel(context))
            section.value = null
        }
        compose.onNodeWithTag("settings_group_list")
            .performScrollToNode(hasTestTag("settings_group_About"))
        compose.onNodeWithTag("settings_group_About")
            .assert(hasText(context.getString(R.string.appearance_channel_stable)))
    }

    @Test
    @Config(qualifiers = "w1000dp-h600dp")
    fun limitedHeightSplitPaneCanReachAndSaveSettings() {
        val context: Application = RuntimeEnvironment.getApplication()
        var savedUrl: String? = null
        compose.setContent {
            MaterialTheme {
                ProvideComponentVaultStrings(runtimeComponentVaultStrings()) {
                    Box(Modifier.requiredSize(840.dp, 280.dp)) {
                        SettingsContent(
                            contentPadding = PaddingValues(),
                            syncConfiguration = SyncConfiguration("device", "https://saved.example", "saved-token", false, "", ""),
                            appPreferences = AppPreferences(), importLearningSummary = ImportLearningSummary(),
                            isBusy = false, statusMessage = "", layoutMode = ExpandedPreviewLayout,
                            selectedSection = SettingsSection.Sync, onSelectSection = {},
                            onSaveSyncSettings = { url, _, _, _ -> savedUrl = url }, onSaveAppPreferences = {},
                            onTestConnection = { _, _, _ -> }, onSyncNow = {},
                            onClearImportLearningMappings = {},
                        )
                    }
                }
            }
        }
        compose.onNodeWithText(context.getString(R.string.field_server_url))
            .performTextReplacement("https://draft.example")
        val saveLabel = context.getString(R.string.action_save_settings)
        compose.onNodeWithTag("settings_detail_list").performScrollToNode(hasText(saveLabel))
        compose.onNodeWithText(saveLabel).assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals("https://draft.example", savedUrl) }
        val syncLabel = context.getString(R.string.action_sync_now)
        compose.onNodeWithTag("settings_detail_list").performScrollToNode(hasText(syncLabel))
        compose.onNodeWithText(syncLabel).assertIsDisplayed()
    }

    @Test
    fun testingEditedUrlDoesNotSaveAndSyncRequiresExplicitSave() {
        val context: Application = RuntimeEnvironment.getApplication()
        var saves = 0
        var tested: Pair<String, String>? = null
        var testedExternal = ""
        compose.setContent {
            MaterialTheme {
                ProvideComponentVaultStrings(runtimeComponentVaultStrings()) {
                    SettingsContent(
                        contentPadding = PaddingValues(),
                        syncConfiguration = SyncConfiguration("device", "https://saved.example", "saved-token", false, "", ""),
                        appPreferences = AppPreferences(), importLearningSummary = ImportLearningSummary(),
                        isBusy = false, statusMessage = "", layoutMode = CompactPreviewLayout,
                        selectedSection = SettingsSection.Sync, onSelectSection = {},
                        onSaveSyncSettings = { _, _, _, _ -> saves++ }, onSaveAppPreferences = { saves++ },
                        onTestConnection = { url, token, external -> tested = url to token; testedExternal = external }, onSyncNow = {},
                        onClearImportLearningMappings = {},
                    )
                }
            }
        }
        compose.onNodeWithText(context.getString(R.string.field_server_url)).performTextReplacement("https://draft.example")
        compose.onNodeWithTag("external-server-url").performTextReplacement("https://external.example")
        val testLabel = context.getString(R.string.action_test_connection)
        compose.onNodeWithTag("settings_detail_list").performScrollToNode(hasText(testLabel))
        compose.onNodeWithText(testLabel).performClick()
        compose.runOnIdle {
            assertEquals("https://draft.example" to "saved-token", tested)
            assertEquals("https://external.example", testedExternal)
            assertEquals(0, saves)
        }
        val syncLabel = context.getString(R.string.action_sync_now)
        compose.onNodeWithTag("settings_detail_list").performScrollToNode(hasText(syncLabel))
        compose.onNodeWithText(syncLabel).assertIsNotEnabled()
    }
}
