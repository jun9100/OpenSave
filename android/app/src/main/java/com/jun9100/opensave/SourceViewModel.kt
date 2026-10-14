package com.jun9100.opensave

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class RestoreRequest(
    val local: SnapshotRecord? = null,
    val remote: RemoteSnapshot? = null,
)

data class SourceUiState(
    val selectedUri: Uri? = null,
    val hasPersistedPermission: Boolean = false,
    val busy: Boolean = false,
    val action: String? = null,
    val error: String? = null,
    val report: CopyReport? = null,
    val snapshots: List<SnapshotRecord> = emptyList(),
    val remoteSnapshots: List<RemoteSnapshot> = emptyList(),
    val webDavEnabled: Boolean = false,
    val webDavUrl: String = "",
    val webDavUsername: String = "",
    val webDavPassword: String = "",
    val webDavSaved: Boolean = false,
    val autoBackupEnabled: Boolean = false,
    val pendingRestore: RestoreRequest? = null,
)

class SourceViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application
    private val storage = SafStorage(application)
    private val snapshotEngine = SnapshotEngine()
    private val snapshotRepository = SnapshotRepository(File(application.filesDir, SNAPSHOT_DIRECTORY))
    private val webDavStore = WebDavConfigStore(application)
    private val autoBackupScheduler = AutoBackupScheduler(application)
    private val state = MutableStateFlow(SourceUiState())
    val uiState: StateFlow<SourceUiState> = state.asStateFlow()

    init {
        refreshPersistedSource()
    }

    fun onTreeSelected(uri: Uri) {
        storage.persistTreePermission(uri)
            .onSuccess {
                state.value = state.value.copy(
                    selectedUri = uri,
                    hasPersistedPermission = storage.hasReadWritePermission(uri),
                    action = "已保存目录权限",
                    error = null,
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
        val config = runCatching { webDavStore.load() }.getOrDefault(WebDavConfig())
        state.value = state.value.copy(
            selectedUri = uri,
            hasPersistedPermission = uri != null && storage.hasReadWritePermission(uri),
            snapshots = currentSnapshots(),
            webDavEnabled = config.enabled,
            webDavUrl = config.url,
            webDavUsername = config.username,
            webDavPassword = config.password,
            webDavSaved = config.url.isNotBlank(),
            autoBackupEnabled = autoBackupScheduler.isEnabled(),
            error = null,
        )
    }

    fun updateWebDavUrl(value: String) = updateState { copy(webDavUrl = value, webDavSaved = false) }

    fun updateWebDavUsername(value: String) = updateState { copy(webDavUsername = value, webDavSaved = false) }

    fun updateWebDavPassword(value: String) = updateState { copy(webDavPassword = value, webDavSaved = false) }

    fun updateWebDavEnabled(value: Boolean) = updateState { copy(webDavEnabled = value, webDavSaved = false) }

    fun saveWebDav() {
        val config = configFromState()
        viewModelScope.launch {
            state.value = state.value.copy(busy = true, action = "正在加密保存 WebDAV 配置", error = null)
            val result = withContext(Dispatchers.IO) {
                runCatching { webDavStore.save(config) }
            }
            result.onSuccess {
                state.value = state.value.copy(
                    busy = false,
                    action = "WebDAV 配置已保存",
                    webDavSaved = true,
                )
            }.onFailure { error ->
                state.value = state.value.copy(busy = false, action = null, error = "保存 WebDAV 配置失败：${error.message}")
            }
        }
    }

    fun testWebDav() {
        val config = configFromState()
        if (config.url.isBlank()) {
            updateState { copy(error = "请先填写 WebDAV 地址") }
            return
        }
        viewModelScope.launch {
            state.value = state.value.copy(busy = true, action = "正在测试 WebDAV 连接", error = null)
            val result = withContext(Dispatchers.IO) {
                runCatching { WebDavClient(config).testConnection().getOrThrow() }
            }
            result.onSuccess {
                state.value = state.value.copy(busy = false, action = "WebDAV 连接成功")
            }.onFailure { error ->
                state.value = state.value.copy(busy = false, action = null, error = "WebDAV 连接失败：${error.message}")
            }
        }
    }

    fun importSource() {
        val uri = requiredSource() ?: return
        runSourceOperation("正在复制存档到私有工作区") {
            storage.importTree(uri)
        }
    }

    fun restoreSource() {
        val uri = requiredSource() ?: return
        runSourceOperation("正在把工作区写回授权目录") {
            storage.restoreWorkspace(uri)
        }
    }

    fun backupNow() {
        val uri = requiredSource() ?: return
        viewModelScope.launch {
            state.value = state.value.copy(busy = true, action = "正在检查并创建快照", error = null)
            val outcome = withContext(Dispatchers.IO) {
                runCatching { captureSnapshot(uri, upload = true) }
            }
            outcome.onSuccess { captured ->
                val uploadSuffix = captured.uploadError?.let { "；$it" }.orEmpty()
                state.value = state.value.copy(
                    busy = false,
                    action = "快照已保存：${captured.record.remoteName}$uploadSuffix",
                    report = captured.report,
                    snapshots = currentSnapshots(),
                    error = captured.uploadError,
                )
            }.onFailure { error ->
                state.value = state.value.copy(busy = false, action = null, error = "创建快照失败：${error.message}")
            }
        }
    }

    fun listCloud() {
        val config = configFromState()
        if (config.url.isBlank()) {
            updateState { copy(error = "请先填写并保存 WebDAV 地址") }
            return
        }
        viewModelScope.launch {
            state.value = state.value.copy(busy = true, action = "正在列举云端快照", error = null)
            val result = withContext(Dispatchers.IO) {
                runCatching { WebDavClient(config).list().getOrThrow() }
            }
            result.onSuccess { files ->
                state.value = state.value.copy(busy = false, action = "找到 ${files.size} 个云端快照", remoteSnapshots = files)
            }.onFailure { error ->
                state.value = state.value.copy(busy = false, action = null, error = "列举云端快照失败：${error.message}")
            }
        }
    }

    fun requestLocalRestore(record: SnapshotRecord) {
        updateState { copy(pendingRestore = RestoreRequest(local = record), error = null) }
    }

    fun requestRemoteRestore(remote: RemoteSnapshot) {
        updateState { copy(pendingRestore = RestoreRequest(remote = remote), error = null) }
    }

    fun cancelRestore() = updateState { copy(pendingRestore = null) }

    fun confirmRestore() {
        val request = state.value.pendingRestore ?: return
        val uri = requiredSource() ?: return
        viewModelScope.launch {
            state.value = state.value.copy(busy = true, pendingRestore = null, action = "正在下载并安全恢复快照", error = null)
            val result = withContext(Dispatchers.IO) {
                runCatching { restoreRequest(request, uri) }
            }
            result.onSuccess { report ->
                state.value = state.value.copy(
                    busy = false,
                    action = "已恢复并写回授权目录：${report.fileCount} 个文件",
                    report = report,
                    snapshots = currentSnapshots(),
                )
            }.onFailure { error ->
                state.value = state.value.copy(busy = false, action = null, error = "恢复快照失败：${error.message}")
            }
        }
    }

    fun setAutoBackupEnabled(enabled: Boolean) {
        runCatching { autoBackupScheduler.setEnabled(enabled) }
            .onSuccess {
                updateState { copy(autoBackupEnabled = enabled, action = if (enabled) "已启用 Wi-Fi 自动备份" else "已关闭自动备份") }
            }
            .onFailure { error -> updateState { copy(error = "设置自动备份失败：${error.message}") } }
    }

    private suspend fun captureSnapshot(uri: Uri, upload: Boolean): CaptureOutcome {
        val report = storage.importTree(uri).getOrThrow()
        val sourceId = SnapshotNaming.sourceId(uri.toString())
        val config = webDavStore.load()
        val previous = snapshotRepository.list().firstOrNull {
            it.sourceId == sourceId && it.manifestHash == report.manifestHash && snapshotRepository.fileFor(it).isFile
        }
        if (previous != null) {
            var uploadError: String? = null
            if (upload && config.enabled && config.url.isNotBlank() && !previous.uploaded) {
                val uploadResult = WebDavClient(config).upload(snapshotRepository.fileFor(previous), previous.remoteName)
                if (uploadResult.isSuccess) {
                    snapshotRepository.markUploaded(previous.id)
                } else {
                    uploadError = "云端上传失败：${uploadResult.exceptionOrNull()?.message ?: "未知错误"}"
                }
            }
            return CaptureOutcome(
                report,
                snapshotRepository.list().first { it.id == previous.id },
                uploadError,
            )
        }

        val timestamp = System.currentTimeMillis()
        val remoteName = SnapshotNaming.remoteName(sourceId, timestamp)
        val archive = File(app.filesDir, "$SNAPSHOT_DIRECTORY/$remoteName")
        val build = snapshotEngine.createSnapshot(storage.workspaceDirectory(), archive).getOrThrow()
        val record = SnapshotRecord(
            id = "snap_$timestamp",
            sourceId = sourceId,
            remoteName = remoteName,
            localPath = remoteName,
            createdAtMillis = timestamp,
            sizeBytes = build.archive.length(),
            sha256 = build.archiveSha256,
            manifestHash = report.manifestHash,
        )
        snapshotRepository.add(record)

        var uploadError: String? = null
        if (upload && config.enabled && config.url.isNotBlank()) {
            val uploadResult = WebDavClient(config).upload(build.archive, remoteName)
            if (uploadResult.isSuccess) {
                snapshotRepository.markUploaded(record.id)
            } else {
                uploadError = "云端上传失败：${uploadResult.exceptionOrNull()?.message ?: "未知错误"}"
            }
        }
        return CaptureOutcome(report, snapshotRepository.list().first { it.id == record.id }, uploadError)
    }

    private suspend fun restoreRequest(request: RestoreRequest, uri: Uri): CopyReport {
        val sourceId = SnapshotNaming.sourceId(uri.toString())
        val archive = request.local?.let { snapshotRepository.fileFor(it) }
            ?: request.remote?.let { File(app.filesDir, "$SNAPSHOT_DIRECTORY/${it.name}") }
            ?: error("没有可恢复的快照")
        if (request.remote != null && !archive.isFile) {
            val config = webDavStore.load()
            WebDavClient(config).download(request.remote.name, archive).getOrThrow()
        }
        require(archive.isFile) { "快照文件不存在" }

        val workspace = storage.workspaceDirectory()
        if (workspace.isDirectory) {
            val timestamp = System.currentTimeMillis()
            val safetyName = SnapshotNaming.remoteName(sourceId, timestamp)
            val safetyArchive = File(app.filesDir, "$SNAPSHOT_DIRECTORY/$safetyName")
            val safetyBuild = snapshotEngine.createSnapshot(workspace, safetyArchive).getOrThrow()
            snapshotRepository.add(
                SnapshotRecord(
                    id = "snap_$timestamp",
                    sourceId = sourceId,
                    remoteName = safetyName,
                    localPath = safetyName,
                    createdAtMillis = timestamp,
                    sizeBytes = safetyBuild.archive.length(),
                    sha256 = safetyBuild.archiveSha256,
                    manifestHash = "safety",
                    comment = "恢复前安全副本",
                ),
            )
        }
        snapshotEngine.restoreSnapshot(archive, workspace).getOrThrow()
        return storage.replaceTreeFromWorkspace(uri).getOrThrow()
    }

    private fun runSourceOperation(action: String, operation: suspend (Uri) -> Result<CopyReport>) {
        val uri = requiredSource() ?: return
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
                    state.value = state.value.copy(busy = false, action = null, error = error.message ?: "操作失败")
                }
        }
    }

    private fun requiredSource(): Uri? {
        val uri = state.value.selectedUri
        if (uri == null || !state.value.hasPersistedPermission) {
            updateState { copy(error = "请先选择并授权一个可读写目录") }
            return null
        }
        return uri
    }

    private fun configFromState() = WebDavConfig(
        enabled = state.value.webDavEnabled,
        url = state.value.webDavUrl,
        username = state.value.webDavUsername,
        password = state.value.webDavPassword,
    )

    private fun currentSnapshots(): List<SnapshotRecord> =
        snapshotRepository.list().filter { snapshotRepository.fileFor(it).isFile }.take(MAX_HISTORY)

    private fun updateState(update: SourceUiState.() -> SourceUiState) {
        state.value = state.value.update()
    }

    private data class CaptureOutcome(
        val report: CopyReport,
        val record: SnapshotRecord,
        val uploadError: String?,
    )

    private companion object {
        const val SNAPSHOT_DIRECTORY = "snapshots"
        const val MAX_HISTORY = 40
    }
}
