package com.componentvault.android.ui.screen

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import com.componentvault.android.R
import com.componentvault.android.data.bom.BomParser
import com.componentvault.android.data.bom.BomReleaseLine
import com.componentvault.android.data.bom.BomReleasePreview
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w800dp-h360dp")
class BomPreviewShortViewportUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun shortPreviewCanScrollPastSummaryToManualSearchAndExport() {
        val context = RuntimeEnvironment.getApplication()
        val parsed = BomParser.parseCsv("SKU,Qty\nDEV23-003,1".toByteArray(), "Short viewport preview", 1)
        val requirement = parsed.requirements.single()
        compose.runOnUiThread {
            ViewModelProvider(compose.activity)[BomImportSession::class.java].apply {
                initialized = true
                entryMode = BomImportMode.Bom
                selectedMode.value = BomImportMode.Bom
                fileName.value = "dev23-bom-15-rows.xlsx"
                projectName.value = parsed.projectName
                expandedSearches.value = setOf(requirement.identity.canonicalKey)
                preview.value = BomReleasePreview(parsed, listOf(
                    BomReleaseLine(requirement, null, null, 0, null, emptyList()),
                ))
            }
        }
        compose.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxWidth().height(240.dp)) {
                    BomImportScreen(InventoryViewModel(context), {}, BomImportMode.Bom)
                }
            }
        }
        compose.onNodeWithTag("bom_preview_list").performScrollToNode(hasText(context.getString(R.string.bom_match_search_label)))
        compose.onNodeWithText(context.getString(R.string.bom_match_search_label))
            .performScrollTo().assertIsDisplayed().performClick().performTextInput("DEV23")
        compose.onNodeWithText("DEV23", substring = false).assertIsDisplayed()
        compose.onNodeWithTag("bom_preview_list").performScrollToNode(hasText("导出缺料 CSV"))
        compose.onNodeWithText("导出缺料 CSV").performScrollTo().assertIsDisplayed()
    }
}

