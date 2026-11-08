package com.componentvault.android.ui.screen

import com.componentvault.android.data.ExistingImportTarget
import com.componentvault.android.model.ComponentDraft
import com.componentvault.android.model.ComponentImportCandidate
import com.componentvault.android.model.ComponentImportSourceType
import com.componentvault.android.model.OperationResult
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class ComponentSaveCoordinatorTest {
    private val draft = ComponentDraft(
        sku = "R-001", name = "Resistor", category = "Resistors", packageName = "0603",
        location = "bin-a", description = "", quantity = 3, minStock = 0,
    )
    private val candidate = ComponentImportCandidate(
        sourceType = ComponentImportSourceType.JlcQr,
        rawPayload = "R-001",
        sourceLabel = "JLC",
    )
    private val existing = ExistingImportTarget("component-1", "R-001", 7, "2026-01-01", "bin-b")

    @Test
    fun duplicateWaitsForConfirmationAndRetriesAfterFailedAppend() {
        var lookup: ((ExistingImportTarget?) -> Unit)? = null
        var appended = 0
        var appendCompletion: ((OperationResult) -> Unit)? = null
        var completed = 0
        var revealed = 0
        val coordinator = ComponentSaveCoordinator(
            saveComponent = { _, _ -> error("unexpected editor save") },
            saveImportedComponent = { _, _, _ -> error("duplicate must not create a component") },
            findExistingImportTarget = { sku, _, complete ->
                assertEquals("R-001", sku)
                lookup = complete
            },
            appendImportedStock = { target, quantity, location, _, complete ->
                assertEquals(existing, target)
                assertEquals(3, quantity)
                assertEquals("bin-a", location)
                appended++
                appendCompletion = complete
            },
        )

        coordinator.submitImportedDraft(draft, candidate, { completed++ }) { revealed++ }
        coordinator.submitImportedDraft(draft, candidate, { completed++ }) { revealed++ }
        assertTrue(coordinator.singleAppendBusy)
        lookup?.invoke(existing)
        assertFalse(coordinator.singleAppendBusy)
        assertEquals(10, coordinator.appendTotal(requireNotNull(coordinator.pendingSingleAppend)))
        assertEquals(0, completed)

        coordinator.confirmAppend { revealed++ }
        coordinator.confirmAppend { revealed++ }
        assertEquals(1, appended)
        appendCompletion?.invoke(OperationResult(false, "try again"))
        assertEquals(1, completed)
        assertEquals(0, revealed)
        assertTrue(coordinator.pendingSingleAppend != null)

        coordinator.confirmAppend { revealed++ }
        appendCompletion?.invoke(OperationResult(true, "saved", "component-1"))
        assertEquals(2, appended)
        assertEquals(2, completed)
        assertEquals(1, revealed)
        assertNull(coordinator.pendingSingleAppend)
    }

    @Test
    fun newImportSavesAndEditedImportSkipsDuplicateLookup() {
        var lookups = 0
        var saved = 0
        var revealed = 0
        val coordinator = ComponentSaveCoordinator(
            saveComponent = { _, _ -> error("unexpected editor save") },
            saveImportedComponent = { _, source, complete ->
                assertEquals(candidate, source)
                saved++
                complete(OperationResult(true, "saved", "component-2"))
            },
            findExistingImportTarget = { _, _, complete ->
                lookups++
                complete(null)
            },
            appendImportedStock = { _, _, _, _, _ -> error("unexpected append") },
        )
        coordinator.submitImportedDraft(draft, candidate, {}) { revealed++ }
        coordinator.submitImportedDraft(draft.copy(id = "component-2"), candidate, {}) { revealed++ }

        assertEquals(1, lookups)
        assertEquals(2, saved)
        assertEquals(1, revealed)
    }
}
