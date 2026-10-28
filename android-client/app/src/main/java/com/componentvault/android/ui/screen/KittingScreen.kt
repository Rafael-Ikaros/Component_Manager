package com.componentvault.android.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.componentvault.android.data.bom.*
import com.componentvault.android.model.ComponentRecord
import java.io.File
import java.util.UUID
import kotlinx.coroutines.launch

internal data class KittingRow(val key: String, val label: String, val componentId: String?, val quantity: String)

/** A draft is retained with the same release ID across navigation and activity recreation. */
internal class KittingSession : ViewModel() {
    var rows by mutableStateOf<List<KittingRow>>(emptyList())
    var projectName by mutableStateOf("配套件")
    var query by mutableStateOf("")
    var preview by mutableStateOf<BomReleasePreview?>(null)
    var message by mutableStateOf("")
    var busy by mutableStateOf(false)
    var applied by mutableStateOf(false)
    var releaseId = UUID.randomUUID().toString()
    var batchId = UUID.randomUUID().toString()
    var generation = 0L
    fun invalidate() { generation++; preview = null; message = "" }
    fun reset() {
        invalidate(); rows = emptyList(); projectName = "配套件"; query = ""; applied = false
        releaseId = UUID.randomUUID().toString(); batchId = UUID.randomUUID().toString()
    }
    fun add(component: ComponentRecord) {
        invalidate()
        val existing = rows.firstOrNull { it.componentId == component.id }
        rows = if (existing == null) rows + KittingRow("stock:${component.id}", "${component.sku} · ${component.name}", component.id, "1")
        else rows.map { if (it.key == existing.key) it.copy(quantity = ((it.quantity.toIntOrNull() ?: 0).toLong() + 1).coerceAtMost(Int.MAX_VALUE.toLong()).toString()) else it }
    }
}

@Composable
internal fun KittingScreen(inventoryViewModel: InventoryViewModel, onDismiss: () -> Unit) {
    val session: KittingSession = viewModel()
    val context = LocalContext.current.applicationContext
    val presetStore = remember(context) { BomPresetStore(File(context.filesDir, "bom-presets.json")) }
    var presets by remember { mutableStateOf<List<BomPreset>>(emptyList()) }
    var showPresets by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    var pendingPreset by remember { mutableStateOf<BomPreset?>(null) }
    var replacementKey by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val components = inventoryViewModel.kittingComponents()
    LaunchedEffect(presetStore) {
        runCatching { presetStore.list() }.onSuccess { presets = it }.onFailure { session.message = it.message.orEmpty() }
    }
    fun locationName(id: String): String = inventoryViewModel.uiState.inventory.storageLocations.firstOrNull { it.id == id }?.name ?: id
    fun loadPreset(preset: BomPreset) {
        showPresets = false; replacementKey = null; session.busy = true
        val token = ++session.generation
        inventoryViewModel.previewBomPreset(preset.parsed, preset.selections, emptyMap(), preset.excludedKeys) { result ->
            session.busy = false
            if (token == session.generation) result.onSuccess { preview ->
                session.projectName = preset.parsed.projectName
                session.rows = preview.matchingLines.map { line -> KittingRow(line.requirement.identity.canonicalKey,
                    listOfNotNull(line.requirement.sku, line.requirement.name, line.requirement.model).joinToString(" · ").ifBlank { line.requirement.identity.canonicalKey },
                    line.componentId, line.requirement.requiredQuantity.toString()) }
                session.preview = null
                session.message = "预设已带入，跳过项未加入。请修复失效匹配并复核数量和库位。"
            }.onFailure { session.message = it.message.orEmpty() }
        }
    }
    fun preview() {
        val quantities = session.rows.map { it.quantity.toIntOrNull() }
        if (session.rows.isEmpty() || quantities.any { it == null || it <= 0 } || session.rows.any { it.componentId == null }) {
            session.message = "请为每项选择库存元件并填写正整数数量。"; return
        }
        val sheet = BomSheet("配套件", 0)
        val parsed = BomParseResult(session.projectName.trim().ifBlank { "配套件" }, 1, listOf(sheet), sheet,
            session.rows.mapIndexed { index, row ->
                val component = components.firstOrNull { it.id == row.componentId }
                BomRequirement(BomRequirementIdentity(row.key), component?.sku, row.label, null, component?.packageName,
                    requireNotNull(quantities[index]), requireNotNull(quantities[index]), emptyList())
            }, "")
        session.busy = true
        val generation = session.generation
        inventoryViewModel.previewBomPreset(parsed, session.rows.associate { it.key to it.componentId.orEmpty() }, emptyMap(), emptySet()) { result ->
            session.busy = false
            if (generation == session.generation) result.onSuccess {
                session.preview = it
                session.message = if (it.canCommit) "请复核每项数量和库位，再确认扣减。" else "存在缺料、失效匹配或库位不足；请调整后重新复核。"
            }.onFailure { session.message = it.message ?: "无法生成配套件预览。" }
        }
    }
    SecondaryPageScaffold(title = "配套件", onBack = onDismiss, navigationEnabled = !session.busy) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding().padding(horizontal = 16.dp), state = listState, verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            item { Text("从库存逐项选料，或从 BOM 预设带入。复核后确认一次扣减库存。", style = MaterialTheme.typography.bodyMedium) }
            item { OutlinedTextField(session.projectName, { session.projectName = it; session.invalidate() }, label = { Text("配套件名称") }, enabled = !session.busy && !session.applied, modifier = Modifier.fillMaxWidth()) }
            item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton({ showPresets = true }, enabled = !session.busy && !session.applied) { Text("从 BOM 预设带入") }
                TextButton({ replacementKey = null; session.reset() }, enabled = !session.busy) { Text("新建 / 清空") }
            } }
            if (!session.applied) {
                item(key = "kitting-search") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        val replacing = session.rows.firstOrNull { it.key == replacementKey }
                        if (replacing != null) {
                            Text("正在为 ${replacing.label} 重新选料。搜索并点选库存元件，即可替换此项。", style = MaterialTheme.typography.bodyMedium)
                            TextButton({ replacementKey = null; session.query = "" }, enabled = !session.busy) { Text("取消重新选料") }
                        }
                        OutlinedTextField(session.query, { session.query = it },
                            label = { Text(if (replacing == null) "搜索库存并添加" else "搜索库存以修复匹配") },
                            placeholder = { Text("输入 SKU、名称或封装") },
                            enabled = !session.busy, modifier = Modifier.fillMaxWidth())
                    }
                }
                val matches = if (session.query.isBlank()) emptyList() else components.filter {
                    it.sku.contains(session.query, true) || it.name.contains(session.query, true) || it.packageName.contains(session.query, true)
                }.take(30)
                items(matches, key = { "search:${it.id}" }) { component ->
                    ListItem(headlineContent = { Text("${component.sku} · ${component.name}") }, supportingContent = { Text("库存 ${component.quantity} · ${locationName(component.location)}") },
                        modifier = Modifier.clickable(enabled = !session.busy) {
                            val key = replacementKey
                            if (key == null) session.add(component) else {
                                session.invalidate()
                                session.rows = session.rows.map { if (it.key == key) it.copy(componentId = component.id, label = "${component.sku} · ${component.name}") else it }
                                replacementKey = null
                            }
                            session.query = ""
                        })
                }
                if (session.query.isNotBlank() && matches.isEmpty()) item { Text("没有匹配的库存元件。") }
            }
            if (session.rows.isEmpty()) item { Text("尚未选料。搜索 SKU、名称或封装，点选库存元件加入清单。") }
            items(session.rows, key = { it.key }) { row ->
                Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(row.label, style = MaterialTheme.typography.titleMedium)
                    val component = components.firstOrNull { it.id == row.componentId }
                    Text(if (component == null) "未匹配或原元件已删除。请点击重新选料修复。" else "库存 ${component.quantity} · ${locationName(component.location)}",
                        color = if (component == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                    QuantityInputField(row.quantity, { value -> session.invalidate(); session.rows = session.rows.map { if (it.key == row.key) it.copy(quantity = value) else it } },
                        label = "扣减数量", enabled = !session.busy && !session.applied, modifier = Modifier.fillMaxWidth())
                    if (!session.applied) Row {
                        TextButton({
                            replacementKey = row.key
                            session.query = component?.sku.orEmpty()
                            scope.launch { listState.animateScrollToItem(3) }
                        }, enabled = !session.busy) { Text("重新选料") }
                        TextButton({ session.invalidate(); session.rows = session.rows.filterNot { it.key == row.key }; if (replacementKey == row.key) replacementKey = null }, enabled = !session.busy) { Text("移除") }
                    }
                } }
            }
            item { Button(::preview, enabled = !session.busy && !session.applied && session.rows.isNotEmpty(), modifier = Modifier.fillMaxWidth()) { Text("复核数量和库位") } }
            session.preview?.let { preview ->
                item { Text("库位扣减计划", style = MaterialTheme.typography.titleMedium) }
                items(preview.lines, key = { "plan:${it.requirement.identity.canonicalKey}" }) { line ->
                    Column(Modifier.fillMaxWidth()) {
                        Text("${line.componentSku ?: line.requirement.name.orEmpty()}：${line.requirement.requiredQuantity}")
                        line.allocationPlan.forEach { plan ->
                            val name = inventoryViewModel.uiState.inventory.storageLocations.firstOrNull { it.id == plan.locationId }?.name ?: plan.locationId
                            Text("$name：${plan.quantity}", style = MaterialTheme.typography.bodyMedium)
                        }
                        if (line.allocationPlan.isEmpty()) Text("无法分配库位 / 库存不足", color = MaterialTheme.colorScheme.error)
                    }
                }
                item { Button({ confirm = true }, enabled = preview.canCommit && !session.busy && !session.applied, modifier = Modifier.fillMaxWidth()) { Text("确认配套并扣减库存") } }
            }
            if (session.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (session.message.isNotBlank()) item { Text(session.message) }
        }
    }
    if (showPresets) AlertDialog(onDismissRequest = { showPresets = false }, title = { Text("选择 BOM 预设") }, text = {
        LazyColumn { if (presets.isEmpty()) item { Text("暂无预设。请先在 BOM 导入页保存预设，或同步其他设备的预设。") }
            items(presets, key = { it.id }) { preset -> TextButton({
                if (session.rows.isEmpty()) loadPreset(preset) else { showPresets = false; pendingPreset = preset }
            }, enabled = !session.busy) { Text(preset.name) } }
        }
    }, confirmButton = { TextButton({ showPresets = false }) { Text("取消") } })
    pendingPreset?.let { preset -> AlertDialog(onDismissRequest = { pendingPreset = null }, title = { Text("替换当前配套件清单？") },
        text = { Text("将使用 ${preset.name} 替换当前清单，尚未扣减库存。") },
        confirmButton = { TextButton({ pendingPreset = null; loadPreset(preset) }, enabled = !session.busy) { Text("替换清单") } },
        dismissButton = { TextButton({ pendingPreset = null }) { Text("取消") } }) }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("确认扣减库存？") }, text = {
        LazyColumn(Modifier.heightIn(max = 360.dp)) {
            item { Text("将一次扣减以下数量，并按已复核的库位计划出库：") }
            items(session.preview?.lines.orEmpty()) { Text("${it.componentSku}：${it.requirement.requiredQuantity}") }
        }
    }, confirmButton = { TextButton({
        confirm = false
        val preview = session.preview ?: return@TextButton
        session.busy = true
        inventoryViewModel.commitBomRelease(preview, session.releaseId, session.batchId) {
            session.busy = false
            session.applied = it.outcome == BomReleaseOutcome.APPLIED || it.outcome == BomReleaseOutcome.ALREADY_APPLIED
            session.message = it.message
            if (!session.applied) session.preview = null
        }
    }) { Text("确认扣减") } }, dismissButton = { TextButton({ confirm = false }) { Text("取消") } })
}
