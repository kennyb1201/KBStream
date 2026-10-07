package com.kennyb1201.kbstream.ui.detail

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Two pieces of the detail screen's own wiring, both of which are exactly the
 * kind of change a compile cannot tell apart from a half-finished one.
 *
 * **The hero Play button's long press.** It used to jump straight into the
 * streams picker, which meant the only action a long press could express was
 * "play manually" - the "play from beginning" a poster's long press offers had
 * no equivalent on the one screen whose whole job is playing the title. The
 * long press now opens the same two-row menu, and each row hands the press to
 * the screen's existing auto-play effect (which owns the single navigation
 * into the picker) instead of building a second copy of it: the row sets the
 * flag that effect reads, and clears `autoPlayed` so it runs again on a screen
 * that already auto-played once.
 *
 * **Spoiler-free mode's consumer.** The rule itself is unit tested in
 * `SpoilerFreeTest`; what has to be pinned here is that the episode card is
 * actually asking it, and that all three spoiler surfaces - the name, the
 * still and the synopsis - are covered rather than just one of them.
 *
 * This is wiring inside Android composables, so a device cannot be avoided
 * here. What a contract test can pin is the shape and the order.
 */
class DetailPlayMenuAndSpoilerContractTest {

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

    /** Source with per-line indentation stripped, so nested blocks can be matched. */
    private fun normalized(raw: String): String = raw.lines().joinToString("\n") { it.trim() }

    private val detail by lazy { readSource(DETAIL) }
    private val normalizedDetail by lazy { normalized(detail) }

    @Test
    fun `the hero Play button's long press opens the menu, not the picker`() {
        assertTrue(
            "the long press must open the play menu",
            normalizedDetail.contains("onLongClick = { playButtonMenu = true },")
        )
        // The old behavior must be gone: a long press that still requests a
        // manual pick and opens the picker in the same breath would mean the
        // menu can never appear.
        assertFalse(
            "the long press must not jump straight into the picker",
            Regex(
                "ManualSourceSelection\\.request\\(\\)\\s*\\n\\s*openStreams\\(\\)"
            ).containsMatchIn(detail)
        )
    }

    @Test
    fun `the menu offers both rows a poster's long press offers`() {
        assertTrue(
            "the menu must be gated on its own state",
            normalizedDetail.contains("if (playButtonMenu) {")
        )
        assertTrue(
            "the menu must open on a PosterContextMenu",
            normalizedDetail.contains("subtitle = playLabel,")
        )
        val menu = normalizedDetail
            .substringAfter("if (playButtonMenu) {")
            .substringBefore("episodeMenu?.let { target ->")
        assertTrue(
            "the menu must offer Play from Beginning",
            menu.contains("label = \"Play from Beginning\",")
        )
        assertTrue(
            "the menu must offer Play Manually",
            menu.contains("label = \"Play Manually\",")
        )
        assertTrue(
            "dismissing the menu must clear the gate through the one hand-back " +
                "that also returns focus to the button",
            menu.contains("onDismiss = { dismissPlayButtonMenu() }")
        )
    }

    @Test
    fun `each row drives the screen's own auto-play effect rather than the picker`() {
        val menu = normalizedDetail
            .substringAfter("if (playButtonMenu) {")
            .substringBefore("episodeMenu?.let { target ->")

        // Play from Beginning: the same flag the poster-menu handoff sets, so
        // the target is rebuilt at position 0 before the effect navigates.
        assertTrue(
            "Play from Beginning must set the start-over flag",
            menu.contains("dismissPlayButtonMenu()\nstartOver = true\nautoPlayed = false")
        )
        // Play Manually: the flag the effect turns into a keyed
        // ManualSourceSelection request for this exact target.
        assertTrue(
            "Play Manually must set the manual-pick flag",
            menu.contains("dismissPlayButtonMenu()\nmanualPick = true\nautoPlayed = false")
        )
    }

    @Test
    fun `the episode card asks the spoiler rule about every episode`() {
        assertTrue(
            "the card must consult the shared rule",
            normalizedDetail.contains("val hidesSpoiler = SpoilerFree.hidesIdentity(")
        )
        assertTrue(
            "the mode must be read from the preference",
            normalizedDetail.contains("AppPreferences.getSpoilerFree(spoilerFreeContext)")
        )
    }

    @Test
    fun `all three spoiler surfaces are covered, not just the name`() {
        // The name, including what a screen reader is handed.
        assertTrue(
            "a hidden episode must be listed by its number",
            normalizedDetail.contains("SpoilerFree.episodeLabel(")
        )
        assertTrue(
            "the accessibility description must not leak the name either",
            normalizedDetail.contains("contentDescription = listedTitle,")
        )
        // The still: covered by an opaque scrim on EVERY API level.
        assertTrue(
            "the still must be covered by an opaque scrim when hidden",
            normalizedDetail.contains("KBVoid.copy(alpha = 0.94f)")
        )
        // The per-frame RenderEffect blur is gone: it re-evaluated on every
        // frame of an episode-row scroll, and under the 94% scrim it was not
        // visible. The scrim above is what holds, so the blur must not return.
        assertFalse(
            "the per-frame blur must not come back",
            normalizedDetail.contains("posterBlurRadius")
        )
        assertTrue(
            "the still must be labelled as hidden",
            normalizedDetail.contains("text = SpoilerFree.HIDDEN_STILL_LABEL,")
        )
        // The synopsis.
        assertTrue(
            "the synopsis must be replaced, not left blank",
            normalizedDetail.contains("SpoilerFree.HIDDEN_SYNOPSIS")
        )
    }

    private companion object {
        private const val DETAIL =
            "com/kennyb1201/kbstream/ui/detail/DetailScreen.kt"
    }
}
