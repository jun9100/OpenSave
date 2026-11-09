package com.jun9100.opensave

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PairingCodeTest {
    @Test
    fun normalizesCaseSpacesAndConfusingCharacters() {
        assertEquals("ABCDEFGH", PairingCode.normalize("abcd-efgh"))
        assertEquals("ABCDEFGH", PairingCode.normalize(" AB CD EF GH "))
        assertEquals("01112345", PairingCode.normalize("O1IL-2345"))
    }

    @Test
    fun rejectsCodesThatCannotBeCrockfordBase32() {
        assertNull(PairingCode.normalize("ABCDEFG"))
        assertNull(PairingCode.normalize("ABCDEFGHI"))
        assertNull(PairingCode.normalize("ABCD-EFU!"))
        assertNull(PairingCode.normalize(""))
    }

    @Test
    fun displaysTheCanonicalCodeInTwoGroups() {
        assertEquals("ABCD-EFGH", PairingCode.display("ABCDEFGH"))
        assertEquals("ABCD-EFGH", PairingCode.display("abcd efgh"))
    }
}
