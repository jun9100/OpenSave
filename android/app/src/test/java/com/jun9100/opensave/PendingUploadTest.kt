package com.jun9100.opensave

import com.jun9100.opensave.activity.ActivityLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingUploadTest {
    private val config = GameSaveConfig(enabled = true, serviceUrl = "https://save.example", syncKey = "sync-key")

    @Test
    fun retryableManualUploadFailureIsScheduled() {
        val failures = listOf("source::$game" to GameSaveException("连接超时", retryable = true))

        assertEquals(listOf("source::$game"), PendingUploadPolicy.keysToRetry(config, failures))
    }

    @Test
    fun permanentFailureIsNotScheduled() {
        // 409 (the cloud head moved) and 401 (a refused key) must keep their own records, never loop.
        val conflict = listOf("source::$game" to GameSaveScopeConflictException("head-2"))
        val refused = listOf("source::$game" to GameSaveException("同步密钥被拒绝", retryable = false, httpStatus = 401))

        assertEquals(emptyList<String>(), PendingUploadPolicy.keysToRetry(config, conflict))
        assertEquals(emptyList<String>(), PendingUploadPolicy.keysToRetry(config, refused))
    }

    @Test
    fun noRetryWhileAutomaticUploadIsOffOrCloudIsUnconfigured() {
        val failures = listOf("source::$game" to GameSaveException("连接超时", retryable = true))

        assertEquals(emptyList<String>(), PendingUploadPolicy.keysToRetry(config.copy(enabled = false), failures))
        assertEquals(emptyList<String>(), PendingUploadPolicy.keysToRetry(config.copy(serviceUrl = ""), failures))
        assertEquals(emptyList<String>(), PendingUploadPolicy.keysToRetry(config.copy(syncKey = ""), failures))
    }

    @Test
    fun allUploadedClearsTheQueue() {
        val decision = PendingUploadPolicy.decide(
            listOf(
                "source::one" to PendingUploadPolicy.Outcome.Uploaded,
                "source::two" to PendingUploadPolicy.Outcome.Uploaded,
            ),
            attempt = 0,
        )

        assertFalse(decision.retry)
        assertEquals(emptyList<String>(), decision.keepKeys)
        assertEquals(2, decision.uploaded)
        assertEquals("已补传 2 款", PendingUploadPolicy.entry(decision).text)
        assertEquals(ActivityLevel.SUCCESS, PendingUploadPolicy.entry(decision).level)
    }

    @Test
    fun partialRetryableFailureKeepsOnlyTheFailedKeys() {
        val decision = PendingUploadPolicy.decide(
            listOf(
                "source::one" to PendingUploadPolicy.Outcome.Uploaded,
                "source::two" to PendingUploadPolicy.Outcome.Failed("二", "连接超时", retryable = true),
                "source::three" to PendingUploadPolicy.Outcome.Failed("三", "云端当前版本已变化", retryable = false),
            ),
            attempt = 0,
        )

        assertTrue(decision.retry)
        assertEquals(listOf("source::two"), decision.keepKeys)
        assertEquals(
            listOf("二" to "连接超时", "三" to "云端当前版本已变化"),
            decision.failures,
        )
    }

    @Test
    fun exhaustedRetriesStopButKeepTheFailureRecord() {
        val failures = listOf(
            "source::one" to PendingUploadPolicy.Outcome.Failed("一", "连接超时", retryable = true),
            "source::two" to PendingUploadPolicy.Outcome.Failed("二", "云端当前版本已变化", retryable = false),
        )

        // MAX_RETRIES is how many retries may follow the first attempt.
        val decision = PendingUploadPolicy.decide(failures, attempt = PendingUploadPolicy.MAX_RETRIES)

        assertFalse(decision.retry)
        assertEquals(emptyList<String>(), decision.keepKeys)
        val entry = PendingUploadPolicy.entry(decision)
        assertEquals("补传失败", entry.text)
        assertEquals(ActivityLevel.WARNING, entry.level)
        assertTrue(entry.detail.contains("连接超时"))
        assertTrue(entry.detail.contains("云端当前版本已变化"))
    }

    @Test
    fun retryabilityMirrorsTheExistingCloudClassification() {
        assertTrue(PendingUploadPolicy.retryable(GameSaveException("超时", retryable = true)))
        assertFalse(PendingUploadPolicy.retryable(GameSaveException("拒绝", retryable = false)))
        // This mirrors the auto-backup worker: an unexpected error may still pass next time.
        assertTrue(PendingUploadPolicy.retryable(IllegalStateException("boom")))
        assertTrue(PendingUploadPolicy.retryable(null))
    }

    @Test
    fun nothingToDoIsNotAFailure() {
        val decision = PendingUploadPolicy.decide(
            listOf(
                "source::one" to PendingUploadPolicy.Outcome.NothingToDo,
                "source::two" to PendingUploadPolicy.Outcome.NothingToDo,
            ),
            attempt = 0,
        )

        assertFalse(decision.retry)
        assertEquals(emptyList<String>(), decision.keepKeys)
        assertEquals(emptyList<Pair<String, String>>(), decision.failures)
        assertEquals("补传：没有需要上传的版本", PendingUploadPolicy.entry(decision).text)
    }

    private companion object {
        const val game = "game-key"
    }
}
