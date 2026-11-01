package com.jun9100.opensave

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GameSaveConfigLinkTest {
    @Test
    fun readsAnEncodedLink() {
        val config = GameSaveConfigLink.parse(
            "  opensave://game-save?url=http%3A%2F%2F192.168.1.5%3A8788%2F&token=abc%2Bdef-123  \n",
        ).getOrThrow()

        assertEquals("http://192.168.1.5:8788", config.serviceUrl)
        assertEquals("abc+def-123", config.syncKey)
    }

    @Test
    fun readsPlainJson() {
        val config = GameSaveConfigLink.parse("""{"url": "https://nas.example/v1/opensave", "token": "k-1234567890"}""").getOrThrow()

        assertEquals("https://nas.example", config.serviceUrl)
        assertEquals("k-1234567890", config.syncKey)
    }

    @Test
    fun rejectsAddressesThatAreNotHttp() {
        listOf(
            "opensave://game-save?url=ftp%3A%2F%2Fnas&token=abc",
            "opensave://game-save?url=file%3A%2F%2F%2Fsdcard&token=abc",
            "opensave://game-save?url=javascript%3Aalert(1)&token=abc",
            """{"url":"content://x","token":"abc"}""",
        ).forEach { text -> assertTrue(text, GameSaveConfigLink.parse(text).isFailure) }
    }

    @Test
    fun rejectsAMissingToken() {
        listOf(
            "opensave://game-save?url=http%3A%2F%2Fnas%3A8788",
            "opensave://game-save?url=http%3A%2F%2Fnas%3A8788&token=",
            "opensave://game-save?url=http%3A%2F%2Fnas%3A8788&token=%20%20",
            """{"url":"http://nas:8788"}""",
            """{"url":"http://nas:8788","token":""}""",
        ).forEach { text -> assertTrue(text, GameSaveConfigLink.parse(text).isFailure) }
    }

    @Test
    fun rejectsExtraJunk() {
        listOf(
            "看这里 opensave://game-save?url=http%3A%2F%2Fnas&token=abc",
            "opensave://game-save?url=http%3A%2F%2Fnas&token=abc 还有别的",
            "opensave://game-save?url=http%3A%2F%2Fnas&token=abc\nopensave://game-save?url=http%3A%2F%2Fevil&token=x",
            "opensave://game-save?url=http%3A%2F%2Fnas&token=abc&device=x",
            "opensave://game-save?url=http%3A%2F%2Fnas&token=abc&token=def",
            "opensave://game-save?url=http%3A%2F%2Fnas&token=abc#frag",
            "opensave://game-save/extra?url=http%3A%2F%2Fnas&token=abc",
            "opensave://other?url=http%3A%2F%2Fnas&token=abc",
            "https://nas:8788",
            """{"url":"http://nas","token":"abc","extra":1}""",
            """{"url":"http://nas","token":"abc"} trailing""",
            """{"url":"http://nas","token":123}""",
            "opensave://game-save?url=http%3A%2F%2Fuser%40nas%3Fq%3D1&token=abc",
            "",
            null,
        ).forEach { text -> assertTrue(text.toString(), GameSaveConfigLink.parse(text).isFailure) }
    }

    @Test
    fun theMaskNeverShowsTheWholeKey() {
        val masked = GameSaveConfigLink.maskToken("super-secret-sync-key")
        assertEquals("su••••••••ey", masked)
        assertFalse(masked.contains("secret"))
        assertEquals("••••••••", GameSaveConfigLink.maskToken("short"))
    }
}
