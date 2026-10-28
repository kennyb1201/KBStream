package com.kennyb1201.kbstream.ui.home

/**
 * The line Home shows when some of the installed add-ons' catalogs did not
 * answer.
 *
 * A failed catalog produces no rail, so the only symptom was a Home that was a
 * few rows shorter than the viewer expected - and the failure itself went to
 * logcat and nowhere else. This names the add-ons instead, because "AIOStreams
 * didn't answer" is something a viewer can act on and "your rails loaded" is
 * not.
 *
 * Pure, so the wording is pinned by a test rather than by reading the screen:
 * the caller passes whatever names it collected, duplicates and blanks
 * included.
 */
internal fun failedAddonsMessage(names: List<String>, surface: String = "Home"): String {
    val distinct = names.filter { it.isNotBlank() }.distinct().sorted()
    if (distinct.isEmpty()) return ""
    val named = when (distinct.size) {
        1 -> distinct.first()
        2 -> "${distinct[0]} and ${distinct[1]}"
        else -> distinct.dropLast(1).joinToString(", ") + " and " + distinct.last()
    }
    return if (distinct.size == 1) {
        "$named didn't answer - its rows are missing from $surface."
    } else {
        "$named didn't answer - their rows are missing from $surface."
    }
}
