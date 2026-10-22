package com.kennyb1201.kbstream.ui.detail

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A press that has to end in the streams picker is RECORDED by the detail
 * screen and DELIVERED by one effect, and the delivery can never swallow it.
 *
 * The field report behind this: "Play Manually" in every long-press menu "isn't
 * bringing up streams screen, it's just bringing me to details", and the same
 * row on the Play button of that details page then did nothing at all. Two
 * separate ways the effect could lose a press, both silent:
 *
 *  - it latched `autoPlayed` BEFORE awaiting the title's resolved stream root.
 *    That wait is a suspension point, and every one of the effect's other keys
 *    (the resume row, the episode list, the meta) is published while a load is
 *    still settling, which cancels the run and restarts it - where the restarted
 *    run read the latch and returned. The press was gone, and stayed gone.
 *  - a press was only visible to the effect through the flags it set, so a press
 *    whose flag already had the value it wanted ("Play Manually" on a screen a
 *    poster menu's "Play Manually" had already put into the manual state)
 *    changed no key at all and never ran the effect.
 *
 * And one way it could sit on a condition the press does not need: the series
 * episode list, which only the Continue Watching deep link has to wait for - the
 * Play button beside the menu builds a target without it and played.
 *
 * This is Compose state and effect ordering inside a composable, so a device
 * cannot be avoided here; what a contract test can pin is the shape and the
 * order, which is exactly what decided the report.
 */
class DetailPlayPressDeliveryContractTest {

    private val detail by lazy {
        val file = File(findSourceRoot(), DETAIL)
        assertTrue("source missing: $file", file.isFile)
        blankCommentsNormalized(file.readText())
    }

    /**
     * The auto-play/delivery effect and its body: from the latch's declaration
     * to the page's root Box that follows it.
     */
    private fun effectBody(): String {
        val start = detail.indexOf("var autoPlayed by remember")
        assertTrue("the delivery effect's latch not found", start >= 0)
        val end = detail.indexOf("Box(", start)
        assertTrue("the delivery effect's end not found", end > start)
        return detail.substring(start, end)
    }

    @Test
    fun `a press is a recorded key, so it always re-runs the delivery`() {
        assertTrue(
            "the press needs a record of its own, or a press whose flags are " +
                "already in the state it wants changes no effect key",
            detail.contains(
                "var playPress by remember(type, id) { mutableIntStateOf(0) }"
            )
        )
        assertTrue(
            "and the delivery has to key on it",
            effectBody().contains("playPress,")
        )
    }

    @Test
    fun `both Play menu rows record the press they raise`() {
        val menu = detail
            .substringAfter("if (playButtonMenu) {")
            .substringBefore("onDismiss = { dismissPlayButtonMenu() }")
        assertEquals(
            "each row has to record its press after clearing the latch that " +
                "would otherwise retire it",
            2,
            Regex("autoPlayed = false\\nplayPress\\+\\+").findAll(menu).count()
        )
    }

    @Test
    fun `a poster menu's marker is recorded as a press too`() {
        assertTrue(
            "the arrival marker has to read the pending request",
            detail.contains("manualPick = ManualSourceSelection.consume()")
        )
        assertTrue(
            "and record it as a press, not only as a flag",
            detail.contains("if (manualPick) playPress++")
        )
    }

    @Test
    fun `the delivered latch is set after the wait it must survive`() {
        val body = effectBody()
        val wait = body.indexOf("playTargetFor(viewModel.awaitStreamRootId(id))")
        val latch = body.indexOf("autoPlayed = true")
        assertTrue("the root wait is gone", wait >= 0)
        assertTrue("the latch is gone", latch >= 0)
        assertTrue(
            "the latch must be set AFTER the root wait: set before it, any key " +
                "changing while the wait suspends cancels the run and the " +
                "restarted run answers \"already auto-played\" - the press the " +
                "report describes as lost",
            latch > wait
        )
        assertTrue(
            "and it must be set before the navigation it is retiring",
            latch < body.indexOf("onNavigateStreams(")
        )
    }

    @Test
    fun `a press is not parked on an empty series episode list the way a deep link is`() {
        val body = effectBody()
        assertFalse(
            "an unqualified episode wait parks a press on a list that may never " +
                "arrive: the Play button builds the same target without it, which " +
                "is why the picker opened from the button and not from \"Play Manually\"",
            body.contains(
                "if (\nnormalizedType == \"series\" &&\n" +
                    "(episodesLoading || episodes.isEmpty())\n) return@LaunchedEffect"
            )
        )
        assertTrue(
            "a press still waits while the listing is LOADING - the next episode " +
                "is what the list names",
            body.contains(
                "val waitingForEpisodes =\nif (manualPick || startOver) {\nepisodesLoading\n}"
            )
        )
        assertTrue(
            "and the deep link keeps both halves of the wait",
            body.contains("} else {\nepisodesLoading || episodes.isEmpty()\n}")
        )
        assertTrue(
            "the qualified wait is what the guard reads",
            body.contains("if (\nnormalizedType == \"series\" &&\nwaitingForEpisodes\n) return@LaunchedEffect")
        )
    }

    @Test
    fun `every delivered press names itself in the log`() {
        val body = effectBody()
        assertTrue(
            "the delivery has to be visible in a field capture - it was silent " +
                "while the report was being chased",
            body.contains("DETAIL_PLAY_TAG")
        )
        assertTrue("the tag is the detail page's play tag", detail.contains("\"DETAIL_PLAY\""))
        assertTrue(
            "and the line names the intent and the target",
            body.contains("\"play delivered: manual=")
        )
    }

    private fun blankCommentsNormalized(raw: String): String =
        raw.lineSequence()
            .joinToString("\n") { line ->
                // Whole-line comments are dropped; trailing ones are kept out of
                // the match by starting every anchor at a code line.
                if (line.trimStart().startsWith("//")) "" else line.trim()
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

    private companion object {
        const val DETAIL = "com/kennyb1201/kbstream/ui/detail/DetailScreen.kt"
    }
}
