package com.componentvault.android.ui.screen

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Brightness6
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Sell
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.outlined.Warehouse
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.sp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.componentvault.android.AppLocaleManager
import com.componentvault.android.BuildConfig
import com.componentvault.android.R
import com.componentvault.android.data.GitHubReleaseUpdateChecker
import com.componentvault.android.data.ReleaseCheckResult
import com.componentvault.android.data.UpdateChannel
import com.componentvault.android.data.UpdatePreferences
import com.componentvault.android.model.AppLanguage
import com.componentvault.android.model.AppPreferences
import com.componentvault.android.model.ImportLearningSummary
import com.componentvault.android.model.OcrEngineMode
import com.componentvault.android.model.SyncConfiguration
import com.componentvault.android.model.StorageLocationRecord
import com.componentvault.android.data.AppearanceMode
import com.componentvault.android.data.AppearanceAccent
import com.componentvault.android.ui.theme.LocalAppearance
import com.componentvault.android.ui.theme.LocalOnAppearanceChange
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.shape.RoundedCornerShape
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsRouteScreen(
    syncConfiguration: SyncConfiguration,
    appPreferences: AppPreferences,
    importLearningSummary: ImportLearningSummary,
    isBusy: Boolean,
    statusMessage: String,
    layoutMode: InventoryLayoutMode,
    selectedSection: SettingsSection?,
    onSelectSection: (SettingsSection?) -> Unit,
    onDismiss: () -> Unit,
    onSaveSyncSettings: (String, String, Boolean, String) -> Unit,
    onSaveAppPreferences: (AppPreferences) -> Unit,
    onTestConnection: (String, String, String) -> Unit,
    onSyncNow: () -> Unit,
    onClearImportLearningMappings: () -> Unit,
    storageLocations: List<StorageLocationRecord> = emptyList(),
    localComponentCount: Int? = null,
) {
    val strings = vaultStrings()
    val canStepBackToSectionList = !layoutMode.showsListDetail && selectedSection != null
    val title = selectedSection?.title(strings) ?: strings.shell.settingsDestination

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    TextButton(
                        onClick = {
                            if (canStepBackToSectionList) {
                                onSelectSection(null)
                            } else {
                                onDismiss()
                            }
                        },
                    ) {
                        Text(strings.common.actionBack)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        SettingsContent(
            contentPadding = padding,
            syncConfiguration = syncConfiguration,
            appPreferences = appPreferences,
            importLearningSummary = importLearningSummary,
            isBusy = isBusy,
            statusMessage = statusMessage,
            layoutMode = layoutMode,
            selectedSection = selectedSection,
            onSelectSection = onSelectSection,
            onSaveSyncSettings = onSaveSyncSettings,
            onSaveAppPreferences = onSaveAppPreferences,
            onTestConnection = onTestConnection,
            onSyncNow = onSyncNow,
            onClearImportLearningMappings = onClearImportLearningMappings,
            storageLocations = storageLocations,
            localComponentCount = localComponentCount,
        )
    }
}

@Composable
internal fun SettingsScreen(
    contentPadding: PaddingValues,
    syncConfiguration: SyncConfiguration,
    appPreferences: AppPreferences,
    importLearningSummary: ImportLearningSummary,
    isBusy: Boolean,
    statusMessage: String,
    layoutMode: InventoryLayoutMode,
    selectedSection: SettingsSection?,
    onSelectSection: (SettingsSection?) -> Unit,
    onSaveSyncSettings: (String, String, Boolean, String) -> Unit,
    onSaveAppPreferences: (AppPreferences) -> Unit,
    onTestConnection: (String, String, String) -> Unit,
    onSyncNow: () -> Unit,
    onClearImportLearningMappings: () -> Unit,
    storageLocations: List<StorageLocationRecord> = emptyList(),
    localComponentCount: Int? = null,
) {
    SettingsContent(
        contentPadding = contentPadding,
        syncConfiguration = syncConfiguration,
        appPreferences = appPreferences,
        importLearningSummary = importLearningSummary,
        isBusy = isBusy,
        statusMessage = statusMessage,
        layoutMode = layoutMode,
        selectedSection = selectedSection,
        onSelectSection = onSelectSection,
        onSaveSyncSettings = onSaveSyncSettings,
        onSaveAppPreferences = onSaveAppPreferences,
        onTestConnection = onTestConnection,
        onSyncNow = onSyncNow,
        onClearImportLearningMappings = onClearImportLearningMappings,
        storageLocations = storageLocations,
        localComponentCount = localComponentCount,
    )
}

@Composable
internal fun SettingsContent(
    contentPadding: PaddingValues,
    syncConfiguration: SyncConfiguration,
    appPreferences: AppPreferences,
    importLearningSummary: ImportLearningSummary,
    isBusy: Boolean,
    statusMessage: String,
    layoutMode: InventoryLayoutMode,
    selectedSection: SettingsSection?,
    onSelectSection: (SettingsSection?) -> Unit,
    onSaveSyncSettings: (String, String, Boolean, String) -> Unit,
    onSaveAppPreferences: (AppPreferences) -> Unit,
    onTestConnection: (String, String, String) -> Unit,
    onSyncNow: () -> Unit,
    onClearImportLearningMappings: () -> Unit,
    onManageLocations: (() -> Unit)? = null,
    onBackupRestore: (() -> Unit)? = null,
    onReviewSyncConflict: (() -> Unit)? = null,
    storageLocations: List<StorageLocationRecord> = emptyList(),
    onOpenLabelTools: (() -> Unit)? = null,
    onSplitLayoutChanged: (Boolean) -> Unit = {},
    localComponentCount: Int? = null,
) {
    val strings = vaultStrings()
    val context = LocalContext.current
    var updateChannel by remember(context) { mutableStateOf(UpdatePreferences.readChannel(context)) }
    var serverUrl by rememberSaveable(syncConfiguration.serverBaseUrl) {
        mutableStateOf(syncConfiguration.serverBaseUrl)
    }
    var externalServerUrl by rememberSaveable(syncConfiguration.externalServerBaseUrl) {
        mutableStateOf(syncConfiguration.externalServerBaseUrl)
    }
    var apiToken by rememberSaveable(syncConfiguration.apiToken) {
        mutableStateOf(syncConfiguration.apiToken)
    }
    var autoSyncEnabled by rememberSaveable(syncConfiguration.autoSyncEnabled) {
        mutableStateOf(syncConfiguration.autoSyncEnabled)
    }
    var defaultImportLocation by rememberSaveable(appPreferences.defaultImportLocation) {
        mutableStateOf(appPreferences.defaultImportLocation)
    }
    var defaultImportMinStockText by rememberSaveable(appPreferences.defaultImportMinStock) {
        mutableStateOf(appPreferences.defaultImportMinStock.toString())
    }
    var rememberLastImportLocation by rememberSaveable(appPreferences.rememberLastImportLocation) {
        mutableStateOf(appPreferences.rememberLastImportLocation)
    }
    var syncAfterLocalChanges by rememberSaveable(appPreferences.syncAfterLocalChanges) {
        mutableStateOf(appPreferences.syncAfterLocalChanges)
    }
    var enableLocalAutoRecognition by rememberSaveable(appPreferences.enableLocalAutoRecognition) {
        mutableStateOf(appPreferences.enableLocalAutoRecognition)
    }
    var preferAggressiveAutoRecognition by rememberSaveable(appPreferences.preferAggressiveAutoRecognition) {
        mutableStateOf(appPreferences.preferAggressiveAutoRecognition)
    }
    var enableLocalImportLearning by rememberSaveable(appPreferences.enableLocalImportLearning) {
        mutableStateOf(appPreferences.enableLocalImportLearning)
    }
    var enableServerJlcLookup by rememberSaveable(appPreferences.enableServerJlcLookup) {
        mutableStateOf(appPreferences.enableServerJlcLookup)
    }
    var enablePublicJlcLookup by rememberSaveable(appPreferences.enablePublicJlcLookup) {
        mutableStateOf(appPreferences.enablePublicJlcLookup)
    }
    var ocrEngineMode by rememberSaveable(appPreferences.ocrEngineMode) {
        mutableStateOf(appPreferences.ocrEngineMode)
    }
    var appLanguage by rememberSaveable(appPreferences.appLanguage) {
        mutableStateOf(appPreferences.appLanguage)
    }
    var showToken by rememberSaveable { mutableStateOf(false) }
    var errorMessage by rememberSaveable { mutableStateOf<String?>(null) }
    var showClearLearningConfirmation by rememberSaveable { mutableStateOf(false) }

    val hasUnsavedConnection = serverUrl.trim().trimEnd('/') != syncConfiguration.serverBaseUrl ||
        externalServerUrl.trim().trimEnd('/') != syncConfiguration.externalServerBaseUrl ||
        apiToken.trim() != syncConfiguration.apiToken || autoSyncEnabled != syncConfiguration.autoSyncEnabled

    val saveDraft: (Boolean) -> Boolean = { applyLocale ->
        val minStock = defaultImportMinStockText.toIntOrNull()
        if (minStock == null || minStock < 0) {
            errorMessage = strings.forms.componentNonNegative
            false
        } else {
            errorMessage = null
            onSaveSyncSettings(serverUrl, apiToken, autoSyncEnabled, externalServerUrl)
            onSaveAppPreferences(
                AppPreferences(
                    defaultImportLocation = defaultImportLocation.trim(),
                    lastImportLocation = appPreferences.lastImportLocation,
                    defaultImportMinStock = minStock,
                    rememberLastImportLocation = rememberLastImportLocation,
                    syncAfterLocalChanges = syncAfterLocalChanges,
                    enableLocalAutoRecognition = enableLocalAutoRecognition,
                    preferAggressiveAutoRecognition = preferAggressiveAutoRecognition,
                    enableLocalImportLearning = enableLocalImportLearning,
                    enableServerJlcLookup = enableServerJlcLookup,
                    enablePublicJlcLookup = enablePublicJlcLookup,
                    ocrEngineMode = ocrEngineMode,
                    appLanguage = appLanguage,
                ),
            )
            if (applyLocale) {
                AppLocaleManager.apply(appLanguage)
            }
            true
        }
    }

    val detailStates = SettingsSection.entries.associateWith { rememberLazyListState() }
    val navigationState = rememberLazyListState()
    val sectionStateHolder = rememberSaveableStateHolder()
    val section = selectedSection ?: SettingsSection.Sync

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .consumeWindowInsets(contentPadding)
            .imePadding()
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        // Budget the content left after the shell, insets and page margins.
        val showTwoPanes = layoutMode.showsListDetail && maxWidth >= 700.dp
        androidx.compose.runtime.SideEffect { onSplitLayoutChanged(showTwoPanes) }
        BackHandler(enabled = !showTwoPanes && selectedSection != null) {
            onSelectSection(null)
        }
        val detail: @Composable (Modifier) -> Unit = { modifier ->
            sectionStateHolder.SaveableStateProvider(section.name) {
                LazyColumn(
                    modifier = modifier.then(pageEntranceMotion(section)).testTag("settings_detail_list"),
                    state = detailStates.getValue(section),
                    contentPadding = PaddingValues(bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    settingsSectionDetailItems(
                        section = section,
                        showSectionHeading = showTwoPanes,
                        strings = strings,
                        serverUrl = serverUrl,
                        onServerUrlChange = { serverUrl = it },
                        externalServerUrl = externalServerUrl,
                        onExternalServerUrlChange = { externalServerUrl = it },
                        apiToken = apiToken,
                        onApiTokenChange = { apiToken = it },
                        autoSyncEnabled = autoSyncEnabled,
                        onAutoSyncChange = { autoSyncEnabled = it },
                        defaultImportLocation = defaultImportLocation,
                        onDefaultImportLocationChange = { defaultImportLocation = it },
                        defaultImportMinStockText = defaultImportMinStockText,
                        onDefaultImportMinStockChange = { defaultImportMinStockText = it },
                        rememberLastImportLocation = rememberLastImportLocation,
                        onRememberLastImportLocationChange = { rememberLastImportLocation = it },
                        syncAfterLocalChanges = syncAfterLocalChanges,
                        onSyncAfterLocalChangesChange = { syncAfterLocalChanges = it },
                        enableLocalAutoRecognition = enableLocalAutoRecognition,
                        onEnableLocalAutoRecognitionChange = { enableLocalAutoRecognition = it },
                        preferAggressiveAutoRecognition = preferAggressiveAutoRecognition,
                        onPreferAggressiveAutoRecognitionChange = { preferAggressiveAutoRecognition = it },
                        enableLocalImportLearning = enableLocalImportLearning,
                        onEnableLocalImportLearningChange = { enableLocalImportLearning = it },
                        enableServerJlcLookup = enableServerJlcLookup,
                        onEnableServerJlcLookupChange = { enableServerJlcLookup = it },
                        enablePublicJlcLookup = enablePublicJlcLookup,
                        onEnablePublicJlcLookupChange = { enablePublicJlcLookup = it },
                        ocrEngineMode = ocrEngineMode,
                        onOcrEngineModeChange = { ocrEngineMode = it },
                        appLanguage = appLanguage,
                        onAppLanguageChange = { appLanguage = it },
                        importLearningSummary = importLearningSummary,
                        updateChannel = updateChannel,
                        onUpdateChannelChange = { channel ->
                            UpdatePreferences.writeChannel(context, channel)
                            updateChannel = channel
                        },
                        showToken = showToken,
                        onToggleToken = { showToken = !showToken },
                        isBusy = isBusy,
                        syncRunning = syncConfiguration.syncState == "Running",
                        syncSummary = syncConfiguration.syncSummary,
                        errorMessage = errorMessage,
                        hasUnsavedConnection = hasUnsavedConnection,
                        onSave = { saveDraft(true) },
                        onTestConnection = {
                            onTestConnection(serverUrl, apiToken, externalServerUrl)
                        },
                        onSyncNow = {
                            onSyncNow()
                        },
                        onClearImportLearningMappings = { showClearLearningConfirmation = true },
                    )
                    if (section == SettingsSection.Sync) {
                        if (syncConfiguration.hasInventoryConflict && onReviewSyncConflict != null) {
                            item {
                                Button(
                                    onClick = onReviewSyncConflict,
                                    enabled = !isBusy,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(stringResource(R.string.sync_conflict_review_title))
                                }
                            }
                        }
                        item { SettingsSyncStatusPane(syncConfiguration) }
                    }
                }
            }
        }
        if (showTwoPanes) {
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                LazyColumn(
                    modifier = Modifier.width(240.dp).fillMaxHeight().testTag("settings_group_list"),
                    state = navigationState,
                    contentPadding = PaddingValues(bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item {
                        Text(strings.shell.settingsDestination, style = MaterialTheme.typography.titleMedium)
                        Text(
                            syncConfiguration.syncSummary,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    item {
                        SettingsSectionList(
                            selectedSection = section,
                            onSelectSection = onSelectSection,
                            onManageLocations = onManageLocations,
                            onBackupRestore = onBackupRestore,
                            onOpenLabelTools = onOpenLabelTools,
                            syncConfiguration = syncConfiguration,
                            appLanguage = appPreferences.appLanguage,
                            updateChannel = updateChannel,
                            localComponentCount = localComponentCount,
                            compact = true,
                        )
                    }
                }
                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    detail(Modifier.widthIn(max = 640.dp).fillMaxWidth().fillMaxHeight())
                }
            }
        } else if (selectedSection == null) {
            LazyColumn(
                modifier = Modifier.fillMaxSize().testTag("settings_group_list"),
                state = navigationState,
                contentPadding = PaddingValues(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    SettingsSectionList(
                        selectedSection = null,
                        onSelectSection = onSelectSection,
                        onManageLocations = onManageLocations,
                        onBackupRestore = onBackupRestore,
                        onOpenLabelTools = onOpenLabelTools,
                        syncConfiguration = syncConfiguration,
                        appLanguage = appPreferences.appLanguage,
                        updateChannel = updateChannel,
                        localComponentCount = localComponentCount,
                    )
                }
            }
        } else {
            detail(Modifier.widthIn(max = 640.dp).fillMaxWidth().fillMaxHeight())
        }
    }

    if (showClearLearningConfirmation) {
        AlertDialog(
            onDismissRequest = { showClearLearningConfirmation = false },
            title = { Text(strings.settings.clearLearnedMappingsTitle) },
            text = { Text(strings.settings.clearLearnedMappingsMessage) },
            confirmButton = {
                Button(
                    onClick = {
                        showClearLearningConfirmation = false
                        onClearImportLearningMappings()
                    },
                ) {
                    Text(strings.settings.clearLearnedMappingsAction)
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showClearLearningConfirmation = false }) {
                    Text(strings.common.actionCancel)
                }
            },
        )
    }
}

@Composable
private fun SettingsSectionList(
    selectedSection: SettingsSection?,
    onSelectSection: (SettingsSection) -> Unit,
    syncConfiguration: SyncConfiguration,
    appLanguage: AppLanguage,
    updateChannel: UpdateChannel,
    localComponentCount: Int?,
    onManageLocations: (() -> Unit)? = null,
    onBackupRestore: (() -> Unit)? = null,
    onOpenLabelTools: (() -> Unit)? = null,
    compact: Boolean = false,
) {
    val strings = vaultStrings()
    val appearance = LocalAppearance.current
    val route: @Composable (SettingsSection) -> Unit = { section ->
        val summary = when (section) {
            SettingsSection.Appearance -> stringResource(
                R.string.appearance_summary,
                stringResource(appearance.mode.label()),
                stringResource(appearance.accent.label()),
            )
            SettingsSection.App -> when (appLanguage) {
                AppLanguage.ZhCn -> stringResource(R.string.appearance_language_chinese)
                AppLanguage.English -> "English"
            }
            SettingsSection.Sync -> stringResource(
                if (syncConfiguration.serverBaseUrl.isNotBlank() && syncConfiguration.apiToken.isNotBlank()) {
                    R.string.appearance_sync_configured
                } else R.string.appearance_sync_not_configured,
            )
            SettingsSection.About -> stringResource(
                if (updateChannel == UpdateChannel.Dev) R.string.appearance_channel_dev
                else R.string.appearance_channel_stable,
            )
            else -> null
        }
        SettingsSectionCard(
            title = section.listTitle(strings),
            supporting = section.listSupporting(),
            icon = section.icon(),
            selected = section == selectedSection,
            onClick = { onSelectSection(section) },
            summary = summary,
            compact = compact,
            modifier = Modifier.testTag("settings_group_${section.name}"),
        )
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(22.dp)) {
        SettingsLocalWarehouseSummary(localComponentCount)
        SettingsRouteGroup(stringResource(R.string.appearance_group_habits)) {
            route(SettingsSection.Appearance)
            SettingsRowDivider(compact)
            route(SettingsSection.ImportAndOcr)
            SettingsRowDivider(compact)
            route(SettingsSection.App)
        }
        SettingsRouteGroup(stringResource(R.string.appearance_group_warehouse)) {
            route(SettingsSection.Sync)
            onManageLocations?.let { action ->
                SettingsRowDivider(compact)
                SettingsSectionCard(
                    title = stringResource(R.string.appearance_locations_title),
                    supporting = stringResource(R.string.appearance_locations_hint),
                    icon = Icons.Outlined.LocationOn,
                    selected = false,
                    onClick = action,
                    compact = compact,
                )
            }
            onBackupRestore?.let { action ->
                SettingsRowDivider(compact)
                SettingsSectionCard(
                    title = stringResource(R.string.appearance_backup_title),
                    supporting = stringResource(R.string.appearance_backup_hint),
                    icon = Icons.Outlined.Storage,
                    selected = false,
                    onClick = action,
                    compact = compact,
                )
            }
            onOpenLabelTools?.let { action ->
                SettingsRowDivider(compact)
                SettingsSectionCard(
                    title = stringResource(R.string.appearance_printing),
                    supporting = stringResource(R.string.appearance_printing_hint),
                    icon = Icons.Outlined.Sell,
                    selected = false,
                    onClick = action,
                    compact = compact,
                )
            }
        }
        SettingsRouteGroup(stringResource(R.string.appearance_group_support)) {
            route(SettingsSection.Feedback)
            SettingsRowDivider(compact)
            route(SettingsSection.About)
        }
    }
}

private fun SettingsSection.icon(): ImageVector = when (this) {
    SettingsSection.Appearance -> Icons.Outlined.Brightness6
    SettingsSection.ImportAndOcr -> Icons.Outlined.DocumentScanner
    SettingsSection.App -> Icons.Outlined.Translate
    SettingsSection.Sync -> Icons.Outlined.Dns
    SettingsSection.Feedback -> Icons.Outlined.ChatBubbleOutline
    SettingsSection.About -> Icons.Outlined.Info
}

@Composable
private fun SettingsLocalWarehouseSummary(localComponentCount: Int?) {
    val colors = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth().testTag("settings_local_warehouse"),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surface),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 77.dp).padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            androidx.compose.material3.Surface(
                shape = RoundedCornerShape(14.dp),
                color = colors.primaryContainer,
                contentColor = colors.onPrimaryContainer,
            ) {
                Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Warehouse, contentDescription = null, modifier = Modifier.size(20.dp))
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    stringResource(R.string.appearance_local_warehouse),
                    style = MaterialTheme.typography.bodyLarge,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    if (localComponentCount == null) stringResource(R.string.appearance_local_loading)
                    else stringResource(R.string.appearance_local_components, localComponentCount),
                    style = MaterialTheme.typography.bodySmall,
                    fontSize = 12.sp,
                    color = colors.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SettingsRouteGroup(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            title,
            modifier = Modifier.padding(horizontal = 14.dp),
            style = MaterialTheme.typography.labelMedium,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.primary,
        )
        Card(
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) { content() }
    }
}

@Composable
private fun SettingsRowDivider(compact: Boolean) {
    HorizontalDivider(Modifier.padding(start = if (compact) 38.dp else 48.dp, end = 16.dp))
}

@Composable
private fun SettingsSectionCard(
    title: String,
    supporting: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    summary: String? = null,
    compact: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    val supportingColor = if (selected) colors.onPrimaryContainer else colors.onSurfaceVariant
    androidx.compose.material3.Surface(
        color = if (selected) colors.primaryContainer else colors.surface,
        contentColor = if (selected) colors.onPrimaryContainer else colors.onSurface,
    ) {
        Row(
            modifier = modifier.fillMaxWidth().heightIn(min = 77.dp)
                .then(if (selected) Modifier.selectable(true, role = Role.Button, onClick = onClick)
                    else Modifier.clickable(role = Role.Button, onClick = onClick))
                .padding(horizontal = if (compact) 12.dp else 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(if (compact) 18.dp else 20.dp),
                tint = supportingColor,
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    supporting,
                    style = MaterialTheme.typography.bodySmall,
                    fontSize = 13.sp,
                    color = supportingColor,
                )
            }
            if (summary != null) {
                Text(
                    summary,
                    modifier = Modifier.widthIn(max = if (compact) 64.dp else 96.dp),
                    style = MaterialTheme.typography.bodySmall,
                    fontSize = 12.sp,
                    color = supportingColor,
                    textAlign = androidx.compose.ui.text.style.TextAlign.End,
                )
            }
            Icon(
                Icons.Outlined.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = supportingColor,
            )
        }
    }
}

@Composable
private fun SettingsSyncStatusPane(
    syncConfiguration: SyncConfiguration,
) {
    val strings = vaultStrings()

    SectionPane(
        title = stringResource(R.string.sync_run_label),
        supporting = strings.settings.summarySubtitle,
    ) {
        ValueBlock(label = stringResource(R.string.sync_bom_success_label),
            value = syncConfiguration.bomLastSyncedAt.ifBlank { stringResource(R.string.sync_never) })
        PhotoSyncSettings(syncConfiguration)
        ValueBlock(
            label = strings.settings.endpoint,
            value = syncConfiguration.serverBaseUrl.ifBlank { strings.common.labelNotConfigured },
        )
        ValueBlock(
            label = strings.settings.deviceId,
            value = syncConfiguration.deviceId,
        )
        ValueBlock(
            label = stringResource(R.string.sync_inventory_success_label),
            value = syncConfiguration.lastSyncedAt,
        )
        ValueBlock(
            label = strings.settings.lastResult,
            value = syncConfiguration.lastSyncMessage,
        )
    }
}

@Composable
private fun SettingsAboutPane(
    updateChannel: UpdateChannel,
    onUpdateChannelChange: (UpdateChannel) -> Unit,
    showHeading: Boolean = true,
) {
    val strings = vaultStrings()
    val uriHandler = LocalUriHandler.current
    val coroutineScope = rememberCoroutineScope()
    val updateChecker = remember { GitHubReleaseUpdateChecker() }
    val context = LocalContext.current
    var showStartupUpdatePrompt by remember {
        mutableStateOf(UpdatePreferences.showStartupPrompt(context))
    }
    var isChecking by remember { mutableStateOf(false) }
    var updateResult by remember { mutableStateOf<ReleaseCheckResult?>(null) }
    var browserError by remember { mutableStateOf(false) }

    fun openTrustedUrl(url: String) {
        browserError = runCatching { uriHandler.openUri(url) }.isFailure
    }

    SectionPane(
        title = strings.settings.aboutTitle.takeIf { showHeading },
        supporting = strings.settings.aboutSubtitle,
    ) {
        ValueBlock(
            label = stringResource(R.string.settings_about_app_name),
            value = stringResource(R.string.app_name),
        )
        ValueBlock(
            label = strings.settings.appVersion,
            value = stringResource(
                R.string.settings_about_version_format,
                BuildConfig.VERSION_NAME,
                BuildConfig.VERSION_CODE,
            ),
        )
        ValueBlock(
            label = stringResource(R.string.settings_about_author),
            value = "Rafael-Ikaros",
        )
        ValueBlock(
            label = strings.settings.localStorage,
            value = strings.settings.localStorageValue,
        )
        Text(
            text = strings.settings.aboutBody,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ValueBlock(
            label = stringResource(R.string.settings_about_project_address),
            value = PROJECT_URL,
        )
        OutlinedButton(
            onClick = { openTrustedUrl(PROJECT_URL) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.settings_about_open_project))
        }
        ValueBlock(
            label = stringResource(R.string.settings_about_open_source_label),
            value = stringResource(R.string.settings_about_open_source_value),
        )
        OutlinedButton(
            onClick = { openTrustedUrl(OPEN_SOURCE_URL) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.settings_about_open_source))
        }
    }
    SectionPane(title = stringResource(R.string.settings_update_title)) {
        SettingsToggleRow(
            title = stringResource(R.string.settings_update_startup_prompt),
            subtitle = stringResource(R.string.settings_update_startup_prompt_description),
            checked = showStartupUpdatePrompt,
            onCheckedChange = { enabled ->
                UpdatePreferences.setShowStartupPrompt(context, enabled)
                showStartupUpdatePrompt = enabled
            },
        )
        Text(stringResource(R.string.settings_update_channel_title), fontWeight = FontWeight.SemiBold)
        Column {
          Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable(enabled = !isChecking) {
              onUpdateChannelChange(UpdateChannel.Stable)
              updateResult = null
          }) {
            RadioButton(
                selected = updateChannel == UpdateChannel.Stable,
                onClick = null,
                enabled = !isChecking,
            )
            Text(stringResource(R.string.settings_update_channel_stable))
          }
          Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable(enabled = !isChecking) {
              onUpdateChannelChange(UpdateChannel.Dev)
              updateResult = null
          }) {
            RadioButton(
                selected = updateChannel == UpdateChannel.Dev,
                onClick = null,
                enabled = !isChecking,
            )
            Text(stringResource(R.string.settings_update_channel_dev))
          }
        }
        Text(
            stringResource(
                if (updateChannel == UpdateChannel.Dev) R.string.settings_update_channel_dev_hint
                else R.string.settings_update_channel_stable_hint,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(
            onClick = {
                if (!isChecking) {
                    coroutineScope.launch {
                        isChecking = true
                        try {
                            updateResult = updateChecker.check(BuildConfig.VERSION_NAME, updateChannel)
                        } finally {
                            isChecking = false
                        }
                    }
                }
            },
            enabled = !isChecking,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                if (isChecking) {
                    stringResource(R.string.settings_update_checking)
                } else {
                    stringResource(R.string.settings_update_check_action)
                },
            )
        }
        SettingsUpdateResult(
            result = updateResult,
            onOpenRelease = ::openTrustedUrl,
        )
        ApkUpdateDownloadPane((updateResult as? ReleaseCheckResult.UpdateAvailable)?.release)
        OutlinedButton(
            onClick = { openTrustedUrl(RELEASES_URL) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.settings_update_open_all_releases))
        }
        if (browserError) {
            Text(
                text = stringResource(R.string.settings_browser_open_failed),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Text(
            text = stringResource(R.string.settings_update_install_steps),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun SettingsUpdateResult(
    result: ReleaseCheckResult?,
    onOpenRelease: (String) -> Unit,
) {
    when (result) {
        null -> Unit
        is ReleaseCheckResult.UpdateAvailable -> {
            Text(
                text = stringResource(
                    R.string.settings_update_available,
                    result.release.tagName,
                    releasePublishedDate(result.release),
                ),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            ReleaseMetadata(result.release, onOpenRelease)
            if (result.release.apkUrl == null) {
                Text(
                    text = stringResource(R.string.settings_update_missing_apk),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        is ReleaseCheckResult.UpToDate -> {
            Text(stringResource(R.string.settings_update_up_to_date, result.release.tagName))
            ReleaseMetadata(result.release, onOpenRelease)
        }
        is ReleaseCheckResult.LocalNewer -> {
            Text(stringResource(R.string.settings_update_local_newer, result.release.tagName))
            ReleaseMetadata(result.release, onOpenRelease)
        }
        ReleaseCheckResult.NoRelease -> Text(stringResource(R.string.settings_update_no_release))
        ReleaseCheckResult.RateLimited -> Text(stringResource(R.string.settings_update_rate_limited))
        is ReleaseCheckResult.CannotDetermineVersion -> Text(
            stringResource(R.string.settings_update_bad_version, result.tagName.ifBlank { "?" }),
            color = MaterialTheme.colorScheme.error,
        )
        ReleaseCheckResult.InvalidReleaseLinks -> Text(stringResource(R.string.settings_update_invalid_links), color = MaterialTheme.colorScheme.error)
        ReleaseCheckResult.NetworkFailure -> Text(stringResource(R.string.settings_update_network_failure), color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun ReleaseMetadata(
    release: com.componentvault.android.data.GitHubReleaseInfo,
    onOpenRelease: (String) -> Unit,
) {
    val notes = if (release.releaseNotes.isBlank()) {
        stringResource(R.string.settings_update_notes_empty)
    } else {
        release.releaseNotes
    }
    Text(
        text = stringResource(R.string.settings_update_release_date, releasePublishedDate(release)),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
        text = notes,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    TextButton(onClick = { onOpenRelease(release.pageUrl) }) {
        Text(stringResource(R.string.settings_update_open_release))
    }
}

@Composable
private fun releasePublishedDate(release: com.componentvault.android.data.GitHubReleaseInfo): String {
    val date = release.publishedAt.take(10)
    return if (date.isBlank()) stringResource(R.string.settings_update_date_unknown) else date
}

private const val PROJECT_URL = "https://github.com/Rafael-Ikaros/Component_Manager"
private const val OPEN_SOURCE_URL = "https://github.com/Rafael-Ikaros/Component_Manager/blob/master/LICENSE.txt"
private const val RELEASES_URL = "https://github.com/Rafael-Ikaros/Component_Manager/releases"

private fun androidx.compose.foundation.lazy.LazyListScope.settingsSectionDetailItems(
    section: SettingsSection,
    showSectionHeading: Boolean,
    strings: ComponentVaultStrings,
    serverUrl: String,
    onServerUrlChange: (String) -> Unit,
    externalServerUrl: String,
    onExternalServerUrlChange: (String) -> Unit,
    apiToken: String,
    onApiTokenChange: (String) -> Unit,
    autoSyncEnabled: Boolean,
    onAutoSyncChange: (Boolean) -> Unit,
    defaultImportLocation: String,
    onDefaultImportLocationChange: (String) -> Unit,
    defaultImportMinStockText: String,
    onDefaultImportMinStockChange: (String) -> Unit,
    rememberLastImportLocation: Boolean,
    onRememberLastImportLocationChange: (Boolean) -> Unit,
    syncAfterLocalChanges: Boolean,
    onSyncAfterLocalChangesChange: (Boolean) -> Unit,
    enableLocalAutoRecognition: Boolean,
    onEnableLocalAutoRecognitionChange: (Boolean) -> Unit,
    preferAggressiveAutoRecognition: Boolean,
    onPreferAggressiveAutoRecognitionChange: (Boolean) -> Unit,
    enableLocalImportLearning: Boolean,
    onEnableLocalImportLearningChange: (Boolean) -> Unit,
    enableServerJlcLookup: Boolean,
    onEnableServerJlcLookupChange: (Boolean) -> Unit,
    enablePublicJlcLookup: Boolean,
    onEnablePublicJlcLookupChange: (Boolean) -> Unit,
    ocrEngineMode: OcrEngineMode,
    onOcrEngineModeChange: (OcrEngineMode) -> Unit,
    appLanguage: AppLanguage,
    onAppLanguageChange: (AppLanguage) -> Unit,
    importLearningSummary: ImportLearningSummary,
    updateChannel: UpdateChannel,
    onUpdateChannelChange: (UpdateChannel) -> Unit,
    showToken: Boolean,
    onToggleToken: () -> Unit,
    isBusy: Boolean,
    syncRunning: Boolean,
    syncSummary: String,
    errorMessage: String?,
    onSave: () -> Boolean,
    hasUnsavedConnection: Boolean,
    onTestConnection: () -> Unit,
    onSyncNow: () -> Unit,
    onClearImportLearningMappings: () -> Unit,
) {
    when (section) {
        SettingsSection.Sync -> {
            item {
                ValueBlock(label = stringResource(R.string.sync_run_label), value = syncSummary)
                SectionPane(
                    title = strings.settings.connectionTitle.takeIf { showSectionHeading },
                    supporting = strings.settings.connectionSubtitle,
                ) {
                    OutlinedTextField(
                        value = serverUrl,
                        onValueChange = onServerUrlChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(strings.common.fieldServerUrl) },
                        placeholder = { Text(strings.settings.serverUrlPlaceholder) },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = externalServerUrl,
                        onValueChange = onExternalServerUrlChange,
                        modifier = Modifier.fillMaxWidth().testTag("external-server-url"),
                        label = { Text(stringResource(R.string.settings_external_server_url)) },
                        supportingText = { Text(stringResource(R.string.settings_external_server_help)) },
                        singleLine = true,
                    )
                }
            }
            item {
                val context = LocalContext.current
                var tokenCopied by remember(apiToken) { mutableStateOf(false) }
                SectionPane(
                    title = strings.settings.authTitle,
                    supporting = strings.settings.authSubtitle,
                ) {
                    OutlinedTextField(
                        value = apiToken,
                        onValueChange = {
                            tokenCopied = false
                            onApiTokenChange(it)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(strings.common.fieldApiToken) },
                        supportingText = { Text(stringResource(R.string.settings_api_token_help)) },
                        singleLine = true,
                        visualTransformation = if (showToken) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onToggleToken) {
                            Text(
                                if (showToken) {
                                    strings.common.actionHideToken
                                } else {
                                    strings.common.actionShowToken
                                },
                            )
                        }
                        TextButton(
                            enabled = apiToken.isNotBlank(),
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("API token", apiToken))
                                tokenCopied = true
                            },
                        ) {
                            Text(stringResource(R.string.settings_api_token_copy))
                        }
                    }
                    if (tokenCopied) {
                        Text(
                            text = stringResource(R.string.settings_api_token_copied),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
            item {
                SectionPane(
                    title = strings.settings.syncBehaviorTitle,
                    supporting = strings.settings.syncBehaviorSubtitle,
                ) {
                    SettingsToggleRow(
                        title = strings.settings.syncOnLaunch,
                        subtitle = strings.settings.syncOnLaunchDescription,
                        checked = autoSyncEnabled,
                        onCheckedChange = onAutoSyncChange,
                    )
                    SettingsToggleRow(
                        title = strings.settings.syncAfterWrites,
                        subtitle = strings.settings.syncAfterWritesDescription,
                        checked = syncAfterLocalChanges,
                        onCheckedChange = onSyncAfterLocalChangesChange,
                    )
                }
            }
            item {
                SectionPane(
                    title = strings.settings.summaryTitle,
                    supporting = strings.settings.summarySubtitle,
                ) {
                    if (!errorMessage.isNullOrBlank()) {
                        Text(
                            text = errorMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Text(
                        stringResource(if (hasUnsavedConnection) R.string.settings_sync_save_first else R.string.settings_sync_test_draft),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Button(
                            onClick = { onSave() },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(strings.common.actionSaveSettings)
                        }
                        OutlinedButton(
                            onClick = onTestConnection,
                            enabled = !isBusy,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(strings.common.actionTestConnection)
                        }
                    }
                    OutlinedButton(
                        onClick = onSyncNow,
                        enabled = syncRunning || (!isBusy && !hasUnsavedConnection),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (syncRunning) stringResource(R.string.sync_action_cancel) else strings.common.actionSyncNow)
                    }
                }
            }
        }

        SettingsSection.ImportAndOcr -> {
            item {
                SectionPane(
                    title = strings.settings.importPreferencesTitle.takeIf { showSectionHeading },
                    supporting = strings.settings.importPreferencesSubtitle,
                ) {
                    OutlinedTextField(
                        value = defaultImportLocation,
                        onValueChange = onDefaultImportLocationChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(strings.common.fieldLocation) },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = defaultImportMinStockText,
                        onValueChange = onDefaultImportMinStockChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(strings.common.fieldMinimumStock) },
                        singleLine = true,
                    )
                    SettingsToggleRow(
                        title = strings.settings.rememberLastImportLocation,
                        subtitle = strings.settings.rememberLastImportLocationDescription,
                        checked = rememberLastImportLocation,
                        onCheckedChange = onRememberLastImportLocationChange,
                    )
                    SettingsToggleRow(
                        title = strings.settings.localAutoRecognition,
                        subtitle = strings.settings.localAutoRecognitionDescription,
                        checked = enableLocalAutoRecognition,
                        onCheckedChange = onEnableLocalAutoRecognitionChange,
                    )
                    SettingsToggleRow(
                        title = strings.settings.aggressiveRecognition,
                        subtitle = strings.settings.aggressiveRecognitionDescription,
                        checked = preferAggressiveAutoRecognition,
                        onCheckedChange = onPreferAggressiveAutoRecognitionChange,
                    )
                    SettingsToggleRow(
                        title = strings.settings.localImportLearning,
                        subtitle = strings.settings.localImportLearningDescription,
                        checked = enableLocalImportLearning,
                        onCheckedChange = onEnableLocalImportLearningChange,
                    )
                    SettingsToggleRow(
                        title = androidx.compose.ui.res.stringResource(com.componentvault.android.R.string.settings_public_jlc_lookup),
                        subtitle = androidx.compose.ui.res.stringResource(com.componentvault.android.R.string.settings_public_jlc_lookup_description),
                        checked = enablePublicJlcLookup,
                        onCheckedChange = onEnablePublicJlcLookupChange,
                    )
                }
            }
            item {
                SectionPane(
                    title = strings.settings.ocrEngineLabel,
                    supporting = strings.settings.importPreferencesSubtitle,
                ) {
                    SettingsChoiceRow(
                        title = strings.settings.ocrEngineAuto,
                        subtitle = strings.settings.ocrEngineAutoDescription,
                        selected = ocrEngineMode == OcrEngineMode.Auto,
                        onClick = { onOcrEngineModeChange(OcrEngineMode.Auto) },
                    )
                    SettingsChoiceRow(
                        title = strings.settings.ocrEngineMlKit,
                        subtitle = strings.settings.ocrEngineMlKitDescription,
                        selected = ocrEngineMode == OcrEngineMode.MlKit,
                        onClick = { onOcrEngineModeChange(OcrEngineMode.MlKit) },
                    )
                }
            }
            item {
                SectionPane(
                    title = strings.settings.learnedMappingsCount,
                    supporting = strings.settings.localImportLearningDescription,
                ) {
                    ValueBlock(
                        label = strings.settings.learnedMappingsCount,
                        value = if (importLearningSummary.mappingCount > 0) {
                            importLearningSummary.mappingCount.toString()
                        } else {
                            strings.settings.noLearnedMappings
                        },
                    )
                    if (!errorMessage.isNullOrBlank()) {
                        Text(
                            text = errorMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Button(
                        onClick = { onSave() },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(strings.common.actionSaveSettings)
                    }
                    OutlinedButton(
                        onClick = onClearImportLearningMappings,
                        enabled = importLearningSummary.mappingCount > 0 && !isBusy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(strings.settings.clearLearnedMappingsAction)
                    }
                }
            }
        }

        SettingsSection.App -> {
            item {
                SectionPane(
                    title = strings.settings.languageTitle.takeIf { showSectionHeading },
                    supporting = strings.settings.languageSubtitle,
                ) {
                    SettingsChoiceRow(
                        title = strings.settings.languageChinese,
                        subtitle = strings.settings.languageSubtitle,
                        selected = appLanguage == AppLanguage.ZhCn,
                        onClick = { onAppLanguageChange(AppLanguage.ZhCn) },
                    )
                    SettingsChoiceRow(
                        title = strings.settings.languageEnglish,
                        subtitle = strings.settings.languageSubtitle,
                        selected = appLanguage == AppLanguage.English,
                        onClick = { onAppLanguageChange(AppLanguage.English) },
                    )
                    if (!errorMessage.isNullOrBlank()) {
                        Text(
                            text = errorMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Button(
                        onClick = { onSave() },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(strings.common.actionSaveSettings)
                    }
                }
            }
        }

        SettingsSection.About -> {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    SettingsAboutPane(
                        updateChannel = updateChannel,
                        onUpdateChannelChange = onUpdateChannelChange,
                        showHeading = showSectionHeading,
                    )
                }
            }
        }

        SettingsSection.Feedback -> {
            item { IssueFeedbackCard() }
        }

        SettingsSection.Appearance -> item { SettingsAppearancePane() }
    }
}

private fun AppearanceMode.label(): Int = when (this) {
    AppearanceMode.System -> R.string.appearance_system
    AppearanceMode.Light -> R.string.appearance_light
    AppearanceMode.Dark -> R.string.appearance_dark
}

private fun AppearanceAccent.label(): Int = when (this) {
    AppearanceAccent.Teal -> R.string.appearance_teal
    AppearanceAccent.Blue -> R.string.appearance_blue
    AppearanceAccent.Sand -> R.string.appearance_sand
}

@Composable
private fun SettingsAppearancePane() {
    val appearance = LocalAppearance.current
    val change = LocalOnAppearanceChange.current
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionPane(title = stringResource(R.string.appearance_theme)) {
            Column(Modifier.selectableGroup()) {
                AppearanceMode.entries.forEach { mode ->
                    AppearanceChoice(stringResource(mode.label()), appearance.mode == mode,
                        "appearance_mode_${mode.name}") { change(appearance.copy(mode = mode)) }
                }
            }
        }
        SectionPane(title = stringResource(R.string.appearance_accent)) {
            Column(Modifier.selectableGroup()) {
                AppearanceAccent.entries.forEach { accent ->
                    AppearanceChoice(stringResource(accent.label()), appearance.accent == accent,
                        "appearance_accent_${accent.name}") { change(appearance.copy(accent = accent)) }
                }
            }
        }
    }
}

@Composable
private fun AppearanceChoice(label: String, selected: Boolean, tag: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(tag)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun SettingsToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
    }
}

@Composable
private fun SettingsChoiceRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.RadioButton, onClick = onClick),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        RadioButton(
            selected = selected,
            onClick = null,
        )
    }
}

@Composable
private fun PhotoSyncSettings(configuration: SyncConfiguration) {
    val configured = configuration.serverBaseUrl.isNotBlank() && configuration.apiToken.isNotBlank()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val photos = remember(context) { com.componentvault.android.data.InventoryPhotoSync(context) }
    var summary by remember { mutableStateOf("正在读取照片状态…") }
    var running by remember { mutableStateOf(false) }
    var revision by remember { mutableStateOf(0) }
    androidx.compose.runtime.DisposableEffect(context) {
        val preferences = context.getSharedPreferences("photo-sync", android.content.Context.MODE_PRIVATE)
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> revision++ }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    androidx.compose.runtime.LaunchedEffect(configuration, revision) {
        summary = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { photos.summary(configured) }
        running = com.componentvault.android.data.InventoryPhotoSync.running.get()
    }
    ValueBlock(label = "照片同步", value = summary)
    OutlinedButton(enabled = !running, onClick = {
        scope.launch {
            summary = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { photos.clearDownloads(); photos.summary(configured) }.getOrElse { it.message ?: "缓存清理失败。" }
            }
        }
    }) { Text("清理下载的照片缓存") }
    Text("仅清理从服务器下载的副本，保留本机原始照片；下次同步可重新下载。", style = MaterialTheme.typography.bodySmall)
}
