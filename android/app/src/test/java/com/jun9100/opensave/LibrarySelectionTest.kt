package com.jun9100.opensave

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LibrarySelectionTest {
    @Test
    fun nothingIsSelectedByDefault() {
        assertTrue(LibrarySelection.initial.isEmpty())
        assertTrue(SourceUiState().librarySelection.isEmpty())
    }

    @Test
    fun explicitChoicesSurviveARescanThatStillOffersThem() {
        var selection = LibrarySelection.set(LibrarySelection.initial, "a::1", selected = true)
        selection = LibrarySelection.set(selection, "b::2", selected = true)
        selection = LibrarySelection.set(selection, "b::2", selected = false)

        val rescanned = LibrarySelection.effective(selection, listOf("a::1", "c::3"))

        assertEquals(setOf("a::1"), rescanned)
    }

    @Test
    fun toggleAllOnlyTouchesVisibleKeys() {
        val selection = setOf("hidden::1")
        val visible = listOf("a::1", "b::2")

        val selected = LibrarySelection.toggleAll(selection, visible)
        assertEquals(setOf("hidden::1", "a::1", "b::2"), selected)

        val cleared = LibrarySelection.toggleAll(selected, visible)
        assertEquals(setOf("hidden::1"), cleared)

        val partial = LibrarySelection.toggleAll(setOf("a::1"), visible)
        assertEquals(setOf("a::1", "b::2"), partial)
        assertEquals(selection, LibrarySelection.toggleAll(selection, emptyList()))
    }
}
