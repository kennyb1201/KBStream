package com.kennyb1201.kbstream.ui.iptv

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The TV guide header's three action buttons: ICONS, not words.
 *
 * The word buttons ("SPORTS HUB", "SEARCH", "SETUP") were the last place the two
 * guide forms disagreed - the phone header has always drawn a soccer ball, a
 * magnifier and a gear (see `GuideScreenMobile`). What must NOT change with the
 * artwork is everything a viewer relies on: the same three KBCards (the header's
 * focus ring, glow and scale are the card's), the same callbacks, the same order
 * with the sports button first and accent-tinted, and a TalkBack name on each,
 * because an icon-only button without one is silent where the words used to
 * speak.
 */
class GuideHeaderButtonsContractTest {

    private val screen: String by lazy { read(SCREEN) }

    /** The header composable alone: the row, its comment, and nothing past it. */
    private val header: String by lazy {
        screen.substringAfter("private fun GuideHeader(")
            .substringBefore("private fun GuideHeaderClock(")
    }

    @Test
    fun `no word label survives on a header action`() {
        listOf("\"SPORTS HUB\"", "\"SEARCH\"", "\"SETUP\"").forEach { label ->
            assertFalse(
                "$label is the word button this replaces",
                header.contains(label)
            )
        }
    }

    @Test
    fun `the three icons are the phone's, in the header's order`() {
        val sports = header.indexOf("Icons.Filled.SportsSoccer")
        val search = header.indexOf("Icons.Filled.Search")
        val settings = header.indexOf("Icons.Filled.Settings")
        assertTrue("the sports glyph is missing", sports >= 0)
        assertTrue("the search glyph is missing", search >= 0)
        assertTrue("the setup glyph is missing", settings >= 0)
        assertTrue(
            "sports first, then search, then setup - the order the D-pad walks and " +
                "the order the word buttons had",
            sports < search && search < settings
        )
        assertTrue(
            "and the clock still closes the row, untouched",
            header.contains("GuideHeaderClock()") && header.indexOf("GuideHeaderClock()") > settings
        )
    }

    @Test
    fun `each card names its icon for TalkBack and keeps its own tint`() {
        // (callback, vector, spoken name, tint) - the phone header's own three.
        val buttons = listOf(
            Quad("onSportsClick", "Icons.Filled.SportsSoccer", "Sports hub", "KBAccent"),
            Quad("onSearchClick", "Icons.Filled.Search", "Search", "KBTextHi"),
            Quad("onSetupClick", "Icons.Filled.Settings", "Playlist setup", "KBTextHi")
        )
        buttons.forEach { button ->
            val cardAt = header.indexOf("KBCard(onClick = ${button.callback})")
            assertTrue(
                "${button.callback} must still be the card's onClick - the callback is " +
                    "the wiring, the icon is only the face",
                cardAt >= 0
            )
            // The card's own body: up to the next button, or to the clock that
            // closes the row.
            val nextCardAt = header.indexOf("KBCard(onClick =", cardAt + 1)
            val clockAt = header.indexOf("GuideHeaderClock()")
            val end = when {
                nextCardAt > cardAt -> nextCardAt
                clockAt > cardAt -> clockAt
                else -> header.length
            }
            val body = header.substring(cardAt, end)
            assertTrue(
                "${button.vector} must sit inside the card that calls ${button.callback}",
                body.contains("imageVector = ${button.vector}")
            )
            assertTrue(
                "an icon-only button must carry the name the word label provided, " +
                    "or TalkBack announces nothing",
                body.contains("contentDescription = \"${button.spokenName}\"")
            )
            assertTrue(
                "the tint is the button's own (accent only for the sports hub)",
                body.contains("tint = ${button.tint}")
            )
            assertTrue(
                "the glyph is 24dp, inside the words' own 12/9 padding, so the header " +
                    "row's footprint does not move",
                body.contains("Modifier.size(24.dp)") &&
                    body.contains("Modifier.padding(horizontal = 12.dp, vertical = 9.dp)")
            )
        }
    }

    @Test
    fun `the TV header is not turned into IconButtons`() {
        assertFalse(
            "the phone uses IconButton because it is touch-first; on TV it would replace " +
                "the card's focus ring, glow and KBFocusCard scale",
            header.contains("IconButton(")
        )
        assertFalse(
            "and no per-button focus scale was added - the card's default is the header's look",
            header.contains("focusedScale")
        )
    }

    @Test
    fun `the comment still explains why sports leads, now for the icon`() {
        assertTrue(
            "the reasoning must survive the wording: sports LEAVES Live TV rather than " +
                "filtering it",
            header.contains("leaves Live TV")
        )
        assertTrue(
            "and it is first because the D-pad reaches it first",
            header.contains("reaches it first")
        )
        assertTrue(
            "the comment now credits the icon and its TalkBack name with the destination " +
                "meaning the word used to carry",
            header.contains("TalkBack name") && header.contains("\"Sports hub\"")
        )
        assertTrue(
            "the phone's own glyph is the precedent, named so the two forms stay in step",
            header.contains("GuideScreenMobile")
        )
    }

    // ── source access ─────────────────────────────────────────────────

    private data class Quad(
        val callback: String,
        val vector: String,
        val spokenName: String,
        val tint: String
    )

    private fun read(relative: String): String {
        val file = File(sourceRoot(), relative)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

    private fun sourceRoot(): File {
        val prefixes = listOf("", "app/")
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            prefixes.forEach { prefix ->
                val candidate = File(dir, "${prefix}src/main/java")
                if (candidate.isDirectory) return candidate
            }
            dir = dir.parentFile
        }
        throw AssertionError(
            "main source root not found walking up from " + System.getProperty("user.dir")
        )
    }

    private companion object {
        const val SCREEN = "com/kennyb1201/kbstream/ui/iptv/GuideScreen.kt"
    }
}
