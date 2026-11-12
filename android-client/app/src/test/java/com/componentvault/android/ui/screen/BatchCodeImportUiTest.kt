package com.componentvault.android.ui.screen

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import com.componentvault.android.R
import com.componentvault.android.data.BatchJlcDraftStore
import com.componentvault.android.data.BatchJlcStatus
import com.componentvault.android.data.BatchJlcDraft
import com.componentvault.android.data.BatchCodeImportParser
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
    private var committed = false

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
                    "A", onDismiss = {}, onCommitted = { committed = true }, initialCodeImport = true)
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodes(hasText(context.getString(R.string.batch_codes_parse))).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun parse() {
        // The dialog can render before the asynchronous draft load has finished.
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasText(context.getString(R.string.batch_codes_parse)) and isEnabled())
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(context.getString(R.string.batch_codes_parse)).performClick()
    }

    @Test fun parsedCodesUseExistingMetadataAndOnlyCommitAfterReview() {
        open()
        compose.onNodeWithTag("batch_codes_input").performScrollTo().performTextReplacement("C70565 2\nC70565 3")
        parse()
        val store = BatchJlcDraftStore(context)
        // Observe the UI's completed save instead of repeatedly opening the file during its atomic rename.
        compose.waitUntil(15_000) {
            compose.onAllNodes(hasText(context.getString(R.string.batch_commit)) and isEnabled())
                .fetchSemanticsNodes().isNotEmpty()
        }
        val row = store.load().rows.single()
        assertEquals(BatchJlcStatus.Ready, row.status)
        assertEquals("5", row.quantityText)
        assertEquals("Existing part", row.name)
        assertEquals("A", row.location)
        assertEquals(10, runBlocking { repository.loadComponents().single().quantity })
        compose.onNodeWithText(context.getString(R.string.batch_commit)).performClick()
        compose.waitUntil(10_000) { committed }
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasText(context.getString(R.string.batch_tab_review, 0)) and isEnabled())
                .fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(BatchJlcStatus.Committed, store.load().rows.single().status)
        assertEquals(15, runBlocking { repository.loadComponents().single().quantity })
    }

    @Test fun invalidSecondLineLeavesDraftAndInventoryUnchanged() {
        open()
        compose.onNodeWithTag("batch_codes_input").performScrollTo().performTextReplacement("C70565 2\nC70565 0")
        parse()
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("第 2 行：", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(BatchJlcDraftStore(context).load().rows.isEmpty())
        assertEquals(10, runBlocking { repository.loadComponents().single().quantity })
    }

    private fun openCorrectedImport() : BatchJlcDraft {
        runBlocking {
            for (sku in listOf("C123", "C456")) assertTrue(repository.saveComponent(ComponentDraft(
                sku = sku, name = sku, category = "IC", packageName = "SMD", location = "A",
                description = "", quantity = 10, minStock = 1)).isSuccess)
        }
        val original = BatchJlcDraft(rows = BatchCodeImportParser.text("C123 2\nC456 3", "A", setOf("A"))
            .mapIndexed { index, row ->
                row.copy(status = BatchJlcStatus.Ready, selected = index != 0, name = "Manual name $index",
                    category = "Manual category", packageName = "Manual package",
                    purchaseAmount = if (index == 0) "12.34" else "23.45")
            })
        BatchJlcDraftStore(context).save(original)
        open()
        compose.onNodeWithTag("batch_codes_input").performScrollTo().performTextReplacement("C123 2\nC456 4")
        parse()
        compose.waitUntil(10_000) { compose.onAllNodes(hasText(context.getString(R.string.batch_codes_replace))).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(context.getString(R.string.batch_codes_overlap_row, "C123", "A", "2", "2")).assertExists()
        compose.onNodeWithText(context.getString(R.string.batch_codes_overlap_row, "C456", "A", "3", "4")).assertExists()
        compose.onNodeWithText(context.getString(R.string.batch_codes_overlap_amount_review, "23.45")).assertExists()
        assertEquals(original, BatchJlcDraftStore(context).load())
        return original
    }

    private fun waitForReviewedImport() {
        compose.waitUntil(15_000) {
            compose.onAllNodes(hasText(context.getString(R.string.batch_commit)) and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test fun correctedReimportDoesNotChangeDraftBeforeChoiceAndReplaceUsesNewQuantities() {
        val original = openCorrectedImport()
        compose.onNodeWithTag("batch_codes_replace").performClick()
        waitForReviewedImport()
        val rows = BatchJlcDraftStore(context).load().rows
        assertEquals(listOf("2", "4"), rows.map { it.quantityText })
        assertEquals(listOf("C123", "C456"), rows.map { it.sku })
        assertEquals(listOf(original.rows.first(), original.rows.last().copy(quantityText = "4")), rows)
        assertTrue(runBlocking { repository.loadComponents() }.all { it.quantity == 10 })
    }

    @Test fun explicitAppendRetainsOriginalRowsAndCancelLeavesThemUnchanged() {
        val original = openCorrectedImport()
        compose.onNodeWithText(context.getString(R.string.action_cancel)).performClick()
        assertEquals(original, BatchJlcDraftStore(context).load())
        parse()
        compose.waitUntil(10_000) { compose.onAllNodes(hasText(context.getString(R.string.batch_codes_append))).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("batch_codes_append").performClick()
        waitForReviewedImport()
        val rows = BatchJlcDraftStore(context).load().rows
        assertEquals(mapOf("C123" to 4, "C456" to 7), rows.groupBy { it.sku }
            .mapValues { (_, values) -> values.sumOf { it.quantityText.toInt() } })
        assertEquals(4, rows.map { it.id }.distinct().size)
    }

    @Test fun committedOverlapDisablesReplacement() {
        val original = BatchJlcDraft(rows = BatchCodeImportParser.text("C70565 2", "A", setOf("A"))
            .map { it.copy(status = BatchJlcStatus.Committed) })
        BatchJlcDraftStore(context).save(original)
        open()
        compose.onNodeWithTag("batch_codes_input").performScrollTo().performTextReplacement("C70565 4")
        parse()
        compose.waitUntil(10_000) { compose.onAllNodes(hasText(context.getString(R.string.batch_codes_replace))).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("batch_codes_replace").assertIsNotEnabled()
        assertEquals(original, BatchJlcDraftStore(context).load())
    }
}
