package com.kennyb1201.kbstream.ui.player

import com.kennyb1201.kbstream.data.player.EpisodeScheme
import com.kennyb1201.kbstream.data.player.SchemeKind
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The MISMATCH self-correction: when a session's own season/episode disagree
 * with the file its stream id names, the file wins.
 *
 * Field case (Paw Patrol S06, a season of mixed 1:1 and sp2 files): a session
 * launched as s=6 e=15 played the id `tmdb:57532:6:8`, so E15 got the watched
 * marker, the Simkl scrobble and the resume point, and E08 - the file that
 * actually played - got nothing. The session's fields are a prediction carried
 * from the previous session's arithmetic; the id is what was resolved and is
 * playing. [PlaybackHistoryIds.correctedSessionEpisode] rewrites the prediction
 * from the file through the detected scheme, and these cases pin both halves:
 * that it repairs a genuine mismatch, and that it stays a no-op on everything
 * the scheme already maps correctly.
 */
class SessionEpisodeCorrectionTest {

    private val oneToOne = EpisodeScheme.ONE_TO_ONE
    private val sp2 = EpisodeScheme(SchemeKind.SEGMENTS_PER_FILE, 2)
    private val fe3 = EpisodeScheme(SchemeKind.FILES_PER_EPISODE, 3)

    // ── the repair ────────────────────────────────────────────────────────

    @Test
    fun `the Paw Patrol mismatch is corrected to the file's episode`() {
        val corrected = PlaybackHistoryIds.correctedSessionEpisode(
            season = 6,
            episode = 15,
            episodeStreamId = "tmdb:57532:6:8",
            scheme = oneToOne
        )

        assertEquals(6 to 8, corrected)
    }

    @Test
    fun `a cross-season mismatch follows the id's season too`() {
        assertEquals(
            6 to 8,
            PlaybackHistoryIds.correctedSessionEpisode(5, 15, "tmdb:57532:6:8", oneToOne)
        )
    }

    @Test
    fun `an sp2 file corrects a session that drifted off its two episodes`() {
        // File 9 holds TMDB episodes [17, 18]; a session claiming 15 did not
        // come from this file, so it is corrected to the file's first (17).
        assertEquals(
            6 to 17,
            PlaybackHistoryIds.correctedSessionEpisode(6, 15, "tmdb:57532:6:9", sp2)
        )
    }

    // ── the no-ops: the scheme already maps them ──────────────────────────

    @Test
    fun `a consistent 1 to 1 session is left alone`() {
        assertNull(
            PlaybackHistoryIds.correctedSessionEpisode(6, 8, "tmdb:57532:6:8", oneToOne)
        )
    }

    @Test
    fun `an sp2 file that holds the session's episode is not corrected`() {
        // File 9 holds [17, 18], so both segments are the right session and
        // must not be renumbered to the file's first.
        assertNull(
            PlaybackHistoryIds.correctedSessionEpisode(6, 17, "tmdb:57532:6:9", sp2)
        )
        assertNull(
            PlaybackHistoryIds.correctedSessionEpisode(6, 18, "tmdb:57532:6:9", sp2)
        )
    }

    @Test
    fun `any file of an fe3 episode's group is not corrected`() {
        // fe3: files 4, 5 and 6 all hold TMDB episode 2. A session on the
        // second or third file is correctly mapped, not mismatched.
        (4..6).forEach { file ->
            assertNull(
                "file $file holds episode 2",
                PlaybackHistoryIds.correctedSessionEpisode(6, 2, "tmdb:57532:6:$file", fe3)
            )
        }
    }

    @Test
    fun `no id or no episode is never a correction and never crashes`() {
        assertNull(PlaybackHistoryIds.correctedSessionEpisode(6, 15, null, oneToOne))
        assertNull(PlaybackHistoryIds.correctedSessionEpisode(6, 15, "", oneToOne))
        // A show-level id names no episode to correct from.
        assertNull(PlaybackHistoryIds.correctedSessionEpisode(6, 15, "tmdb:57532", oneToOne))
        assertNull(
            PlaybackHistoryIds.correctedSessionEpisode(null, null, "tmdb:57532:6:8", oneToOne)
        )
        assertNull(PlaybackHistoryIds.correctedSessionEpisode(6, null, "tmdb:57532:6:8", oneToOne))
    }

    // ── wiring: every engine repairs at session start ─────────────────────

    @Test
    fun `every player corrects the session before it builds the history row`() {
        listOf(NATIVE, MPV, EXTERNAL).forEach { path ->
            val src = read(path)
            val call = src.indexOf("PlaybackHistoryIds.correctedSessionEpisode(")
            assertTrue(
                "$path must correct the session's episode from the file the id names, " +
                    "or the wrong episode is filed, scrobbled and handed off",
                call >= 0
            )
            val row = src.indexOf("PlaybackHistoryIds.historyId(", call)
            assertTrue(
                "$path must correct BEFORE it computes the history id, so the row and the " +
                    "downstream writes see the file's episode",
                row > call
            )
        }
    }

    @Test
    fun `the correction is logged with the scheme`() {
        val source = read(IDS)
        val start = source.indexOf("fun correctedSessionEpisode(")
        assertTrue("the repair lives in PlaybackHistoryIds", start >= 0)
        val end = source.indexOf("fun playbackSessionLine(", start)
        assertTrue("playbackSessionLine must follow it", end > start)
        val body = source.substring(start, end)
        assertTrue("it logs the correction", body.contains("Log.w("))
        assertTrue(
            "and the log names the scheme that mapped the file, so a report shows why",
            body.contains("scheme.encode()")
        )
    }

    // ── source helpers ────────────────────────────────────────────────────

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

    private companion object {
        const val NATIVE = "com/kennyb1201/kbstream/ui/player/NativePlayerActivity.kt"
        const val MPV = "com/kennyb1201/kbstream/ui/player/MpvPlayerActivity.kt"
        const val EXTERNAL = "com/kennyb1201/kbstream/ui/player/ExternalPlayerActivity.kt"
        const val IDS = "com/kennyb1201/kbstream/ui/player/PlaybackHistoryIds.kt"
    }
}
