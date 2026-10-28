package com.kennyb1201.kbstream.data.tmdb

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where the resolved stream root id is wired in.
 *
 * [preferredStreamRootId] is only worth anything if the id it chooses is the one
 * the add-ons are actually asked for, and the paths that build a stream id are
 * spread across four files: the series listing that turns a show plus a season
 * into every episode id ([TmdbRepository.getSeasonEpisodes]), the Detail screen's
 * two play targets, the cloud resume rows, and the ViewModel state the screen
 * reads the resolved id from. These read those sources and pin the wiring - the
 * same pattern the other source-contract tests use, because the paths themselves
 * need a network (and, for the screen, a device) to run.
 */
class StreamRootIdWiringContractTest {

    private val tmdbRepository by lazy {
        source("com/kennyb1201/kbstream/data/tmdb/TmdbRepository.kt")
    }
    private val detailViewModel by lazy {
        source("com/kennyb1201/kbstream/ui/detail/DetailViewModel.kt")
    }
    private val detailScreen by lazy {
        source("com/kennyb1201/kbstream/ui/detail/DetailScreen.kt")
    }
    private val playbackResume by lazy {
        source("com/kennyb1201/kbstream/ui/detail/DetailPlaybackResume.kt")
    }

    // ── the series listing ───────────────────────────────────────────
    //
    // The one place the series side turns a show plus a season into the ids the
    // add-ons are asked for. Rooting it here is what keeps every entry point -
    // the season browser, Continue Watching, Up Next, random episodes, the
    // players' own next-episode walk - on the same id.

    @Test
    fun `the season listing roots every episode id in TMDB's IMDB id`() {
        val listing = section(
            tmdbRepository,
            "suspend fun getSeasonEpisodes(",
            "private fun episodeSchemeTag("
        )
        assertTrue(
            "the listing must resolve the root from the verified TMDB id",
            listing.contains("val streamRootId = streamRootIdFor(imdbId, tvId, \"series\")")
        )
        assertTrue(
            "every episode id must be built from the resolved root",
            listing.contains("rootId = streamRootId,")
        )
        assertFalse(
            "the route id must not root episode ids any more",
            listing.contains("rootId = imdbId,")
        )
    }

    @Test
    fun `the season cache cannot outlive the resolved root`() {
        val listing = section(
            tmdbRepository,
            "suspend fun getSeasonEpisodes(",
            "private fun episodeSchemeTag("
        )
        // Both halves of the key are derived from the same root, so a season
        // cached while the route id was all the app had is never served for
        // ids built with the IMDB one (and the episode scheme is looked up
        // under the same id the player files it under).
        assertTrue(
            "the cache key must carry the resolved root",
            listing.contains("val key = \"\$tvId:\$season:\$streamRootId:")
        )
        assertTrue(
            "the episode scheme must be read under the resolved root too",
            listing.contains("episodeSchemeTag(streamRootId, tvId)")
        )
    }

    @Test
    fun `the resolver is the cached TMDB lookup and only accepts an IMDB answer`() {
        val resolver = section(
            tmdbRepository,
            "suspend fun streamRootIdFor(",
            "suspend fun resolveTmdbId("
        )
        assertTrue(
            "the lookup must go through the memory+disk cached resolution",
            resolver.contains("resolveImdbId(verified, type)")
        )
        assertTrue(
            "and the rule decides, not the caller",
            resolver.contains("preferredStreamRootId(routeId, resolved)")
        )
        assertTrue(
            "no TMDB id to ask about must answer the route id",
            resolver.contains("?: return routeId")
        )
    }

    // ── the Detail screen's play targets ─────────────────────────────

    @Test
    fun `the movie play target names the movie by the resolved root`() {
        val movie = section(detailScreen, "if (type == \"movie\") {", "} else {")
        assertTrue(
            "a movie request must be named by the resolved root",
            movie.contains("streamId = streamRootId,")
        )
        assertFalse(
            "the route id must not reach the request",
            movie.contains("streamId = id,")
        )
        assertTrue(
            "and the target must rebuild when the root resolves",
            movie.contains("streamRootId,")
        )
    }

    @Test
    fun `the episode fallback id is rooted like the listing's rows`() {
        val fallback = section(detailScreen, "val fallbackStreamId = remember(", "val targetStreamId =")
        assertTrue(
            "the fallback episode id must be rooted in the resolved root",
            fallback.contains("rootId = streamRootId,")
        )
        assertFalse(
            "otherwise the fallback row would name a different show than its siblings",
            fallback.contains("rootId = id,")
        )
    }

    @Test
    fun `the screen reads the resolved root and falls back to the route id`() {
        assertTrue(
            "the screen must read the ViewModel's resolved root",
            detailScreen.contains(
                "val vmStreamRootId by viewModel.streamRootId.collectAsStateWithLifecycle()"
            )
        )
        assertTrue(
            "and a blank (unresolved) root must fall back to the route id",
            detailScreen.contains("val streamRootId = vmStreamRootId.ifBlank { id }")
        )
    }

    // ── the play paths wait for the resolution ───────────────────────
    //
    // Resolving the root is only half of it: a play press that arrives before
    // the lookup answers reads the blank state and names the request with the
    // CATALOG id, which is how every episode of one show ended up requesting
    // another title's streams. So the press waits for the resolved value -
    // bounded - and builds its target from the root it waited for.

    @Test
    fun `a play action waits for the resolved root before it names its request`() {
        val wait = section(
            detailViewModel,
            "internal suspend fun waitForStreamRootId(",
            "fun computeEpisodeWatched("
        )
        assertTrue(
            "the wait must be bounded rather than open-ended",
            wait.contains("withTimeoutOrNull(timeoutMs)")
        )
        assertTrue(
            "and it must wait for a NON-BLANK root, not for any value",
            wait.contains("rootId.first { it.isNotBlank() }")
        )
        assertTrue(
            "a wait that runs out must answer the route id, the old behavior",
            wait.contains("?: routeId")
        )
        assertTrue(
            "the bound is the five-second budget",
            detailViewModel.contains("private const val STREAM_ROOT_WAIT_MS = 5_000L")
        )
        assertTrue(
            "and the screen reads the wait through the ViewModel's own state",
            detailViewModel.contains(
                "): String = waitForStreamRootId(_streamRootId, routeId, timeoutMs)"
            )
        )
    }

    @Test
    fun `every play path builds its target from the root it waited for`() {
        assertTrue(
            "the movie target must be built from the root id it is given, not from the screen's",
            section(detailScreen, "if (type == \"movie\") {", "} else {")
                .contains("playTargetFor = { streamRootId ->")
        )
        assertTrue(
            "the series target must re-derive its fallback file id from that root",
            detailScreen.contains("?: fallbackIdFor(streamRootId)")
        )
        assertEquals(
            "the autoplay effect, the Play button and the random button's fallback " +
                "must each name the request by the awaited root",
            3,
            Regex("awaitStreamRootId\\(id\\)").findAll(detailScreen).count()
        )
        assertFalse(
            "and no play path may hand the painted target on: it is built from a " +
                "root id the request cannot have waited for",
            Regex("onNavigateStreams\\(\\s*playTarget,").containsMatchIn(detailScreen)
        )
    }

    @Test
    fun `a stored id is only played under the root the request is named by`() {
        // Continue Watching hands this screen an id it did not build: the
        // history row's own episodeStreamId, written when the row was filed.
        // A row filed before the root was verified carries the CATALOG id, so
        // playing it as-is is the other title's streams all over again - the
        // row's half of "the fix resolved the root but play did not use it".
        assertTrue(
            "a stored id must be read against the root it is played under",
            detailScreen.contains(
                "streamId?.takeIf { EpisodeMatch.seriesRootFrom(it) == rootId }"
            )
        )
        assertEquals(
            "both stored rungs - the history row and the deep link - must go through it",
            2,
            Regex("\\?\\.let \\{ filedUnder\\(it, streamRootId\\) \\}")
                .findAll(detailScreen).count()
        )
        assertFalse(
            "neither stored id may be used as-is, or a stale catalog root names the request",
            Regex("\\?: resumeStreamId\\?\\.[^\\n]*takeIf").containsMatchIn(detailScreen)
        )
    }

    // ── the ViewModel state ──────────────────────────────────────────

    @Test
    fun `the view model publishes the resolved root and resets it per load`() {
        assertTrue(
            "the root must be observable by the screen",
            detailViewModel.contains(
                "val streamRootId: StateFlow<String> = _streamRootId.asStateFlow()"
            )
        )
        assertTrue(
            "a new title must not inherit the previous root",
            detailViewModel.contains("_streamRootId.value = \"\"")
        )
        val resolve = section(
            detailViewModel,
            "val streamRootRouteId = id",
            "// Second-source air dates"
        )
        assertTrue(
            "the load must resolve the root from the TMDB detail it fetched",
            resolve.contains("tmdbRepository.streamRootIdFor(")
        )
        assertTrue(
            "and a slow answer must not rename a title the viewer already left",
            resolve.contains("if (imdbId == streamRootRouteId)")
        )
    }

    // ── the cloud resume rows ────────────────────────────────────────

    @Test
    fun `both cloud resume rows root their episode id the same way`() {
        assertEquals(
            "the Simkl, MDBList and Trakt resume rows must all root through the resolver",
            3,
            Regex("rootId = vm\\.tmdbRepository\\.streamRootIdFor\\(").findAll(playbackResume).count()
        )
        assertFalse(
            "a resume row rooted in the route id would resume another title's streams",
            playbackResume.contains("rootId = id,")
        )
    }

    /** The text between two anchors, with both anchors asserted to exist. */
    private fun section(text: String, from: String, to: String): String {
        val start = text.indexOf(from)
        assertTrue("anchor not found: $from", start >= 0)
        val end = text.indexOf(to, start + from.length)
        assertTrue("anchor not found after $from: $to", end > start)
        return text.substring(start, end)
    }

    private fun source(relative: String): String {
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
}
