package com.jun9100.opensave

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SaveSignatureTest {
    @Test
    fun filesCombineIntoCountSizeAndNewestSecond() {
        val signature = SaveSignature.combine(
            listOf(
                SaveSignature.ofFile(100, 1_700_000_000_999),
                SaveSignature.ofFile(-5, 1_600_000_000_000),
                SaveSignature.ofFile(23, 1_700_000_001_000),
            ),
        )
        assertEquals(SaveSignature(3, 123, 1_700_000_001), signature)
        assertEquals("s1:3:123:1700000001", signature.encode())
        assertEquals(SaveSignature.EMPTY, SaveSignature.combine(emptyList()))
    }

    @Test
    fun anyUnknownLocationMakesTheGameUnknown() {
        val known = SaveSignature(2, 10, 5)
        assertEquals(SaveSignature(4, 20, 5), SaveSignature.ofLocations(listOf(known, known)))
        assertNull(SaveSignature.ofLocations(listOf(known, null)))
        assertNull(SaveSignature.ofLocations(emptyList()))
        // An empty but present location is known.
        assertEquals(SaveSignature.EMPTY, SaveSignature.ofLocations(listOf(SaveSignature.EMPTY)))
    }

    @Test
    fun aChangedFileChangesTheSignature() {
        val before = SaveSignature.combine(listOf(SaveSignature.ofFile(10, 1_000_000), SaveSignature.ofFile(20, 2_000_000)))
        val touched = SaveSignature.combine(listOf(SaveSignature.ofFile(10, 1_000_000), SaveSignature.ofFile(20, 3_000_000)))
        val grown = SaveSignature.combine(listOf(SaveSignature.ofFile(11, 1_000_000), SaveSignature.ofFile(20, 2_000_000)))
        assertNotEquals(before.encode(), touched.encode())
        assertNotEquals(before.encode(), grown.encode())
    }

    @Test
    fun rootListingParsesPrintfFractionsStatSecondsAndMissingSections() {
        val marker = SaveSignature.SECTION_MARKER
        val output = """
            $marker
            100 1700000000.1234567890
            20 1700000005.9
            $marker
            ${SaveSignature.MISSING_MARKER}
            $marker
            $marker
            7 1600000000
        """.trimIndent()
        assertEquals(
            listOf(
                SaveSignature(2, 120, 1_700_000_005),
                null,
                SaveSignature.EMPTY,
                SaveSignature(1, 7, 1_600_000_000),
            ),
            SaveSignature.parseRootListing(output),
        )
    }

    @Test
    fun rootListingWithAnUnreadableLineIsUnknownNotPartial() {
        val marker = SaveSignature.SECTION_MARKER
        // e.g. a toybox find whose -printf leaves %T@ unexpanded.
        val output = "$marker\n100 1700000000\n100 %T@\n$marker\n5 10\n"
        assertEquals(listOf(null, SaveSignature(1, 5, 10)), SaveSignature.parseRootListing(output))
        // Noise before the first section is ignored.
        assertEquals(listOf(SaveSignature.EMPTY), SaveSignature.parseRootListing("garbage\n$marker\n"))
    }

    @Test
    fun sameSecondInMillisAndSecondsGivesTheSameSignature() {
        val fromSaf = SaveSignature.ofFile(42, 1_700_000_000_750)
        val fromRoot = SaveSignature.parseRootListing("${SaveSignature.SECTION_MARKER}\n42 1700000000.75\n").single()
        assertEquals(fromSaf, fromRoot)
    }
}
