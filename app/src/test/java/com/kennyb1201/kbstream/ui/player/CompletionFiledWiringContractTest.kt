package com.kennyb1201.kbstream.ui.player

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The session's `completionFiled` flag, and the resume point it keeps gone.
 *
 * The field report: pause an episode with ten minutes left (a resume point is
 * saved), resume from it, watch to the end - and the episode is NOT marked as
 * watched while the resume point survives, so the show keeps returning to the
 * ten-minutes-left position forever.
 *
 * Both halves are pinned here, and neither is a rule that can be lost in a
 * silent refactor:
 *
 *  1. the 90% threshold completes an episode the file never announced the end
 *     of, and the pause/exit saves that follow used to re-decide the verdict
 *     from the playhead alone - so one rewind after the threshold filed a
 *     RESUME row straight over the completion. Each engine now records that a
 *     completion was FILED, judges every later save of the session by that
 *     fact, and cannot write a resume row back over it;
 *  2. a completion DELETES the episode's resume rows ([PlaybackHistoryWriter]),
 *     including a resume row filed under a different row id than the completion
 *     - which the completion's own overwrite cannot reach - and tombstones
 *     them in the cloud so the next pull cannot put them back.
 *
 * Read out of the sources rather than exercised, for the reason this tree's
 * other player contracts give: an Activity that drives a libmpv/ExoPlayer
 * surface cannot be run from a JVM test, and a flag nobody reads fixes nothing.
 * The behaviour of the flag itself is pinned by [PlayerCompletionRulesTest],
 * and the SQL the cleanup uses by [com.kennyb1201.kbstream.data.history
 * .CompletedResumeClearDaoTest].
 */
class CompletionFiledWiringContractTest {

    private fun read(relative: String): String {
        val file = File(findSourceRoot(), relative)
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

    private fun occurrences(haystack: String, needle: String): Int {
        var count = 0
        var at = haystack.indexOf(needle)
        while (at >= 0) {
            count++
            at = haystack.indexOf(needle, at + needle.length)
        }
        return count
    }

    private fun body(source: String, anchor: String, spanLength: Int = 6_000): String {
        val at = source.indexOf(anchor)
        assertTrue("anchor not found: $anchor", at >= 0)
        return source.substring(at, minOf(source.length, at + spanLength))
    }

    // ── the flag exists, is read, and is set when a completion is written ──

    private data class Engine(
        val path: String,
        /** The one line that makes a filed completion sticky for the session. */
        val sticky: String,
        /** The line that claims the flag once a completion has been written. */
        val claims: String,
        /** How many `completionFiled = completionFiled` passes to expect. */
        val passes: Int
    )

    private val engines = listOf(
        Engine(
            path = "com/kennyb1201/kbstream/ui/player/NativePlayerActivity.kt",
            sticky = "val sessionCompleted = forceCompleted || playbackEndReached || completionFiled",
            claims = "if (isCompleted) completionFiled = true",
            passes = 2
        ),
        Engine(
            path = "com/kennyb1201/kbstream/ui/player/MpvPlayerActivity.kt",
            sticky = "val sessionCompleted = forceCompleted || endedHandled || completionFiled",
            claims = "if (completed) completionFiled = true",
            passes = 2
        ),
        Engine(
            path = "com/kennyb1201/kbstream/ui/player/ExternalPlayerActivity.kt",
            sticky = "val completed = forceCompleted || completionFiled ||",
            claims = "if (completed) completionFiled = true",
            passes = 0
        )
    )

    @Test
    fun `every engine declares the flag and starts it unfiled`() {
        engines.forEach { engine ->
            val source = read(engine.path)
            assertTrue(
                "${engine.path} must declare completionFiled",
                source.contains("private var completionFiled = false")
            )
            assertTrue(
                "${engine.path} must make a filed completion sticky",
                source.contains(engine.sticky)
            )
            assertTrue(
                "${engine.path} must claim the flag when a completion is written",
                source.contains(engine.claims)
            )
        }
    }

    @Test
    fun `the handoff and exit verdicts are told the completion was filed`() {
        engines.forEach { engine ->
            val source = read(engine.path)
            assertEquals(
                "${engine.path}: every shouldRecordCompletion call site must pass the " +
                    "filed fact, or a rewind after the threshold re-files a resume point",
                engine.passes,
                occurrences(source, "completionFiled = completionFiled")
            )
        }
    }

    @Test
    fun `the flag is claimed before the completion write is launched`() {
        engines.forEach { engine ->
            val source = read(engine.path)
            val claimAt = source.indexOf(engine.claims)
            assertTrue("${engine.path}: claim missing", claimAt >= 0)
            // Claiming the flag first is what makes a save racing it read the
            // fact instead of re-deciding from the playhead - so the claim has
            // to precede the row actually being written, whichever coroutine
            // that happens in.
            val writeAt = source.indexOf("PlaybackHistoryWriter.write(", claimAt)
            assertTrue(
                "${engine.path}: the claim has no row write after it",
                writeAt > claimAt
            )
        }
    }

    @Test
    fun `the mpv threshold never re-fires after a completion is filed`() {
        val source = read("com/kennyb1201/kbstream/ui/player/MpvPlayerActivity.kt")
        assertTrue(
            "the 90% poller must be gated on the filed flag, not only on a local " +
                "'sent' flag that the pause path can race",
            source.contains("if (!completionFiled &&")
        )
        assertTrue(
            "and the old completionSent name must not survive as a second flag " +
                "that can drift out of step with it",
            !source.contains("completionSent")
        )
    }

    // ── the BYW panel asserts the episode is marked watched ────────────────

    @Test
    fun `showing because-you-watched records whether the completion was filed`() {
        listOf(
            "com/kennyb1201/kbstream/ui/player/NativePlayerActivity.kt",
            "com/kennyb1201/kbstream/ui/player/MpvPlayerActivity.kt",
            "com/kennyb1201/kbstream/ui/player/ExternalPlayerActivity.kt"
        ).forEach { path ->
            val source = read(path)
            val panel = body(source, "fun showBecauseYouWatchedPanel()")
            assertTrue(
                "$path: the panel must record the filed state - 'BYW appeared but " +
                    "the episode is not marked watched' is the inconsistency the " +
                    "field report asks to catch",
                panel.contains("\"byw shown filed=false") &&
                    panel.contains("\"byw shown filed=true")
            )
            assertTrue(
                "$path: and it must say so loudly when the two disagree",
                panel.contains("BYW raised without a filed completion")
            )
            // The note belongs to the panel showing, not to some later path: it
            // has to be inside the function that raises it.
            val showAt = panel.indexOf("ui.show(itemName)")
            val noteAt = panel.indexOf("\"byw shown filed=false")
            assertTrue("$path: note must precede the raise", noteAt >= 0)
            if (showAt >= 0) {
                assertTrue("$path: note must be part of raising it", noteAt < showAt)
            }
        }
    }

    // ── the completion deletes the resume point, locally and in the cloud ──

    private val writer by lazy { read("com/kennyb1201/kbstream/data/history/PlaybackHistoryWriter.kt") }

    @Test
    fun `a filed completion clears the episode's superseded resume rows`() {
        assertTrue(
            "the writer is the one place a playback row is written, so the cleanup " +
                "belongs here and reaches every engine",
            writer.contains("clearSupersededResumeRows(row, dao)")
        )
        assertTrue(
            "and the cleanup must find the rows by the episode's identity",
            writer.contains("dao.getSupersededResumeIds(")
        )
        assertTrue(
            "deleting them, not merely overwriting the row the completion landed on",
            writer.contains("dao.deleteByIds(ids)")
        )
    }

    @Test
    fun `the deleted resume rows are tombstoned in the cloud`() {
        assertTrue(
            "a resume row deleted only locally is re-inserted by the next pull, " +
                "which is how the finished episode's card came back",
            writer.contains("SupabaseSync.deleteHistoryRows(cleared")
        )
    }

    @Test
    fun `the cleanup runs only for a completion and only once per save`() {
        val helper = body(writer, "private suspend fun clearSupersededResumeRows(", 1_800)
        assertTrue(
            "an in-progress save must not touch resume rows at all",
            helper.contains("if (!entry.isCompleted) return emptyList()")
        )
        assertTrue(
            "and the completed row itself is never a candidate",
            helper.contains("filter { it != entry.id }")
        )
    }
}
