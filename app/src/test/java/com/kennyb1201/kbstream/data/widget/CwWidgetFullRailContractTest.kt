package com.kennyb1201.kbstream.data.widget

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The widget carries the WHOLE Continue Watching rail, not only its resume half
 * (spec 2026-11-16).
 *
 * Kenny's ask: "our cw widget only shows resume can we make it a full cw widget
 * with everything same with the one for launcher on tv". Two mechanisms narrowed
 * it: a `MAX_ROWS = 8` of the widget's own (the eight newest cards are resume
 * cards, so the next-up and tracker cards fell below the cut), and the rebuild
 * writing the history-derived half alone (so every round and every tap replaced
 * the full snapshot with resume-only rows until Home published again).
 *
 * - F1: no row cap - the snapshot is the rail's list.
 * - F2: a rebuild MERGES: fresh resume rows from local history, plus the
 *   non-resume cards the last Home publish carried, re-checked against the same
 *   hidden and unaired rules. It still invents nothing.
 * - F3: a non-resume row draws its chip, title and subtitle, and no progress bar
 *   and no time left.
 * - F4: a non-resume row's tap opens its Detail; a resume row's tap resumes.
 *
 * [CwWidgetRulesTest] pins what a row prints and [CwWidgetWiringContractTest] the
 * launcher shape; this pins the half that spans the publisher, the row model and
 * the route.
 */
class CwWidgetFullRailContractTest {

    private val publisher by lazy { source(DATA_WIDGET + "CwWidgetPublisher.kt") }
    private val rules by lazy { source(DATA_WIDGET + "CwWidgetRules.kt") }
    private val store by lazy { source(DATA_WIDGET + "CwWidgetStore.kt") }
    private val json by lazy { source(DATA_WIDGET + "CwWidgetSnapshotJson.kt") }
    private val service by lazy { source(WIDGET + "CwWidgetService.kt") }
    private val route by lazy { source(WIDGET + "CwWidgetLaunchRoute.kt") }
    private val homeUpNext by lazy { source("com/kennyb1201/kbstream/ui/home/HomeUpNext.kt") }
    private val tvHome by lazy { source("com/kennyb1201/kbstream/ui/home/HomeScreen.kt") }
    private val phoneHome by lazy {
        source("com/kennyb1201/kbstream/ui/mobile/HomeScreenMobile.kt")
    }

    private val renderRows by lazy {
        publisher.substringAfter("private fun rows(")
            .substringBefore("private fun rowOf(")
    }

    private val carried by lazy {
        publisher.substringAfter("private fun carriedForwardRows(")
            .substringBefore("/**\n     * The rows the widget shows for [items]")
    }

    // ── F1: no cap of the widget's own ───────────────────────────────────

    @Test
    fun `the widget publishes the rail's whole list`() {
        assertFalse(
            "the number 8 is gone from the rules: no cap constant may exist",
            rules.contains("MAX_ROWS")
        )
        assertFalse(
            "and the row builder truncates nothing",
            renderRows.contains("take(")
        )
        assertTrue(
            "every card that survives the read-site rules becomes a row",
            renderRows.contains("upNextContinueWatchingDecision(item, hidden, showNames, today)") &&
                renderRows.contains(".mapNotNull(::rowOf)")
        )
        assertFalse(
            "nothing else in the widget path caps the list either",
            publisher.contains("MAX_ROWS") || store.contains("MAX_ROWS") ||
                json.contains("MAX_ROWS")
        )
    }

    // ── F2: the rebuild merges, and invents nothing ──────────────────────

    @Test
    fun `the rebuild keeps the cards local history cannot reproduce`() {
        assertTrue(
            "the one rebuild turns the carry on",
            publisher.contains("carryNonResume = true")
        )
        assertTrue(
            "and it is a merge, not a second writer: the same publish writes it",
            publisher.contains("rowsForPublish(items, showNames, application, carryNonResume)") &&
                publisher.contains("return fresh + carriedForwardRows(fresh, showNames, context)")
        )
        assertTrue(
            "only NON-resume rows are carried: the resume half was just rebuilt from " +
                "live history, and a carried resume row could only be a stale copy",
            carried.contains("!row.isResume")
        )
        assertTrue(
            "a show the rebuild already reproduced cannot appear twice",
            carried.contains("row.parentKey !in freshParents") &&
                carried.contains("val freshParents = fresh.map { it.parentKey }.toSet()")
        )
        assertTrue(
            "and the carried rows go back through the rail's hidden and unaired rules",
            carried.contains("row.droppedFromRail(hidden, showNames, todayEpochDay)") &&
                carried.contains("HiddenTitles.keys(context)")
        )
    }

    @Test
    fun `the carry-forward reads the snapshot and nothing else`() {
        listOf("Simkl", "Trakt", "MdbList", "AddonRepository", "TmdbRepository", "http")
            .forEach { forbidden ->
                assertFalse(
                    "a rebuild must not fetch: the carried cards are the ones Home " +
                        "already published ($forbidden)",
                    carried.contains(forbidden)
                )
            }
        assertTrue(
            "the one source of carried rows is the last snapshot",
            carried.contains("CwWidgetStore.read(context).rows")
        )
        assertFalse(
            "and it never writes: the publish below it is the writer",
            carried.contains("CwWidgetStore.write(")
        )
    }

    @Test
    fun `a hidden or unaired carried row drops on the rail's rules`() {
        val row = CwWidgetRow(
            parentType = "series",
            parentId = "tt4192786",
            cardId = "nextup:tt4192786",
            tmdbId = 73626,
            showTitle = "Forbidden History",
            isResume = false,
            season = 4,
            episode = 2
        )
        assertFalse(
            "with nothing hidden and no air record, the row stays",
            row.droppedFromRail(hiddenKeys = emptySet(), todayEpochDay = 20_000L)
        )
        assertTrue(
            "a hide stored under the card's TMDB series key drops it",
            row.droppedFromRail(hiddenKeys = setOf("series::tmdb:73626"), todayEpochDay = 20_000L)
        )
        assertTrue(
            "and a hide stored under the card's own rail id drops it too (a non-TMDB id " +
                "is keyed without the media type, so the spelling cannot decide the hide)",
            row.droppedFromRail(hiddenKeys = setOf("id::nextup:tt4192786"), todayEpochDay = 20_000L)
        )

        val tracker = row.copy(
            cardId = "simkl:1218",
            ownEpisodeAirEpochDay = 20_002L
        )
        assertTrue(
            "an episode whose air day is still ahead is unaired, so it drops",
            tracker.droppedFromRail(hiddenKeys = emptySet(), todayEpochDay = 20_000L)
        )
        assertTrue(
            "an episode airing TODAY is still unaired - the rail's rule is 'today or later'",
            tracker.droppedFromRail(hiddenKeys = emptySet(), todayEpochDay = 20_002L)
        )
        assertFalse(
            "the morning after it airs the same row proves the opposite and comes back",
            tracker.droppedFromRail(hiddenKeys = emptySet(), todayEpochDay = 20_003L)
        )
    }

    @Test
    fun `the rendered row carries what the carry-forward rules need`() {
        val rowOf = publisher.substringAfter("private fun rowOf(item: UpNextItem)")
            .substringBefore("\n    }\n}")
        assertTrue(
            "the card's own id (tracker-sourced or not)",
            rowOf.contains("cardId = item.id")
        )
        assertTrue(
            "its TMDB show id and history row, the other two hide spellings",
            rowOf.contains("tmdbId = item.tmdbId?.takeIf { it > 0 }") &&
                rowOf.contains("historyRowId = item.historyRowId")
        )
        assertTrue(
            "and its own episode's air day, from the shared rule",
            rowOf.contains("ownEpisodeAirEpochDay = upNextOwnEpisodeAirEpochDay(item)") &&
                homeUpNext.contains("internal fun upNextOwnEpisodeAirEpochDay(item: UpNextItem): Long?")
        )
        assertTrue(
            "all of it survives the round trip to disk",
            json.contains(".put(\"res\", row.isResume)") &&
                json.contains("isResume = node.optBoolean(\"res\", true)") &&
                json.contains("ownEpisodeAirEpochDay = node.optLong(\"air\", -1L)")
        )
    }

    // ── F3: a non-resume row renders honestly ────────────────────────────

    @Test
    fun `the row's resume verdict comes from the rail's one rule`() {
        assertTrue(
            "rendered at publish, like the chip",
            publisher.contains("isResume = upNextIsResumeCard(item)") &&
                homeUpNext.contains("internal fun upNextIsResumeCard(item: UpNextItem): Boolean")
        )
        assertTrue(
            "and both rails draw their cards from the same predicate",
            tvHome.contains("val isResumeCard = upNextIsResumeCard(item)") &&
                phoneHome.contains("val isResumeCard = upNextIsResumeCard(item)")
        )
        assertFalse(
            "neither screen may keep its own copy of the rule",
            tvHome.contains("item.badge == UpNextBadge.CONTINUE_WATCHING ||") ||
                phoneHome.contains("item.badge == UpNextBadge.CONTINUE_WATCHING ||")
        )
    }

    @Test
    fun `a non-resume row draws no bar and no time left`() {
        assertTrue(
            "the bar is hidden for a card nobody started",
            service.contains("if (row.isResume) android.view.View.VISIBLE else android.view.View.GONE")
        )
        assertTrue(
            "no chip is drawn when the rail had none, and none is invented",
            service.contains("if (row.chip.isNullOrBlank()) android.view.View.GONE else android.view.View.VISIBLE")
        )
    }

    // ── F4: the tap follows what the row is ──────────────────────────────

    @Test
    fun `a non-resume row opens its Detail instead of resuming`() {
        assertTrue(
            "the route takes the row's own verdict",
            route.contains("isResume: Boolean = true") &&
                route.contains("pendingTarget = if (!isResume) {")
        )
        assertTrue(
            "and the fill-in carries it, so the router cannot guess",
            service.contains("CwWidgetTap.rowFillIn(row, position)")
        )
    }

    // ── Source ------------------------------------------------------------

    private fun source(relative: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            listOf("", "app/").forEach { prefix ->
                val candidate = File(dir, "${prefix}src/main/java")
                if (candidate.isDirectory) {
                    val file = File(candidate, relative)
                    if (file.isFile) return file.readText()
                    throw AssertionError("source missing: ${file.absolutePath}")
                }
            }
            dir = dir.parentFile
        }
        throw AssertionError("main source root not found")
    }

    private companion object {
        const val WIDGET = "com/kennyb1201/kbstream/widget/"
        const val DATA_WIDGET = "com/kennyb1201/kbstream/data/widget/"
    }
}
