package com.kennyb1201.kbstream.ui.player

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The credits recommendation panel is raised FILLED, never empty.
 *
 * The field report: "sometimes byw pops up but is blank for like 3 to 7 seconds
 * and then fills in". The panel was shown on the spot and the TMDB round-trip
 * that builds its row ran afterwards, so the box sat on screen with nothing in
 * it for as long as the fetch took - and on an empty answer it appeared and was
 * taken straight back down.
 *
 * The fetch now comes first, and the raise, the credits arrangement and the row
 * build happen in one turn. Nothing is raised when the fetch answers nothing:
 * the credits simply keep playing full screen.
 *
 * The order is what these assertions read; there is no device in CI and neither
 * Activity can be driven from a JVM test.
 *
 * The panel's second half is pinned here too: the Like / Dislike pair for the
 * JUST-FINISHED title, which the BYW thumbs spec put in the header. What a JVM
 * test can hold is the wiring that makes the behaviour true - where the row sits
 * relative to the featured strip, that the identity travels with the raise, that
 * a thumb press touches nothing else in the player, and that the pair is
 * title-level (no season or episode identity exists to write). D-pad feel and
 * legibility at TV distance are the field checklist's job, not these assertions'.
 */
class BywPanelFillOrderContractTest {

    private val sourceRoot: File by lazy { findSourceRoot() }

    private fun source(path: String): String {
        val file = File(sourceRoot, path)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

    private fun findSourceRoot(): File {
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

    /**
     * The panel function up to the next member: a line indented by exactly four
     * spaces, which the body's deeper indents do not match.
     */
    private fun panel(path: String): String {
        val src = source(path)
        val start = src.indexOf("private fun showBecauseYouWatchedPanel() {")
        assertTrue("$path must keep the BYW panel", start >= 0)
        val rest = src.substring(start)
        val end = Regex("\\n {4}\\S").find(rest)?.range?.first ?: rest.length
        return rest.substring(0, end)
    }

    @Test
    fun `every panel fetches its picks before it is shown`() {
        listOf(NATIVE, MPV, EXTERNAL).forEach { path ->
            val body = panel(path)
            val fetch = body.indexOf("buildBecauseYouWatchedPicks(")
            // The raise carries the finished title's identity with it: the panel's
            // thumbs write for that title, so the identity is resolved in the SAME
            // IO block as the picks and travels to show() beside them.
            val show = body.indexOf("show(itemName, plan.finished)")
            val build = body.indexOf("build(picks)")
            assertTrue("$path: the panel must still fetch its picks", fetch >= 0)
            assertTrue("$path: and still raise the panel", show >= 0)
            assertTrue(
                "$path: show() must come AFTER the fetch, or the panel is up with " +
                    "an empty row for the whole length of the TMDB round-trip",
                fetch < show
            )
            assertTrue(
                "$path: and the row is built in the same turn as the raise, so no " +
                    "frame can paint the panel empty",
                show < build
            )
        }
    }

    @Test
    fun `an empty pick list raises nothing`() {
        listOf(NATIVE, MPV, EXTERNAL).forEach { path ->
            val body = panel(path)
            assertTrue(
                "$path: the empty answer must return before the raise",
                body.contains("picks.isEmpty() || bywDismissed")
            )
            assertFalse(
                "$path: the old empty-box path - show it, then hide it again - is " +
                    "gone with the raise it belonged to",
                body.contains("isVisible")
            )
        }
    }

    // ── the finished title's thumbs ──────────────────────────────────────

    /**
     * The row's slot and the strip's are one decision. The strip inserts itself
     * on the first pick, so a strip still claiming index 2 lands ABOVE the thumbs
     * and the header order becomes kicker, title, strip, thumbs - which is not the
     * design, and not something a later "fix" should have to guess at.
     */
    @Test
    fun `the thumbs sit between the title and the strip, and the strip index moved with them`() {
        val src = source(BYW)

        assertTrue(
            "the strip has to insert one slot BELOW the thumbs row",
            src.contains("const val FEATURED_STRIP_INDEX = 3")
        )
        assertTrue(
            "and the thumbs row owns the slot the strip gave up",
            src.contains("const val TASTE_ROW_INDEX = 2")
        )
        val thumbsInsert = src.indexOf("TASTE_ROW_INDEX,")
        val stripInsert = src.indexOf("FEATURED_STRIP_INDEX,")
        assertTrue("both inserts must exist", thumbsInsert >= 0 && stripInsert >= 0)
        assertTrue(
            "the panel reads kicker, title, thumbs, strip, row scroll, hint",
            thumbsInsert < stripInsert
        )
        assertTrue(
            "the header inset has to include the thumbs, or the row runs under the " +
                "shrunk credits video's notch while everything around it stops short",
            src
                .substringAfter("private fun applyHeaderInset() {")
                .substringBefore("/** The scroll view the pick row lives in")
                .contains("TAG_TASTE_ROW")
        )
    }

    @Test
    fun `the raise carries the finished title's identity to every player`() {
        assertTrue(
            "show() has to take the identity the thumbs write for",
            source(BYW).contains("fun show(titleText: String?, finished: FinishedTitle? = null)")
        )
        listOf(NATIVE to "main player", MPV to "MPV player", EXTERNAL to "external player")
            .forEach { (path, name) ->
                val body = panel(path)
                assertTrue(
                    "the $name must hand the identity over with the raise",
                    body.contains("show(itemName, plan.finished)")
                )
                assertTrue(
                    "and resolve it in the same IO block the picks come from, so " +
                        "no panel is ever raised for a title it cannot name",
                    body.contains("finishedTitleFor(")
                )
            }
    }

    /**
     * A series thumb is a thumb for the SHOW: the identity the panel writes has
     * no season and no episode in it, so an episode-scoped signal is not merely
     * absent from the UI - it is unrepresentable.
     */
    @Test
    fun `the identity is title-level, with no season or episode in it`() {
        val shape = source(BYW)
            .substringAfter("internal data class FinishedTitle(")
            .substringBefore("\n) {")
            .lowercase()

        listOf("season", "episode").forEach { member ->
            assertFalse(
                "per-episode thumbs are excluded by the commission, so FinishedTitle " +
                    "must not carry $member",
                shape.contains(member)
            )
        }
        // The shape was lowercased above so the season/episode scan cannot be
        // dodged by casing; the field needles wear the same casing.
        assertTrue("the four fields the spec pins", shape.contains("val keys: list<string>"))
        assertTrue(shape.contains("val title: string"))
        assertTrue(shape.contains("val mediatype: string"))
        assertTrue(shape.contains("val posterurl: string?"))
    }

    /**
     * A press is inert to the session, pinned by OMISSION: there is no countdown
     * under this panel (the Up Next countdown belongs to the other one, and the
     * raise disarms it), so the thumb code must not touch a countdown field, a
     * handoff flag, a pending-episode field, a dismiss flag, or the session's end
     * - and it must not move focus, which is why nothing on that path requests it.
     */
    @Test
    fun `a thumb press touches no countdown, no handoff, no session state and no focus`() {
        val taste = source(BYW)
            .substringAfter("private fun ensureTasteRow()")
            .substringBefore("/**\n     * Switches the panel between its plain box")
        assertTrue("the taste block could not be located", taste.isNotBlank())

        listOf(
            "nextUpCountdown",
            "nextUpHandoffArmed",
            "pendingNext",
            "bywDismissed",
            "enterCreditsMode",
            "finish()",
            "requestFocus()"
        ).forEach { forbidden ->
            assertFalse(
                "the thumbs row and its press handler must not touch $forbidden",
                taste.contains(forbidden)
            )
        }
        assertTrue(
            "the pressed thumb keeps focus because nothing rebuilds or removes it",
            source(BYW).contains("paintThumbs(")
        )
    }

    /**
     * The row survives every `build()`, the way the featured strip does: `build()`
     * rebuilds the PICKS, and its thumbs belong to no pick. No numeric rating or
     * star control belongs in this panel either - the rating's surface is Detail -
     * and no preference was added for the thumbs, which are always on when the
     * panel is.
     */
    @Test
    fun `build() never rebuilds the taste row, and the panel grows no rating UI or pref`() {
        val src = source(BYW)
        val build = src
            .substringAfter("fun build(picks: List<BywPick>) {")
            .substringBefore("/** The imdb id PLAY / DETAILS hand back for a pick. */")
        assertTrue("build() could not be located", build.isNotBlank())
        assertFalse("build() must not remove the thumbs row", build.contains("TAG_TASTE_ROW"))
        assertFalse("and must not clear the whole panel", build.contains("panel.removeAllViews"))

        listOf("RatingBar", "TitleRatingDialog", "setRating(", "ic_star", "AppPreferences.set")
            .forEach { forbidden ->
                assertFalse(
                    "the panel must not grow $forbidden - the rating is Detail's " +
                        "surface and the thumbs are always on",
                    src.contains(forbidden)
                )
            }
    }

    /**
     * Four drawables, reused from the Detail row, and no thumb on a pick card:
     * the pair is for the finished title, and the marks the row draws are the
     * same four the title's own page draws.
     */
    @Test
    fun `the panel draws the four shared thumb marks and puts none on a card`() {
        val src = source(BYW)
        listOf(
            "R.drawable.ic_detail_thumb_up_outline",
            "R.drawable.ic_detail_thumb_up_filled",
            "R.drawable.ic_detail_thumb_down_outline",
            "R.drawable.ic_detail_thumb_down_filled"
        ).forEach { mark ->
            assertTrue("the panel no longer draws $mark", src.contains(mark))
        }
        assertTrue(
            "and it paints them through the shared chip look, not a new one",
            src.contains("pillChipBackground(")
        )
        listOf(NATIVE, MPV, EXTERNAL).forEach { path ->
            assertFalse(
                "$path must not grow a thumb of its own: the row lives in the " +
                    "shared panel UI",
                source(path).contains("ic_detail_thumb")
            )
        }
    }

    private companion object {
        const val BYW =
            "com/kennyb1201/kbstream/ui/player/BecauseYouWatched.kt"
        const val NATIVE =
            "com/kennyb1201/kbstream/ui/player/NativePlayerActivity.kt"
        const val MPV =
            "com/kennyb1201/kbstream/ui/player/MpvPlayerActivity.kt"
        const val EXTERNAL =
            "com/kennyb1201/kbstream/ui/player/ExternalPlayerActivity.kt"
    }
}
