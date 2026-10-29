package com.kennyb1201.kbstream.domain.streamengine

import com.kennyb1201.kbstream.domain.streamengine.RowMediaType.Kind
import com.kennyb1201.kbstream.data.addon.Stream
import com.kennyb1201.kbstream.data.addon.StreamBehaviorHints
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The picker's wrong-type guard, pinned on the rows that produced the report.
 *
 * Field case (Onn 4K Plus, build 0.9.100, South Park S29E4): a stored `fe4`
 * scheme made the picker ask for S29E13, and Flix-Streams answered with five
 * copies of the 1999 film at 17.4–20.6 GB. Autoplay played one. Every row here
 * that the guard refuses is a row from that capture, and every row it KEEPS is
 * the everyday shape it must not touch — the unlabeled release, the pack, the
 * honest but huge episode. The guard's whole risk is false positives, so the
 * kept cases are the assertions that matter most.
 */
class RowMediaTypeTest {

    private val episodeId = "tt0121955:29:13"
    private val movieId = "tt0121955"

    private fun stream(
        title: String? = null,
        name: String? = null,
        description: String? = null,
        url: String? = null,
        filename: String? = null
    ) = Stream(
        name = name,
        title = title,
        description = description,
        url = url,
        behaviorHints = filename?.let { StreamBehaviorHints(filename = it) }
    )

    private fun kind(
        stream: Stream,
        contentType: String = "series",
        streamId: String = episodeId,
        runtimeMinutes: Int? = 22
    ) = RowMediaType.classify(stream, contentType, streamId, runtimeMinutes)

    // --- the field rows ----------------------------------------------------

    @Test
    fun `the 1999 film on an episode query is movie-typed`() {
        val film = stream(
            title = "South.Park.Bigger.Longer.and.Uncut.1999.2160p.BluRay.TrueHD.7.1 17.4 GB"
        )
        assertEquals(Kind.MOVIE, kind(film))
    }

    @Test
    fun `the same film at 41 Mbps of a 22-minute episode is NOT provable - kept`() {
        // 6.79 GB over 22 minutes is 41 Mbps: high, but inside what a long
        // episode can carry, so the guard must stay silent. This is the honest
        // limit of a size-based rule, pinned rather than glossed over.
        val film = stream(
            title = "South.Park.Bigger.Longer.and.Uncut.1999.2160p.BluRay.TrueHD.7.1 6.79 GB"
        )
        assertEquals(Kind.UNKNOWN, kind(film))
    }

    @Test
    fun `a row that declares the episode is an episode whatever its size`() {
        val declared = stream(
            title = "South.Park.S29E04.2160p.BluRay.TrueHD.7.1 20.0 GB"
        )
        assertEquals(Kind.EPISODE, kind(declared))
    }

    @Test
    fun `a season pack is not a film - the pack signal saves it`() {
        val pack = stream(title = "South.Park.S29.COMPLETE.1080p.WEB-DL 20.0 GB")
        assertEquals(Kind.UNKNOWN, kind(pack))
    }

    @Test
    fun `a row with no size and no declaration cannot be proved either`() {
        assertEquals(
            Kind.UNKNOWN,
            kind(stream(title = "Some Release Name"), runtimeMinutes = null)
        )
        // A size on its own proves nothing without a length to read it against,
        // and a modest size under the ceiling proves nothing either.
        assertEquals(Kind.UNKNOWN, kind(stream(title = "South Park 2160p 17.4 GB"), runtimeMinutes = null))
        assertEquals(Kind.UNKNOWN, kind(stream(title = "South Park 1080p WEB-DL 700 MB")))
    }

    @Test
    fun `declaring ANOTHER episode is an episode row, not a movie row`() {
        // The type guard must not conflate itself with the episode rules: this
        // row IS an episode file (of the wrong episode), which is EpisodeMatch's
        // call and the ranker's, and the guard keeps it.
        val otherEpisode = stream(title = "South.Park.S29E13.1080p.WEB-DL 1.2 GB")
        assertEquals(Kind.EPISODE, kind(otherEpisode))
        assertTrue(
            "the guard keeps a declared-episode row on an episode query",
            RowMediaType.guard(listOf(otherEpisode), "series", episodeId, 22).kept.size == 1
        )
    }

    // --- the mirror rule: a film query -------------------------------------

    @Test
    fun `a row naming an episode is dropped on a FILM query`() {
        val episodeRow = stream(title = "South.Park.S01E01.1080p 900 MB")
        assertEquals(Kind.EPISODE, kind(episodeRow, contentType = "movie", streamId = movieId))

        val guarded = RowMediaType.guard(listOf(episodeRow), "movie", movieId, 81)
        assertTrue(guarded.kept.isEmpty())
        assertEquals(1, guarded.droppedEpisode)
    }

    @Test
    fun `a film query keeps ordinary film rows whatever their size`() {
        val film = stream(
            title = "South.Park.Bigger.Longer.and.Uncut.1999.2160p.BluRay 17.4 GB"
        )
        assertEquals(Kind.UNKNOWN, kind(film, contentType = "movie", streamId = movieId, runtimeMinutes = 81))
    }

    @Test
    fun `a bare episode number cannot prove anything on a film query`() {
        val bare = stream(title = "South Park - E04 [1080p]")
        assertEquals(Kind.UNKNOWN, kind(bare, contentType = "movie", streamId = movieId, runtimeMinutes = 81))
    }

    // --- the guard's arithmetic --------------------------------------------

    @Test
    fun `the guard counts both shapes and keeps everything else`() {
        val rows = listOf(
            stream(title = "South.Park.Bigger.Longer.and.Uncut.1999.2160p 17.4 GB"),
            stream(title = "South.Park.S29E04.1080p 1.1 GB"),
            stream(title = "Some Unlabeled Release 700 MB"),
            stream(title = "South.Park.S29.COMPLETE 20 GB")
        )
        val guarded = RowMediaType.guard(rows, "series", episodeId, 22)

        assertEquals(listOf(rows[1], rows[2], rows[3]), guarded.kept)
        assertEquals(1, guarded.droppedMovie)
        assertEquals(0, guarded.droppedEpisode)
        assertEquals(1, guarded.dropped)
    }

    @Test
    fun `a request that is neither an episode nor a film query keeps everything`() {
        val film = stream(title = "South.Park.Bigger.Longer.and.Uncut.1999.2160p 17.4 GB")
        val guarded = RowMediaType.guard(listOf(film), "channel", "https://example.com/live.m3u8", null)
        assertEquals(1, guarded.kept.size)
        assertEquals(0, guarded.dropped)
    }

    @Test
    fun `an unknown runtime proves nothing, however large the file`() {
        val film = stream(title = "South.Park.Bigger.Longer.and.Uncut.1999.2160p 17.4 GB")
        assertEquals(Kind.UNKNOWN, kind(film, runtimeMinutes = null))
    }

    // --- the scheme commit guard -------------------------------------------

    @Test
    fun `a movie-typed row may not be committed as the show's scheme`() {
        val film = stream(title = "South.Park.Bigger.Longer.and.Uncut.1999.2160p 17.4 GB")
        val refusal = RowMediaType.schemeCommitRefusal(
            stream = film,
            contentType = "series",
            streamId = episodeId,
            runtimeMinutes = 22,
            sessionEpisode = 4,
            fileName = film.title
        )
        assertEquals("playing row is movie-typed", refusal)
    }

    @Test
    fun `a bundle that starts at another episode may not be committed`() {
        val refusal = RowMediaType.schemeCommitRefusal(
            stream = null,
            contentType = "series",
            streamId = "tt0121955:1:1",
            runtimeMinutes = 22,
            sessionEpisode = 1,
            fileName = "Paw.Patrol.S01E05E06.1080p"
        )
        assertTrue("names the mismatch: $refusal", refusal.orEmpty().contains("E5"))
    }

    @Test
    fun `a bundle that starts at this episode may be committed`() {
        assertNull(
            RowMediaType.schemeCommitRefusal(
                stream = null,
                contentType = "series",
                streamId = "tt0121955:1:1",
                runtimeMinutes = 22,
                sessionEpisode = 1,
                fileName = "Paw.Patrol.S01E01E02.1080p"
            )
        )
    }

    @Test
    fun `a session on the bundle's SECOND episode may still be committed`() {
        // Paw Patrol's segment 2 and a split episode's second half are sessions
        // on the right file with a bundle that does not START at their episode.
        // The guard asks whether the file holds this episode, not whether it
        // starts there: getting this wrong would make a genuinely segmented
        // show unable to learn its scheme from the file the viewer opened.
        assertNull(
            RowMediaType.schemeCommitRefusal(
                stream = null,
                contentType = "series",
                streamId = "tt1487921:1:2",
                runtimeMinutes = 11,
                sessionEpisode = 2,
                fileName = "Paw.Patrol.S01E01E02.1080p"
            )
        )
    }

    @Test
    fun `an ordinary episode row with no bundle is free to commit`() {
        val row = stream(title = "Paw.Patrol.S01E01.1080p 700 MB")
        assertNull(
            RowMediaType.schemeCommitRefusal(
                stream = row,
                contentType = "series",
                streamId = "tt0121955:1:1",
                runtimeMinutes = 11,
                sessionEpisode = 1,
                fileName = row.title
            )
        )
    }
}
