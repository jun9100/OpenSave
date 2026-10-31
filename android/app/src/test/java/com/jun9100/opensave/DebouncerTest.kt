package com.jun9100.opensave

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class DebouncerTest {
    @Test
    fun rapidSubmissionsRunOnlyTheLastAction() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val runs = AtomicInteger()
            val lastValue = AtomicInteger()
            val debouncer = Debouncer(scope, delayMillis = 100)
            repeat(5) { index ->
                debouncer.submit {
                    runs.incrementAndGet()
                    lastValue.set(index)
                }
                delay(10)
            }
            delay(400)

            assertEquals(1, runs.get())
            assertEquals(4, lastValue.get())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun cancelDropsThePendingAction() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val runs = AtomicInteger()
            val debouncer = Debouncer(scope, delayMillis = 50)
            debouncer.submit { runs.incrementAndGet() }
            debouncer.cancel()
            delay(200)

            assertEquals(0, runs.get())
        } finally {
            scope.cancel()
        }
    }
}
