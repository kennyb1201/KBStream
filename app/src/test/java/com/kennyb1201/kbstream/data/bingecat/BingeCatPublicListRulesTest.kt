package com.kennyb1201.kbstream.data.bingecat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The public-lists rules, pinned without a TV.
 *
 * Each case here is a decision the surface makes silently: a mode offered for a
 * list that cannot take it, a verdict read as an error when it is a satisfied
 * state, a job polled forever because an unknown status read as "finished", or
 * a public list presented without its owner. The rules are pure so the answers
 * are checked here rather than discovered on a build.
 */
class BingeCatPublicListRulesTest {

    // ── Modes ────────────────────────────────────────────────────────────

    @Test
    fun `a mixed list offers combined, split and both halves`() {
        assertEquals(
            listOf(
                BingeCatImportMode.COMBINED,
                BingeCatImportMode.SPLIT,
                BingeCatImportMode.MOVIE,
                BingeCatImportMode.SERIES
            ),
            BingeCatPublicListRules.modesFor("movie", movieCount = 12, seriesCount = 8)
        )
    }

    @Test
    fun `a movie-only list does not offer a series list`() {
        val modes = BingeCatPublicListRules.modesFor("movie", movieCount = 12, seriesCount = 0)
        assertTrue("combined is always offered", BingeCatImportMode.COMBINED in modes)
        assertFalse(
            "splitting a movie-only list would create an empty half",
            BingeCatImportMode.SPLIT in modes
        )
        assertFalse(BingeCatImportMode.SERIES in modes)
        assertTrue(BingeCatImportMode.MOVIE in modes)
    }

    @Test
    fun `a series-only list does not offer a movie list`() {
        val modes = BingeCatPublicListRules.modesFor("series", movieCount = 0, seriesCount = 7)
        assertFalse(BingeCatImportMode.MOVIE in modes)
        assertTrue(BingeCatImportMode.SERIES in modes)
        assertFalse(BingeCatImportMode.SPLIT in modes)
    }

    @Test
    fun `unknown counts hide nothing`() {
        val modes = BingeCatPublicListRules.modesFor(null, movieCount = null, seriesCount = null)
        // The contract's counts are nullable; withholding a mode on a missing
        // count would withhold the one the viewer came for.
        assertTrue(BingeCatImportMode.SPLIT in modes)
        assertTrue(BingeCatImportMode.MOVIE in modes)
        assertTrue(BingeCatImportMode.SERIES in modes)
    }

    @Test
    fun `anime is offered only for an anime list`() {
        assertFalse(
            "anime must be HIDDEN, not disabled, for a non-anime list",
            BingeCatImportMode.ANIME in
                BingeCatPublicListRules.modesFor("movie", movieCount = 10, seriesCount = 4)
        )
        assertTrue(
            BingeCatPublicListRules.isAnime("anime")
        )
        assertTrue(
            BingeCatPublicListRules.isAnime("Anime")
        )
        assertTrue(
            "a content type that names anime in a longer form still counts",
            BingeCatPublicListRules.isAnime("anime_series")
        )
        assertTrue(
            BingeCatImportMode.ANIME in
                BingeCatPublicListRules.modesFor("anime", movieCount = 3, seriesCount = 3)
        )
        assertFalse(BingeCatPublicListRules.isAnime(null))
        assertFalse(BingeCatPublicListRules.isAnime(""))
    }

    @Test
    fun `the confirmation starts on combined`() {
        assertEquals(
            BingeCatImportMode.COMBINED,
            BingeCatPublicListRules.initialMode("movie", movieCount = 5, seriesCount = 5)
        )
    }

    // ── Counts and labels ───────────────────────────────────────────────

    @Test
    fun `a card counts its halves, or falls back to the total`() {
        assertEquals("12 movies · 8 series", BingeCatPublicListRules.countsLabel(12, 8, 20))
        assertEquals("1 movie", BingeCatPublicListRules.countsLabel(1, 0, 1))
        assertEquals("3 series", BingeCatPublicListRules.countsLabel(0, 3, 3))
        assertEquals("42 titles", BingeCatPublicListRules.countsLabel(null, null, 42))
        assertEquals("1 title", BingeCatPublicListRules.countsLabel(null, null, 1))
        assertNull(
            "a list that said nothing about its size says nothing",
            BingeCatPublicListRules.countsLabel(null, null, null)
        )
    }

    @Test
    fun `a category counts what the tree said`() {
        assertEquals(
            "\"all\" is the tile's own total",
            "30 lists",
            BingeCatPublicListRules.categoryCountLabel(mapOf("all" to 30, "movies" to 20))
        )
        assertEquals(
            "without an all key the per-type counts are summed",
            "30 lists",
            BingeCatPublicListRules.categoryCountLabel(mapOf("movies" to 20, "shows" to 10))
        )
        assertEquals(
            "and an unknown shape still says something",
            "7 lists",
            BingeCatPublicListRules.categoryCountLabel(mapOf("channels" to 7))
        )
        assertNull(BingeCatPublicListRules.categoryCountLabel(emptyMap()))
    }

    @Test
    fun `attribution always names BingeCat and the owner`() {
        assertEquals(
            "BingeCat community list by kenny",
            BingeCatPublicListRules.attribution("kenny")
        )
        assertEquals(
            "BingeCat official list by kenny · from trakt",
            BingeCatPublicListRules.attribution("kenny", isOfficial = true, provider = "trakt")
        )
        assertEquals(
            "an ownerless list is still named as BingeCat's, not KBStream's",
            "BingeCat community list",
            BingeCatPublicListRules.attribution(null)
        )
        assertFalse(
            "a public list must never be presented as KBStream's pick",
            BingeCatPublicListRules.attribution("kenny").contains("KBStream's pick")
        )
    }

    @Test
    fun `likes and added-by lines appear only when there is a number`() {
        assertEquals("1 like", BingeCatPublicListRules.likesLabel(1))
        assertEquals("14 likes", BingeCatPublicListRules.likesLabel(14))
        assertNull(BingeCatPublicListRules.likesLabel(0))
        assertNull(BingeCatPublicListRules.likesLabel(null))
        assertEquals("added by 1 profile", BingeCatPublicListRules.addedByLabel(1))
        assertEquals("added by 3 profiles", BingeCatPublicListRules.addedByLabel(3))
        assertNull(BingeCatPublicListRules.addedByLabel(0))
    }

    // ── Cards ────────────────────────────────────────────────────────────

    @Test
    fun `a row levels into a card under its own key`() {
        val card = BingeCatPublicListRules.cardFromRow(
            BingeCatPublicListRow(
                key = "mdblist:abc",
                source = "mdblist",
                name = "Neo-noir",
                owner = "kenny",
                movieCount = 9,
                coverPosters = listOf("a", "b")
            )
        )
        assertEquals("mdblist:abc", card?.importId)
        assertEquals("Neo-noir", card?.name)
        assertEquals("kenny", card?.owner)
        assertEquals("mdblist", card?.provider)
        assertEquals(listOf("a", "b"), card?.coverPosters)
    }

    @Test
    fun `a search result levels into a card under its own id`() {
        val card = BingeCatPublicListRules.cardFromSearch(
            BingeCatPublicSearchResult(
                id = "trakt:123",
                name = "Cozy mysteries",
                owner = BingeCatPublicOwner(username = "u", displayName = "Kenny"),
                likes = 4,
                addedCount = 2,
                isOfficial = true
            )
        )
        assertEquals("trakt:123", card?.importId)
        assertEquals("the display name is the owner label", "Kenny", card?.owner)
        assertEquals(4, card?.likes)
        assertEquals(2, card?.addedCount)
        assertTrue(card?.isOfficial == true)
    }

    @Test
    fun `a card with no identity or name is skipped, never guessed`() {
        assertNull(BingeCatPublicListRules.cardFromRow(BingeCatPublicListRow(key = "k")))
        assertNull(BingeCatPublicListRules.cardFromRow(BingeCatPublicListRow(name = "n")))
        assertNull(BingeCatPublicListRules.cardFromSearch(BingeCatPublicSearchResult(name = "n")))
        assertNull(BingeCatPublicListRules.cardFromSearch(BingeCatPublicSearchResult(id = "i")))
    }

    // ── Per-list verdicts ────────────────────────────────────────────────

    @Test
    fun `already imported is a satisfied state, not a failure`() {
        val feedback = BingeCatPublicListRules.feedbackFor(
            BingeCatPublicListImportAccepted(alreadyImported = listOf("x")),
            "x"
        )
        assertEquals(BingeCatImportFeedback.AlreadyImported, feedback)
    }

    @Test
    fun `a missing list and a refused list stay apart`() {
        assertEquals(
            BingeCatImportFeedback.NotFound,
            BingeCatPublicListRules.feedbackFor(
                BingeCatPublicListImportAccepted(notFound = listOf("x")),
                "x"
            )
        )
        val refused = BingeCatPublicListRules.feedbackFor(
            BingeCatPublicListImportAccepted(notImportable = listOf("x")),
            "x"
        )
        assertTrue(refused is BingeCatImportFeedback.NotImportable)
    }

    @Test
    fun `a job is a started import and its status url is kept`() {
        val feedback = BingeCatPublicListRules.feedbackFor(
            BingeCatPublicListImportAccepted(
                job = BingeCatAcceptedJob(id = "j1", statusUrl = "https://bingecat.com/api/v1/jobs/j1")
            ),
            "x"
        )
        assertTrue(feedback is BingeCatImportFeedback.Started)
        assertEquals(
            "https://bingecat.com/api/v1/jobs/j1",
            (feedback as BingeCatImportFeedback.Started).jobUrl
        )
    }

    @Test
    fun `an answer with neither a job nor a verdict is a failed import`() {
        val feedback = BingeCatPublicListRules.feedbackFor(
            BingeCatPublicListImportAccepted(),
            "x"
        )
        assertTrue(
            "a blank 200 must not read as success",
            feedback is BingeCatImportFeedback.NotImportable
        )
    }

    // ── Job statuses ─────────────────────────────────────────────────────

    @Test
    fun `queued and running keep waiting, and an unknown status does too`() {
        assertEquals(
            BingeCatJobOutcome.Pending,
            BingeCatPublicListRules.jobOutcome("queued", null)
        )
        assertEquals(
            BingeCatJobOutcome.Pending,
            BingeCatPublicListRules.jobOutcome("running", null)
        )
        assertEquals(
            "a status a newer server adds must not read as finished",
            BingeCatJobOutcome.Pending,
            BingeCatPublicListRules.jobOutcome("reticulating", null)
        )
        assertEquals(
            BingeCatJobOutcome.Pending,
            BingeCatPublicListRules.jobOutcome(null, null)
        )
    }

    @Test
    fun `succeeded and partial finish, failed and cancelled report`() {
        assertEquals(
            BingeCatJobOutcome.Finished(partial = false, detail = null),
            BingeCatPublicListRules.jobOutcome("succeeded", null)
        )
        assertEquals(
            BingeCatJobOutcome.Finished(partial = true, detail = "partial"),
            BingeCatPublicListRules.jobOutcome("partial", "partial")
        )
        val failed = BingeCatPublicListRules.jobOutcome("failed", "list_limit_reached")
        assertTrue(failed is BingeCatJobOutcome.Failed)
        assertTrue(
            "the tier limit is its own sentence, not a bare code",
            (failed as BingeCatJobOutcome.Failed).reason.contains("list limit")
        )
        val cancelled = BingeCatPublicListRules.jobOutcome("cancelled", null)
        assertTrue(cancelled is BingeCatJobOutcome.Failed)
    }

    @Test
    fun `a poll waits the job's Retry-After, bounded`() {
        assertEquals(
            "no header means 5 seconds",
            BingeCatPublicListRules.DEFAULT_RETRY_AFTER_SECONDS,
            BingeCatPublicListRules.pollSeconds(null)
        )
        assertEquals(7L, BingeCatPublicListRules.pollSeconds("7"))
        assertEquals("never a tight loop", 1L, BingeCatPublicListRules.pollSeconds("0"))
        assertEquals("never a parked card", 60L, BingeCatPublicListRules.pollSeconds("9999"))
        assertEquals(5L, BingeCatPublicListRules.pollSeconds("nonsense"))
    }

    // ── Gates and copy ───────────────────────────────────────────────────

    @Test
    fun `search needs a real query`() {
        assertFalse(BingeCatPublicListRules.searchEnabled(""))
        assertFalse(BingeCatPublicListRules.searchEnabled("  "))
        assertFalse(BingeCatPublicListRules.searchEnabled("a"))
        assertTrue(BingeCatPublicListRules.searchEnabled("ab"))
        assertTrue(BingeCatPublicListRules.searchEnabled("  drama  "))
    }

    @Test
    fun `every failure reason has a sentence a viewer can act on`() {
        listOf("429", "network", "401", "no-token", "403-scope", "http:500").forEach { reason ->
            val text = BingeCatPublicListRules.failureText(reason)
            assertTrue("no raw code in $reason: $text", !text.contains(reason))
            assertTrue("a reason line is a sentence: $text", text.endsWith("."))
        }
        assertTrue(
            BingeCatPublicListRules.importFailureText("import-running").contains("already importing")
        )
        assertTrue(
            BingeCatPublicListRules.importFailureText("unsupported_import_mode")
                .contains("pick another one")
        )
        assertTrue(
            BingeCatPublicListRules.importFailureText("403-scope").contains("Reconnect")
        )
    }
}
