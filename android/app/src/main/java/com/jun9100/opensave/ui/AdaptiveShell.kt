package com.jun9100.opensave.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SettingsBackupRestore
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jun9100.opensave.BackTarget
import com.jun9100.opensave.LibraryView
import com.jun9100.opensave.OpenSavePage
import com.jun9100.opensave.SourceViewModel
import com.jun9100.opensave.ui.dialogs.GameBackupConfirmDialog
import com.jun9100.opensave.ui.dialogs.RestoreConfirmDialog
import com.jun9100.opensave.ui.dialogs.WorkspaceWriteBackDialog
import com.jun9100.opensave.ui.library.GameLibraryPage
import com.jun9100.opensave.ui.settings.SourceManagementPage

@Composable
internal fun OpenSaveScreen(sourceViewModel: SourceViewModel = viewModel()) {
    val state by sourceViewModel.uiState.collectAsStateWithLifecycle()
    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
        onResult = { uri -> uri?.let(sourceViewModel::onTreeSelected) },
    )
    // Hoisted here so switching pages or between portrait and landscape keeps the scroll position.
    val libraryListState = rememberLazyListState()

    state.pendingGameBackup?.let { entry ->
        GameBackupConfirmDialog(
            entry = entry,
            onDismiss = sourceViewModel::cancelGameBackup,
            onConfirm = { includeCandidates -> sourceViewModel.confirmGameBackup(includeCandidates = includeCandidates) },
        )
    }

    state.pendingWriteBack?.let { request ->
        WorkspaceWriteBackDialog(
            request = request,
            onDismiss = sourceViewModel::cancelWorkspaceWriteBack,
            onConfirm = sourceViewModel::confirmWorkspaceWriteBack,
        )
    }

    state.pendingRestore?.let { request ->
        RestoreConfirmDialog(
            request = request,
            storageMode = state.storageMode,
            onDismiss = sourceViewModel::cancelRestore,
            onConfirm = sourceViewModel::confirmRestore,
        )
    }

    ProvideAdaptiveLayout(modifier = Modifier.fillMaxSize()) {
        val landscape = LocalAdaptiveLayout.current.isLandscape
        // Back stack: portrait game detail -> list; secondary page -> library; otherwise the system.
        BackHandler(enabled = LibraryView.backTarget(state.page, state.gameDetailOpen, landscape) != BackTarget.SYSTEM) {
            sourceViewModel.navigateBack(landscape)
        }
        AdaptiveShellFrame(
            page = state.page,
            busy = state.busy,
            progress = state.progress,
            gameDetailOpen = state.gameDetailOpen && state.selectedGameKey != null,
            onShowPage = sourceViewModel::showPage,
            onRefresh = if (state.page == OpenSavePage.LIBRARY) {
                sourceViewModel::scanSource
            } else {
                sourceViewModel::refreshPersistedSource
            },
            onCloseDetail = sourceViewModel::closeGameDetail,
        ) { contentModifier ->
            when (state.page) {
                OpenSavePage.LIBRARY -> GameLibraryPage(
                    state = state,
                    viewModel = sourceViewModel,
                    listState = libraryListState,
                    modifier = contentModifier,
                    onOpenManagement = { sourceViewModel.showPage(OpenSavePage.MANAGEMENT) },
                    onPickFolder = { folderPicker.launch(null) },
                )
                OpenSavePage.MANAGEMENT -> SourceManagementPage(
                    state = state,
                    viewModel = sourceViewModel,
                    onPickFolder = { folderPicker.launch(null) },
                    modifier = contentModifier,
                )
            }
        }
    }
}

/**
 * The navigation frame without the ViewModel. Landscape: NavigationRail on the
 * left and no TopAppBar, so the page keeps the whole height. Portrait: TopAppBar
 * and NavigationBar; the full-screen game detail swaps them for a back arrow.
 * The status strip is drawn under the page only while [busy]; in landscape the
 * library page draws its own under the detail pane instead.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AdaptiveShellFrame(
    page: OpenSavePage,
    busy: Boolean,
    progress: String?,
    gameDetailOpen: Boolean,
    onShowPage: (OpenSavePage) -> Unit,
    onRefresh: () -> Unit,
    onCloseDetail: () -> Unit,
    content: @Composable ColumnScope.(Modifier) -> Unit,
) {
    val landscape = LocalAdaptiveLayout.current.isLandscape
    if (landscape) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Row(modifier = Modifier.fillMaxSize()) {
                NavigationRail(
                    modifier = Modifier.fillMaxHeight().testTag(NAVIGATION_RAIL_TAG),
                    header = {
                        Text(
                            "OpenSave",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                        )
                    },
                ) {
                    NavigationRailItem(
                        selected = page == OpenSavePage.LIBRARY,
                        onClick = { onShowPage(OpenSavePage.LIBRARY) },
                        icon = { Icon(Icons.Outlined.SportsEsports, contentDescription = null) },
                        label = { Text("游戏库") },
                    )
                    NavigationRailItem(
                        selected = page == OpenSavePage.MANAGEMENT,
                        onClick = { onShowPage(OpenSavePage.MANAGEMENT) },
                        icon = { Icon(Icons.Outlined.SettingsBackupRestore, contentDescription = null) },
                        label = { Text("备份与设置", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    )
                    Spacer(Modifier.weight(1f))
                    NavigationRailItem(
                        selected = false,
                        onClick = onRefresh,
                        enabled = !busy,
                        icon = { Icon(Icons.Outlined.Refresh, contentDescription = "刷新") },
                        label = { Text(if (page == OpenSavePage.LIBRARY) "扫描" else "刷新") },
                    )
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical + WindowInsetsSides.End)),
                ) {
                    content(Modifier.weight(1f))
                    // The library draws it under its detail pane so the list keeps its height.
                    if (page != OpenSavePage.LIBRARY) StatusStrip(busy = busy, progress = progress)
                }
            }
        }
    } else {
        val showingDetail = page == OpenSavePage.LIBRARY && gameDetailOpen
        Scaffold(
            topBar = {
                TopAppBar(
                    navigationIcon = {
                        if (showingDetail) {
                            IconButton(onClick = onCloseDetail) {
                                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回游戏列表")
                            }
                        }
                    },
                    title = {
                        Column {
                            Text("OpenSave")
                            Text(
                                text = when {
                                    showingDetail -> "游戏详情"
                                    page == OpenSavePage.LIBRARY -> "游戏库"
                                    else -> "备份与设置"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    actions = {
                        if (!showingDetail) {
                            IconButton(onClick = onRefresh, enabled = !busy) {
                                Icon(Icons.Outlined.Refresh, contentDescription = "刷新")
                            }
                        }
                    },
                )
            },
            bottomBar = {
                if (!showingDetail) {
                    NavigationBar {
                        NavigationBarItem(
                            selected = page == OpenSavePage.LIBRARY,
                            onClick = { onShowPage(OpenSavePage.LIBRARY) },
                            icon = { Icon(Icons.Outlined.SportsEsports, contentDescription = null) },
                            label = { Text("游戏库") },
                        )
                        NavigationBarItem(
                            selected = page == OpenSavePage.MANAGEMENT,
                            onClick = { onShowPage(OpenSavePage.MANAGEMENT) },
                            icon = { Icon(Icons.Outlined.SettingsBackupRestore, contentDescription = null) },
                            label = { Text("备份与设置") },
                        )
                    }
                }
            },
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .consumeWindowInsets(padding)
                    .imePadding(),
            ) {
                content(Modifier.weight(1f))
                StatusStrip(busy = busy, progress = progress)
            }
        }
    }
}

internal const val NAVIGATION_RAIL_TAG = "navigation-rail"
internal const val LIBRARY_TOOLBAR_TAG = "library-toolbar"
internal const val GAME_DETAIL_TAG = "game-detail"
