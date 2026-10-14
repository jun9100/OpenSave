package com.jun9100.opensave

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SourceUiState(
    val selectedUri: Uri? = null,
    val hasPersistedPermission: Boolean = false,
    val busy: Boolean = false,
    val action: String? = null,
    val error: String? = null,
    val report: CopyReport? = null,
)

class SourceViewModel(application: Application) : AndroidViewModel(application) {
    private val storage = SafStorage(application)
    private val state = MutableStateFlow(SourceUiState())
    val uiState: StateFlow<SourceUiState> = state.asStateFlow()

    init {
        refreshPersistedSource()
    }

    fun onTreeSelected(uri: Uri) {
        storage.persistTreePermission(uri)
            .onSuccess {
                state.value = SourceUiState(
                    selectedUri = uri,
                    hasPersistedPermission = storage.hasReadWritePermission(uri),
                    action = "已保存目录权限",
                )
            }
            .onFailure { error ->
                state.value = state.value.copy(
                    selectedUri = uri,
                    hasPersistedPermission = false,
                    error = "无法保存目录权限：${error.message ?: "provider refused persistent access"}",
                )
            }
    }

    fun refreshPersistedSource() {
        val uri = storage.persistedTreeUri()
        state.value = state.value.copy(
            selectedUri = uri,
            hasPersistedPermission = uri != null && storage.hasReadWritePermission(uri),
            error = null,
        )
    }

    fun importSource() {
        runWithSource("正在复制存档到私有工作区") { uri -> storage.importTree(uri) }
    }

    fun restoreSource() {
        runWithSource("正在把工作区写回授权目录") { uri -> storage.restoreWorkspace(uri) }
    }

    private fun runWithSource(
        action: String,
        operation: suspend (Uri) -> Result<CopyReport>,
    ) {
        val uri = state.value.selectedUri
        if (uri == null || !state.value.hasPersistedPermission) {
            state.value = state.value.copy(error = "请先选择并授权一个可读写目录")
            return
        }
        viewModelScope.launch {
            state.value = state.value.copy(busy = true, action = action, error = null)
            operation(uri)
                .onSuccess { report ->
                    state.value = state.value.copy(
                        busy = false,
                        action = "完成：${report.fileCount} 个文件，${report.byteCount} 字节",
                        report = report,
                    )
                }
                .onFailure { error ->
                    state.value = state.value.copy(
                        busy = false,
                        action = null,
                        error = error.message ?: "操作失败",
                    )
                }
        }
    }
}
