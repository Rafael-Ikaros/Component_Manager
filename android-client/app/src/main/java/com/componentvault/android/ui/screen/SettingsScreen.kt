package com.componentvault.android.ui.screen

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
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
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.LocalTextStyle
import androidx.compose.ui.unit.sp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Surface
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.SolidColor
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
import com.componentvault.android.ui.theme.isDarkTheme
import com.componentvault.android.ui.theme.vaultAccentColors
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
                                onSelectSection(selectedSection?.parent)
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
            .padding(horizontal = 18.dp, vertical = 12.dp),
    ) {
        // Budget the content left after the shell, insets and page margins.
        val showTwoPanes = layoutMode.showsListDetail && maxWidth >= 700.dp
        androidx.compose.runtime.SideEffect { onSplitLayoutChanged(showTwoPanes) }
        BackHandler(enabled = !showTwoPanes && selectedSection != null) {
            onSelectSection(selectedSection?.parent)
        }
        val detail: @Composable (Modifier) -> Unit = { modifier ->
            sectionStateHolder.SaveableStateProvider(section.name) {
                LazyColumn(
                    modifier = modifier.then(pageEntranceMotion(section)).testTag("settings_detail_list"),
                    state = detailStates.getValue(section),
                    contentPadding = PaddingValues(bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (showTwoPanes && section.parent != null) {
                        item {
                            TextButton(onClick = { onSelectSection(section.parent) }) {
                                Text(stringResource(R.string.appearance_title))
                            }
                        }
                    }
                    settingsSectionDetailItems(
                        onSelectSection = onSelectSection,
                        section = section,
                        storageLocations = storageLocations,
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
                            selectedSection = section.parent ?: section,
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
            SettingsSection.Appearance -> stringResource(appearance.mode.label())
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
            title = section.title(strings),
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
        Box(Modifier.padding(bottom = 6.dp)) { SettingsLocalWarehouseSummary(localComponentCount) }
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
                    icon = R.drawable.v2_map_pin,
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
                    icon = R.drawable.v2_database,
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
                    icon = R.drawable.v2_tags,
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

private fun SettingsSection.icon(): Int = when (this) {
    SettingsSection.Appearance, SettingsSection.Theme -> R.drawable.v2_sun_moon
    SettingsSection.Accent -> R.drawable.v2_palette
    SettingsSection.ImportAndOcr -> R.drawable.v2_scan_text
    SettingsSection.App -> R.drawable.v2_languages
    SettingsSection.Sync -> R.drawable.v2_server
    SettingsSection.Feedback -> R.drawable.v2_message_square
    SettingsSection.About -> R.drawable.v2_info
}

@Composable
private fun SettingsLocalWarehouseSummary(localComponentCount: Int?) {
    val colors = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth().testTag("settings_local_warehouse"),
        shape = RoundedCornerShape(24.dp),
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
                    Icon(painterResource(R.drawable.v2_warehouse), contentDescription = null, modifier = Modifier.size(21.dp), tint = colors.primary)
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
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) { content() }
    }
}

@Composable
private fun SettingsRowDivider(compact: Boolean) {
    HorizontalDivider(Modifier.padding(start = if (compact) 38.dp else 51.dp, end = 16.dp),
        color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
internal fun SettingsSectionCard(
    title: String,
    supporting: String,
    icon: Int,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    summary: String? = null,
    summarySwatch: Color? = null,
    enabled: Boolean = true,
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
                .then(if (selected) Modifier.selectable(true, enabled = enabled, role = Role.Button, onClick = onClick)
                    else Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick))
                .padding(horizontal = if (compact) 12.dp else 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(if (compact) 10.dp else 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painterResource(icon),
                contentDescription = null,
                modifier = Modifier.size(if (compact) 18.dp else 21.dp),
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
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    summarySwatch?.let { Box(Modifier.size(16.dp).background(it, CircleShape)) }
                    Text(
                        summary,
                        modifier = Modifier.widthIn(max = if (compact) 64.dp else 96.dp),
                        style = MaterialTheme.typography.bodySmall,
                        fontSize = 12.sp,
                        color = supportingColor,
                        textAlign = androidx.compose.ui.text.style.TextAlign.End,
                    )
                }
            }
            Icon(
                painterResource(R.drawable.v2_chevron_right),
                contentDescription = null,
                modifier = Modifier.size(15.dp),
                tint = supportingColor,
            )
        }
    }
}

@Composable
private fun SettingsSyncStatusPane(syncConfiguration: SyncConfiguration) {
    val strings = vaultStrings()
    val never = stringResource(R.string.sync_never)
    Column {
        SettingsStatusRow(stringResource(R.string.sync_inventory_success_label), syncConfiguration.lastSyncedAt.ifBlank { never })
        SettingsStatusRow(strings.settings.deviceId, syncConfiguration.deviceId)
        SettingsStatusRow(stringResource(R.string.sync_bom_success_label), syncConfiguration.bomLastSyncedAt.ifBlank { never })
        SettingsStatusRow(strings.settings.endpoint, syncConfiguration.serverBaseUrl.ifBlank { strings.common.labelNotConfigured })
        if (syncConfiguration.lastSyncMessage.isNotBlank()) {
            SettingsStatusRow(strings.settings.lastResult, syncConfiguration.lastSyncMessage)
        }
        PhotoSyncSettings(syncConfiguration)
    }
}

@Composable
internal fun SettingsStatusRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 13.dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
            textAlign = androidx.compose.ui.text.style.TextAlign.End)
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
internal fun SettingsFormNote(message: String) {
    Text(
        message, Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(15.dp))
            .padding(horizontal = 15.dp, vertical = 13.dp),
        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 18.sp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun SettingsSecondaryButton(label: String, onClick: () -> Unit, modifier: Modifier, enabled: Boolean = true) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 48.dp),
        shape = RoundedCornerShape(15.dp),
        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
    ) { Text(label) }
}

@Composable
private fun SettingsDropdown(
    label: String,
    value: String,
    choices: @Composable (close: () -> Unit) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        Box {
            Surface(
                color = colors.surface, shape = RoundedCornerShape(13.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, colors.outlineVariant),
            ) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 49.dp)
                        .clickable(role = Role.Button) { expanded = true }.padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(value, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    Icon(painterResource(R.drawable.v2_chevron_down), contentDescription = null, modifier = Modifier.size(16.dp))
                }
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, modifier = Modifier.heightIn(max = 320.dp)) {
                choices { expanded = false }
            }
        }
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

    SettingsFormSection(
        title = strings.settings.aboutTitle.takeIf { showHeading },
    ) {
        Column {
            SettingsStatusRow(stringResource(R.string.settings_about_app_name), stringResource(R.string.app_name))
            SettingsStatusRow(strings.settings.appVersion, stringResource(
                R.string.settings_about_version_format, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE,
            ))
            SettingsStatusRow(stringResource(R.string.settings_about_author), "Rafael-Ikaros")
            SettingsStatusRow(stringResource(R.string.settings_about_open_source_label),
                stringResource(R.string.settings_about_open_source_value))
        }
        SettingsFormNote(strings.settings.aboutBody)
    }
    SettingsFormSection(title = stringResource(R.string.settings_update_channel_title)) {
        Column(Modifier.selectableGroup()) {
            UpdateChannel.entries.forEach { channel ->
                val stable = channel == UpdateChannel.Stable
                AppearanceChoice(
                    label = stringResource(if (stable) R.string.settings_update_channel_stable else R.string.settings_update_channel_dev),
                    supporting = stringResource(if (stable) R.string.settings_update_channel_stable_hint else R.string.settings_update_channel_dev_hint),
                    selected = updateChannel == channel,
                    tag = "settings_update_channel_${channel.name}",
                    enabled = !isChecking,
                    leading = {
                        Icon(painterResource(if (stable) R.drawable.v2_check else R.drawable.v2_flask_conical),
                            contentDescription = null, modifier = Modifier.size(21.dp))
                    },
                    onClick = {
                        onUpdateChannelChange(channel)
                        updateResult = null
                    },
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                shape = RoundedCornerShape(15.dp),
            ) {
                Text(stringResource(if (isChecking) R.string.settings_update_checking else R.string.settings_update_check_action))
            }
            FilledTonalButton(
                onClick = { openTrustedUrl(PROJECT_URL) },
                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                shape = RoundedCornerShape(15.dp),
            ) { Text(stringResource(R.string.settings_about_open_project)) }
        }
        SettingsUpdateResult(
            result = updateResult,
            onOpenRelease = ::openTrustedUrl,
        )
        ApkUpdateDownloadPane((updateResult as? ReleaseCheckResult.UpdateAvailable)?.release)
        SettingsToggleRow(
            title = stringResource(R.string.settings_update_startup_prompt),
            subtitle = stringResource(R.string.settings_update_startup_prompt_description),
            checked = showStartupUpdatePrompt,
            onCheckedChange = { enabled ->
                UpdatePreferences.setShowStartupPrompt(context, enabled)
                showStartupUpdatePrompt = enabled
            },
            compact = true,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { openTrustedUrl(OPEN_SOURCE_URL) }, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.settings_about_open_source))
            }
            TextButton(onClick = { openTrustedUrl(RELEASES_URL) }, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.settings_update_open_all_releases))
            }
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
    onSelectSection: (SettingsSection?) -> Unit,
    section: SettingsSection,
    storageLocations: List<StorageLocationRecord>,
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
                SettingsFormSection(title = strings.settings.connectionTitle.takeIf { showSectionHeading }) {
                    SettingsFormNote(syncSummary.ifBlank { strings.settings.connectionSubtitle })
                    SettingsTextField(
                        value = serverUrl,
                        onValueChange = onServerUrlChange,
                        modifier = Modifier.fillMaxWidth().testTag("primary-server-url"),
                        label = { Text(strings.common.fieldServerUrl) },
                        placeholder = { Text(strings.settings.serverUrlPlaceholder) },
                        singleLine = true,
                    )
                    SettingsTextField(
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
                SettingsFormSection {
                    SettingsTextField(
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
                SettingsFormSection {
                    SettingsToggleRow(
                        compact = true,
                        title = strings.settings.syncOnLaunch,
                        subtitle = strings.settings.syncOnLaunchDescription,
                        checked = autoSyncEnabled,
                        onCheckedChange = onAutoSyncChange,
                    )
                    SettingsToggleRow(
                        compact = true,
                        title = strings.settings.syncAfterWrites,
                        subtitle = strings.settings.syncAfterWritesDescription,
                        checked = syncAfterLocalChanges,
                        onCheckedChange = onSyncAfterLocalChangesChange,
                    )
                }
            }
            item {
                SettingsFormSection {
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
                        SettingsSecondaryButton(
                            label = strings.common.actionTestConnection,
                            onClick = onTestConnection,
                            enabled = !isBusy,
                            modifier = Modifier.weight(1f),
                        )
                        Button(
                            onClick = { onSave() },
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                            shape = RoundedCornerShape(15.dp),
                        ) {
                            Text(strings.common.actionSaveSettings)
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    SettingsSecondaryButton(
                        label = if (syncRunning) stringResource(R.string.sync_action_cancel) else strings.common.actionSyncNow,
                        onClick = onSyncNow,
                        enabled = syncRunning || (!isBusy && !hasUnsavedConnection),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        SettingsSection.ImportAndOcr -> {
            item {
                SettingsFormSection(title = strings.settings.importPreferencesTitle.takeIf { showSectionHeading }) {
                    var manualLocation by rememberSaveable {
                        mutableStateOf(defaultImportLocation.isNotBlank() && storageLocations.none {
                            it.id == defaultImportLocation || it.name == defaultImportLocation
                        })
                    }
                    val noLocation = stringResource(R.string.v2_settings_no_default_location)
                    SettingsDropdown(
                        label = stringResource(R.string.v2_settings_default_location),
                        value = if (manualLocation) stringResource(R.string.v2_settings_manual_location) else
                            storageLocations.firstOrNull { it.id == defaultImportLocation || it.name == defaultImportLocation }
                                ?.name ?: defaultImportLocation.ifBlank { noLocation },
                    ) { close ->
                        DropdownMenuItem(text = { Text(noLocation) }, onClick = {
                            manualLocation = false
                            onDefaultImportLocationChange("")
                            close()
                        })
                        storageLocations.forEach { location ->
                            DropdownMenuItem(text = { Text(location.name) }, onClick = {
                                manualLocation = false
                                onDefaultImportLocationChange(location.id)
                                close()
                            })
                        }
                        DropdownMenuItem(text = { Text(stringResource(R.string.v2_settings_manual_location)) },
                            onClick = { manualLocation = true; close() })
                    }
                    if (manualLocation) {
                        SettingsTextField(
                            value = defaultImportLocation,
                            onValueChange = onDefaultImportLocationChange,
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(strings.common.fieldLocation) },
                            singleLine = true,
                        )
                    }
                    SettingsTextField(
                        value = defaultImportMinStockText,
                        onValueChange = onDefaultImportMinStockChange,
                        modifier = Modifier.fillMaxWidth().testTag("import-min-stock"),
                        label = { Text(strings.common.fieldMinimumStock) },
                        singleLine = true,
                    )
                    SettingsToggleRow(
                        compact = true,
                        title = strings.settings.rememberLastImportLocation,
                        subtitle = strings.settings.rememberLastImportLocationDescription,
                        checked = rememberLastImportLocation,
                        onCheckedChange = onRememberLastImportLocationChange,
                    )
                    SettingsToggleRow(
                        compact = true,
                        title = strings.settings.localAutoRecognition,
                        subtitle = strings.settings.localAutoRecognitionDescription,
                        checked = enableLocalAutoRecognition,
                        onCheckedChange = onEnableLocalAutoRecognitionChange,
                    )
                    SettingsToggleRow(
                        compact = true,
                        title = strings.settings.aggressiveRecognition,
                        subtitle = strings.settings.aggressiveRecognitionDescription,
                        checked = preferAggressiveAutoRecognition,
                        onCheckedChange = onPreferAggressiveAutoRecognitionChange,
                    )
                    SettingsToggleRow(
                        compact = true,
                        title = strings.settings.localImportLearning,
                        subtitle = strings.settings.localImportLearningDescription,
                        checked = enableLocalImportLearning,
                        onCheckedChange = onEnableLocalImportLearningChange,
                    )
                    SettingsToggleRow(
                        compact = true,
                        title = androidx.compose.ui.res.stringResource(com.componentvault.android.R.string.settings_public_jlc_lookup),
                        subtitle = androidx.compose.ui.res.stringResource(com.componentvault.android.R.string.settings_public_jlc_lookup_description),
                        checked = enablePublicJlcLookup,
                        onCheckedChange = onEnablePublicJlcLookupChange,
                    )
                }
            }
            item {
                SettingsFormSection {
                    SettingsDropdown(
                        label = strings.settings.ocrEngineLabel,
                        value = if (ocrEngineMode == OcrEngineMode.Auto) strings.settings.ocrEngineAuto else strings.settings.ocrEngineMlKit,
                    ) { close ->
                        DropdownMenuItem(
                            text = { Text(strings.settings.ocrEngineAuto) },
                            onClick = { onOcrEngineModeChange(OcrEngineMode.Auto); close() },
                        )
                        DropdownMenuItem(
                            text = { Text(strings.settings.ocrEngineMlKit) },
                            onClick = { onOcrEngineModeChange(OcrEngineMode.MlKit); close() },
                        )
                    }
                    if (!errorMessage.isNullOrBlank()) {
                        Text(
                            text = errorMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Button(
                        onClick = { onSave() },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        shape = RoundedCornerShape(15.dp),
                    ) {
                        Text(strings.common.actionSaveSettings)
                    }
                    SettingsStatusRow(
                        strings.settings.learnedMappingsCount,
                        if (importLearningSummary.mappingCount > 0) importLearningSummary.mappingCount.toString()
                        else strings.settings.noLearnedMappings,
                    )
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
                SettingsFormSection(
                    title = strings.settings.languageTitle.takeIf { showSectionHeading },
                ) {
                    Column(Modifier.selectableGroup()) {
                        AppLanguage.entries.forEach { language ->
                            val label = when (language) {
                                AppLanguage.ZhCn -> stringResource(R.string.v2_settings_language_zh)
                                AppLanguage.English -> stringResource(R.string.v2_settings_language_en)
                            }
                            AppearanceChoice(label, appLanguage == language, "settings_language_${language.name}",
                                supporting = stringResource(if (language == AppLanguage.ZhCn)
                                    R.string.v2_settings_language_zh_hint else R.string.v2_settings_language_en_hint),
                                leading = { Icon(painterResource(R.drawable.v2_languages), contentDescription = null, modifier = Modifier.size(20.dp)) },
                                onClick = { onAppLanguageChange(language) },
                            )
                        }
                    }
                    SettingsFormNote(stringResource(R.string.v2_settings_language_note))
                    if (!errorMessage.isNullOrBlank()) {
                        Text(
                            text = errorMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Button(
                        onClick = { onSave() },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        shape = RoundedCornerShape(15.dp),
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

        SettingsSection.Appearance -> item { SettingsAppearancePane { onSelectSection(it) } }
        SettingsSection.Theme -> item { ThemeChoices() }
        SettingsSection.Accent -> item { AccentChoices() }
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
private fun SettingsAppearancePane(onSelectSection: (SettingsSection) -> Unit) {
    val appearance = LocalAppearance.current
    val strings = vaultStrings()
    val darkTheme = appearance.isDarkTheme()
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            SettingsSectionCard(
                title = SettingsSection.Theme.title(strings),
                supporting = SettingsSection.Theme.listSupporting(),
                icon = SettingsSection.Theme.icon(),
                selected = false,
                summary = stringResource(appearance.mode.label()),
                onClick = { onSelectSection(SettingsSection.Theme) },
                modifier = Modifier.testTag("appearance_open_theme"),
            )
            SettingsRowDivider(compact = false)
            SettingsSectionCard(
                title = SettingsSection.Accent.title(strings),
                supporting = SettingsSection.Accent.listSupporting(),
                icon = SettingsSection.Accent.icon(),
                selected = false,
                summary = stringResource(appearance.accent.label()),
                summarySwatch = vaultAccentColors(appearance.accent, darkTheme).primary,
                onClick = { onSelectSection(SettingsSection.Accent) },
                modifier = Modifier.testTag("appearance_open_accent"),
            )
        }
        Text(
            text = stringResource(R.string.appearance_stock_color_note),
            modifier = Modifier.fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(15.dp))
                .padding(horizontal = 15.dp, vertical = 13.dp),
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 18.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ThemeChoices() {
    val appearance = LocalAppearance.current
    val change = LocalOnAppearanceChange.current
    Column(Modifier.selectableGroup()) {
        AppearanceMode.entries.forEach { mode ->
            val icon = when (mode) {
                AppearanceMode.System -> R.drawable.v2_sun_moon
                AppearanceMode.Light -> R.drawable.v2_sun
                AppearanceMode.Dark -> R.drawable.v2_moon
            }
            val description = when (mode) {
                AppearanceMode.System -> R.string.appearance_system_description
                AppearanceMode.Light -> R.string.appearance_light_description
                AppearanceMode.Dark -> R.string.appearance_dark_description
            }
            AppearanceChoice(stringResource(mode.label()), appearance.mode == mode, "appearance_mode_${mode.name}",
                supporting = stringResource(description),
                leading = { Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(20.dp)) },
                onClick = { change(appearance.copy(mode = mode)) },
            )
        }
    }
}

@Composable
private fun AccentChoices() {
    val appearance = LocalAppearance.current
    val change = LocalOnAppearanceChange.current
    val darkTheme = appearance.isDarkTheme()
    Column(Modifier.selectableGroup()) {
        AppearanceAccent.entries.forEach { accent ->
            val color = vaultAccentColors(accent, darkTheme).primary
            val description = when (accent) {
                AppearanceAccent.Teal -> R.string.appearance_teal_description
                AppearanceAccent.Blue -> R.string.appearance_blue_description
                AppearanceAccent.Sand -> R.string.appearance_sand_description
            }
            AppearanceChoice(stringResource(accent.label()), appearance.accent == accent, "appearance_accent_${accent.name}",
                supporting = stringResource(description),
                leading = { Box(Modifier.size(27.dp).background(color, CircleShape)) },
                onClick = { change(appearance.copy(accent = accent)) },
            )
        }
    }
}

@Composable
private fun AppearanceChoice(
    label: String,
    selected: Boolean,
    tag: String,
    supporting: String? = null,
    enabled: Boolean = true,
    leading: @Composable () -> Unit,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    androidx.compose.material3.Surface(
        shape = RoundedCornerShape(15.dp),
        color = if (selected) colors.primaryContainer else colors.background,
        contentColor = if (selected) colors.primary else colors.onSurface,
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 65.dp).testTag(tag)
                .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            leading()
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(label, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp, lineHeight = 22.5.sp),
                    fontWeight = FontWeight.Medium)
                supporting?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 18.sp),
                        color = colors.onSurfaceVariant)
                }
            }
            if (selected) Icon(painterResource(R.drawable.v2_check), contentDescription = null, modifier = Modifier.size(21.dp))
        }
    }
}

@Composable
internal fun SettingsFormSection(
    title: String? = null,
    supporting: String? = null,
    content: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        title?.let { Text(it, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium) }
        supporting?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        content()
    }
}

@Composable
internal fun SettingsTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    placeholder: (@Composable () -> Unit)? = null,
    supportingText: (@Composable () -> Unit)? = null,
    singleLine: Boolean = false,
    minLines: Int = 1,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    isError: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(13.dp)
    val labelStyle = MaterialTheme.typography.bodySmall.copy(color = colors.onSurfaceVariant)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        enabled = enabled,
        readOnly = readOnly,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface),
        singleLine = singleLine,
        minLines = minLines,
        visualTransformation = visualTransformation,
        interactionSource = interaction,
        cursorBrush = SolidColor(colors.primary),
        decorationBox = { input ->
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                CompositionLocalProvider(LocalTextStyle provides labelStyle) { label() }
                Box(
                    Modifier.fillMaxWidth()
                        .heightIn(min = if (minLines > 1) 92.dp else 49.dp)
                        .background(colors.surface, shape)
                        .border(1.dp, when {
                            isError -> colors.error
                            focused -> colors.primary
                            else -> colors.outlineVariant
                        }, shape)
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                    contentAlignment = if (minLines > 1) Alignment.TopStart else Alignment.CenterStart,
                ) {
                    if (value.isEmpty() && placeholder != null) {
                        CompositionLocalProvider(LocalTextStyle provides labelStyle) { placeholder() }
                    }
                    input()
                }
                if (supportingText != null) {
                    CompositionLocalProvider(
                        LocalTextStyle provides labelStyle.copy(fontSize = 12.sp, lineHeight = 18.sp),
                    ) { supportingText() }
                }
            }
        },
    )
}

@Composable
private fun SettingsToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    compact: Boolean = false,
) {
    Row(
        modifier = Modifier.fillMaxWidth().then(
            if (compact) Modifier.heightIn(min = 48.dp)
                .toggleable(checked, role = Role.Checkbox, onValueChange = onCheckedChange)
            else Modifier,
        ),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                text = title,
                style = if (compact) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (compact) {
            Checkbox(checked = checked, onCheckedChange = null)
        } else {
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
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
