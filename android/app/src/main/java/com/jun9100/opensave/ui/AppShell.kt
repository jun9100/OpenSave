package com.jun9100.opensave.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SettingsBackupRestore
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jun9100.opensave.OpenSavePage
import com.jun9100.opensave.SourceViewModel
import com.jun9100.opensave.ui.dialogs.GameBackupConfirmDialog
import com.jun9100.opensave.ui.dialogs.RestoreConfirmDialog
import com.jun9100.opensave.ui.dialogs.WorkspaceWriteBackDialog
import com.jun9100.opensave.ui.library.GameLibraryPage
import com.jun9100.opensave.ui.settings.SourceManagementPage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OpenSaveScreen(sourceViewModel: SourceViewModel = viewModel()) {
    val state by sourceViewModel.uiState.collectAsStateWithLifecycle()
    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
        onResult = { uri -> uri?.let(sourceViewModel::onTreeSelected) },
    )

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
