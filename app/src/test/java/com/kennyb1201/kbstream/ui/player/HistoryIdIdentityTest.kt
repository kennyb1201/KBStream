package com.kennyb1201.kbstream.ui.player

import com.kennyb1201.kbstream.data.tmdb.TmdbDetail
import com.kennyb1201.kbstream.data.tmdb.TmdbDetailLookup
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The writer's identity check: the id it files a row under has to be the id OF THE
 * SHOW THAT WAS PLAYED, not merely the id of some show that exists.
 *
 * Why it matters: the phantom Continue Watching card in the field - a show that
 * was never watched, never started, with RESUME S1E1 and the real play's
 * position - came from a candidate id that pointed at a real series. Every earlier
 * guard asked TMDB whether the id named a title, so it passed; nothing compared the
 * candidate with the show the playing file itself names.
 *
 * The rules live in [filingVerdict], which is pure: it takes the evidence the
 * writer gathered (the candidate's verdict, the root's resolved TMDB id, and the
 * membership fallback's answer) and decides. That makes the four verdicts
 * testable without a network or a Context.
 */
class HistoryIdIdentityTest {

    // ── T1: a wrong-but-real series is not the show that played ──────────────

    @Test
    fun `a candidate naming another real show is re-filed under the episode root`() {
        val decision = filingVerdict(
            // A real series id - which is exactly why the existence check kept
            // it and the phantom row was written.
            candidate = "tt0944946",
            episodeRoot = "tt0944947",
            existence = TmdbDetailLookup.Found(TmdbDetail(id = 60625)),
            rootTmdbId = 1399,
            membership = null
        )

        assertEquals(ParentIdVerdict.MISMATCH, decision.verdict)
        assertEquals("tt0944947", decision.filed)
    }

    // ── T2: the same show under two flavors is not a mismatch ────────────────

    @Test
    fun `a candidate and a root that resolve to the same tmdb id are kept`() {
        val decision = filingVerdict(
            // Cross-flavor on purpose: comparing the strings would call this a
            // mismatch and file two rows for one show.
            candidate = "tmdb:1399",
            episodeRoot = "tt0944947",
            existence = TmdbDetailLookup.Found(TmdbDetail(id = 1399)),
            rootTmdbId = 1399,
            membership = null
        )

        assertEquals(ParentIdVerdict.MATCH, decision.verdict)
        assertEquals("tmdb:1399", decision.filed)
    }

    @Test
    fun `a candidate that is already the root cannot be refiled onto itself`() {
        // The commonest session of all: the file id is rooted at the parent id.
        // Nothing to compare and nothing to repair - and the verdict says so,
        // rather than claiming the id carried no root.
        val decision = filingVerdict(
            candidate = "tt0944947",
            episodeRoot = "tt0944947",
            existence = TmdbDetailLookup.Found(TmdbDetail(id = 1399)),
            rootTmdbId = 1399,
            membership = null
        )

        assertEquals(ParentIdVerdict.CANDIDATE_IS_ROOT, decision.verdict)
        assertEquals("tt0944947", decision.filed)
    }

    // ── T3: a film has no episode root to check against ──────────────────────

    @Test
    fun `a film keeps its candidate`() {
        val decision = filingVerdict(
            candidate = "tt0111161",
            episodeRoot = null,
            existence = TmdbDetailLookup.Found(TmdbDetail(id = 278)),
            rootTmdbId = null,
            membership = null
        )

        assertEquals(ParentIdVerdict.NO_ROOT, decision.verdict)
        assertEquals("tt0111161", decision.filed)
    }

    // ── T4: only a definitive mismatch repairs ───────────────────────────────

    @Test
    fun `a root that will not resolve and no membership answer keeps the candidate`() {
        // A timeout, an outage, a catalog root with no TMDB twin: nothing was
        // proven, so the row is filed exactly as before.
        val decision = filingVerdict(
            candidate = "tt0944946",
            episodeRoot = "kitsu:12",
            existence = TmdbDetailLookup.Found(TmdbDetail(id = 60625)),
            rootTmdbId = null,
            membership = null
        )

        assertEquals(ParentIdVerdict.UNVERIFIABLE, decision.verdict)
        assertEquals("tt0944946", decision.filed)
    }

    @Test
    fun `an existence check that could not ask keeps the candidate`() {
        // Transient - and Unresolvable, a shape no lookup accepts - are not
        // verdicts about the id. Membership evidence must not be read on them.
        listOf(TmdbDetailLookup.Transient, TmdbDetailLookup.Unresolvable).forEach { verdict ->
            val decision = filingVerdict(
                candidate = "tt0944946",
                episodeRoot = "kitsu:12",
                existence = verdict,
                rootTmdbId = null,
                membership = false
            )

            assertEquals(verdict.toString(), ParentIdVerdict.UNVERIFIABLE, decision.verdict)
            assertEquals(verdict.toString(), "tt0944946", decision.filed)
        }
    }

    @Test
    fun `a 404 still repairs the way it always did`() {
        val decision = filingVerdict(
            candidate = "tt0000001",
            episodeRoot = "tt0944947",
            existence = TmdbDetailLookup.NotFound,
            rootTmdbId = null,
            membership = null
        )

        assertEquals(ParentIdVerdict.NO_TITLE, decision.verdict)
        assertEquals("tt0944947", decision.filed)
    }

    @Test
    fun `a 404 with no root to repair with keeps the candidate`() {
        val film = filingVerdict(
            candidate = "tt0000001",
            episodeRoot = null,
            existence = TmdbDetailLookup.NotFound,
            rootTmdbId = null,
            membership = null
        )
        val sameId = filingVerdict(
            candidate = "tt0000001",
            episodeRoot = "tt0000001",
            existence = TmdbDetailLookup.NotFound,
            rootTmdbId = null,
            membership = null
        )

        assertEquals(ParentIdVerdict.NO_ROOT, film.verdict)
        assertEquals("tt0000001", film.filed)
        assertEquals(ParentIdVerdict.CANDIDATE_IS_ROOT, sameId.verdict)
        assertEquals("tt0000001", sameId.filed)
    }

    // ── T5: the membership fallback, for a root with no TMDB id ──────────────

    @Test
    fun `a series that lacks the playing episode is a mismatch`() {
        // The candidate is a real series, its root is a catalog id nothing maps,
        // and its own seasons say it has no such episode: the file was played
        // from another show.
        val decision = filingVerdict(
            candidate = "tt0944946",
            episodeRoot = "kitsu:12",
            existence = TmdbDetailLookup.Found(TmdbDetail(id = 60625)),
            rootTmdbId = null,
            membership = false
        )

        assertEquals(ParentIdVerdict.MISMATCH, decision.verdict)
        assertEquals("kitsu:12", decision.filed)
    }

    @Test
    fun `a series that holds the playing episode is kept`() {
        // The same show reached through a catalog root: the candidate is right,
        // and there is nothing to repair.
        val decision = filingVerdict(
            candidate = "tt0944946",
            episodeRoot = "kitsu:12",
            existence = TmdbDetailLookup.Found(TmdbDetail(id = 60625)),
            rootTmdbId = null,
            membership = true
        )

        assertEquals(ParentIdVerdict.MATCH, decision.verdict)
        assertEquals("tt0944946", decision.filed)
    }

    @Test
    fun `a resolved root outranks the membership answer`() {
        // Both TMDB ids are in hand, so the fallback was never asked - and must
        // not be able to overturn the primary comparison either way.
        val wrongShow = filingVerdict(
            candidate = "tt0944946",
            episodeRoot = "tt0944947",
            existence = TmdbDetailLookup.Found(TmdbDetail(id = 60625)),
            rootTmdbId = 1399,
            membership = true
        )
        val rightShow = filingVerdict(
            candidate = "tt0944947",
            episodeRoot = "tmdb:1399",
            existence = TmdbDetailLookup.Found(TmdbDetail(id = 1399)),
            rootTmdbId = 1399,
            membership = false
        )

        assertEquals(ParentIdVerdict.MISMATCH, wrongShow.verdict)
        assertEquals("tt0944947", wrongShow.filed)
        assertEquals(ParentIdVerdict.MATCH, rightShow.verdict)
        assertEquals("tt0944947", rightShow.filed)
    }

    // ── T7: the line that makes a row's provenance visible ───────────────────

    @Test
    fun `the filing line names the candidate, the root, the verdict and the filed id`() {
        val match = filingVerdict(
            candidate = "tmdb:1399",
            episodeRoot = "tt0944947",
            existence = TmdbDetailLookup.Found(TmdbDetail(id = 1399)),
            rootTmdbId = 1399,
            membership = null
        )
        val mismatch = filingVerdict(
            candidate = "tt0944946",
            episodeRoot = "tt0944947",
            existence = TmdbDetailLookup.Found(TmdbDetail(id = 60625)),
            rootTmdbId = 1399,
            membership = null
        )

        val matchLine = parentIdVerdictLine("tmdb:1399", "tt0944947", match)
        val mismatchLine = parentIdVerdictLine("tt0944946", "tt0944947", mismatch)

        assertTrue(matchLine, matchLine.contains("candidate=tmdb:1399"))
        assertTrue(matchLine, matchLine.contains("root=tt0944947"))
        assertTrue(matchLine, matchLine.contains("verdict=match"))
        assertTrue(matchLine, matchLine.contains("filed=tmdb:1399"))

        assertTrue(mismatchLine, mismatchLine.contains("candidate=tt0944946"))
        assertTrue(mismatchLine, mismatchLine.contains("root=tt0944947"))
        assertTrue(mismatchLine, mismatchLine.contains("verdict=mismatch→refiled"))
        assertTrue(mismatchLine, mismatchLine.contains("filed=tt0944947"))
    }

    @Test
    fun `a session with no root says so rather than printing a blank`() {
        val kept = filingVerdict(
            candidate = "tt0111161",
            episodeRoot = null,
            existence = TmdbDetailLookup.Found(TmdbDetail(id = 278)),
            rootTmdbId = null,
            membership = null
        )

        val line = parentIdVerdictLine("tt0111161", null, kept)

        assertTrue(line, line.contains("root=-"))
        assertTrue(line, line.contains("verdict=no-root→kept"))
    }

    // ── T6: one check, every writer ──────────────────────────────────────────

    @Test
    fun `every engine funnels through the one checked writer`() {
        val writer = File(
            findSourceRoot(),
            "com/kennyb1201/kbstream/ui/player/PlaybackHistoryIds.kt"
        ).readText()

        assertTrue(
            "the identity check runs inside the verified writer, next to the existence check",
            writer.contains("val decision = withTimeoutOrNull(VERIFY_TIMEOUT_MS)") &&
                writer.contains("rootTmdbId = if (detail != null && repairableRoot != null)")
        )
        assertTrue(
            "and the root's resolution and the season fetch share the write's one budget",
            writer.contains("private const val VERIFY_TIMEOUT_MS = 2500L")
        )
        assertTrue(
            "the candidate's TMDB id comes from the existence answer - a second lookup " +
                "would be a second chance for the two sides to disagree",
            writer.contains(
                "val candidateTmdbId = (existence as? TmdbDetailLookup.Found)?.detail?.id"
            )
        )

        listOf(
            "com/kennyb1201/kbstream/ui/player/NativePlayerActivity.kt",
            "com/kennyb1201/kbstream/ui/player/MpvPlayerActivity.kt",
            "com/kennyb1201/kbstream/ui/player/ExternalPlayerActivity.kt"
        ).forEach { path ->
            val player = File(findSourceRoot(), path).readText()
                .slice("PlaybackHistoryIds.canonicalParentId(", "\n        )")
            assertTrue(
                "$path must hand the writer the playing episode",
                player.contains("episodeStreamId = episodeStreamId")
            )
            assertTrue(
                "$path must hand the writer the session's own file/episode mapping, so a " +
                    "split show's file asks about the episode it holds",
                player.contains("scheme = bingeScheme")
            )
        }
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

    private fun String.slice(startMarker: String, endMarker: String): String {
        val start = indexOf(startMarker)
        assertTrue("$startMarker is missing", start >= 0)
        val end = indexOf(endMarker, start)
        assertTrue("$endMarker must follow $startMarker", end > start)
        return substring(start, end)
    }
}
