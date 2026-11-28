package com.componentvault.android.ui.screen

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
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

@Composable
internal fun IssueFeedbackCard() {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
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

    fun report() = IssueReportBuilder.report(title, description, diagnostics.takeIf { includeDiagnostics }, deviceInfo.takeIf { includeDevice })
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
    SettingsFormSection(supporting=stringResource(R.string.feedback_public_notice)) {
        SettingsTextField(title,{title=it},label={Text(stringResource(R.string.feedback_issue_title))},modifier=Modifier.fillMaxWidth(),singleLine=true)
        SettingsTextField(description,{description=it},label={Text(stringResource(R.string.feedback_reproduction))},modifier=Modifier.fillMaxWidth(),minLines=4)
        Row(verticalAlignment=Alignment.CenterVertically){Checkbox(includeDiagnostics,{includeDiagnostics=it});Text(stringResource(R.string.feedback_attach_diagnostics))}
        if(includeDiagnostics)SettingsTextField(diagnostics,{diagnostics=it},label={Text(stringResource(R.string.feedback_diagnostics_preview))},modifier=Modifier.fillMaxWidth(),minLines=5)
        Row(verticalAlignment=Alignment.CenterVertically){Checkbox(includeDevice,{includeDevice=it});Text(stringResource(R.string.feedback_attach_device))}
        if(includeDevice)SettingsTextField(deviceInfo,{deviceInfo=it},label={Text(stringResource(R.string.feedback_device_preview))},modifier=Modifier.fillMaxWidth())
        Button(onClick={copy(report());message=copiedText},modifier=Modifier.fillMaxWidth(),enabled=title.isNotBlank()&&description.isNotBlank()){Text(stringResource(R.string.feedback_copy))}
        OutlinedButton(onClick={pendingExport=report();export.launch("component-vault-issue.txt")},modifier=Modifier.fillMaxWidth(),enabled=title.isNotBlank()&&description.isNotBlank()){Text(stringResource(R.string.feedback_export))}
        OutlinedButton(onClick={val body=report();when(val result=IssueReportBuilder.issueUrl(title,body)){is IssueUrlResult.Full->runCatching{uriHandler.openUri(result.url)}.onFailure{copy(body);message=browserFailedText};is IssueUrlResult.TooLong->{copy(body);runCatching{uriHandler.openUri(result.titleOnlyUrl)}.onFailure{message=browserFailedText}.onSuccess{message=tooLongText}}}},modifier=Modifier.fillMaxWidth(),enabled=title.isNotBlank()&&description.isNotBlank()){Text(stringResource(R.string.feedback_open_github))}
        TextButton(onClick={AppDiagnostics.clear();diagnostics=AppDiagnostics.report();message=clearedText}){Text(stringResource(R.string.feedback_clear))}
        if(message.isNotBlank())Text(message,style=MaterialTheme.typography.bodySmall)
    }
}
