package com.jun9100.opensave

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FolderRecommendationsTest {
    @Test
    fun onlyWhatThisDeviceHasIsRecommended() {
        val recommendations = FolderRecommendations.build(
            installed = { it in setOf("dev.eden.eden_emulator", "com.xiaoji.egggame") },
            folderExists = { it == "pc-game" },
            grantedKeys = setOf("com.android.externalstorage.documents/primary:pc-game"),
        )
        assertEquals(listOf("Eden 用户数据", "PC 游戏公共目录 pc-game", "GameHub"), recommendations.map { it.title })
        val eden = recommendations[0]
        assertEquals("dev.eden.eden_emulator.user" to "root/", eden.authority to eden.documentId)
        assertFalse(eden.granted)
        assertTrue(recommendations[1].granted)
        // GameHub has no way in without Root.
        assertTrue(recommendations[2].needsRoot)
    }
}
