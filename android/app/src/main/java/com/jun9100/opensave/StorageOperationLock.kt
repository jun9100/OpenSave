package com.jun9100.opensave

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex

/**
 * Serializes operations that share the private source workspace.
 *
 * The Mutex is not reentrant: only the outermost caller takes it, and the
 * `*Locked` functions (e.g. in [com.jun9100.opensave.ops.GameOperations])
 * never take it themselves (plan §5.5). [owner] names the holder, so the UI
 * can say what it is waiting for ("等待自动备份完成…").
 */
internal object StorageOperationLock {
    const val APP = "OpenSave"
    const val AUTO_BACKUP = "自动备份"

    private val mutex = Mutex()
    private val currentOwner = MutableStateFlow<String?>(null)

    /** The label of whoever holds the lock now; null while it is free. */
    val owner: StateFlow<String?> = currentOwner.asStateFlow()

    /**
     * Runs [block] holding the lock as [owner]. When someone else holds it,
     * [onWait] is told who before this suspends until it is free.
     */
    suspend fun <T> withLock(
        owner: String = APP,
        onWait: (holder: String) -> Unit = {},
        block: suspend () -> T,
    ): T {
        if (!mutex.tryLock()) {
            currentOwner.value?.let(onWait)
            mutex.lock()
        }
        currentOwner.value = owner
        try {
            return block()
        } finally {
            currentOwner.value = null
            mutex.unlock()
        }
    }
}
