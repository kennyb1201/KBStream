package com.kennyb1201.kbstream.ui.detail

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reported defect (0.9.36): long-press an episode card on the detail page,
 * mark the episode watched, and the page scrolled up to the PLAY button - while
 * the hand-back then put the focus back on the card it had raised the menu from.
 *
 * The hand-back was working; the scroll was not its doing. The menus are
 * in-page overlays (see PosterContextMenu), so the frame that closes one is the
 * frame its focused row leaves the tree, and the page's header is what the
 * framework picks while the recorded hand-back is still a frame away from
 * asking. `scrollToTopOnFocus` - written for the viewer WALKING UP to the
 * header, where it is the only way the overview comes back - treated that one
 * transient frame as an arrival and started a full `animateScrollToItem(0)`,
 * which then kept animating after the focus had already been handed back to the
 * card. From the sofa: the screen scrolls up, the focus stays on the card.
 *
 * What this pins is the shape that cannot do that again: the snap waits a frame
 * and re-reads the header's focus, it is gated on there being no pending
 * hand-back at all, it is cancelled the moment the header loses focus, and
 * every header call site passes the gate. The hand-back itself is untouched -
 * the card still takes the focus back (that half already worked, and
 * DetailFocusHandBackContractTest pins it).
 *
 * Read from the source: these are Compose modifiers and focus callbacks, which
 * need a device and a remote to press.
 */
class DetailHeaderSnapContractTest {

    /** The detail source with comments removed, then whitespace collapsed. */
    private val source: String by lazy {
        normalized(stripComments(File(findMainSourceRoot(), DETAIL).readText()))
    }

    /** The snap modifier's body, and nothing after it on the page. */
    private val snap: String by lazy {
        source
            .substringAfter("private fun Modifier.scrollToTopOnFocus(")
            .substringBefore("private fun ReviewCard(")
    }

    /**
     * Both comment shapes, because the KDoc above the snap names the call it
     * makes: leaving block comments in would count prose as a second scroll.
     */
    private fun stripComments(text: String): String =
        text.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
            .lineSequence()
            .joinToString("\n") { line ->
                val marker = line.indexOf("//")
                if (marker < 0) line else line.substring(0, marker)
            }

    private fun normalized(text: String): String =
        text.replace(Regex("\\s+"), " ").trim()

    private fun findMainSourceRoot(): File {
        val prefixes = listOf("", "app/")
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            prefixes.forEach { prefix ->
                val candidate = File(dir, "${prefix}src/main/java")
                if (candidate.isDirectory) {
                    return candidate
                }
            }
            dir = dir.parentFile
        }
        throw AssertionError(
            "main source root not found walking up from " + System.getProperty("user.dir")
        )
    }

    @Test
    fun `the snap is gated on there being no hand-back in flight`() {
        assertTrue(
            "a pending hand-back means focus is being DELIVERED to the surface " +
                "that raised the menu; the header cannot be the viewer's answer " +
                "while that is true",
            snap.contains("canSnap: () -> Boolean")
        )
        assertTrue(
            "the gate is re-read at the moment of scrolling, not only when focus " +
                "arrived - the hand-back records in the same click the menu closes",
            snap.contains("if (!headerFocused || !canSnap()) return@launch")
        )
    }

    @Test
    fun `every snap call site passes that gate`() {
        assertEquals(
            "the declaration plus the three surfaces that snap: the Play/Trailer " +
                "row, the meta column with the genre chips, and the overview's own " +
                "\"View more\" toggle (the topmost focusable INSIDE the list, and so " +
                "the other node a dismissing menu's transient focus can land on)",
            4,
            Regex("\\.scrollToTopOnFocus\\(").findAll(source).count()
        )
        assertEquals(
            "each of them names the pending hand-back as the reason not to move",
            3,
            Regex("scrollToTopOnFocus\\(detailListState, scope\\) \\{ " +
                "handFocusBack\\.pending == null \\}")
                .findAll(source)
                .count()
        )
    }

    @Test
    fun `there is one snap implementation, and it is the only one that scrolls`() {
        assertEquals(
            "the page body no longer carries a snap of its own: the overview " +
                "toggle's ad-hoc `animateScrollToItem(0)` was the second way a " +
                "dismissal could move the page, so it routes through the modifier " +
                "with the same gates",
            1,
            Regex("animateScrollToItem\\(0\\)").findAll(source).count()
        )
        assertEquals(
            "and the ONE jump to a list position that is not a snap stays where it " +
                "belongs - `focusTopOfPage`, the D-pad chain's last resort, which no " +
                "menu dismissal can reach",
            1,
            Regex("scrollToItem\\(0\\)").findAll(source).count()
        )
        assertTrue(
            "every hand-back in the page (the four header/menus, the overlays, and " +
                "the inline watched rows of the poster, season and episode menus) " +
                "goes through the one driver, so the gate above covers all of them",
            Regex("handFocusBack\\(").findAll(source).count() >= 15
        )
    }

    @Test
    fun `a focus that leaves the header inside that frame never moves the list`() {
        val wait = snap.indexOf("awaitFrame()")
        val recheck = snap.indexOf("if (!headerFocused || !canSnap()) return@launch")
        val scroll = snap.indexOf("listState.animateScrollToItem(0)")
        assertTrue(
            "the snap waits a frame first: the hand-back asks one frame after " +
                "the dismissal, so a transient focus is already replaced by then",
            wait >= 0 && wait < recheck
        )
        assertTrue("and the scroll is what follows the recheck", recheck < scroll)
        assertFalse(
            "the old shape - a scroll launched straight out of onFocusChanged - " +
                "is what turned one transient frame into a page move",
            snap.contains("scope.launch { listState.animateScrollToItem(0) }")
        )
    }

    @Test
    fun `a snap already travelling is cancelled when the header loses focus`() {
        assertTrue(
            "focus returning to the card mid-animation must stop the page, not " +
                "let it finish sliding to the top under the viewer",
            snap.contains("if (!state.hasFocus) { snapJob?.cancel() return@onFocusChanged }")
        )
    }

    @Test
    fun `the episode card still takes the focus back after a mark`() {
        assertTrue(
            "the mark rows keep the hand-back to the card that raised the menu: " +
                "the report's other half (\"it should stay focused on the " +
                "episode card\") is this call, not a new one",
            source.contains(
                "handFocusBack(\"episode menu\", lastEpisodeFocusRequester)"
            )
        )
    }

    private companion object {
        const val DETAIL = "com/kennyb1201/kbstream/ui/detail/DetailScreen.kt"
    }
}
