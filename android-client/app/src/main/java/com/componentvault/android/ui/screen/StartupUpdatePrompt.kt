package com.componentvault.android.ui.screen

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.componentvault.android.BuildConfig
import com.componentvault.android.R
import com.componentvault.android.data.GitHubReleaseInfo
import com.componentvault.android.data.GitHubReleaseUpdateChecker
import com.componentvault.android.data.ReleaseCheckResult
import com.componentvault.android.data.UpdateChannel
import com.componentvault.android.data.UpdateChannelPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** An automatic check is best effort and runs only once in the current app session. */
internal class StartupUpdateSession(
    private val checkRelease: suspend (String, UpdateChannel) -> ReleaseCheckResult,
) {
    private var checked = false

    suspend fun checkOnce(installedVersion: String, channel: UpdateChannel): GitHubReleaseInfo? {
        if (checked) return null
        checked = true
        return try {
            (checkRelease(installedVersion, channel) as? ReleaseCheckResult.UpdateAvailable)?.release
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }
}

/** Retains the pending prompt and request across configuration changes, outside navigation. */
internal class StartupUpdateViewModel(
    application: Application,
    savedStateHandle: SavedStateHandle,
) : AndroidViewModel(application) {
    var availableRelease by mutableStateOf<GitHubReleaseInfo?>(null)
        private set

    init {
        if (savedStateHandle.get<Boolean>(checkStartedKey) != true) {
            savedStateHandle[checkStartedKey] = true
            val channel = UpdateChannelPreferences.read(application)
            val session = StartupUpdateSession(GitHubReleaseUpdateChecker()::check)
            viewModelScope.launch {
                availableRelease = session.checkOnce(BuildConfig.VERSION_NAME, channel)
            }
        }
    }

    fun dismiss() {
        availableRelease = null
    }

    private companion object {
        const val checkStartedKey = "startup_update_check_started"
    }
}

@Composable
internal fun StartupUpdatePrompt(release: GitHubReleaseInfo?, onDismiss: () -> Unit) {
    if (release == null) return
    val uriHandler = LocalUriHandler.current
    var browserError by remember(release.tagName) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_update_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SettingsUpdateResult(
                    result = ReleaseCheckResult.UpdateAvailable(release),
                    onOpenRelease = { url -> browserError = runCatching { uriHandler.openUri(url) }.isFailure },
                )
                ApkUpdateDownloadPane(release)
                if (browserError) {
                    Text(
                        stringResource(R.string.settings_browser_open_failed),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
        },
    )
}
