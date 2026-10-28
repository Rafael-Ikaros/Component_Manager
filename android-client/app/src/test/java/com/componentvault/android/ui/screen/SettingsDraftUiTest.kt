package com.componentvault.android.ui.screen

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.componentvault.android.R
import com.componentvault.android.model.AppPreferences
import com.componentvault.android.model.ImportLearningSummary
import com.componentvault.android.model.SyncConfiguration
import com.componentvault.android.ui.screen.preview.CompactPreviewLayout
import com.componentvault.android.ui.screen.preview.ExpandedPreviewLayout
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class SettingsDraftUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

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
