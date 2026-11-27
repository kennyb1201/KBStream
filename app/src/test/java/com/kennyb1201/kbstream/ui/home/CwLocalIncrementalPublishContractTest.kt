package com.kennyb1201.kbstream.ui.home

import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rail's local half publishes AS ITS ROWS LAND, and the pass is bounded
 * (spec 2026-11-26, CW load F5).
 *
 * The 6390 paste measured what the old shape cost: the enriched build published
 * at +153856ms against a seed at +0ms. Every row was capped at 8s (F3), but the
 * pass awaited EVERY row - `history.map { async { ... } }.awaitAll()` - and the
 * rows run six at a time, so ~108 rows are ~18 waves and the cap multiplied by
 * the waves. Nothing on the rail moved until the slowest wave answered.
 *
 * These cases drive the build directly, with one row that never answers inside
 * its own pass deadline and the rest fast. Against the old awaitAll shape the
 * first publish can only come after the slow row's 5s (or after the whole pass,
 * at +153s on the field device); after the fix it carries the fast rows within
 * their own latency, keeps the slow row's seed card where it was, and the pass
 * answers inside its deadline.
 */
class CwLocalIncrementalPublishContractTest {

    private fun card(id: String, historyRowId: String? = id) =
        UpNextItem(
            id = id,
            title = id,
            poster = null,
            badge = UpNextBadge.CONTINUE_WATCHING,
            historyRowId = historyRowId
        )

    private fun elapsedMs(startedAtNs: Long) =
        (System.nanoTime() - startedAtNs) / 1_000_000L

    // ── The publish follows the rows, not the pass ─────────────────────────

    @Test
    fun `the rail publishes the answered rows without waiting for a row that never answers`() =
        runBlocking {
            val startedAt = System.nanoTime()
            val published = mutableListOf<Pair<Long, List<String>>>()

            val progress =
                CwLocalProgress(
                    listOf(
                        "a" to card("aSeed"),
                        "slow" to card("slowSeed"),
                        "c" to card("cSeed")
                    )
                )

            val result =
                cwLocalItemsIncrementally(
                    rows = listOf("a", "slow", "c"),
                    progress = progress,
                    deadlineAtMs = 300L,
                    nowMs = { elapsedMs(startedAt) },
                    keyOf = { row -> row },
                    publish = { cards ->
                        published.add(elapsedMs(startedAt) to cards.map { it.id })
                    },
                    enrich = { row, _ ->
                        when (row) {
                            // Never answers inside the pass's deadline: the
                            // field device's cold-TMDB row.
                            "slow" -> {
                                delay(5_000)
                                card("slowRich")
                            }
                            "a" -> {
                                delay(5)
                                card("aRich")
                            }
                            else -> {
                                delay(60)
                                card("cRich")
                            }
                        }
                    }
                )

            assertTrue(
                "nothing published while a slow row was still out",
                published.isNotEmpty()
            )
            assertTrue(
                "the first publish came with the slow row, not with the fast ones " +
                    "(t=${published.single().first}ms)",
                published.single().first <= 150L
            )
            assertEquals(
                "one publish: the first landing, with the other fast rows still out",
                1,
                published.size
            )
            assertEquals(
                "the seed's order is kept, and the row that answered is upgraded in place",
                listOf("aRich", "slowSeed", "cSeed"),
                published.single().second
            )
            assertEquals(
                "once both fast rows answered, both upgrades are in the slice - " +
                    "only the next batch's publish waits",
                listOf("aRich", "slowSeed", "cRich"),
                progress.cards().map { it.id }
            )
            assertEquals(
                "the pass answers with what landed, not with the seeds it did not upgrade",
                listOf("aRich", "cRich"),
                result.map { it.id }
            )
            assertEquals("two rows answered", 2, progress.landedCount())
            assertTrue(
                "and the pass is bounded: it did not sit on the 5s row " +
                    "(${elapsedMs(startedAt)}ms)",
                elapsedMs(startedAt) < 1_500L
            )
        }

    @Test
    fun `a pass whose rows all hang answers at its deadline with the seed still up`() =
        runBlocking {
            val startedAt = System.nanoTime()
            var publishes = 0

            val result =
                cwLocalItemsIncrementally(
                    rows = listOf("x"),
                    progress = CwLocalProgress(listOf("x" to card("xSeed"))),
                    deadlineAtMs = 120L,
                    nowMs = { elapsedMs(startedAt) },
                    keyOf = { row -> row },
                    publish = { publishes += 1 },
                    enrich = { row, _ ->
                        delay(5_000)
                        card("${row}Rich")
                    }
                )

            assertEquals("no row answered", 0, publishes)
            assertTrue("nothing landed", result.isEmpty())
            assertTrue(
                "the deadline ended the pass, not the row's 5s (${elapsedMs(startedAt)}ms)",
                elapsedMs(startedAt) < 1_500L
            )
        }

    // ── F5b: why the first publish is late ─────────────────────────────────

    @Test
    fun `the pass's summary names the first landing, the timeout and the deadline`() =
        runBlocking {
            val startedAt = System.nanoTime()
            val progress =
                CwLocalProgress(
                    listOf("fast" to card("fastSeed"), "slow" to card("slowSeed"))
                )

            cwLocalItemsIncrementally(
                rows = listOf("fast", "slow"),
                progress = progress,
                deadlineAtMs = 200L,
                nowMs = { elapsedMs(startedAt) },
                keyOf = { row -> row },
                publish = { },
                enrich = { row, timing ->
                    when (row) {
                        // Never answers inside the pass's deadline.
                        "slow" -> {
                            delay(5_000)
                            card("slowRich")
                        }
                        else -> {
                            progress.notePermit(timing, elapsedMs(startedAt))
                            progress.noteLookupSource(timing, "net")
                            delay(10)
                            card("fastRich")
                        }
                    }
                }
            )

            val summary = progress.summaryLine(rows = 2)
            assertTrue("the fast row landed: $summary", summary.contains("rows landed=1/2"))
            assertTrue(
                "and its landing is the pass's firstLand: $summary",
                Regex("firstLand=\\+(\\d+)ms").find(summary)
                    ?.groupValues
                    ?.get(1)
                    ?.toLong()
                    ?.let { it in 0L..150L } == true
            )
            assertTrue(
                "the row that never answered is a timeout: $summary",
                summary.contains("timeouts=1")
            )
            assertTrue(
                "the deadline fired, with the fast row on the slice: $summary",
                summary.contains("deadlineFired=yes") &&
                    summary.contains("landedAtDeadline=1")
            )
            assertTrue(
                "the cache the lookup answered from is counted: $summary",
                summary.contains("cacheHits=0 net=1")
            )
            assertTrue(
                "and the slowest row is named by its lookup time: $summary",
                summary.contains("slowest=fast:")
            )
        }

    @Test
    fun `a row's wait for a permit is recorded apart from its lookup`() =
        runBlocking {
            val startedAt = System.nanoTime()
            val progress = CwLocalProgress(listOf("queued" to card("queuedSeed")))

            cwLocalItemsIncrementally(
                rows = listOf("queued"),
                progress = progress,
                deadlineAtMs = 2_000L,
                nowMs = { elapsedMs(startedAt) },
                keyOf = { row -> row },
                publish = { },
                enrich = { _, timing ->
                    // The row stood in the permit queue before its lookup.
                    delay(40)
                    progress.notePermit(timing, elapsedMs(startedAt))
                    progress.noteLookupSource(timing, "mem")
                    card("queuedRich")
                }
            )

            val summary = progress.summaryLine(rows = 1)
            val waitMs =
                Regex("permitWaitP95=(\\d+)ms").find(summary)
                    ?.groupValues
                    ?.get(1)
                    ?.toLong()
            assertTrue(
                "the queue wait is recorded, and it is not the lookup: $summary",
                waitMs != null && waitMs >= 20L
            )
            assertTrue(
                "the lookup that followed is a cache hit, and no deadline fired: $summary",
                summary.contains("cacheHits=1 net=0") &&
                    summary.contains("deadlineFired=no")
            )
        }

    // ── F5c: the row timeout fires before the pass deadline ───────────────

    @Test
    fun `a row that never answers lands as its seed card before the pass deadline`() =
        runBlocking {
            val startedAt = System.nanoTime()
            val progress =
                CwLocalProgress(
                    listOf("a" to card("aSeed"), "b" to card("bSeed"))
                )
            val rowTimeoutMs = 100L

            val result =
                cwLocalItemsIncrementally(
                    rows = listOf("a", "b"),
                    progress = progress,
                    // F5c's ordering: the pass deadline is taken from the
                    // ENRICHMENT start, at the row timeout plus a margin. F5
                    // took it from beginCwBuild at the row timeout itself, so
                    // the pass cut the rows off before their own timeout could
                    // land them (the 6400 paste: landed=0/7).
                    deadlineAtMs = elapsedMs(startedAt) + rowTimeoutMs + 300L,
                    nowMs = { elapsedMs(startedAt) },
                    keyOf = { row -> row },
                    publish = { },
                    enrich = { row, timing ->
                        progress.noteStep(timing, CwRowStep.DETAIL, elapsedMs(startedAt))
                        // The F3 fallback, as the ViewModel's lambda runs it.
                        val enriched =
                            withTimeoutOrNull(rowTimeoutMs) {
                                delay(5_000L)
                                card("${row}Rich")
                            }
                        if (enriched == null) {
                            progress.noteTimedOut(timing, elapsedMs(startedAt))
                        }
                        enriched ?: card("${row}Seed")
                    }
                )

            val summary = progress.summaryLine(rows = 2)
            assertEquals(
                "a row that never answered still lands, as the seed card it was",
                listOf("aSeed", "bSeed"),
                result.map { it.id }
            )
            assertEquals("both rows are on the slice", 2, progress.landedCount())
            assertTrue("rows landed, not cut off: $summary", summary.contains("rows landed=2/2"))
            assertTrue("on their own timeouts: $summary", summary.contains("timeouts=2"))
            assertTrue(
                "and the pass deadline never had to cut them: $summary",
                summary.contains("deadlineFired=no")
            )
        }

    @Test
    fun `a pass deadline that does fire reports the rows that had already landed`() =
        runBlocking {
            val startedAt = System.nanoTime()
            val progress =
                CwLocalProgress(
                    listOf("a" to card("aSeed"), "b" to card("bSeed"))
                )

            cwLocalItemsIncrementally(
                rows = listOf("a", "b"),
                progress = progress,
                deadlineAtMs = elapsedMs(startedAt) + 250L,
                nowMs = { elapsedMs(startedAt) },
                keyOf = { row -> row },
                // The pass's tail is what is still out: both rows landed, and
                // the deadline ends the pass around their publish.
                publish = { delay(2_000L) },
                enrich = { row, timing ->
                    progress.noteStep(timing, CwRowStep.NEXTUP, elapsedMs(startedAt))
                    card("${row}Rich")
                }
            )

            val summary = progress.summaryLine(rows = 2)
            assertTrue("both rows landed: $summary", summary.contains("rows landed=2/2"))
            assertTrue("and the deadline did end the pass: $summary", summary.contains("deadlineFired=yes"))
            assertTrue(
                "with what had landed counted, not zero: $summary",
                summary.contains("landedAtDeadline=2")
            )
        }

    // ── F5c: which stage the row was slow in ───────────────────────────────

    @Test
    fun `the summary names the step a slow row was in`() =
        runBlocking {
            val startedAt = System.nanoTime()
            val progress =
                CwLocalProgress(
                    listOf(
                        "a" to card("aSeed"),
                        "b" to card("bSeed"),
                        "c" to card("cSeed")
                    )
                )

            cwLocalItemsIncrementally(
                rows = listOf("a", "b", "c"),
                progress = progress,
                deadlineAtMs = 2_000L,
                nowMs = { elapsedMs(startedAt) },
                keyOf = { row -> row },
                publish = { },
                enrich = { row, timing ->
                    progress.noteStep(timing, CwRowStep.DETAIL, elapsedMs(startedAt))
                    delay(if (row == "a") 120L else if (row == "b") 130L else 140L)
                    progress.noteStep(timing, CwRowStep.NEXTUP, elapsedMs(startedAt))
                    delay(1L)
                    card("${row}Rich")
                }
            )

            val summary = progress.summaryLine(rows = 3)
            val stepP50 =
                Regex("slowStep=detail:(\\d+)ms").find(summary)
                    ?.groupValues
                    ?.get(1)
                    ?.toLong()
            assertTrue(
                "the stage the rows spent their time in is named, with its p50: $summary",
                stepP50 != null && stepP50 >= 100L
            )
            assertTrue("and nothing was cut off in it: $summary", summary.contains("cutOff=0"))
        }

    @Test
    fun `a row the deadline cut off still names the step it was standing in`() =
        runBlocking {
            val startedAt = System.nanoTime()
            val progress =
                CwLocalProgress(
                    listOf(
                        "a" to card("aSeed"),
                        "b" to card("bSeed"),
                        "c" to card("cSeed")
                    )
                )

            cwLocalItemsIncrementally(
                rows = listOf("a", "b", "c"),
                progress = progress,
                deadlineAtMs = elapsedMs(startedAt) + 200L,
                nowMs = { elapsedMs(startedAt) },
                keyOf = { row -> row },
                publish = { },
                enrich = { row, timing ->
                    progress.noteStep(timing, CwRowStep.DETAIL, elapsedMs(startedAt))
                    progress.noteStep(timing, CwRowStep.SEASONS, elapsedMs(startedAt))
                    if (row == "c") {
                        delay(20L)
                    } else {
                        delay(5_000L)
                    }
                    card("${row}Rich")
                }
            )

            val summary = progress.summaryLine(rows = 3)
            assertTrue(
                "the cut-off rows' stage is named, not left as '-': $summary",
                summary.contains("slowStep=seasons:")
            )
            assertTrue(
                "with how many were in it when they were cut: $summary",
                summary.contains("cutOff=2")
            )
            assertTrue("one row landed: $summary", summary.contains("rows landed=1/3"))
        }

    // ── F5d: one clock for the row timing ─────────────────────────────────

    @Test
    fun `every row-timing field is an offset from the one clock`() =
        runBlocking {
            val startedAt = System.nanoTime()
            val progress =
                CwLocalProgress(
                    listOf(
                        "a" to card("aSeed"),
                        "b" to card("bSeed")
                    )
                )
            // The pass's clock: ms since the build started. The 6403 paste's
            // fields were ~1.13e9 because the helper was handed raw device
            // uptime while everything else was build-relative; a build-relative
            // clock is what the summary has to be readable against.
            val clock: () -> Long = { elapsedMs(startedAt) }

            cwLocalItemsIncrementally(
                rows = listOf("a", "b"),
                progress = progress,
                deadlineAtMs = clock() + 5_000L,
                nowMs = clock,
                keyOf = { row -> row },
                publish = { },
                enrich = { row, timing ->
                    // The wait for a permit is a real wait, timed on the same
                    // clock it is measured against.
                    delay(40L)
                    progress.notePermit(timing, clock())
                    progress.noteStep(timing, CwRowStep.DETAIL, clock())
                    delay(if (row == "a") 80L else 120L)
                    progress.noteStep(timing, CwRowStep.NEXTUP, clock())
                    delay(1L)
                    card("${row}Rich")
                }
            )

            val summary = progress.summaryLine(rows = 2)
            val firstLand = summary.longAfter("firstLand=\\+")
            assertTrue(
                "firstLand is the first row's own landing delay, not the " +
                    "device's uptime: $summary",
                firstLand != null && firstLand in 60L..1_500L
            )
            val lookup =
                Regex("slowest=[a-z]+:(\\d+)ms").find(summary)
                    ?.groupValues
                    ?.get(1)
                    ?.toLong()
            assertTrue(
                "a lookup is small and non-negative when both ends share the clock: $summary",
                lookup != null && lookup in 0L..1_500L
            )
            val wait =
                summary.longAfter("permitWaitP95=")
            assertTrue(
                "the permit wait is the 40ms it really spent, not a coerced 0: $summary",
                wait != null && wait >= 30L
            )
            val stepP50 =
                Regex("slowStep=detail:(\\d+)ms").find(summary)
                    ?.groupValues
                    ?.get(1)
                    ?.toLong()
            assertTrue(
                "and the slow stage's p50 is a real duration, not device uptime: $summary",
                stepP50 != null && stepP50 in 60L..8_000L
            )
            val p50 = summary.longAfter("p50=")
            assertTrue(
                "the row totals stay real too - both ends were on that clock: $summary",
                p50 != null && p50 in 60L..1_500L
            )
        }

    // ── The seasons step's own cost (F5e) ──────────────────────────────────

    @Test
    fun `a cold one-season show costs one season request, not the probe's eight`() =
        runBlocking {
            val fetched = mutableListOf<Int>()
            val fetch: suspend (Int) -> List<String>? = { season ->
                fetched.add(season)
                if (season == 1) listOf("s1e1") else emptyList()
            }

            // F5e: the row already holds TMDB's detail, which names the seasons
            // the show has. The walk asks for those.
            val declared =
                cwWalkSeasons(
                    declaredSeasons = cwSeasonsToWalk(listOf(CwSeasonDecl(number = 1, episodeCount = 10))),
                    batchSize = 4,
                    probeThrough = 50,
                    fetch = fetch
                )
            assertEquals("one declared season is one lookup", listOf(1), fetched)
            assertEquals(1, declared.requests)
            assertEquals(setOf(1), declared.episodesBySeason.keys)
            assertFalse(declared.failed)

            // The old walk: the same show, no declaration, probed 1..50 - a whole
            // empty batch past the end, four 404s per batch and eight requests
            // for one real season. This is the count the 6407 paste paid per row.
            fetched.clear()
            val probe =
                cwWalkSeasons(
                    declaredSeasons = null,
                    batchSize = 4,
                    probeThrough = 50,
                    fetch = fetch
                )
            assertEquals(
                "the probe spends a second batch finding the end of a one-season show",
                8,
                probe.requests
            )
            assertEquals(8, fetched.size)
        }

    @Test
    fun `a declared season whose listing lags is still fetched, and the walk goes on`() =
        runBlocking {
            val fetched = mutableListOf<Int>()
            val walk =
                cwWalkSeasons(
                    declaredSeasons = listOf(1, 2),
                    batchSize = 4,
                    probeThrough = 50
                ) { season ->
                    fetched.add(season)
                    if (season == 1) emptyList() else listOf("s2e1")
                }

            assertEquals("both declared seasons are asked for", listOf(1, 2), fetched.sorted())
            assertFalse("an empty listing is not the end of a declared walk", walk.failed)
            assertEquals(emptyList<String>(), walk.episodesBySeason[1])
            assertEquals(listOf("s2e1"), walk.episodesBySeason[2])
        }

    @Test
    fun `a season that did not answer is a hole, and stops the walk`() =
        runBlocking {
            val walk =
                cwWalkSeasons(
                    declaredSeasons = listOf(1, 2, 3),
                    batchSize = 4,
                    probeThrough = 50
                ) { season -> if (season == 2) null else listOf("s${season}e1") }

            assertTrue("a failed season makes the map a map with a hole in it", walk.failed)
            assertEquals(setOf(1), walk.episodesBySeason.keys)
        }

    @Test
    fun `the walk asks for the seasons the detail declares, and nothing else`() {
        assertEquals(
            listOf(1, 2),
            cwSeasonsToWalk(
                listOf(CwSeasonDecl(1, 8), CwSeasonDecl(2, 10))
            )
        )
        assertEquals(
            "specials are not a season of the show",
            listOf(1),
            cwSeasonsToWalk(listOf(CwSeasonDecl(0, 12), CwSeasonDecl(1, 8)))
        )
        assertEquals(
            "a season TMDB declares with zero episodes is announced, not watchable",
            listOf(1),
            cwSeasonsToWalk(listOf(CwSeasonDecl(1, 8), CwSeasonDecl(2, 0)))
        )
        assertEquals(
            "a season with no declared count is kept - unknown is not empty",
            listOf(1, 2),
            cwSeasonsToWalk(listOf(CwSeasonDecl(1, 8), CwSeasonDecl(2, null)))
        )
        assertEquals(
            "the next episode to air can name a season the summary has not listed yet",
            listOf(1, 2),
            cwSeasonsToWalk(listOf(CwSeasonDecl(1, 8)), nextEpisodeSeason = 2)
        )
        assertEquals(
            "declared out of order is walked in order",
            listOf(1, 2),
            cwSeasonsToWalk(listOf(CwSeasonDecl(2, 10), CwSeasonDecl(1, 8)))
        )
        assertTrue(
            "no declaration keeps the old probe",
            cwSeasonsToWalk(null) == null && cwSeasonsToWalk(emptyList()) == null
        )
    }

    @Test
    fun `the summary names the call inside the seasons step`() {
        val progress = CwLocalProgress(listOf("a" to card("aSeed")))
        val timing = progress.beginRow("a", atMs = 0L)
        progress.noteStep(timing, CwRowStep.SEASONS, atMs = 0L)
        progress.noteSeasonCall(timing, CwSeasonCall.PRELOAD, 120L)
        progress.noteSeasonCall(timing, CwSeasonCall.RESOLVE, 7_409L)
        progress.noteSeasonCall(timing, CwSeasonCall.EPISODES, 40L)
        progress.place("a", card("aRich"), atMs = 7_600L)

        val summary = progress.summaryLine(rows = 1)
        assertTrue(
            "the step's slowest call is named: $summary",
            summary.contains("slowCall=resolve:7409ms")
        )
        assertTrue(
            "and the step is still named beside it: $summary",
            summary.contains("slowStep=seasons:7600ms")
        )
        assertTrue(
            "a pass with no season call recorded says so rather than inventing one",
            CwLocalProgress(listOf("a" to card("aSeed"))).summaryLine(rows = 1)
                .contains("slowCall=-")
        )
    }

    // ── The publisher's shape ──────────────────────────────────────────────

    @Test
    fun `an answered row upgrades its seed card in place, and a dropped row leaves`() {
        val progress =
            CwLocalProgress(
                listOf(
                    "a" to card("aSeed"),
                    "b" to card("bSeed"),
                    "c" to card("cSeed")
                )
            )

        assertTrue(
            "the first landing publishes on its own",
            progress.place("b", card("bRich"), atMs = 10L)
        )
        assertEquals(
            "the upgraded row keeps the position the seed gave it",
            listOf("aSeed", "bRich", "cSeed"),
            progress.cards().map { it.id }
        )

        assertFalse(
            "the second landing is inside the first batch",
            progress.place("a", card("aRich"), atMs = 20L)
        )
        assertEquals(
            listOf("aRich", "bRich", "cSeed"),
            progress.cards().map { it.id }
        )

        progress.remove("c", atMs = 30L)
        assertEquals(
            "a dropped row does not sit on the rail with an un-upgraded seed card",
            listOf("aRich", "bRich"),
            progress.cards().map { it.id }
        )
    }

    @Test
    fun `one publish per batch after the first landing`() {
        assertFalse("nothing landed", cwLocalPublishDue(0))
        assertTrue("the first landing publishes at once", cwLocalPublishDue(1))
        assertFalse(cwLocalPublishDue(2))
        assertFalse(cwLocalPublishDue(CW_LOCAL_PUBLISH_BATCH - 1))
        assertTrue(cwLocalPublishDue(CW_LOCAL_PUBLISH_BATCH))
        assertFalse(cwLocalPublishDue(CW_LOCAL_PUBLISH_BATCH + 1))
        assertTrue(cwLocalPublishDue(CW_LOCAL_PUBLISH_BATCH * 2))
    }

    // ── The pass's wiring ──────────────────────────────────────────────────

    private val root: File by lazy {
        val prefixes = listOf("", "app/")
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            prefixes.forEach { prefix ->
                val candidate = File(dir, "${prefix}src/main/java")
                if (candidate.isDirectory) return@lazy candidate
            }
            dir = dir.parentFile
        }
        throw AssertionError(
            "main source root not found walking up from " + System.getProperty("user.dir")
        )
    }

    private fun source(path: String): String {
        val file = File(root, path)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

    @Test
    fun `the upNext pass publishes local cards through the bounded helper`() {
        val viewModel = source("com/kennyb1201/kbstream/ui/home/HomeViewModel.kt")
        val start = viewModel.indexOf("private fun observeUpNext()")
        assertTrue("the upNext pass is missing", start >= 0)
        val end = viewModel.indexOf("private suspend fun dropSupersededResumeRows(", start)
        assertTrue("the pass's end is missing", end > start)
        val pass = viewModel.substring(start, end)

        assertTrue(
            "the local rows go through the incremental build",
            pass.contains("cwLocalItemsIncrementally(")
        )
        // F5c: the deadline is taken from the ENRICHMENT start, at the row
        // timeout plus a margin, so the row's own timeout fires first. Pinned
        // as properties of the expression rather than its layout: against the
        // old wiring (`cwBuildStartedAtMs + UP_NEXT_PASS_DEADLINE_MS`) the
        // first and third of these fail. F5d moved the expression onto the
        // pass's one clock (`cwClockMs()`); the pin accepts either spelling of
        // the enrichment-start clock so it keeps testing the F5c ordering, not
        // the F5d shape - the clock's single definition is pinned below.
        val deadlineExpr =
            pass.substringAfter("deadlineAtMs =").substringBefore("nowMs =")
        assertTrue(
            "the pass deadline is measured from the enrichment start, not the build: " +
                deadlineExpr,
            !deadlineExpr.contains("cwBuildStartedAtMs") &&
                (
                    deadlineExpr.contains("cwClockMs()") ||
                        deadlineExpr.contains("android.os.SystemClock.elapsedRealtime()")
                    )
        )
        assertTrue(
            "and it is the row timeout plus a margin, so the row timeout fires first: " +
                deadlineExpr,
            deadlineExpr.contains("UP_NEXT_ROW_ENRICH_TIMEOUT_MS") &&
                deadlineExpr.contains("UP_NEXT_PASS_DEADLINE_MARGIN_MS")
        )
        // F5d (2026-11-26): ONE clock for the row timing. The pass used to hand
        // the helper raw `elapsedRealtime()` as `nowMs` and stamp steps,
        // permits and timeouts build-relative, so every field subtracting
        // across the bases read as the device's 13-day uptime (firstLand,
        // slowest, slowStep) or as a coerced 0 (permitWaitP95). Against that
        // wiring the single-stamp pin below finds five, and each of the named
        // stamps fails.
        assertTrue(
            "the pass defines its clock once, as an offset from the build's start",
            pass.contains("val cwClockMs: () -> Long = {")
        )
        assertEquals(
            "and every row-timing stamp goes through it, not a second base",
            1,
            Regex("android\\.os\\.SystemClock\\.elapsedRealtime\\(\\) - cwBuildStartedAtMs")
                .findAll(pass)
                .count()
        )
        assertTrue(
            "the helper reads that clock, not raw uptime",
            pass.contains("nowMs = cwClockMs,") &&
                !pass.contains("nowMs = { android.os.SystemClock")
        )
        listOf(
            "localProgress.notePermit(rowClock, cwClockMs())",
            "localProgress.noteTimedOut(rowClock, cwClockMs())",
            "localProgress.noteStep(timing, step, cwClockMs())"
        ).forEach { stamp ->
            assertTrue("the pass stamps through the clock: $stamp", pass.contains(stamp))
        }

        assertTrue(
            "each landing publishes the slice it has",
            pass.contains("arrivals.publishLocalCards(cards)")
        )
        assertTrue(
            "and the final publish replaces it once every answered row has landed",
            pass.contains("arrivals.publishLocalCards(localCards)")
        )
        assertTrue(
            "the publisher is built before the rows answer - it is what they publish through",
            pass.indexOf("val arrivals =") in 0 until pass.indexOf("cwLocalItemsIncrementally(")
        )
        assertTrue(
            "the per-row timeout F3 is untouched",
            pass.contains("withTimeoutOrNull(UP_NEXT_ROW_ENRICH_TIMEOUT_MS)")
        )
        assertTrue(
            "and so is the seed that shows first",
            pass.indexOf("seedUpNextFromHistory(history, source = \"seed\")") <
                pass.indexOf("cwLocalItemsIncrementally(")
        )

        // F5b (2026-11-26): the diagnostic that says why the first publish is
        // late. One summary per build, from the pass's own progress.
        assertTrue(
            "the pass emits its per-row summary once, when it ends",
            pass.contains("localProgress.summaryLine(rows = history.size)")
        )
        assertTrue(
            "timed from the build's clock, on the trace the paste already reads",
            Regex(
                "CwLoadTrace\\.note\\(\\s*cwBuildId,\\s*cwClockMs\\(\\),\\s*" +
                    "localProgress\\.summaryLine"
            ).containsMatchIn(pass)
        )
        assertTrue(
            "the permit wait is timed where the permit is taken",
            pass.contains("localProgress.notePermit(")
        )
        assertTrue(
            "and the detail lookup reports which cache answered it",
            pass.contains("localProgress.noteLookupSource(rowClock, source)")
        )
        assertTrue(
            "a row that kept its seed card is counted as a timeout",
            pass.contains("localProgress.noteTimedOut(")
        )

        // F5c (2026-11-26): the stages a row walks through, recorded as it
        // enters each one, so the summary can name the slow one.
        assertTrue(
            "the row's stages are recorded through the pass's own progress",
            pass.contains("localProgress.noteStep(")
        )
        listOf(
            "CwRowStep.DETAIL",
            "CwRowStep.SEASONS",
            "CwRowStep.WATCHED",
            "CwRowStep.NEXTUP"
        ).forEach { step ->
            assertTrue("the pass records $step", pass.contains(step))
        }
        assertTrue(
            "and the summary carries the stage it names",
            pass.contains("localProgress.summaryLine(rows = history.size)")
        )

    }

    // ── The seasons step's wiring (F5e) ────────────────────────────────────

    @Test
    fun `the local row hands its show's declared seasons to the resolution`() {
        val viewModel = source("com/kennyb1201/kbstream/ui/home/HomeViewModel.kt")
        val start = viewModel.indexOf("private fun observeUpNext()")
        assertTrue("the upNext pass is missing", start >= 0)
        val end = viewModel.indexOf("private suspend fun dropSupersededResumeRows(", start)
        assertTrue("the pass's end is missing", end > start)
        val pass = viewModel.substring(start, end)

        assertTrue(
            "the pass reads the seasons off the detail it already holds",
            pass.contains("cwSeasonsToWalk(") &&
                pass.contains("nextEpisodeSeason = tmdbDetail?.nextEpisodeToAir?.seasonNumber")
        )
        assertTrue(
            "and hands them to the resolution that walks them",
            pass.contains("declaredSeasons = declaredSeasons")
        )
        // F5f moved the walk onto the near window, so the seasons it walks are
        // the caller's list narrowed to that window (see the pins below).
        assertTrue(
            "the resolution walks only the seasons it is handed",
            viewModel.contains("cwWalkSeasons(\n            declaredSeasons = seasonsToWalk,")
        )
        assertTrue(
            "the totals walk record what the map holds, so a numbering gap is not the end",
            viewModel.contains("for (season in seasonsToCount.keys.sorted())") &&
                viewModel.contains(
                    "val seasonsToCount: Map<Int, List<ResolvedEpisode>> =\n" +
                        "        if (countingFromWalk) seasonEpisodesBySeason else emptyMap()"
                )
        )
        assertTrue(
            "the remaining count is bounded by the declared last season too",
            viewModel.contains("lastDeclaredSeason: Int? = null") &&
                viewModel.contains("lastDeclaredSeason = lastDeclaredSeason")
        )

        // F5e's other half: the summary names the CALL inside the step, so a
        // paste that still shows `slowStep=seasons` says which one.
        listOf(
            "localProgress.noteSeasonCall(",
            "CwSeasonCall.PRELOAD",
            "CwSeasonCall.RESOLVE",
            "CwSeasonCall.EPISODES"
        ).forEach { pin ->
            assertTrue("the local row times its seasons calls: $pin", pass.contains(pin))
        }
        assertTrue(
            "and each duration is taken on the pass's one clock",
            Regex("cwClockMs\\(\\) - (preload|resolve|episodes)StartedAtMs").findAll(pass).count() == 3
        )
    }

    // ── The resolve call off the row's clock (F5f) ─────────────────────────

    @Test
    fun `the near window is the viewer's season and the two after it`() {
        assertEquals(
            "a show whose detail declares seasons 1..10 around a viewer in S3",
            listOf(3, 4, 5),
            cwNearSeasons(from = 3, declaredSeasons = (1..10).toList())
        )
        assertEquals(
            "and the window stops at the show's last declared season",
            listOf(8, 9),
            cwNearSeasons(from = 8, declaredSeasons = (1..9).toList())
        )
        assertEquals(
            "a tracker position past the declared end still probes itself - a" +
                " stale detail is not a finished show",
            listOf(12),
            cwNearSeasons(from = 12, declaredSeasons = (1..9).toList())
        )
        assertEquals(
            "a detail that named no seasons leaves the window as the seasons to ask for",
            listOf(2, 3, 4),
            cwNearSeasons(from = 2, declaredSeasons = null)
        )
        assertEquals(
            "and a zero position (a row with no episode yet) starts at season 1",
            listOf(1, 2, 3),
            cwNearSeasons(from = 0, declaredSeasons = null)
        )
    }

    @Test
    fun `the furthest watched episode comes from the watched state, not a walk`() {
        assertEquals(
            "Simkl pairs and local keys are one set, furthest wins",
            3 to 4,
            cwFurthestWatchedEpisode(
                parentId = "tt1",
                simklWatchedEpisodes = setOf(1 to 1, 3 to 2),
                watchedEpisodeKeys = setOf("tt1:3:4", "tt1:2:9")
            )
        )
        assertEquals(
            "keys anchored to the show's other id flavor are not this show's position",
            2 to 1,
            cwFurthestWatchedEpisode(
                parentId = "tt1",
                simklWatchedEpisodes = emptySet(),
                watchedEpisodeKeys = setOf("tmdb:9:5:7", "tt1:2:1")
            )
        )
        assertEquals(
            "season 0 (specials) is not a position Continue Watching resumes from",
            1 to 2,
            cwFurthestWatchedEpisode(
                parentId = "tt1",
                simklWatchedEpisodes = emptySet(),
                watchedEpisodeKeys = setOf("tt1:0:9", "tt1:1:2")
            )
        )
        assertEquals(
            "nothing watched is null, not season 1 episode 1",
            null,
            cwFurthestWatchedEpisode("tt1", emptySet(), emptySet())
        )
    }

    @Test
    fun `the local row resolves near its position and leaves the counts to a later pass`() {
        val viewModel = source("com/kennyb1201/kbstream/ui/home/HomeViewModel.kt")
        val start = viewModel.indexOf("private fun observeUpNext()")
        assertTrue("the upNext pass is missing", start >= 0)
        val end = viewModel.indexOf("private suspend fun dropSupersededResumeRows(", start)
        assertTrue("the pass's end is missing", end > start)
        val pass = viewModel.substring(start, end)

        assertTrue(
            "the row asks for the near-position resolve",
            pass.contains("nearPositionOnly = true")
        )
        assertTrue(
            "and a show without counts is remembered for the deferred walk",
            pass.contains("deferredTotals[tmdbId] =") &&
                pass.contains("PendingShowTotals(")
        )
        assertTrue(
            "the deferred walk runs after the local slice was published",
            pass.indexOf("arrivals.publishLocalCards(localCards)") <
                pass.indexOf("resolveSeriesTargetFromSharedWatchedState(\n                                                parentId = pending.parentId")
        )
        assertTrue(
            "the upgrade republishes the local slice through the same publisher",
            pass.contains("arrivals.publishLocalCards(upgraded)")
        )
        assertTrue(
            "and it only patches the cards, it does not rebuild them",
            pass.contains("card.copy(") &&
                pass.contains("episodesWatched =") &&
                pass.contains("episodesTotal = resolved.episodesTotal")
        )
        assertTrue(
            "a newer pass keeps its rail: the upgrade is gated on the request",
            pass.contains("isLatestUpNextRequest(requestVersion)")
        )

        // The resolver's half: the window bounds the walk, the counts are
        // skipped (they would be a partial "X of Y"), and a near walk never
        // becomes the show's cached map.
        assertTrue(
            "the resolution takes the near-position mode",
            viewModel.contains("nearPositionOnly: Boolean = false")
        )
        assertTrue(
            "the walk is the near window in that mode",
            viewModel.contains("cwNearSeasons(") &&
                viewModel.contains("declaredSeasons = seasonsToWalk,")
        )
        assertTrue(
            "the position comes from the watched state, before any walk",
            viewModel.contains("cwFurthestWatchedEpisode(")
        )
        assertTrue(
            "the totals walk does not count a near-only map",
            viewModel.contains("if (countingFromWalk) seasonEpisodesBySeason else emptyMap()")
        )
        assertTrue(
            "and a near-only map is never cached as the show's seasons",
            viewModel.contains("!seasonWalkFailed && !nearPositionOnly")
        )
        assertTrue(
            "the remaining count is left to the deferred pass",
            viewModel.contains("if (!countingFromWalk) {") &&
                viewModel.contains("if (countingFromWalk) 0 else null")
        )
    }

    /** The number after [prefix], if the summary carries one. */
    private fun String.longAfter(prefix: String): Long? =
        Regex("$prefix(\\d+)").find(this)?.groupValues?.get(1)?.toLongOrNull()
}
