package com.componentvault.android.ui.screen

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import com.componentvault.android.BuildConfig
import com.componentvault.android.R
import com.componentvault.android.data.AppDiagnostics
import com.componentvault.android.data.IssueReportBuilder
import com.componentvault.android.data.IssueUrlResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class FeedbackKind(val label: Int) {
    Interface(R.string.v2_feedback_interface),
    Import(R.string.v2_feedback_import),
    Sync(R.string.v2_feedback_sync),
    Other(R.string.v2_feedback_other),
}

@Composable
internal fun IssueFeedbackCard() {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    var kind by rememberSaveable { mutableStateOf(FeedbackKind.Interface) }
    var kindExpanded by remember { mutableStateOf(false) }
    var previewVisible by rememberSaveable { mutableStateOf(false) }
    var title by rememberSaveable { mutableStateOf("") }
    var description by rememberSaveable { mutableStateOf("") }
    var includeDiagnostics by rememberSaveable { mutableStateOf(false) }
    var includeDevice by rememberSaveable { mutableStateOf(false) }
    var diagnostics by rememberSaveable { mutableStateOf(AppDiagnostics.report()) }
    var deviceInfo by rememberSaveable { mutableStateOf("Component Vault ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}); Android ${Build.VERSION.RELEASE}; ${Build.MANUFACTURER} ${Build.MODEL}") }
    var pendingExport by rememberSaveable { mutableStateOf("") }
    var message by rememberSaveable { mutableStateOf("") }
    val copiedText=stringResource(R.string.feedback_copied);val exportedText=stringResource(R.string.feedback_exported)
    val exportFailedText=stringResource(R.string.feedback_export_failed);val clearedText=stringResource(R.string.feedback_cleared)
    val tooLongText=stringResource(R.string.feedback_url_too_long);val browserFailedText=stringResource(R.string.feedback_browser_failed)

    fun report() = IssueReportBuilder.report(
        title,
        "${context.getString(R.string.v2_feedback_kind)}: ${context.getString(kind.label)}\n\n$description",
        diagnostics.takeIf { includeDiagnostics },
        deviceInfo.takeIf { includeDevice },
    )
    fun copy(value: String) = (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
        .setPrimaryClip(ClipData.newPlainText("Component Vault issue", value))
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        uri?.let {
            val snapshot=pendingExport
            if(snapshot.isBlank()){message=exportFailedText;return@let}
            scope.launch {
                val result=withContext(Dispatchers.IO){runCatching { context.contentResolver.openOutputStream(it,"w")!!.use { stream -> stream.write(snapshot.toByteArray()) } }}
                message=if(result.isSuccess)exportedText else exportFailedText
            }
        }
    }
    SettingsFormSection(supporting = stringResource(R.string.feedback_public_notice)) {
        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(stringResource(R.string.v2_feedback_kind), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Box {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(13.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 49.dp)
                            .clickable { kindExpanded = true }.padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(stringResource(kind.label), Modifier.weight(1f))
                        Icon(painterResource(R.drawable.v2_chevron_down), contentDescription = null)
                    }
                }
                DropdownMenu(expanded = kindExpanded, onDismissRequest = { kindExpanded = false }) {
                    FeedbackKind.entries.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(stringResource(option.label)) },
                            onClick = { kind = option; kindExpanded = false },
                        )
                    }
                }
            }
        }
        SettingsTextField(
            title, { title = it }, label = { Text(stringResource(R.string.v2_feedback_title)) },
            modifier = Modifier.fillMaxWidth(), singleLine = true,
        )
        SettingsTextField(
            description, { description = it }, label = { Text(stringResource(R.string.v2_feedback_steps)) },
            placeholder = { Text(stringResource(R.string.v2_feedback_steps_hint)) },
            modifier = Modifier.fillMaxWidth(), minLines = 3,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(includeDiagnostics, { includeDiagnostics = it })
            Text(stringResource(R.string.feedback_attach_diagnostics))
        }
        if (includeDiagnostics) {
            SettingsTextField(diagnostics, { diagnostics = it },
                label = { Text(stringResource(R.string.feedback_diagnostics_preview)) },
                modifier = Modifier.fillMaxWidth(), minLines = 5)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(includeDevice, { includeDevice = it })
            Text(stringResource(R.string.feedback_attach_device))
        }
        if (includeDevice) {
            SettingsTextField(deviceInfo, { deviceInfo = it },
                label = { Text(stringResource(R.string.feedback_device_preview)) }, modifier = Modifier.fillMaxWidth())
        }
        Button(
            onClick = { message = ""; previewVisible = true },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            shape = RoundedCornerShape(15.dp),
            enabled = title.isNotBlank() && description.isNotBlank(),
        ) { Text(stringResource(R.string.v2_feedback_preview)) }
        TextButton(onClick = {
            AppDiagnostics.clear()
            diagnostics = AppDiagnostics.report()
            message = clearedText
        }) { Text(stringResource(R.string.feedback_clear)) }
        if (message.isNotBlank() && !previewVisible) Text(message, style = MaterialTheme.typography.bodySmall)
    }

    if (previewVisible) {
        val body = report()
        Dialog(
            onDismissRequest = { previewVisible = false },
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
        ) {
            SettingsRouteScaffold(
                title = stringResource(R.string.v2_feedback_preview_title),
                onBack = { previewVisible = false },
            ) { padding ->
                Column(
                    Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)
                        .verticalScroll(rememberScrollState()).padding(start = 18.dp, end = 18.dp, bottom = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column {
                        SettingsStatusRow(stringResource(R.string.v2_feedback_kind), stringResource(kind.label))
                        SettingsStatusRow(stringResource(R.string.v2_feedback_title), title.trim())
                    }
                    Text(stringResource(R.string.v2_feedback_steps), style = MaterialTheme.typography.titleSmall)
                    SelectionContainer {
                        Text(description.trim(), style = MaterialTheme.typography.bodyMedium)
                    }
                    if (includeDiagnostics) {
                        Text(stringResource(R.string.feedback_diagnostics_preview), style = MaterialTheme.typography.titleSmall)
                        SelectionContainer { Text(diagnostics, style = MaterialTheme.typography.bodySmall) }
                    }
                    if (includeDevice) {
                        Text(stringResource(R.string.feedback_device_preview), style = MaterialTheme.typography.titleSmall)
                        SelectionContainer { Text(deviceInfo, style = MaterialTheme.typography.bodySmall) }
                    }
                    SettingsFormNote(stringResource(R.string.feedback_public_notice))
                    Button(
                        onClick = { previewVisible = false }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        shape = RoundedCornerShape(15.dp),
                    ) {
                        Text(stringResource(R.string.prototype_movement_modify))
                    }
                    TextButton(onClick = { copy(body); message = copiedText }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.feedback_copy))
                    }
                    OutlinedButton(onClick = {
                        pendingExport = body
                        export.launch("component-vault-issue.txt")
                    }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.feedback_export)) }
                    OutlinedButton(
                        onClick = {
                            when (val result = IssueReportBuilder.issueUrl(title, body)) {
                                is IssueUrlResult.Full -> runCatching { uriHandler.openUri(result.url) }.onFailure {
                                    copy(body)
                                    message = browserFailedText
                                }
                                is IssueUrlResult.TooLong -> {
                                    copy(body)
                                    runCatching { uriHandler.openUri(result.titleOnlyUrl) }
                                        .onFailure { message = browserFailedText }
                                        .onSuccess { message = tooLongText }
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.feedback_open_github)) }
                    if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
