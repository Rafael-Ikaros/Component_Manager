package com.componentvault.android.ui.screen

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import com.componentvault.android.R
import com.componentvault.android.model.AppPreferences
import com.componentvault.android.model.ImportLearningSummary
import com.componentvault.android.model.SyncConfiguration
import com.componentvault.android.ui.screen.preview.ExpandedPreviewLayout
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w1000dp-h600dp")
class SettingsAdaptiveDraftUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun groupSwitchAndRestorationKeepDraftAndReachSaveInShortWindow() {
        val context: Application = RuntimeEnvironment.getApplication()
        var saveCount = 0
        var savedUrl: String? = null
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            var selected by rememberSaveable { mutableStateOf<SettingsSection?>(SettingsSection.Sync) }
            MaterialTheme {
                ProvideComponentVaultStrings(runtimeComponentVaultStrings()) {
                    Box(Modifier.requiredSize(840.dp, 280.dp)) {
                        SettingsContent(
                            contentPadding = PaddingValues(),
                            syncConfiguration = SyncConfiguration("device", "https://saved.example", "saved-token", false, "", ""),
                            appPreferences = AppPreferences(),
                            importLearningSummary = ImportLearningSummary(),
                            isBusy = false,
                            statusMessage = "",
                            layoutMode = ExpandedPreviewLayout,
                            selectedSection = selected,
                            onSelectSection = { selected = it },
                            onSaveSyncSettings = { url, _, _, _ -> saveCount++; savedUrl = url },
                            onSaveAppPreferences = {},
                            onTestConnection = { _, _, _ -> },
                            onSyncNow = {},
                            onClearImportLearningMappings = {},
                        )
                    }
                }
            }
        }
        compose.onNodeWithText(context.getString(R.string.field_server_url))
            .performTextReplacement("https://draft.example")
        compose.onNodeWithTag("settings_group_list")
            .performScrollToNode(hasTestTag("settings_group_App"))
        compose.onNodeWithTag("settings_group_App").performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("settings_group_list")
            .performScrollToNode(hasTestTag("settings_group_Sync"))
        compose.onNodeWithTag("settings_group_Sync").performClick()
        compose.runOnIdle { assertEquals(0, saveCount) }
        val saveLabel = context.getString(R.string.action_save_settings)
        compose.onNodeWithTag("settings_detail_list").performScrollToNode(hasText(saveLabel))
        compose.onNodeWithText(saveLabel).assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(1, saveCount)
            assertEquals("https://draft.example", savedUrl)
        }
    }
}
