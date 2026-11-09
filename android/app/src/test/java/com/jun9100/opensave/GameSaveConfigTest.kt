package com.jun9100.opensave

import com.jun9100.opensave.ops.ready
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GameSaveConfigTest {
    private val config = GameSaveConfig(
        enabled = true,
        serviceUrl = "https://save.example",
        syncKey = "super-secret-sync-key",
    )

    @Test
    fun authBlockedStopsEveryReadyPath() {
        assertTrue(config.ready)
        assertFalse(config.copy(authBlocked = "token_revoked").ready)
        assertFalse(config.copy(authBlocked = "shared_token_disabled").ready)
    }

    @Test
    fun toStringNeverPrintsTheSyncKey() {
        val text = config.toString()

        assertTrue(text.contains("syncKey=***"))
        assertFalse(text.contains(config.syncKey))

        val imported = ImportedGameSaveConfig("https://save.example", config.syncKey)
        assertFalse(imported.toString().contains(config.syncKey))
    }
}
