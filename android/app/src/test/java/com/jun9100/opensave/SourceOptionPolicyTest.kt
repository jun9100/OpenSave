package com.jun9100.opensave

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceOptionPolicyTest {
    private val rootOption = SaveSourceOption(
        source = SourceSelection.Root("/data/user/0/org.yuzu.yuzu_emu/files"),
        label = "Yuzu",
        saveType = GameSaveTypes.SWITCH,
        requiredAccess = SourceAccessCapability.ROOT,
        selected = true,
    )

    @Test
    fun aMissingDirectoryIsShownUncheckedAndCannotBeChecked() {
        val missing = SourceAccessStatus.unavailable(SourceAccessCapability.ROOT, "目录不存在")

        val unreachable = SourceOptionPolicy.unreachable(rootOption, missing, rootChecked = true, rootAvailable = true)

        assertTrue(unreachable)
        assertFalse(SourceOptionPolicy.displayedChecked(rootOption, unreachable))
    }

    @Test
    fun aReadableSourceKeepsItsChoice() {
        val unreachable = SourceOptionPolicy.unreachable(
            rootOption,
            SourceAccessStatus.rootOnly(),
            rootChecked = true,
            rootAvailable = true,
        )

        assertFalse(unreachable)
        assertTrue(SourceOptionPolicy.displayedChecked(rootOption, unreachable))
        assertFalse(SourceOptionPolicy.displayedChecked(rootOption.copy(selected = false), unreachable))
    }

    @Test
    fun anUnscannedSourceIsOnlyBlockedOnceRootWasRefused() {
        assertFalse(SourceOptionPolicy.unreachable(rootOption, null, rootChecked = false, rootAvailable = false))
        assertTrue(SourceOptionPolicy.unreachable(rootOption, null, rootChecked = true, rootAvailable = false))
        assertFalse(SourceOptionPolicy.unreachable(rootOption, null, rootChecked = true, rootAvailable = true))
    }
}
