package com.componentvault.android.ui.screen

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
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
import com.componentvault.android.data.AppearanceAccent
import com.componentvault.android.data.AppearanceMode
import com.componentvault.android.data.AppearancePreferences
import com.componentvault.android.data.AppearancePreferenceStore
import com.componentvault.android.ui.theme.ComponentVaultTheme
import com.componentvault.android.ui.theme.LocalAppearance
import com.componentvault.android.ui.theme.LocalOnAppearanceChange
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
        var savedPreferences: AppPreferences? = null
        var labelToolOpens = 0
        val store = AppearancePreferenceStore(context)
        var appearance by mutableStateOf(store.read())
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            var selected by rememberSaveable { mutableStateOf<SettingsSection?>(SettingsSection.Sync) }
            CompositionLocalProvider(
                LocalAppearance provides appearance,
                LocalOnAppearanceChange provides { value ->
                    store.save(value)
                    appearance = value
                },
            ) {
                ComponentVaultTheme(darkTheme = appearance.mode == AppearanceMode.Dark, accent = appearance.accent) {
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
                                onSaveAppPreferences = { savedPreferences = it },
                                onTestConnection = { _, _, _ -> },
                                onSyncNow = {},
                                onClearImportLearningMappings = {},
                                onOpenLabelTools = { labelToolOpens++ },
                            )
                        }
                    }
                }
            }
        }
        compose.onNodeWithText(context.getString(R.string.field_server_url))
            .performTextReplacement("https://draft.example")
        val groups = compose.onNodeWithTag("settings_group_list")
        val details = compose.onNodeWithTag("settings_detail_list")
        fun select(section: SettingsSection) {
            val tag = "settings_group_${section.name}"
            groups.performScrollToNode(hasTestTag(tag))
            compose.onNodeWithTag(tag).performClick()
        }
        val minStockLabel = context.getString(R.string.field_minimum_stock)
        select(SettingsSection.ImportAndOcr)
        details.performScrollToNode(hasText(minStockLabel))
        compose.onNodeWithText(minStockLabel).performTextReplacement("17")
        select(SettingsSection.Appearance)
        compose.onNodeWithTag("appearance_open_theme").performClick()
        compose.onNodeWithTag("appearance_mode_Dark").performClick()
        select(SettingsSection.Appearance)
        compose.onNodeWithTag("appearance_open_accent").performClick()
        details.performScrollToNode(hasTestTag("appearance_accent_Blue"))
        compose.onNodeWithTag("appearance_accent_Blue").performClick()
        val printingLabel = context.getString(R.string.appearance_printing)
        groups.performScrollToNode(hasText(printingLabel))
        compose.onNodeWithText(printingLabel).performClick()
        restoration.emulateSavedInstanceStateRestore()
        select(SettingsSection.Sync)
        compose.runOnIdle {
            assertEquals(AppearancePreferences(AppearanceMode.Dark, AppearanceAccent.Blue), store.read())
            assertEquals(1, labelToolOpens)
            assertEquals(0, saveCount)
            assertEquals(null, savedPreferences)
        }
        val saveLabel = context.getString(R.string.action_save_settings)
        details.performScrollToNode(hasText(saveLabel))
        compose.onNodeWithText(saveLabel).assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(1, saveCount)
            assertEquals("https://draft.example", savedUrl)
            assertEquals(17, savedPreferences?.defaultImportMinStock)
        }
    }
}
