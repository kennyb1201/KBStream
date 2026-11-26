package com.kennyb1201.kbstream.ui.home

import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
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
        assertTrue(
            "and the pass has a deadline taken from its own clock, not from the " +
                "call after the history read",
            pass.contains("deadlineAtMs = cwBuildStartedAtMs + UP_NEXT_PASS_DEADLINE_MS")
        )
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
            pass.contains("android.os.SystemClock.elapsedRealtime() - cwBuildStartedAtMs,\n                            localProgress.summaryLine")
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
            pass.contains("localProgress.noteTimedOut(rowClock)")
        )
    }
}
