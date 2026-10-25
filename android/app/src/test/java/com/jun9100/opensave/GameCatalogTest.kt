package com.jun9100.opensave

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GameCatalogTest {
    @Test
    fun localizesKnownGameHubNamesAndKeepsOriginalName() {
        val presentation = GameCatalog.present(
            DetectedSave(
                kind = "gamehub-pc",
                relativePath = "Wandering_Sword/Saved",
                note = "test",
                displayName = "Wandering_Sword",
                gameKey = "pc:wandering sword",
            ),
        )

        assertEquals("逸剑风云决", presentation.localizedName)
        assertEquals("Wandering Sword", presentation.originalName)
        assertEquals(GameIconKey.PC, presentation.iconKey)
    }

    @Test
    fun localizesSteamAppIdCandidate() {
        val presentation = GameCatalog.present(
            DetectedSave(
                kind = "gamehub-pc",
                relativePath = "userdata/1245620",
                note = "test",
                displayName = "Steam AppID 1245620",
                gameKey = "steam:1245620",
                candidate = true,
            ),
        )

        assertEquals("艾尔登法环", presentation.localizedName)
        assertEquals("ELDEN RING", presentation.originalName)
        assertEquals("Steam AppID 1245620", presentation.identity)
    }

    @Test
    fun localizesObservedGameHubSteamAppIds() {
        val brante = GameCatalog.present(
            DetectedSave(
                kind = "gamehub-pc",
                relativePath = "userdata/1272160",
                note = "test",
                displayName = "Steam AppID 1272160",
                gameKey = "steam:1272160",
                candidate = true,
            ),
        )
        val survivors = GameCatalog.present(
            DetectedSave(
                kind = "gamehub-pc",
                relativePath = "userdata/1794680",
                note = "test",
                displayName = "Steam AppID 1794680",
                gameKey = "steam:1794680",
                candidate = true,
            ),
        )

        assertEquals("布兰特爵士的生平和苦难", brante.localizedName)
        assertEquals("吸血鬼幸存者", survivors.localizedName)
    }

    @Test
    fun keepsUnknownSwitchTitleIdAsIdentityWithoutInventingName() {
        val presentation = GameCatalog.present(
            DetectedSave(
                kind = "yuzu-switch",
                relativePath = "nand/user/save/0100ABCDEF123456",
                titleId = "0100ABCDEF123456",
                note = "test",
                displayName = "Switch 0100ABCDEF123456",
                gameKey = "switch:0100ABCDEF123456",
            ),
        )

        assertNull(presentation.localizedName)
        assertNull(presentation.originalName)
        assertEquals("0100ABCDEF123456", presentation.identity)
        assertEquals("未命名游戏", presentation.primaryName)
        assertEquals(GameIconKey.SWITCH, presentation.iconKey)
        assertEquals(
            "https://tinfoil.media/ti/0100ABCDEF123456/0/0/",
            presentation.coverUrl,
        )
        assertEquals("switch-0100abcdef123456", presentation.coverCacheKey)
    }

    @Test
    fun suppliesKnownSwitchGameArtworkAndChineseName() {
        val presentation = GameCatalog.present(
            DetectedSave(
                kind = "yuzu-switch",
                relativePath = "nand/user/save/0100000000010000",
                titleId = "0100000000010000",
                note = "test",
                displayName = "Switch 0100000000010000",
                gameKey = "switch:0100000000010000",
            ),
        )

        assertEquals("超级马力欧 奥德赛", presentation.localizedName)
        assertEquals("Super Mario Odyssey", presentation.originalName)
        assertTrue(presentation.coverUrl?.startsWith("https://tinfoil.media/ti/") == true)
    }

    @Test
    fun localizesKnownAndroidPackage() {
        val presentation = GameCatalog.present(
            DetectedSave(
                kind = "android-app",
                relativePath = "Android/data/dev.eden.eden_emulator",
                note = "test",
                displayName = "dev.eden.eden_emulator",
                gameKey = "android:dev.eden.eden_emulator",
                candidate = true,
            ),
        )

        assertEquals("Eden 模拟器", presentation.localizedName)
        assertEquals("Eden", presentation.originalName)
        assertEquals("dev.eden.eden_emulator", presentation.androidPackageName)
        assertEquals(GameIconKey.ANDROID, presentation.iconKey)
    }
}
