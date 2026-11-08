package com.componentvault.android.ui.screen

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.componentvault.android.R
import com.componentvault.android.data.ExistingImportTarget
import com.componentvault.android.model.ComponentDraft
import com.componentvault.android.model.ComponentImportCandidate
import com.componentvault.android.model.OperationResult
import com.componentvault.android.model.StorageLocationRecord

internal data class PendingSingleAppend(
    val target: ExistingImportTarget,
    val draft: ComponentDraft,
    val onComplete: (OperationResult) -> Unit,
)

/** Owns the save and duplicate-SKU decision while the app owns presentation and navigation. */
internal class ComponentSaveCoordinator(
    private val saveComponent: (ComponentDraft, (OperationResult) -> Unit) -> Unit,
    private val saveImportedComponent: (ComponentDraft, ComponentImportCandidate?, (OperationResult) -> Unit) -> Unit,
    private val findExistingImportTarget: (String, (OperationResult) -> Unit, (ExistingImportTarget?) -> Unit) -> Unit,
    private val appendImportedStock: (ExistingImportTarget, Int, String, String?, (OperationResult) -> Unit) -> Unit,
) {
    var pendingSingleAppend by mutableStateOf<PendingSingleAppend?>(null)
        private set
    var singleAppendBusy by mutableStateOf(false)
        private set

    fun submitEditorDraft(
        draft: ComponentDraft,
        importedDraft: Boolean,
        importCandidate: ComponentImportCandidate?,
        onComplete: (OperationResult) -> Unit,
        onSaved: (OperationResult) -> Unit,
    ) {
        val handleResult: (OperationResult) -> Unit = { result ->
            onComplete(result)
            if (result.isSuccess) onSaved(result)
        }
        if (importedDraft) saveImportedComponent(draft, importCandidate, handleResult)
        else saveComponent(draft, handleResult)
    }

    fun submitImportedDraft(
        draft: ComponentDraft,
        sourceCandidate: ComponentImportCandidate,
        onComplete: (OperationResult) -> Unit,
        onSaved: (OperationResult) -> Unit,
    ) {
        if (draft.id != null) {
            saveImportedComponent(draft, sourceCandidate, onComplete)
            return
        }
        if (singleAppendBusy || pendingSingleAppend != null) return
        singleAppendBusy = true
        findExistingImportTarget(draft.sku, { error ->
            singleAppendBusy = false
            onComplete(error)
        }) { existing ->
            if (existing != null) {
                pendingSingleAppend = PendingSingleAppend(existing, draft, onComplete)
                singleAppendBusy = false
            } else {
                saveImportedComponent(draft, sourceCandidate) { result ->
                    singleAppendBusy = false
                    onComplete(result)
                    if (result.isSuccess) onSaved(result)
                }
            }
        }
    }

    fun appendTotal(pending: PendingSingleAppend): Int? =
        runCatching { Math.addExact(pending.target.quantity, pending.draft.quantity) }.getOrNull()

    fun confirmAppend(onSaved: (OperationResult) -> Unit) {
        val pending = pendingSingleAppend ?: return
        if (singleAppendBusy || appendTotal(pending) == null || pending.draft.quantity <= 0) return
        singleAppendBusy = true
        appendImportedStock(pending.target, pending.draft.quantity, pending.draft.location, pending.draft.purchaseAmount) { result ->
            singleAppendBusy = false
            pending.onComplete(result)
            if (result.isSuccess) {
                pendingSingleAppend = null
                onSaved(result)
            }
        }
    }

    fun dismissAppend() {
        if (!singleAppendBusy) pendingSingleAppend = null
    }
}

@Composable
internal fun SingleAppendConfirmationDialog(
    coordinator: ComponentSaveCoordinator,
    storageLocations: List<StorageLocationRecord>,
    onSaved: (OperationResult) -> Unit,
) {
    val pending = coordinator.pendingSingleAppend ?: return
    val total = coordinator.appendTotal(pending)
    AlertDialog(
        onDismissRequest = coordinator::dismissAppend,
        title = { Text(stringResource(R.string.single_append_title)) },
        text = {
            Text(
                if (total == null) stringResource(R.string.single_append_overflow)
                else stringResource(
                    R.string.single_append_detail,
                    pending.target.quantity,
                    pending.draft.quantity,
                    total,
                    displayLocationName(
                        pending.draft.location,
                        storageLocations,
                        vaultStrings().common.labelNotConfigured,
                    ),
                ),
            )
        },
        confirmButton = {
            Button(
                onClick = { coordinator.confirmAppend(onSaved) },
                enabled = !coordinator.singleAppendBusy && total != null && pending.draft.quantity > 0,
            ) { Text(stringResource(R.string.single_append_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = coordinator::dismissAppend, enabled = !coordinator.singleAppendBusy) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}
