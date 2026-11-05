package com.kennyb1201.kbstream.ui.home

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A return from Detail lands on the card that opened it, on every row Home has.
 *
 * The reported failure (2026-11-05, TV): a title opened from "Top Picks for You"
 * came back to Continue Watching. The three lines below are what makes the
 * restore reach a row that is not the first one, and any of them going away
 * silently restores the bug:
 *
 *  - the card keys have ONE spelling, and the row is part of the key, so the
 *    remembered key names the row it lives on;
 *  - the restore is keyed on the rows, not on the composition, because a return
 *    rebuilds them and the remembered card was often composed after the first
 *    window had given up;
 *  - when the card is not composed yet, its ROW is scrolled into view - the
 *    seeded scroll is an index into a list whose contents arrive late, and a
 *    card that never composes can never be asked for focus.
 *
 * HomeViewModel's half (the remembered key and the entry signal) is pinned by
 * [HomeReturnRestoreContractTest]; this file is the screen's half.
 */
class HomeReturnFocusContractTest {

    private val screen: String by lazy { read(SCREEN) }
    private val focus: String by lazy { read(FOCUS) }

    private fun read(relative: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.isFile) return candidate.readText()
            dir = dir.parentFile
        }
        throw AssertionError("could not find $relative walking up from ${System.getProperty("user.dir")}")
    }

    private fun flat(src: String): String = src.replace(Regex("\\s+"), " ")

    @Test
    fun `the card keys have one spelling, and it lives with the row lookup`() {
        assertTrue(
            "the built-in card prefixes must be declared once, beside the lookup " +
                "that turns them into a row: two spellings drift, and the restore " +
                "is the only reader that would notice",
            focus.contains("internal const val CONTINUE_WATCHING_CARD_KEY_PREFIX = \"upnext:\"") &&
                focus.contains("internal const val UPCOMING_CARD_KEY_PREFIX = \"upcoming:\"") &&
                focus.contains("internal const val TOP_PICKS_CARD_KEY_PREFIX = \"top-picks|\"")
        )
        assertTrue(
            "and the screen must not declare its own copies",
            !screen.contains("private const val CONTINUE_WATCHING_CARD_KEY_PREFIX") &&
                !screen.contains("private const val UPCOMING_CARD_KEY_PREFIX") &&
                !screen.contains("private const val TOP_PICKS_CARD_KEY_PREFIX")
        )
        assertTrue(
            "a catalog card is keyed by its RAIL as well as its content - the same " +
                "title sits in several rails, and one key must never name two places " +
                "to put the D-pad back",
            flat(focus).contains(
                "internal fun railCardKeyPrefix(rail: Rail): String = " +
                    "CATALOG_CARD_KEY_PREFIX + rail.addonName + \"::\" + " +
                    "rail.catalogId + \"::\" + rail.type + \"|\""
            )
        )
        assertTrue(
            "and the screen builds its prefix through it, not by hand",
            flat(screen).contains("val catalogCardKeyPrefix = railCardKeyPrefix(rail)")
        )
    }

    @Test
    fun `the lookup names the row every built-in family and every catalog row lives on`() {
        assertTrue(
            "Continue Watching, Upcoming and Top Picks are the three rows that draw " +
                "cards without a catalog behind them",
            focus.contains("cardKey.startsWith(CONTINUE_WATCHING_CARD_KEY_PREFIX)") &&
                focus.contains("KBHomeOrderPrefs.BUILTIN_CONTINUE_WATCHING") &&
                focus.contains("cardKey.startsWith(UPCOMING_CARD_KEY_PREFIX)") &&
                focus.contains("KBHomeOrderPrefs.BUILTIN_UPCOMING_SCHEDULE") &&
                focus.contains("cardKey.startsWith(TOP_PICKS_CARD_KEY_PREFIX)") &&
                focus.contains("KBHomeOrderPrefs.BUILTIN_TOP_PICKS_FOR_YOU")
        )
        assertTrue(
            "a built-in row is found by its key, whatever slot the arrangement put it in",
            flat(focus).contains(
                "entry is HomeEntry.BuiltinRail && entry.key == builtin"
            )
        )
        assertTrue(
            "the offset is read from the column's own layout, never guessed: with " +
                "nothing composed yet only the always-drawn hero spacer is known",
            flat(focus).contains(
                "return entryIndex + (itemIndex - index)"
            ) &&
                flat(focus).contains("return entryIndex + HOME_COLUMN_HERO_SPACER_ITEMS") &&
                focus.contains("internal const val HOME_COLUMN_HERO_SPACER_ITEMS = 1")
        )
        assertTrue(
            "a catalog row is found by the rail the card key names",
            flat(focus).contains("entry is HomeEntry.AddonRail && " +
                "entry.rail.addonName == spec[0] && " +
                "entry.rail.catalogId.orEmpty() == spec[1] && " +
                "entry.rail.type == spec[2]")
        )
        assertTrue(
            "and a key this lookup has no term for answers null rather than " +
                "guessing a row",
            flat(focus).contains("if (!cardKey.startsWith(CATALOG_CARD_KEY_PREFIX)) return null")
        )
    }

    @Test
    fun `the restore is re-armed by the rows and brings the remembered row into view`() {
        assertTrue(
            "keyed on the rows: a return rebuilds them, and a one-shot window " +
                "expires before the remembered card is composed",
            screen.contains("LaunchedEffect(mergedEntries) {")
        )
        assertTrue(
            "a landing is final, so a later rebuild cannot drag the viewer back",
            screen.contains("var homeReturnLanded by remember {") &&
                flat(screen).contains("if (landed) { homeReturnLanded = true " +
                    "return@LaunchedEffect }")
        )
        assertTrue(
            "a deliberate press ends the restore, the same latch the Continue " +
                "Watching landing obeys",
            flat(screen).contains("repeat(HOME_RETURN_FOCUS_STEPS) { " +
                "if (viewerMovedFocusSinceEntry) return@LaunchedEffect")
        )
        assertTrue(
            "an uncomposed card is reached by scrolling its ROW into view - only " +
                "when it is not registered, so a landing never jumps the column",
            flat(screen).contains(
                "if (railFocusRequesters[wanted] == null) { " +
                    "homeEntryIndexForCardKey(mergedEntries, wanted)?.let { entryIndex ->"
            )
        )
        assertTrue(
            "and the entry index is converted into the COLUMN's own numbering " +
                "before it is scrolled to: the hero spacer and any status card sit " +
                "before the rails, so an entry index is not a column index",
            flat(screen).contains(
                "val columnIndex = homeColumnIndexForEntry( " +
                    "visibleItems = visible, mergedEntries = mergedEntries, " +
                    "entryIndex = entryIndex )"
            ) &&
                flat(screen).contains(
                    "val visible = railListState.layoutInfo.visibleItemsInfo " +
                        ".map { item -> item.index to item.key }"
                )
        )
        assertTrue(
            "the column and the restore agree on a row's key, or the restore " +
                "reaches the wrong row",
            flat(screen).contains("key = { _, entry -> homeEntryItemKey(entry) }")
        )
        assertTrue(
            "and the request's own answer is still what decides a landing",
            flat(screen).contains(
                "val landed = runCatching { requester.requestFocus() } .getOrDefault(false)"
            )
        )
    }

    private companion object {
        const val SCREEN = "app/src/main/java/com/kennyb1201/kbstream/ui/home/HomeScreen.kt"
        const val FOCUS = "app/src/main/java/com/kennyb1201/kbstream/ui/home/HomeReturnFocus.kt"
    }
}
