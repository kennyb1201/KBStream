package com.kennyb1201.kbstream.ui.player

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The episode mapping has to be walked the SAME way in every place that names an
 * episode — and it is a mapping, not a behavior, so every one of those places
 * compiles cleanly while disagreeing with the others.
 *
 * That is exactly the failure this feature exists to fix: one file is not one
 * TMDB episode on a show like Paw Patrol (47 segments on TMDB, 24 files holding
 * two each), and the app's arithmetic drifted further off with every file of a
 * binge while every individual step looked right. [EpisodeScheme] is the pure
 * half and [EpisodeSchemeTest] covers it; what is left is WIRING, and wiring is
 * where a missing call is invisible:
 *
 *  - a player that forgets to read the stored scheme starts a binge at the wrong
 *    file, silently;
 *  - a player that forgets to DETECT never corrects it, and the fired report is
 *    the only way anyone finds out;
 *  - `nextEpisodeTarget()` that adds one keeps the labels moving while the file
 *    cursor stands still (or the reverse) — the off-by-one-nothing symptom;
 *  - a handoff whose stream id is built from the TMDB number re-opens the file
 *    the viewer is already inside, which reads as "autoplay repeats the
 *    episode";
 *  - an id builder that forgets the mapping writes a row under a file id no card
 *    matches, so the episode plays and nothing is ever ticked.
 *
 * Read from the source, the way this repo pins player wiring (see
 * PlayerBackgroundReturnContractTest and HandoffFilingContractTest): the
 * activities cannot be launched in a JVM test — they build ExoPlayer/libmpv and
 * take a video surface.
 */
class EpisodeSchemeWiringContractTest {

    private val sourceRoot: File by lazy { findSourceRoot() }

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
        val file = File(sourceRoot, relative)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

    /**
     * The text of the member starting at [signature] up to the next member: a
     * line indented by exactly four spaces, which is the level every member of
     * these classes sits at (a deeper line has another space where the pattern
     * wants a non-space, so it cannot match).
     */
    private fun functionBody(src: String, signature: String): String {
        val start = src.indexOf(signature)
        assertTrue("source missing member: $signature", start >= 0)
        val rest = src.substring(start + signature.length)
        val end = Regex("\\n {4}\\S").find(rest)?.range?.first ?: rest.length
        return rest.substring(0, end)
    }

    @Test
    fun `both in-app engines read the stored scheme and detect one from the file that played`() {
        listOf(NATIVE, MPV).forEach { path ->
            val src = read(path)
            assertTrue(
                "$path must read the show's stored scheme at session start, or a binge " +
                    "on a show it has already seen opens the wrong file",
                src.contains("EpisodeSchemeStore.get(")
            )
            assertTrue(
                "$path must read the played file against the episode's runtime",
                src.contains("EpisodeScheme.detect(")
            )
            assertTrue(
                "$path must remember what it detected, so the next session is exact",
                src.contains("EpisodeSchemeStore.put(")
            )
            assertTrue(
                "$path must keep the detection to one per file",
                src.contains("schemeDetectedForFileId")
            )
        }
    }

    @Test
    fun `detection runs on the progress tick and again before the handoff decides`() {
        val native = read(NATIVE)
        val tick = functionBody(native, "private val positionRunnable = object : Runnable {")
        assertTrue(
            "NativePlayerActivity must detect from the position tick - the read needs a " +
                "duration, and the tick is where the session gets one",
            tick.contains("maybeDetectScheme()")
        )
        val mpvTick = functionBody(read(MPV), "private fun onProgress(")
        assertTrue(
            "MpvPlayerActivity must detect from mpv's own progress callback",
            mpvTick.contains("maybeDetectScheme()")
        )

        listOf(NATIVE, MPV).forEach { path ->
            val handoff = functionBody(read(path), "private fun fileEpisodeForHandoff() {")
            assertTrue(
                "$path must detect BEFORE the handoff decides what to chain into: a file " +
                    "watched without the tick ever seeing a duration still has to be read",
                handoff.contains("maybeDetectScheme()")
            )
        }
    }

    @Test
    fun `the next episode is the scheme's arithmetic and not a plus one`() {
        listOf(NATIVE, MPV, EXTERNAL).forEach { path ->
            val body = functionBody(read(path), "private fun nextEpisodeTarget(): Pair<Int, Int>? {")
            // The label of the NEXT FILE, derived from the scheme's own file-
            // cursor mapping. It used to be `advance(fileE, sessionLabel)`, which
            // is the same arithmetic only when the session entered at a file's
            // FIRST segment: an E4 tap on an sp2 file holding [3,4] advanced to
            // E6 and drifted the rest of the binge. labelForFile answers from
            // the cursor instead (see EpisodeSchemeFileCursorTest).
            assertTrue(
                "$path must derive the next label from the scheme's file-cursor " +
                    "mapping, or a session entered at a non-first segment leaves the " +
                    "TMDB label wrong for the rest of the binge",
                body.contains("bingeScheme.labelForFile(")
            )
            assertTrue(
                "$path must read the FILE cursor that mapping is anchored on",
                body.contains("currentFileEpisode()")
            )
            assertFalse(
                "$path still walks the SESSION label through the scheme, which drifts " +
                    "whenever the session did not start at a file's first episode",
                body.contains("bingeScheme.advance(")
            )
        }
    }

    @Test
    fun `the handoff's stream id is built from FILE numbering`() {
        listOf(NATIVE, MPV, EXTERNAL).forEach { path ->
            assertTrue(
                "$path must convert the TMDB target into the file the addons resolve " +
                    "before building the id, or autoplay re-opens the same file",
                read(path).contains(
                    "nextStreamId(targetSeason, nextFileEpisodeFor(targetSeason, targetEpisode))"
                )
            )
        }
    }

    @Test
    fun `the file cursor is read from the stream id, which is file numbering`() {
        listOf(NATIVE, MPV, EXTERNAL).forEach { path ->
            val body = functionBody(read(path), "private fun currentFileEpisode(): Int? {")
            assertTrue(
                "$path must read the played file from the session's own stream id (the id " +
                    "invariant: it is always FILE numbering) rather than a second counter",
                body.contains("episodeStreamId") && body.contains("substringAfterLast(':')")
            )
        }
    }

    @Test
    fun `a finished file marks every episode it held`() {
        listOf(NATIVE, MPV, EXTERNAL).forEach { path ->
            val src = read(path)
            assertTrue(
                "$path must push each covered episode, or the second segment of a doubled " +
                    "file stays unwatched on the tracker",
                src.contains("coveredTmdbEpisodes(")
            )
            val body = functionBody(src, "private fun coveredTmdbEpisodes(")
            assertTrue(
                "$path must clamp the covered range to the season, so the odd tail of a " +
                    "47-segment season cannot be pushed as an episode that does not exist",
                body.contains("totalEpisodesInSeason")
            )
        }
    }

    @Test
    fun `every builder that names an episode from its TMDB number maps it to the file`() {
        val builders = mapOf(
            // The season listing every episode card and every Up Next row reads.
            "com/kennyb1201/kbstream/data/tmdb/TmdbRepository.kt" to 1,
            // The synthetic (add-on videos) listing, for titles with no TMDB match.
            "com/kennyb1201/kbstream/ui/detail/DetailViewModel.kt" to 1,
            // The tracker-derived RESUME rows: both the Simkl and the MDBList one.
            "com/kennyb1201/kbstream/ui/detail/DetailPlaybackResume.kt" to 2,
            // Detail's last-resort target, when nothing resolved an episode row.
            "com/kennyb1201/kbstream/ui/detail/DetailScreen.kt" to 1,
            // A random pick's own id, for a TMDB row that carried none.
            "com/kennyb1201/kbstream/ui/player/RandomEpisode.kt" to 1
        )
        builders.forEach { (path, expected) ->
            val calls = Regex("fileEpisodeStreamId\\(").findAll(read(path)).count()
            assertTrue(
                "$path must name the FILE that holds the episode (expected at least " +
                    "$expected mapped builder(s), found $calls) - an unmapped id is filed " +
                    "and resolved under a number no card on this screen matches",
                calls >= expected
            )
        }
    }

    @Test
    fun `both engines ask a subtitles add-on about the file that is playing`() {
        val addon = read(ADDON_SUBTITLES)
        assertTrue(
            "AddonSubtitles must build its video id through the shared mapping, or a " +
                "segmented show is served the subtitles of a file it is not playing",
            addon.contains("addonSubtitleVideoId(")
        )
        assertTrue(
            "the session's own episode id must be what the add-on is asked about",
            addon.contains("sessionStreamId")
        )
        assertTrue(
            "and with no session id it must still map through the stored scheme",
            addon.contains("fileEpisodeStreamId(")
        )
        assertFalse(
            "the hand-built TMDB-numbered video id is what the add-on used to be asked " +
                "about; it names a file that does not hold the episode",
            addon.contains("\"\$parentId:\$season:\$episode\"")
        )

        assertTrue(
            "NativePlayerActivity must hand the session's own episode id to the subtitle " +
                "controller, or the add-on is asked about the TMDB number's file",
            read(NATIVE)
                .substringAfter("addonSubtitleController.bind(")
                .take(400)
                .contains("episodeStreamId = episodeStreamId")
        )
        assertTrue(
            "MpvPlayerActivity must build its own subtitle lookup with that same " +
                "function, so the two engines cannot ask about different videos",
            read(MPV).contains("addonSubtitleVideoId(")
        )
    }

    @Test
    fun `the external engine applies the stored scheme without detecting one`() {
        val src = read(EXTERNAL)
        assertTrue(
            "ExternalPlayerActivity must still step the file cursor with the stored scheme",
            src.contains("bingeScheme.advance(")
        )
        assertFalse(
            "ExternalPlayerActivity has no duration of its own - the stream plays in " +
                "another app - so it must not invent a detection from the runtime it knows",
            src.contains("EpisodeScheme.detect(")
        )
    }

    private companion object {
        const val NATIVE = "com/kennyb1201/kbstream/ui/player/NativePlayerActivity.kt"
        const val MPV = "com/kennyb1201/kbstream/ui/player/MpvPlayerActivity.kt"
        const val EXTERNAL = "com/kennyb1201/kbstream/ui/player/ExternalPlayerActivity.kt"
        const val ADDON_SUBTITLES = "com/kennyb1201/kbstream/ui/player/AddonSubtitles.kt"
    }
}
