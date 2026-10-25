package com.jun9100.opensave

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SteamMetadataRepositoryTest {
    @Test
    fun parsesChineseSteamName() {
        val result = SteamMetadataRepository.parse(
            "{\"475550\":{\"success\":true,\"data\":{\"name\":\"监视者\"}}}",
            "475550",
        )

        assertEquals("监视者", result?.name)
        assertTrue(result?.localized == true)
    }

    @Test
    fun keepsEnglishSteamNameAsUnlocalized() {
        val result = SteamMetadataRepository.parse(
            "{\"475550\":{\"success\":true,\"data\":{\"name\":\"Beholder\"}}}",
            "475550",
        )

        assertEquals("Beholder", result?.name)
        assertFalse(result?.localized == true)
    }

    @Test
    fun ignoresUnsuccessfulSteamResponse() {
        val result = SteamMetadataRepository.parse(
            "{\"612070\":{\"success\":false}}",
            "612070",
        )

        assertNull(result)
    }
}
