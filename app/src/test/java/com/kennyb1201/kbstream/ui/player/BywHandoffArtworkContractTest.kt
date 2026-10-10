package com.kennyb1201.kbstream.ui.player

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The because-you-watched handoff's artwork.
 *
 * Playing a movie from the end-credits popup reached the player's loading
 * screen with no clearlogo and no backdrop, from two places at once:
 *
 *  1. The panel fetched a clearlogo per pick into its own `logoCache`, and
 *     `finishWithBywResult` never put it in the result Intent - MainActivity
 *     then hardcoded `clearLogoUrl = null`, so the fetched wordmark could only
 *     ever be drawn inside the popup.
 *  2. The handoff sent `pick.backdropUrl`, which is null for the franchise and
 *     credits candidates (they carry a poster only), while the backdrop the
 *     panel HAD fetched for its featured strip sat in `metaCache` unused.
 *
 * Both are one-way wiring that an Activity's result cannot be driven through in
 * a plain JUnit test, so the shape is pinned at the source, the way this repo's
 * player contracts do it: the panel exposes the two lookups its strip draws
 * from, all three players put them in the result, and MainActivity spends the
 * clearlogo on the pending play instead of discarding it.
 */
class BywHandoffArtworkContractTest {

    private fun readSource(path: String): String {
        val file = File(findSourceRoot(), path)
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

    /** Source with per-line indentation stripped, so nested guards can be matched. */
    private fun normalized(raw: String): String = raw.lines().joinToString("\n") { it.trim() }

    /**
     * The text of the function starting at [signature] up to the next member.
     *
     * The end is the next class-level declaration (or its KDoc) rather than the
     * first line indented by four spaces: a body may legitimately contain such a
     * line, because a multi-line signature closes with `    ) {` at exactly that
     * indent.
     */
    private fun functionBody(source: String, signature: String): String {
        val src = readSource(source)
        val start = src.indexOf(signature)
        assertTrue("source missing function: $signature", start >= 0)
        val rest = src.substring(start + signature.length)
        val end = NEXT_MEMBER.find(rest)?.range?.first ?: rest.length
        return rest.substring(0, end)
    }

    // ── 1. the panel hands out what its strip is drawing ───────────────────

    @Test
    fun `the panel exposes the strip's logo and backdrop, and no poster as backdrop`() {
        // The callback contract is deliberately unchanged: the artwork travels
        // through the result, not through onPlay's signature.
        val panel = normalized(readSource(BYW_UI))
        assertTrue(panel.contains("private val onPlay: (BywPick, String) -> Unit,"))
        assertTrue(panel.contains("private val onDetails: (BywPick, String) -> Unit"))

        val logo = normalized(functionBody(BYW_UI, CLEAR_LOGO_SIG)).trim()
        assertEquals("logoCache[pick.tmdbId]", logo)

        val backdrop = normalized(functionBody(BYW_UI, BACKDROP_SIG))
        assertTrue(
            "the handoff backdrop is the strip's chain: fetched detail, then the pick's own",
            backdrop.contains("metaCache[pick.tmdbId]?.backdropUrl ?: pick.backdropUrl")
        )
        assertFalse(
            "the strip's poster last-resort is not a backdrop: the player already falls " +
                "back to the item poster, and a poster sent as one is cropped as one",
            backdrop.contains("posterUrl")
        )
    }

    // ── 2. every player sends them ─────────────────────────────────────────

    @Test
    fun `every player sends the strip's clearlogo and backdrop in the byw result`() {
        PLAYERS.forEach { (name, path) ->
            val body = normalized(functionBody(path, FINISH_BYW_SIG))
            assertTrue(
                "$name must put the pick's clearlogo in the result Intent",
                CLEAR_LOGO_EXTRA.containsMatchIn(body)
            )
            assertTrue(
                "$name must send the strip's backdrop, not the pick's own",
                BACKDROP_EXTRA.containsMatchIn(body)
            )
        }
    }

    // ── 3. MainActivity spends the clearlogo ───────────────────────────────

    @Test
    fun `the play_now branch hands the clearlogo to the pending play`() {
        val main = normalized(readSource(MAIN_ACTIVITY))
        val playNow = main
            .substringAfter(PLAY_NOW_BRANCH)
            .substringBefore(GO_DETAILS_BRANCH)

        assertTrue(
            "the clearlogo must be read off the result Intent",
            playNow.contains("val bywClearLogo = data.getStringExtra(\"byw_clearlogo\")")
        )
        assertTrue(
            "the pending play must carry it - it used to be hardcoded null",
            playNow.contains("clearLogoUrl = bywClearLogo,")
        )
        // Auto-select off: the pick opens the source list, and the player that
        // list launches is only given the clearlogo if the list was given it.
        assertTrue(
            "both the pending play and the source-list branch must carry it",
            Regex("clearLogoUrl = bywClearLogo").findAll(playNow).count() == 2
        )
        assertFalse(playNow.contains("clearLogoUrl = null"))
    }

    @Test
    fun `the go_details branch still hands over only the backdrop`() {
        val main = normalized(readSource(MAIN_ACTIVITY))
        val goDetails = main
            .substringAfter(GO_DETAILS_BRANCH)
            .substringBefore(NAVIGATE_ACTOR_BRANCH)

        assertTrue(goDetails.contains("itemBackdrop = bywBackdrop"))
        assertFalse(
            "Detail resolves its own clearlogo; the backdrop fallback is all it needs",
            goDetails.contains("byw_clearlogo")
        )
    }

    private companion object {
        const val BYW_UI = "com/kennyb1201/kbstream/ui/player/BecauseYouWatched.kt"
        const val MAIN_ACTIVITY = "com/kennyb1201/kbstream/MainActivity.kt"

        val PLAYERS = mapOf(
            "NativePlayerActivity" to
                "com/kennyb1201/kbstream/ui/player/NativePlayerActivity.kt",
            "MpvPlayerActivity" to
                "com/kennyb1201/kbstream/ui/player/MpvPlayerActivity.kt",
            "ExternalPlayerActivity" to
                "com/kennyb1201/kbstream/ui/player/ExternalPlayerActivity.kt"
        )

        /** The next class-level member, or a KDoc opening for one. */
        val NEXT_MEMBER = Regex("\\n {4}(?:private |internal |public )?(?:fun |val |var |@|/\\*)")

        const val CLEAR_LOGO_SIG = "fun clearLogoFor(pick: BywPick): String? ="
        const val BACKDROP_SIG = "fun backdropFor(pick: BywPick): String? ="
        const val FINISH_BYW_SIG = "private fun finishWithBywResult("

        val CLEAR_LOGO_EXTRA =
            Regex("putExtra\\(\"byw_clearlogo\", [^)]*clearLogoFor\\(pick\\)")
        val BACKDROP_EXTRA =
            Regex("putExtra\\(\"byw_backdrop\", [^)]*backdropFor\\(pick\\)")

        const val PLAY_NOW_BRANCH = "\"play_now\" -> {"
        const val GO_DETAILS_BRANCH = "\"go_details\" -> {"
        const val NAVIGATE_ACTOR_BRANCH = "\"navigate_actor\" -> {"
    }
}
