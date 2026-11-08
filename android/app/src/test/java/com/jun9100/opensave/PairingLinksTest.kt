package com.jun9100.opensave

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingLinksTest {
    @Test
    fun readsAPairingLinkWithSurroundingWhitespace() {
        val link = PairingLinks.parse(
            "  SAVeVAULT://PAIR?v=1&server=http%3A%2F%2F192.168.1.5%3A8788&code=abcd-efgh \n",
        ).getOrThrow()

        assertEquals("http://192.168.1.5:8788", link.serverUrl)
        assertEquals("ABCDEFGH", link.code)
        assertEquals("192.168.1.5:8788", link.host)
    }

    @Test
    fun keepsAnEncodedPathAndDropsTheDefaultPortFromTheHost() {
        val path = PairingLinks.parse(
            "savevault://pair?v=1&server=https%3A%2F%2Fnas.example%2Fgame-save&code=ABCD-EFGH",
        ).getOrThrow()
        assertEquals("https://nas.example/game-save", path.serverUrl)
        assertEquals("nas.example", path.host)

        val explicitDefault = PairingLinks.parse(
            "savevault://pair?v=1&server=http%3A%2F%2Fnas.example%3A80&code=ABCD-EFGH",
        ).getOrThrow()
        assertEquals("nas.example", explicitDefault.host)
    }

    @Test
    fun rejectsUnsupportedLinksAndServers() {
        listOf(
            "savevault://pair?v=2&server=http%3A%2F%2Fnas.example&code=ABCD-EFGH",
            "savevault://pair?server=http%3A%2F%2Fnas.example&code=ABCD-EFGH",
            "savevault://pair?v=1&server=ftp%3A%2F%2Fnas.example&code=ABCD-EFGH",
            "savevault://pair?v=1&server=http%3A%2F%2Fnas.example&code=ABCD-EFGH&extra=1",
            "savevault://pair?v=1&server=http%3A%2F%2Fnas.example&code=ABCD-EFGH&code=JKMN-PQRS",
            "savevault://pair?v=1&server=http%3A%2F%2Fnas.example",
            "savevault://pair?v=1&server=http%3A%2F%2Fuser%3Apass%40nas.example&code=ABCD-EFGH",
            "savevault://pair?v=1&server=http%3A%2F%2Fnas.example%3Fq%3D1&code=ABCD-EFGH",
            "savevault://pair?v=1&server=http%3A%2F%2Fnas.example&code=ABCD-EFU!",
            "savevault://pair/extra?v=1&server=http%3A%2F%2Fnas.example&code=ABCD-EFGH",
            "savevault://other?v=1&server=http%3A%2F%2Fnas.example&code=ABCD-EFGH",
            "https://nas.example/pair?v=1&server=http%3A%2F%2Fnas.example&code=ABCD-EFGH",
            "",
        ).forEach { text -> assertTrue(text, PairingLinks.parse(text).isFailure) }
    }

    @Test
    fun rejectsMalformedParameters() {
        listOf(
            "savevault://pair?v=1&server=http%3A%2F%2Fnas.example&code=ABCD-EFGH&",
            "savevault://pair?v=1&server&code=ABCD-EFGH",
            "savevault://pair?v=1&server=http%3A%2F%2Fnas.example&code=ABCD-EFGH#fragment",
            "savevault://pair?v=1&server=http%3A%2F%2Fnas.example%2F%ZZ&code=ABCD-EFGH",
        ).forEach { text -> assertTrue(text, PairingLinks.parse(text).isFailure) }
    }

    @Test
    fun keepsALiteralPlusInTheServerAddress() {
        val link = PairingLinks.parse(
            "savevault://pair?v=1&server=http%3A%2F%2Fnas.example%2Fsave+dir&code=ABCD-EFGH",
        ).getOrThrow()

        assertEquals("http://nas.example/save+dir", link.serverUrl)
        assertFalse(link.host.contains("+"))
    }
}
