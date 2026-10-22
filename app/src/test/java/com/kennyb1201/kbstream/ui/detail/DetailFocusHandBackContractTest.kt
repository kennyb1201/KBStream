package com.kennyb1201.kbstream.ui.detail

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How the detail page gives the focus BACK, and how it keeps two moves from
 * racing each other.
 *
 * Two halves of the same complaint - "the D-pad loses my place on the detail
 * screen". A menu or an overlay is drawn in its own focus group over the page,
 * so dismissing it has to hand the focus to the surface that raised it; that
 * hand-back used to be a single `requestFocus()`, made in the frame the menu's
 * scrim is leaving the tree, and a request against a node that cannot take
 * focus yet answers FALSE rather than throwing - so a miss was silent and the
 * D-pad restarted at the top of the page. And the page's vertical moves are
 * asynchronous (a rail the scroll disposed is put back, then asked again a
 * frame at a time), so a press arriving mid-move used to be able to leave two
 * moves in flight, the older one landing its focus after the newer one had
 * already moved - the focus snapping back the way the viewer had just come.
 *
 * Read from the source: these are Compose modifiers, click handlers and
 * coroutine launches, which need a device and a remote to press.
 */
class DetailFocusHandBackContractTest {

    /** The detail screen, comments removed, then whitespace collapsed. */
    private val detail: String by lazy { normalized(stripComments(read(DETAIL_FILE))) }

    @Test
    fun `a hand-back is read from the request's own answer`() {
        assertTrue(
            "the page's one hand-back is the app's verified landing - the same " +
                "helper every browse page and Home use - rather than a loop of " +
                "its own",
            detail.contains("val handFocusBack = rememberFocusHandBack()")
        )
        assertFalse(
            "and the one-shot spelling - an optional requester asked once, with " +
                "nobody reading the answer, against a node that is being " +
                "recomposed in the same frame - is what left the focus at the " +
                "top of the page",
            Regex("\\w+\\?\\.requestFocus\\(\\)").containsMatchIn(detail)
        )
    }

    @Test
    fun `every menu and overlay hands the focus back to where it was raised`() {
        listOf(
            "handFocusBack(lastStudioChipFocusRequester)",
            "handFocusBack(lastReviewTileFocusRequester)",
            "handFocusBack(lastPosterFocusRequester)",
            "handFocusBack(lastSeasonFocusRequester)",
            "handFocusBack(lastEpisodeFocusRequester)",
            "handFocusBack(lastBackdropCardFocusRequester)",
            "handFocusBack(mediaImageMenuCardFocusRequester)",
            "handFocusBack(playButtonFocusRequester)"
        ).forEach { handBack ->
            assertTrue(
                "every surface that raises a menu or an overlay has to take the " +
                    "focus back when it closes, or the D-pad restarts at the top " +
                    "of the page: $handBack",
                detail.contains(handBack)
            )
        }
    }

    @Test
    fun `an action inside a menu hands the focus back too`() {
        // A row press closes the menu itself rather than going through the
        // dismiss path, so the same hand-back has to be in the row.
        assertTrue(
            "the title menu's watched row, which closes the menu itself rather " +
                "than going through the dismiss path",
            Regex(
                "handFocusBack\\(lastPosterFocusRequester\\)"
            ).findAll(detail).count() >= 2
        )
        assertTrue(
            "and the season and episode menus' rows, which close the menu and " +
                "then need the viewer back on the chip or card they pressed",
            Regex(
                "handFocusBack\\(lastSeasonFocusRequester\\)"
            ).findAll(detail).count() >= 2 &&
                Regex(
                    "handFocusBack\\(lastEpisodeFocusRequester\\)"
                ).findAll(detail).count() >= 4
        )
    }

    @Test
    fun `only one vertical move is in flight at a time`() {
        assertTrue(
            "a move cancels the one before it, so a press that arrives while a " +
                "rail is still being retried cannot be undone a moment later by " +
                "the older move finishing",
            detail.contains("var focusMoveInFlight by remember") &&
                detail.contains("fun startFocusMove(move: suspend () -> Unit)") &&
                detail.contains("focusMoveInFlight?.cancel() focusMoveInFlight = scope.launch { move() }")
        )
        assertTrue(
            "and every press that starts a move goes through it: the chain's " +
                "own handler, the Media strip's DOWN and a card's UP back to the " +
                "strip",
            detail.contains("startFocusMove { moveFocusVertical(row, direction, column, presence) }") &&
                detail.contains("startFocusMove { focusFirstMediaCard() }") &&
                detail.contains("startFocusMove { focusMediaStrip(")
        )
        assertFalse(
            "the bare launches are what let two moves overlap",
            detail.contains("scope.launch { moveFocusVertical(row, direction, column, presence) }") ||
                detail.contains("scope.launch { focusFirstMediaCard() }")
        )
    }

    @Test
    fun `UP out of an episode card lands somewhere even when the chip cannot`() {
        assertTrue(
            "the chip is a lazy row's card, so the ask reports whether it landed",
            detail.contains("suspend fun focusSeasonChip(season: Int): Boolean") &&
                detail.contains("return true") &&
                detail.contains("return false")
        )
        assertTrue(
            "and the card's UP - which is consumed either way - falls back to " +
                "the chain's own UP when it did not: a press eaten by a chip that " +
                "never took the focus is an UP that visibly did nothing",
            detail.contains(
                "if ( !focusSeasonChip( chipSeason ) ) { moveFocusVertical( " +
                    "DetailRow.EPISODES, DetailFocusDirection.UP, 0, detailRowPresence ) }"
            )
        )
    }

    private fun read(relativePath: String): String =
        File(findMainSourceRoot(), relativePath).readText()

    private fun stripComments(text: String): String =
        text.lineSequence()
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

    private companion object {
        const val DETAIL_FILE = "com/kennyb1201/kbstream/ui/detail/DetailScreen.kt"
    }
}
