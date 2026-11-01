package com.jun9100.opensave.batch

import com.jun9100.opensave.GameSaveCloudUpdate
import com.jun9100.opensave.RemoteSnapshot
import com.jun9100.opensave.SourceGameEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap

/** Cloud verdicts a batch found: for every [checkedKeys] game, [updates] replace what was known. */
data class CloudFindings(
    val batchId: String,
    val checkedKeys: Set<String>,
    val updates: List<GameSaveCloudUpdate>,
    /** The cloud file list, when the batch had to read it. */
    val remoteSnapshots: List<RemoteSnapshot>? = null,
)

/**
 * The one batch of the process, shared by the ViewModel, [BatchWorker] and
 * the auto-backup worker (which yields while a batch runs). Plain Kotlin, so
 * the reservation rules are unit tested.
 */
object BatchCenter {
    private val state = MutableStateFlow<BatchProgress?>(null)
    private val findings = MutableStateFlow<CloudFindings?>(null)
    private val finished = MutableStateFlow<BatchReport?>(null)
    private val handOff = ConcurrentHashMap<String, List<SourceGameEntry>>()

    /** The running batch; null while none runs. */
    val running: StateFlow<BatchProgress?> = state.asStateFlow()

    /** Cloud verdicts of the last check or restore batch. */
    val cloudFindings: StateFlow<CloudFindings?> = findings.asStateFlow()

    /** The report of the batch that finished last in this process. */
    val lastFinished: StateFlow<BatchReport?> = finished.asStateFlow()

    val isRunning: Boolean get() = state.value != null

    /** Reserves the process for batch [id]; false while another batch runs (plan §5.5: never two at once). */
    fun tryBegin(id: String, operation: BatchOperation, total: Int): Boolean {
        var began = false
        state.update { current ->
            if (current == null || current.id == id) {
                began = true
                current ?: BatchProgress(id, operation, total)
            } else {
                current
            }
        }
        return began
    }

    fun progress(id: String, done: Int, currentName: String) = updateIf(id) {
        copy(done = done, currentName = currentName, waitingFor = null)
    }

    fun waiting(id: String, holder: String?) = updateIf(id) { copy(waitingFor = holder) }

    /** Asks the running batch to stop after the current game; false when none runs. */
    fun requestCancel(): Boolean {
        var requested = false
        state.update { current ->
            requested = current != null
            current?.copy(cancelRequested = true)
        }
        return requested
    }

    fun isCancelRequested(id: String): Boolean = state.value?.takeIf { it.id == id }?.cancelRequested == true

    fun publishFindings(value: CloudFindings) {
        findings.value = value
    }

    /** Releases the reservation of [id] and announces its report. */
    fun finish(id: String, report: BatchReport?) {
        handOff.remove(id)
        var ended = false
        state.update { current ->
            if (current?.id == id) {
                ended = true
                null
            } else {
                current
            }
        }
        if (ended && report != null) finished.value = report
    }

    /** The ViewModel hands its scanned games to the worker of the same process, so it need not rescan. */
    fun putEntries(id: String, entries: List<SourceGameEntry>) {
        handOff[id] = entries
    }

    fun takeEntries(id: String): List<SourceGameEntry>? = handOff.remove(id)

    private fun updateIf(id: String, change: BatchProgress.() -> BatchProgress) {
        state.update { current -> if (current?.id == id) current.change() else current }
    }

    /** Tests only: forget every reservation. */
    internal fun resetForTest() {
        state.value = null
        findings.value = null
        finished.value = null
        handOff.clear()
    }
}
