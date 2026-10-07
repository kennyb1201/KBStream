package com.kennyb1201.kbstream.data.player

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Files are not episodes.
 *
 * Paw Patrol season 1 is 47 eleven-minute segments on TMDB and 26 twenty-two
 * minute files on the addons; CatDog is 20 twenty-two minute TMDB episodes and
 * 11 eleven-minute files. The app advanced its TMDB counter by one per file, so
 * a binge labelled, marked and chained one episode per file - which drifted
 * further off with every file played, and left every second segment unmarked.
 *
 * This pins the mapping: what a file's duration against the TMDB runtime is
 * read as, which file holds a given TMDB episode, and what the cursor does
 * after a file finishes. The 1:1 answer is the important one: everything
 * unknown, unmeasured or merely similar must stay byte-for-byte the behavior
 * the app had before, because a scheme invented out of a 22-minute file against
 * a 24-minute entry would skip an episode of an ordinary show.
 */
@RunWith(AndroidJUnit4::class)
class EpisodeSchemeTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun minutes(value: Int) = value * 60_000L

    // --- detection ---------------------------------------------------------

    @Test
    fun `a file twice the TMDB runtime holds two episodes`() {
        assertEquals(
            EpisodeScheme(SchemeKind.SEGMENTS_PER_FILE, 2),
            EpisodeScheme.detect(fileDurationMs = minutes(22), tmdbRuntimeMs = minutes(11))
        )
    }

    @Test
    fun `a file half the TMDB runtime is half an episode`() {
        assertEquals(
            EpisodeScheme(SchemeKind.FILES_PER_EPISODE, 2),
            EpisodeScheme.detect(fileDurationMs = minutes(11), tmdbRuntimeMs = minutes(22))
        )
    }

    @Test
    fun `a normal file against a slightly different runtime stays one to one`() {
        // The dead zone. 22 against 24 is 0.92: an ordinary title whose file and
        // TMDB entry merely disagree by a couple of minutes.
        assertEquals(
            EpisodeScheme.ONE_TO_ONE,
            EpisodeScheme.detect(fileDurationMs = minutes(22), tmdbRuntimeMs = minutes(24))
        )
        assertEquals(
            EpisodeScheme.ONE_TO_ONE,
            EpisodeScheme.detect(fileDurationMs = minutes(24), tmdbRuntimeMs = minutes(22))
        )
    }

    @Test
    fun `nothing measurable answers one to one`() {
        assertEquals(
            EpisodeScheme.ONE_TO_ONE,
            EpisodeScheme.detect(fileDurationMs = 0L, tmdbRuntimeMs = minutes(11))
        )
        assertEquals(
            EpisodeScheme.ONE_TO_ONE,
            EpisodeScheme.detect(fileDurationMs = minutes(22), tmdbRuntimeMs = 0L)
        )
        assertEquals(
            EpisodeScheme.ONE_TO_ONE,
            EpisodeScheme.detect(fileDurationMs = -1L, tmdbRuntimeMs = minutes(11))
        )
        assertEquals(
            "a live stream reporting TIME_UNSET through a negative must not read as a scheme",
            EpisodeScheme.ONE_TO_ONE,
            EpisodeScheme.detect(fileDurationMs = minutes(11), tmdbRuntimeMs = -1L)
        )
    }

    @Test
    fun `a file three times the runtime holds three`() {
        assertEquals(
            EpisodeScheme(SchemeKind.SEGMENTS_PER_FILE, 3),
            EpisodeScheme.detect(fileDurationMs = minutes(66), tmdbRuntimeMs = minutes(22))
        )
    }

    @Test
    fun `an absurd ratio is capped, not followed`() {
        // A season dump, a badly labelled row, a two-hour "11 minute" segment:
        // following the number would be worse than doing nothing.
        assertEquals(
            EpisodeScheme(SchemeKind.SEGMENTS_PER_FILE, 4),
            EpisodeScheme.detect(fileDurationMs = minutes(200), tmdbRuntimeMs = minutes(11))
        )
        assertEquals(
            EpisodeScheme(SchemeKind.FILES_PER_EPISODE, 4),
            EpisodeScheme.detect(fileDurationMs = minutes(5), tmdbRuntimeMs = minutes(60))
        )
    }

    // --- TMDB episode -> file ---------------------------------------------

    @Test
    fun `segments per file maps a TMDB episode to the file that holds it`() {
        val pawPatrol = EpisodeScheme(SchemeKind.SEGMENTS_PER_FILE, 2)
        assertEquals(1, pawPatrol.fileForTmdbEpisode(1))
        assertEquals(1, pawPatrol.fileForTmdbEpisode(2))
        assertEquals(2, pawPatrol.fileForTmdbEpisode(3))
        // 47 segments is file 24, the odd tail included.
        assertEquals(24, pawPatrol.fileForTmdbEpisode(47))
    }

    @Test
    fun `files per episode maps a TMDB episode to its first file`() {
        val catDog = EpisodeScheme(SchemeKind.FILES_PER_EPISODE, 2)
        assertEquals(1, catDog.fileForTmdbEpisode(1))
        assertEquals(3, catDog.fileForTmdbEpisode(2))
        assertEquals(5, catDog.fileForTmdbEpisode(3))
    }

    @Test
    fun `one to one is the identity`() {
        val plain = EpisodeScheme.ONE_TO_ONE
        (1..20).forEach { episode ->
            assertEquals(episode, plain.fileForTmdbEpisode(episode))
        }
    }

    // --- file -> the episodes it holds ------------------------------------

    @Test
    fun `a segmented file holds its pair, and the odd tail holds one`() {
        val pawPatrol = EpisodeScheme(SchemeKind.SEGMENTS_PER_FILE, 2)
        assertEquals(listOf(1, 2), pawPatrol.tmdbEpisodesOfFile(1))
        assertEquals(listOf(11, 12), pawPatrol.tmdbEpisodesOfFile(6))
        // The last file is a full pair on paper and a single segment in fact:
        // the season's own episode count is what clamps it (see
        // coveredTmdbEpisodes at the call sites), so the set may name one
        // episode past the end.
        assertEquals(listOf(47, 48), pawPatrol.tmdbEpisodesOfFile(24))
        assertEquals(false, pawPatrol.fileHolds(24, 8))
        // The property that makes this usable as a check: every TMDB episode is
        // held by the file it maps to, which is the inverse of fileForTmdbEpisode.
        (1..47).forEach { episode ->
            val file = pawPatrol.fileForTmdbEpisode(episode)
            assertEquals(
                "episode $episode must be held by the file it maps to",
                true,
                pawPatrol.fileHolds(file, episode)
            )
        }
    }

    @Test
    fun `a split episode is held by its whole group of files`() {
        val catDog = EpisodeScheme(SchemeKind.FILES_PER_EPISODE, 2)
        assertEquals(listOf(1), catDog.tmdbEpisodesOfFile(1))
        assertEquals(listOf(1), catDog.tmdbEpisodesOfFile(2))
        assertEquals(listOf(2), catDog.tmdbEpisodesOfFile(3))
        assertEquals(listOf(2), catDog.tmdbEpisodesOfFile(4))
        // Both halves of episode 2 are mapped correctly, which is the case a
        // strict `file == episode` reading would report as a mismatch.
        assertEquals(true, catDog.fileHolds(3, 2))
        assertEquals(true, catDog.fileHolds(4, 2))
        assertEquals(false, catDog.fileHolds(4, 1))
    }

    @Test
    fun `one to one holds only its own episode`() {
        assertEquals(true, EpisodeScheme.ONE_TO_ONE.fileHolds(8, 8))
        assertEquals(false, EpisodeScheme.ONE_TO_ONE.fileHolds(6, 8))
        assertEquals(false, EpisodeScheme.ONE_TO_ONE.fileHolds(8, 6))
    }

    @Test
    fun `nothing below the first episode or file holds anything`() {
        // A degenerate id (`:0`, a movie, a negative fragment) must not be
        // matched by a zero that happens to sit in the set.
        val schemes = listOf(
            EpisodeScheme.ONE_TO_ONE,
            EpisodeScheme(SchemeKind.SEGMENTS_PER_FILE, 2),
            EpisodeScheme(SchemeKind.FILES_PER_EPISODE, 2)
        )
        schemes.forEach { scheme ->
            assertEquals(false, scheme.fileHolds(0, 1))
            assertEquals(false, scheme.fileHolds(1, 0))
            assertEquals(false, scheme.fileHolds(-1, 1))
            assertEquals(false, scheme.fileHolds(1, -1))
        }
    }

    // --- the cursor --------------------------------------------------------

    @Test
    fun `a segmented binge advances the TMDB number with the file`() {
        val pawPatrol = EpisodeScheme(SchemeKind.SEGMENTS_PER_FILE, 2)
        assertEquals(2 to 3, pawPatrol.advance(fileEp = 1, tmdbEp = 1))
        assertEquals(3 to 5, pawPatrol.advance(fileEp = 2, tmdbEp = 3))
        assertEquals(4 to 7, pawPatrol.advance(fileEp = 3, tmdbEp = 5))
    }

    @Test
    fun `a files-per-episode binge holds the TMDB number for its group`() {
        val catDog = EpisodeScheme(SchemeKind.FILES_PER_EPISODE, 2)
        assertEquals(2 to 1, catDog.advance(fileEp = 1, tmdbEp = 1))
        assertEquals(3 to 2, catDog.advance(fileEp = 2, tmdbEp = 1))
        assertEquals(4 to 2, catDog.advance(fileEp = 3, tmdbEp = 2))
        assertEquals(5 to 3, catDog.advance(fileEp = 4, tmdbEp = 2))
    }

    @Test
    fun `one to one advances both together`() {
        assertEquals(2 to 2, EpisodeScheme.ONE_TO_ONE.advance(fileEp = 1, tmdbEp = 1))
    }

    @Test
    fun `a session with no episode number advances by one whatever the scheme`() {
        // Degenerate, and it must stay the arithmetic the app always did: no
        // episode number means there is nothing to map.
        val pawPatrol = EpisodeScheme(SchemeKind.SEGMENTS_PER_FILE, 2)
        assertEquals(1 to 1, pawPatrol.advance(fileEp = 0, tmdbEp = 0))
        assertEquals(2 to 1, pawPatrol.advance(fileEp = 1, tmdbEp = 0))
    }

    // --- the two shows the feature was reported for ------------------------

    @Test
    fun `Paw Patrol's 24 files walk TMDB 1 to 47 without skipping or repeating`() {
        val pawPatrol = EpisodeScheme(SchemeKind.SEGMENTS_PER_FILE, 2)
        val covered = (1..47).map { pawPatrol.fileForTmdbEpisode(it) }
        assertEquals("every TMDB episode has a file", 47, covered.size)
        assertEquals("the files are 1..24 - the 25th and 26th of 26 hold nothing new", 24, covered.max())
        assertEquals(1, covered.min())
        // Every file holds a pair of segments except the odd tail: 47 segments
        // make 23 full files and a last one holding the single 47th.
        val perFile = covered.groupingBy { it }.eachCount()
        assertEquals("files 1..23 hold two segments each", listOf(2), perFile.filterKeys { it < 24 }.values.distinct())
        assertEquals("and the 24th holds the odd one", 1, perFile[24])
        assertEquals("which is the file the last segment lives in", 24, covered.last())
    }

    @Test
    fun `CatDog's first four files cover TMDB 1 to 3 by halves`() {
        val catDog = EpisodeScheme(SchemeKind.FILES_PER_EPISODE, 2)
        var file = 1
        var tmdb = 1
        val visited = mutableListOf(tmdb)
        repeat(4) {
            val (nextFile, nextTmdb) = catDog.advance(file, tmdb)
            file = nextFile
            tmdb = nextTmdb
            visited += tmdb
        }
        assertEquals("files 1..5 visit TMDB 1,1,2,2,3", listOf(1, 1, 2, 2, 3), visited)
    }

    // --- the store ---------------------------------------------------------

    @Test
    fun `a stored scheme round-trips and survives a restart`() {
        val show = "tt1234567"
        clearStore()
        assertEquals(
            "nothing stored is one to one",
            EpisodeScheme.ONE_TO_ONE,
            EpisodeSchemeStore.get(context, show)
        )

        EpisodeSchemeStore.put(context, show, EpisodeScheme(SchemeKind.SEGMENTS_PER_FILE, 2))
        assertEquals(
            EpisodeScheme(SchemeKind.SEGMENTS_PER_FILE, 2),
            EpisodeSchemeStore.get(context, show)
        )

        // The value is a fresh read of prefs, so it is what a new session sees.
        EpisodeSchemeStore.put(context, show, EpisodeScheme(SchemeKind.FILES_PER_EPISODE, 3))
        assertEquals(
            "the last detection wins",
            EpisodeScheme(SchemeKind.FILES_PER_EPISODE, 3),
            EpisodeSchemeStore.get(context, show)
        )
    }

    @Test
    fun `one to one clears the entry instead of being stored`() {
        val show = "tt7654321"
        clearStore()
        EpisodeSchemeStore.put(context, show, EpisodeScheme(SchemeKind.SEGMENTS_PER_FILE, 2))
        EpisodeSchemeStore.put(context, show, EpisodeScheme.ONE_TO_ONE)
        assertEquals(EpisodeScheme.ONE_TO_ONE, EpisodeSchemeStore.get(context, show))
        assertNull(
            "and nothing is left in prefs for a show that is plain 1:1",
            prefs().getString("episode_scheme_$show", null)
        )
    }

    @Test
    fun `a blank id is never filed, and never reads another show's scheme`() {
        clearStore()
        EpisodeSchemeStore.put(context, "tt1111111", EpisodeScheme(SchemeKind.SEGMENTS_PER_FILE, 2))
        EpisodeSchemeStore.put(context, null, EpisodeScheme(SchemeKind.FILES_PER_EPISODE, 2))
        EpisodeSchemeStore.put(context, "  ", EpisodeScheme(SchemeKind.FILES_PER_EPISODE, 2))
        assertEquals(EpisodeScheme.ONE_TO_ONE, EpisodeSchemeStore.get(context, null))
        assertEquals(EpisodeScheme.ONE_TO_ONE, EpisodeSchemeStore.get(context, "  "))
        assertNull("no entry was written under an empty key", prefs().getString("episode_scheme_", null))
    }

    @Test
    fun `the stable id prefers the imdb flavor and falls back to tmdb`() {
        assertEquals("tt1234567", EpisodeSchemeStore.stableShowId("tt1234567", 42))
        assertEquals("tmdb:42", EpisodeSchemeStore.stableShowId("tmdb:42", 42))
        assertEquals("tmdb:42", EpisodeSchemeStore.stableShowId(null, 42))
        assertEquals("tmdb:42", EpisodeSchemeStore.stableShowId("  ", 42))
        assertNull(EpisodeSchemeStore.stableShowId(null, null))
        assertNull(EpisodeSchemeStore.stableShowId("", 0))
    }

    @Test
    fun `a stored scheme turns a TMDB episode into the file id the addons resolve`() {
        clearStore()
        val show = "tt9999999"
        assertEquals(
            "with no scheme the id is the plain TMDB one",
            "$show:1:3",
            fileEpisodeStreamId(context, show, EpisodeSchemeStore.stableShowId(show, 9), 1, 3)
        )

        EpisodeSchemeStore.put(context, show, EpisodeScheme(SchemeKind.SEGMENTS_PER_FILE, 2))
        assertEquals(
            "and with one it is the file that holds that episode",
            "$show:1:2",
            fileEpisodeStreamId(context, show, EpisodeSchemeStore.stableShowId(show, 9), 1, 3)
        )
    }

    @Test
    fun `the encoded value is the documented one`() {
        assertEquals("sp2", EpisodeScheme(SchemeKind.SEGMENTS_PER_FILE, 2).encode())
        assertEquals("fe3", EpisodeScheme(SchemeKind.FILES_PER_EPISODE, 3).encode())
        assertNull(EpisodeScheme.ONE_TO_ONE.encode())
        assertEquals(EpisodeScheme(SchemeKind.SEGMENTS_PER_FILE, 2), EpisodeScheme.decode("sp2"))
        assertEquals(EpisodeScheme(SchemeKind.FILES_PER_EPISODE, 3), EpisodeScheme.decode("fe3"))
        // Unreadable values are 1:1, never a guess.
        listOf(null, "", "sp", "xx2", "sp0", "sp1", "nonsense").forEach { raw ->
            assertEquals("'$raw' must not invent a scheme", EpisodeScheme.ONE_TO_ONE, EpisodeScheme.decode(raw))
        }
        // ...and a factor past the detector's own cap is clamped rather than
        // followed, so a hand-edited value cannot skip a dozen episodes.
        assertEquals(EpisodeScheme(SchemeKind.SEGMENTS_PER_FILE, 4), EpisodeScheme.decode("sp9"))
    }

    private fun prefs() = context.getSharedPreferences("kbstream_episode_scheme", Context.MODE_PRIVATE)

    private fun clearStore() = prefs().edit().clear().commit()
}
