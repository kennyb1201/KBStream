package com.kennyb1201.kbstream.ui.library

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Library screen's deep dive, pinned where it can be seen.
 *
 * Each finding is the same shape: a value that describes ONE profile's pass was
 * carried into the next, or a failure was reported as an answer. The
 * empty-watchlist line has a pure seam and is tested as behaviour; the rest are
 * Compose/ViewModel wiring over an Android context (profile switches, TMDB
 * fan-outs, a D-pad), so they are read out of the source like this tree's other
 * UI contracts. Two findings live beside their own seams instead: the long-press
 * menu's return record in LibraryReturnFocusContractTest, and the premiere/
 * finale ordering in LibraryCalendarTest.
 */
class LibraryDeepDiveContractTest {

    // ── C4 + C6: the watchlist's empty line ──────────────────────────────

    @Test
    fun `the empty watchlist names the state it is in`() {
        assertEquals(
            "the local half being on screen is not the remote half having " +
                "answered, so a first open still reads as a wait",
            "Loading watchlists…",
            libraryWatchlistEmptyText(
                loading = false,
                remoteLoading = true,
                simklConnected = true,
                mdbListConfigured = true,
                traktConnected = true,
                failed = emptySet()
            )
        )

        assertEquals(
            "every configured source answered and there is nothing in it",
            "Watchlist is empty.",
            libraryWatchlistEmptyText(
                loading = false,
                remoteLoading = false,
                simklConnected = true,
                mdbListConfigured = true,
                traktConnected = true,
                failed = emptySet()
            )
        )
    }

    @Test
    fun `a failed fetch is never reported as an empty watchlist`() {
        val text = libraryWatchlistEmptyText(
            loading = false,
            remoteLoading = false,
            simklConnected = true,
            mdbListConfigured = true,
            traktConnected = true,
            failed = setOf(LibraryRemote.SIMKL, LibraryRemote.MDBLIST, LibraryRemote.TRAKT)
        )

        assertFalse(
            "all three configured and all three down is the case this used to " +
                "draw as \"Watchlist is empty.\"",
            text.contains("is empty")
        )
        assertTrue(
            "it says the read failed, and which services could not be reached",
            text == "Watchlist couldn't be loaded — Simkl unreachable · " +
                "MDBList unreachable · Trakt unreachable."
        )
    }

    @Test
    fun `an unconnected service is still named, after the ones that failed`() {
        val text = libraryWatchlistEmptyText(
            loading = false,
            remoteLoading = false,
            simklConnected = false,
            mdbListConfigured = true,
            traktConnected = false,
            failed = setOf(LibraryRemote.MDBLIST)
        )

        assertTrue(
            "one sentence: the reachable reason first, then what was never set up",
            text == "Watchlist couldn't be loaded — MDBList unreachable · " +
                "Simkl not connected · Trakt not connected."
        )
    }

    // ── C1: the badge sets are rebuilt, never accumulated ────────────────

    @Test
    fun `enrichment replaces each row's badge instead of unioning onto it`() {
        val enrich = functionBody(VIEW_MODEL, "private fun enrich(")

        assertTrue(
            "every resolved row carries the keys it could be badged under - its " +
                "own spellings plus the IMDb id this pass resolved, which is a key " +
                "it did not carry in",
            enrich.contains("badgeKeys = item.watchedKeyCandidates() +") &&
                enrich.contains("imdbId?.takeIf { it.isNotBlank() }?.let { \"\$normalizedType::\$it\" }")
        )
        assertTrue(
            "and publishing drops those keys before it files this pass's answer, " +
                "which is what removes a badge no longer earned - a title " +
                "un-watched, or one only the PREVIOUS profile ever finished",
            enrich.contains("watchedKeys -= row.badgeKeys") &&
                enrich.contains("partiallyWatchedKeys -= row.badgeKeys") &&
                enrich.contains("row.watched?.let { watchedKeys += it }") &&
                enrich.contains("row.partiallyWatched?.let { partiallyWatchedKeys += it }")
        )
        assertFalse(
            "the plain unions are what grew one profile's badge set onto another's",
            enrich.contains("watchedKeys = _uiState.value.watchedKeys +") ||
                enrich.contains("partiallyWatchedKeys = _uiState.value.partiallyWatchedKeys +")
        )
        assertTrue(
            "the two sets are seeded from the state, so a row outside this batch " +
                "(a list loading on its own) keeps the badge it already had",
            enrich.contains("val watchedKeys = _uiState.value.watchedKeys.toMutableSet()") &&
                enrich.contains(
                    "val partiallyWatchedKeys = _uiState.value.partiallyWatchedKeys.toMutableSet()"
                )
        )
    }

    // ── C2: the checkmark takes the eye's rule ───────────────────────────

    @Test
    fun `both badges are filtered by title ownership`() {
        val enrich = functionBody(VIEW_MODEL, "private fun enrich(")

        assertTrue(
            "one ownership test for the pair - exempting a badge key this profile " +
                "marked itself, which outranks the rule",
            enrich.contains("val explicitBadgeKeys =") &&
                enrich.contains("explicitBadgeKeys.any { key -> key in explicitWatchedKeys }") &&
                enrich.contains("titleOwnedByAnotherProfile(")
        )
        assertTrue(
            "and both are cleared, so a completion on a sibling profile paints " +
                "nothing here - the checkmark is the stronger claim of the two",
            enrich.contains("watched = null") &&
                enrich.contains("partiallyWatched = null")
        )
        assertTrue(
            "the UNWATCHED toggle then cannot hide a row under a foreign key",
            enrich.indexOf("watched = null") <
                enrich.indexOf("partiallyWatched = null")
        )
    }

    // ── C4 + C6 on the screens ───────────────────────────────────────────

    @Test
    fun `both Library screens draw the shared watchlist sentence`() {
        listOf(SCREEN_TV, SCREEN_MOBILE).forEach { path ->
            val screen = source(path)
            assertTrue(
                "$path must not build its own watchlist copy",
                screen.contains("libraryWatchlistEmptyText(") &&
                    screen.contains("failed = state.failedRemotes")
            )
            assertFalse(
                "$path still spells the old two-state sentence",
                screen.contains("\"Watchlist is empty.")
            )
        }
    }

    // ── C5: the grid belongs to the list it is showing ───────────────────

    @Test
    fun `the tv grid state is keyed on the open personal list`() {
        assertTrue(
            "a scroll offset from list A means nothing in list B",
            source(SCREEN_TV).contains(
                "val gridState = remember(state.filter, state.selectedList?.id)"
            )
        )
    }

    // ── C7: a hidden show leaves the calendar ────────────────────────────

    @Test
    fun `the calendar drops a hidden show before it places a single episode`() {
        val showFor = functionBody(CALENDAR_VM, "private suspend fun calendarShowFor(")

        assertTrue(
            "the calendar's show builder asks the app's hidden-title rule",
            showFor.contains("HiddenTitles.hides(") &&
                showFor.contains("HiddenTitles.keys(appContext)")
        )
        assertTrue(
            "with every spelling the sources use plus the name/year fallback",
            showFor.contains("\"series\",") &&
                showFor.contains("candidateId,") &&
                showFor.contains("\"tmdb:\${detail.id}\"") &&
                showFor.contains("detail.firstAirDate")
        )
        assertTrue(
            "and drops the show, so its episodes never reach a day",
            showFor.contains(") {\n            return null\n        }")
        )
        assertTrue(
            "asked before the season payloads are requested",
            showFor.indexOf("HiddenTitles.hides(") <
                showFor.indexOf("getSeasonEpisodes(")
        )
    }

    // ── S2: Down on the grid's last row stays in the calendar ────────────

    @Test
    fun `down on the last row is consumed even when the day is empty`() {
        val pane = source(CALENDAR_PANE)

        assertTrue(
            "the press is always consumed on the last row",
            pane.contains("event.key == Key.DirectionDown && isLastRow -> {") &&
                pane.contains("if (hasEpisodesBelow) onEnterDayList()")
        )
        assertFalse(
            "the unconsumed fall-through is what let the D-pad search backwards",
            pane.contains("Key.DirectionDown && isLastRow && hasEpisodesBelow")
        )
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private fun source(path: String): String =
        File(findMainSourceRoot(), path).readText()

    private fun findMainSourceRoot(): File {
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

    /** The body of the function starting at [signature], up to its closing brace. */
    private fun functionBody(path: String, signature: String): String {
        val src = source(path)
        val start = src.indexOf(signature)
        assertTrue("source missing function: $signature", start >= 0)
        val brace = src.indexOf('{', start)
        assertTrue("no body for function: $signature", brace >= 0)
        val rest = src.substring(brace + 1)
        val end = Regex("\\n {4}\\S").find(rest)?.range?.first ?: rest.length
        return rest.substring(0, end)
    }

    private companion object {
        const val VIEW_MODEL = "com/kennyb1201/kbstream/ui/library/LibraryViewModel.kt"
        const val SCREEN_TV = "com/kennyb1201/kbstream/ui/library/LibraryScreen.kt"
        const val SCREEN_MOBILE = "com/kennyb1201/kbstream/ui/mobile/LibraryScreenMobile.kt"
        const val CALENDAR_VM =
            "com/kennyb1201/kbstream/ui/library/LibraryCalendarViewModel.kt"
        const val CALENDAR_PANE =
            "com/kennyb1201/kbstream/ui/library/LibraryCalendarPane.kt"
    }
}
