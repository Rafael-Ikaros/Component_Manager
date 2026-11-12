package com.componentvault.android.ui.screen

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import com.componentvault.android.R
import com.componentvault.android.data.BatchJlcDraftStore
import com.componentvault.android.data.BatchJlcStatus
import com.componentvault.android.data.InventoryDatabaseHelper
import com.componentvault.android.data.InventoryRepository
import com.componentvault.android.model.AppPreferences
import com.componentvault.android.model.ComponentDraft
import com.componentvault.android.model.StorageLocationSaveIntent
import com.componentvault.android.model.SyncConfiguration
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class BatchCodeImportUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context: Application get() = RuntimeEnvironment.getApplication()
    private lateinit var repository: InventoryRepository

    @Before fun setup() = runBlocking {
        InventoryDatabaseHelper(context).let { helper ->
            try { context.deleteDatabase(helper.databaseName) } finally { helper.close() }
        }
        BatchJlcDraftStore(context).clear()
        repository = InventoryRepository(context)
        assertTrue(repository.saveStorageLocation("A", "Shelf A", StorageLocationSaveIntent.Create).isSuccess)
        assertTrue(repository.saveComponent(ComponentDraft(sku = "C70565", name = "Existing part", category = "IC",
            packageName = "SOT-23", location = "A", description = "Existing metadata", quantity = 10, minStock = 1)).isSuccess)
    }

    private fun open() {
        compose.setContent {
            MaterialTheme {
                BatchJlcInboundScreen(AppPreferences(), SyncConfiguration("test", "", "", false, "", ""),
                    "A", onDismiss = {}, onCommitted = {}, initialCodeImport = true)
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodes(hasText(context.getString(R.string.batch_codes_parse))).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun parsedCodesUseExistingMetadataAndOnlyCommitAfterReview() {
        open()
        compose.onNodeWithTag("batch_codes_input").performScrollTo().performTextReplacement("C70565 2\nC70565 3")
        compose.onNodeWithText(context.getString(R.string.batch_codes_parse)).performClick()
        val store = BatchJlcDraftStore(context)
        compose.waitUntil(15_000) { store.load().rows.singleOrNull()?.status == BatchJlcStatus.Ready }
        val row = store.load().rows.single()
        assertEquals("5", row.quantityText)
        assertEquals("Existing part", row.name)
        assertEquals("A", row.location)
        assertEquals(10, runBlocking { repository.loadComponents().single().quantity })
        compose.onNodeWithText(context.getString(R.string.batch_commit)).performClick()
        compose.waitUntil(10_000) { store.load().rows.singleOrNull()?.status == BatchJlcStatus.Committed }
        assertEquals(15, runBlocking { repository.loadComponents().single().quantity })
    }

    @Test fun invalidSecondLineLeavesDraftAndInventoryUnchanged() {
        open()
        compose.onNodeWithTag("batch_codes_input").performScrollTo().performTextReplacement("C70565 2\nC70565 0")
        compose.onNodeWithText(context.getString(R.string.batch_codes_parse)).performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("第 2 行：", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(BatchJlcDraftStore(context).load().rows.isEmpty())
        assertEquals(10, runBlocking { repository.loadComponents().single().quantity })
    }
}
