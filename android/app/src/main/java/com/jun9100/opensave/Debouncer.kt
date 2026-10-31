package com.jun9100.opensave

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Runs only the last action submitted within [delayMillis]. Rapid source
 * toggles from a gamepad therefore trigger one rescan instead of one per press.
 */
internal class Debouncer(
    private val scope: CoroutineScope,
    private val delayMillis: Long,
) {
    private var pending: Job? = null

    @Synchronized
    fun submit(action: suspend () -> Unit) {
        pending?.cancel()
        pending = scope.launch {
            delay(delayMillis)
            action()
        }
    }

    @Synchronized
    fun cancel() {
        pending?.cancel()
        pending = null
    }
}
