package com.jun9100.opensave

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingInputTest {
    @Test
    fun onlyTheSaveVaultSchemeIsTreatedAsALink() {
        assertTrue(PairingInput.isLink("  SAVeVAULT://PAIR?v=1&server=http%3A%2F%2Fnas.example&code=ABCD-EFGH "))
        assertFalse(PairingInput.isLink("savevault:/pair?v=1&server=http%3A%2F%2Fnas.example&code=ABCD-EFGH"))
        assertFalse(PairingInput.isLink("https://nas.example/pair?code=ABCD-EFGH"))
        assertFalse(PairingInput.isLink("ABCD-EFGH"))
        assertFalse(PairingInput.isLink(""))
    }

    @Test
    fun aLinkKeepsItsOwnServerAndIgnoresTheAddressField() {
        val link = PairingInput.resolve(
            serviceUrl = " ",
            codeOrLink = "  savevault://pair?v=1&server=https%3A%2F%2Fnas.example%2Fgame-save&code=abcd-efgh ",
        ).getOrThrow()

        assertEquals("https://nas.example/game-save", link.serverUrl)
        assertEquals("ABCDEFGH", link.code)
        assertEquals("nas.example", link.host)
    }

    @Test
    fun anAddressAndCodeBecomeAPairingLink() {
        val link = PairingInput.resolve(" http://192.168.1.5:8788/ ", "ab cd-ef gh").getOrThrow()

        assertEquals("http://192.168.1.5:8788", link.serverUrl)
        assertEquals("ABCDEFGH", link.code)
        assertEquals("192.168.1.5:8788", link.host)
    }

    @Test
    fun missingOrInvalidFieldsExplainWhatToFix() {
        assertEquals(
            "请填写 SaveVault 云端地址",
            PairingInput.resolve(" ", "ABCD-EFGH").exceptionOrNull()?.message,
        )
        assertEquals(
            "配对码应为 8 位，例如 ABCD-EFGH",
            PairingInput.resolve("http://nas.example", "ABC").exceptionOrNull()?.message,
        )
        assertEquals(
            "SaveVault 云端只支持 http 或 https",
            PairingInput.resolve("ftp://nas.example", "ABCD-EFGH").exceptionOrNull()?.message,
        )
        assertTrue(PairingInput.resolve("http://nas.example", "savevault://other?v=1").isFailure)
    }
}
