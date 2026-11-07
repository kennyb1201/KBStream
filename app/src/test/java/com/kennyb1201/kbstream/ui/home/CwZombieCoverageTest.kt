package com.kennyb1201.kbstream.ui.home

import com.kennyb1201.kbstream.data.tmdb.TmdbDetail
import com.kennyb1201.kbstream.data.tmdb.TmdbDetailLookup
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The zombie Continue Watching cards, second pass: EVERY builder that can emit
 * one, and every id shape that could skip the drop's verdict.
 *
 * Reported 2026-11-07 12:13, on v0.9.191 - i.e. AFTER the first fix shipped:
 * the same black, artless, nameless cards were still on Continue Watching on a
 * fresh Home build. The first fix's drop was right; its COVERAGE was not.
 *
 * Two holes, and each one is pinned here as behaviour plus wiring:
 *
 *  - the id: the drop's verdict was recorded in only two of the four parent-id
 *    branches of the local resume builder. A row travelling on `tt...` (the
 *    nullable enriched lookup, no verdict) or on an id of no known flavor (a
 *    bare null) left `detailLookup` at its initial [TmdbDetailLookup.Transient],
 *    and [dropsZombieCard] can never fire on that. One verdict-producing lookup
 *    now answers every flavor, and a shape no lookup accepts is
 *    [TmdbDetailLookup.Unresolvable] - a droppable verdict of its own, kept
 *    DISTINCT from a 404 so the trace says which failure the row has.
 *  - the card family: only the resume builder called the drop. The next-up
 *    builder and all three tracker builders (Simkl, Trakt, MDBList) could build
 *    the same card from a session or a completed row filed under a dead id,
 *    because their title gates pass on the source's own title. All four now ask
 *    for a verdict and drop through the same rule.
 *
 * The trace line is the settler for the field: one line per card per build,
 * naming the source builder, the id shape, the verdict, the two facts that can
 * save a card, and the decision.
 */
class CwZombieCoverageTest {

    private val viewModel = source("com/kennyb1201/kbstream/ui/home/HomeViewModel.kt")
    private val upNext = source("com/kennyb1201/kbstream/ui/home/HomeUpNext.kt")
    private val repository = source("com/kennyb1201/kbstream/data/tmdb/TmdbRepository.kt")
    private val writer = source("com/kennyb1201/kbstream/ui/player/PlaybackHistoryIds.kt")

    // ── T1: a `tt` id whose /find misses is droppable ───────────────────────

    @Test
    fun `a tt id the find endpoint does not know is a droppable verdict`() {
        assertTrue(
            "an empty /find answer is the same fact as a 404: TMDB answered, and " +
                "the answer was 'no title'",
            repository.contains("if (found.tvResults.isEmpty() && found.movieResults.isEmpty()) {") &&
                repository.contains("return TmdbDetailLookup.NotFound")
        )
        assertTrue(
            "and it drops a card with no art and no name",
            dropsZombieCard(
                lookup = TmdbDetailLookup.NotFound,
                hasArtwork = false,
                resolvedName = null
            )
        )
        assertTrue(
            "the shape that used to skip the verdict now takes a verdict lookup",
            viewModel.contains("tmdbRepository.lookupDetailByImdbId(") ||
                viewModel.contains("tmdbRepository.lookupDetailForRawId(")
        )
    }

    @Test
    fun `the local builder asks one lookup for every id flavor`() {
        val verdicts = viewModel.slice(
            "var detailLookup: TmdbDetailLookup = TmdbDetailLookup.Transient",
            "localCompletedForParent ="
        )

        assertTrue(
            "one call answers every shape, so no branch can answer a detail-or-null " +
                "without recording what the lookup said",
            verdicts.contains("tmdbRepository.lookupDetailForRawId(")
        )
        assertFalse(
            "the hole: the tt branch called the nullable enriched lookup and never " +
                "set the verdict",
            verdicts.contains("tmdbRepository.fetchEnrichedMetaCached(")
        )
        assertTrue(
            "and every non-Found outcome becomes the recorded verdict",
            verdicts.contains("detailLookup = lookup")
        )
    }

    // ── T2: an id of no known shape is droppable, and says so ───────────────

    @Test
    fun `an id no lookup accepts is unresolvable and drops`() {
        assertEquals(
            "an add-on key or a slug is not a shape any TMDB lookup takes",
            "slug",
            upNextIdShape("an-addons-own-key")
        )
        assertEquals("blank row id", "blank", upNextIdShape("   "))
        assertEquals("tmdb shape", "tmdb", upNextIdShape("tmdb:3634822"))
        assertEquals("imdb shape", "imdb", upNextIdShape("tt3634822"))
        assertEquals("tvdb shape", "tvdb", upNextIdShape("tvdb:12345"))
        assertEquals("bare number", "number", upNextIdShape("1396"))

        assertTrue(
            "the lookup says Unresolvable rather than 'we could not ask'",
            repository.contains("else -> TmdbDetailLookup.Unresolvable")
        )
        assertTrue(
            "and a row that names nothing any lookup can ask about can only ever be " +
                "a black tile",
            dropsZombieCard(
                lookup = TmdbDetailLookup.Unresolvable,
                hasArtwork = false,
                resolvedName = null
            )
        )
    }

    // ── T3: the shipped 404 rule still holds ────────────────────────────────

    @Test
    fun `a tmdb 404 row is still dropped`() {
        assertTrue(
            dropsZombieCard(
                lookup = TmdbDetailLookup.NotFound,
                hasArtwork = false,
                resolvedName = null
            )
        )
    }

    // ── T4 / T5 / T11: the three things that keep a card ────────────────────

    @Test
    fun `art on the row keeps the card however dead the id`() {
        assertFalse(
            "a failed lookup must not blank a card that already had artwork",
            dropsZombieCard(
                lookup = TmdbDetailLookup.NotFound,
                hasArtwork = true,
                resolvedName = null
            )
        )
        assertFalse(
            dropsZombieCard(
                lookup = TmdbDetailLookup.Unresolvable,
                hasArtwork = true,
                resolvedName = null
            )
        )
    }

    @Test
    fun `a name TMDB resolved keeps the card`() {
        assertFalse(
            dropsZombieCard(
                lookup = TmdbDetailLookup.Found(TmdbDetail(id = 1396, name = "The Bear")),
                hasArtwork = false,
                resolvedName = "The Bear"
            )
        )
    }

    @Test
    fun `an outage keeps every card`() {
        assertFalse(
            "Transient is 'we could not ask', which is not a fact about the id - " +
                "dropping on it would empty the rail during an outage",
            dropsZombieCard(
                lookup = TmdbDetailLookup.Transient,
                hasArtwork = false,
                resolvedName = null
            )
        )
    }

    // ── T6: the next-up builder gets the same gate ──────────────────────────

    @Test
    fun `a zombie completed row builds no next-up card`() {
        val builder = memberAfter("    private suspend fun buildLocalNextUpItem(")

        assertTrue(
            "the builder asks for a verdict, not just a detail",
            builder.contains("seriesDetailLookup(parentId)")
        )
        assertTrue(
            "records the verdict the same way the resume path does",
            builder.contains("dropsZombieCard(")
        )
        assertTrue(
            "and returns null on a drop, which the caller already filters",
            builder.contains("if (zombie) return null")
        )
    }

    // ── T7: the trace line names the decision ───────────────────────────────

    @Test
    fun `the diag line names the source, the verdict and the decision`() {
        val dropped = upNextZombieDiagLine(
            source = "local",
            parentId = "tmdb:3634822",
            lookup = TmdbDetailLookup.NotFound,
            hasArtwork = false,
            resolvedName = null,
            dropped = true
        )
        assertTrue("names the builder family: $dropped", dropped.contains("source=local"))
        assertTrue("names the id: $dropped", dropped.contains("id='tmdb:3634822'"))
        assertTrue("names the shape: $dropped", dropped.contains("shape=tmdb"))
        assertTrue("names the verdict: $dropped", dropped.contains("verdict=NotFound"))
        assertTrue("names the art fact: $dropped", dropped.contains("artwork=false"))
        assertTrue("names the decision: $dropped", dropped.contains("-> drop"))

        val kept = upNextZombieDiagLine(
            source = "trakt",
            parentId = "tt1234567",
            lookup = TmdbDetailLookup.Transient,
            hasArtwork = false,
            resolvedName = null,
            dropped = false
        )
        assertTrue("a kept card says why: $kept", kept.contains("verdict=Transient"))
        assertTrue("and says it was kept: $kept", kept.contains("-> keep"))
        assertTrue("naming its own builder: $kept", kept.contains("source=trakt"))
    }

    @Test
    fun `every builder that can emit a card writes the line`() {
        listOf("local", "local-nextup", "simkl", "trakt", "mdblist").forEach { source ->
            assertTrue(
                "the $source builder must trace its decision",
                viewModel.contains("source = \"$source\"")
            )
        }
        assertTrue(
            "gated by one switch, like the Upcoming rail's diagnostics",
            upNext.contains("internal const val UP_NEXT_DIAGNOSTICS = true") &&
                viewModel.contains("if (UP_NEXT_DIAGNOSTICS) {")
        )
    }

    // ── T8: the writer stops stamping a dead `tt` id ────────────────────────

    @Test
    fun `the writer verifies an imdb candidate before filing under it`() {
        assertTrue(
            "the candidate's own flavor decides which lookup answers",
            writer.contains("val isImdb = candidate.startsWith(\"tt\", ignoreCase = true)") &&
                writer.contains("repository.lookupDetailByImdbId(candidate, parentType)")
        )
        assertTrue(
            "only a definitive miss repairs, so an outage files under the same id as before",
            writer.contains("if (verdict != TmdbDetailLookup.NotFound) return candidate")
        )
        assertTrue(
            "and the repair is still the playing episode's own root",
            writer.contains("val episodeRoot = episodeRootFromId(episodeStreamId)")
        )
        assertTrue(
            "a route that already carries a canonical IMDb id is verified too - that " +
                "flavor used to be returned on sight, which is how a stale tt id got filed",
            writer.contains("if (raw.startsWith(\"tt\")) {") &&
                writer.contains("return verifiedParentId(context, raw, parentType, episodeStreamId)")
        )
    }

    // ── T9 / T10: the tracker builders get the same gate ────────────────────

    @Test
    fun `a simkl session for a dead id builds no card`() {
        val builder = memberAfter("    private suspend fun buildSimklUpNextItem(")

        assertTrue(
            "the session's id is resolved with the verdict kept",
            builder.contains("tmdbRepository\n                                .lookupDetailForRawId(") ||
                builder.contains("lookupDetailForRawId(")
        )
        assertTrue("and the drop is the shared rule", builder.contains("dropsZombieCard("))
        assertTrue(
            "the tracker's own title does not save it: only the TMDB-resolved name is passed",
            builder.contains("resolvedName = resolvedName")
        )
        assertTrue("a drop returns no card", builder.contains("if (simklIsZombie) return null"))
    }

    @Test
    fun `a trakt or mdblist session for a dead id builds no card`() {
        val trakt = memberAfter("    private suspend fun buildTraktUpNextItem(")
        val mdblist = memberAfter("    private suspend fun buildMdbListUpNextItem(")

        listOf("trakt" to trakt, "mdblist" to mdblist).forEach { (name, builder) ->
            assertTrue(
                "$name resolves its id with the verdict kept",
                builder.contains("lookupDetailForRawId(")
            )
            assertTrue("$name drops through the shared rule", builder.contains("dropsZombieCard("))
            assertTrue(
                "$name's own title cannot save the card",
                builder.contains("upNextDisplayTitleOrNull(detail?.name") &&
                    builder.contains("upNextDisplayTitleOrNull(detail?.title")
            )
            assertTrue(
                "$name returns null on a drop",
                builder.contains("if (${name}IsZombie) return null")
            )
        }
    }

    @Test
    fun `a tracker resolution failure keeps the card`() {
        // The builder cannot tell 404 from timeout if it collapses the answer, so
        // every one of them starts from Transient and only a verdict overwrites it.
        assertTrue(
            viewModel.contains("var simklLookup: TmdbDetailLookup = TmdbDetailLookup.Transient")
        )
        assertTrue(
            "and an exception in the lookup is 'we could not ask', never a miss",
            viewModel.contains("} catch (_: Exception) {\n                    TmdbDetailLookup.Transient\n                }")
        )
        assertFalse(
            dropsZombieCard(
                lookup = TmdbDetailLookup.Transient,
                hasArtwork = false,
                resolvedName = null
            )
        )
    }

    // ── The rule itself: three parts, two droppable verdicts ────────────────

    @Test
    fun `the drop is still three parts and never more`() {
        assertTrue(
            "verdict && no art && no name - the art and the name are what save a card",
            upNext.contains("lookup.namesNoTitle && !hasArtwork && resolvedName == null")
        )
        assertTrue(
            "and only the two verdicts that name no title are droppable",
            source("com/kennyb1201/kbstream/data/tmdb/TmdbDetailLookup.kt").contains(
                "get() = this == TmdbDetailLookup.NotFound || this == TmdbDetailLookup.Unresolvable"
            )
        )
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    /** A member's body: from its declaration to the next `private suspend fun`. */
    private fun memberAfter(declaration: String): String {
        val start = viewModel.indexOf(declaration)
        assertTrue("$declaration is missing", start >= 0)
        val end = viewModel.indexOf("    private suspend fun ", start + declaration.length)
        return viewModel.substring(start, if (end > start) end else viewModel.length)
    }

    private fun source(relative: String): String {
        val file = File(findSourceRoot(), relative)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

    private fun String.slice(startMarker: String, endMarker: String): String {
        val start = indexOf(startMarker)
        assertTrue("$startMarker is missing", start >= 0)
        val end = indexOf(endMarker, start)
        assertTrue("$endMarker must follow $startMarker", end > start)
        return substring(start, end)
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
}
