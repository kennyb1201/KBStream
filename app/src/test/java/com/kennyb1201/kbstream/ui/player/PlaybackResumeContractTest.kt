package com.kennyb1201.kbstream.ui.player

import com.kennyb1201.kbstream.data.history.WatchHistoryEntity
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A source picked from the streams picker must resume from the saved position.
 *
 * Reported problem: long-press "Play Manually", pick a source, and a title
 * Continue Watching has progress on started from the beginning - every time.
 * The picker launches with no position of its own (the Detail screen's manual
 * route fires as soon as the metadata is ready rather than waiting on a resume
 * lookup, and a session restored after BACK carries only the position it
 * started with), so the last place that can answer "where was the viewer" is
 * the player itself.
 *
 * That rule lived inline in the ExoPlayer engine - which is why the same press
 * resumed in one engine and restarted the title in the others. It now lives in
 * one file ([PlaybackResume]) that all three engines consult before they start
 * the file, which is what these two halves pin: the rule's own answers, and the
 * wiring that makes each engine ask.
 */
class PlaybackResumeContractTest {

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

    private fun read(relative: String): String {
        val file = File(findSourceRoot(), relative)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

    // ── the rule ────────────────────────────────────────────────────────────

    @Test
    fun `a launch with no position of its own resumes`() {
        assertTrue(
            "a picker launch knows nothing about where the viewer was, so the " +
                "watch history is the only answer",
            PlaybackResume.mayResumeFromHistory(
                startPositionMs = 0L,
                startFromBeginning = false,
                historyId = "tt10986410:1:2"
            )
        )
    }

    @Test
    fun `a launch that carries a position keeps it`() {
        // Continue Watching carries the resume point itself; a launch that
        // already knows where to start must not be argued with.
        assertFalse(
            PlaybackResume.mayResumeFromHistory(
                startPositionMs = 12L * 60_000L,
                startFromBeginning = false,
                historyId = "tt10986410"
            )
        )
    }

    @Test
    fun `from the beginning is never a resume`() {
        // The one flag that wins over the history row, position 0 included.
        assertFalse(
            PlaybackResume.mayResumeFromHistory(
                startPositionMs = 0L,
                startFromBeginning = true,
                historyId = "tt10986410"
            )
        )
    }

    @Test
    fun `a blank row id has nothing to ask about`() {
        assertFalse(
            PlaybackResume.mayResumeFromHistory(
                startPositionMs = 0L,
                startFromBeginning = false,
                historyId = ""
            )
        )
    }

    // ── the widened lookup ─────────────────────────────────────────────────

    private fun row(
        id: String,
        parentId: String,
        season: Int? = null,
        episode: Int? = null,
        episodeStreamId: String? = null,
        positionMs: Long = 5_000L,
        isCompleted: Boolean = false
    ) = WatchHistoryEntity(
        id = id,
        parentId = parentId,
        type = if (season != null) "series" else "movie",
        name = "Title",
        poster = null,
        streamUrl = null,
        season = season,
        episode = episode,
        episodeStreamId = episodeStreamId,
        positionMs = positionMs,
        durationMs = 60_000L,
        updatedAt = 1L,
        isCompleted = isCompleted
    )

    @Test
    fun `a row matches the episode by its stream id`() {
        val saved = row(
            id = "tt10986410:1:2",
            parentId = "tt10986410",
            season = 1,
            episode = 2,
            episodeStreamId = "tt10986410:1:2"
        )
        assertTrue(
            "the stream id names the exact episode, so a launch carrying it " +
                "must find the row filed under the show's other id flavor",
            saved.namesSameEpisode(1, 2, "tt10986410:1:2")
        )
    }

    @Test
    fun `a row matches the episode by season and episode when the stream ids differ`() {
        val saved = row(
            id = "tmdb:123:1:2",
            parentId = "tt10986410",
            season = 1,
            episode = 2,
            episodeStreamId = "tmdb:123:1:2"
        )
        assertTrue(
            "the id flavor changed the stream id but not the episode, so the " +
                "numbered pair is what finds it",
            saved.namesSameEpisode(1, 2, "tt10986410:1:2")
        )
    }

    @Test
    fun `a row for another episode of the same show does not match`() {
        val other = row(
            id = "tt10986410:1:3",
            parentId = "tt10986410",
            season = 1,
            episode = 3,
            episodeStreamId = "tt10986410:1:3"
        )
        assertFalse(
            "resuming the wrong episode is worse than restarting: the fallback " +
                "must never take a sibling episode",
            other.namesSameEpisode(1, 2, "tt10986410:1:2")
        )
    }

    @Test
    fun `a movie matches its title's own row even when the stream ids differ`() {
        // A movie's stream id is the route's parent id, so the two flavors
        // produce different "episode" ids for the SAME title.
        val saved = row(
            id = "tmdb:123",
            parentId = "tt10986410",
            episodeStreamId = "tmdb:123"
        )
        assertTrue(
            "with no episode identity the title's in-progress row is the " +
                "answer, whichever flavor launched playback",
            saved.namesSameEpisode(null, null, "tt10986410")
        )
    }

    // ── the wiring ──────────────────────────────────────────────────────────

    @Test
    fun `every engine asks the shared rule before it starts the file`() {
        val engines = mapOf(
            "ExoPlayer" to NATIVE,
            "MPV" to MPV,
            "external" to EXTERNAL
        )
        engines.forEach { (name, path) ->
            val source = read(path)
            assertTrue(
                "$name does not consult the shared resume rule, so a source " +
                    "picked from the picker restarts the title there",
                source.contains("PlaybackResume.mayResumeFromHistory(")
            )
            assertTrue(
                "$name never reads the saved position",
                source.contains("PlaybackResume.savedPositionMs(")
            )
            assertTrue(
                "$name has to hand the rule both halves of the question: the " +
                    "position it was launched with and the raw id it files under",
                source.contains("startPositionMs = startPositionMs") &&
                    source.contains("historyId = historyId")
            )
        }
    }

    @Test
    fun `every engine hands the lookup the whole launch identity`() {
        // The widened lookup cannot search the title's rows without the parent
        // id, and cannot match the episode without season/episode/stream id.
        listOf("ExoPlayer" to NATIVE, "MPV" to MPV, "external" to EXTERNAL).forEach {
            (name, path) ->
            val source = read(path)
            assertTrue(
                "$name does not give the resume lookup the parent id it needs " +
                    "to find the title under another id flavor",
                source.contains("PlaybackResume.savedPositionMs(") &&
                    source.contains("parentId = parentId") &&
                    source.contains("parentType = parentType")
            )
            assertTrue(
                "$name does not give the resume lookup the episode it is " +
                    "resuming, so the fallback could take a sibling episode",
                source.contains("season = season") &&
                    source.contains("episode = episode") &&
                    source.contains("episodeStreamId = episodeStreamId")
            )
        }
    }

    @Test
    fun `a tt-first session leaves the tmdb twin resolvable offline`() {
        // The widened fallback resolves the launch's parent id to canonical to
        // find a row written under the other flavor. For a "tmdb:<n>" route
        // that resolve is only offline if the pair was recorded when the title
        // played under "tt..." - and the enriched-meta cache the player reads
        // does not record it, so resolveTmdbId itself has to.
        val ids = read(
            "com/kennyb1201/kbstream/ui/player/PlaybackHistoryIds.kt"
        )
        assertTrue(
            "the player's tmdb resolve no longer records the IMDB<->TMDB pair, " +
                "so a later tmdb: route has to resolve it over the network (and " +
                "cannot, on a device with no TMDB key)",
            ids.contains("recordResolution(")
        )
        assertTrue(
            "recording has to be gated on a real tt<->tmdb pair, or a miss forges " +
                "a mapping every later lookup trusts",
            ids.contains("recordsResolution(parentId, resolved)")
        )
    }

    @Test
    fun `the in-app engines only open the file after the position is known`() {
        // The player is created / the file is opened inside the fallback
        // branch, after the read: nothing starts at 0 and jumps.
        val native = read(NATIVE)
        val nativeBlock = native.substring(
            native.indexOf("PlaybackResume.mayResumeFromHistory("),
            native.indexOf("} else {", native.indexOf("PlaybackResume.mayResumeFromHistory("))
        )
        // The initial build is entered through probeThenCreatePlayer now: the
        // pre-playback probe decides WHICH source to open before the player is
        // created, so the ordering this pins is "position known, then the
        // probe, then the player" - and the build itself still happens through
        // createPlayer() at the end of that path.
        assertTrue(
            "ExoPlayer has to know the position before it creates the player",
            nativeBlock.indexOf("PlaybackResume.savedPositionMs(") <
                nativeBlock.indexOf("probeThenCreatePlayer()")
        )
        assertTrue(
            "and the player is still built by the one createPlayer() path",
            native.contains("private fun probeThenCreatePlayer() {") &&
                native.contains("createPlayer()")
        )
        assertTrue(
            "and the position it found is the one the session starts from",
            native.contains("startPositionMs = savedPositionMs")
        )

        val mpv = read(MPV)
        val mpvBlock = mpv.substring(
            mpv.indexOf("PlaybackResume.mayResumeFromHistory("),
            mpv.indexOf("} else {", mpv.indexOf("PlaybackResume.mayResumeFromHistory("))
        )
        assertTrue(
            "MPV opens the file once, so it has to open it after the read",
            mpvBlock.indexOf("PlaybackResume.savedPositionMs(") <
                mpvBlock.indexOf("loadStream()")
        )
        assertTrue(
            "and the position it found is what the file opens at",
            mpv.contains("startPositionMs = saved")
        )
        // The file is opened through ONE call (the source switch goes through
        // the surface), so a second copy of the load cannot be left behind
        // outside the branch that read the position.
        assertEquals(
            "MPV stopped opening the file through the shared call",
            1,
            Regex("view\\.load\\(").findAll(mpv).count()
        )
    }

    @Test
    fun `the external handoff resolves the position before it leaves`() {
        val external = read(EXTERNAL)
        val start = external.indexOf("PlaybackResume.mayResumeFromHistory(")
        assertTrue("the hand-off does not consult the rule", start >= 0)
        val block = external.substring(
            start,
            external.indexOf(
                "showHandoffCard(prompt, playerName, skipped = null)",
                start
            )
        )
        assertTrue(
            "another app owns the playhead once the stream leaves, so the " +
                "position has to be settled before the hand-off",
            block.indexOf("PlaybackResume.savedPositionMs(") < block.indexOf("handOff(")
        )
        assertTrue(
            "both the launch position and the measured clock have to move to " +
                "the saved one, or the session reports progress it never played",
            external.contains("startPositionMs = saved") &&
                external.contains("positionMs = saved")
        )
    }

    @Test
    fun `an engine handoff only drops the from-beginning flag once something played`() {
        // THE mirror-image hole this rule would otherwise open: a handoff
        // carries the playhead forward, but a launch that never played a frame
        // has none - so a bare position 0 with the flag cleared reads as "no
        // position of its own" and the next engine resumes the very title the
        // viewer asked to start over.
        listOf("ExoPlayer" to NATIVE, "MPV" to MPV).forEach { (name, path) ->
            val source = read(path)
            assertFalse(
                "$name hands the next engine a bare 0 position with the resume " +
                    "flag cleared",
                source.contains("putExtra(\"from_beginning\", false)")
            )
            assertTrue(
                "$name has to keep the flag when there is no playhead to carry",
                source.contains(
                    "putExtra(\"from_beginning\", position <= 0L && startFromBeginning)"
                )
            )
        }
    }

    @Test
    fun `each engine reads its own from-beginning flag`() {
        // The flag is what stops the fallback, so it has to be read where the
        // position is - not inferred from a position that is 0 for two
        // different reasons.
        listOf("MPV" to MPV, "external" to EXTERNAL).forEach { (name, path) ->
            val source = read(path)
            assertTrue(
                "$name does not keep the launch's from-beginning flag",
                source.contains(
                    "startFromBeginning = intent.getBooleanExtra(\"from_beginning\", false)"
                )
            )
        }
    }

    private companion object {
        const val NATIVE = "com/kennyb1201/kbstream/ui/player/NativePlayerActivity.kt"
        const val MPV = "com/kennyb1201/kbstream/ui/player/MpvPlayerActivity.kt"
        const val EXTERNAL = "com/kennyb1201/kbstream/ui/player/ExternalPlayerActivity.kt"
    }
}
