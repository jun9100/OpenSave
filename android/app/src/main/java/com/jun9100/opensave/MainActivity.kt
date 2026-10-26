package com.jun9100.opensave

import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.SettingsBackupRestore
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.text.DateFormat
import java.util.Date

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            OpenSaveTheme {
                OpenSaveScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OpenSaveScreen(sourceViewModel: SourceViewModel = viewModel()) {
    val state by sourceViewModel.uiState.collectAsStateWithLifecycle()
    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
        onResult = { uri -> uri?.let(sourceViewModel::onTreeSelected) },
    )

    state.pendingGameBackup?.let { entry ->
        val game = entry.game
        AlertDialog(
            onDismissRequest = sourceViewModel::cancelGameBackup,
            title = { Text("确认备份 ${game.presentation.primaryName}") },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("来源：${entry.sourceLabel}")
                    Text("访问：${sourceAccessLabel(entry.sourceAccess)}")
                    Text("已识别位置 ${game.saves.count { !it.candidate }} 个；候选位置 ${game.candidateCount} 个。")
                    Text("候选位置只有在你明确确认后才会写入快照。")
                    game.saves.forEach { save ->
                        Text(
                            "${if (save.candidate) "候选" else "已识别"} · ${save.relativePath}",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (save.candidate) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = sourceViewModel::cancelGameBackup) { Text("取消") }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(
                        onClick = { sourceViewModel.confirmGameBackup(includeCandidates = false) },
                        enabled = game.saves.any { !it.candidate },
                    ) { Text("只备份已识别") }
                    Button(onClick = { sourceViewModel.confirmGameBackup(includeCandidates = true) }) {
                        Text("包含候选")
                    }
                }
            },
        )
    }

    state.pendingRestore?.let { request ->
        val gameRestore = request.game != null
        AlertDialog(
            onDismissRequest = sourceViewModel::cancelRestore,
            title = { Text(if (gameRestore) "恢复游戏存档" else "恢复整份存档快照") },
            text = {
                Text(
                    if (gameRestore) {
                        "将恢复 ${request.game?.presentation?.primaryName}。程序会验证当前扫描到的游戏身份和快照路径，只替换 ZIP 清单所列位置，并先把这些位置的当前内容保存为新的安全快照。"
                    } else if (state.storageMode == StorageMode.ROOT) {
                        "此操作会替换 Root 来源目录内容。请确认路径为专用存档目录。"
                    } else {
                        "此操作会删除授权目录内现有内容并写入快照。请确认选择的是专用存档目录。"
                    },
                )
            },
            confirmButton = {
                Button(onClick = sourceViewModel::confirmRestore) { Text("确认恢复") }
            },
            dismissButton = {
                TextButton(onClick = sourceViewModel::cancelRestore) { Text("取消") }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("OpenSave")
                        Text(
                            text = if (state.page == OpenSavePage.LIBRARY) "游戏库" else "备份与设置",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = if (state.page == OpenSavePage.LIBRARY) {
                            sourceViewModel::scanSource
                        } else {
                            sourceViewModel::refreshPersistedSource
                        },
                        enabled = !state.busy,
                    ) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "刷新")
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = state.page == OpenSavePage.LIBRARY,
                    onClick = { sourceViewModel.showPage(OpenSavePage.LIBRARY) },
                    icon = { Icon(Icons.Outlined.SportsEsports, contentDescription = null) },
                    label = { Text("游戏库") },
                )
                NavigationBarItem(
                    selected = state.page == OpenSavePage.MANAGEMENT,
                    onClick = { sourceViewModel.showPage(OpenSavePage.MANAGEMENT) },
                    icon = { Icon(Icons.Outlined.SettingsBackupRestore, contentDescription = null) },
                    label = { Text("备份与设置") },
                )
            }
        },
    ) { padding ->
        when (state.page) {
            OpenSavePage.LIBRARY -> GameLibraryPage(
                state = state,
                viewModel = sourceViewModel,
                modifier = Modifier.fillMaxSize().padding(padding),
                onOpenManagement = { sourceViewModel.showPage(OpenSavePage.MANAGEMENT) },
            )
            OpenSavePage.MANAGEMENT -> SourceManagementPage(
                state = state,
                viewModel = sourceViewModel,
                onPickFolder = { folderPicker.launch(null) },
                modifier = Modifier.fillMaxSize().padding(padding),
            )
        }
    }
}

@Composable
private fun GameLibraryPage(
    state: SourceUiState,
    viewModel: SourceViewModel,
    modifier: Modifier = Modifier,
    onOpenManagement: () -> Unit,
) {
    val games = remember(state.sourceScans) { GameLibrary.sourceGames(state.sourceScans) }
    val batchEligibleKeys = GameLibrary.batchBackupEligibleEntries(games).map { it.libraryKey }
    var selectedGameKeys by remember(state.scanRevision, batchEligibleKeys) {
        mutableStateOf(batchEligibleKeys.toSet())
    }
    val typeOptions = remember(games) {
        listOf("all" to "全部") + games
            .map(SourceGameEntry::saveType)
            .distinctBy(GameSaveType::id)
            .sortedBy(GameSaveType::label)
            .map { it.id to it.label }
    }
    var selectedTypeId by remember(state.scanRevision) { mutableStateOf("all") }
    val visibleGames = remember(games, selectedTypeId) {
        if (selectedTypeId == "all") games else games.filter { it.saveType.id == selectedTypeId }
    }
    val visibleEligibleKeys = remember(visibleGames) {
        GameLibrary.batchBackupEligibleEntries(visibleGames).map { it.libraryKey }
    }
    val visibleSelectedKeys = selectedGameKeys.intersect(visibleEligibleKeys.toSet())
    var sourceDetailsExpanded by remember(state.scanRevision) { mutableStateOf(false) }
    val context = LocalContext.current
    val coverRepository = remember(context) { CoverRepository(context.applicationContext) }
    val metadataRepository = remember(context) { SteamMetadataRepository(context.applicationContext) }
    val availableSources = state.sourceScans.count { it.scan != null }
    val skippedSources = state.sourceScans.filter { it.scan == null }
    val gameHubLocations = state.sourceScans.sumOf { it.scan?.gameHubPcCount ?: 0 }
    val candidateLocations = state.sourceScans.sumOf { it.scan?.candidateCount ?: 0 }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 280.dp),
        modifier = modifier,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 0.dp, top = 4.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            if (state.sourceScans.isEmpty()) "游戏存档" else "${visibleGames.size} / ${games.size} 个存档条目 · ${batchEligibleKeys.size} 款默认勾选",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "$availableSources 个来源已扫描 · 跳过 ${skippedSources.size} 个",
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            IconButton(
                                onClick = { sourceDetailsExpanded = !sourceDetailsExpanded },
                                enabled = state.sourceScans.isNotEmpty(),
                                modifier = Modifier.size(32.dp),
                            ) {
                                Icon(
                                    if (sourceDetailsExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                                    contentDescription = if (sourceDetailsExpanded) "收起来源扫描结果" else "展开来源扫描结果",
                                )
                            }
                        }
                    }
                    OutlinedButton(
                        onClick = viewModel::scanSource,
                        enabled = !state.busy,
                    ) {
                        Icon(Icons.Outlined.Search, contentDescription = "扫描存档")
                        Text("扫描", modifier = Modifier.padding(start = 6.dp))
                    }
                }
                if (sourceDetailsExpanded) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        state.sourceScans.forEach { source ->
                            val detail = source.scan?.let { scan ->
                                "${scan.gameCount} 款已识别 · ${scan.detected.size} 个位置"
                            } ?: "跳过 · ${source.skippedReason}"
                            Text(
                                "${source.label} · ${sourceAccessLabel(source.access)} · $detail",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (source.access.readable) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        state.sourceScans
                            .filter { it.scan?.let { scan -> scan.truncated || scan.unreadableDirectoryCount > 0 } == true }
                            .take(4)
                            .forEach { source ->
                                Text(
                                    "${source.label}：扫描结果可能不完整，请检查权限和范围。",
                                    modifier = Modifier.padding(vertical = 4.dp),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        skippedSources.take(4).forEach { source ->
                            Text(
                                "${source.label}：${source.skippedReason}",
                                modifier = Modifier.padding(vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                if (gameHubLocations > 0 || candidateLocations > 0) {
                    Text(
                        buildString {
                            if (gameHubLocations > 0) append("PC 容器 $gameHubLocations 个位置")
                            if (candidateLocations > 0) {
                                if (isNotEmpty()) append(" · ")
                                append("候选 $candidateLocations 个")
                            }
                        },
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                if (state.busy) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(state.action.orEmpty(), modifier = Modifier.padding(start = 10.dp), style = MaterialTheme.typography.bodySmall)
                    }
                }
                state.error?.let {
                    Text(it, modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp), color = MaterialTheme.colorScheme.error)
                }
                if (games.isNotEmpty()) {
                    SaveTypeFilterBar(
                        options = typeOptions,
                        selectedTypeId = selectedTypeId,
                        enabled = !state.busy,
                        onSelected = { selectedTypeId = it },
                    )
                }
                if (visibleEligibleKeys.isNotEmpty()) {
                    BatchSelectionToolbar(
                        selectedCount = visibleSelectedKeys.size,
                        eligibleKeys = visibleEligibleKeys,
                        enabled = !state.busy,
                        onToggleAll = {
                            val allSelected = visibleEligibleKeys.isNotEmpty() &&
                                selectedGameKeys.containsAll(visibleEligibleKeys)
                            selectedGameKeys = if (allSelected) {
                                selectedGameKeys - visibleEligibleKeys.toSet()
                            } else {
                                selectedGameKeys + visibleEligibleKeys
                            }
                        },
                        onBackup = { viewModel.requestBatchGameBackup(visibleSelectedKeys) },
                    )
                }
                state.action?.takeIf { !state.busy && !it.startsWith("扫描完成：") }?.let {
                    Text(it, modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        when {
            state.sourceScans.isEmpty() -> item(span = { GridItemSpan(maxLineSpan) }) {
                EmptyGameLibrary(
                    message = "正在扫描已知的游戏与模拟器存档来源。",
                    modifier = Modifier.fillMaxWidth().heightIn(min = 260.dp),
                    onOpenManagement = onOpenManagement,
                )
            }
            games.isEmpty() -> item(span = { GridItemSpan(maxLineSpan) }) {
                EmptyGameLibrary(
                    message = "已扫描所有可用来源，但没有发现明确的游戏存档位置。候选目录不会默认纳入备份。",
                    modifier = Modifier.fillMaxWidth().heightIn(min = 260.dp),
                    onOpenManagement = onOpenManagement,
                )
            }
            else -> items(visibleGames, key = { it.libraryKey }) { entry ->
                GameLibraryCard(
                    entry = entry,
                    state = state,
                    viewModel = viewModel,
                    coverRepository = coverRepository,
                    metadataRepository = metadataRepository,
                    selected = entry.libraryKey in selectedGameKeys,
                    onSelectedChange = { selected ->
                        selectedGameKeys = if (selected) {
                            selectedGameKeys + entry.libraryKey
                        } else {
                            selectedGameKeys - entry.libraryKey
                        }
                    },
                )
            }
        }
    }
}

private fun sourceAccessLabel(access: SourceAccessStatus): String = when (access.capability) {
    SourceAccessCapability.APP -> if (access.writable) "应用可访问 · 可读写" else "应用可访问 · 只读"
    SourceAccessCapability.SAF -> if (access.writable) "SAF 已授权 · 可读写" else "SAF 已授权 · 只读，可备份"
    SourceAccessCapability.ROOT -> "仅 Root 可访问 · 可读写"
    SourceAccessCapability.UNAVAILABLE -> buildString {
        append("不可访问 · ")
        append(
            when (access.requiredCapability) {
                SourceAccessCapability.APP -> "应用访问"
                SourceAccessCapability.SAF -> "需要 SAF 授权"
                SourceAccessCapability.ROOT -> "需要 Root"
                SourceAccessCapability.UNAVAILABLE -> "来源检查"
            },
        )
        access.reason.takeIf(String::isNotBlank)?.let { append(" · $it") }
    }
}

private fun plannedAccessStatus(
    option: SaveSourceOption,
    rootChecked: Boolean,
    rootAvailable: Boolean,
): SourceAccessStatus = when (option.requiredAccess) {
    SourceAccessCapability.APP -> SourceAccessStatus.appAccessible()
    SourceAccessCapability.SAF -> SourceAccessStatus.pending(SourceAccessCapability.SAF)
    SourceAccessCapability.ROOT -> if (rootChecked && !rootAvailable) {
        SourceAccessStatus.unavailable(SourceAccessCapability.ROOT, "Root 未授权")
    } else {
        SourceAccessStatus.pending(SourceAccessCapability.ROOT)
    }
    SourceAccessCapability.UNAVAILABLE -> SourceAccessStatus.pending(SourceAccessCapability.UNAVAILABLE)
}

@Composable
private fun SaveTypeFilterBar(
    options: List<Pair<String, String>>,
    selectedTypeId: String,
    enabled: Boolean,
    onSelected: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { (id, label) ->
            FilterChip(
                selected = selectedTypeId == id,
                onClick = { onSelected(id) },
                enabled = enabled,
                label = { Text(label) },
            )
        }
    }
}

@Composable
private fun BatchSelectionToolbar(
    selectedCount: Int,
    eligibleKeys: List<String>,
    enabled: Boolean,
    onToggleAll: () -> Unit,
    onBackup: () -> Unit,
) {
    val allSelected = eligibleKeys.isNotEmpty() && selectedCount == eligibleKeys.size
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("已选 $selectedCount / ${eligibleKeys.size} 款", modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
        TextButton(onClick = onToggleAll, enabled = enabled && eligibleKeys.isNotEmpty()) {
            Text(if (allSelected) "取消全选" else "全选")
        }
        Button(
            onClick = onBackup,
            enabled = enabled && selectedCount > 0,
            modifier = Modifier.height(44.dp),
        ) {
            Icon(Icons.Outlined.Save, contentDescription = null)
            Text("批量备份", modifier = Modifier.padding(start = 8.dp))
        }
    }
}

@Composable
private fun EmptyGameLibrary(
    message: String,
    modifier: Modifier = Modifier,
    onOpenManagement: () -> Unit,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Outlined.Storage, contentDescription = null, modifier = Modifier.size(42.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(14.dp))
        Text(message, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(16.dp))
        Button(onClick = onOpenManagement) {
            Icon(Icons.Outlined.FolderOpen, contentDescription = null)
            Text("存档来源", modifier = Modifier.padding(start = 8.dp))
        }
    }
}

@Composable
private fun SourceManagementPage(
    state: SourceUiState,
    viewModel: SourceViewModel,
    onPickFolder: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        GameSaveCard(state, viewModel)
        CloudUpdateCard(state, viewModel)
        SourceSelectionCard(state, viewModel, onPickFolder)
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(18.dp)) {
                Text("整来源快照", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text("为当前选定目录创建 OpenSave ZIP 快照；游戏库中的单游戏备份只包含清单列出的存档路径。")
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(onClick = viewModel::importSource, enabled = !state.busy, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Outlined.CloudDownload, contentDescription = "复制")
                        Text("复制到工作区", modifier = Modifier.padding(start = 6.dp))
                    }
                    Button(onClick = viewModel::backupNow, enabled = !state.busy, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Outlined.Save, contentDescription = "创建快照")
                        Text("创建快照", modifier = Modifier.padding(start = 6.dp))
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = viewModel::restoreSource, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.UploadFile, contentDescription = "写回")
                    Text("把工作区写回来源目录", modifier = Modifier.padding(start = 8.dp))
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Schedule, contentDescription = null)
                        Text("Wi-Fi 自动备份", modifier = Modifier.padding(start = 8.dp))
                    }
                    Switch(checked = state.autoBackupEnabled, onCheckedChange = viewModel::setAutoBackupEnabled, enabled = !state.busy)
                }
                if (state.busy) {
                    Row(modifier = Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(state.action.orEmpty(), modifier = Modifier.padding(start = 10.dp), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        LocalSnapshotCard(state, viewModel)
        RemoteSnapshotCard(state, viewModel)
        state.action?.takeIf { !state.busy }?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        state.report?.let { ReportCard(it) }
    }
}

@Composable
private fun SourceSelectionCard(state: SourceUiState, viewModel: SourceViewModel, onPickFolder: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text("存档来源", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = state.storageMode == StorageMode.SAF,
                    onClick = { viewModel.selectStorageMode(StorageMode.SAF) },
                    enabled = !state.busy,
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    modifier = Modifier.weight(1f),
                ) { Text("系统授权") }
                SegmentedButton(
                    selected = state.storageMode == StorageMode.ROOT,
                    onClick = { viewModel.selectStorageMode(StorageMode.ROOT) },
                    enabled = !state.busy,
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    modifier = Modifier.weight(1f),
                ) { Text("Root 路径") }
            }
            Spacer(Modifier.height(12.dp))
            if (state.storageMode == StorageMode.ROOT) {
                OutlinedTextField(
                    value = state.rootPath,
                    onValueChange = viewModel::updateRootPath,
                    label = { Text("Root 扫描路径") },
                    placeholder = { Text("/data/user/0/com.xiaoji.egggame/files") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = viewModel::applyRootPath, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.FolderOpen, contentDescription = "保存 Root 路径")
                    Text("保存并扫描", modifier = Modifier.padding(start = 8.dp))
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    when {
                        !state.rootChecked -> "正在检测 su 权限..."
                        state.rootAvailable -> "Root 可用；目录扫描为只读操作。"
                        else -> "未检测到 uid 0，请确认 Magisk 已授权 OpenSave。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state.rootAvailable) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                if (state.authorizedTrees.isEmpty()) {
                    Text(
                        "尚未添加系统授权目录",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    state.authorizedTrees.forEach { tree ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(tree.label, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    when {
                                        tree.readable && tree.writable -> "已授权读写 · 自动扫描"
                                        tree.readable -> "已授权只读 · 可备份，不能恢复"
                                        else -> "目录授权已失效"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (tree.readable) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                )
                            }
                            IconButton(
                                onClick = { viewModel.forgetAuthorizedTree(tree.uri) },
                                enabled = !state.busy,
                            ) {
                                Icon(Icons.Outlined.DeleteOutline, contentDescription = "移除授权目录")
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Button(onClick = onPickFolder, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.FolderOpen, contentDescription = "选择目录")
                    Text("添加并授权目录", modifier = Modifier.padding(start = 8.dp))
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    if (state.authorizedTrees.isNotEmpty()) {
                        "可重复添加多个目录；应用启动和 Wi-Fi 自动备份都会扫描所有仍有读取权限的目录。"
                    } else {
                        "系统授权模式只访问你选择的目录。Android 不允许非 Root 应用读取其他应用的私有数据目录。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state.authorizedTrees.any(AuthorizedSafTree::readable)) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.sourceOptions.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))
                val selectedCount = state.sourceOptions.count(SaveSourceOption::selected)
                val scanBySourceId = state.sourceScans.associateBy(SourceScanResult::sourceId)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("自动扫描来源", style = MaterialTheme.typography.titleSmall)
                    Text("$selectedCount / ${state.sourceOptions.size} 已选", style = MaterialTheme.typography.labelMedium)
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(
                        onClick = { viewModel.setAllSourcesEnabled(true) },
                        enabled = !state.busy && selectedCount < state.sourceOptions.size,
                    ) { Text("全选") }
                    TextButton(
                        onClick = { viewModel.setAllSourcesEnabled(false) },
                        enabled = !state.busy && selectedCount > 0,
                    ) { Text("全不选") }
                }
                state.sourceOptions.forEach { option ->
                    val access = scanBySourceId[option.sourceId]?.access
                        ?: plannedAccessStatus(option, state.rootChecked, state.rootAvailable)
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = option.selected,
                            onCheckedChange = { selected -> viewModel.setSourceEnabled(option.id, selected) },
                            enabled = !state.busy,
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(option.label, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                buildString {
                                    append(if (option.enabledByDefault) option.saveType.label else "未归类应用候选")
                                    append(" · ")
                                    append(sourceAccessLabel(access))
                                    if (!option.enabledByDefault) append(" · 默认关闭，避免扫描非游戏数据")
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = if (access.readable) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
                if (!state.rootAvailable) {
                    Text(
                        "未 Root 的设备会自动跳过 Root 来源；请为可访问的模拟器或游戏目录添加系统授权。",
                        modifier = Modifier.padding(top = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun GameSaveCard(state: SourceUiState, viewModel: SourceViewModel) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text("game-save 云端备份", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                "OpenSave 直接调用 game-save API。同步密钥使用 Android Keystore 加密保存；PC、Switch 和各模拟器存档在云端分开存放。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = state.gameSaveUrl,
                onValueChange = viewModel::updateGameSaveUrl,
                label = { Text("game-save 服务地址") },
                placeholder = { Text("http://nas.example:8788") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = state.gameSaveSyncKey,
                onValueChange = viewModel::updateGameSaveSyncKey,
                label = { Text("同步密钥") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("启用新快照自动上传")
                Switch(checked = state.gameSaveEnabled, onCheckedChange = viewModel::updateGameSaveEnabled)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = viewModel::saveGameSave, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Outlined.SettingsBackupRestore, contentDescription = "保存")
                    Text("保存", modifier = Modifier.padding(start = 6.dp))
                }
                OutlinedButton(onClick = viewModel::testGameSave, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Outlined.CloudUpload, contentDescription = "测试")
                    Text("测试连接", modifier = Modifier.padding(start = 6.dp))
                }
            }
            Spacer(Modifier.height(8.dp))
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = viewModel::listCloud,
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Outlined.Refresh, contentDescription = "列举云端")
                    Text("列举云端快照", modifier = Modifier.padding(start = 6.dp))
                }
                OutlinedButton(
                    onClick = viewModel::checkCloudUpdates,
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Outlined.CloudDownload, contentDescription = "检查跨设备更新")
                    Text("检查跨设备更新", modifier = Modifier.padding(start = 6.dp))
                }
            }
        }
    }
}

@Composable
private fun CloudUpdateCard(state: SourceUiState, viewModel: SourceViewModel) {
    if (state.cloudUpdates.isEmpty()) return
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text("跨设备更新", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            state.cloudUpdates.take(16).forEach { update ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            update.entry.game.presentation.primaryName,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            buildString {
                                append(update.entry.sourceLabel)
                                append(" · ")
                                append(
                                    when (update.action) {
                                        GameSaveRemoteAction.AUTO_RESTORE -> "可安全恢复"
                                        GameSaveRemoteAction.REVIEW_REQUIRED -> "需要确认"
                                        GameSaveRemoteAction.WAIT_FOR_REMOTE -> "远端尚未就绪"
                                        GameSaveRemoteAction.UP_TO_DATE -> "已同步"
                                    },
                                )
                                update.deviceName.takeIf(String::isNotBlank)?.let {
                                    append(" · ")
                                    append(it)
                                }
                                update.receivedAtMillis.takeIf { it > 0L }?.let {
                                    append(" · ")
                                    append(formatTime(it))
                                }
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        update.message.takeIf(String::isNotBlank)?.let { message ->
                            Text(
                                message,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.tertiary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    if (update.remote != null) {
                        IconButton(
                            onClick = { viewModel.requestCloudUpdateRestore(update) },
                            enabled = !state.busy,
                        ) {
                            Icon(Icons.Outlined.SettingsBackupRestore, contentDescription = "确认恢复此跨设备更新")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LocalSnapshotCard(state: SourceUiState, viewModel: SourceViewModel) {
    val snapshots = state.snapshots.filter { it.sourceId == state.activeSourceId && it.gameKey == null }
    if (snapshots.isEmpty()) return
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text("本地历史", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            snapshots.take(12).forEach { snapshot ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(snapshot.remoteName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${formatTime(snapshot.createdAtMillis)} · ${formatBytes(snapshot.sizeBytes)} · ${if (snapshot.uploaded) "已上传" else "未上传"}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    OutlinedButton(
                        onClick = { viewModel.requestLocalRestore(snapshot) },
                        enabled = !state.busy,
                    ) {
                        Icon(Icons.Outlined.SettingsBackupRestore, contentDescription = "恢复")
                        Text("恢复")
                    }
                }
            }
        }
    }
}

@Composable
private fun RemoteSnapshotCard(state: SourceUiState, viewModel: SourceViewModel) {
    val sourceId = state.activeSourceId ?: return
    val snapshots = state.remoteSnapshots.filter { it.name.startsWith(SnapshotNaming.remotePrefix(sourceId)) }
    if (snapshots.isEmpty()) return
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text("云端快照", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            snapshots.take(20).forEach { remote ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(remote.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${GameSaveTypes.fromId(remote.type).label} · ${formatBytes(remote.sizeBytes)}${if (remote.modified.isBlank()) "" else " · ${remote.modified}"}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    OutlinedButton(
                        onClick = { viewModel.requestRemoteRestore(remote) },
                        enabled = !state.busy,
                    ) {
                        Icon(Icons.Outlined.Download, contentDescription = "下载并恢复")
                        Text("恢复")
                    }
                }
            }
        }
    }
}

@Composable
private fun ReportCard(report: CopyReport) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text("最近一次工作区结果", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text("文件：${report.fileCount}    大小：${report.byteCount} bytes")
            Text(
                text = "Manifest SHA-256：${report.manifestHash}",
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (report.detected.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                Text("工作区识别提示", style = MaterialTheme.typography.labelLarge)
                report.detected.take(8).forEach { detected ->
                    val title = detected.titleId?.let { " · TitleID $it" }.orEmpty()
                    Text(
                        "${detected.note}$title\n${detected.relativePath}",
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (report.detected.size > 8) {
                    Text("还有 ${report.detected.size - 8} 个识别提示", style = MaterialTheme.typography.labelSmall)
                }
            }
            if (report.files.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                report.files.take(12).forEach { file ->
                    Text(
                        text = "${file.relativePath}  (${file.sizeBytes} bytes)",
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (report.files.size > 12) {
                    Text("还有 ${report.files.size - 12} 个文件未展开", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun GameLibraryCard(
    entry: SourceGameEntry,
    state: SourceUiState,
    viewModel: SourceViewModel,
    coverRepository: CoverRepository,
    metadataRepository: SteamMetadataRepository,
    selected: Boolean,
    onSelectedChange: (Boolean) -> Unit,
) {
    val game = entry.game
    val cloudIdentity = GameCloudNaming.identity(entry.source, entry.sourceId, game)
    var expanded by remember(entry.libraryKey) { mutableStateOf(false) }
    var presentation by remember(entry.libraryKey) { mutableStateOf(game.presentation) }
    LaunchedEffect(entry.libraryKey) {
        presentation = metadataRepository.enrich(game.presentation)
    }
    val sourceId = entry.sourceId
    val localHistory = state.snapshots.filter {
        it.gameKey == game.gameKey && it.sourceId == sourceId
    }
    val remoteHistory = state.remoteSnapshots.filter {
        it.type == cloudIdentity.saveType.id && it.name.startsWith(GameCloudNaming.remotePrefix(cloudIdentity))
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            GameArtwork(
                presentation = presentation,
                coverRepository = coverRepository,
                modifier = Modifier.fillMaxWidth().height(96.dp),
            )
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        entry.sourceLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        sourceAccessLabel(entry.sourceAccess),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        entry.saveType.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        presentation.primaryName,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    presentation.originalName?.takeIf { it != presentation.localizedName }?.let { original ->
                        Text(
                            original,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Checkbox(
                    checked = selected,
                    onCheckedChange = onSelectedChange,
                    enabled = !state.busy && GameLibrary.canBatchBackup(game),
                )
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(
                        imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                        contentDescription = if (expanded) "收起游戏详情" else "展开游戏详情",
                    )
                }
            }
            Text(
                buildString {
                    append(game.saves.map { kindLabel(it.kind) }.distinct().joinToString("、"))
                    append(" · ${game.locationCount} 个位置")
                    if (game.candidateCount > 0) append(" · 候选 ${game.candidateCount}")
                    presentation.identity?.let { append(" · $it") }
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (game.isCandidateOnly) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Schedule, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text(
                        "本地 ${localHistory.size} · 云端 ${remoteHistory.size}",
                        modifier = Modifier.padding(start = 5.dp),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                OutlinedButton(
                    onClick = { expanded = !expanded },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                ) { Text(if (expanded) "收起" else "详情") }
            }
            Button(
                onClick = {
                    viewModel.requestGameBackup(entry.copy(game = game.copy(presentation = presentation)))
                },
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            ) {
                Icon(Icons.Outlined.Save, contentDescription = "备份游戏存档")
                Text("备份游戏存档", modifier = Modifier.padding(start = 8.dp))
            }

            if (expanded) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))
                Text("存档位置", style = MaterialTheme.typography.labelLarge)
                game.saves.take(MAX_VISIBLE_LOCATIONS_PER_GAME).forEach { save ->
                    Text(
                        "${if (save.candidate) "候选" else "已识别"} · ${save.relativePath}",
                        modifier = Modifier.padding(top = 5.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (save.candidate) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (game.saves.size > MAX_VISIBLE_LOCATIONS_PER_GAME) {
                    Text("还有 ${game.saves.size - MAX_VISIBLE_LOCATIONS_PER_GAME} 个位置未展开", style = MaterialTheme.typography.labelSmall)
                }
                if (localHistory.isNotEmpty()) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))
                    Text("本地快照", style = MaterialTheme.typography.labelLarge)
                    localHistory.take(MAX_GAME_HISTORY_ROWS).forEach { snapshot ->
                        SnapshotHistoryRow(
                            title = snapshot.comment.ifBlank { formatTime(snapshot.createdAtMillis) },
                            detail = "${formatBytes(snapshot.sizeBytes)} · ${if (snapshot.uploaded) "已上传" else "仅本地"}",
                            enabled = !state.busy,
                            onRestore = {
                                viewModel.requestLocalGameRestore(
                                    entry.copy(game = game.copy(presentation = presentation)),
                                    snapshot,
                                )
                            },
                        )
                    }
                }
                if (remoteHistory.isNotEmpty()) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))
                    Text("云端快照", style = MaterialTheme.typography.labelLarge)
                    remoteHistory.take(MAX_GAME_HISTORY_ROWS).forEach { snapshot ->
                        SnapshotHistoryRow(
                            title = snapshot.modified.ifBlank { snapshot.name },
                            detail = formatBytes(snapshot.sizeBytes),
                            enabled = !state.busy,
                            onRestore = {
                                viewModel.requestRemoteGameRestore(
                                    entry.copy(game = game.copy(presentation = presentation)),
                                    snapshot,
                                )
                            },
                        )
                    }
                }
                if (state.gameSaveEnabled && remoteHistory.isEmpty()) {
                    Text(
                        "云端记录为空时，可在“备份与设置”中列举 game-save 快照。",
                        modifier = Modifier.padding(top = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun SnapshotHistoryRow(
    title: String,
    detail: String,
    enabled: Boolean,
    onRestore: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = onRestore, enabled = enabled) {
            Icon(Icons.Outlined.SettingsBackupRestore, contentDescription = "恢复此游戏快照")
        }
    }
}

@Composable
private fun GameArtwork(
    presentation: GamePresentation,
    coverRepository: CoverRepository,
    modifier: Modifier,
) {
    var bitmap by remember(presentation.coverCacheKey) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(presentation.coverCacheKey) {
        bitmap = coverRepository.load(presentation)
    }
    if (bitmap != null) {
        Surface(
            modifier = modifier,
            shape = MaterialTheme.shapes.medium,
            tonalElevation = 1.dp,
        ) {
            Image(
                bitmap!!.asImageBitmap(),
                contentDescription = "${presentation.primaryName}封面",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
    } else {
        GameIcon(presentation.iconKey, modifier)
    }
}

@Composable
private fun GameIcon(iconKey: GameIconKey, modifier: Modifier) {
    val (image, tint) = when (iconKey) {
        GameIconKey.SWITCH -> Icons.Outlined.SportsEsports to MaterialTheme.colorScheme.primary
        GameIconKey.PC -> Icons.Outlined.Computer to MaterialTheme.colorScheme.secondary
        GameIconKey.ANDROID -> Icons.Outlined.PhoneAndroid to MaterialTheme.colorScheme.tertiary
        GameIconKey.PPSSPP -> Icons.Outlined.SportsEsports to MaterialTheme.colorScheme.primary
        GameIconKey.RETROARCH -> Icons.Outlined.Storage to MaterialTheme.colorScheme.secondary
        GameIconKey.DOLPHIN -> Icons.Outlined.SportsEsports to MaterialTheme.colorScheme.tertiary
        GameIconKey.DUCKSTATION -> Icons.Outlined.Memory to MaterialTheme.colorScheme.secondary
        GameIconKey.AETHERSX2 -> Icons.Outlined.Memory to MaterialTheme.colorScheme.tertiary
        GameIconKey.EMULATOR -> Icons.Outlined.Memory to MaterialTheme.colorScheme.primary
        GameIconKey.UNKNOWN -> Icons.Outlined.Storage to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        modifier = modifier,
        color = tint.copy(alpha = 0.12f),
        shape = MaterialTheme.shapes.medium,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(image, contentDescription = null, tint = tint, modifier = Modifier.size(28.dp))
        }
    }
}

private fun kindLabel(kind: String): String = when (kind) {
    "yuzu-switch" -> "Yuzu/Eden Switch"
    "ryujinx-switch" -> "Ryujinx/Ryubing Switch"
    "switch-title" -> "Switch TitleID"
    "ppsspp" -> "PPSSPP"
    "retroarch" -> "RetroArch"
    "dolphin" -> "Dolphin"
    "duckstation" -> "DuckStation"
    "aethersx2" -> "AetherSX2/NetherSX2"
    "emulator" -> "模拟器"
    "android-app" -> "Android 应用数据"
    "android-game" -> "Android 游戏"
    "gamehub-pc" -> "GameHub 本地 PC"
    else -> kind
}

private fun formatTime(millis: Long): String = DateFormat.getDateTimeInstance().format(Date(millis))

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KiB".format(bytes / 1024.0)
    else -> "%.1f MiB".format(bytes / (1024.0 * 1024.0))
}

private const val MAX_VISIBLE_LOCATIONS_PER_GAME = 24
private const val MAX_GAME_HISTORY_ROWS = 8
