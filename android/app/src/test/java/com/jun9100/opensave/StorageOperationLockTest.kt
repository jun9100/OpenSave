package com.jun9100.opensave

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StorageOperationLockTest {
    @Test
    fun ownerIsPublishedAndAWaiterIsToldWhoHoldsTheLock() = runBlocking {
        assertNull(StorageOperationLock.owner.value)
        val held = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val holder = launch(Dispatchers.Default) {
            StorageOperationLock.withLock(StorageOperationLock.AUTO_BACKUP) {
                held.complete(Unit)
                release.await()
            }
        }
        held.await()
        assertEquals(StorageOperationLock.AUTO_BACKUP, StorageOperationLock.owner.value)

        val waitedFor = CompletableDeferred<String>()
        val waiter = async(Dispatchers.Default) {
            StorageOperationLock.withLock("批量备份", onWait = { waitedFor.complete(it) }) {
                StorageOperationLock.owner.value
            }
        }
        assertEquals(StorageOperationLock.AUTO_BACKUP, withTimeout(5_000) { waitedFor.await() })
        release.complete(Unit)
        assertEquals("批量备份", waiter.await())
        holder.join()
        assertNull(StorageOperationLock.owner.value)
    }
}
