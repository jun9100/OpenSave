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
            sdkInt = 33,
        )
        assertEquals(listOf("Eden 用户数据", "PC 游戏公共目录 pc-game", "GameHub"), recommendations.map { it.title })
        val eden = recommendations[0]
        assertEquals("dev.eden.eden_emulator.user" to "root/", eden.authority to eden.documentId)
        assertFalse(eden.granted)
        assertTrue(recommendations[1].granted)
        // GameHub has no way in without Root.
        assertTrue(recommendations[2].needsRoot)
    }

    @Test
    fun edenSwitch2ForksAreGrantedThroughTheirOwnDocumentsProvider() {
        val recommendations = FolderRecommendations.build(
            installed = { it in setOf("dev.eglantine.eglantine", "dev.eden.nx2") },
            folderExists = { false },
            grantedKeys = emptySet(),
            sdkInt = 33,
        )
        assertEquals(listOf("Eglantine 用户数据", "Eden SX2A 用户数据"), recommendations.map { it.title })
        assertEquals(
            listOf("dev.eglantine.eglantine.user" to "root/", "dev.eden.nx2.user" to "root/"),
            recommendations.map { it.authority to it.documentId },
        )
        assertFalse(recommendations.any { it.needsRoot })
    }

    @Test
    fun androidTenAndOlderCanGrantTheWholeStorageOnce() {
        val before = FolderRecommendations.build(
            installed = { it == "com.xiaoji.emulator" },
            folderExists = { it in setOf("epsxe", "XiaoJi") },
            grantedKeys = emptySet(),
            sdkInt = 29,
        )
        assertEquals(listOf("整个内部存储（推荐）", "ePSXe（小鸡模拟器 PS1）", "小鸡模拟器 XiaoJi"), before.map { it.title })
        assertEquals("primary:", before[0].documentId)

        // Granting it covers every shared folder below it.
        val after = FolderRecommendations.build(
            installed = { false },
            folderExists = { it == "epsxe" },
            grantedKeys = setOf("com.android.externalstorage.documents/primary:"),
            sdkInt = 29,
        )
        assertTrue(after.all { it.granted })

        // Android 11 refuses the storage root.
        val eleven = FolderRecommendations.build({ false }, { it == "epsxe" }, emptySet(), sdkInt = 30)
        assertEquals(listOf("ePSXe（小鸡模拟器 PS1）"), eleven.map { it.title })
    }
}
