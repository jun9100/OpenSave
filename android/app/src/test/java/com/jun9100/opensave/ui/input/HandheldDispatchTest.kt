package com.jun9100.opensave.ui.input

import com.jun9100.opensave.LibraryView
import com.jun9100.opensave.OpenSavePage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HandheldDispatchTest {
    private val keys = listOf("a", "b", "c", "d")

    @Test
    fun l1AndR1CycleThroughTheTopLevelPages() {
        assertEquals(OpenSavePage.SOURCES, HandheldDispatch.pageAfter(OpenSavePage.LIBRARY, HandheldCommand.NEXT_PAGE))
        assertEquals(OpenSavePage.SETTINGS, HandheldDispatch.pageAfter(OpenSavePage.SOURCES, HandheldCommand.NEXT_PAGE))
        assertEquals(OpenSavePage.LIBRARY, HandheldDispatch.pageAfter(OpenSavePage.SETTINGS, HandheldCommand.NEXT_PAGE))
        assertEquals(OpenSavePage.SETTINGS, HandheldDispatch.pageAfter(OpenSavePage.LIBRARY, HandheldCommand.PREVIOUS_PAGE))
        assertNull(HandheldDispatch.pageAfter(OpenSavePage.LIBRARY, HandheldCommand.GAME_MENU))
    }

    @Test
    fun l1AndR1FromSyncAndTasksCycleFromThePageItWasOpenedFrom() {
        // "同步与任务" is not on the rail; the shell passes the page it was opened from.
        assertEquals(OpenSavePage.LIBRARY, HandheldDispatch.pageAfter(OpenSavePage.SETTINGS, HandheldCommand.NEXT_PAGE))
        // A page that is not top-level never makes the cycle stick.
        assertEquals(OpenSavePage.SOURCES, HandheldDispatch.pageAfter(OpenSavePage.SYNC, HandheldCommand.NEXT_PAGE))
    }

    @Test
    fun pagesSavedByOlderBuildsAreMigrated() {
        assertEquals(OpenSavePage.SETTINGS, OpenSavePage.fromSavedName("MANAGEMENT"))
        assertEquals(OpenSavePage.SOURCES, OpenSavePage.fromSavedName("SOURCES"))
        assertNull(OpenSavePage.fromSavedName("UNKNOWN"))
        assertNull(OpenSavePage.fromSavedName(null))
        assertEquals(listOf(OpenSavePage.LIBRARY, OpenSavePage.SOURCES, OpenSavePage.SETTINGS), OpenSavePage.topLevelPages)
    }

    @Test
    fun targetIsTheFocusedRowThenTheShownGame() {
        assertEquals("c", HandheldDispatch.targetGame("c", "a", keys, landscape = true, detailOpen = false))
        assertEquals("a", HandheldDispatch.targetGame(null, "a", keys, landscape = true, detailOpen = false))
        // Landscape always shows a game: the first row when none was chosen.
        assertEquals("a", HandheldDispatch.targetGame(null, null, keys, landscape = true, detailOpen = false))
        // A focused key filtered out of the list does not count.
        assertEquals("b", HandheldDispatch.targetGame("gone", "b", keys, landscape = true, detailOpen = false))
    }

    @Test
    fun portraitTargetsTheOpenDetailOrTheFocusedRowOnly() {
        assertEquals("b", HandheldDispatch.targetGame("c", "b", keys, landscape = false, detailOpen = true))
        assertEquals("c", HandheldDispatch.targetGame("c", "b", keys, landscape = false, detailOpen = false))
        assertNull(HandheldDispatch.targetGame(null, "b", keys, landscape = false, detailOpen = false))
        assertNull(HandheldDispatch.targetGame(null, null, emptyList(), landscape = true, detailOpen = false))
    }

    @Test
    fun focusReturnsToTheLastRowThenTheShownGameThenTheFirst() {
        assertEquals("c", HandheldDispatch.preferredFocusKey("c", "b", keys))
        assertEquals("b", HandheldDispatch.preferredFocusKey("gone", "b", keys))
        assertEquals("a", HandheldDispatch.preferredFocusKey(null, null, keys))
        assertNull(HandheldDispatch.preferredFocusKey("c", "b", emptyList()))
    }

    @Test
    fun pageJumpMovesOneScreenAndStaysInTheList() {
        assertEquals(6, HandheldDispatch.pageJumpIndex(currentIndex = 0, visibleRows = 7, total = 90, forward = true))
        assertEquals(89, HandheldDispatch.pageJumpIndex(currentIndex = 86, visibleRows = 7, total = 90, forward = true))
        assertEquals(0, HandheldDispatch.pageJumpIndex(currentIndex = 3, visibleRows = 7, total = 90, forward = false))
        // A list that shows a single row still moves.
        assertEquals(5, HandheldDispatch.pageJumpIndex(currentIndex = 4, visibleRows = 1, total = 90, forward = true))
        assertEquals(0, HandheldDispatch.pageJumpIndex(currentIndex = 0, visibleRows = 7, total = 0, forward = true))
    }

    @Test
    fun xOpensTheMenuOfTheTarget() {
        assertEquals(LibraryHandheldAction.OpenMenu("b"), action(HandheldCommand.GAME_MENU, target = "b"))
        assertEquals(LibraryHandheldAction.None, action(HandheldCommand.GAME_MENU, target = null))
    }

    @Test
    fun selectTogglesOnlyGamesBatchBackupTakes() {
        assertEquals(LibraryHandheldAction.SetSelected("b", true), action(HandheldCommand.TOGGLE_SELECTION, target = "b"))
        assertEquals(
            LibraryHandheldAction.SetSelected("b", false),
            action(HandheldCommand.TOGGLE_SELECTION, target = "b", selected = true),
        )
        // A row batch backup skips (or none) only opens selection mode, once.
        assertEquals(LibraryHandheldAction.EnterSelection, action(HandheldCommand.TOGGLE_SELECTION, target = "b", eligible = false))
        assertEquals(LibraryHandheldAction.EnterSelection, action(HandheldCommand.TOGGLE_SELECTION, target = null))
        assertEquals(
            LibraryHandheldAction.None,
            action(HandheldCommand.TOGGLE_SELECTION, target = "b", eligible = false, selectionMode = true),
        )
    }

    @Test
    fun startFollowsTheToolbarMainButton() {
        assertEquals(
            LibraryHandheldAction.BackupSelected(setOf("a", "c")),
            action(HandheldCommand.BACKUP, target = "b", selection = setOf("a", "c"), changed = setOf("d")),
        )
        // Nothing selected: the games with changes, not the focused one.
        assertEquals(
            LibraryHandheldAction.BackupSelected(setOf("d")),
            action(HandheldCommand.BACKUP, target = "b", changed = setOf("d")),
        )
        assertEquals(LibraryHandheldAction.NothingToBackup, action(HandheldCommand.BACKUP, target = "b"))
        // Selection mode with nothing checked never falls back to the changed games.
        assertEquals(
            LibraryHandheldAction.NothingToBackup,
            action(HandheldCommand.BACKUP, target = "b", changed = setOf("d"), selectionMode = true),
        )
    }

    @Test
    fun triggersPageAndShellCommandsAreNotLibraryActions() {
        assertEquals(LibraryHandheldAction.Page(forward = false), action(HandheldCommand.PAGE_UP, target = "a"))
        assertEquals(LibraryHandheldAction.Page(forward = true), action(HandheldCommand.PAGE_DOWN, target = "a"))
        listOf(HandheldCommand.PREVIOUS_PAGE, HandheldCommand.NEXT_PAGE, HandheldCommand.COMMAND_PANEL).forEach {
            assertEquals(LibraryHandheldAction.None, action(it, target = "a"))
        }
    }

    @Test
    fun stickDeadZoneIsRemovedAndTheRangeRescaled() {
        assertEquals(0f, HandheldDispatch.stickValue(0.15f))
        assertEquals(0f, HandheldDispatch.stickValue(-0.2f))
        assertEquals(1f, HandheldDispatch.stickValue(1f))
        assertEquals(-1f, HandheldDispatch.stickValue(-1f))
        assertEquals(0.5f, HandheldDispatch.stickValue(0.6f), 0.0001f)
    }

    @Test
    fun focusGoesBackToTheRowWhenTheSystemMovedItOnWindowFocus() {
        // The system put focus on the "全部" chip: neither list nor detail has it.
        assertEquals(true, HandheldDispatch.shouldRestoreRowFocus(listHasFocus = false, detailHasFocus = false, detailFocusPending = false))
        assertEquals(false, HandheldDispatch.shouldRestoreRowFocus(listHasFocus = true, detailHasFocus = false, detailFocusPending = false))
        // A dialog choice moved focus to the detail pane on purpose.
        assertEquals(false, HandheldDispatch.shouldRestoreRowFocus(listHasFocus = false, detailHasFocus = true, detailFocusPending = false))
        assertEquals(false, HandheldDispatch.shouldRestoreRowFocus(listHasFocus = false, detailHasFocus = false, detailFocusPending = true))
    }

    @Test
    fun hintsOnlyListKeysThatActOnThePage() {
        val library = HandheldHints.forPage(OpenSavePage.LIBRARY).map { it.first }
        val management = HandheldHints.forPage(OpenSavePage.SETTINGS).map { it.first }
        assertEquals(listOf("A", "B", "X", "Select", "Start", "Y", "L1/R1", "L2/R2"), library)
        assertEquals(listOf("A", "B", "Y", "L1/R1", "L2/R2"), management)
    }

    private fun action(
        command: HandheldCommand,
        target: String?,
        eligible: Boolean = true,
        selected: Boolean = false,
        selection: Set<String> = emptySet(),
        changed: Set<String> = emptySet(),
        selectionMode: Boolean = false,
    ) = HandheldDispatch.libraryAction(
        command = command,
        targetKey = target,
        targetEligible = eligible,
        targetSelected = selected,
        primary = LibraryView.primaryBackup(selection, changed, selectionMode),
        selectionMode = selectionMode,
    )
}
