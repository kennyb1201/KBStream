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
 * D-pad restarted at the top of the page, which on this page is the episode
 * row (the reported scroll-up). And the page's vertical moves are asynchronous
 * (a rail the scroll disposed is put back, then asked again a frame at a time),
 * so a press arriving mid-move used to be able to leave two moves in flight,
 * the older one landing its focus after the newer one had already moved - the
 * focus snapping back the way the viewer had just come.
 *
 * The delivery rule now lives in [DetailHandBack]: every dismissal RECORDS the
 * surface it was raised from and one effect in the page's composition DELIVERS
 * the hand-back after the overlay has closed, resolving rail targets from the
 * page's live registries. Read from the source: these are Compose modifiers,
 * click handlers and coroutine launches, which need a device and a remote to
 * press.
 */
class DetailFocusHandBackContractTest {

    /** The detail screen, comments removed, then whitespace collapsed. */
    private val detail: String by lazy { normalized(stripComments(read(DETAIL_FILE))) }

    /** The page's hand-back driver, same treatment. */
    private val driver: String by lazy { normalized(stripComments(read(DRIVER_FILE))) }

    @Test
    fun `a hand-back is delivered after the close and read from the request's own answer`() {
        assertTrue(
            "the page's one hand-back is the record-then-deliver driver, so the " +
                "ask is made after the frame that closed the overlay rather than " +
                "inside the key handler that requested the close",
            detail.contains("val handFocusBack = rememberDetailHandBack()")
        )
        assertTrue(
            "the driver holds one pending record, delivered by the one effect in " +
                "the page's composition - which is also why a hand-back still " +
                "pending when Detail leaves dies with the composition",
            driver.contains("var pending by mutableStateOf<DetailHandBack?>(null)") &&
                driver.contains("LaunchedEffect(pending)") &&
                driver.contains("deliverDetailHandBack(handBack)")
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
    fun `the delivery is frame-paced, bounded and reported`() {
        assertTrue(
            "the first ask follows a frame, so the overlay's focused node has " +
                "left the tree and the card underneath has been recomposed",
            driver.contains("awaitFrame()")
        )
        assertTrue(
            "a miss is asked again, and the exit is a VERIFIED landing - two " +
                "consecutive acceptances through the shared verdict, because one " +
                "answer cannot tell a stable card from one disposed a frame later",
            driver.contains("val verdict = FocusLandingVerdict()") &&
                driver.contains("val landed = verdict.accepted(") &&
                driver.contains("requester != null &&") &&
                driver.contains("runCatching { requester.requestFocus() }.getOrDefault(false)")
        )
        assertTrue(
            "the budget is frames and it is bounded (about a second at " +
                "television refresh rates)",
            driver.contains("internal const val DETAIL_HAND_BACK_FRAMES = 60") &&
                driver.contains("detailHandBackStep(frame, landed, cap)")
        )
        assertTrue(
            "every outcome is logged under DETAIL_FOCUS with the target and the " +
                "frames it took, and running out of budget is a warning - so the " +
                "next field report names the variant that survived",
            driver.contains("internal const val DETAIL_FOCUS_TAG = \"DETAIL_FOCUS\"") &&
                driver.contains("hand-back delivered: ") &&
                driver.contains("hand-back undelivered: ") &&
                driver.contains("Log.w(")
        )
    }

    @Test
    fun `every menu and overlay hands the focus back to where it was raised`() {
        listOf(
            "handFocusBack(\"network/production chip menu\", lastStudioChipFocusRequester)",
            "handFocusBack(\"review overlay\", lastReviewTileFocusRequester)",
            "handFocusBack(\"poster menu\", lastPosterFocusRequester)",
            "handFocusBack(\"season menu\", lastSeasonFocusRequester)",
            "handFocusBack(\"episode menu\", lastEpisodeFocusRequester)",
            "handFocusBack(\"backdrop overlay\", lastBackdropCardFocusRequester)",
            "handFocusBack(\"media image menu\", mediaImageMenuCardFocusRequester)",
            "handFocusBack(\"play button menu\", playButtonFocusRequester)"
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
    fun `rail targets are resolved from the live registry at delivery time`() {
        assertTrue(
            "the review tile is filed by id as its rail composes, so its overlay " +
                "finds the tile again even if the requester was rebuilt",
            detail.contains("railFocusRequesters[\"review:\${review.id}\"] =") &&
                detail.contains("reviewId?.let { railFocusRequesters[\"review:\$it\"] }")
        )
        assertTrue(
            "a poster menu resolves the rail card through the record it already " +
                "files for the drill-down return",
            detail.contains("val railKey = posterMenu?.returnTarget?.targetKey") &&
                detail.contains("railKey?.let { railFocusRequesters[it] }")
        )
        assertTrue(
            "the network and production chips are rail cards too, keyed the same " +
                "way the rail files them",
            detail.contains(
                "\"network:\${menu.id}\""
            ) && detail.contains("chipKey?.let { railFocusRequesters[it] }")
        )
        assertTrue(
            "and the season chip and episode card resolve their own registries",
            detail.contains("seasonNumber?.let { seasonFocusRequesters[it] }") &&
                detail.contains("slot?.let { episodeFocusRequesters[it] }")
        )
        assertTrue(
            "while the surfaces with no registry - the backdrop, the media image, " +
                "the play button - deliver to the requester captured when they opened",
            detail.contains("handFocusBack(\"backdrop overlay\", lastBackdropCardFocusRequester)") &&
                detail.contains(
                    "handFocusBack(\"media image menu\", mediaImageMenuCardFocusRequester)"
                ) &&
                detail.contains("handFocusBack(\"play button menu\", playButtonFocusRequester)")
        )
        assertTrue(
            "the record's own resolver prefers the live entry and falls back to " +
                "the captured requester",
            driver.contains("fun resolve(): FocusRequester? = live?.invoke() ?: captured")
        )
    }

    @Test
    fun `a newer hand-back supersedes the pending one`() {
        assertTrue(
            "a record replaces the pending one, and the effect keys on the record " +
                "so the older delivery is cancelled - a dismissal the viewer has " +
                "already superseded cannot land its focus after the next one moved",
            driver.contains("pending = DetailHandBack(label, captured, live)") &&
                driver.contains("LaunchedEffect(pending)") &&
                driver.contains("fun consumed(handBack: DetailHandBack)") &&
                driver.contains("if (pending === handBack)")
        )
    }

    @Test
    fun `an action inside a menu hands the focus back too`() {
        // A row press closes the menu itself rather than going through the
        // dismiss path, so the same hand-back has to be in the row.
        assertTrue(
            "the title menu's watched row, which closes the menu itself rather " +
                "than going through the dismiss path",
            Regex(
                "handFocusBack\\(\"poster menu\", lastPosterFocusRequester\\)"
            ).findAll(detail).count() >= 2
        )
        assertTrue(
            "and the season and episode menus' rows, which close the menu and " +
                "then need the viewer back on the chip or card they pressed",
            Regex(
                "handFocusBack\\(\"season menu\", lastSeasonFocusRequester\\)"
            ).findAll(detail).count() >= 2 &&
                Regex(
                    "handFocusBack\\(\"episode menu\", lastEpisodeFocusRequester\\)"
                ).findAll(detail).count() >= 4
        )
        assertTrue(
            "each row's record also resolves the live registry, so the action " +
                "path cannot strand the restore on a rebuilt instance either",
            detail.contains("selected.returnTarget?.targetKey") &&
                detail.contains("seasonFocusRequesters[target.seasonNumber]") &&
                Regex("episodeFocusRequesters\\[target.season to target.episode\\]")
                    .findAll(detail).count() >= 4
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
        const val DRIVER_FILE = "com/kennyb1201/kbstream/ui/detail/DetailHandBack.kt"
    }
}
