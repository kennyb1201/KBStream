package com.kennyb1201.kbstream.ui.sports

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Sports hub's 2026-10-27 deep dive, as wiring rather than rules.
 *
 * Three of its findings are about what a piece of code is allowed to ASSUME,
 * and each one is only visible in the shape of the code:
 *
 *  1. **The guide index's identity** (F1). The cache is a playlist-channel ->
 *     guide-row mapping, and it was reused for the ViewModel's life on the
 *     premise that "the guide URLs do not change while the hub is open". They
 *     do: Playlist Setup edits Extra EPG, and the Free TV migration adds lines,
 *     while this activity-scoped hub lives on - so a game carried only on the
 *     new source's channels stayed "Not in your playlist" until a restart. The
 *     source set is now part of the key, and a finished import drops it.
 *  2. **The broadcast field shapes** (C7). `espnBroadcasts` read exactly one
 *     shape (`broadcasts[].names[]`), so a league that ships its network in the
 *     per-market listing instead named no network at all, and the network tier
 *     was dead for every game in it.
 *  3. **The entry focus claim** (C10). A LazyRow composes only its viewport, so
 *     a restored selection deep in the tab row did not exist while the claim
 *     ran; its fixed frame window expired and nothing re-ran for that
 *     selection, leaving the first D-pad press to scroll the list instead of
 *     focusing anything.
 *
 * Plus the RSN map's league keys (C12's remaining half is data, and data is
 * checked as data below). The parse itself is behaviour, so it is pinned by
 * real payloads in [com.kennyb1201.kbstream.data.sports.EspnSportsRepositoryTest]
 * instead; there is no TV in CI, so the wiring is read out of the source.
 */
class SportsHubDeepDiveContractTest {

    private val viewModel: String by lazy { flat(VIEW_MODEL) }
    private val screen: String by lazy { flat(SCREEN) }
    private val matcher: String by lazy { flat(MATCHER) }
    private val repository: String by lazy { flat(REPOSITORY) }

    /** A literal `$` in the Kotlin source being asserted about. */
    private val d = '$'

    // ── F1: the guide index's identity ──────────────────────────────

    @Test
    fun `the guide index is keyed on the guide sources it was built from`() {
        assertTrue(
            "the source set is remembered with the index, and the index builder " +
                "is handed it rather than reading it again",
            viewModel.contains("private var guideIndexSourceKey: String? = null") &&
                viewModel.contains("val epgUrls = allEpgUrls()") &&
                viewModel.contains(
                    "if (guideIndexSourceKey == sourceKey) { " +
                        "guideIndexCache?.let { return it } }"
                ) &&
                viewModel.contains("guideChannelIndex(channels, epgUrls)")
        )
        assertTrue(
            "the builder takes the sources as a parameter, so the set it was " +
                "handed and the set the cache claims are the same set",
            viewModel.contains(
                "private suspend fun guideChannelIndex( channels: List<IptvChannel>, " +
                    "epgUrls: List<String>, ): Map<String, IptvChannel> { " +
                    "if (epgUrls.isEmpty()) return emptyMap()"
            )
        )
        assertFalse(
            "the unconditional reuse - the assumption that the guide URLs are " +
                "fixed while the hub is open - is gone",
            viewModel.contains("{ guideIndexCache?.let { return it } val built =") ||
                viewModel.contains("guideChannelIndex(channels)")
        )
    }

    @Test
    fun `a finished guide import drops the index, before the player test`() {
        val observe = slice(
            viewModel,
            "private fun observeGuideImports()",
            "private fun observeProfileSwitches()"
        )
        val generation = observe.indexOf("guideGeneration++")
        val droppedCache = observe.indexOf("guideIndexCache = null")
        val droppedKey = observe.indexOf("guideIndexSourceKey = null")
        val inFront = observe.indexOf("if (pollPaused ||")
        assertTrue(
            "an import is when the mapping can GROW (a channel whose tvg-id " +
                "resolved to nothing while the guide was empty resolves now), so " +
                "the cache goes with the generation bump - and before the " +
                "\"hub is behind the player\" test, so a pass resumed after the " +
                "player rebuilds it too",
            generation >= 0 &&
                droppedCache > generation &&
                droppedKey > droppedCache &&
                inFront > droppedKey
        )
        assertTrue(
            "and the pass this kicks re-reads the slate, which is what carries " +
                "the rebuild into the matching",
            observe.contains("refresh()")
        )
    }

    // ── C7: every broadcast shape ───────────────────────────────────

    @Test
    fun `the network tier reads every broadcast shape ESPN ships`() {
        val broadcasts = slice(
            repository,
            "private fun espnBroadcasts(",
            "internal fun parseTournamentEventsOrNull("
        )
        assertTrue(
            "the per-market geo listing is a shape a number of leagues ship " +
                "INSTEAD of `broadcasts`, and a league using it named no network " +
                "at all until this read it",
            broadcasts.contains("owner.optJSONArray(\"geoBroadcasts\")?.let { array ->") &&
                broadcasts.contains("listOf(\"shortName\", \"name\").forEach { key ->")
        )
        assertTrue(
            "the summary's bare string is read, and only when it really is a " +
                "string: optString would stringify an object into the list",
            broadcasts.contains("if (owner.opt(\"broadcast\") is String) {")
        )
        assertTrue(
            "both owners are still walked - the event and its first competition, " +
                "which is where the league decides to put them",
            broadcasts.contains("listOfNotNull(event, competition).forEach { owner ->")
        )
        assertFalse(
            "the one-shape read that left `broadcastNames` empty is gone",
            broadcasts.contains("listOfNotNull(event.optJSONArray(\"broadcasts\")")
        )
    }

    // ── C10: the entry focus claim ──────────────────────────────────

    @Test
    fun `a tab selection that composes late still gets the entry claim`() {
        assertTrue(
            "the row reports the moment it puts the selection on screen",
            screen.contains("onSelectionPlaced: () -> Unit = {},") &&
                screen.contains("onSelectionPlaced = { entryFocusPlacements++ },")
        )
        val rowScroll = slice(
            screen,
            "val selectedIndex = leagues.indexOfFirst",
            "LazyRow("
        )
        assertTrue(
            "after scrolling it into view, which is what makes a deep restored " +
                "selection exist at all",
            rowScroll.contains("if (selectedIndex >= 0) state.scrollToItem(selectedIndex)") &&
                rowScroll.contains("onSelectionPlaced()")
        )
        assertTrue(
            "and the claim is re-keyed on that, so its frame window can run " +
                "again for a selection the first pass could not see",
            screen.contains("var entryFocusPlacements by remember { mutableStateOf(0) }") &&
                screen.contains("LaunchedEffect(leagues, selectedPath, entryFocusPlacements) {")
        )
        assertTrue(
            "the claim itself is unchanged: one-shot, retried across frames, and " +
                "read from the request's return value rather than from " +
                "runCatching's always-true isSuccess",
            screen.contains("if (entryFocusPlaced || leagues.isEmpty()) return@LaunchedEffect") &&
                screen.contains("entryFocusPlaced = landed") &&
                screen.contains("while (!landed && attempts < HUB_ENTRY_FOCUS_ATTEMPTS) {")
        )
        assertFalse(
            "and a placement report never becomes a second way to claim focus - " +
                "the row itself never calls requestFocus",
            rowScroll.contains("requestFocus")
        )
    }

    // ── C12: the RSN map's keys, and the league that had none ───────

    @Test
    fun `every RSN key carries its league, and the NHL has entries at all`() {
        val map = slice(
            matcher,
            "val TEAM_RSN: Map<String, List<String>> = mapOf(",
            "fun rsnNetworks("
        )
        val keys = Regex("\"([A-Za-z]+):([A-Z0-9]+)\" to listOf\\(")
            .findAll(map)
            .map { it.groupValues[1].uppercase() to it.groupValues[2] }
            .toList()

        assertTrue("the map is written in keys at all", keys.size >= 40)
        assertTrue(
            "every league the map covers is keyed, and the NHL - which had no " +
                "entries at all, so every regional hockey game fell straight " +
                "through - now names its teams' own networks",
            keys.map { it.first }.toSet().containsAll(listOf("MLB", "NBA", "NFL", "NHL")) &&
                keys.count { it.first == "NHL" } >= 18
        )
        assertEquals(
            "a duplicated key would silently drop one team's networks",
            keys.size,
            keys.distinct().size
        )
        assertTrue(
            "and the lookup builds exactly the key the map is written in - the " +
                "league from the path, the team's own abbreviation",
            matcher.contains("return TEAM_RSN[\"${d}league:${d}abbreviation\"].orEmpty()")
        )
    }

    // ── Source reading ──────────────────────────────────────────────

    private fun slice(source: String, start: String, end: String): String {
        val startIndex = source.indexOf(start)
        assertTrue("$start is missing", startIndex >= 0)
        val endIndex = source.indexOf(end, startIndex + start.length)
        assertTrue("$end must follow $start", endIndex > startIndex)
        return source.substring(startIndex, endIndex)
    }

    private fun flat(relative: String): String {
        val file = File(sourceRoot, relative)
        assertTrue("source missing: $file", file.isFile)
        return file.readText().replace(Regex("\\s+"), " ")
    }

    private val sourceRoot: File by lazy { findSourceRoot() }

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

    private companion object {
        const val VIEW_MODEL = "com/kennyb1201/kbstream/ui/sports/SportsHubViewModel.kt"
        const val SCREEN = "com/kennyb1201/kbstream/ui/sports/SportsHubScreen.kt"
        const val MATCHER = "com/kennyb1201/kbstream/data/sports/SportsChannelMatcher.kt"
        const val REPOSITORY = "com/kennyb1201/kbstream/data/sports/EspnSportsRepository.kt"
    }
}
