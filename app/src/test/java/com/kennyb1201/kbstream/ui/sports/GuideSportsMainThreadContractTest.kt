package com.kennyb1201.kbstream.ui.sports

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Sports beat and the guide grid window keep their bulk work off Main.
 *
 * Spec 2026-11-18, Guide + Sports main-thread work.
 *
 * The beat is a `viewModelScope.launch` loop, so everything `refreshLeagues`
 * called ran on Main - and the ESPN repository only switches to IO around the
 * network, so the `JSONObject`/`JSONArray` parse landed on the frame thread every
 * ~30 s while a game was live (the 60 s live TTL means roughly every other beat
 * re-parses, and college football can pay two or three windows per beat).
 *
 * The grid resolves a window's channel ids in the caller's own body, i.e. on Main
 * once per visible-index change during a fling, with
 * `playlist.channels.firstOrNull { it.id == id }` per requested id over playlists
 * that run to five figures. The already-read set makes repeats cheap after that
 * filter; the filter itself re-ran every time.
 *
 * Both are dispatcher/algorithm shape, so both are pinned as source.
 */
class GuideSportsMainThreadContractTest {

    private val sports = source(SPORTS_VM)
    private val iptv = source(IPTV_VM)
    private val espn = source(ESPN)

    // ── F1: the beat's fetch + parse is off Main ─────────────────────────

    @Test
    fun `the live beat fetches and parses on the IO dispatcher`() {
        val refresh = block(sports, "private suspend fun refreshLeagues(")
        val io = refresh.indexOf("withContext(Dispatchers.IO) {")
        val fetch = refresh.indexOf("fetchSection(league, previous)")
        assertTrue("the beat wraps its fetches in an IO hop", io >= 0)
        assertTrue(
            "and the per-league fetch - which is where the JSON parse happens, " +
                "because the repository only hops for the network itself - is INSIDE it",
            fetch > io
        )
        assertTrue(
            "the initial load's own hop is untouched: the two paths now agree",
            block(sports, "private suspend fun fetchSections(")
                .contains("withContext(Dispatchers.IO) {")
        )
        assertTrue(
            "and the parse really is the caller's: the repository's fetch does not " +
                "move it off whichever thread asked",
            espn.contains("parseScoreboardOrNull(") &&
                !block(espn, "suspend fun scoreboard(").contains("withContext(Dispatchers.")
        )
        assertTrue(
            "the open sheet's summary rides the same beat, so it takes the same hop - " +
                "gameSummary parses on its caller too",
            block(sports, "private suspend fun loadDetailSummary(")
                .contains("withContext(Dispatchers.IO)")
        )
    }

    @Test
    fun `F3 - the cadence, the TTLs and the no-republish guard are unchanged`() {
        assertTrue(
            "the beat is still 30 s",
            source(POLL_RULES).contains("const val POLL_INTERVAL_MS = 30_000L") &&
                sports.contains("delay(SportsLivePollRules.POLL_INTERVAL_MS)")
        )
        assertTrue(
            "the live TTL is still 60 s",
            espn.contains("LIVE_TTL_MS = 60_000L")
        )
        val refresh = block(sports, "private suspend fun refreshLeagues(")
        assertTrue(
            "and a poll that changed nothing still does not republish",
            refresh.contains("if (updated != _sections.value) {") &&
                refresh.contains("_sections.value = updated")
        )
        assertTrue(
            "with the merge keeping the previous instance for untouched leagues",
            refresh.contains("_sections.value.map { fresh[it.league.path] ?: it }")
        )
        assertTrue(
            "the match pass still runs after the merge",
            refresh.contains("resolveMatches(updated)")
        )
    }

    // ── F2: ids resolve through a map, not a scan ────────────────────────

    @Test
    fun `the grid resolves window ids against a per-playlist map`() {
        assertFalse(
            "no per-id scan of the playlist's channels is left in the request path",
            block(iptv, "fun requestGuideGridWindow(")
                .contains("playlist.channels.firstOrNull { it.id == id }")
        )
        assertTrue(
            "the request looks its rows up instead",
            block(iptv, "fun requestGuideGridWindow(").contains("channelIndexFor(playlist)")
        )
        val index = block(iptv, "private fun channelIndexFor(")
        assertTrue(
            "the map is built once per playlist INSTANCE, which is the version: " +
                "the list is immutable and every change replaces it",
            index.contains("if (guideIndexSource === playlist) return guideChannelIndex") &&
                index.contains("guideIndexSource = playlist")
        )
        assertTrue(
            "and it keeps the FIRST channel per id - the row `firstOrNull` answered " +
                "with - so a playlist naming an id twice resolves as before",
            index.contains("if (channel.id !in built) built[channel.id] = channel")
        )
        assertTrue(
            "built under a lock, because it is replaced rather than mutated",
            iptv.contains("@Synchronized\n    private fun channelIndexFor(")
        )
    }

    @Test
    fun `F3 - the dedup and the missing filter still decide what is read`() {
        val request = block(iptv, "fun requestGuideGridWindow(")
        assertTrue(
            "the already-read set still gates the request",
            request.contains("gridWindowKey(channel.id, windowStart, windowEnd) !in gridWindowReads")
        )
        assertTrue(
            "hidden channels are still dropped AFTER resolution, not by the map",
            request.contains(".filter { it.id !in hiddenIds }")
        )
        assertTrue(
            "the per-request cap is still applied to the resolved rows",
            request.contains(".take(MAX_GUIDE_CHANNELS_PER_REQUEST)")
        )
        assertTrue(
            "a channel the playlist does not carry is still simply absent, which is " +
                "what the missing filter reports",
            request.contains("val missing = wanted.filter { channel ->") &&
                request.contains("requestedIds = missingIds")
        )
    }

    @Test
    fun `the guide's live-score feed is not part of this`() {
        assertTrue(
            "it is already IO-scoped, which is why the spec leaves it alone",
            source(LIVE_SCORES).contains("Dispatchers.IO")
        )
    }

    // ── helpers ──────────────────────────────────────────────────────────

    /** One function's source, by matching its declaration and balancing braces. */
    private fun block(source: String, declaration: String): String {
        val at = source.indexOf(declaration)
        require(at >= 0) { "missing: $declaration" }
        val bodyStart = source.indexOf("{", at)
        require(bodyStart > at) { "no body: $declaration" }
        var depth = 0
        for (i in bodyStart until source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(at, i + 1)
                }
            }
        }
        error("unbalanced: $declaration")
    }

    private fun source(relative: String): String {
        val prefixes = listOf("", "app/")
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            prefixes.forEach { prefix ->
                val candidate =
                    File(dir, "${prefix}src/main/java/com/kennyb1201/kbstream/$relative")
                if (candidate.isFile) return candidate.readText()
            }
            dir = dir.parentFile
        }
        throw AssertionError("source not found above ${System.getProperty("user.dir")}: $relative")
    }

    private companion object {
        const val SPORTS_VM = "ui/sports/SportsHubViewModel.kt"
        const val IPTV_VM = "ui/iptv/IptvViewModel.kt"
        const val ESPN = "data/sports/EspnSportsRepository.kt"
        const val LIVE_SCORES = "data/sports/LiveScoreFeed.kt"
        const val POLL_RULES = "ui/sports/SportsLivePollRules.kt"
    }
}
