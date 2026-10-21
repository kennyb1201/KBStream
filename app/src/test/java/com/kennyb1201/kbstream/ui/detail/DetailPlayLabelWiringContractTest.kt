package com.kennyb1201.kbstream.ui.detail

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * That the Detail ViewModel derives the Play button's label from the state the
 * press will act on, instead of being handed a fixed string.
 *
 * [DetailPlayLabelTest] pins the rule; this pins that the rule is what the
 * ViewModel asks, that it is re-run wherever an input moves (the resume row,
 * the loaded season, its target episode), and that the fixed "Play S2E3" /
 * "Resume S2E3" literals that used to be written by the season load are gone -
 * they were why a half-watched film still read "Play" and a paused episode read
 * "Play S2E3" on a press that resumed it.
 *
 * Read from the source: this is a ViewModel's private derivation, which needs a
 * Room database and a device to observe.
 */
class DetailPlayLabelWiringContractTest {

    /** The ViewModel, comments removed, then whitespace collapsed. */
    private val vm: String by lazy { normalized(stripComments(read())) }

    @Test
    fun `the label is derived, never written as a fixed string`() {
        assertTrue(
            "the ViewModel must derive the label through the shared rule",
            vm.contains("publishPlayButtonLabel()") &&
                vm.contains("detailPlayLabel(")
        )
        assertFalse(
            "the old season-load label was a fixed string, and it is what " +
                "promised \"Play S2E3\" for a press that resumed the episode",
            vm.contains("\"Play S") || vm.contains("\"Resume S")
        )
    }

    @Test
    fun `it reads the three things the press depends on`() {
        val body = vm
            .substringAfter("private fun publishPlayButtonLabel() {")
            .substringBefore("Watches this title's rows in the watch-history table")

        assertTrue(
            "the resume row is what makes a film's press a RESUME",
            body.contains("_resumeInfo.value")
        )
        assertTrue(
            "the target episode and its season are the pair the press starts",
            body.contains("_targetEpisode.value") && body.contains("_loadedSeason.value")
        )
        assertTrue(
            "and a resume for ANOTHER season must not label this button - it " +
                "would promise an episode the press will not play",
            body.contains("resume.season == season")
        )
        assertTrue(
            "the target episode's OWN paused row is read too: the newest row on a " +
                "show is not always the episode this press starts, and the play " +
                "paths resume from the target's own row",
            body.contains("_inProgressByStreamId.value[episode.streamId]")
        )
    }

    @Test
    fun `the resume row re-derives the label whenever it moves`() {
        val progress = vm
            .substringAfter("private fun publishLocalProgress(")
            .substringBefore("private fun publishPlayButtonLabel() {")

        assertTrue(
            "the one-shot read and every later history emission both publish " +
                "the resume row through this function, so the label has to move " +
                "with it - that is what turns a finished film's button back from " +
                "RESUME to PLAY",
            progress.contains("publishPlayButtonLabel()")
        )
        val cloudFallback = vm
            .substringAfter("mdbListPlaybackResumeFor(")
            .substringBefore("val localCompletedEntries")
        assertTrue(
            "the cloud resume fallback is a resume too, so the button has to " +
                "offer RESUME for it exactly as it does for a local row",
            cloudFallback.contains("publishPlayButtonLabel()")
        )
    }

    @Test
    fun `the episode listing labels through the same rule`() {
        assertTrue(
            "the season load must set the target episode and then re-derive, " +
                "so the pair in the label is the pair the press starts",
            vm.contains("_targetEpisode.value = targetEp publishPlayButtonLabel()")
        )
    }

    private fun read(): String =
        File(
            findMainSourceRoot(),
            "com/kennyb1201/kbstream/ui/detail/DetailViewModel.kt"
        ).readText()

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
}
