package com.kennyb1201.kbstream.ui.home

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What bounds a Continue Watching load, and what the paste can say about it.
 *
 * The field report (spec 2026-11-24) is three failures in one sentence - full,
 * three minutes, never - and every link below is silently revertable while the
 * rail still looks right on a warm device:
 *
 *  - the rail seeds from the rows the pass just read, BEFORE the per-row
 *    enrichment, so an empty rail cannot wait for TMDB (F2);
 *  - one row's enrichment cannot hold the whole rail past the cap, and its
 *    fallback is the card the seed already publishes (F3);
 *  - the art memo keeps its hits across builds and drops only its misses, which
 *    is the half the clear existed for (F4);
 *  - the load trace carries a build id per record, and reaches the paste, so
 *    "never loaded" and "loaded without art" stop reading the same (F1);
 *  - the two segments that were measured from the wrong origin - the lock wait
 *    and the snapshot read - are timed on their own now (F5).
 */
class CwLoadBoundsContractTest {

    private val root: File by lazy { findSourceRoot() }

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

    private fun source(path: String): String {
        val file = File(root, path)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

    private fun squash(text: String): String = text.replace(Regex("\\s+"), " ")

    private val viewModel = source("com/kennyb1201/kbstream/ui/home/HomeViewModel.kt")
    private val home = source("com/kennyb1201/kbstream/ui/home/HomeScreen.kt")
    private val upNext = source("com/kennyb1201/kbstream/ui/home/HomeUpNext.kt")
    private val diagnostics = source("com/kennyb1201/kbstream/data/reporting/Diagnostics.kt")
    private val trace = source("com/kennyb1201/kbstream/data/reporting/CwLoadTrace.kt")

    /** The upNext pass, from its first line to the fold that ends it. */
    private val pass: String by lazy {
        val start = viewModel.indexOf("private fun observeUpNext()")
        assertTrue("the upNext pass is missing", start >= 0)
        val end = viewModel.indexOf("private suspend fun dropSupersededResumeRows(", start)
        assertTrue("the pass's end is missing", end > start)
        viewModel.substring(start, end)
    }

    // ── F2: the rail paints from the rows, not from the enrichment ─────────

    @Test
    fun `the upNext pass seeds from its own history read before it enriches`() {
        val seedAt = pass.indexOf("seedUpNextFromHistory(history, source = \"seed\")")
        val enrichAt = pass.indexOf("withTimeoutOrNull(UP_NEXT_ROW_ENRICH_TIMEOUT_MS)")
        assertTrue("the pass does not seed its own rows", seedAt >= 0)
        assertTrue("the per-row enrichment is missing", enrichAt > 0)
        assertTrue(
            "the seed has to land first: the rail is empty until it does",
            seedAt < enrichAt
        )
        assertTrue(
            "and the seed must not clobber a list that is on screen",
            squash(viewModel).contains(
                "if (_upNext.value.isEmpty()) { val seeded = applyContinueWatchingDismissals("
            )
        )
    }

    @Test
    fun `the init seed and the per-pass seed are one function`() {
        assertTrue(
            "the init read hands its rows to the shared seed",
            squash(viewModel).contains(
                "seedUpNextFromHistory(history, source = \"instant\")"
            )
        )
        assertFalse(
            "the card building must not be duplicated back into the init path",
            squash(viewModel).contains(
                "publishInstantUpNextSnapshot() { val visibleHistory = dropSupersededResumeRows"
            )
        )
    }

    // ── F3: one lookup cannot hold the rail ────────────────────────────────

    @Test
    fun `a row's enrichment is capped, and its fallback is the seeded card`() {
        assertTrue(
            "no cap on a row's lookup",
            pass.contains("withTimeoutOrNull(UP_NEXT_ROW_ENRICH_TIMEOUT_MS)")
        )
        assertTrue(
            "a timed-out row keeps a card",
            pass.contains("enriched ?: instantContinueWatchingItem(entry)")
        )
        assertTrue(
            "and the timeout is counted, so a thin card is not read as a TMDB miss",
            pass.contains("PerfTrace.recordCount(\"home.cwRowTimeout\", 1)")
        )
    }

    // ── F4: hits survive a build, misses do not ────────────────────────────

    @Test
    fun `the art memo keeps its hits and drops only its misses`() {
        val build = viewModel.substringAfter("private suspend fun loadRailsInternal(")
        assertFalse(
            "clearing the memo whole is what re-asked TMDB for 108 cards a build",
            build.contains("landscapeLookupMemo.clear()")
        )
        assertTrue(
            "the misses still have to be re-asked (the Wi-Fi-coming-up case)",
            squash(build).contains(
                "landscapeLookupMemo.entries.removeAll { entry -> " +
                    "entry.value.first == null && entry.value.second == null }"
            )
        )
    }

    // ── F1: one load, one timeline, in the paste ───────────────────────────

    @Test
    fun `the load's records carry the build they belong to`() {
        assertTrue(
            "the trace has to key its lines by load",
            squash(trace).contains("lines.addLast(\"build=\$buildId t=+\${atMs}ms \$what\")")
        )
        assertTrue(
            "each pass starts its own build",
            squash(viewModel).contains("beginCwBuild()")
        )
        assertTrue(
            "the publish, the first paint and the settle each report",
            squash(viewModel).contains("noteCwPublished(items, source = \"publish\")") &&
                squash(viewModel).contains("fun noteContinueWatchingFirstPaint(") &&
                squash(viewModel).contains("\"artTimeout cards=\$cards art=\$withArt late=\$late\"")
        )
        assertTrue(
            "and the rail reports the paint it actually reached, with its art count",
            squash(home).contains(
                "onFirstPaint?.invoke( cards.size, cards.count { item -> upNextCardHasArt(item) } )"
            )
        )
        assertTrue(
            "the paint must reach the ViewModel above the rail",
            squash(home).contains(
                "onFirstPaint = { cards, withArt -> " +
                    "viewModel.noteContinueWatchingFirstPaint(cards, withArt) }"
            )
        )
        assertTrue(
            "the art count is the card's own fallback chain, not a second reading",
            squash(upNext).contains(
                "internal fun upNextCardHasArt(item: UpNextItem): Boolean = " +
                    "!(item.episodeThumbnail ?: item.backdrop ?: item.poster).isNullOrBlank()"
            )
        )
    }

    @Test
    fun `the trace reaches COPY DIAGNOSTICS`() {
        assertTrue(
            "a load nobody can read is a load nobody can fix",
            squash(diagnostics).contains(
                "CwLoadTrace.lines().forEach { report.appendLine(\"cwload: \$it\") }"
            )
        )
    }

    // ── F5: the segments, measured from the right origin ───────────────────

    @Test
    fun `the lock wait and the snapshot read are timed apart`() {
        assertTrue(
            "the build's start is before the lock, so a wait for a predecessor is " +
                "indistinguishable from work until it is its own label",
            squash(viewModel).contains("PerfTrace.record( \"home.mutexWait\"")
        )
        assertTrue(
            "the snapshot read runs off the main thread and carries its own duration",
            squash(viewModel).contains("PerfTrace.timedSuspend(\"home.snapshotLoad\") { " +
                "withContext(Dispatchers.Default) { HomeCatalogSnapshot.load(")
        )
        assertTrue(
            "and the catalog order build beside it is timed too",
            squash(viewModel).contains(
                "PerfTrace.timed(\"home.catalogOrder\") { catalogOrderByKey(pendingCatalogs) }"
            )
        )
    }
}
