package com.jun9100.opensave

/**
 * Batch selection in the game library. Nothing is selected until the user
 * chooses; a rescan keeps explicit choices, and only keys that are currently
 * eligible are counted or acted on.
 */
object LibrarySelection {
    val initial: Set<String> = emptySet()

    fun set(selection: Set<String>, key: String, selected: Boolean): Set<String> =
        if (selected) selection + key else selection - key

    /** Selects every visible eligible key, or clears them when all are already selected. */
    fun toggleAll(selection: Set<String>, visibleEligibleKeys: Collection<String>): Set<String> {
        if (visibleEligibleKeys.isEmpty()) return selection
        return if (selection.containsAll(visibleEligibleKeys)) {
            selection - visibleEligibleKeys.toSet()
        } else {
            selection + visibleEligibleKeys
        }
    }

    /** "全选当前筛选": adds every key of the current filter and keeps choices hidden by it. */
    fun selectAll(selection: Set<String>, keys: Collection<String>): Set<String> = selection + keys

    fun effective(selection: Set<String>, eligibleKeys: Collection<String>): Set<String> =
        selection.intersect(eligibleKeys.toSet())
}
