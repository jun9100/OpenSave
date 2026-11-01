package com.jun9100.opensave

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SwitchTitleNameSourcesTest {
    private val preferences = """
        <map>
            <string name="GamesSortType">0</string>
            <set name="Games">
                <string>{&quot;title&quot;:&quot;ハッピーダンガンロンパS&quot;,&quot;path&quot;:&quot;content://com.android.externalstorage.documents/tree/primary%3Aswitch-roms/document/primary%3Aswitch-roms%2FDanganronpa%20Happy%20S%20%5B0100AA50140CA000%5D.xci&quot;,&quot;programId&quot;:&quot;72244854948405248&quot;}</string>
                <string>{&quot;title&quot;:&quot;&quot;,&quot;path&quot;:&quot;&quot;,&quot;programId&quot;:&quot;1&quot;}</string>
            </set>
        </map>
    """.trimIndent()

    @Test
    fun readsTitlesAndRomFoldersFromTheEmulatorGameList() {
        assertEquals(mapOf("0100AA50140CA000" to "ハッピーダンガンロンパS"), SwitchTitleNameSources.fromEmulatorPreferences(preferences))
        assertEquals(setOf("/storage/emulated/0/switch-roms"), SwitchTitleNameSources.romDirectories(preferences))
        assertEquals(emptyMap<String, String>(), SwitchTitleNameSources.fromEmulatorPreferences("<map/>"))
    }

    @Test
    fun namesComeFromRomFileNamesWithABracketedTitleId() {
        assertEquals(
            "01001AB0140F2000" to "Danganronpa V3 Anniversary Edition",
            SwitchTitleNameSources.fromRomFileName("Danganronpa V3 Anniversary Edition [01001AB0140F2000] [v1.0.2] (MOD10.2.0).xci"),
        )
        assertEquals(
            "01007B4014888800" to "Rune Factory 5",
            SwitchTitleNameSources.fromRomFileName("[switch]Rune Factory 5[01007b4014888800][v1.0.1].xci"),
        )
        assertNull(SwitchTitleNameSources.fromRomFileName("SUPER MARIO ODYSSEY [1.3.0].xci"))
        assertNull(SwitchTitleNameSources.fromRomFileName("[0100000000010000].xci"))
    }

    @Test
    fun programIdIsTheDecimalTitleId() {
        assertEquals("0100000000010000", SwitchTitleNameSources.programIdToTitleId("72057594037993472"))
        assertNull(SwitchTitleNameSources.programIdToTitleId("not a number"))
    }

    @Test
    fun onlyPrimaryStorageTreeUrisBecomePaths() {
        assertNull(SwitchTitleNameSources.treeUriToPath("content://x/tree/1234-5678%3Aroms/document/1234-5678%3Aroms%2Fa.xci"))
        assertNull(SwitchTitleNameSources.treeUriToPath("content://x/tree/primary%3A..%2F/document/primary%3A..%2Fa.xci"))
    }
}
